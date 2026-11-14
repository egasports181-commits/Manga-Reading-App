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
    BLACK("black", "AMOLED Black", 0xFF000000),
    OBSIDIAN("obsidian", "Slate Dark", 0xFF14161E),
    SEPIA("sepia", "Warm Sepia", 0xFF2B231A),
    WHITE("white", "Paper White", 0xFFF5F5F0);

    companion object {
        fun fromId(id: String): ReaderBackground = entries.firstOrNull { it.id == id } ?: BLACK
    }
}

enum class TapZoneLayout(val id: String, val label: String, val description: String) {
    SIDES("sides", "Left / Center / Right", "30% side zones flip pages, center toggles HUD"),
    L_SHAPE("l_shape", "L-Shaped", "Top & left edge previous, bottom & right next"),
    KINDLISH("kindlish", "Kindlish", "Left 25% previous, right 75% next, top/center HUD"),
    EDGES("edges", "Compact Edges", "Outer 18% edges flip pages, wide center HUD");

    companion object {
        fun fromId(id: String): TapZoneLayout = entries.firstOrNull { it.id == id } ?: SIDES
    }
}

enum class ReaderTransitionMode(val id: String, val label: String) {
    SLIDE("slide", "Slide"),
    FADE("fade", "Fade"),
    NONE("none", "Instant");

    companion object {
        fun fromId(id: String): ReaderTransitionMode = entries.firstOrNull { it.id == id } ?: SLIDE
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
    KOMA_JSON_ADAPTER("koma_json_v1", "Kiyomi Declarative Adapter", true),
    COMPANION_IPC_EXTENSION("koma_ipc_v1", "Companion IPC Extension", true),
    MIHON_PROTOBUF_INDEX("mihon_apk_pb", "Mihon Extension (index.pb)", true),
    MIHON_JSON_INDEX("mihon_apk_json", "Mihon Extension (index.json)", true),
    EXTERNAL_APK_INCOMPATIBLE("mihon_apk_legacy", "Mihon APK Package", false);

    companion object {
        fun fromId(id: String): ExtensionFormat =
            entries.firstOrNull { it.id == id } ?: EXTERNAL_APK_INCOMPATIBLE
    }
}

enum class ExtensionCompatibilityStatus(val label: String) {
    EXECUTABLE_KOMA_JSON("Compatible (kiyomi_json_v1)"),
    COMPANION_IPC_READY("Compatible (Companion IPC)"),
    NATIVE_EQUIVALENT_AVAILABLE("Native Adapter Available"),
    VERIFIED_MIHON_READY("Verified APK • Ready to Install"),
    INSTALLED_MIHON_ACTIVE("Installed • Active"),
    VERIFIED_MIHON_RUNTIME_RESTRICTED("Mihon APK • Pending Install / Trust"),
    COMPATIBLE_MIHON_METADATA("Compatible Metadata (v1.2–v1.6)"),
    LIB_VERSION_INCOMPATIBLE("Incompatible API Version"),
    UNSIGNED_APK("Unsigned APK"),
    MALFORMED_APK("Malformed / Corrupted APK"),
    DIFFERENT_ARCHITECTURE("Different Extension Architecture"),
    SIGNATURE_MISMATCH("Signing Key Fingerprint Mismatch"),
    UNTRUSTED_SIGNER("Untrusted Signer Certificate"),
    UNTRUSTED_REPO("Repository Untrusted"),
    MIHON_LOAD_FAILED("Extension Load Failed"),
    UNKNOWN("Pending Inspection")
}

enum class ApkIncompatibilityCategory(val code: String, val label: String) {
    NONE("OK", "Compatible"),
    API_VERSION_INCOMPATIBLE("A", "Cat A • Incompatible Extension API"),
    MALFORMED_OR_INCOMPLETE("B", "Cat B • Invalid, Unsigned, or Tampered APK"),
    MIHON_DEX_POLICY_RESTRICTED("C", "Cat C • Valid Mihon APK"),
    DIFFERENT_ARCHITECTURE("D", "Cat D • Different Extension Architecture"),
    UNTRUSTED("U", "Untrusted Signer or Repository")
}

enum class ExtensionLifecycleState(val label: String) {
    ACTIVE("Active"),
    COMPATIBLE("Compatible"),
    VERIFIED("Verified"),
    DOWNLOADED("Downloaded"),
    UNSUPPORTED("Unsupported")
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
    FAVORITE_SOURCES("favorite_sources", "Favorite Sources"),
    RECENTLY_UPDATED("recently_updated", "Recently Updated"),
    RECENTLY_ADDED("recently_added", "Recently Added"),
    POPULAR_MANGA("popular_manga", "Popular Manga"),
    TRENDING_MANGA("trending_manga", "Trending Manga"),
    LATEST_CHAPTERS("latest_chapters", "Latest Releases"),
    RECOMMENDED_SERIES("recommended_series", "New Arrivals"),
    RECENTLY_DOWNLOADED("recently_downloaded", "Downloaded"),
    READING_HISTORY("reading_history", "Reading History"),
    PERSONALIZED("personalized", "Picked For You");

    companion object {
        fun defaultOrder(): List<HomeSectionType> = entries.toList()
    }
}
