package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SChapter : Serializable {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
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
        fun create(): SChapter = SChapterImpl()
    }
}

class SChapterImpl : SChapter {
    override lateinit var url: String
    override lateinit var name: String
    override var date_upload: Long = 0L
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
}
