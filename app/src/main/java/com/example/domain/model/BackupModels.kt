package com.example.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class KomaBackupV1(
    val schemaVersion: Int = 1,
    val appVersion: String = "1.0.0",
    val createdAt: Long = System.currentTimeMillis(),
    val categories: List<BackupCategory> = emptyList(),
    val manga: List<BackupManga> = emptyList(),
    val tracking: List<BackupTracking> = emptyList(),
    val sourcePreferences: List<BackupSourcePref> = emptyList(),
    val settings: BackupSettings? = null
)

@Serializable
data class BackupCategory(
    val name: String,
    val sortOrder: Int,
    val isDefault: Boolean
)

@Serializable
data class BackupManga(
    val id: String,
    val sourceId: String,
    val mangaKey: String,
    val title: String,
    val altTitle: String,
    val author: String,
    val artist: String,
    val description: String,
    val genres: String,
    val status: String,
    val coverUrl: String,
    val language: String,
    val contentRating: String,
    val releaseYear: Int? = null,
    val dateAdded: Long,
    val lastReadAt: Long,
    val isBookmarked: Boolean,
    val totalReadingTimeMs: Long,
    val categoryNames: List<String> = emptyList(),
    val chapters: List<BackupChapter> = emptyList()
)

@Serializable
data class BackupChapter(
    val chapterKey: String,
    val name: String,
    val chapterNumber: Double,
    val isRead: Boolean,
    val isBookmarked: Boolean,
    val lastPageRead: Int,
    val totalPages: Int
)

@Serializable
data class BackupTracking(
    val mangaId: String,
    val serviceId: String,
    val remoteMediaId: String,
    val remoteTitle: String,
    val status: String,
    val score: Double,
    val chaptersRead: Int,
    val totalChapters: Int
)

@Serializable
data class BackupSourcePref(
    val sourceId: String,
    val key: String,
    val value: String
)

@Serializable
data class BackupSettings(
    val themeMode: String,
    val amoledBlack: Boolean,
    val accentId: String,
    val defaultReadingMode: String,
    val defaultScaleMode: String,
    val wifiOnlyDownloads: Boolean,
    val concurrentDownloads: Int
)

data class ReadingStatsSummary(
    val totalLibraryManga: Int = 0,
    val totalChaptersRead: Int = 0,
    val totalReadingTimeMs: Long = 0L,
    val downloadedChaptersCount: Int = 0,
    val downloadedBytes: Long = 0L,
    val dailyStreakDays: Int = 0,
    val favoriteGenres: List<Pair<String, Int>> = emptyList(),
    val monthlyActivity: List<Pair<String, Int>> = emptyList(),
    val mostReadManga: List<Pair<String, Long>> = emptyList()
)
