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
    version = 2,
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

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
