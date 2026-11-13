package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.dao.*
import com.example.data.local.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        MangaEntity::class,
        CategoryEntity::class,
        MangaCategoryCrossRef::class,
        ChapterEntity::class,
        ReadingHistoryEntity::class,
        ExtensionRepoEntity::class,
        ExtensionPackageEntity::class,
        DownloadTaskEntity::class,
        UpdateEntryEntity::class,
        TrackingEntryEntity::class,
        SearchHistoryEntity::class,
        SourcePreferenceEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class KomaDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun categoryDao(): CategoryDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun extensionDao(): ExtensionDao
    abstract fun downloadDao(): DownloadDao
    abstract fun updateDao(): UpdateDao
    abstract fun trackingDao(): TrackingDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun sourcePreferenceDao(): SourcePreferenceDao

    companion object {
        fun build(context: Context): KomaDatabase {
            lateinit var instance: KomaDatabase
            instance = Room.databaseBuilder(
                context.applicationContext,
                KomaDatabase::class.java,
                "koma_reader.db"
            )
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Reading", sortOrder = 0, isDefault = true)
                            )
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Completed", sortOrder = 1, isDefault = false)
                            )
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Plan to Read", sortOrder = 2, isDefault = false)
                            )
                        }
                    }
                })
                .build()
            return instance
        }
    }
}
