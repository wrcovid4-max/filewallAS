package com.filewall.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.filewall.model.AutoLock
import com.filewall.model.SortField
import com.filewall.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Every toggle on the Security tab, plus the vault toolbar's view preferences. */
data class VaultSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val vaultLockEnabled: Boolean = true,
    val biometricEnabled: Boolean = false,
    val disablePasscodeFallback: Boolean = false,
    val autoLock: AutoLock = AutoLock.SECONDS_15,
    val disableStorageSync: Boolean = true,
    val allowScreenshots: Boolean = false,
    val syncToWatch: Boolean = true,
    val autoBackupEnabled: Boolean = false,
    val sortField: SortField = SortField.DATE_ADDED,
    val sortAscending: Boolean = false,
    val gridView: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "filewall_settings")

class SettingsStore(context: Context) {

    private val store = context.applicationContext.dataStore

    val settings: Flow<VaultSettings> = store.data.map { prefs ->
        VaultSettings(
            theme = prefs[Keys.THEME]?.toEnum { ThemeMode.valueOf(it) } ?: ThemeMode.SYSTEM,
            vaultLockEnabled = prefs[Keys.VAULT_LOCK] ?: true,
            biometricEnabled = prefs[Keys.BIOMETRIC] ?: false,
            disablePasscodeFallback = prefs[Keys.NO_PIN_FALLBACK] ?: false,
            autoLock = AutoLock.fromSeconds(prefs[Keys.AUTO_LOCK] ?: AutoLock.SECONDS_15.seconds),
            disableStorageSync = prefs[Keys.NO_STORAGE_SYNC] ?: true,
            allowScreenshots = prefs[Keys.ALLOW_SCREENSHOTS] ?: false,
            syncToWatch = prefs[Keys.SYNC_TO_WATCH] ?: true,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP] ?: false,
            sortField = prefs[Keys.SORT_FIELD]?.toEnum { SortField.valueOf(it) } ?: SortField.DATE_ADDED,
            sortAscending = prefs[Keys.SORT_ASC] ?: false,
            gridView = prefs[Keys.GRID_VIEW] ?: true,
        )
    }

    suspend fun setTheme(value: ThemeMode) = put(Keys.THEME, value.name)
    suspend fun setVaultLockEnabled(value: Boolean) = put(Keys.VAULT_LOCK, value)
    suspend fun setBiometricEnabled(value: Boolean) = put(Keys.BIOMETRIC, value)
    suspend fun setDisablePasscodeFallback(value: Boolean) = put(Keys.NO_PIN_FALLBACK, value)
    suspend fun setAutoLock(value: AutoLock) = put(Keys.AUTO_LOCK, value.seconds)
    suspend fun setDisableStorageSync(value: Boolean) = put(Keys.NO_STORAGE_SYNC, value)
    suspend fun setAllowScreenshots(value: Boolean) = put(Keys.ALLOW_SCREENSHOTS, value)
    suspend fun setSyncToWatch(value: Boolean) = put(Keys.SYNC_TO_WATCH, value)
    suspend fun setAutoBackupEnabled(value: Boolean) = put(Keys.AUTO_BACKUP, value)
    suspend fun setSortField(value: SortField) = put(Keys.SORT_FIELD, value.name)
    suspend fun setSortAscending(value: Boolean) = put(Keys.SORT_ASC, value)
    suspend fun setGridView(value: Boolean) = put(Keys.GRID_VIEW, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private fun <T : Enum<T>> String.toEnum(parse: (String) -> T): T? =
        runCatching { parse(this) }.getOrNull()

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val VAULT_LOCK = booleanPreferencesKey("vault_lock_enabled")
        val BIOMETRIC = booleanPreferencesKey("biometric_enabled")
        val NO_PIN_FALLBACK = booleanPreferencesKey("disable_passcode_fallback")
        val AUTO_LOCK = intPreferencesKey("auto_lock_seconds")
        val NO_STORAGE_SYNC = booleanPreferencesKey("disable_storage_sync")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
        val SYNC_TO_WATCH = booleanPreferencesKey("sync_to_watch")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup_enabled")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASC = booleanPreferencesKey("sort_ascending")
        val GRID_VIEW = booleanPreferencesKey("grid_view")
    }
}
