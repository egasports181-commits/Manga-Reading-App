package com.example.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.model.*
import com.example.network.DnsMode
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "koma_preferences")

data class UserPreferences(
    // General & Appearance
    val appLanguage: String = "en",
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val amoledBlack: Boolean = false,
    val dynamicColor: Boolean = false,
    val accentPreset: AccentPreset = AccentPreset.WARM_ORANGE,
    val demoModeEnabled: Boolean = false,

    // Network & DNS
    val dnsMode: DnsMode = DnsMode.DOH_AUTO_FALLBACK,
    val preferIpv4: Boolean = true,

    // Home Customization
    val hiddenHomeSections: Set<String> = emptySet(),
    val homeSectionOrder: List<String> = HomeSectionType.defaultOrder().map { it.id },
    val homeCompactCards: Boolean = false,

    // Reader Defaults
    val defaultReadingMode: ReadingMode = ReadingMode.RIGHT_TO_LEFT,
    val defaultScaleMode: ImageScaleMode = ImageScaleMode.FIT_SCREEN,
    val customZoomScale: Float = 1.0f,
    val readerBackground: ReaderBackground = ReaderBackground.BLACK,
    val pageTransitions: Boolean = true,
    val readerTransitionMode: ReaderTransitionMode = ReaderTransitionMode.SLIDE,
    val tapZonesEnabled: Boolean = true,
    val readerTapZoneLayout: TapZoneLayout = TapZoneLayout.SIDES,
    val readerPageMarginsDp: Int = 0,
    val readerAutoHideMs: Int = 0,
    val volumeKeyNavigation: Boolean = false,
    val keepScreenAwake: Boolean = true,
    val hideSystemBars: Boolean = true,
    val readerBrightness: Float = -1f, // -1 means system default
    val orientationLock: OrientationLock = OrientationLock.FREE,

    // Library Defaults
    val librarySort: LibrarySort = LibrarySort.LAST_READ,
    val librarySortAscending: Boolean = false,
    val librarySmartView: String = "all",
    val libraryGroupBySource: Boolean = false,
    val autoCheckUpdatesHours: Int = 12,
    val updateNotificationsEnabled: Boolean = true,

    // Downloads
    val wifiOnlyDownloads: Boolean = true,
    val concurrentDownloads: Int = 2,
    val downloadMaxRetries: Int = 2,
    val downloadStorageLimitMb: Int = 4096,
    val autoDownloadNewChapters: Boolean = false,
    val autoCleanupReadChapters: Boolean = false,
    val safDownloadTreeUri: String = "",
    val localSafSourceTreeUri: String = "",

    // Extensions, Sources & Security
    val autoUpdateExtensions: Boolean = false,
    val allowUntrustedReposWarning: Boolean = true,
    val incognitoMode: Boolean = false,
    val autoBackupEnabled: Boolean = false,
    val disabledSourceIds: Set<String> = emptySet(),
    val disabledSearchSourceIds: Set<String> = emptySet(),

    // Tracking Tokens (user-provided OAuth / PAT)
    val anilistToken: String = "",
    val malToken: String = "",
    val kitsuToken: String = ""
)

class AppPreferencesRepository(private val context: Context) {

    private object Keys {
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val ACCENT_PRESET = stringPreferencesKey("accent_preset")
        val DEMO_MODE = booleanPreferencesKey("demo_mode_enabled")

        val DNS_MODE = stringPreferencesKey("dns_mode")
        val PREFER_IPV4 = booleanPreferencesKey("prefer_ipv4")

        val HIDDEN_HOME_SECTIONS = stringSetPreferencesKey("hidden_home_sections")
        val HOME_SECTION_ORDER = stringPreferencesKey("home_section_order")
        val HOME_COMPACT_CARDS = booleanPreferencesKey("home_compact_cards")

        val DEFAULT_READING_MODE = stringPreferencesKey("default_reading_mode")
        val DEFAULT_SCALE_MODE = stringPreferencesKey("default_scale_mode")
        val CUSTOM_ZOOM = floatPreferencesKey("custom_zoom")
        val READER_BG = stringPreferencesKey("reader_bg")
        val PAGE_TRANSITIONS = booleanPreferencesKey("page_transitions")
        val READER_TRANSITION_MODE = stringPreferencesKey("reader_transition_mode")
        val TAP_ZONES = booleanPreferencesKey("tap_zones")
        val READER_TAP_ZONE_LAYOUT = stringPreferencesKey("reader_tap_zone_layout")
        val READER_PAGE_MARGINS_DP = intPreferencesKey("reader_page_margins_dp")
        val READER_AUTO_HIDE_MS = intPreferencesKey("reader_auto_hide_ms")
        val VOLUME_NAV = booleanPreferencesKey("volume_nav")
        val KEEP_AWAKE = booleanPreferencesKey("keep_awake")
        val HIDE_SYSTEM_BARS = booleanPreferencesKey("hide_system_bars")
        val READER_BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val ORIENTATION_LOCK = stringPreferencesKey("orientation_lock")

        val LIBRARY_SORT = stringPreferencesKey("library_sort")
        val LIBRARY_SORT_ASC = booleanPreferencesKey("library_sort_asc")
        val LIBRARY_SMART_VIEW = stringPreferencesKey("library_smart_view")
        val LIBRARY_GROUP_SOURCE = booleanPreferencesKey("library_group_source")
        val AUTO_UPDATE_HOURS = intPreferencesKey("auto_update_hours")
        val UPDATE_NOTIFS = booleanPreferencesKey("update_notifs")

        val WIFI_ONLY = booleanPreferencesKey("wifi_only_downloads")
        val CONCURRENT_DOWNLOADS = intPreferencesKey("concurrent_downloads")
        val DOWNLOAD_MAX_RETRIES = intPreferencesKey("download_max_retries")
        val STORAGE_LIMIT_MB = intPreferencesKey("storage_limit_mb")
        val AUTO_DOWNLOAD_NEW = booleanPreferencesKey("auto_download_new")
        val AUTO_CLEANUP_READ = booleanPreferencesKey("auto_cleanup_read")
        val SAF_DOWNLOAD_URI = stringPreferencesKey("saf_download_uri")
        val LOCAL_SAF_SOURCE_URI = stringPreferencesKey("local_saf_source_uri")

        val AUTO_UPDATE_EXT = booleanPreferencesKey("auto_update_ext")
        val UNTRUSTED_WARNING = booleanPreferencesKey("untrusted_warning")
        val INCOGNITO_MODE = booleanPreferencesKey("incognito_mode")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val DISABLED_SOURCE_IDS = stringSetPreferencesKey("disabled_source_ids")
        val DISABLED_SEARCH_SOURCE_IDS = stringSetPreferencesKey("disabled_search_source_ids")

        val ANILIST_TOKEN = stringPreferencesKey("anilist_token")
        val MAL_TOKEN = stringPreferencesKey("mal_token")
        val KITSU_TOKEN = stringPreferencesKey("kitsu_token")
    }

    val preferences: Flow<UserPreferences> = context.dataStore.data.map { prefs ->
        val defaultOrder = HomeSectionType.defaultOrder().map { it.id }
        val savedOrder = prefs[Keys.HOME_SECTION_ORDER]
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?: defaultOrder
        val mergedOrder = (savedOrder + defaultOrder.filterNot { it in savedOrder })

        UserPreferences(
            appLanguage = prefs[Keys.APP_LANGUAGE] ?: "en",
            themeMode = ThemeMode.fromId(prefs[Keys.THEME_MODE] ?: "light"),
            amoledBlack = prefs[Keys.AMOLED_BLACK] ?: false,
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: false,
            accentPreset = AccentPreset.fromId(prefs[Keys.ACCENT_PRESET] ?: "warm_orange"),
            demoModeEnabled = prefs[Keys.DEMO_MODE] ?: false,
            dnsMode = DnsMode.fromId(prefs[Keys.DNS_MODE] ?: DnsMode.DOH_AUTO_FALLBACK.id),
            preferIpv4 = prefs[Keys.PREFER_IPV4] ?: true,
            hiddenHomeSections = prefs[Keys.HIDDEN_HOME_SECTIONS] ?: emptySet(),
            homeSectionOrder = mergedOrder,
            homeCompactCards = prefs[Keys.HOME_COMPACT_CARDS] ?: false,
            defaultReadingMode = ReadingMode.fromId(prefs[Keys.DEFAULT_READING_MODE] ?: "rtl"),
            defaultScaleMode = ImageScaleMode.fromId(prefs[Keys.DEFAULT_SCALE_MODE] ?: "fit_screen"),
            customZoomScale = prefs[Keys.CUSTOM_ZOOM] ?: 1.0f,
            readerBackground = ReaderBackground.fromId(prefs[Keys.READER_BG] ?: "black"),
            pageTransitions = prefs[Keys.PAGE_TRANSITIONS] ?: true,
            readerTransitionMode = ReaderTransitionMode.fromId(prefs[Keys.READER_TRANSITION_MODE] ?: "slide"),
            tapZonesEnabled = prefs[Keys.TAP_ZONES] ?: true,
            readerTapZoneLayout = TapZoneLayout.fromId(prefs[Keys.READER_TAP_ZONE_LAYOUT] ?: "sides"),
            readerPageMarginsDp = prefs[Keys.READER_PAGE_MARGINS_DP] ?: 0,
            readerAutoHideMs = prefs[Keys.READER_AUTO_HIDE_MS] ?: 0,
            volumeKeyNavigation = prefs[Keys.VOLUME_NAV] ?: false,
            keepScreenAwake = prefs[Keys.KEEP_AWAKE] ?: true,
            hideSystemBars = prefs[Keys.HIDE_SYSTEM_BARS] ?: true,
            readerBrightness = prefs[Keys.READER_BRIGHTNESS] ?: -1f,
            orientationLock = OrientationLock.fromId(prefs[Keys.ORIENTATION_LOCK] ?: "free"),
            librarySort = LibrarySort.fromId(prefs[Keys.LIBRARY_SORT] ?: "last_read"),
            librarySortAscending = prefs[Keys.LIBRARY_SORT_ASC] ?: false,
            librarySmartView = prefs[Keys.LIBRARY_SMART_VIEW] ?: "all",
            libraryGroupBySource = prefs[Keys.LIBRARY_GROUP_SOURCE] ?: false,
            autoCheckUpdatesHours = prefs[Keys.AUTO_UPDATE_HOURS] ?: 12,
            updateNotificationsEnabled = prefs[Keys.UPDATE_NOTIFS] ?: true,
            wifiOnlyDownloads = prefs[Keys.WIFI_ONLY] ?: true,
            concurrentDownloads = prefs[Keys.CONCURRENT_DOWNLOADS] ?: 2,
            downloadMaxRetries = prefs[Keys.DOWNLOAD_MAX_RETRIES] ?: 2,
            downloadStorageLimitMb = prefs[Keys.STORAGE_LIMIT_MB] ?: 4096,
            autoDownloadNewChapters = prefs[Keys.AUTO_DOWNLOAD_NEW] ?: false,
            autoCleanupReadChapters = prefs[Keys.AUTO_CLEANUP_READ] ?: false,
            safDownloadTreeUri = prefs[Keys.SAF_DOWNLOAD_URI] ?: "",
            localSafSourceTreeUri = prefs[Keys.LOCAL_SAF_SOURCE_URI] ?: "",
            autoUpdateExtensions = prefs[Keys.AUTO_UPDATE_EXT] ?: false,
            allowUntrustedReposWarning = prefs[Keys.UNTRUSTED_WARNING] ?: true,
            incognitoMode = prefs[Keys.INCOGNITO_MODE] ?: false,
            autoBackupEnabled = prefs[Keys.AUTO_BACKUP] ?: false,
            disabledSourceIds = prefs[Keys.DISABLED_SOURCE_IDS] ?: emptySet(),
            disabledSearchSourceIds = prefs[Keys.DISABLED_SEARCH_SOURCE_IDS] ?: emptySet(),
            anilistToken = prefs[Keys.ANILIST_TOKEN] ?: "",
            malToken = prefs[Keys.MAL_TOKEN] ?: "",
            kitsuToken = prefs[Keys.KITSU_TOKEN] ?: ""
        )
    }

    suspend fun updateTheme(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.id }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.AMOLED_BLACK] = enabled }
    suspend fun setDynamicColor(enabled: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = enabled }
    suspend fun setAccentPreset(preset: AccentPreset) = edit { it[Keys.ACCENT_PRESET] = preset.id }
    suspend fun setAppLanguage(lang: String) = edit { it[Keys.APP_LANGUAGE] = lang }
    suspend fun setDemoMode(enabled: Boolean) = edit { it[Keys.DEMO_MODE] = enabled }

    suspend fun setDnsMode(mode: DnsMode) = edit { it[Keys.DNS_MODE] = mode.id }
    suspend fun setPreferIpv4(prefer: Boolean) = edit { it[Keys.PREFER_IPV4] = prefer }

    suspend fun setHiddenHomeSections(hidden: Set<String>) = edit { it[Keys.HIDDEN_HOME_SECTIONS] = hidden }
    suspend fun setHomeSectionOrder(order: List<String>) = edit { it[Keys.HOME_SECTION_ORDER] = order.joinToString(",") }
    suspend fun setHomeCompactCards(compact: Boolean) = edit { it[Keys.HOME_COMPACT_CARDS] = compact }

    suspend fun setDefaultReadingMode(mode: ReadingMode) = edit { it[Keys.DEFAULT_READING_MODE] = mode.id }
    suspend fun setDefaultScaleMode(mode: ImageScaleMode) = edit { it[Keys.DEFAULT_SCALE_MODE] = mode.id }
    suspend fun setCustomZoom(zoom: Float) = edit { it[Keys.CUSTOM_ZOOM] = zoom }
    suspend fun setReaderBackground(bg: ReaderBackground) = edit { it[Keys.READER_BG] = bg.id }
    suspend fun setPageTransitions(enabled: Boolean) = edit { it[Keys.PAGE_TRANSITIONS] = enabled }
    suspend fun setReaderTransitionMode(mode: ReaderTransitionMode) = edit { it[Keys.READER_TRANSITION_MODE] = mode.id }
    suspend fun setTapZones(enabled: Boolean) = edit { it[Keys.TAP_ZONES] = enabled }
    suspend fun setReaderTapZoneLayout(layout: TapZoneLayout) = edit { it[Keys.READER_TAP_ZONE_LAYOUT] = layout.id }
    suspend fun setReaderPageMarginsDp(marginsDp: Int) = edit { it[Keys.READER_PAGE_MARGINS_DP] = marginsDp.coerceIn(0, 48) }
    suspend fun setReaderAutoHideMs(ms: Int) = edit { it[Keys.READER_AUTO_HIDE_MS] = ms }
    suspend fun setVolumeNavigation(enabled: Boolean) = edit { it[Keys.VOLUME_NAV] = enabled }
    suspend fun setKeepScreenAwake(enabled: Boolean) = edit { it[Keys.KEEP_AWAKE] = enabled }
    suspend fun setHideSystemBars(enabled: Boolean) = edit { it[Keys.HIDE_SYSTEM_BARS] = enabled }
    suspend fun setReaderBrightness(brightness: Float) = edit { it[Keys.READER_BRIGHTNESS] = brightness }
    suspend fun setOrientationLock(lock: OrientationLock) = edit { it[Keys.ORIENTATION_LOCK] = lock.id }

    suspend fun setLibrarySort(sort: LibrarySort, ascending: Boolean) = edit {
        it[Keys.LIBRARY_SORT] = sort.id
        it[Keys.LIBRARY_SORT_ASC] = ascending
    }
    suspend fun setLibrarySmartView(view: String) = edit { it[Keys.LIBRARY_SMART_VIEW] = view }
    suspend fun setLibraryGroupBySource(group: Boolean) = edit { it[Keys.LIBRARY_GROUP_SOURCE] = group }
    suspend fun setAutoUpdateHours(hours: Int) = edit { it[Keys.AUTO_UPDATE_HOURS] = hours }
    suspend fun setUpdateNotifications(enabled: Boolean) = edit { it[Keys.UPDATE_NOTIFS] = enabled }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) = edit { it[Keys.WIFI_ONLY] = enabled }
    suspend fun setConcurrentDownloads(count: Int) = edit { it[Keys.CONCURRENT_DOWNLOADS] = count.coerceIn(1, 5) }
    suspend fun setDownloadMaxRetries(retries: Int) = edit { it[Keys.DOWNLOAD_MAX_RETRIES] = retries.coerceIn(0, 5) }
    suspend fun setStorageLimitMb(mb: Int) = edit { it[Keys.STORAGE_LIMIT_MB] = mb }
    suspend fun setAutoDownloadNew(enabled: Boolean) = edit { it[Keys.AUTO_DOWNLOAD_NEW] = enabled }
    suspend fun setAutoCleanupRead(enabled: Boolean) = edit { it[Keys.AUTO_CLEANUP_READ] = enabled }
    suspend fun setSafDownloadUri(uri: String) = edit { it[Keys.SAF_DOWNLOAD_URI] = uri }
    suspend fun setLocalSafSourceUri(uri: String) = edit { it[Keys.LOCAL_SAF_SOURCE_URI] = uri }

    suspend fun setAutoUpdateExtensions(enabled: Boolean) = edit { it[Keys.AUTO_UPDATE_EXT] = enabled }
    suspend fun setIncognitoMode(enabled: Boolean) = edit { it[Keys.INCOGNITO_MODE] = enabled }
    suspend fun setAutoBackupEnabled(enabled: Boolean) = edit { it[Keys.AUTO_BACKUP] = enabled }
    suspend fun setDisabledSourceIds(ids: Set<String>) = edit { it[Keys.DISABLED_SOURCE_IDS] = ids }
    suspend fun setDisabledSearchSourceIds(ids: Set<String>) = edit { it[Keys.DISABLED_SEARCH_SOURCE_IDS] = ids }

    suspend fun setTrackerToken(service: TrackerService, token: String) = edit {
        when (service) {
            TrackerService.ANILIST -> it[Keys.ANILIST_TOKEN] = token
            TrackerService.MYANIMELIST -> it[Keys.MAL_TOKEN] = token
            TrackerService.KITSU -> it[Keys.KITSU_TOKEN] = token
            else -> Unit
        }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
