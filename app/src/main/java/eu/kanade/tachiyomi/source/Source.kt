package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.network.awaitSingle
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import rx.Observable

interface Source {
    val id: Long
    val name: String
    val lang: String
        get() = ""

    @Deprecated("Use suspend getMangaDetails instead", replaceWith = ReplaceWith("getMangaDetails(manga)"))
    fun fetchMangaDetails(manga: SManga): Observable<SManga> =
        Observable.error(UnsupportedOperationException("fetchMangaDetails not implemented"))

    @Deprecated("Use suspend getChapterList instead", replaceWith = ReplaceWith("getChapterList(manga)"))
    fun fetchChapterList(manga: SManga): Observable<List<SChapter>> =
        Observable.error(UnsupportedOperationException("fetchChapterList not implemented"))

    @Deprecated("Use suspend getPageList instead", replaceWith = ReplaceWith("getPageList(chapter)"))
    fun fetchPageList(chapter: SChapter): Observable<List<Page>> =
        Observable.error(UnsupportedOperationException("fetchPageList not implemented"))

    @Suppress("DEPRECATION")
    suspend fun getMangaDetails(manga: SManga): SManga =
        fetchMangaDetails(manga).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getChapterList(manga: SManga): List<SChapter> =
        fetchChapterList(manga).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getPageList(chapter: SChapter): List<Page> =
        fetchPageList(chapter).awaitSingle()
}
