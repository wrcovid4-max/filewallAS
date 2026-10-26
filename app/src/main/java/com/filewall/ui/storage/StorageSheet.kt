package com.filewall.ui.storage

import com.filewall.ui.common.InfoIcon
import androidx.compose.ui.Alignment
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
                    "Shows how full your phone, SD card and any connected USB drive (HDD or SSD) are, and how your vault's space splits by type. Nothing here is uploaded.",
                    Modifier.padding(start = 4.dp),
                )
            }

            // Every mounted storage device: the phone, an SD card, and USB drives (HDD / SSD / flash).
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Storage devices",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                StorageDrivesList()
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
