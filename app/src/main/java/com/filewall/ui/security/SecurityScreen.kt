package com.filewall.ui.security

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    data object DriveBackup : PassphrasePrompt
    data object DriveRestore : PassphrasePrompt
    data object EnableAutoBackup : PassphrasePrompt
}

@Composable
fun SecurityScreen(
    viewModel: SecurityViewModel,
    onChangePasscode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = state.settings

    var prompt by remember { mutableStateOf<PassphrasePrompt?>(null) }
    val biometricAvailable = remember { Biometrics.isAvailable(context) }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onSignInResult(result.data) }

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
                    description = stringResourceSafe(R.string.fingerprint_face_desc),
                    checked = settings.biometricEnabled && biometricAvailable,
                    enabled = settings.vaultLockEnabled && biometricAvailable,
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
                            onClick = { prompt = PassphrasePrompt.DriveBackup },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Icon(Icons.Filled.CloudUpload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResourceSafe(R.string.backup))
                        }
                        OutlinedButton(
                            onClick = { prompt = PassphrasePrompt.DriveRestore },
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
                            // Enabling needs the passphrase up front; the worker has no one
                            // to ask when it wakes up.
                            if (enabled) {
                                prompt = PassphrasePrompt.EnableAutoBackup
                            } else {
                                viewModel.disableAutoBackup()
                            }
                        },
                    )
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
        val writing = current is PassphrasePrompt.ExportLocal ||
            current is PassphrasePrompt.DriveBackup ||
            current is PassphrasePrompt.EnableAutoBackup
        val titleRes = when (current) {
            is PassphrasePrompt.EnableAutoBackup -> R.string.auto_backup
            else -> if (writing) R.string.backup else R.string.restore
        }
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
                    is PassphrasePrompt.DriveBackup -> viewModel.backupToDrive(passphrase)
                    is PassphrasePrompt.DriveRestore -> viewModel.restoreFromDrive(passphrase)
                    is PassphrasePrompt.EnableAutoBackup -> viewModel.enableAutoBackup(passphrase)
                }
                prompt = null
            },
        )
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
        Icon(
            painter = painterResource(R.drawable.ic_shield),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(96.dp),
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
