package eu.kanade.tachiyomi.network

import android.content.Context
import com.example.network.KomaDns
import okhttp3.OkHttpClient

open class NetworkHelper(
    val context: Context,
    open val client: OkHttpClient
) {
    open val cloudflareClient: OkHttpClient
        get() = client

    open fun defaultUserAgentProvider(): String = KomaDns.USER_AGENT
}
