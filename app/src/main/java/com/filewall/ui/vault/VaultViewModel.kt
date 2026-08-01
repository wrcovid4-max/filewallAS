package com.filewall.ui.vault

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.filewall.data.media.ThumbnailStore
import com.filewall.data.repo.VaultRepository
import com.filewall.data.settings.SettingsStore
import com.filewall.data.wear.WearSyncManager
import com.filewall.di.AppContainer
import com.filewall.model.FolderWithCount
import com.filewall.model.SortField
import com.filewall.model.VaultFilter
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import com.filewall.ui.lock.LockController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the vault tab renders, in one snapshot. */
data class VaultUiState(
    val filter: VaultFilter = VaultFilter.UNLOCKED,
    val query: String = "",
    val sortField: SortField = SortField.DATE_ADDED,
    val sortAscending: Boolean = false,
    val gridView: Boolean = true,
    val folders: List<FolderWithCount> = emptyList(),
    val items: List<VaultItem> = emptyList(),
    val openFolder: VaultFolder? = null,
    val selectedIds: Set<String> = emptySet(),
    val selectionMode: Boolean = false,
    val hiddenUnlocked: Boolean = false,
    val busy: Boolean = false,
) {
    /** The count shown next to the sort control: folders plus files, as the original does. */
    val visibleCount: Int get() = items.size + folders.size

    val needsPasscode: Boolean get() = filter == VaultFilter.HIDDEN && !hiddenUnlocked
}

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModel(
    private val repository: VaultRepository,
    private val settings: SettingsStore,
    private val lock: LockController,
    private val wearSync: WearSyncManager,
    val thumbnails: ThumbnailStore,
) : ViewModel() {

    private val filter = MutableStateFlow(VaultFilter.UNLOCKED)
    private val query = MutableStateFlow("")
    private val openFolderId = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private data class Params(
        val filter: VaultFilter,
        val query: String,
        val openFolderId: String?,
        val selection: Set<String>,
        val busy: Boolean,
    )

    private val params = combine(filter, query, openFolderId, selection, busy) { f, q, folder, selected, working ->
        Params(f, q, folder, selected, working)
    }

    private val itemsFlow = filter.flatMapLatest { current ->
        repository.observeItems(hidden = current == VaultFilter.HIDDEN)
    }

    private val foldersFlow = filter.flatMapLatest { current ->
        repository.observeFolders(hidden = current == VaultFilter.HIDDEN)
    }

    val state: StateFlow<VaultUiState> = combine(
        params,
        settings.settings,
        itemsFlow,
        foldersFlow,
        lock.hiddenUnlocked,
    ) { current, prefs, allItems, allFolders, unlocked ->
        val locked = current.filter == VaultFilter.HIDDEN && !unlocked
        val openFolder = current.openFolderId?.let { id -> allFolders.firstOrNull { it.folder.id == id }?.folder }

        // Nothing is listed while the hidden side is locked, not even a count.
        val items = if (locked) {
            emptyList()
        } else {
            allItems
                .filter { item -> openFolder?.let { item.folderId == it.id } ?: true }
                .filter { item -> current.query.isBlank() || item.name.contains(current.query, ignoreCase = true) }
                .sortedWith(comparatorFor(prefs.sortField, prefs.sortAscending))
        }

        VaultUiState(
            filter = current.filter,
            query = current.query,
            sortField = prefs.sortField,
            sortAscending = prefs.sortAscending,
            gridView = prefs.gridView,
            // Folders only make sense at the top level; inside one, the grid is just files.
            folders = if (locked || openFolder != null) emptyList() else allFolders,
            items = items,
            openFolder = openFolder,
            selectedIds = current.selection,
            selectionMode = current.selection.isNotEmpty(),
            hiddenUnlocked = unlocked,
            busy = current.busy,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VaultUiState())

    private fun comparatorFor(field: SortField, ascending: Boolean): Comparator<VaultItem> {
        val base: Comparator<VaultItem> = when (field) {
            SortField.DATE_ADDED -> compareBy { it.addedAt }
            SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.TYPE -> compareBy({ it.category.ordinal }, { it.name.lowercase() })
        }
        return if (ascending) base else base.reversed()
    }

    // ------------------------------------------------------------------ inputs

    fun setFilter(value: VaultFilter) {
        filter.value = value
        openFolderId.value = null
        selection.value = emptySet()
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun openFolder(id: String?) {
        openFolderId.value = id
        selection.value = emptySet()
    }

    fun setSortField(field: SortField) = viewModelScope.launch { settings.setSortField(field) }

    fun toggleSortDirection() = viewModelScope.launch {
        settings.setSortAscending(!state.value.sortAscending)
    }

    fun toggleViewMode() = viewModelScope.launch {
        settings.setGridView(!state.value.gridView)
    }

    fun toggleSelection(id: String) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun selectAll() {
        selection.value = state.value.items.map { it.id }.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    // ----------------------------------------------------------------- actions

    fun import(uris: List<Uri>) = runGuarded {
        if (uris.isEmpty()) return@runGuarded null
        val result = repository.import(
            uris = uris,
            hidden = filter.value == VaultFilter.HIDDEN,
            folderId = openFolderId.value,
        )
        result.failures.firstOrNull()?.let { return@runGuarded it }
        "Added ${result.added} file(s) to the vault"
    }

    fun delete(items: List<VaultItem>) = runGuarded {
        repository.delete(items)
        items.forEach { thumbnails.evict(it.id) }
        selection.value = emptySet()
        if (items.size == 1) "Deleted \"${items.first().name}\"" else "Deleted ${items.size} files"
    }

    fun deleteSelected() {
        val selected = state.value.items.filter { it.id in selection.value }
        if (selected.isNotEmpty()) delete(selected)
    }

    fun rename(item: VaultItem, name: String) = runGuarded {
        repository.rename(item, name)
        null
    }

    fun move(item: VaultItem, folderId: String?) = runGuarded {
        repository.move(item, folderId)
        null
    }

    fun setHidden(items: List<VaultItem>, hidden: Boolean) = runGuarded {
        repository.setHidden(items.map { it.id }, hidden)
        selection.value = emptySet()
        if (hidden) "Moved to the hidden archive" else "Moved to the unlocked vault"
    }

    fun createFolder(name: String, colorIndex: Int) = runGuarded {
        repository.createFolder(name, colorIndex, hidden = filter.value == VaultFilter.HIDDEN)
        null
    }

    fun renameFolder(id: String, name: String) = runGuarded {
        repository.renameFolder(id, name)
        null
    }

    fun recolorFolder(id: String, colorIndex: Int) = runGuarded {
        repository.recolorFolder(id, colorIndex)
        null
    }

    fun deleteFolder(id: String) = runGuarded {
        repository.deleteFolder(id)
        thumbnails.clear()
        if (openFolderId.value == id) openFolderId.value = null
        null
    }

    fun deleteAllFolders() = runGuarded {
        repository.deleteAllFolders(hidden = filter.value == VaultFilter.HIDDEN)
        thumbnails.clear()
        openFolderId.value = null
        null
    }

    fun export(item: VaultItem, destination: Uri) = runGuarded {
        repository.export(item, destination)
        "Exported \"${item.name}\""
    }

    suspend fun countInFolders(): Int = repository.countInFolders(filter.value == VaultFilter.HIDDEN)

    fun refreshWatch() = viewModelScope.launch {
        runCatching { wearSync.publishManifest() }
    }

    /** Runs [block] with the busy flag raised, surfacing whatever it returns as a snackbar. */
    private fun runGuarded(block: suspend () -> String?) = viewModelScope.launch {
        busy.value = true
        val message = runCatching { block() }
            .getOrElse { error -> error.message ?: error.javaClass.simpleName }
        busy.value = false
        message?.let { _messages.tryEmit(it) }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = VaultViewModel(
            repository = container.repository,
            settings = container.settings,
            lock = container.lock,
            wearSync = container.wearSync,
            thumbnails = container.thumbnails,
        ) as T
    }
}
