package com.filewall.ui.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.filewall.R
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

/** Names the user gave to drives, kept by volume id so they survive unplugging and re-plugging. */
private object DriveNames {
    private const val FILE = "storage_names"
    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()
    }
    fun save(context: Context, id: String, name: String?) {
        val e = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
        if (name.isNullOrBlank()) e.remove(id) else e.putString(id, name.trim())
        e.apply()
    }
}

/** One bar per storage device: phone storage, SD card, USB HDD / SSD. */
@Composable
fun StorageDrivesList(modifier: Modifier = Modifier) {
    val drives = rememberStorageDrives()
    val context = LocalContext.current
    val names = remember { mutableStateMapOf<String, String>().apply { putAll(DriveNames.load(context)) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        drives.forEach { drive ->
            DriveRow(drive, names[drive.id]) { newName ->
                DriveNames.save(context, drive.id, newName)
                if (newName.isNullOrBlank()) names.remove(drive.id) else names[drive.id] = newName.trim()
            }
        }
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
private fun DriveRow(drive: StorageDrive, customName: String?, onRename: (String?) -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    val shownName = customName ?: drive.name
    if (renaming) {
        var text by remember { mutableStateOf(shownName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.storage_rename_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(40) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.storage_rename_label)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { onRename(text); renaming = false }) { Text(stringResource(R.string.storage_rename_save)) }
            },
            dismissButton = {
                Row {
                    if (customName != null) {
                        TextButton(onClick = { onRename(null); renaming = false }) { Text(stringResource(R.string.storage_rename_reset)) }
                    }
                    TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.storage_rename_cancel)) }
                }
            },
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.clickable { renaming = true }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    text = shownName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.storage_rename_title), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
