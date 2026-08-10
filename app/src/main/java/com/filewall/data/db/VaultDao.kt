package com.filewall.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filewall.model.FileCategory
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    // ------------------------------------------------------------------ items

    // Live files only: nothing in the trash or the archive shows in the normal grid.
    @Query("SELECT * FROM vault_items WHERE hidden = :hidden AND deletedAt = 0 AND archived = 0 ORDER BY addedAt DESC")
    fun observeItems(hidden: Boolean): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE hidden = :hidden AND deletedAt = 0 AND archived = 1 ORDER BY addedAt DESC")
    fun observeArchive(hidden: Boolean): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE hidden = :hidden AND deletedAt > 0 ORDER BY deletedAt DESC")
    fun observeTrash(hidden: Boolean): Flow<List<VaultItem>>

    @Query("SELECT COUNT(*) FROM vault_items WHERE hidden = :hidden AND deletedAt = 0 AND archived = 1")
    fun observeArchiveCount(hidden: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM vault_items WHERE hidden = :hidden AND deletedAt > 0")
    fun observeTrashCount(hidden: Boolean): Flow<Int>

    @Query("SELECT * FROM vault_items WHERE hidden = :hidden AND deletedAt > 0")
    suspend fun trashedItems(hidden: Boolean): List<VaultItem>

    @Query("SELECT * FROM vault_items WHERE deletedAt > 0 AND deletedAt < :cutoff")
    suspend fun expiredTrash(cutoff: Long): List<VaultItem>

    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun observeAllItems(): Flow<List<VaultItem>>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    fun observeItem(id: String): Flow<VaultItem?>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun findItem(id: String): VaultItem?

    @Query("SELECT * FROM vault_items")
    suspend fun allItems(): List<VaultItem>

    @Query("SELECT * FROM vault_items WHERE folderId = :folderId")
    suspend fun itemsInFolder(folderId: String): List<VaultItem>

    // Only live members of a folder — the ones destroyed when the folder is deleted. Items
    // already in Recently Deleted or Archive are left alone.
    @Query("SELECT * FROM vault_items WHERE folderId = :folderId AND deletedAt = 0 AND archived = 0")
    suspend fun liveItemsInFolder(folderId: String): List<VaultItem>

    @Query("UPDATE vault_items SET folderId = NULL WHERE folderId = :folderId")
    suspend fun detachFolder(folderId: String)

    @Query("SELECT COUNT(*) FROM vault_items WHERE folderId = :folderId")
    fun observeFolderCount(folderId: String): Flow<Int>

    @Query("SELECT folderId AS folderId, COUNT(*) AS count FROM vault_items WHERE folderId IS NOT NULL AND deletedAt = 0 AND archived = 0 GROUP BY folderId")
    fun observeFolderCounts(): Flow<List<FolderCount>>

    // Storage counts everything still stored, including the archive, but not the trash —
    // trashed files are on their way out.
    @Query("SELECT category AS category, SUM(sizeBytes) AS bytes FROM vault_items WHERE deletedAt = 0 GROUP BY category")
    fun observeCategoryTotals(): Flow<List<CategoryTotal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: VaultItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<VaultItem>)

    @Update
    suspend fun updateItem(item: VaultItem)

    @Delete
    suspend fun deleteItem(item: VaultItem)

    @Query("DELETE FROM vault_items WHERE id IN (:ids)")
    suspend fun deleteItemsByIds(ids: List<String>)

    @Query("UPDATE vault_items SET folderId = :folderId WHERE id = :id")
    suspend fun setItemFolder(id: String, folderId: String?)

    @Query("UPDATE vault_items SET hidden = :hidden WHERE id IN (:ids)")
    suspend fun setItemsHidden(ids: List<String>, hidden: Boolean)

    @Query("UPDATE vault_items SET name = :name WHERE id = :id")
    suspend fun renameItem(id: String, name: String)

    // Trashing also clears the archive flag so a file lives in exactly one place at a time.
    @Query("UPDATE vault_items SET deletedAt = :timestamp, archived = 0 WHERE id IN (:ids)")
    suspend fun trashItems(ids: List<String>, timestamp: Long)

    @Query("UPDATE vault_items SET deletedAt = 0, archived = 0 WHERE id IN (:ids)")
    suspend fun restoreItems(ids: List<String>)

    @Query("UPDATE vault_items SET archived = :archived WHERE id IN (:ids)")
    suspend fun setItemsArchived(ids: List<String>, archived: Boolean)

    // ---------------------------------------------------------------- folders

    @Query("SELECT * FROM vault_folders WHERE hidden = :hidden ORDER BY createdAt ASC")
    fun observeFolders(hidden: Boolean): Flow<List<VaultFolder>>

    @Query("SELECT * FROM vault_folders WHERE hidden = :hidden")
    suspend fun foldersFor(hidden: Boolean): List<VaultFolder>

    @Query("SELECT * FROM vault_folders")
    suspend fun allFolders(): List<VaultFolder>

    @Query("SELECT * FROM vault_folders WHERE id = :id")
    suspend fun findFolder(id: String): VaultFolder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: VaultFolder)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolders(folders: List<VaultFolder>)

    @Query("UPDATE vault_folders SET name = :name WHERE id = :id")
    suspend fun renameFolder(id: String, name: String)

    @Query("UPDATE vault_folders SET colorIndex = :colorIndex WHERE id = :id")
    suspend fun recolorFolder(id: String, colorIndex: Int)

    @Query("DELETE FROM vault_folders WHERE id = :id")
    suspend fun deleteFolderRow(id: String)

    @Query("DELETE FROM vault_folders WHERE hidden = :hidden")
    suspend fun deleteFoldersRows(hidden: Boolean)

    // ----------------------------------------------------------------- wiping

    @Query("DELETE FROM vault_items")
    suspend fun clearItems()

    @Query("DELETE FROM vault_folders")
    suspend fun clearFolders()
}

/** Projection for [VaultDao.observeFolderCounts]. */
data class FolderCount(val folderId: String, val count: Int)

/** Projection for [VaultDao.observeCategoryTotals]. */
data class CategoryTotal(val category: FileCategory, val bytes: Long)
