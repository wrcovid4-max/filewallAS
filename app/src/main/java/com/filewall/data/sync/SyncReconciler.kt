package com.filewall.data.sync

/**
 * The device-agnostic merge rules from FIREBASE_BLUEPRINT.md (§3.4–§3.7).
 *
 * Pure, total and idempotent: given the same local/remote pair it always returns the same
 * action and never invents a record. This is the correctness core of the sync layer; the
 * Firestore/Storage SDK layer just executes the [Action] it returns.
 */
object SyncReconciler {

    enum class Action {
        UPSERT_LOCAL,   // remote is newer: write/refresh the local copy
        DELETE_LOCAL,   // remote tombstone is newer: drop the local copy
        PUSH_REMOTE,    // local is newer (or remote-absent): write the local copy up
        NONE,           // already converged
    }

    /** Decides the action for one record; either side may be absent. */
    fun <T : SyncDoc> reconcile(local: T?, remote: T?): Action = when {
        local == null && remote == null -> Action.NONE
        local == null && remote != null ->
            if (remote.deletedAt > 0L) Action.NONE else Action.UPSERT_LOCAL
        remote == null -> Action.PUSH_REMOTE
        else -> {
            val l = local!!
            val r = remote
            when {
                r.updatedAt > l.updatedAt ->
                    if (r.deletedAt > 0L) Action.DELETE_LOCAL else Action.UPSERT_LOCAL
                l.updatedAt > r.updatedAt -> Action.PUSH_REMOTE
                else -> Action.NONE
            }
        }
    }

    /**
     * Reconciles two full sets by id and returns, for each id, the action to take. Safe to run
     * on every manual/background sync — deterministic ids mean it can never produce duplicates.
     */
    fun <T : SyncDoc> reconcileSets(local: Collection<T>, remote: Collection<T>): Map<String, Action> {
        val byIdLocal = local.associateBy { it.id }
        val byIdRemote = remote.associateBy { it.id }
        val ids = byIdLocal.keys + byIdRemote.keys
        return ids.associateWith { reconcile(byIdLocal[it], byIdRemote[it]) }
    }

    /**
     * Secondary, content-level dedup: if a live record with the same [checksum] already exists,
     * a new upload should reuse it instead of creating a second object. Returns the doc to reuse
     * or null when the content is genuinely new. (The deterministic id is the primary guard;
     * this catches "same bytes, different id" — e.g. the same photo shared from two apps.)
     */
    fun findDuplicate(existing: Collection<FileDoc>, checksum: String?): FileDoc? {
        if (checksum.isNullOrBlank()) return null
        return existing.firstOrNull { it.deletedAt == 0L && it.checksum == checksum }
    }
}
