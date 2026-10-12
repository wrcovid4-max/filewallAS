package com.filewall.ui.viewer

import android.content.Context
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val VIEWER_URL = "$ORIGIN/assets/web/viewer.html?file=$ORIGIN/doc/current.pdf"

// Acrobat-style colours.
private val BarColor = Color(0xFF1F1F1F)
private val SheetColor = Color(0xFF1F1F1F)
private val IconColor = Color(0xFFE8EAED)
private val DimIconColor = Color(0xFF6B6F76)
private val ToolActiveColor = Color(0xFFB39DDB)
private val PageBackground = Color(0xFFFFFFFF)

// PDF.js editor modes: 0 none, 3 free text (comment), 9 highlight, 13 stamp, 15 ink (draw).
private const val MODE_NONE = 0
private const val MODE_COMMENT = 3
private const val MODE_HIGHLIGHT = 9
private const val MODE_FILL_SIGN = 13
private const val MODE_DRAW = 15

// Export controls hidden: the document can be read here but not copied out through the viewer.
// PDF.js's own toolbar is hidden too, because the Acrobat-style bar here replaces it.
private const val HIDE_CHROME_CSS =
    "(function(){var s=document.createElement('style');" +
        "s.textContent='#openFile,#print,#download,#secondaryOpenFile,#secondaryPrint,#secondaryDownload{display:none!important}" +
        "#toolbarContainer,#secondaryToolbar,#sidebarContainer,#findbar{display:none!important}" +
        "#viewerContainer{top:0!important}" +
        "#outerContainer,#viewerContainer{background:#ffffff!important}';" +
        "document.head.appendChild(s);})();"

// Re-fit the pages to the real size of the viewer, so a viewer opened before it is measured
// never shows a blank page.
private const val REFIT_JS =
    "(function(){var a=window.PDFViewerApplication;" +
        "if(a&&a.pdfViewer){a.pdfViewer.currentScaleValue='auto';}" +
        "window.dispatchEvent(new Event('resize'));})();"

/** Acrobat-style reading view: dark top bar, white page, dark tool sheet and a gradient button. */
@Composable
fun PdfJsViewer(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
) {
    val context = LocalContext.current
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
    var tool by rememberSaveable(item.id) { mutableStateOf(PdfTool.NONE) }
    var showMore by remember(item.id) { mutableStateOf(false) }
    val viewHolder = remember(item.id) { arrayOfNulls<WebView>(1) }

    LaunchedEffect(item.id) {
        val file = withContext(Dispatchers.IO) { runCatching { decryptToCache(item) }.getOrNull() }
        docHandler.file = file
        failed = file == null
        ready = file != null
    }

    Box(
        modifier
            .fillMaxSize()
            .background(BarColor)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top bar: back, highlight, and more.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", onClose)
                Spacer(Modifier.weight(1f))
                BarIcon(
                    Icons.Filled.Highlight,
                    "Highlight",
                    tint = if (tool == PdfTool.HIGHLIGHT) ToolActiveColor else IconColor,
                ) { tool = toggle(tool, PdfTool.HIGHLIGHT, viewHolder[0]) }
                Box {
                    BarIcon(Icons.Filled.MoreVert, "More") { showMore = true }
                    MoreMenu(
                        expanded = showMore,
                        layout = layout,
                        onDismiss = { showMore = false },
                        onSelect = { selected ->
                            layout = selected
                            viewHolder[0]?.evaluateJavascript(layoutScript(selected), null)
                            showMore = false
                        },
                    )
                }
            }

            // Document: a white page area, with the PDF engine inside.
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(PageBackground),
            ) {
                when {
                    failed -> Text(
                        text = "This PDF could not be opened",
                        color = Color.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp).align(Alignment.Center),
                    )

                    !ready -> CircularProgressIndicator(
                        color = Color(0xFF5F6368),
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
                                webChromeClient = object : WebChromeClient() {
                                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                                        Log.w(
                                            "FileWallPdf",
                                            "${message.messageLevel()} ${message.message()} " +
                                                "(${message.sourceId()}:${message.lineNumber()})",
                                        )
                                        return true
                                    }
                                }
                                webViewClient = object : WebViewClientCompat() {
                                    override fun shouldInterceptRequest(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

                                    override fun onPageFinished(view: WebView, url: String?) {
                                        view.evaluateJavascript(HIDE_CHROME_CSS, null)
                                        (view.tag as? PdfLayout)?.let { view.evaluateJavascript(layoutScript(it), null) }
                                        view.postDelayed({ view.evaluateJavascript(REFIT_JS, null) }, 400)
                                    }
                                }
                                addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                                        (v as WebView).evaluateJavascript(REFIT_JS, null)
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

                // Gradient action button, as in Acrobat: it opens the tool sheet's first action.
                if (ready && !failed) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                            .size(56.dp)
                            .shadow(6.dp, CircleShape)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFFE8337A), Color(0xFF7B2FF7)),
                                ),
                            )
                            .clickable {
                                tool = toggle(tool, PdfTool.DRAW, viewHolder[0])
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Draw,
                            contentDescription = "Draw",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            // Bottom tool sheet: the same six actions as Acrobat.
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(SheetColor)
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF5F6368)),
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SheetTool(Icons.Filled.Edit, "Edit PDF", enabled = false) {}
                    SheetTool(Icons.Filled.ChatBubbleOutline, "Comment", tool == PdfTool.COMMENT) {
                        tool = toggle(tool, PdfTool.COMMENT, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.Highlight, "Highlight", tool == PdfTool.HIGHLIGHT) {
                        tool = toggle(tool, PdfTool.HIGHLIGHT, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.Draw, "Draw", tool == PdfTool.DRAW) {
                        tool = toggle(tool, PdfTool.DRAW, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.DriveFileRenameOutline, "Fill & Sign", tool == PdfTool.FILL_SIGN) {
                        tool = toggle(tool, PdfTool.FILL_SIGN, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.Apps, "More tools", selected = false) { showMore = true }
                }
            }
        }
    }
}

/** A tool on or off. Turning a tool off returns the viewer to plain reading. */
private fun toggle(current: PdfTool, wanted: PdfTool, view: WebView?): PdfTool {
    val next = if (current == wanted) PdfTool.NONE else wanted
    val mode = when (next) {
        PdfTool.NONE -> MODE_NONE
        PdfTool.COMMENT -> MODE_COMMENT
        PdfTool.HIGHLIGHT -> MODE_HIGHLIGHT
        PdfTool.DRAW -> MODE_DRAW
        PdfTool.FILL_SIGN -> MODE_FILL_SIGN
    }
    view?.evaluateJavascript(editorModeScript(mode), null)
    return next
}

private enum class PdfTool { NONE, COMMENT, HIGHLIGHT, DRAW, FILL_SIGN }

@Composable
private fun BarIcon(
    icon: ImageVector,
    description: String,
    tint: Color = IconColor,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

@Composable
private fun SheetTool(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> DimIconColor
        selected -> ToolActiveColor
        else -> IconColor
    }
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun MoreMenu(
    expanded: Boolean,
    layout: PdfLayout,
    onDismiss: () -> Unit,
    onSelect: (PdfLayout) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (layout == PdfLayout.SINGLE) "✓  Single page" else "    Single page") },
            onClick = { onSelect(PdfLayout.SINGLE) },
        )
        DropdownMenuItem(
            text = { Text(if (layout == PdfLayout.TWO_PAGE) "✓  Two pages" else "    Two pages") },
            onClick = { onSelect(PdfLayout.TWO_PAGE) },
        )
        DropdownMenuItem(
            text = { Text(if (layout == PdfLayout.CONTINUOUS) "✓  Continuous scroll" else "    Continuous scroll") },
            onClick = { onSelect(PdfLayout.CONTINUOUS) },
        )
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
    return "(function apply(n){var a=window.PDFViewerApplication;" +
        "if(!a||!a.pdfViewer){if(n<60)setTimeout(function(){apply(n+1)},150);return;}" +
        "a.pdfViewer.spreadMode=$spread;a.pdfViewer.scrollMode=$scroll;})(0);"
}

/** Switches the PDF engine's editing mode (comment, highlight, draw, and so on). */
private fun editorModeScript(mode: Int): String =
    "(function apply(n){var a=window.PDFViewerApplication;" +
        "if(!a||!a.eventBus){if(n<60)setTimeout(function(){apply(n+1)},150);return;}" +
        "a.eventBus.dispatch('switchannotationeditormode',{source:window,mode:$mode});})(0);"

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

enum class PdfLayout { SINGLE, TWO_PAGE, CONTINUOUS }
