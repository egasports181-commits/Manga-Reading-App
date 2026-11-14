package eu.kanade.tachiyomi.source.model

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
open class Page(
    val index: Int,
    val url: String = "",
    var imageUrl: String? = null,
    @Transient var uri: Uri? = null
) {
    val number: Int
        get() = index + 1

    @Transient
    @Volatile
    var status: State = State.QUEUE

    @Transient
    @Volatile
    var progress: Int = 0

    enum class State {
        QUEUE,
        LOAD_PAGE,
        DOWNLOAD_IMAGE,
        READY,
        ERROR
    }
}

data class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean
)
