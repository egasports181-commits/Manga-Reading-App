package com.example.domain.model

enum class MangaStatus(val label: String) {
    ONGOING("Ongoing"),
    COMPLETED("Completed"),
    HIATUS("Hiatus"),
    CANCELLED("Cancelled"),
    UNKNOWN("Unknown");

    companion object {
        fun fromString(raw: String?): MangaStatus {
            return when (raw?.lowercase()?.trim()) {
                "ongoing", "releasing", "publishing" -> ONGOING
                "completed", "finished" -> COMPLETED
                "hiatus", "on hiatus" -> HIATUS
                "cancelled", "canceled", "discontinued" -> CANCELLED
                else -> UNKNOWN
            }
        }
    }
}

enum class ReadingMode(val id: String, val label: String) {
    LEFT_TO_RIGHT("ltr", "Left to Right"),
    RIGHT_TO_LEFT("rtl", "Right to Left (Manga)"),
    VERTICAL("vertical", "Vertical Paged"),
    CONTINUOUS_VERTICAL("continuous_vertical", "Continuous Vertical"),
    WEBTOON("webtoon", "Webtoon (Seamless)"),
    DOUBLE_PAGE("double_page", "Double Page Spread"),
    AUTO("auto", "Automatic Direction");

    companion object {
        fun fromId(id: String): ReadingMode = entries.firstOrNull { it.id == id } ?: RIGHT_TO_LEFT
    }
}

enum class ImageScaleMode(val id: String, val label: String) {
    FIT_WIDTH("fit_width", "Fit Width"),
    FIT_HEIGHT("fit_height", "Fit Height"),
    FIT_SCREEN("fit_screen", "Fit Screen"),
    ORIGINAL("original", "Original Size"),
    CUSTOM("custom", "Custom Scale");

    companion object {
        fun fromId(id: String): ImageScaleMode = entries.firstOrNull { it.id == id } ?: FIT_SCREEN
    }
}

enum class ReaderBackground(val id: String, val label: String, val colorHex: Long) {
    BLACK("black", "Pure Black", 0xFF000000),
    OBSIDIAN("obsidian", "Obsidian Gray", 0xFF14161E),
    SEPIA("sepia", "Warm Sepia", 0xFF2B231A),
    WHITE("white", "Paper White", 0xFFF5F5F0);

    companion object {
        fun fromId(id: String): ReaderBackground = entries.firstOrNull { it.id == id } ?: BLACK
    }
}

enum class OrientationLock(val id: String, val label: String) {
    FREE("free", "System / Auto"),
    PORTRAIT("portrait", "Lock Portrait"),
    LANDSCAPE("landscape", "Lock Landscape");

    companion object {
        fun fromId(id: String): OrientationLock = entries.firstOrNull { it.id == id } ?: FREE
    }
}

enum class LibrarySort(val id: String, val label: String) {
    TITLE("title", "Title"),
    DATE_ADDED("date_added", "Date Added"),
    LAST_READ("last_read", "Last Read"),
    LATEST_UPDATE("latest_update", "Latest Update"),
    UNREAD_COUNT("unread_count", "Unread Chapters");

    companion object {
        fun fromId(id: String): LibrarySort = entries.firstOrNull { it.id == id } ?: LAST_READ
    }
}

enum class ExtensionFormat(val id: String, val label: String, val executable: Boolean) {
    KOMA_JSON_ADAPTER("koma_json_v1", "Koma Declarative Adapter", true),
    EXTERNAL_APK_INCOMPATIBLE("mihon_apk_legacy", "Mihon APK (Incompatible)", false);

    companion object {
        fun fromId(id: String): ExtensionFormat =
            entries.firstOrNull { it.id == id } ?: EXTERNAL_APK_INCOMPATIBLE
    }
}

enum class DownloadStatus(val label: String) {
    QUEUED("Queued"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    COMPLETED("Downloaded"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

enum class HomeSectionType(val id: String, val title: String) {
    CONTINUE_READING("continue_reading", "Continue Reading"),
    RECENTLY_UPDATED("recently_updated", "Recently Updated"),
    RECENTLY_ADDED("recently_added", "Recently Added"),
    POPULAR_MANGA("popular_manga", "Popular Manga"),
    TRENDING_MANGA("trending_manga", "Trending Manga"),
    LATEST_CHAPTERS("latest_chapters", "Latest Chapters"),
    RECOMMENDED_SERIES("recommended_series", "Recommended Series"),
    RECENTLY_DOWNLOADED("recently_downloaded", "Recently Downloaded"),
    READING_HISTORY("reading_history", "Reading History"),
    PERSONALIZED("personalized", "Picked For You");

    companion object {
        fun defaultOrder(): List<HomeSectionType> = entries.toList()
    }
}
