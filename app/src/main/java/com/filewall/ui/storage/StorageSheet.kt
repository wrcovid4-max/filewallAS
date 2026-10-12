package com.filewall.ui.storage

import com.filewall.ui.common.InfoIcon
import androidx.compose.ui.Alignment
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.filewall.model.StorageBreakdown
import com.filewall.ui.theme.DocAmber
import com.filewall.ui.theme.OtherGrey
import com.filewall.ui.theme.PhotoGreen
import com.filewall.ui.theme.VideoBlue
import com.filewall.util.formatBytes

/**
 * Storage panel: how full the phone is, and how the vault's space splits by type.
 * Opened from the header; it reads the device's storage directly, so nothing is stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSheet(breakdown: StorageBreakdown, onDismiss: () -> Unit) {
    val device = remember { deviceStorage() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Storage",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                InfoIcon(
                    "Storage",
                    "Shows how full your phone is, and how your vault's space splits by type. Nothing here is uploaded.",
                    Modifier.padding(start = 4.dp),
                )
            }

            // Phone storage: the whole device, the same figure the system settings show.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Phone storage",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                UsageBar(
                    fraction = if (device.total > 0) device.used.toFloat() / device.total else 0f,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatBytes(device.used)} used",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${formatBytes(device.free)} free of ${formatBytes(device.total)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Vault storage: each type as a share of what the vault holds.
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    text = "FileWall vault",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val total = breakdown.totalBytes
                StorageRow("Photos", breakdown.photoBytes, total, PhotoGreen)
                StorageRow("Videos", breakdown.videoBytes, total, VideoBlue)
                StorageRow("Documents", breakdown.docBytes, total, DocAmber)
                StorageRow("Other", breakdown.otherBytes, total, OtherGrey)
            }
        }
    }
}

private data class DeviceStorage(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0L)
}

private fun deviceStorage(): DeviceStorage = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    DeviceStorage(total = stat.totalBytes, free = stat.availableBytes)
}.getOrDefault(DeviceStorage(total = 0L, free = 0L))

@Composable
private fun StorageRow(label: String, bytes: Long, vaultTotal: Long, color: Color) {
    val fraction = if (vaultTotal > 0) bytes.toFloat() / vaultTotal else 0f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatBytes(bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        UsageBar(fraction = fraction, color = color)
    }
}

@Composable
private fun UsageBar(fraction: Float, color: Color) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}
