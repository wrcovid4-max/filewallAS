package com.filewall.ui.common

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.filewall.data.media.ThumbnailStore
import com.filewall.model.FileCategory
import com.filewall.model.VaultItem

/**
 * Tile preview for one vault item.
 *
 * Loading is keyed on the item id rather than the item itself, so renaming or moving a file
 * does not throw away a bitmap that is already decoded and on screen.
 */
@Composable
fun ThumbnailImage(
    item: VaultItem,
    store: ThumbnailStore,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(item.id) { mutableStateOf<ImageBitmap?>(store.peek(item.id)?.asImageBitmap()) }

    LaunchedEffect(item.id, item.thumbName) {
        if (bitmap == null) {
            bitmap = store.load(item)?.asImageBitmap()
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = bitmap, label = "thumbnail") { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = contentScale,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = when (item.category) {
                            FileCategory.VIDEO -> Icons.Filled.PlayCircleFilled
                            FileCategory.DOC -> Icons.Filled.Description
                            else -> Icons.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }

        // Videos keep their play affordance even once the frame has loaded.
        if (item.category == FileCategory.VIDEO && bitmap != null) {
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.size(44.dp),
            )
        }
    }
}
