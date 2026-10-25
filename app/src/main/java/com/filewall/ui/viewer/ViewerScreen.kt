package com.filewall.ui.viewer

import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import com.filewall.ui.common.InfoIcon
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filewall.R
import com.filewall.model.FileCategory
import com.filewall.model.VaultItem
import com.filewall.ui.common.stringResourceSafe
import com.filewall.ui.theme.ViewerPurple
import com.filewall.util.formatBytes
import com.filewall.util.formatTimestamp

/**
 * Full-screen item view: the purple stage, the pinch-zoomable image, and the Item Details
 * sheet with Export / Move / Rename / Delete.
 *
 * Non-images have no in-app renderer; they get handed to whatever app the user already
 * trusts with that type, via a cache copy that the lock controller wipes.
 */
@Composable
fun ViewerScreen(
    item: VaultItem,
    loadImage: suspend (VaultItem) -> ImageBitmap?,
    videoPlayer: @Composable (Modifier) -> Unit,
    documentViewer: @Composable (Modifier) -> Unit = {},
    pdfViewer: @Composable (Modifier, Boolean, (Boolean) -> Unit) -> Unit,
    onClose: () -> Unit,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenExternally: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var image by remember(item.id) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(item.id) { mutableStateOf(item.category == FileCategory.PHOTO) }
    var detailsVisible by remember { mutableStateOf(true) }
    // PDF only: false while the reader's tools are hidden, so the floating buttons hide with them.
    var pdfToolsVisible by remember(item.id) { mutableStateOf(true) }

    var scale by remember(item.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(item.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(item.id) { mutableFloatStateOf(0f) }
    val animatedScale by animateFloatAsState(targetValue = scale, label = "zoom")
    val imageAlpha by animateFloatAsState(
        targetValue = if (image != null) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "imageFade",
    )

    // Anything with an in-app preview can be pinched: photos, PDFs and video. External docs
    // (NoPreview) have nothing to zoom.
    val zoomable = item.category == FileCategory.PHOTO ||
        item.category == FileCategory.VIDEO ||
        item.isPdf

    LaunchedEffect(item.id) {
        if (item.category == FileCategory.PHOTO) {
            image = loadImage(item)
            loading = false
        }
    }

    Surface(color = ViewerPurple, modifier = modifier.fillMaxSize()) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val stageHeight = this.maxHeight
        // A Column, not a Box overlay: the top bar, the content, and the details sheet are
        // siblings stacked top-to-bottom, so the sheet can never sit *on top of* the content
        // or the controls. Toggling fullscreen just removes the bar and sheet, and the
        // content expands to fill the freed space.
        // For PDFs the whole column scrolls, so the toolbar, the document and the details move together.
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // -------------------------------------------------------- top bar
            // PDFs have their own bar with back, so this one (and its second back button) is hidden.
            AnimatedVisibility(visible = detailsVisible && !item.isPdf) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResourceSafe(R.string.close),
                            tint = Color.White,
                        )
                    }
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    IconButton(onClick = onExport) {
                        Icon(
                            Icons.Filled.SaveAlt,
                            contentDescription = stringResourceSafe(R.string.action_export),
                            tint = Color.White,
                        )
                    }
                }
            }

            // ---------------------------------------------------------- stage
            Box(
                Modifier
                    .height(
                        if (item.isPdf) {
                            // The PDF reader fills exactly the space the screen gives it. It pads for the
                            // status and navigation bars itself, so nothing is subtracted here (doing so
                            // left a blank band at the bottom and cut the last page short).
                            stageHeight
                        } else {
                            LocalConfiguration.current.screenHeightDp.dp * 0.6f
                        }
                    )
                    .fillMaxWidth()
                    .pointerInput(item.id, zoomable) {
                        if (!zoomable) return@pointerInput
                        // Pinch (2 fingers) zooms and pans; once zoomed in, a single finger
                        // pans too. At 1x a single finger is left untouched so a PDF's page
                        // list and the video controls still scroll/seek.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.count { it.pressed }
                                when {
                                    pressed >= 2 -> {
                                        scale = (scale * event.calculateZoom()).coerceIn(1f, 6f)
                                        val pan = event.calculatePan()
                                        if (scale > 1f) {
                                            offsetX += pan.x
                                            offsetY += pan.y
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                    pressed == 1 && scale > 1f -> {
                                        val pan = event.calculatePan()
                                        offsetX += pan.x
                                        offsetY += pan.y
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    .pointerInput(item.id, zoomable) {
                        // Double-tap to zoom in/out (not on video — the player owns taps there).
                        if (!zoomable || item.category == FileCategory.VIDEO) return@pointerInput
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> CircularProgressIndicator(color = Color.White)

                    image != null -> Image(
                        bitmap = image!!,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = animatedScale,
                                scaleY = animatedScale,
                                alpha = imageAlpha,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )

                    (item.category == FileCategory.VIDEO || item.isMedia) -> videoPlayer(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = animatedScale,
                                scaleY = animatedScale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ),
                    )

                    item.isReadable -> documentViewer(Modifier.fillMaxSize())

                    item.isPdf -> pdfViewer(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = animatedScale,
                                scaleY = animatedScale,
                                translationX = offsetX,
                                translationY = offsetY,
                            ), !detailsVisible,
                    ) { pdfToolsVisible = it }

                    else -> NoPreview(onOpenExternally)
                }

                // Controls live inside the stage box, pinned bottom-right — always above the
                // details sheet because the sheet is a separate row below this whole box.
                if (!item.isPdf || pdfToolsVisible) Row(
                    Modifier
                        .zIndex(1f)
                        .align(Alignment.BottomEnd)
                        .then(if (item.isPdf) Modifier.navigationBarsPadding() else Modifier)
                        .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = if (item.isPdf) 72.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (zoomable) {
                        StageButton(
                            icon = Icons.Filled.ZoomIn,
                            description = stringResourceSafe(R.string.zoom),
                            onClick = {
                                // Tap-to-zoom cycles rather than toggling, so 2x stays one-handed.
                                scale = when {
                                    scale < 1.5f -> 2f
                                    scale < 2.5f -> 3f
                                    else -> 1f
                                }
                                if (scale == 1f) {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                            },
                        )
                    }
                    StageButton(
                        icon = if (detailsVisible) Icons.Filled.Fullscreen else Icons.Filled.FullscreenExit,
                        description = stringResourceSafe(R.string.fullscreen),
                        onClick = { detailsVisible = !detailsVisible },
                    )
                }
            }

            // --------------------------------------------------- details card
            AnimatedVisibility(visible = detailsVisible) {
                ItemDetailsCard(
                    item = item,
                    onExport = onExport,
                    onMove = onMove,
                    onRename = onRename,
                    onDelete = onDelete,
                    scrollable = false,
                )
            }
        }
    }
    }
}

@Composable
private fun StageButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black.copy(alpha = 0.40f)),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
            Icon(icon, contentDescription = description, tint = Color.White.copy(alpha = 0.9f))
        }
    }
}

@Composable
private fun ItemDetailsCard(
    item: VaultItem,
    onExport: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
) {
    // Laid out like Google Drive's file details: preview, title, labelled fields, protection.
    Column(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .then(if (scrollable) Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()) else Modifier)
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))

        OverviewField("Type", item.typeLabel())
        OverviewField("Location", if (item.hidden) "Hidden vault" else "FileWall vault")
        Row(Modifier.fillMaxWidth()) {
            OverviewField("Size", formatBytes(item.sizeBytes), Modifier.weight(1f))
            OverviewField(
                "Category",
                item.category.name.lowercase().replaceFirstChar { it.uppercase() },
                Modifier.weight(1f),
            )
        }
        OverviewField("Created", formatTimestamp(item.addedAt))
        OverviewField("Modified", if (item.updatedAt > 0) formatTimestamp(item.updatedAt) else "—")

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Encrypted on this device",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    InfoIcon(
                        "Encrypted on this device",
                        "Your files are encrypted with a key kept in this phone's secure hardware. They are not uploaded unless you back up, export or share them.",
                        Modifier.padding(start = 4.dp),
                    )
                }
                Text(
                    "Only you can open this file",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResourceSafe(R.string.action_export), maxLines = 1)
            }
            OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                Icon(Icons.AutoMirrored.Filled.DriveFileMove, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResourceSafe(R.string.action_move), maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRename, modifier = Modifier.weight(1f)) {
                Text(stringResourceSafe(R.string.action_rename), maxLines = 1)
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                Text(stringResourceSafe(R.string.action_delete), maxLines = 1)
            }
        }
    }
}

/** One labelled field in the overview: a grey label above the value. */
@Composable
private fun OverviewField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(bottom = 14.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Label takes only what it needs; the value gets the rest and wraps inside it.
        // Previously the value had no width bound, so a long filename ran off the right
        // edge and its last characters (".pdf") were clipped.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NoPreview(onOpenExternally: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResourceSafe(R.string.cannot_preview),
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onOpenExternally) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResourceSafe(R.string.open_externally))
        }
    }
}

@Composable
private fun VaultItem.typeLabel(): String = stringResourceSafe(
    when (category) {
        FileCategory.PHOTO -> R.string.type_image
        FileCategory.VIDEO -> R.string.type_video
        FileCategory.DOC -> R.string.type_doc
        FileCategory.OTHER -> R.string.type_other
    },
)
