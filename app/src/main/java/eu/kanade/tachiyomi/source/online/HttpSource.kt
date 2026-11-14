package eu.kanade.tachiyomi.source.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.awaitSingle
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.injectLazy
import java.security.MessageDigest

@Suppress("DEPRECATION")
abstract class HttpSource : CatalogueSource {

    protected val network: NetworkHelper by injectLazy()

    abstract val baseUrl: String

    open val versionId: Int = 1

    override val id: Long by lazy {
        val key = "${name.lowercase()}/$lang/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xFF shl (8 * (7 - it)) }.reduce(Long::or) and Long.MAX_VALUE
    }

    val headers: Headers by lazy { headersBuilder().build() }

    open val client: OkHttpClient
        get() = network.client

    protected open fun headersBuilder(): Headers.Builder = Headers.Builder().apply {
        add("User-Agent", network.defaultUserAgentProvider())
    }

    override fun toString(): String = "$name (${lang.uppercase()})"

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        return Observable.defer {
            try {
                client.newCall(popularMangaRequest(page)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response -> popularMangaParse(response) }
    }

    protected abstract fun popularMangaRequest(page: Int): Request
    protected abstract fun popularMangaParse(response: Response): MangasPage

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return Observable.defer {
            try {
                client.newCall(searchMangaRequest(page, query, filters)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response ->
            searchMangaParse(response)
        }
    }

    protected abstract fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request
    protected abstract fun searchMangaParse(response: Response): MangasPage

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        return Observable.defer {
            try {
                client.newCall(latestUpdatesRequest(page)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response -> latestUpdatesParse(response) }
    }

    protected abstract fun latestUpdatesRequest(page: Int): Request
    protected abstract fun latestUpdatesParse(response: Response): MangasPage

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return Observable.defer {
            try {
                client.newCall(mangaDetailsRequest(manga)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response ->
            mangaDetailsParse(response).apply {
                initialized = true
            }
        }
    }

    open fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun mangaDetailsParse(response: Response): SManga

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return Observable.defer {
            try {
                client.newCall(chapterListRequest(manga)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response -> chapterListParse(response) }
    }

    open fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url, headers)
    }

    protected abstract fun chapterListParse(response: Response): List<SChapter>

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return Observable.defer {
            try {
                client.newCall(pageListRequest(chapter)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { response -> pageListParse(response) }
    }

    open fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url, headers)
    }

    protected abstract fun pageListParse(response: Response): List<Page>

    open fun fetchImageUrl(page: Page): Observable<String> {
        return Observable.defer {
            try {
                client.newCall(imageUrlRequest(page)).asObservableSuccess()
            } catch (t: Throwable) {
                Observable.error(t)
            }
        }.map { imageUrlParse(it) }
    }

    open suspend fun getImageUrl(page: Page): String =
        fetchImageUrl(page).awaitSingle()

    open fun imageUrlRequest(page: Page): Request {
        return GET(page.url, headers)
    }

    protected abstract fun imageUrlParse(response: Response): String

    open fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    open fun getMangaUrl(manga: SManga): String = baseUrl + manga.url
    open fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    @Suppress("UNUSED_PARAMETER")
    open fun prepareNewChapter(chapter: SChapter, manga: SManga) {
    }

    override fun getFilterList(): FilterList = FilterList()
}
