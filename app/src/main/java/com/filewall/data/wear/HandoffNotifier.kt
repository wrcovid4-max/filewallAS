package com.filewall.data.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.filewall.MainActivity
import com.filewall.R
import com.filewall.model.VaultItem

/**
 * Turns a watch's "open this on my phone" tap into a notification the user can act on.
 *
 * A background process cannot start an activity on Android 10+, and a vault that could
 * throw itself open from inside a pocket would be wrong even where the platform allows it.
 * So the watch gets an acknowledgement, and the phone gets a tappable card.
 */
class HandoffNotifier(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.handoff_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.handoff_channel_desc)
            setShowBadge(false)
            // The name of a vault file is the whole secret; never put it on the lock screen.
            lockscreenVisibility = NotificationCompat.VISIBILITY_SECRET
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun notifyOpenRequest(item: VaultItem) {
        ensureChannel()

        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ITEM
            putExtra(EXTRA_ITEM_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(context.getString(R.string.handoff_title))
            .setContentText(item.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        // Silently dropped when the user has not granted POST_NOTIFICATIONS on 13+, which is
        // the correct outcome — there is nothing to fall back to.
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_OPEN_ITEM = "com.filewall.action.OPEN_ITEM"
        const val EXTRA_ITEM_ID = "com.filewall.extra.ITEM_ID"

        private const val CHANNEL_ID = "filewall_handoff"
        private const val NOTIFICATION_ID = 0x5157
    }
}
