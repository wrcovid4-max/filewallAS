package com.filewall.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.filewall.shared.WearCategory
import com.filewall.shared.WearItem
import com.filewall.wear.R
import com.filewall.wear.data.WatchRepository
import kotlinx.coroutines.launch
import java.util.Locale

private const val ROUTE_LIST = "list"
private const val ROUTE_DETAIL = "detail/{itemId}"

@Composable
fun WearApp(repository: WatchRepository) {
    val navController = rememberSwipeDismissableNavController()

    FileWallWearTheme {
        SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_LIST) {
            composable(ROUTE_LIST) {
                FileListScreen(
                    repository = repository,
                    onOpen = { item -> navController.navigate("detail/${item.id}") },
                )
            }
            composable(
                route = ROUTE_DETAIL,
                arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
            ) { entry ->
                val itemId = entry.arguments?.getString("itemId").orEmpty()
                DetailScreen(repository = repository, itemId = itemId)
            }
        }
    }
}

@Composable
private fun FileListScreen(
    repository: WatchRepository,
    onOpen: (WearItem) -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) { repository.refresh() }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        when {
            !state.loaded -> CenteredMessage(stringResource(R.string.connecting), spinner = true)

            state.items.isEmpty() && !state.connected ->
                CenteredMessage(stringResource(R.string.no_phone))

            state.items.isEmpty() -> CenteredMessage(stringResource(R.string.empty))

            else -> ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 28.dp),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.storage_summary, formatBytes(state.totalBytes)),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(state.items, key = { it.id }) { item ->
                    FileChip(
                        item = item,
                        repository = repository,
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileChip(
    item: WearItem,
    repository: WatchRepository,
    onClick: () -> Unit,
) {
    val thumbnails by repository.thumbnails.collectAsStateWithLifecycle()
    val bitmap = thumbnails[item.id]

    LaunchedEffect(item.id) {
        if (item.hasThumb) repository.loadThumbnail(item.id)
    }

    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors(),
        icon = {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colors.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = item.category.badge(),
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
            }
        },
        label = {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        secondaryLabel = {
            Text(text = formatBytes(item.sizeBytes), maxLines = 1)
        },
    )
}

/**
 * Routes an opened item to whatever the watch can honestly do with it: photos are shown,
 * everything else is handed back to the phone.
 */
@Composable
private fun DetailScreen(repository: WatchRepository, itemId: String) {
    val state by repository.state.collectAsStateWithLifecycle()
    val item = state.items.firstOrNull { it.id == itemId }

    when (item?.category) {
        WearCategory.PHOTO -> ImageScreen(repository = repository, itemId = itemId)
        null -> CenteredMessage(stringResource(R.string.image_unavailable))
        else -> HandoffScreen(repository = repository, item = item)
    }
}

/**
 * A 1.4" screen cannot usefully play a video or read a PDF, and pulling either across the
 * Bluetooth link would be slow and pointless. Show what we already have — the thumbnail
 * from the manifest — and offer to move the job to the phone.
 */
@Composable
private fun HandoffScreen(repository: WatchRepository, item: WearItem) {
    val thumbnails by repository.thumbnails.collectAsStateWithLifecycle()
    val bitmap = thumbnails[item.id]
    var outcome by remember(item.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val sentText = stringResource(R.string.handoff_sent)
    val failedText = stringResource(R.string.handoff_failed)

    LaunchedEffect(item.id) {
        if (item.hasThumb) repository.loadThumbnail(item.id)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer(alpha = 0.35f),
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (item.category) {
                    WearCategory.VIDEO -> stringResource(R.string.video_on_watch)
                    WearCategory.DOC -> stringResource(R.string.doc_on_watch)
                    else -> stringResource(R.string.file_on_watch)
                },
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Chip(
                onClick = {
                    scope.launch {
                        outcome = if (repository.openOnPhone(item.id)) sentText else failedText
                    }
                },
                colors = ChipDefaults.primaryChipColors(),
                label = {
                    Text(
                        text = outcome ?: stringResource(R.string.open_on_phone),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun ImageScreen(repository: WatchRepository, itemId: String) {
    val images by repository.images.collectAsStateWithLifecycle()
    val state by repository.state.collectAsStateWithLifecycle()
    val bitmap = images[itemId]

    var scale by remember(itemId) { mutableFloatStateOf(1f) }
    var offsetX by remember(itemId) { mutableFloatStateOf(0f) }
    var offsetY by remember(itemId) { mutableFloatStateOf(0f) }
    var timedOut by remember(itemId) { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        repository.requestImage(itemId)
        // Poll briefly: the phone answers by publishing a DataItem, which lands through the
        // listener rather than as a reply we can await.
        repeat(30) {
            if (repository.loadImage(itemId) != null) return@LaunchedEffect
            kotlinx.coroutines.delay(400)
        }
        timedOut = true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(itemId) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = state.items.firstOrNull { it.id == itemId }?.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    ),
            )

            timedOut -> CenteredMessage(stringResource(R.string.image_unavailable))

            else -> CenteredMessage(stringResource(R.string.loading_image), spinner = true)
        }
    }
}

@Composable
private fun CenteredMessage(message: String, spinner: Boolean = false) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun WearCategory.badge(): String = when (this) {
    WearCategory.PHOTO -> "IMG"
    WearCategory.VIDEO -> "VID"
    WearCategory.DOC -> "DOC"
    WearCategory.OTHER -> "FILE"
}

/** Same rendering as the phone, kept local so the watch does not pull in the app module. */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) {
        "${value.toLong()} ${units[unit]}"
    } else {
        String.format(Locale.US, "%.1f %s", value, units[unit])
    }
}
