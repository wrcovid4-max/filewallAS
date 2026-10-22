package com.filewall.ui.viewer

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.navigationBarsPadding
import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.filewall.data.repo.VaultRepository
import com.filewall.model.VaultItem
import kotlinx.coroutines.delay

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr", "mka")
private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "3gp", "mov", "ts")
private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Audio files: played by the same player, with a music artwork in place of the picture. */
val VaultItem.isAudio: Boolean
    get() = extension.lowercase() in AUDIO_EXTENSIONS

/** Anything the media player can open: audio or video. */
val VaultItem.isMedia: Boolean
    get() = isAudio || extension.lowercase() in VIDEO_EXTENSIONS

/** The exact container type for each extension, so the player never has to guess. */
private fun playbackMime(item: VaultItem): String? = when (item.extension.lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/wav"
    "flac" -> "audio/flac"
    "ogg", "oga", "opus" -> "audio/ogg"
    "amr" -> "audio/amr"
    "mka" -> "audio/x-matroska"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "3gp" -> "video/3gpp"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    else -> item.mimeType
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

/**
 * Plays vault video and audio with full controls: play and pause, back and forward 10 seconds,
 * a seek bar, playback speed, and controls that hide while playing. Files are read through
 * [VaultRepository.playbackDataSourceFactory], which decrypts only the parts being played, so
 * the plaintext never has to sit on disk.
 */
@OptIn(UnstableApi::class)
@Composable
fun VaultMediaPlayer(
    item: VaultItem,
    repository: VaultRepository,
    modifier: Modifier = Modifier,
) {
    val context: Context = LocalContext.current
    var player by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }
    var mediaSession by remember(item.id) { mutableStateOf<androidx.media3.session.MediaSession?>(null) }
    var failure by remember(item.id) { mutableStateOf<String?>(null) }
    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var position by remember(item.id) { mutableLongStateOf(0L) }
    var duration by remember(item.id) { mutableLongStateOf(0L) }
    var speed by remember(item.id) { mutableFloatStateOf(1f) }
    var controlsVisible by remember(item.id) { mutableStateOf(true) }
    var scrubbing by remember(item.id) { mutableStateOf<Float?>(null) }
    var videoScale by remember(item.id) { mutableStateOf(1f) }
    var speedMenu by remember(item.id) { mutableStateOf(false) }
    val p = player

    LaunchedEffect(item.id) {
        runCatching {
            // Throws if the blob's integrity check fails, before anything is played.
            val uri = repository.openForPlayback(item)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(repository.playbackDataSourceFactory()))
                .build()
                .apply {
                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            failure = "This file can't be played here. Export it and open it in another player."
                        }
                    })
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(uri)
                            .setMimeType(playbackMime(item))
                            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(item.name).build())
                            .build(),
                    )
                    prepare()
                    playWhenReady = true
                }
        }
            .onSuccess { exo ->
                player = exo
                mediaSession = com.filewall.playback.PlaybackSession.publish(context, exo)
            }
            .onFailure { failure = it.message ?: "Could not open this file" }
    }

    // Keeps the seek bar, the times and the play button in step with the player.
    LaunchedEffect(p) {
        val engine = p ?: return@LaunchedEffect
        while (true) {
            isPlaying = engine.isPlaying
            if (scrubbing == null) position = engine.currentPosition
            duration = engine.duration.takeIf { it > 0 } ?: 0L
            delay(250)
        }
    }

    // While playing, the controls hide themselves after a few seconds.
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && scrubbing == null) {
            delay(3000)
            controlsVisible = false
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            mediaSession?.let { com.filewall.playback.PlaybackSession.release(it) }
            mediaSession = null
            player?.release()
            player = null
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> videoScale = (videoScale * zoom).coerceIn(1f, 4f) } }
            .clickable { controlsVisible = !controlsVisible },
        contentAlignment = Alignment.Center,
    ) {
        when {
            failure != null -> Text(
                text = failure.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )

            p == null -> CircularProgressIndicator(color = Color.White)

            item.isAudio -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFB39DDB),
                    modifier = Modifier.size(140.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }

            else -> AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply { useController = false }
                },
                update = { view -> view.player = p },
                onRelease = { view -> view.player = null },
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = videoScale; scaleY = videoScale },
            )
        }

        // Tap anywhere on the video to show or hide the controls, also while paused.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTransformGestures { _, _, zoom, _ -> videoScale = (videoScale * zoom).coerceIn(1f, 4f) } }
                .clickable { controlsVisible = !controlsVisible },
        )
        if (p != null && failure == null) {
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    val fraction = scrubbing ?: if (duration > 0) position.toFloat() / duration else 0f
                    Slider(
                        value = fraction.coerceIn(0f, 1f),
                        onValueChange = { scrubbing = it },
                        onValueChangeFinished = {
                            scrubbing?.let { f ->
                                val target = (f * duration).toLong()
                                p.seekTo(target)
                                position = target
                            }
                            scrubbing = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFB39DDB),
                            activeTrackColor = Color(0xFFB39DDB),
                        ),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(scrubbing?.let { (it * duration).toLong() } ?: position), color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box {
                            TextButton(onClick = { speedMenu = true }) {
                                Text(speedLabel(speed), color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                SPEEDS.forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text(speedLabel(s)) },
                                        onClick = {
                                            speed = s
                                            p.setPlaybackSpeed(s)
                                            speedMenu = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0L)) }) {
                            Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        IconButton(
                            onClick = { if (p.isPlaying) p.pause() else p.play() },
                            modifier = Modifier.size(56.dp),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                        IconButton(onClick = {
                            val limit = if (duration > 0) duration else Long.MAX_VALUE
                            p.seekTo((p.currentPosition + 10_000).coerceAtMost(limit))
                        }) {
                            Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        }
    }
}
