package com.filewall.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class VaultDatabase : RoomDatabase() {

    abstract fun vaultDao(): VaultDao

    companion object {
        private const val NAME = "filewall.db"

        fun create(context: Context): VaultDatabase =
            Room.databaseBuilder(context.applicationContext, VaultDatabase::class.java, NAME)
                .build()
    }
}
