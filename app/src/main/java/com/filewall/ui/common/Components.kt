package com.filewall.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filewall.R
import com.filewall.model.FileCategory
import com.filewall.model.StorageBreakdown
import com.filewall.model.VaultFilter
import com.filewall.ui.theme.DocAmber
import com.filewall.ui.theme.OtherGrey
import com.filewall.ui.theme.PhotoGreen
import com.filewall.ui.theme.VideoBlue
import com.filewall.util.formatBytes

/** The persistent "FileWall / 269.4 MB USED" masthead every tab sits under. */
@Composable
fun VaultHeader(totalBytes: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = stringResourceSafe(R.string.app_name),
                // headlineMedium rather than displaySmall: the masthead was the biggest driver
                // of the "everything is huge" feel.
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResourceSafe(R.string.storage_used, formatBytes(totalBytes)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Rounded card with a hairline outline — the container used by every settings group. */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** The Unlocked / Hidden pill above the file list. */
@Composable
fun VaultFilterToggle(
    selected: VaultFilter,
    onSelect: (VaultFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterHalf(
            modifier = Modifier.weight(1f),
            label = stringResourceSafe(R.string.filter_unlocked),
            icon = { tint -> Icon(Icons.Outlined.Visibility, null, tint = tint, modifier = Modifier.size(20.dp)) },
            active = selected == VaultFilter.UNLOCKED,
            onClick = { onSelect(VaultFilter.UNLOCKED) },
        )
        FilterHalf(
            modifier = Modifier.weight(1f),
            label = stringResourceSafe(R.string.filter_hidden),
            icon = { tint -> Icon(Icons.Filled.Lock, null, tint = tint, modifier = Modifier.size(20.dp)) },
            active = selected == VaultFilter.HIDDEN,
            onClick = { onSelect(VaultFilter.HIDDEN) },
        )
    }
}

@Composable
private fun FilterHalf(
    label: String,
    icon: @Composable (Color) -> Unit,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(content)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, color = content)
    }
}

/** Search box with the magnifier affordance from the original. */
@Composable
fun VaultSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        leadingIcon = {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        placeholder = {
            Text(
                stringResourceSafe(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

/** Title + description on the left, switch on the right. */
@Composable
fun SettingSwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accentThumb: Boolean = false,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
            )
            if (description != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = if (accentThumb) {
                // Matches the inverted thumb used by the two storage toggles in the original.
                SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                )
            } else {
                SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.surface,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedBorderColor = MaterialTheme.colorScheme.primary,
                )
            },
        )
    }
}

@Composable
fun SettingDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 16.dp),
        color = MaterialTheme.colorScheme.outline,
    )
}

/** Storage Breakdown card: total, segmented bar, then the three-dot legend. */
@Composable
fun StorageBreakdownCard(breakdown: StorageBreakdown, modifier: Modifier = Modifier) {
    SectionCard(modifier) {
        Text(
            stringResourceSafe(R.string.storage_breakdown),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            stringResourceSafe(R.string.storage_used_plain, formatBytes(breakdown.totalBytes)),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        StorageBar(breakdown)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LegendEntry(PhotoGreen, stringResourceSafe(R.string.cat_photos), breakdown.photoBytes)
            LegendEntry(VideoBlue, stringResourceSafe(R.string.cat_videos), breakdown.videoBytes)
            LegendEntry(DocAmber, stringResourceSafe(R.string.cat_docs), breakdown.docBytes)
        }
    }
}

@Composable
private fun StorageBar(breakdown: StorageBreakdown, modifier: Modifier = Modifier) {
    val total = breakdown.totalBytes
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(14.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (total <= 0) return@Row
        // Compose rejects a zero weight, so empty categories are dropped rather than sized 0.
        val segments = listOf(
            PhotoGreen to breakdown.photoBytes,
            VideoBlue to breakdown.videoBytes,
            DocAmber to breakdown.docBytes,
            OtherGrey to breakdown.otherBytes,
        ).filter { it.second > 0 }

        segments.forEach { (color, bytes) ->
            Box(
                Modifier
                    .weight(bytes.toFloat() / total)
                    .fillMaxSize()
                    .background(color),
            )
        }
    }
}

@Composable
private fun LegendEntry(color: Color, label: String, bytes: Long) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                formatBytes(bytes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The IMAGE / VIDEO / DOC chip under each tile. */
@Composable
fun TypeBadge(category: FileCategory, modifier: Modifier = Modifier) {
    val (label, tint) = when (category) {
        FileCategory.PHOTO -> stringResourceSafe(R.string.badge_image) to VideoBlue
        FileCategory.VIDEO -> stringResourceSafe(R.string.badge_video) to PhotoGreen
        FileCategory.DOC -> stringResourceSafe(R.string.badge_doc) to DocAmber
        FileCategory.OTHER -> stringResourceSafe(R.string.badge_file) to OtherGrey
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Small indirection so previews and tests can stub resource lookups in one place. */
@Composable
fun stringResourceSafe(id: Int, vararg formatArgs: Any): String =
    androidx.compose.ui.res.stringResource(id, *formatArgs)
