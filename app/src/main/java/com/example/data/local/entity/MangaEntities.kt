package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "manga",
    indices = [Index("sourceId"), Index("inLibrary"), Index("lastReadAt")]
)
data class MangaEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val sourceName: String,
    val mangaKey: String,
    val title: String,
    val altTitle: String = "",
    val author: String = "",
    val artist: String = "",
    val description: String = "",
    val genres: String = "",
    val status: String = "UNKNOWN",
    val coverUrl: String = "",
    val language: String = "en",
    val contentRating: String = "safe",
    val releaseYear: Int? = null,
    val inLibrary: Boolean = false,
    val dateAdded: Long = 0L,
    val lastReadAt: Long = 0L,
    val latestChapterNumber: Double = 0.0,
    val latestChapterName: String = "",
    val latestUpdateAt: Long = 0L,
    val isBookmarked: Boolean = false,
    val totalReadingTimeMs: Long = 0L,
    val unreadCount: Int = 0,
    val totalChapters: Int = 0,
    val isDemoData: Boolean = false
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false,
    val colorHex: Long = 0xFFE11D48L,
    val iconName: String = "folder"
)

@Entity(
    tableName = "manga_category_cross_ref",
    primaryKeys = ["mangaId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class MangaCategoryCrossRef(
    val mangaId: String,
    val categoryId: Long
)

@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("mangaId"), Index("isDownloaded"), Index("isRead")]
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val mangaId: String,
    val chapterKey: String,
    val name: String,
    val chapterNumber: Double = 0.0,
    val volumeNumber: String = "",
    val scanlator: String = "",
    val uploadedAt: Long = 0L,
    val isRead: Boolean = false,
    val isBookmarked: Boolean = false,
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    val isDownloaded: Boolean = false,
    val localDirPath: String = "",
    val downloadedAt: Long = 0L,
    val sizeBytes: Long = 0L,
    val bookmarkedPagesJson: String = "[]"
)

@Entity(
    tableName = "reading_history",
    indices = [Index("mangaId"), Index("readAt")]
)
data class ReadingHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterName: String,
    val coverUrl: String,
    val readAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val pagesRead: Int = 0
)

@Entity(tableName = "extension_repos")
data class ExtensionRepoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val indexUrl: String,
    val signingKeyFingerprint: String = "",
    val isTrusted: Boolean = false,
    val lastRefreshedAt: Long = 0L,
    val lastError: String? = null,
    val displayName: String = "",
    val website: String = "",
    val iconUrl: String = "",
    val isEnabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val repoFormat: String = "auto",
    val lastSuccessAt: Long = 0L,
    val lastHttpStatus: Int = 0,
    val extensionCount: Int = 0
)

@Entity(
    tableName = "extension_packages",
    indices = [Index("repoId"), Index("isInstalled")]
)
data class ExtensionPackageEntity(
    @PrimaryKey val pkgId: String,
    val repoId: Long? = null,
    val name: String,
    val versionName: String,
    val versionCode: Int,
    val author: String = "",
    val description: String = "",
    val language: String = "en",
    val iconUrl: String = "",
    val format: String = "koma_json_v1",
    val adapterJson: String = "",
    val isInstalled: Boolean = false,
    val isEnabled: Boolean = true,
    val installedVersionCode: Int = 0,
    val hasUpdate: Boolean = false,
    val latestVersionCode: Int = 0,
    val latestVersionName: String = "",
    val signatureHash: String = "",
    val popularity: Int = 0,
    val permissionsSummary: String = "Network access to declared API host only",
    val apkName: String = "",
    val apkUrl: String = "",
    val localApkPath: String = "",
    val isNsfw: Boolean = false,
    val hasReadme: Boolean = false,
    val libVersion: String = "",
    val sourcesJson: String = "[]",
    val compatibilityStatus: String = "UNKNOWN",
    val compatibilityReason: String = "",
    val installError: String? = null,
    val installedSignatureSha256: String = "",
    val updatedAt: Long = 0L
)

@Entity(
    tableName = "download_tasks",
    indices = [Index("mangaId"), Index("status")]
)
data class DownloadTaskEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val mangaTitle: String,
    val chapterName: String,
    val sourceId: String,
    val chapterKey: String,
    val status: String = "QUEUED",
    val progress: Float = 0f,
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val bytesDownloaded: Long = 0L,
    val priority: Int = 0,
    val retryCount: Int = 0
)

@Entity(
    tableName = "update_entries",
    indices = [Index("mangaId"), Index("detectedAt")]
)
data class UpdateEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mangaId: String,
    val chapterId: String,
    val chapterName: String,
    val sourceId: String,
    val sourceName: String,
    val mangaTitle: String,
    val coverUrl: String,
    val detectedAt: Long = System.currentTimeMillis(),
    val isSeen: Boolean = false
)

@Entity(
    tableName = "tracking_entries",
    indices = [Index("mangaId"), Index("serviceId")]
)
data class TrackingEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val mangaId: String,
    val serviceId: String,
    val remoteMediaId: String = "",
    val remoteTitle: String = "",
    val status: String = "reading",
    val score: Double = 0.0,
    val chaptersRead: Int = 0,
    val totalChapters: Int = 0,
    val lastSyncedAt: Long = 0L,
    val syncState: String = "LOCAL_ONLY"
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val query: String,
    val sourceFilter: String = "all",
    val createdAt: Long = System.currentTimeMillis(),
    val isSaved: Boolean = false
)

@Entity(
    tableName = "source_preferences",
    primaryKeys = ["sourceId", "prefKey"]
)
data class SourcePreferenceEntity(
    val sourceId: String,
    val prefKey: String,
    val prefValue: String
)

@Entity(
    tableName = "extension_install_history",
    indices = [Index("pkgId"), Index("timestamp")]
)
data class ExtensionInstallHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val pkgId: String,
    val extensionName: String,
    val action: String,
    val versionName: String,
    val status: String,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "extension_cookies",
    indices = [Index("host")]
)
data class ExtensionCookieEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val host: String,
    val name: String,
    val value: String,
    val domain: String,
    val path: String = "/",
    val expiresAt: Long = 0L,
    val secure: Boolean = true,
    val httpOnly: Boolean = false
)
