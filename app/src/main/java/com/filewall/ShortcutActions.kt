package com.filewall

/**
 * Intent actions behind the launcher shortcuts declared in `res/xml/shortcuts.xml`.
 * The Android counterpart of the iOS App Intents / Shortcuts entry points: each one drops
 * the user straight onto a destination. [MainActivity] turns the action into a request on
 * `AppContainer.pendingShortcut`, which `FileWallRoot` consumes (after the vault is unlocked).
 */
object ShortcutActions {
    const val SEARCH = "com.filewall.action.SHORTCUT_SEARCH"
    const val CONVERT = "com.filewall.action.SHORTCUT_CONVERT"
    const val SETTINGS = "com.filewall.action.SHORTCUT_SETTINGS"
}
