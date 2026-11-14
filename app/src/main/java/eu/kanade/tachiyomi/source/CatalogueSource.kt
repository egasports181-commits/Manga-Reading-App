package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.network.awaitSingle
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import rx.Observable

interface CatalogueSource : Source {
    override val lang: String
    val supportsLatest: Boolean

    @Deprecated("Use suspend getPopularManga instead", replaceWith = ReplaceWith("getPopularManga(page)"))
    fun fetchPopularManga(page: Int): Observable<MangasPage>

    @Deprecated("Use suspend getSearchManga instead", replaceWith = ReplaceWith("getSearchManga(page, query, filters)"))
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage>

    @Deprecated("Use suspend getLatestUpdates instead", replaceWith = ReplaceWith("getLatestUpdates(page)"))
    fun fetchLatestUpdates(page: Int): Observable<MangasPage>

    @Suppress("DEPRECATION")
    suspend fun getPopularManga(page: Int): MangasPage =
        fetchPopularManga(page).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage =
        fetchSearchManga(page, query, filters).awaitSingle()

    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): MangasPage =
        fetchLatestUpdates(page).awaitSingle()

    fun getFilterList(): FilterList = FilterList()
}
