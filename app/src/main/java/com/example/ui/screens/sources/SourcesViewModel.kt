package com.example.ui.screens.sources

import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ExtensionInstallHistoryEntity
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.data.local.entity.SearchHistoryEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.UserPreferences
import com.example.data.repository.ExtensionRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.*
import com.example.extension.ExtensionInstallEvent
import com.example.extension.LoadedExtensionDescriptor
import com.example.network.NetworkErrorFormatter
import com.example.source.ConfiguredSourceEntry
import com.example.source.SourceRegistry
import com.example.ui.components.MangaCardUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class SourceMetaUi(
    val id: String,
    val name: String,
    val language: String,
    val category: String,
    val baseUrl: String,
    val supportsFilters: Boolean,
    val supportsLogin: Boolean,
    val isDemo: Boolean,
    val isEnabled: Boolean = true,
    val healthStatus: SourceHealthStatus? = null
) {
    val isAvailable: Boolean? get() = healthStatus?.isOnline
}

enum class ExtensionSortOption(val label: String) {
    POPULARITY("Popularity"),
    NAME("Name"),
    LANGUAGE("Language"),
    VERSION("Version"),
    RECENT("Recently Updated"),
    UPDATES("Update Status")
}

enum class ExtensionStoreTab(val label: String) {
    ALL("All"),
    INSTALLED("Installed"),
    NOT_INSTALLED("Not installed"),
    UPDATES("Updates"),
    REPOSITORIES("Repositories")
}

enum class ExtensionCompatFilter(val label: String) {
    ALL("All Formats"),
    EXECUTABLE("Compatible (Kiyomi)"),
    VERIFIED_APK("Verified / Trust Needed"),
    ISSUES("Unsupported / Issues")
}

data class SourcesUiState(
    val sources: List<SourceMetaUi> = emptyList(),
    val verifiedExtensionDescriptors: List<LoadedExtensionDescriptor> = emptyList(),
    val selectedLangFilter: String = "all",
    val selectedCategoryFilter: String = "all",
    val repos: List<ExtensionRepoEntity> = emptyList(),
    val packages: List<ExtensionPackageEntity> = emptyList(),
    val totalIndexedPackagesCount: Int = 0,
    val installedPackagesCount: Int = 0,
    val notInstalledPackagesCount: Int = 0,
    val updatesPackagesCount: Int = 0,
    val allPackageLanguages: List<String> = emptyList(),
    val extSubTab: ExtensionStoreTab = ExtensionStoreTab.ALL,
    val extSearchQuery: String = "",
    val extLangFilter: String = "all",
    val extRepoFilterId: Long? = null,
    val extCompatFilter: ExtensionCompatFilter = ExtensionCompatFilter.ALL,
    val extSort: ExtensionSortOption = ExtensionSortOption.POPULARITY,
    val extDownloadProgress: Map<String, Float> = emptyMap(),
    val installHistory: List<ExtensionInstallHistoryEntity> = emptyList(),
    val isRefreshingRepos: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val exportedReposJson: String? = null,
    val safLocalFolderUri: String = "",
    // Global Search
    val globalQuery: String = "",
    val globalFilters: SearchFilters = SearchFilters(),
    val disabledSearchSourceIds: Set<String> = emptySet(),
    val isSearchingGlobal: Boolean = false,
    val globalResults: Map<String, List<MangaCardUi>> = emptyMap(),
    val globalSourceErrors: Map<String, String> = emptyMap(),
    val duplicateTitlesAcrossSources: Set<String> = emptySet(),
    val searchHistory: List<SearchHistoryEntity> = emptyList(),
    val searchSuggestions: List<String> = emptyList()
)

data class BrowseSourceUiState(
    val sourceId: String = "",
    val sourceName: String = "",
    val mode: String = "popular",
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val items: List<MangaCardUi> = emptyList(),
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val preferences: List<SourcePreferenceItem> = emptyList(),
    val supportsLogin: Boolean = false,
    val loginFeedback: String? = null
)

class SourcesViewModel(
    private val sourceRegistry: SourceRegistry,
    private val extRepo: ExtensionRepository,
    private val mangaRepo: MangaRepository,
    private val prefsRepo: AppPreferencesRepository
) : ViewModel() {

    private val healthCache = MutableStateFlow<Map<String, SourceHealthStatus>>(emptyMap())
    private val localUiState = MutableStateFlow(SourcesLocalControls())
    private val browseState = MutableStateFlow(BrowseSourceUiState())
    private var browseJob: Job? = null
    private var healthJob: Job? = null
    private val _osActionIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 16)
    val osActionIntents: SharedFlow<Intent> = _osActionIntents.asSharedFlow()

    private data class SourcesLocalControls(
        val langFilter: String = "all",
        val categoryFilter: String = "all",
        val extSubTab: ExtensionStoreTab = ExtensionStoreTab.ALL,
        val extQuery: String = "",
        val extLangFilter: String = "all",
        val extRepoFilterId: Long? = null,
        val extCompatFilter: ExtensionCompatFilter = ExtensionCompatFilter.ALL,
        val extSort: ExtensionSortOption = ExtensionSortOption.POPULARITY,
        val isRefreshingRepos: Boolean = false,
        val statusMsg: String? = null,
        val errorMsg: String? = null,
        val exportedReposJson: String? = null,
        val globalQuery: String = "",
        val globalFilters: SearchFilters = SearchFilters(),
        val isSearchingGlobal: Boolean = false,
        val globalResults: Map<String, List<MangaCardUi>> = emptyMap(),
        val globalErrors: Map<String, String> = emptyMap()
    )

    private val extensionFlows = combine(
        extRepo.repos,
        extRepo.packages,
        extRepo.downloadProgress,
        extRepo.installHistory,
        sourceRegistry.verifiedExtensionDescriptors
    ) { repos, pkgs, progress, history, descriptors ->
        ExtensionSnapshot(repos, pkgs, progress, history, descriptors)
    }

    private data class ExtensionSnapshot(
        val repos: List<ExtensionRepoEntity>,
        val pkgs: List<ExtensionPackageEntity>,
        val progress: Map<String, Float>,
        val history: List<ExtensionInstallHistoryEntity>,
        val descriptors: List<LoadedExtensionDescriptor>
    )

    val uiState: StateFlow<SourcesUiState> = combine(
        sourceRegistry.allConfiguredSources,
        healthCache,
        extensionFlows,
        mangaRepo.searchHistory,
        prefsRepo.preferences,
        localUiState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val configured = values[0] as List<ConfiguredSourceEntry>
        @Suppress("UNCHECKED_CAST")
        val healthMap = values[1] as Map<String, SourceHealthStatus>
        val extSnap = values[2] as ExtensionSnapshot
        @Suppress("UNCHECKED_CAST")
        val history = values[3] as List<SearchHistoryEntity>
        val prefs = values[4] as UserPreferences
        val local = values[5] as SourcesLocalControls

        val metaList = configured.map { entry ->
            val a = entry.adapter
            SourceMetaUi(
                id = a.id,
                name = a.name,
                language = a.language,
                category = a.category,
                baseUrl = a.baseUrl,
                supportsFilters = a.supportsFilters,
                supportsLogin = a.supportsLogin,
                isDemo = a.isDemoSource,
                isEnabled = entry.isEnabled,
                healthStatus = healthMap[a.id]
            )
        }.filter {
            (local.langFilter == "all" || it.language.equals(local.langFilter, true)) &&
                (local.categoryFilter == "all" || it.category.equals(local.categoryFilter, true))
        }

        val enabledRepoIds = extSnap.repos.filter { it.isEnabled }.map { it.id }.toSet()
        val enabledRepoPackages = extSnap.pkgs.filter { pkg ->
            pkg.repoId == null || pkg.repoId in enabledRepoIds || pkg.isInstalled || pkg.localApkPath.isNotBlank()
        }
        val allLangs = enabledRepoPackages.map { it.language.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val trimmedExtQuery = local.extQuery.trim()
        val filteredPkgs = enabledRepoPackages.filter { pkg ->
            val matchesQuery = trimmedExtQuery.isEmpty() ||
                pkg.name.contains(trimmedExtQuery, ignoreCase = true) ||
                pkg.pkgId.contains(trimmedExtQuery, ignoreCase = true) ||
                pkg.language.contains(trimmedExtQuery, ignoreCase = true) ||
                pkg.author.contains(trimmedExtQuery, ignoreCase = true) ||
                pkg.sourcesJson.contains(trimmedExtQuery, ignoreCase = true)
            val matchesLang = local.extLangFilter == "all" || pkg.language.equals(local.extLangFilter, true)
            val matchesRepo = local.extRepoFilterId == null || pkg.repoId == local.extRepoFilterId
            val matchesSubTab = when (local.extSubTab) {
                ExtensionStoreTab.ALL -> true
                ExtensionStoreTab.INSTALLED -> pkg.isInstalled
                ExtensionStoreTab.NOT_INSTALLED -> !pkg.isInstalled
                ExtensionStoreTab.UPDATES -> (pkg.isInstalled || pkg.localApkPath.isNotBlank()) && pkg.hasUpdate
                ExtensionStoreTab.REPOSITORIES -> true
            }
            val matchesCompat = when (local.extCompatFilter) {
                ExtensionCompatFilter.ALL -> true
                ExtensionCompatFilter.EXECUTABLE ->
                    pkg.compatibilityStatus == ExtensionCompatibilityStatus.EXECUTABLE_KOMA_JSON.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.COMPANION_IPC_READY.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.INSTALLED_MIHON_ACTIVE.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.COMPATIBLE_MIHON_METADATA.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.NATIVE_EQUIVALENT_AVAILABLE.name
                ExtensionCompatFilter.VERIFIED_APK ->
                    (pkg.localApkPath.isNotBlank() && !pkg.isInstalled) ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name
                ExtensionCompatFilter.ISSUES ->
                    !pkg.installError.isNullOrBlank() ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNSIGNED_APK.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.MALFORMED_APK.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.SIGNATURE_MISMATCH.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.LIB_VERSION_INCOMPATIBLE.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.DIFFERENT_ARCHITECTURE.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_SIGNER.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.UNTRUSTED_REPO.name ||
                        pkg.compatibilityStatus == ExtensionCompatibilityStatus.MIHON_LOAD_FAILED.name
            }
            matchesQuery && matchesLang && matchesRepo && matchesSubTab && matchesCompat
        }.let { list ->
            when (local.extSort) {
                ExtensionSortOption.NAME -> list.sortedBy { it.name.lowercase() }
                ExtensionSortOption.LANGUAGE -> list.sortedBy { it.language.lowercase() }
                ExtensionSortOption.POPULARITY -> list.sortedByDescending { it.popularity }
                ExtensionSortOption.VERSION -> list.sortedByDescending { it.latestVersionCode }
                ExtensionSortOption.RECENT -> list.sortedByDescending { it.updatedAt }
                ExtensionSortOption.UPDATES -> list.sortedByDescending { it.hasUpdate }
            }
        }

        val duplicateTitles = local.globalResults.values
            .flatMap { list -> list.map { it.title.trim().lowercase() }.distinct() }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys

        val suggestions = if (local.globalQuery.isBlank()) {
            history.take(5).map { it.query }
        } else {
            history.map { it.query }
                .filter { it.contains(local.globalQuery, ignoreCase = true) && !it.equals(local.globalQuery, true) }
                .distinct()
                .take(5)
        }

        SourcesUiState(
            sources = metaList,
            verifiedExtensionDescriptors = extSnap.descriptors,
            selectedLangFilter = local.langFilter,
            selectedCategoryFilter = local.categoryFilter,
            repos = extSnap.repos,
            packages = filteredPkgs,
            totalIndexedPackagesCount = enabledRepoPackages.size,
            installedPackagesCount = enabledRepoPackages.count { it.isInstalled },
            notInstalledPackagesCount = enabledRepoPackages.count { !it.isInstalled },
            updatesPackagesCount = enabledRepoPackages.count { (it.isInstalled || it.localApkPath.isNotBlank()) && it.hasUpdate },
            allPackageLanguages = allLangs,
            extSubTab = local.extSubTab,
            extSearchQuery = local.extQuery,
            extLangFilter = local.extLangFilter,
            extRepoFilterId = local.extRepoFilterId,
            extCompatFilter = local.extCompatFilter,
            extSort = local.extSort,
            extDownloadProgress = extSnap.progress,
            installHistory = extSnap.history,
            isRefreshingRepos = local.isRefreshingRepos,
            statusMessage = local.statusMsg,
            errorMessage = local.errorMsg,
            exportedReposJson = local.exportedReposJson,
            safLocalFolderUri = prefs.localSafSourceTreeUri,
            globalQuery = local.globalQuery,
            globalFilters = local.globalFilters,
            disabledSearchSourceIds = prefs.disabledSearchSourceIds,
            isSearchingGlobal = local.isSearchingGlobal,
            globalResults = local.globalResults,
            globalSourceErrors = local.globalErrors,
            duplicateTitlesAcrossSources = duplicateTitles,
            searchHistory = history,
            searchSuggestions = suggestions
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourcesUiState())

    val browseUiState: StateFlow<BrowseSourceUiState> = browseState.asStateFlow()

    init {
        checkSourceAvailability()
        syncCompanionApps()
        viewModelScope.launch {
            extRepo.installEvents.collect { event ->
                when (event) {
                    is ExtensionInstallEvent.UserConfirmationRequired -> {
                        _osActionIntents.tryEmit(event.confirmationIntent)
                        localUiState.update {
                            it.copy(statusMsg = "Confirm installation of '${event.pkgId}' in the Android system dialog.")
                        }
                    }
                    is ExtensionInstallEvent.PermissionRequired -> {
                        _osActionIntents.tryEmit(event.settingsIntent)
                        localUiState.update { it.copy(errorMsg = event.message) }
                    }
                    is ExtensionInstallEvent.Succeeded -> {
                        extRepo.syncInstalledExtensions()
                        checkSourceAvailability()
                        localUiState.update { it.copy(statusMsg = event.message, errorMsg = null) }
                    }
                    is ExtensionInstallEvent.Failed -> {
                        localUiState.update { it.copy(errorMsg = event.reason) }
                    }
                    is ExtensionInstallEvent.PackageChanged -> {
                        extRepo.syncInstalledExtensions()
                        checkSourceAvailability()
                    }
                }
            }
        }
    }

    fun syncCompanionApps(showFeedback: Boolean = false) {
        viewModelScope.launch {
            val count = runCatching { extRepo.syncInstalledExtensions() }.getOrDefault(0)
            val mihonCount = extRepo.mihonLoadedState.value.activeAdapters.size
            if (showFeedback) {
                localUiState.update {
                    it.copy(
                        statusMsg = if (count > 0 || mihonCount > 0) {
                            "Reloaded extensions: $mihonCount active Mihon source(s), $count package update(s)."
                        } else {
                            "Scanned device for Mihon & Companion IPC extensions."
                        }
                    )
                }
            }
        }
    }

    fun checkSourceAvailability() {
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                sourceRegistry.allConfiguredSources.first().filter { it.isEnabled }
            }
            val results = entries.map { entry ->
                async(Dispatchers.IO) {
                    val status = try {
                        withTimeout(15_000L) {
                            entry.adapter.checkStatus()
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        SourceHealthStatus(
                            isOnline = false,
                            errorReason = NetworkErrorFormatter.describe(t)
                        )
                    }
                    entry.adapter.id to status
                }
            }.awaitAll().toMap()
            healthCache.value = results
        }
    }

    fun setSourceEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            val current = prefsRepo.preferences.first().disabledSourceIds.toMutableSet()
            if (enabled) current.remove(sourceId) else current.add(sourceId)
            prefsRepo.setDisabledSourceIds(current)
            checkSourceAvailability()
        }
    }

    fun toggleSearchSourceEnabled(sourceId: String) {
        viewModelScope.launch {
            val current = prefsRepo.preferences.first().disabledSearchSourceIds.toMutableSet()
            if (sourceId in current) current.remove(sourceId) else current.add(sourceId)
            prefsRepo.setDisabledSearchSourceIds(current)
        }
    }

    fun setLanguageFilter(lang: String) {
        localUiState.update { it.copy(langFilter = lang) }
    }

    fun setCategoryFilter(cat: String) {
        localUiState.update { it.copy(categoryFilter = cat) }
    }

    fun setLocalSafFolder(uri: String) {
        viewModelScope.launch {
            prefsRepo.setLocalSafSourceUri(uri)
            checkSourceAvailability()
            localUiState.update { it.copy(statusMsg = "Local SAF folder linked.") }
        }
    }

    fun selectExtSubTab(tab: ExtensionStoreTab) {
        localUiState.update { it.copy(extSubTab = tab) }
    }

    fun updateExtSearch(query: String) {
        localUiState.update { it.copy(extQuery = query) }
    }

    fun updateExtLangFilter(lang: String) {
        localUiState.update { it.copy(extLangFilter = lang) }
    }

    fun updateExtRepoFilter(repoId: Long?) {
        localUiState.update { it.copy(extRepoFilterId = repoId) }
    }

    fun updateExtCompatFilter(filter: ExtensionCompatFilter) {
        localUiState.update { it.copy(extCompatFilter = filter) }
    }

    fun updateExtSort(sort: ExtensionSortOption) {
        localUiState.update { it.copy(extSort = sort) }
    }

    fun clearExtSearchAndFilters() {
        localUiState.update {
            it.copy(
                extQuery = "",
                extSubTab = ExtensionStoreTab.ALL,
                extLangFilter = "all",
                extRepoFilterId = null,
                extCompatFilter = ExtensionCompatFilter.ALL
            )
        }
    }

    fun clearBannerMessages() {
        localUiState.update { it.copy(statusMsg = null, errorMsg = null) }
    }

    fun dismissExportJson() {
        localUiState.update { it.copy(exportedReposJson = null) }
    }

    fun addRepository(url: String, name: String, fingerprint: String) {
        viewModelScope.launch {
            localUiState.update { it.copy(isRefreshingRepos = true, errorMsg = null, statusMsg = null) }
            val res = extRepo.addRepository(url, name, fingerprint)
            localUiState.update {
                if (res.isSuccess) {
                    val saved = res.getOrNull()
                    it.copy(
                        isRefreshingRepos = false,
                        statusMsg = "Repository '${saved?.displayName?.ifBlank { saved.name }}' indexed (${saved?.extensionCount ?: 0} items)."
                    )
                } else {
                    it.copy(isRefreshingRepos = false, errorMsg = res.exceptionOrNull()?.message ?: "Failed to add repository")
                }
            }
        }
    }

    fun refreshRepository(repo: ExtensionRepoEntity) {
        viewModelScope.launch {
            localUiState.update { it.copy(isRefreshingRepos = true, errorMsg = null) }
            val res = extRepo.refreshRepository(repo)
            localUiState.update {
                if (res.isSuccess) {
                    it.copy(isRefreshingRepos = false, statusMsg = "Refreshed ${repo.displayName.ifBlank { repo.name }}: ${res.getOrNull()} extensions.")
                } else {
                    it.copy(isRefreshingRepos = false, errorMsg = res.exceptionOrNull()?.message ?: "Refresh failed")
                }
            }
        }
    }

    fun refreshAllEnabledRepos(onlyFailed: Boolean = false) {
        viewModelScope.launch {
            localUiState.update { it.copy(isRefreshingRepos = true, errorMsg = null, statusMsg = null) }
            val report = extRepo.refreshAllEnabledRepos(onlyFailed)
            runCatching { extRepo.syncInstalledCompanionApps() }
            localUiState.update {
                if (report.totalAttempted == 0) {
                    it.copy(isRefreshingRepos = false, statusMsg = "No matching enabled repositories to refresh.")
                } else if (report.failedRepos == 0) {
                    it.copy(
                        isRefreshingRepos = false,
                        statusMsg = "Synced ${report.succeededRepos}/${report.totalAttempted} repos (${report.totalPackagesLoaded} extensions indexed)."
                    )
                } else {
                    it.copy(
                        isRefreshingRepos = false,
                        statusMsg = "Synced ${report.succeededRepos}/${report.totalAttempted} repos (${report.totalPackagesLoaded} extensions).",
                        errorMsg = "${report.failedRepos} repo(s) failed: ${report.errorsByRepo.entries.firstOrNull()?.let { e -> "${e.key}: ${e.value}" } ?: ""}"
                    )
                }
            }
        }
    }

    fun restoreBuiltInRepos() {
        viewModelScope.launch {
            val count = extRepo.seedDefaultRepositoriesIfMissing(forceRestore = true)
            localUiState.update {
                it.copy(statusMsg = if (count > 0) "Restored $count built-in repository(ies)." else "All 8 default repositories are already configured.")
            }
        }
    }

    fun setRepoEnabled(repo: ExtensionRepoEntity, enabled: Boolean) {
        viewModelScope.launch { extRepo.setRepoEnabled(repo, enabled) }
    }

    fun trustRepository(repo: ExtensionRepoEntity, trusted: Boolean) {
        viewModelScope.launch { extRepo.setRepoTrusted(repo, trusted) }
    }

    fun removeRepository(repo: ExtensionRepoEntity) {
        viewModelScope.launch {
            extRepo.removeRepository(repo)
            localUiState.update { it.copy(statusMsg = "Removed repository ${repo.displayName.ifBlank { repo.name }}.") }
        }
    }

    fun exportRepositories() {
        viewModelScope.launch {
            val json = extRepo.exportRepositoriesJson()
            localUiState.update { it.copy(exportedReposJson = json) }
        }
    }

    fun importRepositories(rawJson: String) {
        viewModelScope.launch {
            val res = extRepo.importRepositoriesJson(rawJson)
            localUiState.update {
                if (res.isSuccess) {
                    it.copy(statusMsg = "Imported ${res.getOrDefault(0)} new HTTPS repository(ies). Tap Refresh All to index.")
                } else {
                    it.copy(errorMsg = "Invalid repository JSON bundle: ${res.exceptionOrNull()?.message}")
                }
            }
        }
    }

    fun trustExtensionSigner(pkg: ExtensionPackageEntity) {
        viewModelScope.launch {
            val res = extRepo.trustExtensionSigner(pkg)
            localUiState.update {
                if (res.isSuccess) {
                    val updated = res.getOrNull()
                    it.copy(
                        statusMsg = updated?.compatibilityReason?.takeIf { r -> r.isNotBlank() }
                            ?: "Signer certificate approved for ${pkg.name}.",
                        errorMsg = null
                    )
                } else {
                    it.copy(errorMsg = res.exceptionOrNull()?.message ?: "Failed approving signer.")
                }
            }
        }
    }

    fun installOrUpdateExtension(pkg: ExtensionPackageEntity) {
        viewModelScope.launch {
            when (pkg.format) {
                ExtensionFormat.KOMA_JSON_ADAPTER.id -> {
                    val res = extRepo.installExtension(pkg)
                    localUiState.update {
                        if (res.isSuccess) {
                            it.copy(statusMsg = "${pkg.name} v${pkg.latestVersionName} installed & activated.")
                        } else {
                            it.copy(errorMsg = res.exceptionOrNull()?.message ?: "Installation blocked.")
                        }
                    }
                }
                ExtensionFormat.COMPANION_IPC_EXTENSION.id -> {
                    val cfg = runCatching { org.json.JSONObject(pkg.adapterJson) }.getOrNull()
                    val authority = cfg?.optString("authority").orEmpty()
                    val targetPkg = cfg?.optString("packageName").orEmpty().ifBlank { pkg.pkgId }
                    val res = extRepo.activateCompanionIpc(pkg, authority, targetPkg)
                    localUiState.update {
                        if (res.isSuccess) {
                            it.copy(statusMsg = "${pkg.name} Companion IPC activated ('$authority').")
                        } else {
                            it.copy(errorMsg = res.exceptionOrNull()?.message ?: "Companion IPC activation failed.")
                        }
                    }
                }
                else -> {
                    val status = runCatching {
                        ExtensionCompatibilityStatus.valueOf(pkg.compatibilityStatus)
                    }.getOrDefault(ExtensionCompatibilityStatus.UNKNOWN)

                    if (status == ExtensionCompatibilityStatus.VERIFIED_MIHON_READY && pkg.localApkPath.isNotBlank()) {
                        val installRes = extRepo.requestInstallMihonApk(pkg)
                        installRes.onFailure { err ->
                            localUiState.update { it.copy(errorMsg = err.message ?: "OS installer launch failed.") }
                        }
                    } else {
                        val res = extRepo.downloadAndVerifyMihonApk(pkg)
                        localUiState.update {
                            if (res.isSuccess) {
                                val verifiedPkg = res.getOrNull()
                                val summary = verifiedPkg?.compatibilityReason?.takeIf { r -> r.isNotBlank() }
                                    ?: "Verified ${pkg.name} APK (${verifiedPkg?.libVersion ?: pkg.libVersion})."
                                it.copy(statusMsg = summary, errorMsg = null)
                            } else {
                                it.copy(errorMsg = res.exceptionOrNull()?.message ?: "APK verification failed.")
                            }
                        }
                    }
                }
            }
        }
    }

    fun requestOsInstallMihonApk(pkg: ExtensionPackageEntity) {
        viewModelScope.launch {
            val res = extRepo.requestInstallMihonApk(pkg)
            res.onFailure { err ->
                localUiState.update { it.copy(errorMsg = err.message ?: "OS installer launch failed.") }
            }
        }
    }

    fun updateAllExtensions() {
        viewModelScope.launch {
            val pkgs = extRepo.packages.first()
            val count = extRepo.updateAllExtensions(pkgs)
            localUiState.update { it.copy(statusMsg = "Updated $count extension(s).") }
        }
    }

    fun setExtensionEnabled(pkgId: String, enabled: Boolean) {
        viewModelScope.launch {
            extRepo.setExtensionEnabled(pkgId, enabled)
            checkSourceAvailability()
        }
    }

    fun uninstallExtension(pkgId: String) {
        viewModelScope.launch {
            val pkg = extRepo.packages.first().firstOrNull { it.pkgId == pkgId }
            val wasOsInstalled = pkg?.isInstalled == true &&
                pkg.format != ExtensionFormat.KOMA_JSON_ADAPTER.id &&
                pkg.format != ExtensionFormat.COMPANION_IPC_EXTENSION.id
            if (wasOsInstalled) {
                extRepo.buildOsUninstallIntent(pkgId)?.let { intent ->
                    _osActionIntents.tryEmit(intent)
                }
            }
            extRepo.uninstallExtension(pkgId)
            checkSourceAvailability()
            localUiState.update {
                it.copy(
                    statusMsg = if (wasOsInstalled) {
                        "Prompted Android OS uninstall & cleared cached archive."
                    } else {
                        "Extension & cached archive removed."
                    }
                )
            }
        }
    }

    // Global Search
    fun updateGlobalQuery(q: String) {
        localUiState.update { it.copy(globalQuery = q) }
    }

    fun updateGlobalFilters(filters: SearchFilters) {
        localUiState.update { it.copy(globalFilters = filters) }
    }

    fun executeGlobalSearch() {
        val query = localUiState.value.globalQuery.trim()
        val filters = localUiState.value.globalFilters
        viewModelScope.launch {
            if (query.isNotBlank()) mangaRepo.recordSearch(query)
            localUiState.update { it.copy(isSearchingGlobal = true, errorMsg = null, globalErrors = emptyMap()) }
            val prefs = prefsRepo.preferences.first()
            val sources = sourceRegistry.activeSources.first()
                .filter { it.id !in prefs.disabledSearchSourceIds }

            val resultsMap = linkedMapOf<String, List<MangaCardUi>>()
            val errorsMap = linkedMapOf<String, String>()

            val outcomes = sources.map { source ->
                async(Dispatchers.IO) {
                    try {
                        withTimeout(25_000L) {
                            val items = source.search(query, 1, filters).items.map {
                                MangaCardUi(
                                    id = "${it.sourceId}::${it.mangaKey}",
                                    title = it.title,
                                    altTitle = it.altTitle,
                                    coverUrl = it.coverUrl,
                                    latestChapter = it.latestChapterName,
                                    sourceName = it.sourceName,
                                    status = it.status,
                                    isDemoData = it.isDemoData
                                )
                            }
                            Triple(source.name, items, null as String?)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        Triple(source.name, emptyList(), NetworkErrorFormatter.describe(t))
                    }
                }
            }.awaitAll()

            outcomes.forEach { (sourceName, items, err) ->
                if (items.isNotEmpty()) resultsMap[sourceName] = items
                if (err != null) errorsMap[sourceName] = err
            }

            localUiState.update {
                it.copy(
                    isSearchingGlobal = false,
                    globalResults = resultsMap,
                    globalErrors = errorsMap
                )
            }
        }
    }

    fun toggleSavedSearch(entry: SearchHistoryEntity) {
        viewModelScope.launch { mangaRepo.toggleSaveSearch(entry) }
    }

    fun deleteSearchHistory(id: Long) {
        viewModelScope.launch { mangaRepo.deleteSearch(id) }
    }

    // Single Source Browse
    fun initBrowseSource(sourceId: String, forceReload: Boolean = false) {
        val current = browseState.value
        if (!forceReload && current.sourceId == sourceId && (current.items.isNotEmpty() || current.isLoading)) {
            return
        }
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            val (adapter, prefs) = withContext(Dispatchers.IO) {
                val resolved = sourceRegistry.getSourceById(sourceId)
                val loadedPrefs = try {
                    resolved?.getPreferences() ?: emptyList()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    emptyList()
                }
                resolved to loadedPrefs
            }
            if (adapter == null) {
                runCatching { Log.w("KiyomiSource", "initBrowseSource: sourceId=$sourceId not active or missing") }
                browseState.value = BrowseSourceUiState(
                    sourceId = sourceId,
                    sourceName = sourceId,
                    mode = "popular",
                    isLoading = false,
                    error = "Source '$sourceId' is not active or failed to initialize."
                )
                return@launch
            }
            runCatching { Log.i("KiyomiSource", "Opening source browse: id=${adapter.id} name=${adapter.name}") }
            browseState.value = BrowseSourceUiState(
                sourceId = sourceId,
                sourceName = adapter.name,
                mode = "popular",
                preferences = prefs,
                supportsLogin = adapter.supportsLogin,
                isLoading = true
            )
            loadSourcePageInternal(page = 1, append = false)
        }
    }

    fun setBrowseMode(mode: String) {
        browseState.update { it.copy(mode = mode, page = 1) }
        loadSourcePage(page = 1, append = false)
    }

    fun setBrowseQuery(q: String) {
        browseState.update { it.copy(query = q) }
    }

    fun setBrowseFilters(filters: SearchFilters) {
        browseState.update { it.copy(filters = filters, mode = "search") }
        loadSourcePage(page = 1, append = false)
    }

    fun loadSourcePage(page: Int = 1, append: Boolean = false) {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            loadSourcePageInternal(page, append)
        }
    }

    private suspend fun loadSourcePageInternal(page: Int, append: Boolean) {
        val current = browseState.value
        val adapter = withContext(Dispatchers.IO) {
            sourceRegistry.getSourceById(current.sourceId)
        }
        if (adapter == null) {
            browseState.update {
                it.copy(
                    isLoading = false,
                    error = "Source '${current.sourceName.ifBlank { current.sourceId }}' is not active."
                )
            }
            return
        }
        browseState.update { it.copy(isLoading = true, error = null) }
        try {
            val res = withContext(Dispatchers.IO) {
                withTimeout(35_000L) {
                    when (current.mode) {
                        "latest" -> adapter.getLatest(page, current.filters)
                        "search" -> adapter.search(current.query, page, current.filters)
                        else -> adapter.getPopular(page, current.filters)
                    }
                }
            }
            val newCards = res.items.map {
                MangaCardUi(
                    id = "${it.sourceId}::${it.mangaKey}",
                    title = it.title,
                    altTitle = it.altTitle,
                    coverUrl = it.coverUrl,
                    latestChapter = it.latestChapterName,
                    sourceName = it.sourceName,
                    status = it.status,
                    isDemoData = it.isDemoData
                )
            }
            browseState.update {
                it.copy(
                    isLoading = false,
                    page = page,
                    hasNextPage = res.hasNextPage,
                    items = if (append) (it.items + newCards).distinctBy { c -> c.id } else newCards
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            runCatching {
                Log.w("KiyomiSource", "loadSourcePage failed id=${current.sourceId} mode=${current.mode} page=$page: ${t.javaClass.simpleName}: ${t.message}")
            }
            browseState.update {
                it.copy(isLoading = false, error = NetworkErrorFormatter.describe(t))
            }
        }
    }

    fun updateSourcePreference(key: String, value: String) {
        viewModelScope.launch {
            val refreshed = withContext(Dispatchers.IO) {
                val adapter = sourceRegistry.getSourceById(browseState.value.sourceId) ?: return@withContext null
                adapter.updatePreference(key, value)
                runCatching { adapter.getPreferences() }.getOrDefault(emptyList())
            } ?: return@launch
            browseState.update { it.copy(preferences = refreshed) }
            loadSourcePage(1, append = false)
        }
    }

    fun loginToSource(username: String, credential: String) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val adapter = sourceRegistry.getSourceById(browseState.value.sourceId) ?: return@withContext null
                val res = adapter.login(username, credential)
                val msg = when (res) {
                    is SourceSessionResult.Authenticated -> "Signed in as ${res.username} (${res.note})"
                    is SourceSessionResult.Failed -> "Login failed: ${res.reason}"
                    is SourceSessionResult.Unsupported -> res.reason
                }
                val refreshed = runCatching { adapter.getPreferences() }.getOrDefault(emptyList())
                msg to refreshed
            } ?: return@launch
            browseState.update { it.copy(loginFeedback = outcome.first, preferences = outcome.second) }
        }
    }

    fun openSourceManga(card: MangaCardUi, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                mangaRepo.observeManga(card.id).first()
            }
            if (existing != null) {
                onReady(existing.id)
                return@launch
            }
            val sourceId = card.id.substringBefore("::")
            val mangaKey = card.id.substringAfter("::")
            val summary = SourceMangaItem(
                sourceId = sourceId,
                sourceName = card.sourceName,
                mangaKey = mangaKey,
                title = card.title,
                altTitle = card.altTitle,
                coverUrl = card.coverUrl,
                latestChapterName = card.latestChapter,
                status = card.status,
                isDemoData = card.isDemoData
            )
            val cached = withContext(Dispatchers.IO) {
                mangaRepo.cacheSourceManga(summary)
            }
            onReady(cached.id)
        }
    }

    companion object {
        fun factory(
            sourceRegistry: SourceRegistry,
            extRepo: ExtensionRepository,
            mangaRepo: MangaRepository,
            prefsRepo: AppPreferencesRepository
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SourcesViewModel(sourceRegistry, extRepo, mangaRepo, prefsRepo) as T
        }
    }
}
