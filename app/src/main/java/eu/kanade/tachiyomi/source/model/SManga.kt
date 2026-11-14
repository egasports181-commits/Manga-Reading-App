package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SManga : Serializable {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: UpdateStrategy
    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()
    }

    fun copy(): SManga = SMangaImpl().also { it.copyFrom(this) }

    fun copyFrom(other: SManga) {
        if (other.title.isNotBlank()) title = other.title
        if (other.url.isNotBlank()) url = other.url
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        status = other.status
        update_strategy = other.update_strategy
        if (!initialized) initialized = other.initialized
    }

    fun setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = java.net.URI(orig.replace(" ", "%20"))
            var out = uri.rawPath.orEmpty()
            if (!uri.rawQuery.isNullOrBlank()) out += "?${uri.rawQuery}"
            if (!uri.rawFragment.isNullOrBlank()) out += "#${uri.rawFragment}"
            out.ifBlank { orig }
        } catch (_: Exception) {
            orig
        }
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}

class SMangaImpl : SManga {
    override lateinit var url: String
    override lateinit var title: String
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = SManga.UNKNOWN
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var initialized: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SManga) return false
        return url == other.url && title == other.title
    }

    override fun hashCode(): Int = (if (::url.isInitialized) url.hashCode() else 0)
}
