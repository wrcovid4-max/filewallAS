package com.filewall.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.filewall.R
import com.filewall.di.AppContainer
import com.filewall.model.VaultFilter
import com.filewall.model.VaultItem
import com.filewall.ui.common.VaultHeader
import com.filewall.ui.hidden.HiddenGate
import com.filewall.ui.security.SecurityScreen
import com.filewall.ui.security.SecurityViewModel
import com.filewall.ui.vault.ConfirmDialog
import com.filewall.ui.vault.MoveToFolderDialog
import com.filewall.ui.vault.TextInputDialog
import com.filewall.ui.vault.VaultScreen
import com.filewall.ui.vault.VaultViewModel
import com.filewall.ui.viewer.VaultVideoPlayer
import com.filewall.ui.viewer.ViewerScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Tab { VAULT, HIDDEN, SECURITY }

/** Dialogs raised from the viewer, which lives above the tab content. */
private sealed interface ViewerDialog {
    data object Rename : ViewerDialog
    data object Move : ViewerDialog
    data object Delete : ViewerDialog
}

@Composable
fun FileWallRoot(container: AppContainer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val vaultViewModel: VaultViewModel = viewModel(factory = VaultViewModel.Factory(container))
    val securityViewModel: SecurityViewModel =
        viewModel(factory = SecurityViewModel.Factory(context, container))

    val settings by container.settings.settings
        .collectAsStateWithLifecycle(initialValue = com.filewall.data.settings.VaultSettings())
    val storage by container.repository.storage
        .collectAsStateWithLifecycle(initialValue = com.filewall.model.StorageBreakdown())
    val vaultState by vaultViewModel.state.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(Tab.VAULT) }
    var viewerItem by remember { mutableStateOf<VaultItem?>(null) }
    var viewerDialog by remember { mutableStateOf<ViewerDialog?>(null) }
    var changingPasscode by remember { mutableStateOf(false) }

    val snackbarHost = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val item = viewerItem
        if (uri != null && item != null) vaultViewModel.export(item, uri)
    }

    // Drive occasionally needs an Activity to collect scope consent. The result carries no
    // account payload — the user simply retries the action once permission is granted.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    LaunchedEffect(Unit) {
        launch { vaultViewModel.messages.collect { snackbarHost.showSnackbar(it) } }
        launch { securityViewModel.messages.collect { snackbarHost.showSnackbar(it) } }
        launch { securityViewModel.consentRequests.collect { consentLauncher.launch(it) } }

        // A watch handoff notification names an item; open it, then clear the request so a
        // configuration change does not re-open it behind the user's back.
        launch {
            container.pendingOpen.collect { itemId ->
                if (itemId == null) return@collect
                val item = container.repository.observeItem(itemId).first()
                container.pendingOpen.value = null
                if (item != null && !item.hidden) {
                    tab = Tab.VAULT
                    viewerItem = item
                }
            }
        }
    }

    // Selecting the Hidden tab and the Hidden pill are the same act; keep them in step.
    LaunchedEffect(tab) {
        when (tab) {
            Tab.HIDDEN -> vaultViewModel.setFilter(VaultFilter.HIDDEN)
            Tab.VAULT -> vaultViewModel.setFilter(VaultFilter.UNLOCKED)
            Tab.SECURITY -> Unit
        }
    }
    LaunchedEffect(vaultState.filter) {
        if (tab != Tab.SECURITY) {
            tab = if (vaultState.filter == VaultFilter.HIDDEN) Tab.HIDDEN else Tab.VAULT
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            // Any touch anywhere restarts the inactivity countdown.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                        container.lock.touch()
                    }
                }
            },
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TabItem(Tab.VAULT, tab, Icons.Filled.PhotoLibrary, R.string.tab_open_vault) { tab = it }
                TabItem(Tab.HIDDEN, tab, Icons.Filled.Lock, R.string.tab_hidden) { tab = it }
                TabItem(Tab.SECURITY, tab, Icons.Filled.Shield, R.string.tab_security) { tab = it }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            VaultHeader(totalBytes = storage.totalBytes)

            when (tab) {
                Tab.VAULT, Tab.HIDDEN -> VaultScreen(
                    viewModel = vaultViewModel,
                    pinManager = container.pinManager,
                    settings = settings,
                    onUnlockHidden = { container.lock.unlock() },
                    onOpenItem = { item ->
                        viewerItem = item
                        // Photos and video render in-app; anything else needs a viewer we
                        // do not ship, so hand it over immediately rather than showing an
                        // empty stage first.
                        if (item.category == com.filewall.model.FileCategory.DOC ||
                            item.category == com.filewall.model.FileCategory.OTHER
                        ) {
                            scope.launch { openExternally(context, container, item) }
                        }
                    },
                )

                Tab.SECURITY -> SecurityScreen(
                    viewModel = securityViewModel,
                    onChangePasscode = {
                        // Dropping the old hash first is what puts HiddenGate into its
                        // set-then-confirm flow instead of asking for the code being replaced.
                        container.pinManager.clearPin()
                        changingPasscode = true
                    },
                )
            }
        }
    }

    // ------------------------------------------------------------------ viewer

    viewerItem?.let { item ->
        BackHandler { viewerItem = null }

        ViewerScreen(
            item = item,
            loadImage = { target ->
                runCatching {
                    val bytes = container.repository.fullBytes(target)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            },
            videoPlayer = { playerModifier ->
                VaultVideoPlayer(
                    item = item,
                    repository = container.repository,
                    modifier = playerModifier,
                )
            },
            onClose = { viewerItem = null },
            onExport = { exportLauncher.launch(item.name) },
            onMove = { viewerDialog = ViewerDialog.Move },
            onRename = { viewerDialog = ViewerDialog.Rename },
            onDelete = { viewerDialog = ViewerDialog.Delete },
            onOpenExternally = { scope.launch { openExternally(context, container, item) } },
        )

        when (viewerDialog) {
            null -> Unit

            ViewerDialog.Rename -> TextInputDialog(
                title = stringResource(R.string.dialog_rename_file),
                initialValue = item.name,
                onDismiss = { viewerDialog = null },
                onConfirm = { name ->
                    vaultViewModel.rename(item, name)
                    viewerItem = item.copy(name = name)
                    viewerDialog = null
                },
            )

            ViewerDialog.Move -> MoveToFolderDialog(
                folders = vaultState.folders,
                currentFolderId = item.folderId,
                onDismiss = { viewerDialog = null },
                onPick = { folderId ->
                    vaultViewModel.move(item, folderId)
                    viewerItem = item.copy(folderId = folderId)
                    viewerDialog = null
                },
            )

            ViewerDialog.Delete -> ConfirmDialog(
                title = stringResource(R.string.dialog_delete_item_title, item.name),
                body = stringResource(R.string.dialog_delete_item_body),
                confirmLabel = stringResource(R.string.action_delete),
                onDismiss = { viewerDialog = null },
                onConfirm = {
                    vaultViewModel.delete(listOf(item))
                    viewerDialog = null
                    viewerItem = null
                },
            )
        }
    }

    // -------------------------------------------------------- passcode change

    if (changingPasscode) {
        BackHandler { changingPasscode = false }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            HiddenGate(
                pinManager = container.pinManager,
                biometricEnabled = false,
                disablePasscodeFallback = false,
                onUnlocked = {
                    changingPasscode = false
                    securityViewModel.onPasscodeChanged()
                },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(
    target: Tab,
    current: Tab,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onSelect: (Tab) -> Unit,
) {
    NavigationBarItem(
        selected = current == target,
        onClick = { onSelect(target) },
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(labelRes)) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * Decrypts an item into the preview cache and hands it to another app read-only.
 *
 * The copy is short-lived: [com.filewall.ui.lock.LockController] wipes the directory on
 * every lock, and the app clears it again on next launch.
 */
private suspend fun openExternally(
    context: android.content.Context,
    container: AppContainer,
    item: VaultItem,
) {
    runCatching {
        val file = container.repository.materialisePreview(item)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
