package com.filewall.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.filewall.model.FileCategory
import com.filewall.model.VaultFolder
import com.filewall.model.VaultItem

class Converters {
    @TypeConverter
    fun toCategory(value: String?): FileCategory =
        FileCategory.entries.firstOrNull { it.name == value } ?: FileCategory.OTHER

    @TypeConverter
    fun fromCategory(value: FileCategory): String = value.name
}

@Database(
    entities = [VaultItem::class, VaultFolder::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val NAME = "filewall.db"

        /** v2 adds Recently Deleted (deletedAt) and Archive (archived) to existing vaults. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_items ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE vault_items ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v3 adds `updatedAt` to both tables for cloud sync's last-write-wins merge (see
         * FIREBASE_BLUEPRINT.md §2.2/§3.4). Backfilled from the existing `addedAt`/`createdAt`
         * so every row starts with a sane value instead of 0 — a fresh 0 would make every
         * pre-existing row look older than anything a device pulls from Firestore on first
         * sync, which is harmless but would trigger a needless full re-download.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vault_items ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE vault_items SET updatedAt = addedAt")
                db.execSQL("ALTER TABLE vault_folders ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE vault_folders SET updatedAt = createdAt")
            }
        }

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
