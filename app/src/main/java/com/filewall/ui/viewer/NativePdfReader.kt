package com.filewall.ui.viewer

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PdfRenderer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filewall.model.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Editing tools. DRAW drags freehand, TEXT and SIGN place an item where the page is tapped. */
private enum class Tool { NONE, DRAW, TEXT, SIGN }

/** Marks are stored as fractions of the page (0..1 across and down), so they stay put at any size. */
private sealed interface Mark { val page: Int }
private data class StrokeMark(override val page: Int, val points: List<Offset>) : Mark
private data class TextMark(override val page: Int, val at: Offset, val text: String) : Mark
private data class SignMark(override val page: Int, val at: Offset, val strokes: List<List<Offset>>) : Mark

private data class Pending(val page: Int, val at: Offset, val tool: Tool)

private class OpenDoc(val renderer: PdfRenderer, val descriptor: ParcelFileDescriptor, val pageCount: Int) {
    fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}

private sealed interface Loaded {
    object Loading : Loaded
    object Failed : Loaded
    class Ready(val doc: OpenDoc) : Loaded
}

/**
 * The PDF reader: page layouts, plus full editing. Draw, add text, sign (drawn once, then placed),
 * undo, and save as a new file in the vault. The original file is never changed.
 */
@Composable
fun NativePdfReader(
    item: VaultItem,
    decryptToCache: suspend (VaultItem) -> File,
    saveCopy: suspend (ByteArray, String) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val widthPx = 1080
    var layout by rememberSaveable(item.id) { mutableStateOf(2) }   // 0 single, 1 two pages, 2 continuous
    var tool by rememberSaveable(item.id) { mutableStateOf(Tool.NONE) }
    val marks = remember(item.id) { mutableStateListOf<Mark>() }
    var pending by remember(item.id) { mutableStateOf<Pending?>(null) }
    var status by remember(item.id) { mutableStateOf("") }

    val loaded by produceState<Loaded>(Loaded.Loading, item.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val file = decryptToCache(item)
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(descriptor)
                Loaded.Ready(OpenDoc(renderer, descriptor, renderer.pageCount))
            }.getOrElse { Loaded.Failed }
        }
    }
    DisposableEffect(loaded) {
        onDispose { (loaded as? Loaded.Ready)?.doc?.close() }
    }

    Column(modifier.fillMaxSize().background(Color(0xFF202020))) {
        // Top: page layout.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = { layout = 0 }) { Icon(Icons.Filled.Description, "Single page", tint = if (layout == 0) Color(0xFFB39DDB) else Color.White) }
            IconButton(onClick = { layout = 1 }) { Icon(Icons.Filled.MenuBook, "Two pages", tint = if (layout == 1) Color(0xFFB39DDB) else Color.White) }
            IconButton(onClick = { layout = 2 }) { Icon(Icons.Filled.ViewAgenda, "Continuous", tint = if (layout == 2) Color(0xFFB39DDB) else Color.White) }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val state = loaded) {
                Loaded.Loading -> CircularProgressIndicator(color = Color.White)
                Loaded.Failed -> Text(
                    "This PDF could not be opened.",
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                is Loaded.Ready -> PagesView(
                    doc = state.doc,
                    layout = layout,
                    widthPx = widthPx,
                    tool = tool,
                    marks = marks,
                    onStroke = { page, points -> marks.add(StrokeMark(page, points)) },
                    onTap = { page, at -> pending = Pending(page, at, tool) },
                )
            }
        }

        // Hint for the active tool.
        val hint = when (tool) {
            Tool.DRAW -> "Drag on a page to draw."
            Tool.TEXT -> "Tap a spot on a page to add text."
            Tool.SIGN -> "Tap a spot on a page to place your signature."
            Tool.NONE -> status
        }
        if (hint.isNotBlank()) {
            Text(hint, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        // Bottom: the tools.
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1F1F1F)).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.Draw, "Draw", tool == Tool.DRAW) { tool = toggle(tool, Tool.DRAW) }
            ToolButton(Icons.Filled.Edit, "Add text", tool == Tool.TEXT) { tool = toggle(tool, Tool.TEXT) }
            ToolButton(Icons.Filled.DriveFileRenameOutline, "Sign", tool == Tool.SIGN) { tool = toggle(tool, Tool.SIGN) }
            IconButton(onClick = { if (marks.isNotEmpty()) marks.removeAt(marks.lastIndex) }) {
                Icon(Icons.Filled.Undo, "Undo", tint = Color.White)
            }
            IconButton(onClick = {
                val ready = (loaded as? Loaded.Ready)?.doc ?: return@IconButton
                scope.launch {
                    status = "Saving…"
                    val bytes = withContext(Dispatchers.IO) { buildEdited(ready, marks.toList()) }
                    val base = item.name.substringBeforeLast('.')
                    status = if (saveCopy(bytes, "$base (edited).pdf")) "Saved as a new file in the vault." else "Could not save."
                }
            }) { Icon(Icons.Filled.Save, "Save as new file", tint = Color.White) }
        }
    }

    pending?.let { p ->
        if (p.tool == Tool.TEXT) {
            TextEntryDialog(
                onDismiss = { pending = null },
                onConfirm = { text ->
                    if (text.isNotBlank()) marks.add(TextMark(p.page, p.at, text))
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

private fun toggle(current: Tool, wanted: Tool): Tool = if (current == wanted) Tool.NONE else wanted

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (selected) Color(0xFFB39DDB) else Color.White)
        Text(label, color = if (selected) Color(0xFFB39DDB) else Color.White, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PagesView(
    doc: OpenDoc,
    layout: Int,
    widthPx: Int,
    tool: Tool,
    marks: SnapshotStateList<Mark>,
    onStroke: (Int, List<Offset>) -> Unit,
    onTap: (Int, Offset) -> Unit,
) {
    val page: @Composable (Int, Modifier) -> Unit = { index, pageModifier ->
        EditablePage(doc, index, widthPx, tool, marks, onStroke, onTap, pageModifier)
    }
    when (layout) {
        0 -> LazyRow(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items(doc.pageCount) { index -> page(index, Modifier.fillParentMaxWidth()) }
        }
        1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
            items((doc.pageCount + 1) / 2) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val first = row * 2
                    page(first, Modifier.weight(1f))
                    if (first + 1 < doc.pageCount) page(first + 1, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
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
    widthPx: Int,
    tool: Tool,
    marks: List<Mark>,
    onStroke: (Int, List<Offset>) -> Unit,
    onTap: (Int, Offset) -> Unit,
    modifier: Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, doc, index, widthPx) {
        value = withContext(Dispatchers.IO) { renderPage(doc, index, widthPx) }
    }
    var live by remember(index) { mutableStateOf<List<Offset>>(emptyList()) }
    val density = LocalDensity.current

    val bmp = bitmap
    if (bmp == null) {
        Box(modifier.height(420.dp).background(Color.White), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    Box(modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height.toFloat()).background(Color.White)) {
        Image(
            bitmap = bmp.asImageBitmap(),
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
                        Tool.TEXT, Tool.SIGN -> detectTapGestures { tap -> onTap(index, norm(tap)) }
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
                        drawLine(Color.Black, at(mark.points[i - 1]), at(mark.points[i]), strokeWidth = 3f)
                    }
                    is TextMark -> drawContext.canvas.nativeCanvas.drawText(
                        mark.text, at(mark.at).x, at(mark.at).y,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.BLACK
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
            for (i in 1 until live.size) {
                drawLine(Color.Red, at(live[i - 1]), at(live[i]), strokeWidth = 3f)
            }
        }
    }
}

@Composable
private fun TextEntryDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add text") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }, singleLine = false)
        },
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
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color.White)
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
private fun renderPage(doc: OpenDoc, index: Int, widthPx: Int): Bitmap? = synchronized(doc.renderer) {
    runCatching {
        doc.renderer.openPage(index).use { page ->
            val heightPx = (widthPx * page.height / page.width.toFloat()).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
                page.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.getOrNull()
}

/** Writes a new PDF: each page as it is, with the marks drawn on top. */
private fun buildEdited(doc: OpenDoc, marks: List<Mark>): ByteArray = synchronized(doc.renderer) {
    val pdf = PdfDocument()
    for (index in 0 until doc.pageCount) {
        doc.renderer.openPage(index).use { page ->
            val wPt = page.width
            val hPt = page.height
            val scale = 2
            val bmp = Bitmap.createBitmap(wPt * scale, hPt * scale, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            val out = pdf.startPage(PdfDocument.PageInfo.Builder(wPt, hPt, index + 1).create())
            out.canvas.drawBitmap(bmp, null, RectF(0f, 0f, wPt.toFloat(), hPt.toFloat()), null)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                strokeWidth = 1.5f
            }
            val wf = wPt.toFloat()
            val hf = hPt.toFloat()
            for (mark in marks) {
                if (mark.page != index) continue
                when (mark) {
                    is StrokeMark -> {
                        paint.style = Paint.Style.STROKE
                        for (i in 1 until mark.points.size) {
                            out.canvas.drawLine(mark.points[i - 1].x * wf, mark.points[i - 1].y * hf,
                                mark.points[i].x * wf, mark.points[i].y * hf, paint)
                        }
                    }
                    is TextMark -> {
                        paint.style = Paint.Style.FILL
                        paint.textSize = 12f
                        out.canvas.drawText(mark.text, mark.at.x * wf, mark.at.y * hf, paint)
                    }
                    is SignMark -> {
                        paint.style = Paint.Style.STROKE
                        paint.color = android.graphics.Color.rgb(26, 35, 126)
                        val ox = mark.at.x * wf
                        val oy = mark.at.y * hf
                        val boxW = wf * 0.28f
                        val boxH = hf * 0.1f
                        for (stroke in mark.strokes) for (i in 1 until stroke.size) {
                            out.canvas.drawLine(ox + stroke[i - 1].x * boxW, oy + stroke[i - 1].y * boxH,
                                ox + stroke[i].x * boxW, oy + stroke[i].y * boxH, paint)
                        }
                        paint.color = android.graphics.Color.BLACK
                    }
                }
            }
            pdf.finishPage(out)
            bmp.recycle()
        }
    }
    val bytes = ByteArrayOutputStream()
    pdf.writeTo(bytes)
    pdf.close()
    bytes.toByteArray()
}
