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
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

@Suppress("DEPRECATION")
abstract class HttpSource : CatalogueSource {

    val network: NetworkHelper by injectLazy()

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
        }.map { response ->
            try {
                popularMangaParse(response)
            } finally {
                runCatching { response.close() }
            }
        }
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
            try {
                searchMangaParse(response)
            } finally {
                runCatching { response.close() }
            }
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
        }.map { response ->
            try {
                latestUpdatesParse(response)
            } finally {
                runCatching { response.close() }
            }
        }
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
            try {
                mangaDetailsParse(response).apply {
                    initialized = true
                }
            } finally {
                runCatching { response.close() }
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
        }.map { response ->
            try {
                chapterListParse(response)
            } finally {
                runCatching { response.close() }
            }
        }
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
        }.map { response ->
            try {
                pageListParse(response)
            } finally {
                runCatching { response.close() }
            }
        }
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
        }.map { response ->
            try {
                imageUrlParse(response)
            } finally {
                runCatching { response.close() }
            }
        }
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

    /**
     * Assigns the url of the manga without the scheme and domain. It saves some space from
     * the database and makes it easier to migrate domains.
     */
    fun SManga.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    /**
     * Assigns the url of the chapter without the scheme and domain.
     */
    fun SChapter.setUrlWithoutDomain(url: String) {
        this.url = getUrlWithoutDomain(url)
    }

    private fun getUrlWithoutDomain(orig: String): String {
        return try {
            val uri = URI(orig.replace(" ", "%20"))
            var out = uri.rawPath ?: uri.path.orEmpty()
            if (!uri.rawQuery.isNullOrBlank()) {
                out += "?" + uri.rawQuery
            }
            if (!uri.rawFragment.isNullOrBlank()) {
                out += "#" + uri.rawFragment
            }
            out
        } catch (_: URISyntaxException) {
            orig
        }
    }
}
