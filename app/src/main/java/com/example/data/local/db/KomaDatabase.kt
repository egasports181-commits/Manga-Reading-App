package com.example.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
        SourcePreferenceEntity::class,
        ExtensionInstallHistoryEntity::class,
        ExtensionCookieEntity::class
    ],
    version = 2,
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
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN colorHex INTEGER NOT NULL DEFAULT 4292943176")
                db.execSQL("ALTER TABLE categories ADD COLUMN iconName TEXT NOT NULL DEFAULT 'folder'")

                db.execSQL("ALTER TABLE chapters ADD COLUMN bookmarkedPagesJson TEXT NOT NULL DEFAULT '[]'")

                db.execSQL("ALTER TABLE extension_repos ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN website TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN iconUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN isEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN isBuiltIn INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN repoFormat TEXT NOT NULL DEFAULT 'auto'")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN lastSuccessAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN lastHttpStatus INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_repos ADD COLUMN extensionCount INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE extension_packages ADD COLUMN apkName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN apkUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN localApkPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN isNsfw INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN hasReadme INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN libVersion TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN sourcesJson TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN compatibilityStatus TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN compatibilityReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN installError TEXT")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN installedSignatureSha256 TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE extension_packages ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE download_tasks ADD COLUMN priority INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_install_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        pkgId TEXT NOT NULL,
                        extensionName TEXT NOT NULL,
                        action TEXT NOT NULL,
                        versionName TEXT NOT NULL,
                        status TEXT NOT NULL,
                        detail TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extension_install_history_pkgId ON extension_install_history(pkgId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extension_install_history_timestamp ON extension_install_history(timestamp)")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS extension_cookies (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        host TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        domain TEXT NOT NULL,
                        path TEXT NOT NULL,
                        expiresAt INTEGER NOT NULL,
                        secure INTEGER NOT NULL,
                        httpOnly INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_extension_cookies_host ON extension_cookies(host)")
            }
        }

        fun build(context: Context, dbName: String = "koma_reader.db"): KomaDatabase {
            lateinit var instance: KomaDatabase
            instance = Room.databaseBuilder(
                context.applicationContext,
                KomaDatabase::class.java,
                dbName
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Reading", sortOrder = 0, isDefault = true, colorHex = 0xFFE11D48L, iconName = "book")
                            )
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Completed", sortOrder = 1, isDefault = false, colorHex = 0xFF3B82F6L, iconName = "check")
                            )
                            instance.categoryDao().insertCategory(
                                CategoryEntity(name = "Plan to Read", sortOrder = 2, isDefault = false, colorHex = 0xFF10B981L, iconName = "bookmark")
                            )
                        }
                    }
                })
                .build()
            return instance
        }
    }
}
