package com.example.source

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.dao.SourcePreferenceDao
import com.example.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.json.JSONObject

class SourceRegistry(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val prefsRepo: AppPreferencesRepository,
    private val extensionDao: ExtensionDao,
    private val sourcePrefDao: SourcePreferenceDao
) {
    val mangaDex = MangaDexSourceAdapter(httpClient, sourcePrefDao)
    val localSaf = LocalSafSourceAdapter(context, prefsRepo)
    val demoSample = DemoSampleSourceAdapter(context)

    val activeSources: Flow<List<MangaSourceAdapter>> = combine(
        prefsRepo.preferences,
        extensionDao.observeActiveKomaPackages()
    ) { prefs, installedPkgs ->
        buildList {
            add(mangaDex)
            add(localSaf)
            installedPkgs.forEach { pkg ->
                runCatching {
                    val json = JSONObject(pkg.adapterJson)
                    DeclarativeJsonSourceAdapter(
                        id = pkg.pkgId,
                        name = pkg.name,
                        language = pkg.language,
                        descriptor = json,
                        httpClient = httpClient
                    )
                }.getOrNull()?.let { add(it) }
            }
            if (prefs.demoModeEnabled) {
                add(demoSample)
            }
        }
    }

    suspend fun getSourceById(sourceId: String): MangaSourceAdapter? {
        return activeSources.first().firstOrNull { it.id == sourceId }
            ?: if (sourceId == demoSample.id) demoSample else null
    }
}
