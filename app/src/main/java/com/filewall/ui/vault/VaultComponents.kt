@file:OptIn(ExperimentalFoundationApi::class)

package com.filewall.ui.vault

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filewall.R
import com.filewall.data.media.ThumbnailStore
import com.filewall.model.FolderWithCount
import com.filewall.model.SortField
import com.filewall.model.VaultItem
import com.filewall.ui.common.ThumbnailImage
import com.filewall.ui.common.TypeBadge
import com.filewall.ui.common.stringResourceSafe
import com.filewall.ui.theme.FolderPalette
import com.filewall.util.formatBytes

/** Count, sort control, direction, list/grid switch and the selection toggle. */
@Composable
fun VaultToolbar(
    itemCount: Int,
    sortField: SortField,
    sortAscending: Boolean,
    gridView: Boolean,
    selectionMode: Boolean,
    onSortField: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleView: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left group flexes: the trailing icon buttons are laid out at their fixed size
        // first, and whatever width is left goes here. The sort label truncates before it
        // can ever push the "select" button off the right edge (the clipping we saw).
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResourceSafe(R.string.item_count, itemCount),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))

            Box(Modifier.weight(1f, fill = false)) {
                TextButton(onClick = { sortMenuOpen = true }) {
                    Icon(Icons.Filled.Sort, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        sortField.label(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(
                            text = { Text(field.label()) },
                            trailingIcon = {
                                if (field == sortField) Icon(Icons.Filled.Check, null)
                            },
                            onClick = {
                                onSortField(field)
                                sortMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        // Compact trailing controls (40dp taps) so all three always fit on narrow screens.
        IconButton(onClick = onToggleDirection, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onToggleView, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (gridView) Icons.Filled.ViewList else Icons.Filled.GridView,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }
        IconButton(onClick = onToggleSelection, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = if (selectionMode) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selectionMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun SortField.label(): String = stringResourceSafe(
    when (this) {
        SortField.DATE_ADDED -> R.string.sort_date_added
        SortField.NAME -> R.string.sort_name
        SortField.SIZE -> R.string.sort_size
        SortField.TYPE -> R.string.sort_type
    },
)

// Folder tiles are deliberately compact so more fit across the row.
private val FolderCardWidth = 124.dp
private val FolderCardHeight = 112.dp

/** The dashed "+ New Folder" tile that opens the folder grid. */
@Composable
fun NewFolderCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.size(width = FolderCardWidth, height = FolderCardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResourceSafe(R.string.new_folder),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A system tile for Recently Deleted / Archive, sized to match the folder cards. */
@Composable
fun SpecialFolderCard(
    title: String,
    count: Int,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.size(width = FolderCardWidth, height = FolderCardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (count > 0) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
        }
    }
}

/**
 * A folder tile. When the folder holds a previewable file it shows that file's thumbnail as
 * a cover (like the home-page tiles); otherwise it falls back to the tinted folder icon.
 */
@Composable
fun FolderCard(
    entry: FolderWithCount,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onRecolor: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    coverItem: VaultItem? = null,
    thumbnails: ThumbnailStore? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val index = entry.folder.colorIndex
    val hasCover = coverItem != null && thumbnails != null

    Card(
        modifier = modifier.size(width = FolderCardWidth, height = FolderCardHeight),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (hasCover) MaterialTheme.colorScheme.surfaceContainerHighest else FolderPalette.container(index),
        ),
        border = BorderStroke(1.5.dp, FolderPalette.outline(index)),
        onClick = onOpen,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (hasCover) {
                ThumbnailImage(item = coverItem!!, store = thumbnails!!, modifier = Modifier.fillMaxSize())
                // Bottom scrim so the folder name stays readable over any image.
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                            ),
                        ),
                )
            }

            val overflowTint = if (hasCover) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResourceSafe(R.string.action_more),
                        tint = overflowTint,
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResourceSafe(R.string.action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResourceSafe(R.string.action_colour)) },
                        leadingIcon = {
                            Icon(Icons.Filled.Palette, null, tint = FolderPalette.base(index))
                        },
                        onClick = {
                            menuOpen = false
                            onRecolor()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResourceSafe(R.string.action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error)
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }

            if (hasCover) {
                // Name sits along the bottom, over the scrim.
                Text(
                    text = entry.folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 10.dp, end = 34.dp, bottom = 8.dp),
                )
            } else {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = entry.folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (entry.itemCount > 0) {
                Text(
                    text = "${entry.itemCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasCover) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

/** One entry in a file's 3-dot overflow menu. */
data class TileAction(
    val label: String,
    val icon: ImageVector,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** The 3-dot button plus its dropdown, used on both the grid tile and the list row. */
@Composable
private fun TileOverflow(
    actions: List<TileAction>,
    modifier: Modifier = Modifier,
    iconTint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    if (actions.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResourceSafe(R.string.action_more),
                tint = iconTint,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            action.label,
                            color = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    },
                    leadingIcon = {
                        Icon(
                            action.icon,
                            contentDescription = null,
                            tint = if (action.destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    onClick = {
                        open = false
                        action.onClick()
                    },
                )
            }
        }
    }
}

/** Grid tile: square preview, name, type badge, and a selection ring when selecting. */
@Composable
fun FileGridTile(
    item: VaultItem,
    thumbnails: ThumbnailStore,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<TileAction> = emptyList(),
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
        ) {
            ThumbnailImage(item = item, store = thumbnails, modifier = Modifier.fillMaxSize())

            if (!selectionMode && menuActions.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                ) {
                    TileOverflow(actions = menuActions, iconTint = androidx.compose.ui.graphics.Color.White)
                }
            }

            if (selectionMode) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        TypeBadge(item.category)
    }
}

/** List row: compact preview, name, then size and badge. */
@Composable
fun FileListRow(
    item: VaultItem,
    thumbnails: ThumbnailStore,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    menuActions: List<TileAction> = emptyList(),
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.small),
        ) {
            ThumbnailImage(item = item, store = thumbnails, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatBytes(item.sizeBytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        } else {
            TypeBadge(item.category)
            if (menuActions.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                TileOverflow(actions = menuActions)
            }
        }
    }
}
