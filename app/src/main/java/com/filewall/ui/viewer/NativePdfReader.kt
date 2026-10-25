package com.filewall.ui.viewer

import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.util.Log
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** The editing tools, as in Acrobat. */
private enum class Tool { NONE, DRAW, RECT, TEXT, SIGN, CROSS, CHECK, DOT }

/** Colors and line thicknesses offered in the drawing toolbar. */
private val INK_COLORS = listOf(0xFFE53935.toInt(), 0xFF000000.toInt(), 0xFF1E88E5.toInt(), 0xFF43A047.toInt())
private val INK_WIDTHS = listOf(1.5f, 3f, 6f)
private const val RENDER_WIDTH = 1080

/**
 * Marks are stored as fractions of the page (0..1 across and down), so they stay put at any size.
 * Colors are ARGB ints, widths are in points.
 */
private sealed interface Mark { val page: Int }
private data class StrokeMark(override val page: Int, val points: List<Offset>, val argb: Int, val width: Float) : Mark
private data class ShapeMark(override val page: Int, val kind: Tool, val at: Offset, val to: Offset, val argb: Int, val width: Float) : Mark
private data class TextMark(override val page: Int, val at: Offset, val text: String, val argb: Int) : Mark
private data class SignMark(override val page: Int, val at: Offset, val strokes: List<List<Offset>>) : Mark

private data class Pending(val page: Int, val at: Offset, val tool: Tool)

private class OpenDoc(val renderer: PdfRenderer, val descriptor: ParcelFileDescriptor, val pageCount: Int) {
    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

private sealed interface PageRender {
    object Loading : PageRender
    object Failed : PageRender
    class Ready(val bitmap: Bitmap) : PageRender
}

private sealed interface Loaded {
    object Loading : Loaded
    object Failed : Loaded
    class Ready(val doc: OpenDoc) : Loaded
}

/**
 * The PDF reader, with Acrobat-style editing: a pencil to draw, a color and a line thickness,
 * an A for text, a cross, a check mark, a dot and a rounded rectangle, a signature, undo, and
 * save as a new file. The original file is never changed.
 */
@Composable
fun NativePdfReader(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    saveCopy: suspend (ByteArray, String) -> Boolean,
    fullScreen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Full screen hides the tools; a tap on the page brings them back, and another tap hides them.
    var chromeVisible by remember(item.id) { mutableStateOf(true) }
    LaunchedEffect(fullScreen) { chromeVisible = !fullScreen }
    val chromeShown = !fullScreen || chromeVisible
    val context = LocalContext.current
    val columnState = rememberLazyListState()
    val rowState = rememberLazyListState()
    var gotoOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var pageTexts by remember(item.id) { mutableStateOf<List<String>?>(null) }
    // Render at twice the screen width, so pages stay sharp when zoomed (capped to protect memory).
    val renderWidth = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp.dp.toPx() * 2f).toInt().coerceIn(1080, 2048)
    }
    var layout by rememberSaveable(item.id) { mutableIntStateOf(2) }        // 0 single, 1 two pages, 2 continuous
    var tool by rememberSaveable(item.id) { mutableStateOf(Tool.NONE) }
    var panelOpen by rememberSaveable(item.id) { mutableStateOf(false) }  // the Acrobat-style second toolbar
    var colorIndex by rememberSaveable(item.id) { mutableIntStateOf(0) }
    var widthIndex by rememberSaveable(item.id) { mutableIntStateOf(1) }
    val marks = remember(item.id) { mutableStateListOf<Mark>() }
    var pending by remember(item.id) { mutableStateOf<Pending?>(null) }
    var status by remember(item.id) { mutableStateOf("") }

    val argb = INK_COLORS[colorIndex]
    val width = INK_WIDTHS[widthIndex]

    val loaded by produceState<Loaded>(Loaded.Loading, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = decryptToCache(item)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor)
                Loaded.Ready(OpenDoc(renderer, descriptor, renderer.pageCount))
            }.onFailure { Log.e("FileWallPdf", "open ${item.name}", it) }.getOrElse { Loaded.Failed }
        }
    }
    DisposableEffect(loaded) {
        // Capture the document this effect opened, so cleanup closes that one and no other.
        val opened = (loaded as? Loaded.Ready)?.doc
        onDispose { opened?.close() }
    }

    val pageCount = (loaded as? Loaded.Ready)?.doc?.pageCount ?: 0
    val currentPage = when (layout) {
        0 -> rowState.firstVisibleItemIndex + 1
        1 -> columnState.firstVisibleItemIndex * 2 + 1
        else -> columnState.firstVisibleItemIndex + 1
    }.coerceIn(1, maxOf(pageCount, 1))

    fun goToPage(page: Int) {
        val target = page.coerceIn(1, maxOf(pageCount, 1))
        scope.launch {
            when (layout) {
                0 -> rowState.scrollToItem(target - 1)
                1 -> columnState.scrollToItem((target - 1) / 2)
                else -> columnState.scrollToItem(target - 1)
            }
        }
    }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            return
        }
        scope.launch {
            searching = true
            PDFBoxResourceLoader.init(context.applicationContext)
            val texts = pageTexts ?: withContext(Dispatchers.IO) {
                extractPageTexts(decryptToCache(item))
            }.also { pageTexts = it }
            results = withContext(Dispatchers.Default) {
                texts.mapIndexedNotNull { index, text ->
                    val at = text.indexOf(q, ignoreCase = true)
                    if (at < 0) null
                    else (index + 1) to text.substring(maxOf(0, at - 30), minOf(text.length, at + q.length + 30)).replace('\n', ' ')
                }
            }
            searching = false
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF202020))) {
    Column(Modifier.fillMaxSize()) {
        // Top: page layout.
        AnimatedVisibility(visible = chromeShown, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
            Text(
                text = "$currentPage / ${maxOf(pageCount, 1)}",
                color = Color.White,
                modifier = Modifier
                    .weight(1f)
                    .clickable { gotoOpen = true }
                    .padding(start = 8.dp, top = 10.dp, bottom = 10.dp),
            )
        }
        }

        if (searchOpen && chromeShown) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search this document") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                )
                TextButton(onClick = { runSearch() }) { Text("Find") }
            }
            if (searching) {
                Text("Searching…", color = Color.White, modifier = Modifier.padding(horizontal = 12.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp).background(Color(0xFF2B2B2B))) {
                items(results) { (page, snippet) ->
                    Text(
                        text = "Page $page: $snippet",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { goToPage(page) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(fullScreen, tool) {
                detectTapGestures { if (fullScreen && tool == Tool.NONE) chromeVisible = !chromeVisible }
            },
            contentAlignment = Alignment.Center,
        ) {
            when (val state = loaded) {
                Loaded.Loading -> CircularProgressIndicator(color = Color.White)
                Loaded.Failed -> Text("This PDF could not be opened.", color = Color.White,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
                is Loaded.Ready -> PagesView(
                    doc = state.doc,
                    renderWidth = renderWidth,
                    columnState = columnState,
                    rowState = rowState,
                    layout = layout,
                    tool = tool,
                    argb = argb,
                    width = width,
                    marks = marks,
                    onStroke = { page, points -> marks.add(StrokeMark(page, points, argb, width)) },
                    onShape = { page, from, to, kind -> marks.add(ShapeMark(page, kind, from, to, argb, width)) },
                    onTap = { page, at, kind ->
                        when (kind) {
                            Tool.TEXT, Tool.SIGN -> pending = Pending(page, at, kind)
                            else -> marks.add(ShapeMark(page, kind, at, at, argb, width))
                        }
                    },
                    // With the top bar hidden, start the first page below the floating search/layout pill.
                    topPadding = if (chromeShown) 8.dp else 60.dp,
                )
            }
        }

        // Hint for the active tool.
        val hint = when (tool) {
            Tool.DRAW -> "Drag on a page to draw."
            Tool.RECT -> "Drag on a page to draw a rectangle."
            Tool.TEXT -> "Tap a spot on a page to add text."
            Tool.SIGN -> "Tap a spot on a page to place your signature."
            Tool.CROSS, Tool.CHECK, Tool.DOT -> "Tap a spot on a page to place it."
            Tool.NONE -> status
        }
        AnimatedVisibility(visible = chromeShown && hint.isNotBlank(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Text(hint, color = Color.White, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        // One bottom bar. Normally the main tools; when Draw or Fill & Sign is on, the
        // Acrobat-style tools take its place (undo and save stay within reach).
        AnimatedVisibility(visible = chromeShown, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        if (!panelOpen) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1F1F1F)).navigationBarsPadding().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolIcon(Icons.Filled.Brush, "Draw") {
                    panelOpen = true
                    tool = Tool.DRAW
                }
                ToolIcon(Icons.Filled.DriveFileRenameOutline, "Fill & Sign") {
                    panelOpen = true
                    tool = Tool.SIGN
                }
                IconButton(onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White)
                }
                SaveButton(loaded, marks, scope, item, saveCopy) { status = it }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2B2B2B)).navigationBarsPadding().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TinyTool(Icons.Filled.Edit, "Pencil", tool == Tool.DRAW) { tool = toggle(tool, Tool.DRAW) }
                IconButton(onClick = { colorIndex = (colorIndex + 1) % INK_COLORS.size }, modifier = Modifier.size(32.dp)) {
                    Box(Modifier.size(18.dp).background(Color(INK_COLORS[colorIndex]), CircleShape))
                }
                IconButton(onClick = { widthIndex = (widthIndex + 1) % INK_WIDTHS.size }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.AutoFixHigh, "Line thickness", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                TinyTool(Icons.Filled.Title, "Add text", tool == Tool.TEXT) { tool = toggle(tool, Tool.TEXT) }
                TinyTool(Icons.Filled.Close, "Cross", tool == Tool.CROSS) { tool = toggle(tool, Tool.CROSS) }
                TinyTool(Icons.Filled.Check, "Check", tool == Tool.CHECK) { tool = toggle(tool, Tool.CHECK) }
                TinyTool(Icons.Filled.FiberManualRecord, "Dot", tool == Tool.DOT) { tool = toggle(tool, Tool.DOT) }
                TinyTool(Icons.Filled.CropSquare, "Rectangle", tool == Tool.RECT) { tool = toggle(tool, Tool.RECT) }
                IconButton(onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, "Undo", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                SaveButton(loaded, marks, scope, item, saveCopy) { status = it }
                IconButton(onClick = { panelOpen = false; tool = Tool.NONE }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Done, "Done", tint = Color(0xFFB39DDB), modifier = Modifier.size(18.dp))
                }
            }
        }
        }
    }

    // Always on top, in every mode (including full screen): search and the page layouts.
    Row(
        Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 4.dp, end = 8.dp)
            .zIndex(1f)
            .background(Color(0xB3000000), RoundedCornerShape(50))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LayoutIcon(Icons.Filled.Search, "Search", searchOpen) { searchOpen = !searchOpen }
        LayoutIcon(Icons.Filled.Description, "Single page", layout == 0) { layout = 0 }
        LayoutIcon(Icons.AutoMirrored.Filled.MenuBook, "Two pages", layout == 1) { layout = 1 }
        LayoutIcon(Icons.Filled.ViewAgenda, "Continuous", layout == 2) { layout = 2 }
    }
    }

    if (gotoOpen) {
        var typed by remember { mutableStateOf(currentPage.toString()) }
        AlertDialog(
            onDismissRequest = { gotoOpen = false },
            title = { Text("Go to page") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.filter(Char::isDigit) },
                    singleLine = true,
                    label = { Text("1 – $pageCount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        typed.toIntOrNull()?.let { goToPage(it) }
                        gotoOpen = false
                    }),
                )
            },
            confirmButton = {
                Button(onClick = {
                    typed.toIntOrNull()?.let { goToPage(it) }
                    gotoOpen = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { gotoOpen = false }) { Text("Cancel") } },
        )
    }

    pending?.let { p ->
        if (p.tool == Tool.TEXT) {
            TextEntryDialog(
                onDismiss = { pending = null },
                onConfirm = { text ->
                    if (text.isNotBlank()) marks.add(TextMark(p.page, p.at, text, argb))
                    pending = null
                },
            )
        } else {
            SignatureDialog(
                onDismiss = { pending = null },
                onConfirm = { strokes ->
                    if (strokes.isNotEmpty()) marks.add(SignMark(p.page, p.at, strokes))
                    pending = null
                },
            )
        }
    }
}

@Composable
private fun TinyTool(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, label, tint = if (selected) Color(0xFFB39DDB) else Color.White, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SaveButton(
    loaded: Loaded,
    marks: List<Mark>,
    scope: kotlinx.coroutines.CoroutineScope,
    item: VaultItem,
    saveCopy: suspend (ByteArray, String) -> Boolean,
    setStatus: (String) -> Unit,
) {
    IconButton(onClick = {
        val ready = (loaded as? Loaded.Ready)?.doc ?: return@IconButton
        scope.launch {
            setStatus("Saving…")
            val bytes = withContext(Dispatchers.IO) { buildEdited(ready, marks.toList()) }
            val base = item.name.substringBeforeLast('.')
            setStatus(if (saveCopy(bytes, "$base (edited).pdf")) "Saved as a new file in the vault." else "Could not save.")
        }
    }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Save, "Save as new file", tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

private fun toggle(current: Tool, wanted: Tool): Tool = if (current == wanted) Tool.NONE else wanted

@Composable
private fun LayoutIcon(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (selected) Color(0xFFB39DDB) else Color.White)
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PagesView(
    doc: OpenDoc,
    renderWidth: Int,
    columnState: LazyListState,
    rowState: LazyListState,
    layout: Int,
    tool: Tool,
    argb: Int,
    width: Float,
    marks: SnapshotStateList<Mark>,
    onStroke: (Int, List<Offset>) -> Unit,
    onShape: (Int, Offset, Offset, Tool) -> Unit,
    onTap: (Int, Offset, Tool) -> Unit,
    topPadding: androidx.compose.ui.unit.Dp = 8.dp,
) {
    val pad = PaddingValues(start = 8.dp, end = 8.dp, top = topPadding, bottom = 72.dp)
    val page: @Composable (Int, Modifier) -> Unit = { index, pageModifier ->
        EditablePage(doc, index, renderWidth, tool, argb, width, marks, onStroke, onShape, onTap, pageModifier)
    }
    when (layout) {
        0 -> LazyRow(state = rowState, modifier = Modifier.fillMaxSize(), contentPadding = pad) {
            items(doc.pageCount) { index -> page(index, Modifier.fillParentMaxWidth()) }
        }
        1 -> LazyColumn(state = columnState, modifier = Modifier.fillMaxSize(), contentPadding = pad) {
            items((doc.pageCount + 1) / 2) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val first = row * 2
                    page(first, Modifier.weight(1f))
                    if (first + 1 < doc.pageCount) page(first + 1, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        else -> LazyColumn(state = columnState, modifier = Modifier.fillMaxSize(), contentPadding = pad) {
            items(doc.pageCount) { index ->
                page(index, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun EditablePage(
    doc: OpenDoc,
    index: Int,
    renderWidth: Int,
    tool: Tool,
    argb: Int,
    width: Float,
    marks: List<Mark>,
    onStroke: (Int, List<Offset>) -> Unit,
    onShape: (Int, Offset, Offset, Tool) -> Unit,
    onTap: (Int, Offset, Tool) -> Unit,
    modifier: Modifier,
) {
    val render by produceState<PageRender>(PageRender.Loading, doc, index, renderWidth) {
        value = withContext(Dispatchers.IO) {
            renderPage(doc, index, renderWidth)?.let { PageRender.Ready(it) } ?: PageRender.Failed
        }
    }
    val bitmap = (render as? PageRender.Ready)?.bitmap
    var live by remember(index) { mutableStateOf<List<Offset>>(emptyList()) }
    var rectFrom by remember(index) { mutableStateOf<Offset?>(null) }
    var rectTo by remember(index) { mutableStateOf<Offset?>(null) }

    if (bitmap == null) {
        Box(modifier.height(420.dp).background(Color.White), contentAlignment = Alignment.Center) {
            if (render == PageRender.Failed) Text("This page could not be drawn.", color = Color.Black)
            else CircularProgressIndicator()
        }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat()).background(Color.White)) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(tool, index) {
                    val norm: (Offset) -> Offset = { o -> Offset(o.x / size.width, o.y / size.height) }
                    when (tool) {
                        Tool.DRAW -> detectDragGestures(
                            onDragStart = { start -> live = listOf(norm(start)) },
                            onDrag = { change, _ ->
                                change.consume()
                                live = live + norm(change.position)
                            },
                            onDragEnd = {
                                if (live.size > 1) onStroke(index, live)
                                live = emptyList()
                            },
                            onDragCancel = { live = emptyList() },
                        )
                        Tool.RECT -> detectDragGestures(
                            onDragStart = { start -> rectFrom = norm(start); rectTo = norm(start) },
                            onDrag = { change, _ ->
                                change.consume()
                                rectTo = norm(change.position)
                            },
                            onDragEnd = {
                                val a = rectFrom
                                val b = rectTo
                                if (a != null && b != null) onShape(index, a, b, Tool.RECT)
                                rectFrom = null
                                rectTo = null
                            },
                            onDragCancel = { rectFrom = null; rectTo = null },
                        )
                        Tool.TEXT, Tool.SIGN, Tool.CROSS, Tool.CHECK, Tool.DOT -> detectTapGestures { tap ->
                            onTap(index, norm(tap), tool)
                        }
                        Tool.NONE -> Unit
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun at(p: Offset) = Offset(p.x * w, p.y * h)
            for (mark in marks) {
                if (mark.page != index) continue
                when (mark) {
                    is StrokeMark -> for (i in 1 until mark.points.size) {
                        drawLine(Color(mark.argb), at(mark.points[i - 1]), at(mark.points[i]), strokeWidth = mark.width)
                    }
                    is ShapeMark -> drawShape(mark.kind, at(mark.at), at(mark.to), Color(mark.argb), mark.width, w)
                    is TextMark -> drawContext.canvas.nativeCanvas.drawText(
                        mark.text, at(mark.at).x, at(mark.at).y,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = mark.argb
                            textSize = 16.dp.toPx()
                        },
                    )
                    is SignMark -> {
                        val origin = at(mark.at)
                        val boxW = w * 0.28f
                        val boxH = h * 0.1f
                        for (stroke in mark.strokes) for (i in 1 until stroke.size) {
                            drawLine(
                                Color(0xFF1A237E),
                                Offset(origin.x + stroke[i - 1].x * boxW, origin.y + stroke[i - 1].y * boxH),
                                Offset(origin.x + stroke[i].x * boxW, origin.y + stroke[i].y * boxH),
                                strokeWidth = 3f,
                            )
                        }
                    }
                }
            }
            for (i in 1 until live.size) drawLine(Color(argb), at(live[i - 1]), at(live[i]), strokeWidth = width)
            val a = rectFrom
            val b = rectTo
            if (a != null && b != null) drawShape(Tool.RECT, at(a), at(b), Color(argb), width, w)
        }
    }
}

/** Draws a cross, check mark, dot or rectangle. Symbols are sized relative to the page width. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShape(
    kind: Tool, at: Offset, to: Offset, color: Color, width: Float, pageWidth: Float,
) {
    val s = pageWidth * 0.03f
    when (kind) {
        Tool.CROSS -> {
            drawLine(color, Offset(at.x - s, at.y - s), Offset(at.x + s, at.y + s), strokeWidth = width)
            drawLine(color, Offset(at.x + s, at.y - s), Offset(at.x - s, at.y + s), strokeWidth = width)
        }
        Tool.CHECK -> {
            drawLine(color, Offset(at.x - s, at.y), Offset(at.x - s / 3, at.y + s), strokeWidth = width)
            drawLine(color, Offset(at.x - s / 3, at.y + s), Offset(at.x + s, at.y - s), strokeWidth = width)
        }
        Tool.DOT -> drawCircle(color, radius = width * 2, center = at)
        Tool.RECT -> {
            val left = minOf(at.x, to.x)
            val top = minOf(at.y, to.y)
            val size = Size(kotlin.math.abs(to.x - at.x), kotlin.math.abs(to.y - at.y))
            drawRoundRect(color, topLeft = Offset(left, top), size = size,
                cornerRadius = CornerRadius(width * 3),
                style = Stroke(width = width))
        }
        else -> Unit
    }
}

@Composable
private fun TextEntryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }) },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SignatureDialog(onDismiss: () -> Unit, onConfirm: (List<List<Offset>>) -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    fun norm(o: Offset) = Offset(o.x / area.width.coerceAtLeast(1), o.y / area.height.coerceAtLeast(1))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw your signature") },
        text = {
            Box(
                Modifier.fillMaxWidth().height(180.dp).background(Color.White)
                    .onSizeChanged { area = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start -> current = listOf(norm(start)) },
                            onDrag = { change, _ ->
                                change.consume()
                                current = current + norm(change.position)
                            },
                            onDragEnd = {
                                if (current.size > 1) strokes.add(current)
                                current = emptyList()
                            },
                            onDragCancel = { current = emptyList() },
                        )
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    for (stroke in strokes + listOf(current)) for (i in 1 until stroke.size) {
                        drawLine(Color(0xFF1A237E),
                            Offset(stroke[i - 1].x * w, stroke[i - 1].y * h),
                            Offset(stroke[i].x * w, stroke[i].y * h),
                            strokeWidth = 4f)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(strokes.toList()) }) { Text("Place signature") } },
        dismissButton = { TextButton(onClick = { strokes.clear() }) { Text("Clear") } },
    )
}

/** Renders one page to a bitmap, one page at a time (PdfRenderer allows a single open page). */
private fun renderPage(doc: OpenDoc, index: Int, renderWidth: Int): Bitmap? = synchronized(doc.renderer) {
    runCatching {
        doc.renderer.openPage(index).use { page ->
            val heightPx = (renderWidth * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            createBitmap(renderWidth, heightPx, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.onFailure { Log.e("FileWallPdf", "render page $index", it) }.getOrNull()
}

/** Writes a new PDF: each page as it is, with the marks drawn on top. */
private fun buildEdited(doc: OpenDoc, marks: List<Mark>): ByteArray = synchronized(doc.renderer) {
    val pdf = PdfDocument()
    for (index in 0 until doc.pageCount) {
        doc.renderer.openPage(index).use { page ->
            val wPt = page.width
            val hPt = page.height
            val bitmap = createBitmap(wPt * 2, hPt * 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val out = pdf.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, index + 1).create())
            out.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), null)
            val wf = wPt.toFloat()
            val hf = hPt.toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (mark in marks) {
                if (mark.page != index) continue
                when (mark) {
                    is StrokeMark -> {
                        paint.color = mark.argb
                        paint.strokeWidth = mark.width
                        paint.style = Paint.Style.STROKE
                        for (i in 1 until mark.points.size) {
                            out.canvas.drawLine(mark.points[i - 1].x * wf, mark.points[i - 1].y * hf,
                                mark.points[i].x * wf, mark.points[i].y * hf, paint)
                        }
                    }
                    is ShapeMark -> {
                        paint.color = mark.argb
                        paint.strokeWidth = mark.width
                        paint.style = Paint.Style.STROKE
                        val ax = mark.at.x * wf; val ay = mark.at.y * hf
                        val bx = mark.to.x * wf; val by = mark.to.y * hf
                        val s = wf * 0.03f
                        when (mark.kind) {
                            Tool.CROSS -> {
                                out.canvas.drawLine(ax - s, ay - s, ax + s, ay + s, paint)
                                out.canvas.drawLine(ax + s, ay - s, ax - s, ay + s, paint)
                            }
                            Tool.CHECK -> {
                                out.canvas.drawLine(ax - s, ay, ax - s / 3, ay + s, paint)
                                out.canvas.drawLine(ax - s / 3, ay + s, ax + s, ay - s, paint)
                            }
                            Tool.DOT -> {
                                paint.style = Paint.Style.FILL
                                out.canvas.drawCircle(ax, ay, mark.width * 2, paint)
                            }
                            Tool.RECT -> out.canvas.drawRoundRect(
                                minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by),
                                mark.width * 3, mark.width * 3, paint)
                            else -> Unit
                        }
                    }
                    is TextMark -> {
                        paint.style = Paint.Style.FILL
                        paint.color = mark.argb
                        paint.textSize = 12f
                        out.canvas.drawText(mark.text, mark.at.x * wf, mark.at.y * hf, paint)
                    }
                    is SignMark -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1.5f
                        paint.color = android.graphics.Color.rgb(26, 35, 126)
                        val ox = mark.at.x * wf
                        val oy = mark.at.y * hf
                        val boxW = wf * 0.28f
                        val boxH = hf * 0.1f
                        for (stroke in mark.strokes) for (i in 1 until stroke.size) {
                            out.canvas.drawLine(ox + stroke[i - 1].x * boxW, oy + stroke[i - 1].y * boxH,
                                ox + stroke[i].x * boxW, oy + stroke[i].y * boxH, paint)
                        }
                    }
                }
            }
            pdf.finishPage(out)
            bitmap.recycle()
        }
    }
    val bytes = ByteArrayOutputStream()
    pdf.writeTo(bytes)
    pdf.close()
    bytes.toByteArray()
}

/** Extracts the text of every page, for search. Empty if the file can't be read as text. */
private fun extractPageTexts(file: File): List<String> = runCatching {
    PDDocument.load(file).use { document ->
        val stripper = PDFTextStripper()
        (1..document.numberOfPages).map { page ->
            stripper.startPage = page
            stripper.endPage = page
            stripper.getText(document)
        }
    }
}.getOrElse { emptyList() }
