package com.example.ui.screens.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.local.entity.ExtensionPackageEntity
import com.example.data.local.entity.ExtensionRepoEntity
import com.example.data.local.entity.SearchHistoryEntity
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.repository.ExtensionRepository
import com.example.data.repository.MangaRepository
import com.example.domain.model.*
import com.example.source.MangaSourceAdapter
import com.example.source.SourceRegistry
import com.example.ui.components.MangaCardUi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SourceMetaUi(
    val id: String,
    val name: String,
    val language: String,
    val category: String,
    val baseUrl: String,
    val supportsFilters: Boolean,
    val supportsLogin: Boolean,
    val isDemo: Boolean,
    val isAvailable: Boolean? = null
)

enum class ExtensionSortOption(val label: String) {
    NAME("Name"),
    LANGUAGE("Language"),
    POPULARITY("Popularity"),
    UPDATES("Update Status")
}

data class SourcesUiState(
    val sources: List<SourceMetaUi> = emptyList(),
    val selectedLangFilter: String = "all",
    val selectedCategoryFilter: String = "all",
    val repos: List<ExtensionRepoEntity> = emptyList(),
    val packages: List<ExtensionPackageEntity> = emptyList(),
    val extSearchQuery: String = "",
    val extSort: ExtensionSortOption = ExtensionSortOption.POPULARITY,
    val extShowInstalledOnly: Boolean = false,
    val isRefreshingRepos: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val safLocalFolderUri: String = "",
    // Global Search
    val globalQuery: String = "",
    val globalFilters: SearchFilters = SearchFilters(),
    val isSearchingGlobal: Boolean = false,
    val globalResults: Map<String, List<MangaCardUi>> = emptyMap(),
    val searchHistory: List<SearchHistoryEntity> = emptyList()
)

data class BrowseSourceUiState(
    val sourceId: String = "",
    val sourceName: String = "",
    val mode: String = "popular", // popular, latest, search
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

    private val availabilityCache = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val localUiState = MutableStateFlow(SourcesLocalControls())
    private val browseState = MutableStateFlow(BrowseSourceUiState())

    private data class SourcesLocalControls(
        val langFilter: String = "all",
        val categoryFilter: String = "all",
        val extQuery: String = "",
        val extSort: ExtensionSortOption = ExtensionSortOption.POPULARITY,
        val extInstalledOnly: Boolean = false,
        val isRefreshingRepos: Boolean = false,
        val statusMsg: String? = null,
        val errorMsg: String? = null,
        val globalQuery: String = "",
        val globalFilters: SearchFilters = SearchFilters(),
        val isSearchingGlobal: Boolean = false,
        val globalResults: Map<String, List<MangaCardUi>> = emptyMap()
    )

    val uiState: StateFlow<SourcesUiState> = combine(
        sourceRegistry.activeSources,
        availabilityCache,
        extRepo.repos,
        extRepo.packages,
        mangaRepo.searchHistory,
        prefsRepo.preferences,
        localUiState
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val adapters = values[0] as List<MangaSourceAdapter>
        @Suppress("UNCHECKED_CAST")
        val avail = values[1] as Map<String, Boolean>
        @Suppress("UNCHECKED_CAST")
        val repos = values[2] as List<ExtensionRepoEntity>
        @Suppress("UNCHECKED_CAST")
        val pkgs = values[3] as List<ExtensionPackageEntity>
        @Suppress("UNCHECKED_CAST")
        val history = values[4] as List<SearchHistoryEntity>
        val prefs = values[5] as com.example.data.preferences.UserPreferences
        val local = values[6] as SourcesLocalControls

        val metaList = adapters.map {
            SourceMetaUi(
                id = it.id,
                name = it.name,
                language = it.language,
                category = it.category,
                baseUrl = it.baseUrl,
                supportsFilters = it.supportsFilters,
                supportsLogin = it.supportsLogin,
                isDemo = it.isDemoSource,
                isAvailable = avail[it.id]
            )
        }.filter {
            (local.langFilter == "all" || it.language.equals(local.langFilter, true)) &&
                (local.categoryFilter == "all" || it.category.equals(local.categoryFilter, true))
        }

        val filteredPkgs = pkgs.filter { pkg ->
            val matchesQuery = local.extQuery.isBlank() ||
                pkg.name.contains(local.extQuery, true) ||
                pkg.pkgId.contains(local.extQuery, true) ||
                pkg.author.contains(local.extQuery, true)
            val matchesInstalled = !local.extInstalledOnly || pkg.isInstalled
            matchesQuery && matchesInstalled
        }.let { list ->
            when (local.extSort) {
                ExtensionSortOption.NAME -> list.sortedBy { it.name.lowercase() }
                ExtensionSortOption.LANGUAGE -> list.sortedBy { it.language.lowercase() }
                ExtensionSortOption.POPULARITY -> list.sortedByDescending { it.popularity }
                ExtensionSortOption.UPDATES -> list.sortedByDescending { it.hasUpdate }
            }
        }

        SourcesUiState(
            sources = metaList,
            selectedLangFilter = local.langFilter,
            selectedCategoryFilter = local.categoryFilter,
            repos = repos,
            packages = filteredPkgs,
            extSearchQuery = local.extQuery,
            extSort = local.extSort,
            extShowInstalledOnly = local.extInstalledOnly,
            isRefreshingRepos = local.isRefreshingRepos,
            statusMessage = local.statusMsg,
            errorMessage = local.errorMsg,
            safLocalFolderUri = prefs.localSafSourceTreeUri,
            globalQuery = local.globalQuery,
            globalFilters = local.globalFilters,
            isSearchingGlobal = local.isSearchingGlobal,
            globalResults = local.globalResults,
            searchHistory = history
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SourcesUiState())

    val browseUiState: StateFlow<BrowseSourceUiState> = browseState.asStateFlow()

    init {
        checkSourceAvailability()
    }

    fun checkSourceAvailability() {
        viewModelScope.launch {
            val adapters = sourceRegistry.activeSources.first()
            val results = adapters.map { adapter ->
                async { adapter.id to runCatching { adapter.isAvailable() }.getOrDefault(false) }
            }.awaitAll().toMap()
            availabilityCache.value = results
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

    fun updateExtSearch(query: String) {
        localUiState.update { it.copy(extQuery = query) }
    }

    fun updateExtSort(sort: ExtensionSortOption) {
        localUiState.update { it.copy(extSort = sort) }
    }

    fun toggleExtInstalledOnly(installedOnly: Boolean) {
        localUiState.update { it.copy(extInstalledOnly = installedOnly) }
    }

    fun clearBannerMessages() {
        localUiState.update { it.copy(statusMsg = null, errorMsg = null) }
    }

    fun addRepository(url: String, name: String, fingerprint: String) {
        viewModelScope.launch {
            localUiState.update { it.copy(isRefreshingRepos = true, errorMsg = null, statusMsg = null) }
            val res = extRepo.addRepository(url, name, fingerprint)
            localUiState.update {
                if (res.isSuccess) {
                    it.copy(isRefreshingRepos = false, statusMsg = "Repository '${res.getOrNull()?.name}' added and indexed.")
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
                    it.copy(isRefreshingRepos = false, statusMsg = "Refreshed ${repo.name}: ${res.getOrNull()} packages.")
                } else {
                    it.copy(isRefreshingRepos = false, errorMsg = res.exceptionOrNull()?.message ?: "Refresh failed")
                }
            }
        }
    }

    fun trustRepository(repo: ExtensionRepoEntity, trusted: Boolean) {
        viewModelScope.launch {
            extRepo.setRepoTrusted(repo, trusted)
        }
    }

    fun removeRepository(repo: ExtensionRepoEntity) {
        viewModelScope.launch {
            extRepo.removeRepository(repo)
            localUiState.update { it.copy(statusMsg = "Removed repository ${repo.name}.") }
        }
    }

    fun installOrUpdateExtension(pkg: ExtensionPackageEntity) {
        viewModelScope.launch {
            val res = extRepo.installExtension(pkg)
            localUiState.update {
                if (res.isSuccess) {
                    it.copy(statusMsg = "${pkg.name} v${pkg.latestVersionName} installed.")
                } else {
                    it.copy(errorMsg = res.exceptionOrNull()?.message ?: "Installation blocked.")
                }
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
        viewModelScope.launch { extRepo.setExtensionEnabled(pkgId, enabled) }
    }

    fun uninstallExtension(pkgId: String) {
        viewModelScope.launch {
            extRepo.uninstallExtension(pkgId)
            localUiState.update { it.copy(statusMsg = "Extension uninstalled.") }
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
            localUiState.update { it.copy(isSearchingGlobal = true, errorMsg = null) }
            val sources = sourceRegistry.activeSources.first()
            val resultPairs = sources.map { source ->
                async {
                    val items = runCatching {
                        source.search(query, 1, filters).items.map {
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
                    }.getOrDefault(emptyList())
                    source.name to items
                }
            }.awaitAll().filter { it.second.isNotEmpty() }.toMap()

            localUiState.update {
                it.copy(isSearchingGlobal = false, globalResults = resultPairs)
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
    fun initBrowseSource(sourceId: String) {
        viewModelScope.launch {
            val adapter = sourceRegistry.getSourceById(sourceId)
            val prefs = runCatching { adapter?.getPreferences() ?: emptyList() }.getOrDefault(emptyList())
            browseState.value = BrowseSourceUiState(
                sourceId = sourceId,
                sourceName = adapter?.name ?: sourceId,
                mode = "popular",
                preferences = prefs,
                supportsLogin = adapter?.supportsLogin ?: false
            )
            loadSourcePage(page = 1, append = false)
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
        viewModelScope.launch {
            val current = browseState.value
            val adapter = sourceRegistry.getSourceById(current.sourceId) ?: return@launch
            browseState.update { it.copy(isLoading = true, error = null) }
            try {
                val res = when (current.mode) {
                    "latest" -> adapter.getLatest(page, current.filters)
                    "search" -> adapter.search(current.query, page, current.filters)
                    else -> adapter.getPopular(page, current.filters)
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
            } catch (e: Exception) {
                browseState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to load source catalog")
                }
            }
        }
    }

    fun updateSourcePreference(key: String, value: String) {
        viewModelScope.launch {
            val adapter = sourceRegistry.getSourceById(browseState.value.sourceId) ?: return@launch
            adapter.updatePreference(key, value)
            val refreshed = adapter.getPreferences()
            browseState.update { it.copy(preferences = refreshed) }
            loadSourcePage(1, append = false)
        }
    }

    fun loginToSource(username: String, credential: String) {
        viewModelScope.launch {
            val adapter = sourceRegistry.getSourceById(browseState.value.sourceId) ?: return@launch
            val res = adapter.login(username, credential)
            val msg = when (res) {
                is SourceSessionResult.Authenticated -> "Signed in as ${res.username} (${res.note})"
                is SourceSessionResult.Failed -> "Login failed: ${res.reason}"
                is SourceSessionResult.Unsupported -> res.reason
            }
            val refreshed = adapter.getPreferences()
            browseState.update { it.copy(loginFeedback = msg, preferences = refreshed) }
        }
    }

    fun openSourceManga(card: MangaCardUi, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val existing = mangaRepo.observeManga(card.id).first()
            if (existing != null) {
                onReady(existing.id)
                return@launch
            }
            val sourceId = card.id.substringBefore("::")
            val mangaKey = card.id.substringAfter("::")
            val adapter = sourceRegistry.getSourceById(sourceId)
            val detail = runCatching { adapter?.getMangaDetails(mangaKey) }.getOrNull()
                ?: SourceMangaItem(
                    sourceId = sourceId,
                    sourceName = card.sourceName,
                    mangaKey = mangaKey,
                    title = card.title,
                    altTitle = card.altTitle,
                    coverUrl = card.coverUrl,
                    status = card.status,
                    isDemoData = card.isDemoData
                )
            val cached = mangaRepo.cacheSourceManga(detail)
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
