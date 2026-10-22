package com.filewall.ui.viewer

import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import com.filewall.ui.ai.AskAiFab
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import com.filewall.ui.ai.AiChatSheet
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import com.filewall.model.VaultItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.FileInputStream
import kotlin.coroutines.resume

private const val ORIGIN = "https://appassets.androidplatform.net"
private const val VIEWER_URL = "$ORIGIN/assets/web/viewer.html?file=$ORIGIN/doc/current.pdf"

// One log tag for everything that happens in the viewer: filter Logcat by FileWallDoc.
private const val DOC_TAG = "FileWallDoc"

// Reads the current page number and the page count, as "current/total".
private const val PAGE_STATE_JS =
    "(function(){var a=window.PDFViewerApplication;return a&&a.pdfViewer?(a.page+'/'+a.pagesCount):'';})();"

private fun goToPageScript(page: Int): String =
    "(function(){var a=window.PDFViewerApplication;if(a&&a.pdfViewer){a.page=$page;}})();"

// Acrobat-style colours.
private val BarColor = Color(0xFF1F1F1F)
private val SearchColor = Color(0xFF2B2B2B)
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

// Starts building the marked-up PDF in the page. The result lands in window.__fwSave.
private const val SAVE_PDF_JS =
    "window.__fwSave=null;(function(){var a=window.PDFViewerApplication;" +
        "if(!a||!a.pdfDocument){window.__fwSave='ERR';return;}" +
        "a.pdfDocument.saveDocument().then(function(d){var s='',C=32768;" +
        "for(var i=0;i<d.length;i+=C){s+=String.fromCharCode.apply(null,d.subarray(i,i+C));}" +
        "window.__fwSave=btoa(s);}).catch(function(){window.__fwSave='ERR';});})();"

// Collects the text of every page into window.__fwText, as a JSON array of strings.
private const val EXTRACT_TEXT_JS =
    "window.__fwText=null;(async function(){var a=window.PDFViewerApplication;var out=[];" +
        "var n=a.pdfDocument.numPages;for(var i=1;i<=n;i++){var p=await a.pdfDocument.getPage(i);" +
        "var t=await p.getTextContent();out.push(t.items.map(function(x){return x.str}).join(' '));}" +
        "window.__fwText=JSON.stringify(out);})().catch(function(){window.__fwText='[]';});"

/**
 * Acrobat-style reading view: dark top bar, white page, dark tool sheet and a gradient button.
 *
 * Top bar: back, highlight, search, read aloud, save a marked copy, share, and more.
 * The PDF is decrypted only into the preview cache. Saving writes a new copy into the vault;
 * the original file is never changed.
 */
@Composable
fun PdfJsViewer(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    modifier: Modifier = Modifier,
    onClose: () -> Unit = {},
    saveCopy: suspend (ByteArray, String) -> Boolean = { _, _ -> false },
    fullScreen: Boolean = false,
    onFullScreenChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var showAi by remember(item.id) { mutableStateOf(false) }
    // In full screen the bars stay hidden until the document is tapped.
    var barsVisible by rememberSaveable(item.id) { mutableStateOf(false) }
    val showBars = !fullScreen || barsVisible
    val tapRef = remember(item.id) { arrayOf<() -> Unit>({}) }
    // Ignores a second back press within 0.7 s, so a quick double tap does only one action.
    val lastBack = remember(item.id) { longArrayOf(0L) }
    tapRef[0] = { barsVisible = !barsVisible }
    var searchOpen by remember(item.id) { mutableStateOf(false) }
    var searchText by remember(item.id) { mutableStateOf("") }
    var lastQuery by remember(item.id) { mutableStateOf("") }
    var readAloud by remember(item.id) { mutableStateOf(false) }
    var saving by remember(item.id) { mutableStateOf(false) }
    val viewHolder = remember(item.id) { arrayOfNulls<WebView>(1) }
    var currentPage by remember(item.id) { mutableStateOf(0) }
    var pageCount by remember(item.id) { mutableStateOf(0) }
    fun goToPage(page: Int) {
        val target = page.coerceIn(1, pageCount.coerceAtLeast(1))
        currentPage = target
        viewHolder[0]?.evaluateJavascript(goToPageScript(target), null)
    }
    // System back: leave full screen first, then close, as the back button does.
    // Pre-make this document's questions as soon as it has loaded (only when the global switch is on).
    LaunchedEffect(ready) {
        val key = "doc-${item.id}-${item.updatedAt}"
        if (ready && !item.hidden && com.filewall.data.ai.AiConsent.allAllowed(context) &&
            com.filewall.data.ai.SuggestionCache.get(context, key) == null) {
            val pages = viewHolder[0]?.readPageTexts().orEmpty()
            val text = pages.mapIndexed { i, t -> "=== Page ${i + 1} ===\n$t" }.joinToString("\n\n")
            if (text.isNotBlank()) {
                val questions = com.filewall.ai.GeminiClient.suggestQuestions(text.take(60000)).getOrDefault(emptyList())
                if (questions.isNotEmpty()) com.filewall.data.ai.SuggestionCache.put(context, key, questions)
            }
        }
    }
    BackHandler(enabled = true) { if (fullScreen) onFullScreenChange(false) else onClose() }
    LaunchedEffect(ready) {
        while (ready) {
            val raw = viewHolder[0]?.evalString(PAGE_STATE_JS)
            if (raw != null) {
                val value = JSONTokener(raw).nextValue()
                if (value is String && value.contains('/')) {
                    val parts = value.split('/').map { it.toIntOrNull() ?: 0 }
                    currentPage = parts[0]
                    pageCount = parts[1]
                }
            }
            delay(400)
        }
    }
    val speechHolder = remember(item.id) { arrayOfNulls<TextToSpeech>(1) }

    LaunchedEffect(item.id) {
        val file = withContext(Dispatchers.IO) { runCatching { decryptToCache(item) }.getOrNull() }
        docHandler.publish(file)
        Log.i(DOC_TAG, "open: ${item.name} decrypted=${file != null}")
        failed = file == null
        ready = file != null
    }

    // Read aloud: start when switched on, and stop and release the speech engine on leaving.
    LaunchedEffect(readAloud) {
        if (!readAloud) return@LaunchedEffect
        val webView = viewHolder[0]
        val engine = createSpeech(context)
        if (webView == null || engine == null) {
            readAloud = false
            Toast.makeText(context, "Read aloud is not available", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        speechHolder[0] = engine
        val pages = webView.readPageTexts()
        Log.i(DOC_TAG, "read aloud: ${pages.size} page(s) with text")
        if (pages.all { it.isBlank() }) {
            readAloud = false
            Toast.makeText(context, "This PDF has no text to read", Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        var first = true
        pages.forEach { page ->
            page.chunked(3500).forEach { chunk ->
                engine.speak(chunk, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, null)
                first = false
            }
        }
    }
    DisposableEffect(item.id) {
        onDispose {
            speechHolder[0]?.stop()
            speechHolder[0]?.shutdown()
            speechHolder[0] = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(BarColor)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        if (showAi) {
            AiChatSheet(
                title = "Ask AI",
                subtitle = item.name,
                consentKey = if (item.hidden) null else "doc-${item.id}",
                onGoToPage = { page -> goToPage(page) },
                documentKey = item.id,
                loadContext = { viewHolder[0]?.readPageTexts()?.mapIndexed { i, t -> "=== Page ${i + 1} ===\n$t" }?.joinToString("\n\n").orEmpty() },
                onDismiss = { showAi = false },
            )
        }
        Column(Modifier.fillMaxSize()) {
            // Top bar.
            AnimatedVisibility(
                visible = showBars,
                enter = slideInVertically(animationSpec = tween(260), initialOffsetY = { -it }) + fadeIn(tween(220)),
                exit = slideOutVertically(animationSpec = tween(220), targetOffsetY = { -it }) + fadeOut(tween(180)),
            ) { Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") {
                    val now = System.currentTimeMillis()
                    if (now - lastBack[0] > 700L) {
                        lastBack[0] = now
                        if (fullScreen) onFullScreenChange(false) else onClose()
                    }
                }
                Spacer(Modifier.weight(1f))
                BarIcon(
                    Icons.Filled.Highlight,
                    "Highlight",
                    tint = if (tool == PdfTool.HIGHLIGHT) ToolActiveColor else IconColor,
                ) { tool = toggle(tool, PdfTool.HIGHLIGHT, viewHolder[0]) }
                BarIcon(
                    Icons.Filled.Search,
                    "Search",
                    tint = if (searchOpen) ToolActiveColor else IconColor,
                ) {
                    searchOpen = !searchOpen
                    if (!searchOpen) {
                        searchText = ""
                        lastQuery = ""
                    }
                }
                BarIcon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    "Read aloud",
                    tint = if (readAloud) ToolActiveColor else IconColor,
                ) { readAloud = !readAloud }
                BarIcon(Icons.Filled.Undo, "Undo") { viewHolder[0]?.evaluateJavascript("(function(){var a=window.PDFViewerApplication;var m=a&&a.annotationEditorUIManager;if(m&&m.undo)m.undo();})();", null) }
                BarIcon(Icons.Filled.Save, "Save marked copy") {
                    if (!saving && ready) {
                        saving = true
                        scope.launch {
                            val bytes = viewHolder[0]?.readPdfBytes()
                            Log.i(DOC_TAG, "save: marked copy bytes=${bytes?.size ?: -1}")
                            if (bytes == null) {
                                Toast.makeText(context, "Could not save the markup", Toast.LENGTH_SHORT).show()
                            } else {
                                val name = item.name.substringBeforeLast('.') + " (marked).pdf"
                                val ok = saveCopy(bytes, name)
                                Toast.makeText(
                                    context,
                                    if (ok) "Saved as $name" else "Could not save the copy",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                            saving = false
                        }
                    }
                }
                BarIcon(Icons.Filled.Share, "Share") {
                    scope.launch { shareDocument(context, decryptToCache, item) }
                }
                Box {
                    BarIcon(Icons.Filled.MoreVert, "More") { showMore = true }
                    MoreMenu(
                        expanded = showMore,
                        layout = layout,
                        onDismiss = { showMore = false },
                        onSelect = { selected ->
                            layout = selected
                            Log.i(DOC_TAG, "layout: $selected")
                            viewHolder[0]?.evaluateJavascript(layoutScript(selected), null)
                            showMore = false
                        },
                    )
                }
            } }

            // Search bar, shown under the top bar when search is on.
            if (searchOpen && showBars) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SearchColor)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        singleLine = true,
                        textStyle = TextStyle(color = IconColor, fontSize = 15.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            Log.i(DOC_TAG, "search: \"$searchText\" previous=false")
                            viewHolder[0]?.evaluateJavascript(
                                findScript(searchText, previous = false, again = searchText == lastQuery),
                                null,
                            )
                            lastQuery = searchText
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        viewHolder[0]?.evaluateJavascript(
                            findScript(searchText, previous = true, again = searchText == lastQuery),
                            null,
                        )
                        lastQuery = searchText
                    }) {
                        Icon(Icons.Filled.KeyboardArrowUp, "Previous match", tint = IconColor)
                    }
                    IconButton(onClick = {
                        viewHolder[0]?.evaluateJavascript(
                            findScript(searchText, previous = false, again = searchText == lastQuery),
                            null,
                        )
                        lastQuery = searchText
                    }) {
                        Icon(Icons.Filled.KeyboardArrowDown, "Next match", tint = IconColor)
                    }
                    IconButton(onClick = {
                        searchOpen = false
                        searchText = ""
                        lastQuery = ""
                    }) {
                        Icon(Icons.Filled.Close, "Close search", tint = IconColor)
                    }
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
                                // A tap on the document toggles the bars. The PDF still gets every touch.
                                val tapDetector = android.view.GestureDetector(
                                    ctx,
                                    object : android.view.GestureDetector.SimpleOnGestureListener() {
                                        override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                                            tapRef[0]()
                                            return false
                                        }
                                    },
                                )
                                setOnTouchListener { _, event ->
                                    tapDetector.onTouchEvent(event)
                                    false
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

                // Page number, shown in full screen.
                if (fullScreen && ready && !failed && pageCount > 0) {
                    PageNumberPill(
                        current = currentPage,
                        total = pageCount,
                        onPrevious = { goToPage(currentPage - 1) },
                        onNext = { goToPage(currentPage + 1) },
                        onGoTo = { page -> goToPage(page) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
                    )
                }

                // Ask AI sphere, bottom-left of the page, clear of the Draw button.
                if (ready && !failed) {
                    AskAiFab(
                        onClick = { showAi = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 16.dp, bottom = 12.dp),
                    )
                }

                // Gradient action button, as in Acrobat: it turns the Draw tool on and off.
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
                            .clickable { tool = toggle(tool, PdfTool.DRAW, viewHolder[0]) },
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

            // Bottom tool sheet: the Acrobat actions, minus Edit PDF.
            AnimatedVisibility(
                visible = showBars,
                enter = slideInVertically(animationSpec = tween(260), initialOffsetY = { it }) + fadeIn(tween(220)),
                exit = slideOutVertically(animationSpec = tween(220), targetOffsetY = { it }) + fadeOut(tween(180)),
            ) { Column(
                Modifier
                    .fillMaxWidth()
                    .background(BarColor)
                    .padding(top = 10.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GrabHandle(fullScreen = false, onChange = onFullScreenChange)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    SheetTool(Icons.Filled.ChatBubbleOutline, "Comment", tool == PdfTool.COMMENT) {
                        tool = toggle(tool, PdfTool.COMMENT, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.Draw, "Draw", tool == PdfTool.DRAW) {
                        tool = toggle(tool, PdfTool.DRAW, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.DriveFileRenameOutline, "Fill & Sign", tool == PdfTool.FILL_SIGN) {
                        tool = toggle(tool, PdfTool.FILL_SIGN, viewHolder[0])
                    }
                    SheetTool(Icons.Filled.Apps, "More tools", selected = false) { showMore = true }
                }
            } }
            if (fullScreen && !barsVisible) GrabHandle(fullScreen = true, onChange = onFullScreenChange)
        }
    }
}

@Composable
private fun PageNumberPill(
    current: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var hadFocus by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) focus.requestFocus()
    }
    fun commit() {
        draft.toIntOrNull()?.let(onGoTo)
        editing = false
    }
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xCC000000))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious, enabled = current > 1, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Previous page", tint = Color.White)
        }
        if (editing) {
            // Type a page number and press Done to go there.
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .width(64.dp)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .focusRequester(focus)
                    .onFocusChanged {
                        // Only close once the box has actually had focus, not before it gets it.
                        if (it.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            editing = false
                        }
                    },
            )
        } else {
            Text(
                text = "$current / $total",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clickable {
                        draft = ""
                        hadFocus = false
                        editing = true
                    }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        IconButton(onClick = onNext, enabled = current < total, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Next page", tint = Color.White)
        }
    }
}

/** The grab bar. Drag it down for full screen; drag it back up to leave full screen. */
@Composable
private fun GrabHandle(fullScreen: Boolean, onChange: (Boolean) -> Unit) {
    val threshold = with(LocalDensity.current) { 60.dp.toPx() }
    var drag by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (fullScreen) 28.dp else 20.dp)
            .pointerInput(fullScreen) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onVerticalDrag = { _, amount -> drag += amount },
                    onDragEnd = {
                        if (!fullScreen && drag > threshold) onChange(true)
                        if (fullScreen && drag < -threshold) onChange(false)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 40.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF5F6368)),
        )
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
    Log.i(DOC_TAG, "tool: $next")
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
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun SheetTool(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (selected) ToolActiveColor else IconColor
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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

/** Shares the document through the app's file provider. Runs only when the user taps Share. */
private suspend fun shareDocument(context: Context, decryptToCache: suspend (VaultItem) -> File, item: VaultItem) {
    runCatching {
        val file = decryptToCache(item)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Log.i(DOC_TAG, "share: opened the share sheet for ${item.name}")
    }.onFailure { Log.e(DOC_TAG, "share failed: ${item.name}", it) }
}

/** Reads a value from the page. Null when the page returns nothing usable. */
private suspend fun WebView.evalString(js: String): String? = suspendCancellableCoroutine { cont ->
    evaluateJavascript(js) { result -> if (cont.isActive) cont.resume(result) }
}

/** Builds the marked-up PDF inside the page and returns its bytes, or null if it fails. */
private suspend fun WebView.readPdfBytes(): ByteArray? {
    evaluateJavascript(SAVE_PDF_JS, null)
    repeat(600) {
        val raw = evalString("window.__fwSave") ?: return null
        val value = JSONTokener(raw).nextValue()
        when (value) {
            is String -> return if (value == "ERR") null else Base64.decode(value, Base64.DEFAULT)
            JSONObject.NULL, null -> delay(100)
            else -> return null
        }
    }
    return null
}

/** Reads the text of every page, one string per page. Empty when the PDF has no text. */
private suspend fun WebView.readPageTexts(): List<String> {
    evaluateJavascript(EXTRACT_TEXT_JS, null)
    repeat(600) {
        val raw = evalString("window.__fwText") ?: return emptyList()
        val value = JSONTokener(raw).nextValue()
        when (value) {
            is String -> {
                val array = JSONArray(value)
                return List(array.length()) { array.getString(it).trim() }
            }
            JSONObject.NULL, null -> delay(100)
            else -> return emptyList()
        }
    }
    return emptyList()
}

/** Starts the phone's text-to-speech engine and waits until it is ready. Null if unavailable. */
private suspend fun createSpeech(context: Context): TextToSpeech? {
    val ready = CompletableDeferred<Boolean>()
    val engine = TextToSpeech(context) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
    return if (ready.await()) {
        engine
    } else {
        engine.shutdown()
        null
    }
}

/** Find text in the PDF. Uses the engine's own search, so matches are highlighted in the page. */
private fun findScript(query: String, previous: Boolean, again: Boolean): String {
    val text = JSONObject.quote(query)
    val type = if (again) "again" else ""
    return "(function(){var a=window.PDFViewerApplication;if(!a||!a.eventBus)return;" +
        "a.eventBus.dispatch('find',{source:window,type:'$type',query:$text,caseSensitive:false," +
        "entireWord:false,highlightAll:true,findPrevious:$previous,matchDiacritics:false});})();"
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

/**
 * Serves the one document being viewed. The engine starts loading before the file is ready;
 * a request for the file waits here until it has been decrypted.
 */
private class PdfDocHandler : WebViewAssetLoader.PathHandler {
    private val ready = CountDownLatch(1)
    @Volatile private var file: File? = null

    fun publish(decrypted: File?) {
        file = decrypted
        ready.countDown()
    }

    override fun handle(path: String): WebResourceResponse? {
        ready.await(60, TimeUnit.SECONDS)
        val f = file ?: return null
        return runCatching { WebResourceResponse("application/pdf", null, FileInputStream(f)) }.getOrNull()
    }
}
