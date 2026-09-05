package com.filewall.ui.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Copyright
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filewall.R
import com.filewall.data.backup.VaultArchive
import com.filewall.model.AutoLock
import com.filewall.model.ThemeMode
import com.filewall.ui.common.SectionCard
import com.filewall.ui.common.SettingDivider
import com.filewall.ui.common.SettingSwitchRow
import com.filewall.ui.common.StorageBreakdownCard
import com.filewall.ui.common.stringResourceSafe
import com.filewall.ui.vault.PassphraseDialog
import com.filewall.util.Biometrics
import com.filewall.util.formatDuration

/** Which passphrase prompt is open, and what to do with the result. */
private sealed interface PassphrasePrompt {
    data class ExportLocal(val destination: android.net.Uri) : PassphrasePrompt
    data class RestoreLocal(val source: android.net.Uri) : PassphrasePrompt
    data object SetSyncPassphrase : PassphrasePrompt
}

@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onChangePasscode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val settings = state.settings

    var prompt by remember { mutableStateOf<PassphrasePrompt?>(null) }
    var showPassphraseInfo by remember { mutableStateOf(false) }
    // Re-checked on every resume: the user can leave to Settings, enrol a fingerprint, and
    // come back expecting the toggle to be live now.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var biometricStatus by remember { mutableStateOf(Biometrics.status(context)) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                biometricStatus = Biometrics.status(context)
                viewModel.refreshPinState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val biometricAvailable = biometricStatus == com.filewall.util.BiometricStatus.AVAILABLE

    // Switching to this tab is not an app resume, so catch a PIN set on the Hidden tab here.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshPinState() }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onSignInResult(result.data) }

    val cloudSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onCloudSignInResult(result.data) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VaultArchive.MIME_TYPE),
    ) { uri -> uri?.let { prompt = PassphrasePrompt.ExportLocal(it) } }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { prompt = PassphrasePrompt.RestoreLocal(it) } }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 20.dp,
            end = 20.dp,
            bottom = 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { SecurityHero() }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                LinkText(stringResourceSafe(R.string.about_filewall)) {
                    uriHandler.openUri(AppLinks.WEBSITE)
                }
            }
        }

        item { StorageBreakdownCard(state.storage) }

        item {
            SectionCard {
                SettingSwitchRow(
                    title = stringResourceSafe(R.string.secure_vault_locking),
                    description = stringResourceSafe(
                        if (state.pinSet && settings.vaultLockEnabled) {
                            R.string.passcode_active
                        } else {
                            R.string.passcode_inactive
                        },
                    ),
                    checked = settings.vaultLockEnabled,
                    onCheckedChange = { viewModel.setVaultLockEnabled(it) },
                )

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.fingerprint_face),
                    // Tell the user why it's off rather than leaving a dead grey switch.
                    description = when (biometricStatus) {
                        com.filewall.util.BiometricStatus.NONE_ENROLLED ->
                            stringResourceSafe(R.string.biometric_none_enrolled)
                        com.filewall.util.BiometricStatus.NO_HARDWARE ->
                            stringResourceSafe(R.string.biometric_no_hardware)
                        com.filewall.util.BiometricStatus.UNAVAILABLE ->
                            stringResourceSafe(R.string.biometric_unavailable)
                        com.filewall.util.BiometricStatus.AVAILABLE ->
                            if (!state.pinSet) {
                                stringResourceSafe(R.string.biometric_needs_pin)
                            } else {
                                stringResourceSafe(R.string.fingerprint_face_desc)
                            }
                    },
                    checked = settings.biometricEnabled && biometricAvailable,
                    // A PIN must exist first — it's the enrolment step and the fallback.
                    // No dependency on the vault-lock toggle, which was the bug: a fresh
                    // vault has lock defaulted on but the row read it as off.
                    enabled = biometricAvailable && state.pinSet,
                    onCheckedChange = { viewModel.setBiometricEnabled(it) },
                )

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.disable_passcode_fallback),
                    description = stringResourceSafe(R.string.disable_passcode_fallback_desc),
                    checked = settings.disablePasscodeFallback,
                    // Cutting off the PIN only makes sense once biometrics can actually open it.
                    enabled = settings.biometricEnabled && biometricAvailable,
                    onCheckedChange = { viewModel.setDisablePasscodeFallback(it) },
                )

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onChangePasscode,
                    enabled = settings.vaultLockEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        stringResourceSafe(R.string.change_passcode),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                Spacer(Modifier.height(10.dp))

                // One-tap panic close: seals the hidden archive and wipes anything decrypted
                // to disk right now, without waiting for the idle auto-lock timer.
                Button(
                    onClick = { viewModel.lockNow() },
                    enabled = state.hiddenUnlocked,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResourceSafe(
                            if (state.hiddenUnlocked) R.string.lock_now else R.string.lock_now_already,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        item {
            SectionCard {
                Text(
                    stringResourceSafe(R.string.appearance),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        ChoiceChip(
                            label = stringResourceSafe(
                                when (mode) {
                                    ThemeMode.SYSTEM -> R.string.theme_system
                                    ThemeMode.LIGHT -> R.string.theme_light
                                    ThemeMode.DARK -> R.string.theme_dark
                                },
                            ),
                            selected = settings.theme == mode,
                            onClick = { viewModel.setTheme(mode) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            stringResourceSafe(R.string.auto_lock),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResourceSafe(R.string.auto_lock_desc),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SettingDivider()

                Text(
                    stringResourceSafe(R.string.select_time_limit),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AutoLock.entries.forEach { option ->
                        ChoiceChip(
                            label = stringResourceSafe(
                                when (option) {
                                    AutoLock.SECONDS_15 -> R.string.auto_lock_15s
                                    AutoLock.SECONDS_30 -> R.string.auto_lock_30s
                                    AutoLock.MINUTE_1 -> R.string.auto_lock_1m
                                    AutoLock.MINUTES_5 -> R.string.auto_lock_5m
                                    AutoLock.NEVER -> R.string.auto_lock_never
                                },
                            ),
                            selected = settings.autoLock == option,
                            onClick = { viewModel.setAutoLock(option) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                AutoLockHint(settings.autoLock)

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.disable_storage_sync),
                    description = stringResourceSafe(R.string.disable_storage_sync_desc),
                    checked = settings.disableStorageSync,
                    accentThumb = true,
                    onCheckedChange = { viewModel.setDisableStorageSync(it) },
                )

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.allow_screenshots),
                    description = stringResourceSafe(R.string.allow_screenshots_desc),
                    checked = settings.allowScreenshots,
                    accentThumb = true,
                    onCheckedChange = { viewModel.setAllowScreenshots(it) },
                )

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.sync_to_watch),
                    description = stringResourceSafe(R.string.sync_to_watch_desc),
                    checked = settings.syncToWatch,
                    onCheckedChange = { viewModel.setSyncToWatch(it) },
                )

                SettingDivider()

                SettingSwitchRow(
                    title = stringResourceSafe(R.string.show_doc_previews),
                    description = stringResourceSafe(R.string.show_doc_previews_desc),
                    checked = settings.showDocPreviews,
                    accentThumb = true,
                    onCheckedChange = { viewModel.setShowDocPreviews(it) },
                )
            }
        }

        item {
            SectionCard {
                Text(
                    stringResourceSafe(R.string.local_archive),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResourceSafe(R.string.local_archive_desc),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { exportLauncher.launch(VaultArchive.DEFAULT_FILE_NAME) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResourceSafe(R.string.export_archive), maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResourceSafe(R.string.import_archive), maxLines = 1)
                    }
                }
            }
        }

        item {
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResourceSafe(R.string.firebase_sync_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    CloudSyncStatusIcon(state.cloudStatus)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResourceSafe(R.string.firebase_sync_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))

                when {
                    !state.cloudSyncAvailable -> Text(
                        stringResourceSafe(R.string.firebase_sync_unconfigured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )

                    !state.cloudSignedIn -> Button(
                        onClick = { cloudSignInLauncher.launch(viewModel.cloudSignInIntent) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(stringResourceSafe(R.string.sign_in_google))
                    }

                    else -> {
                        Text(
                            CloudSyncStatusText(state.cloudStatus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))

                        if (!state.cloudHasPassphrase) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResourceSafe(R.string.sync_passphrase_needed),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(
                                    onClick = { showPassphraseInfo = true },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Info,
                                        contentDescription = stringResourceSafe(R.string.sync_passphrase_help),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { prompt = PassphrasePrompt.SetSyncPassphrase },
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Text(stringResourceSafe(R.string.set_sync_passphrase))
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.syncNow() },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraLarge,
                                enabled = state.cloudStatus !is com.filewall.data.sync.SyncStatus.Syncing,
                            ) {
                                Icon(Icons.Filled.CloudSync, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResourceSafe(R.string.sync_now))
                            }
                            IconButton(onClick = { viewModel.signOutOfCloud() }) {
                                Icon(
                                    Icons.Filled.Logout,
                                    contentDescription = stringResourceSafe(R.string.sign_out),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            SectionCard {
                Text(
                    stringResourceSafe(R.string.cloud_backup),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(16.dp))

                val account = state.account
                if (account == null) {
                    Text(
                        stringResourceSafe(R.string.drive_unconfigured),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { signInLauncher.launch(viewModel.driveSignInIntent) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Text(stringResourceSafe(R.string.sign_in_google))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                account.displayName,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                account.email,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { viewModel.signOut() }) {
                            Icon(
                                Icons.Filled.Logout,
                                contentDescription = stringResourceSafe(R.string.sign_out),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = state.lastDriveBackup
                            ?.let { stringResourceSafe(R.string.last_backup, it) }
                            ?: stringResourceSafe(R.string.last_backup_never),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { viewModel.backupToDrive() },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResourceSafe(R.string.backup))
                        }
                        OutlinedButton(
                            onClick = { viewModel.restoreFromDrive() },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Icon(Icons.Filled.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResourceSafe(R.string.restore))
                        }
                    }

                    SettingDivider()

                    SettingSwitchRow(
                        title = stringResourceSafe(R.string.auto_backup),
                        description = stringResourceSafe(R.string.auto_backup_desc),
                        checked = settings.autoBackupEnabled,
                        onCheckedChange = { enabled ->
                            // Sign-in-only: the worker reads the managed key from Drive, so
                            // there is nothing to ask the user up front.
                            if (enabled) viewModel.enableAutoBackup() else viewModel.disableAutoBackup()
                        },
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    LinkText(stringResourceSafe(R.string.link_privacy)) {
                        uriHandler.openUri(AppLinks.PRIVACY)
                    }
                }
            }
        }

        state.busyLabel?.let { label ->
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { AboutLinks() }

        item {
            Text(
                stringResourceSafe(R.string.copyright),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }
    }

    prompt?.let { current ->
        if (current is PassphrasePrompt.SetSyncPassphrase) {
            PassphraseDialog(
                title = stringResourceSafe(R.string.sync_passphrase_label),
                hint = stringResourceSafe(R.string.sync_passphrase_hint),
                confirmLabel = stringResourceSafe(R.string.set_sync_passphrase),
                minLength = VaultArchive.MIN_PASSPHRASE_LENGTH,
                onDismiss = { prompt = null },
                onConfirm = { passphrase ->
                    viewModel.setSyncPassphrase(passphrase)
                    prompt = null
                },
            )
            return@let
        }

        // Only the local .fwvault export/import still asks for a passphrase — Drive backup is
        // sign-in only and keys itself from the account's managed key.
        val writing = current is PassphrasePrompt.ExportLocal
        val titleRes = if (writing) R.string.backup else R.string.restore
        PassphraseDialog(
            title = stringResourceSafe(titleRes),
            hint = stringResourceSafe(R.string.archive_passphrase_hint),
            confirmLabel = stringResourceSafe(titleRes),
            // Restoring must accept whatever the archive was written with; writing enforces
            // a floor.
            minLength = if (writing) VaultArchive.MIN_PASSPHRASE_LENGTH else 1,
            onDismiss = { prompt = null },
            onConfirm = { passphrase ->
                when (current) {
                    is PassphrasePrompt.ExportLocal -> viewModel.exportArchive(current.destination, passphrase)
                    is PassphrasePrompt.RestoreLocal -> viewModel.restoreArchive(current.source, passphrase)
                    is PassphrasePrompt.SetSyncPassphrase -> Unit
                }
                prompt = null
            },
        )
    }

    if (showPassphraseInfo) {
        AlertDialog(
            onDismissRequest = { showPassphraseInfo = false },
            title = { Text(stringResourceSafe(R.string.sync_passphrase_label)) },
            text = {
                Column {
                    SYNC_PASSPHRASE_STEPS.forEachIndexed { index, step ->
                        Row(modifier = Modifier.padding(bottom = 10.dp)) {
                            Text(
                                "${index + 1}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(22.dp),
                            )
                            Text(step, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        stringResourceSafe(R.string.sync_passphrase_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showPassphraseInfo = false }) {
                    Text(stringResourceSafe(R.string.action_ok))
                }
            },
        )
    }
}

/** The (i) walkthrough shown next to the sync-passphrase prompt in the Cloud Sync card. */
private val SYNC_PASSPHRASE_STEPS: List<String>
    @Composable get() = listOf(
        stringResourceSafe(R.string.sync_passphrase_step1),
        stringResourceSafe(R.string.sync_passphrase_step2),
        stringResourceSafe(R.string.sync_passphrase_step3),
        stringResourceSafe(R.string.sync_passphrase_step4),
        stringResourceSafe(R.string.sync_passphrase_step5),
    )

/** FileWall's website pages, opened in the browser from the Security footer. */
private object AppLinks {
    private const val BASE = "https://wrcovid4-max.github.io/FileWallWeb/"
    const val WEBSITE = BASE + "index.html"
    const val PLATFORMS = BASE + "platforms.html"
    const val DOWNLOAD = BASE + "download.html"
    const val SUPPORT = BASE + "support.html"
    const val PRIVACY = BASE + "privacy.html"
    const val TERMS = BASE + "terms.html"
    const val ACCESSIBILITY = BASE + "accessibility.html"
    const val NEWS = BASE + "news.html"
    const val TRADEMARKS = BASE + "trademarks.html"
}

@Composable
private fun LinkText(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp, horizontal = 2.dp),
    )
}

/**
 * The persistent "is it syncing right now" indicator for the Cloud Sync card. The transient
 * popup for the same state lives in [SecurityViewModel.syncNow] via the shared Snackbar
 * channel — this is the always-visible counterpart, so the answer to "is it working" doesn't
 * require triggering a sync to find out.
 */
@Composable
private fun CloudSyncStatusIcon(status: com.filewall.data.sync.SyncStatus) {
    when (status) {
        is com.filewall.data.sync.SyncStatus.Syncing ->
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        is com.filewall.data.sync.SyncStatus.Synced -> Icon(
            Icons.Filled.CloudDone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        is com.filewall.data.sync.SyncStatus.Error -> Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        else -> Icon(
            Icons.Filled.CloudQueue,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloudSyncStatusText(status: com.filewall.data.sync.SyncStatus): String = when (status) {
    is com.filewall.data.sync.SyncStatus.SignedOut -> stringResourceSafe(R.string.sync_status_signed_out)
    is com.filewall.data.sync.SyncStatus.Idle -> stringResourceSafe(R.string.sync_status_idle)
    is com.filewall.data.sync.SyncStatus.Syncing -> status.label
    is com.filewall.data.sync.SyncStatus.Synced -> stringResourceSafe(
        R.string.sync_status_synced,
        android.text.format.DateFormat.getTimeFormat(androidx.compose.ui.platform.LocalContext.current)
            .format(java.util.Date(status.atEpochMs)),
    )
    is com.filewall.data.sync.SyncStatus.Error -> stringResourceSafe(R.string.sync_status_error, status.message)
}

private data class LinkEntry(val label: String, val icon: ImageVector, val url: String)

@Composable
private fun AboutLinks(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val entries = listOf(
        LinkEntry(stringResourceSafe(R.string.link_website), Icons.Filled.Language, AppLinks.WEBSITE),
        LinkEntry(stringResourceSafe(R.string.link_platforms), Icons.Filled.Devices, AppLinks.PLATFORMS),
        LinkEntry(stringResourceSafe(R.string.link_support), Icons.Filled.SupportAgent, AppLinks.SUPPORT),
        LinkEntry(stringResourceSafe(R.string.link_news), Icons.Filled.Article, AppLinks.NEWS),
        LinkEntry(stringResourceSafe(R.string.link_download), Icons.Filled.Download, AppLinks.DOWNLOAD),
        LinkEntry(stringResourceSafe(R.string.link_privacy), Icons.Filled.Shield, AppLinks.PRIVACY),
        LinkEntry(stringResourceSafe(R.string.link_terms), Icons.Filled.Description, AppLinks.TERMS),
        LinkEntry(stringResourceSafe(R.string.link_accessibility), Icons.Filled.Accessibility, AppLinks.ACCESSIBILITY),
        LinkEntry(stringResourceSafe(R.string.link_trademarks), Icons.Filled.Copyright, AppLinks.TRADEMARKS),
    )
    SectionCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResourceSafe(R.string.about_and_links),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(6.dp))
        entries.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { uriHandler.openUri(entry.url) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    entry.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    entry.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SecurityHero(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            modifier = Modifier
                .size(96.dp)
                .clip(MaterialTheme.shapes.large),
        )
        Spacer(Modifier.height(24.dp))
        Text(
            stringResourceSafe(R.string.security_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResourceSafe(R.string.security_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun AutoLockHint(autoLock: AutoLock, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = when (autoLock) {
                AutoLock.NEVER -> stringResourceSafe(R.string.auto_lock_hint_never)
                AutoLock.SECONDS_15 -> stringResourceSafe(R.string.auto_lock_hint_15)
                else -> stringResourceSafe(R.string.auto_lock_hint, formatDuration(autoLock.seconds))
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Pill used by both Appearance and the auto-lock timings. */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
