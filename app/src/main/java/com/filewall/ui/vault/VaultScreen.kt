package com.filewall.ui.vault

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filewall.R
import com.filewall.data.crypto.PinManager
import com.filewall.data.settings.VaultSettings
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
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<VaultDialog?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> viewModel.import(uris) }

    // Inside a folder, back climbs out before it leaves the tab.
    BackHandler(enabled = state.openFolder != null || state.selectionMode) {
        if (state.selectionMode) viewModel.clearSelection() else viewModel.openFolder(null)
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
                SelectionActions(
                    count = state.selectedIds.size,
                    inHidden = state.filter == VaultFilter.HIDDEN,
                    onDelete = {
                        dialog = VaultDialog.DeleteItems(state.items.filter { it.id in state.selectedIds })
                    },
                    onToggleHidden = {
                        viewModel.setHidden(
                            state.items.filter { it.id in state.selectedIds },
                            hidden = state.filter != VaultFilter.HIDDEN,
                        )
                    },
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

            HorizontalDivider(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.outline,
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(if (state.gridView) 3 else 1),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.folders.isNotEmpty() || state.openFolder == null) {
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

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResourceSafe(R.string.section_files),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (state.items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = if (state.query.isBlank()) {
                                stringResourceSafe(R.string.empty_vault)
                            } else {
                                stringResourceSafe(R.string.empty_search, state.query)
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
                    if (state.gridView) {
                        FileGridTile(
                            item = item,
                            thumbnails = viewModel.thumbnails,
                            selected = selected,
                            selectionMode = state.selectionMode,
                            onClick = onClick,
                            onLongClick = { viewModel.toggleSelection(item.id) },
                        )
                    } else {
                        FileListRow(
                            item = item,
                            thumbnails = viewModel.thumbnails,
                            selected = selected,
                            selectionMode = state.selectionMode,
                            onClick = onClick,
                            onLongClick = { viewModel.toggleSelection(item.id) },
                        )
                    }
                }
            }
        }

        if (!state.needsPasscode) {
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
        onDismiss = { dialog = null },
        viewModel = viewModel,
    )
}

@Composable
private fun SelectionActions(
    count: Int,
    inHidden: Boolean,
    onDelete: () -> Unit,
    onToggleHidden: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$count selected",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleHidden, enabled = count > 0) {
            Icon(if (inHidden) Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResourceSafe(if (inHidden) R.string.action_unhide else R.string.action_hide))
        }
        TextButton(onClick = onDelete, enabled = count > 0) {
            Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        }
        TextButton(onClick = onCancel) { Text(stringResourceSafe(R.string.action_cancel)) }
    }
}

@Composable
private fun VaultDialogHost(
    dialog: VaultDialog?,
    onDismiss: () -> Unit,
    viewModel: VaultViewModel,
) {
    when (dialog) {
        null -> Unit

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
