package eu.kanade.tachiyomi.util

import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

fun Response.asJsoup(html: String? = null): Document {
    val bodyText = html ?: body?.string().orEmpty()
    return Jsoup.parse(bodyText, request.url.toString())
}
