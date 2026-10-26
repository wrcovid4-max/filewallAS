package com.filewall.ui.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.filewall.util.DriveKind
import com.filewall.util.StorageDrive
import com.filewall.util.formatBytes
import com.filewall.util.listStorageDrives

/** The mounted drives, refreshed when an SD card or USB drive is plugged in or removed. */
@Composable
fun rememberStorageDrives(): List<StorageDrive> {
    val context = LocalContext.current
    var drives by remember { mutableStateOf(listStorageDrives(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                drives = listStorageDrives(context)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        val registered = runCatching {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(receiver) } }
    }
    return drives
}

/** One bar per storage device: phone storage, SD card, USB HDD / SSD. */
@Composable
fun StorageDrivesList(modifier: Modifier = Modifier) {
    val drives = rememberStorageDrives()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        drives.forEach { drive -> DriveRow(drive) }
        if (drives.none { it.kind != DriveKind.INTERNAL }) {
            Text(
                text = "No SD card or USB drive (HDD / SSD / flash) is connected. Plug one in and it will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DriveRow(drive: StorageDrive) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = drive.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = drive.kind.takeIf { it != DriveKind.INTERNAL && it != DriveKind.OTHER }?.label
                    ?: "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (drive.total > 0) (drive.used.toFloat() / drive.total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (drive.total > 0) {
                Text(
                    text = "${formatBytes(drive.used)} used",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${formatBytes(drive.free)} free of ${formatBytes(drive.total)}" +
                        if (drive.readOnly) " · read-only" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Connected, size not available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
