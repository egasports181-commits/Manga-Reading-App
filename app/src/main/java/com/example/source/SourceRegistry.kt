package com.example.source

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.dao.SourcePreferenceDao
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ExtensionFormat
import com.example.extension.ExtensionCompatibilityTranslator
import com.example.extension.ExtensionSourceRegistry
import com.example.extension.LoadedExtensionDescriptor
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient

data class ConfiguredSourceEntry(
    val adapter: MangaSourceAdapter,
    val isEnabled: Boolean
)

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
    val extensionSourceRegistry = ExtensionSourceRegistry(extensionDao, prefsRepo, httpClient, context)

    val verifiedExtensionDescriptors: Flow<List<LoadedExtensionDescriptor>> =
        extensionSourceRegistry.verifiedExtensionDescriptors

    val allConfiguredSources: Flow<List<ConfiguredSourceEntry>> = combine(
        prefsRepo.preferences,
        extensionDao.observeInstalledEnabledPackages()
    ) { prefs, installedPkgs ->
        buildList {
            add(ConfiguredSourceEntry(mangaDex, mangaDex.id !in prefs.disabledSourceIds))
            add(ConfiguredSourceEntry(localSaf, localSaf.id !in prefs.disabledSourceIds))
            installedPkgs.filter {
                it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id ||
                    it.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id
            }.forEach { pkg ->
                ExtensionCompatibilityTranslator.translateToSourceAdapter(pkg, httpClient, context)
                    .getOrNull()
                    ?.let { adapter ->
                        add(ConfiguredSourceEntry(adapter, adapter.id !in prefs.disabledSourceIds))
                    }
            }
            if (prefs.demoModeEnabled) {
                add(ConfiguredSourceEntry(demoSample, demoSample.id !in prefs.disabledSourceIds))
            }
        }
    }

    val activeSources: Flow<List<MangaSourceAdapter>> = allConfiguredSources.map { entries ->
        entries.filter { it.isEnabled }.map { it.adapter }
    }

    suspend fun getSourceById(sourceId: String): MangaSourceAdapter? {
        return allConfiguredSources.first().firstOrNull { it.adapter.id == sourceId }?.adapter
            ?: if (sourceId == demoSample.id) demoSample else null
    }
}
