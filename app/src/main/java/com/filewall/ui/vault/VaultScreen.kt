package com.filewall.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filewall.R
import com.filewall.data.crypto.PinManager
import com.filewall.data.settings.VaultSettings
import com.filewall.model.SpecialView
import com.filewall.model.VaultFilter
import com.filewall.model.VaultItem
import com.filewall.ui.common.VaultFilterToggle
import com.filewall.ui.common.VaultSearchField
import com.filewall.ui.common.stringResourceSafe
import com.filewall.ui.hidden.HiddenGate
import kotlinx.coroutines.launch

/** Dialogs the vault can raise, kept in one place so only one is ever open. */
private sealed interface VaultDialog {
    data object NewFolder : VaultDialog
    data class RenameFolder(val id: String, val name: String) : VaultDialog
    data class ColourFolder(val id: String, val colorIndex: Int) : VaultDialog
    data class DeleteFolder(val id: String, val name: String, val count: Int) : VaultDialog
    data class DeleteAllFolders(val fileCount: Int) : VaultDialog
    data class DeleteItems(val items: List<VaultItem>) : VaultDialog
    data class DeleteForever(val items: List<VaultItem>) : VaultDialog
    data object EmptyTrash : VaultDialog
    data class RenameItem(val item: VaultItem) : VaultDialog
    data class MoveItem(val item: VaultItem) : VaultDialog
}

@Composable
fun VaultScreen(
    viewModel: VaultViewModel,
    pinManager: PinManager,
    settings: VaultSettings,
    onUnlockHidden: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val moveFolders by viewModel.moveFolders.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<VaultDialog?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.import(uris) }

    // Back climbs out of selection, then out of a folder or a special view, before the tab.
    BackHandler(
        enabled = state.openFolder != null || state.selectionMode || state.special != SpecialView.NONE,
    ) {
        when {
            state.selectionMode -> viewModel.clearSelection()
            state.special != SpecialView.NONE -> viewModel.openSpecial(SpecialView.NONE)
            else -> viewModel.openFolder(null)
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                VaultSearchField(query = state.query, onQueryChange = viewModel::setQuery)
                Spacer(Modifier.height(14.dp))
                VaultFilterToggle(selected = state.filter, onSelect = viewModel::setFilter)
            }

            if (state.needsPasscode) {
                HiddenGate(
                    pinManager = pinManager,
                    biometricEnabled = settings.biometricEnabled,
                    disablePasscodeFallback = settings.disablePasscodeFallback,
                    onUnlocked = onUnlockHidden,
                    modifier = Modifier.fillMaxSize(),
                )
                return@Column
            }

            if (state.busy) {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            // While selecting, the selection bar *replaces* the toolbar rather than stacking
            // below it — two full-width bars was the "too much blank space" in the vault.
            if (state.selectionMode) {
                val selectedItems = state.items.filter { it.id in state.selectedIds }
                SelectionActions(
                    count = state.selectedIds.size,
                    special = state.special,
                    inHidden = state.filter == VaultFilter.HIDDEN,
                    onToggleHidden = {
                        viewModel.setHidden(selectedItems, hidden = state.filter != VaultFilter.HIDDEN)
                    },
                    onUnarchive = { viewModel.unarchive(selectedItems) },
                    onRestore = { viewModel.restore(selectedItems) },
                    onDelete = { dialog = VaultDialog.DeleteItems(selectedItems) },
                    onDeleteForever = { dialog = VaultDialog.DeleteForever(selectedItems) },
                    onCancel = viewModel::clearSelection,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                VaultToolbar(
                    itemCount = state.visibleCount,
                    sortField = state.sortField,
                    sortAscending = state.sortAscending,
                    gridView = state.gridView,
                    selectionMode = state.selectionMode,
                    onSortField = { viewModel.setSortField(it) },
                    onToggleDirection = { viewModel.toggleSortDirection() },
                    onToggleView = { viewModel.toggleViewMode() },
                    onToggleSelection = {
                        if (state.selectionMode) viewModel.clearSelection() else viewModel.selectAll()
                    },
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            state.openFolder?.let { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.openFolder(null) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (state.special != SpecialView.NONE) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.openSpecial(SpecialView.NONE) }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                    Text(
                        text = stringResourceSafe(
                            if (state.special == SpecialView.TRASH) {
                                R.string.recently_deleted
                            } else {
                                R.string.archive
                            },
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.special == SpecialView.TRASH && state.items.isNotEmpty() && !state.selectionMode) {
                        TextButton(onClick = { dialog = VaultDialog.EmptyTrash }) {
                            Icon(
                                Icons.Filled.DeleteForever,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResourceSafe(R.string.action_empty),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // During selection the filled bar is its own visual boundary, so the divider
            // would just add an empty band underneath it — a slim spacer keeps it tight.
            if (state.selectionMode) {
                Spacer(Modifier.height(10.dp))
            } else {
                HorizontalDivider(
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (state.gridView) 3 else 1),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.special == SpecialView.NONE &&
                    (state.folders.isNotEmpty() || state.openFolder == null)
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResourceSafe(R.string.section_folders),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.weight(1f))
                            if (state.folders.isNotEmpty()) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            dialog = VaultDialog.DeleteAllFolders(viewModel.countInFolders())
                                        }
                                    },
                                ) {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResourceSafe(R.string.delete_all),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            item {
                                NewFolderCard(onClick = { dialog = VaultDialog.NewFolder })
                            }
                            items(state.folders, key = { it.folder.id }) { entry ->
                                // Cover = a file in this folder that actually has a preview
                                // (newest first); doc-only folders keep the tinted icon.
                                val cover = state.items.firstOrNull {
                                    it.folderId == entry.folder.id && it.thumbName != null
                                }
                                FolderCard(
                                    entry = entry,
                                    onOpen = { viewModel.openFolder(entry.folder.id) },
                                    onRename = {
                                        dialog = VaultDialog.RenameFolder(entry.folder.id, entry.folder.name)
                                    },
                                    onRecolor = {
                                        dialog = VaultDialog.ColourFolder(entry.folder.id, entry.folder.colorIndex)
                                    },
                                    onDelete = {
                                        dialog = VaultDialog.DeleteFolder(
                                            entry.folder.id,
                                            entry.folder.name,
                                            entry.itemCount,
                                        )
                                    },
                                    coverItem = cover,
                                    thumbnails = viewModel.thumbnails,
                                )
                            }
                            // The system folders sit at the far end, after the user's own folders.
                            item {
                                SpecialFolderCard(
                                    title = stringResourceSafe(R.string.recently_deleted),
                                    count = state.trashCount,
                                    icon = Icons.Filled.Delete,
                                    onClick = { viewModel.openSpecial(SpecialView.TRASH) },
                                )
                            }
                            item {
                                SpecialFolderCard(
                                    title = stringResourceSafe(R.string.archive),
                                    count = state.archiveCount,
                                    icon = Icons.Filled.Archive,
                                    onClick = { viewModel.openSpecial(SpecialView.ARCHIVE) },
                                )
                            }
                        }
                    }

                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HorizontalDivider(
                            Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }

                if (state.special == SpecialView.NONE) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResourceSafe(R.string.section_files),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (state.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = when {
                                state.special == SpecialView.TRASH -> stringResourceSafe(R.string.empty_trash)
                                state.special == SpecialView.ARCHIVE -> stringResourceSafe(R.string.empty_archive)
                                state.query.isBlank() -> stringResourceSafe(R.string.empty_vault)
                                else -> stringResourceSafe(R.string.empty_search, state.query)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                        )
                    }
                }

                items(state.items, key = { it.id }) { item ->
                    val selected = item.id in state.selectedIds
                    val onClick = {
                        if (state.selectionMode) viewModel.toggleSelection(item.id) else onOpenItem(item)
                    }
                    val menuActions = when (state.special) {
                        SpecialView.NONE -> listOf(
                            TileAction(stringResourceSafe(R.string.action_rename), Icons.Filled.Edit) {
                                dialog = VaultDialog.RenameItem(item)
                            },
                            TileAction(stringResourceSafe(R.string.action_move), Icons.Filled.DriveFileMove) {
                                dialog = VaultDialog.MoveItem(item)
                            },
                            TileAction(stringResourceSafe(R.string.action_archive), Icons.Filled.Archive) {
                                viewModel.archive(listOf(item))
                            },
                            TileAction(stringResourceSafe(R.string.action_delete), Icons.Filled.Delete, destructive = true) {
                                dialog = VaultDialog.DeleteItems(listOf(item))
                            },
                        )
                        SpecialView.ARCHIVE -> listOf(
                            TileAction(stringResourceSafe(R.string.action_unarchive), Icons.Filled.Unarchive) {
                                viewModel.unarchive(listOf(item))
                            },
                            TileAction(stringResourceSafe(R.string.action_move), Icons.Filled.DriveFileMove) {
                                dialog = VaultDialog.MoveItem(item)
                            },
                            TileAction(stringResourceSafe(R.string.action_delete), Icons.Filled.Delete, destructive = true) {
                                dialog = VaultDialog.DeleteItems(listOf(item))
                            },
                        )
                        SpecialView.TRASH -> listOf(
                            TileAction(stringResourceSafe(R.string.action_restore), Icons.Filled.Restore) {
                                viewModel.restore(listOf(item))
                            },
                            TileAction(
                                stringResourceSafe(R.string.action_delete_forever),
                                Icons.Filled.DeleteForever,
                                destructive = true,
                            ) {
                                dialog = VaultDialog.DeleteForever(listOf(item))
                            },
                        )
                    }
                    if (state.gridView) {
                        FileGridTile(
                            item = item,
                            thumbnails = viewModel.thumbnails,
                            selected = selected,
                            selectionMode = state.selectionMode,
                            onClick = onClick,
                            onLongClick = { viewModel.toggleSelection(item.id) },
                            menuActions = menuActions,
                        )
                    } else {
                        FileListRow(
                            item = item,
                            thumbnails = viewModel.thumbnails,
                            selected = selected,
                            selectionMode = state.selectionMode,
                            onClick = onClick,
                            onLongClick = { viewModel.toggleSelection(item.id) },
                            menuActions = menuActions,
                        )
                    }
                }
            }
        }

        if (!state.needsPasscode && state.special == SpecialView.NONE) {
            FloatingActionButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 24.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    Icons.Filled.FileUpload,
                    contentDescription = stringResourceSafe(R.string.action_add_files),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }

    VaultDialogHost(
        dialog = dialog,
        moveFolders = moveFolders,
        onDismiss = { dialog = null },
        viewModel = viewModel,
    )
}

@Composable
private fun SelectionActions(
    count: Int,
    special: SpecialView,
    inHidden: Boolean,
    onToggleHidden: () -> Unit,
    onUnarchive: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDeleteForever: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A compact, filled bar. Content is centred inside a tinted pill so it reads as one
    // deliberate control instead of a thin line of text floating in a tall, empty row.
    // The primary action and the destructive one change with the view (normal/archive/trash).
    val onContainer = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleSmall,
            color = onContainer,
        )
        Spacer(Modifier.weight(1f))
        when (special) {
            SpecialView.TRASH -> {
                PillLabelButton(Icons.Filled.Restore, stringResourceSafe(R.string.action_restore), onContainer, count > 0, onRestore)
                PillIconButton(Icons.Filled.DeleteForever, stringResourceSafe(R.string.action_delete_forever), MaterialTheme.colorScheme.error, count > 0, onDeleteForever)
            }
            SpecialView.ARCHIVE -> {
                PillLabelButton(Icons.Filled.Unarchive, stringResourceSafe(R.string.action_unarchive), onContainer, count > 0, onUnarchive)
                PillIconButton(Icons.Filled.Delete, stringResourceSafe(R.string.action_delete), MaterialTheme.colorScheme.error, count > 0, onDelete)
            }
            SpecialView.NONE -> {
                PillLabelButton(
                    if (inHidden) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    stringResourceSafe(if (inHidden) R.string.action_unhide else R.string.action_hide),
                    onContainer,
                    count > 0,
                    onToggleHidden,
                )
                PillIconButton(Icons.Filled.Delete, stringResourceSafe(R.string.action_delete), MaterialTheme.colorScheme.error, count > 0, onDelete)
            }
        }
        PillIconButton(Icons.Filled.Close, stringResourceSafe(R.string.action_cancel), onContainer, true, onCancel)
    }
}

@Composable
private fun PillLabelButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = tint),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
private fun PillIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun VaultDialogHost(
    dialog: VaultDialog?,
    moveFolders: List<com.filewall.model.FolderWithCount>,
    onDismiss: () -> Unit,
    viewModel: VaultViewModel,
) {
    when (dialog) {
        null -> Unit

        is VaultDialog.RenameItem -> TextInputDialog(
            title = stringResourceSafe(R.string.action_rename),
            initialValue = dialog.item.name,
            onDismiss = onDismiss,
            onConfirm = { name ->
                viewModel.rename(dialog.item, name)
                onDismiss()
            },
        )

        is VaultDialog.MoveItem -> MoveToFolderDialog(
            folders = moveFolders,
            currentFolderId = dialog.item.folderId,
            onDismiss = onDismiss,
            onPick = { folderId ->
                viewModel.move(dialog.item, folderId)
                onDismiss()
            },
        )

        is VaultDialog.DeleteForever -> ConfirmDialog(
            title = if (dialog.items.size == 1) {
                stringResourceSafe(R.string.dialog_delete_forever_title, dialog.items.first().name)
            } else {
                stringResourceSafe(R.string.dialog_delete_forever_title_n, dialog.items.size)
            },
            body = stringResourceSafe(R.string.dialog_delete_forever_body),
            confirmLabel = stringResourceSafe(R.string.action_delete_forever),
            onDismiss = onDismiss,
            onConfirm = {
                viewModel.purge(dialog.items)
                onDismiss()
            },
        )

        is VaultDialog.EmptyTrash -> ConfirmDialog(
            title = stringResourceSafe(R.string.dialog_empty_trash_title),
            body = stringResourceSafe(R.string.dialog_empty_trash_body),
            confirmLabel = stringResourceSafe(R.string.action_empty),
            onDismiss = onDismiss,
            onConfirm = {
                viewModel.emptyTrash()
                onDismiss()
            },
        )

        is VaultDialog.NewFolder -> TextInputDialog(
            title = stringResourceSafe(R.string.dialog_new_folder),
            initialValue = "",
            onDismiss = onDismiss,
            onConfirm = { name ->
                // Cycle the palette so consecutive folders never come out the same colour.
                viewModel.createFolder(name, colorIndex = name.hashCode().mod(8))
                onDismiss()
            },
        )

        is VaultDialog.RenameFolder -> TextInputDialog(
            title = stringResourceSafe(R.string.dialog_rename_folder),
            initialValue = dialog.name,
            onDismiss = onDismiss,
            onConfirm = { name ->
                viewModel.renameFolder(dialog.id, name)
                onDismiss()
            },
        )

        is VaultDialog.ColourFolder -> ColourPickerDialog(
            currentIndex = dialog.colorIndex,
            onDismiss = onDismiss,
            onPick = { index ->
                viewModel.recolorFolder(dialog.id, index)
                onDismiss()
            },
        )

        is VaultDialog.DeleteFolder -> ConfirmDialog(
            title = stringResourceSafe(R.string.dialog_delete_folder_title, dialog.name),
            body = stringResourceSafe(R.string.dialog_delete_folder_body, dialog.count),
            confirmLabel = stringResourceSafe(R.string.action_delete),
            onDismiss = onDismiss,
            onConfirm = {
                viewModel.deleteFolder(dialog.id)
                onDismiss()
            },
        )

        is VaultDialog.DeleteAllFolders -> ConfirmDialog(
            title = stringResourceSafe(R.string.dialog_delete_all_title),
            body = stringResourceSafe(R.string.dialog_delete_all_body, dialog.fileCount),
            confirmLabel = stringResourceSafe(R.string.delete_all),
            onDismiss = onDismiss,
            onConfirm = {
                viewModel.deleteAllFolders()
                onDismiss()
            },
        )

        is VaultDialog.DeleteItems -> ConfirmDialog(
            title = if (dialog.items.size == 1) {
                stringResourceSafe(R.string.dialog_delete_item_title, dialog.items.first().name)
            } else {
                "Delete ${dialog.items.size} files?"
            },
            body = stringResourceSafe(R.string.dialog_delete_item_body),
            confirmLabel = stringResourceSafe(R.string.action_delete),
            onDismiss = onDismiss,
            onConfirm = {
                viewModel.delete(dialog.items)
                onDismiss()
            },
        )
    }
}
