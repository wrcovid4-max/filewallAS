package com.filewall.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.filewall.data.repo.VaultRepository
import com.filewall.model.VaultItem

/**
 * Plays a vault video without ever decrypting it to disk.
 *
 * ExoPlayer reads through [com.filewall.data.media.VaultDataSource], which decrypts the
 * byte ranges the player asks for and nothing else — so scrubbing works, and the plaintext
 * only ever exists in the decoder's buffers.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VaultVideoPlayer(
    item: VaultItem,
    repository: VaultRepository,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }
    var failure by remember(item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.id) {
        runCatching {
            // Throws if the blob's HMAC does not check out — the seeking reader cannot
            // verify integrity itself, so this is the one place it can happen.
            val uri = repository.openForPlayback(item)

            ExoPlayer.Builder(context)
                .setMediaSourceFactory(
                    DefaultMediaSourceFactory(repository.playbackDataSourceFactory()),
                )
                .build()
                .apply {
                    setMediaItem(
                        MediaItem.Builder()
                            // The URI carries no extension, so hand the container type over
                            // explicitly rather than making ExoPlayer sniff for it.
                            .setUri(uri)
                            .setMimeType(item.mimeType)
                            .build(),
                    )
                    prepare()
                    playWhenReady = true
                }
        }
            .onSuccess { player = it }
            .onFailure { failure = it.message ?: "Could not open this video" }
    }

    DisposableEffect(item.id) {
        onDispose {
            player?.release()
            player = null
        }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when {
            failure != null -> Text(
                text = failure.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )

            player == null -> CircularProgressIndicator(color = Color.White)

            else -> AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = { view -> view.player = player },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
