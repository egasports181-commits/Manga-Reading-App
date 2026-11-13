package com.example.domain.model

enum class TrackerService(
    val id: String,
    val displayName: String,
    val supportsOAuthTokenSync: Boolean,
    val apiEndpointNote: String
) {
    ANILIST("anilist", "AniList", true, "GraphQL API (Bearer OAuth Token)"),
    MYANIMELIST("mal", "MyAnimeList", true, "MAL v2 REST API (Bearer OAuth Token)"),
    KITSU("kitsu", "Kitsu", true, "Kitsu JSON:API (OAuth Token)"),
    MANGAUPDATES("mangaupdates", "MangaUpdates", false, "Local tracking ledger (OAuth app registration not configured)"),
    MANGABAKA("mangabaka", "MangaBaka", false, "Local tracking ledger (Community API pending)");

    companion object {
        fun fromId(id: String): TrackerService = entries.firstOrNull { it.id == id } ?: ANILIST
    }
}

enum class TrackingStatus(val id: String, val label: String) {
    READING("reading", "Reading"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped"),
    PLAN_TO_READ("plan_to_read", "Plan to Read");

    companion object {
        fun fromId(id: String): TrackingStatus = entries.firstOrNull { it.id == id } ?: READING
    }
}

enum class SyncState(val label: String) {
    LOCAL_ONLY("Local Only"),
    SYNCED("Synced"),
    LOCAL_AHEAD("Local Changes Pending"),
    CONFLICT("Sync Conflict")
}

data class RemoteTrackerSearchResult(
    val remoteMediaId: String,
    val title: String,
    val totalChapters: Int,
    val coverUrl: String = ""
)
