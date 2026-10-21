package com.filewall.ui.convert

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filewall.data.repo.VaultRepository
import com.filewall.ui.viewer.extractReadableText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** File types this converter can read as text. */
private val TEXT_SOURCES = setOf("txt", "md", "csv", "log", "json", "xml", "html", "htm", "docx", "pptx")

/** Writes plain text to a simple A4 PDF, one line per row, many pages if needed. */
private fun writePdf(text: String, out: File) {
    val doc = PdfDocument()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f }
    val lines = text.lines().flatMap { it.chunked(90).ifEmpty { listOf("") } }.ifEmpty { listOf("") }
    lines.chunked(60).forEachIndexed { i, page ->
        val pdfPage = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
        var y = 50f
        page.forEach { line ->
            pdfPage.canvas.drawText(line, 40f, y, paint)
            y += 14f
        }
        doc.finishPage(pdfPage)
    }
    out.outputStream().use { doc.writeTo(it) }
    doc.close()
}

@Composable
fun ConvertScreen(repository: VaultRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sourceName by remember { mutableStateOf<String?>(null) }
    var sourceUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sourceUri = uri
            sourceName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            status = ""
        }
    }
    fun convert(target: String) {
        val uri = sourceUri ?: return
        val name = sourceName ?: return
        val ext = name.substringAfterLast('.', "").lowercase()
        busy = true
        status = "Converting…"
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "convert_in_${System.currentTimeMillis()}")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    val base = name.substringBeforeLast('.')
                    val out = File(context.cacheDir, "convert_out_${System.currentTimeMillis()}")
                    val text = extractReadableText(tmp, ext)
                    val outName: String
                    val mime: String
                    if (target == "txt") {
                        outName = "$base.txt"
                        mime = "text/plain"
                        out.writeText(text)
                    } else {
                        outName = "$base.pdf"
                        mime = "application/pdf"
                        writePdf(text, out)
                    }
                    repository.importPlaintext(
                        name = outName,
                        mimeType = mime,
                        hidden = false,
                        folderId = null,
                        addedAt = System.currentTimeMillis(),
                        source = out,
                    )
                    out.delete()
                    tmp.delete()
                    "Saved \"$outName\" to your vault."
                }.getOrElse { "Could not convert this file: ${it.message}" }
            }
            busy = false
        }
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Convert", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Converts happen on your phone, free, with no internet. Results are saved to your vault.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(sourceName ?: "Pick a file")
        }
        val ext = sourceName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (sourceName != null) {
            if (ext in TEXT_SOURCES) {
                if (ext != "txt") {
                    Button(onClick = { convert("txt") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Convert to Text (.txt)")
                    }
                }
                Button(onClick = { convert("pdf") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Convert to PDF")
                }
            } else {
                Text(
                    "Not supported yet for .$ext files. Text, Word, PowerPoint and text-based files are supported.",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        HorizontalDivider()
        Text(
            "Coming later: audio and video conversion, and PDF to Word.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
