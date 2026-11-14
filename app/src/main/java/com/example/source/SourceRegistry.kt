package com.example.source

import android.content.Context
import com.example.data.local.dao.ExtensionDao
import com.example.data.local.dao.SourcePreferenceDao
import com.example.data.preferences.AppPreferencesRepository
import com.example.domain.model.ExtensionFormat
import com.example.extension.ExtensionCompatibilityTranslator
import com.example.extension.ExtensionSourceRegistry
import com.example.extension.LoadedExtensionDescriptor
import com.example.extension.MihonExtensionLoader
import com.example.extension.MihonLoadedState
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
    private val sourcePrefDao: SourcePreferenceDao,
    val mihonLoader: MihonExtensionLoader? = null
) {
    val mangaDex = MangaDexSourceAdapter(httpClient, sourcePrefDao)
    val localSaf = LocalSafSourceAdapter(context, prefsRepo)
    val demoSample = DemoSampleSourceAdapter(context)
    val extensionSourceRegistry = ExtensionSourceRegistry(
        extensionDao = extensionDao,
        prefsRepo = prefsRepo,
        httpClient = httpClient,
        context = context,
        mihonLoader = mihonLoader
    )

    val verifiedExtensionDescriptors: Flow<List<LoadedExtensionDescriptor>> =
        extensionSourceRegistry.verifiedExtensionDescriptors

    private val mihonStateFlow: Flow<MihonLoadedState> =
        mihonLoader?.state ?: MutableStateFlow(MihonLoadedState())

    val allConfiguredSources: Flow<List<ConfiguredSourceEntry>> = combine(
        prefsRepo.preferences,
        extensionDao.observeInstalledEnabledPackages(),
        mihonStateFlow
    ) { prefs, installedPkgs, mihonState ->
        val seenIds = mutableSetOf<String>()
        buildList {
            fun addUnique(entry: ConfiguredSourceEntry) {
                if (seenIds.add(entry.adapter.id)) {
                    add(entry)
                }
            }

            addUnique(ConfiguredSourceEntry(mangaDex, mangaDex.id !in prefs.disabledSourceIds))
            addUnique(ConfiguredSourceEntry(localSaf, localSaf.id !in prefs.disabledSourceIds))

            installedPkgs.filter {
                it.format == ExtensionFormat.KOMA_JSON_ADAPTER.id ||
                    it.format == ExtensionFormat.COMPANION_IPC_EXTENSION.id
            }.forEach { pkg ->
                ExtensionCompatibilityTranslator.translateToSourceAdapter(pkg, httpClient, context, mihonLoader)
                    .getOrNull()
                    ?.let { adapter ->
                        addUnique(ConfiguredSourceEntry(adapter, adapter.id !in prefs.disabledSourceIds))
                    }
            }

            mihonState.activeAdapters.forEach { adapter ->
                val extPkgDisabled = (adapter as? MihonSourceAdapter)?.extensionPackageName in prefs.disabledSourceIds
                val isEnabled = adapter.id !in prefs.disabledSourceIds && !extPkgDisabled
                addUnique(ConfiguredSourceEntry(adapter, isEnabled))
            }

            if (prefs.demoModeEnabled) {
                addUnique(ConfiguredSourceEntry(demoSample, demoSample.id !in prefs.disabledSourceIds))
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
