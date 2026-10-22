package com.filewall.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.util.UUID

/**
 * Keeps audio and video playing in the background, with a media notification on the lock screen,
 * the notification shade, headphones and Bluetooth controls (play, pause, seek, next).
 */
class PlaybackService : MediaSessionService() {
    override fun onCreate() {
        super.onCreate()
        PlaybackSession.service = this
        PlaybackSession.session?.let { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackSession.session

    override fun onDestroy() {
        PlaybackSession.service = null
        super.onDestroy()
    }
}

/** Shares the active player with the system media controls. */
object PlaybackSession {
    @Volatile var session: MediaSession? = null
    @Volatile var service: PlaybackService? = null

    /** Makes [player] available to the system media controls, and returns its session. */
    fun publish(context: Context, player: Player): MediaSession {
        val created = MediaSession.Builder(context, player)
            .setId(UUID.randomUUID().toString())
            .build()
        val previous = session
        session = created
        val running = service
        if (running != null) {
            previous?.let { running.removeSession(it) }
            running.addSession(created)
        } else {
            context.startService(Intent(context, PlaybackService::class.java))
        }
        return created
    }

    /** Removes the session from the media controls and releases it. */
    fun release(created: MediaSession) {
        if (session === created) session = null
        service?.removeSession(created)
        created.release()
    }
}
