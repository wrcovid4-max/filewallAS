package com.filewall.ui.viewer

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.filewall.R
import com.filewall.model.VaultItem
import com.filewall.ui.common.stringResourceSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val VIEWER_URL = "$ORIGIN/assets/web/viewer.html?file=$ORIGIN/doc/current.pdf"

// Export controls hidden: the document can be read here but not copied out through the viewer.
private const val HIDE_EXPORT_CSS =
    "(function(){var s=document.createElement('style');" +
        "s.textContent='#openFile,#print,#download,#secondaryOpenFile,#secondaryPrint,#secondaryDownload{display:none!important}';" +
        "document.head.appendChild(s);})();"

/**
 * In-app PDF viewer built on PDF.js, the engine behind Firefox's PDF reader.
 *
 * Unlike a plain page renderer, this keeps the document live: forms can be filled, links
 * work, and annotations show. The viewer and its engine ship inside the app, so nothing is
 * loaded from the internet. The PDF is decrypted into the preview cache, served to the
 * viewer from memory-backed requests only, and wiped on lock, on background and at launch.
 */
@Composable
fun PdfJsViewer(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val docHandler = remember(item.id) { PdfDocHandler() }
    val loader = remember(item.id) {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", PdfJsAssetHandler(context))
            .addPathHandler("/doc/", docHandler)
            .build()
    }
    var ready by remember(item.id) { mutableStateOf(false) }
    var failed by remember(item.id) { mutableStateOf(false) }
    var layout by rememberSaveable(item.id) { mutableStateOf(PdfLayout.CONTINUOUS) }
    val viewHolder = remember(item.id) { arrayOfNulls<WebView>(1) }

    LaunchedEffect(item.id) {
        val file = withContext(Dispatchers.IO) { runCatching { decryptToCache(item) }.getOrNull() }
        docHandler.file = file
        failed = file == null
        ready = file != null
    }

    Box(modifier.fillMaxSize().background(Color(0xFF202020))) {
        when {
            failed -> Text(
                text = stringResourceSafe(R.string.pdf_failed),
                color = Color.White,
                modifier = Modifier.padding(32.dp).align(Alignment.Center),
            )

            !ready -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.builtInZoomControls = false
                        webViewClient = object : WebViewClientCompat() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                            override fun onPageFinished(view: WebView, url: String?) {
                                view.evaluateJavascript(HIDE_EXPORT_CSS, null)
                                (view.tag as? PdfLayout)?.let { view.evaluateJavascript(layoutScript(it), null) }
                            }
                        }
                        viewHolder[0] = this
                    }
                },
                update = { view ->
                    view.tag = layout
                    if (view.url == null) view.loadUrl(VIEWER_URL)
                },
            )
        }

        // Layout switcher: single page, two pages, continuous. Maps to PDF.js's own view modes.
        if (ready && !failed) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LayoutChoice(Icons.Filled.Description, "Single page", layout == PdfLayout.SINGLE) {
                    layout = PdfLayout.SINGLE
                    viewHolder[0]?.evaluateJavascript(layoutScript(PdfLayout.SINGLE), null)
                }
                LayoutChoice(Icons.Filled.MenuBook, "Two pages", layout == PdfLayout.TWO_PAGE) {
                    layout = PdfLayout.TWO_PAGE
                    viewHolder[0]?.evaluateJavascript(layoutScript(PdfLayout.TWO_PAGE), null)
                }
                LayoutChoice(Icons.Filled.ViewAgenda, "Continuous scroll", layout == PdfLayout.CONTINUOUS) {
                    layout = PdfLayout.CONTINUOUS
                    viewHolder[0]?.evaluateJavascript(layoutScript(PdfLayout.CONTINUOUS), null)
                }
            }
        }
    }
}

/** PDF.js spread and scroll modes: 0 none / 1 odd spreads; 0 vertical / 3 page. */
private fun layoutScript(layout: PdfLayout): String {
    val spread: Int
    val scroll: Int
    when (layout) {
        PdfLayout.SINGLE -> { spread = 0; scroll = 3 }
        PdfLayout.TWO_PAGE -> { spread = 1; scroll = 0 }
        PdfLayout.CONTINUOUS -> { spread = 0; scroll = 0 }
    }
    // The viewer finishes starting up after the page loads, so retry briefly until it is ready.
    return "(function apply(n){var a=window.PDFViewerApplication;" +
        "if(!a||!a.pdfViewer){if(n<60)setTimeout(function(){apply(n+1)},150);return;}" +
        "a.pdfViewer.spreadMode=$spread;a.pdfViewer.scrollMode=$scroll;})(0);"
}

@Composable
private fun LayoutChoice(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

/** Serves the bundled viewer and engine from the app's assets, with correct MIME types. */
private class PdfJsAssetHandler(private val context: Context) : WebViewAssetLoader.PathHandler {
    override fun handle(path: String): WebResourceResponse? {
        if (path.contains("..")) return null
        return runCatching {
            WebResourceResponse(mimeFor(path), "utf-8", context.assets.open("pdfjs/$path"))
        }.getOrNull()
    }

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html"
        "css" -> "text/css"
        "js", "mjs" -> "application/javascript"
        "json" -> "application/json"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "wasm" -> "application/wasm"
        "properties", "ftl", "txt" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/** Serves the one document being viewed, from the decrypted cache copy. */
private class PdfDocHandler : WebViewAssetLoader.PathHandler {
    @Volatile var file: File? = null

    override fun handle(path: String): WebResourceResponse? {
        val f = file ?: return null
        return runCatching { WebResourceResponse("application/pdf", null, FileInputStream(f)) }.getOrNull()
    }
}
