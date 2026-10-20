package com.filewall.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.zip.ZipFile

val VaultItem.isReadable: Boolean
    get() = extension.lowercase() in setOf("txt", "md", "csv", "log", "json", "xml", "html", "htm", "docx", "doc", "pptx", "ppt")

private val PLAIN_TEXT = setOf("txt", "md", "csv", "log", "json", "xml", "html", "htm")

private fun unescape(s: String): String =
    s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** Pulls the text runs out of Office XML, one line per paragraph. */
private fun officeText(xml: String, runTag: String, paragraphEnd: String): String {
    val run = Regex("<$runTag(?:\\s[^>]*)?>([^<]*)</$runTag>")
    return unescape(xml.split(paragraphEnd).joinToString("\n") { para ->
        run.findAll(para).joinToString("") { it.groupValues[1] }
    }).trim()
}

fun extractReadableText(file: File, ext: String): String = when (ext) {
    "docx" -> ZipFile(file).use { z ->
        val entry = z.getEntry("word/document.xml") ?: return@use "No readable text in this file."
        officeText(z.getInputStream(entry).bufferedReader().readText(), "w:t", "</w:p>")
    }
    "pptx" -> ZipFile(file).use { z ->
        Collections.list(z.entries()).map { it.name }
            .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { Regex("\\d+").find(it.substringAfterLast('/'))?.value?.toInt() ?: 0 }
            .mapIndexed { i, name ->
                "=== Slide ${i + 1} ===\n" +
                    officeText(z.getInputStream(z.getEntry(name)).bufferedReader().readText(), "a:t", "</a:p>")
            }
            .joinToString("\n\n")
    }
    "doc", "ppt" -> "This is an older Office file (.$ext). Export it as " +
        (if (ext == "doc") "Word .docx" else "PowerPoint .pptx") + " to read it here."
    else -> file.readText()
}

@Composable
fun DocumentViewer(item: VaultItem, decryptToCache: suspend (VaultItem) -> File, modifier: Modifier = Modifier) {
    var text by remember(item.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.id) {
        text = withContext(Dispatchers.IO) {
            runCatching { extractReadableText(decryptToCache(item), item.extension.lowercase()) }
                .getOrElse { "This file could not be read: ${it.message}" }
        }
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val shown = text
        if (shown == null) {
            CircularProgressIndicator()
        } else {
            SelectionContainer {
                Text(
                    text = shown,
                    style = if (item.extension.lowercase() in PLAIN_TEXT)
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    else MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                )
            }
        }
    }
}
