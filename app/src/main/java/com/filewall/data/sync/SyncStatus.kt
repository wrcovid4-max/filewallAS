package com.filewall.data.sync

/**
 * Live cloud-sync state, meant to be read by any screen that wants to show "is it syncing right
 * now" — the persistent line in the Security card, and the transient Snackbar popup that rides
 * the same [com.filewall.ui.security.SecurityViewModel.messages] channel every other backup
 * action already uses, so it surfaces over whichever tab the user is on without new UI plumbing.
 */
sealed interface SyncStatus {
    data object SignedOut : SyncStatus
    data object Idle : SyncStatus
    data class Syncing(val label: String) : SyncStatus
    data class Synced(val atEpochMs: Long) : SyncStatus
    data class Error(val message: String) : SyncStatus
}
