package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.AppContainer
import com.example.ui.components.GlassNavTab
import com.example.ui.components.LiquidGlassFloatingNavBar
import com.example.ui.screens.detail.MangaDetailScreen
import com.example.ui.screens.detail.MangaDetailViewModel
import com.example.ui.screens.home.HomeScreen
import com.example.ui.screens.home.HomeViewModel
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import com.example.ui.screens.more.*
import com.example.ui.screens.reader.ReaderScreen
import com.example.ui.screens.reader.ReaderViewModel
import com.example.ui.screens.sources.NetworkDiagnosticsScreen
import com.example.ui.screens.sources.SourceBrowseScreen
import com.example.ui.screens.sources.SourcesScreen
import com.example.ui.screens.sources.SourcesViewModel
import com.example.ui.screens.updates.UpdatesScreen
import com.example.ui.screens.updates.UpdatesViewModel
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SourcesRoute
@Serializable data object LibraryRoute
@Serializable data object UpdatesRoute
@Serializable data object MoreRoute
@Serializable data class SourceBrowseRoute(val sourceId: String)
@Serializable data class MangaDetailRoute(val mangaId: String)
@Serializable data class ReaderRoute(val mangaId: String, val chapterId: String)
@Serializable data object DownloadsRoute
@Serializable data object StatsRoute
@Serializable data object TrackingRoute
@Serializable data object BackupRoute
@Serializable data object SettingsRoute
@Serializable data object DiagnosticsRoute

private data class TopTabItem(
    val id: String,
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val tag: String
)

private val topTabs = listOf(
    TopTabItem("home", HomeRoute, "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    TopTabItem("sources", SourcesRoute, "Sources", Icons.Filled.Explore, Icons.Outlined.Explore, "nav_sources"),
    TopTabItem("library", LibraryRoute, "Library", Icons.Filled.CollectionsBookmark, Icons.Outlined.CollectionsBookmark, "nav_library"),
    TopTabItem("updates", UpdatesRoute, "Updates", Icons.Filled.NewReleases, Icons.Outlined.NewReleases, "nav_updates"),
    TopTabItem("more", MoreRoute, "More", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz, "nav_more")
)

@Composable
fun KomaAppNavigation(container: AppContainer) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination

    val selectedTabId = when {
        currentDest?.hasRoute<HomeRoute>() == true -> "home"
        currentDest?.hasRoute<SourcesRoute>() == true -> "sources"
        currentDest?.hasRoute<LibraryRoute>() == true -> "library"
        currentDest?.hasRoute<UpdatesRoute>() == true -> "updates"
        currentDest?.hasRoute<MoreRoute>() == true -> "more"
        else -> null
    }
    val showChrome = selectedTabId != null || currentDest == null

    val homeVm: HomeViewModel = viewModel(
        factory = HomeViewModel.factory(container.mangaRepository, container.sourceRegistry, container.preferences)
    )
    val sourcesVm: SourcesViewModel = viewModel(
        factory = SourcesViewModel.factory(
            container.sourceRegistry,
            container.extensionRepository,
            container.mangaRepository,
            container.preferences
        )
    )
    val libraryVm: LibraryViewModel = viewModel(
        factory = LibraryViewModel.factory(
            container.mangaRepository,
            container.downloadRepository,
            container.preferences
        )
    )
    val updatesVm: UpdatesViewModel = viewModel(
        factory = UpdatesViewModel.factory(container.mangaRepository)
    )
    val moreVm: MoreViewModel = viewModel(
        factory = MoreViewModel.factory(
            container.appContext,
            container.preferences,
            container.downloadRepository,
            container.mangaRepository,
            container.backupRepository,
            container.trackingRepository,
            container.imageLoader
        )
    )

    val navigateTab: (Any) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (showChrome && useRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        topTabs.forEach { tab ->
                            val selected = (selectedTabId ?: "home") == tab.id
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(tab.route) },
                                icon = {
                                    Icon(
                                        if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ),
                                modifier = Modifier.testTag(tab.tag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f).fillMaxSize()
                ) {
                    composable<HomeRoute> {
                        HomeScreen(
                            viewModel = homeVm,
                            onOpenManga = { id -> navController.navigate(MangaDetailRoute(id)) },
                            onNavigateToSources = { navigateTab(SourcesRoute) },
                            onOpenSource = { srcId -> navController.navigate(SourceBrowseRoute(srcId)) },
                            onNavigateToLibrary = { navigateTab(LibraryRoute) },
                            onNavigateToDownloads = { navController.navigate(DownloadsRoute) }
                        )
                    }
                    composable<SourcesRoute> {
                        SourcesScreen(
                            viewModel = sourcesVm,
                            onOpenSource = { srcId -> navController.navigate(SourceBrowseRoute(srcId)) },
                            onOpenManga = { mangaId -> navController.navigate(MangaDetailRoute(mangaId)) },
                            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) }
                        )
                    }
                    composable<LibraryRoute> {
                        LibraryScreen(
                            viewModel = libraryVm,
                            onOpenManga = { id -> navController.navigate(MangaDetailRoute(id)) },
                            onNavigateToSources = { navigateTab(SourcesRoute) }
                        )
                    }
                    composable<UpdatesRoute> {
                        UpdatesScreen(
                            viewModel = updatesVm,
                            onOpenReader = { mId, chId -> navController.navigate(ReaderRoute(mId, chId)) },
                            onOpenManga = { mId -> navController.navigate(MangaDetailRoute(mId)) },
                            onOpenDownloads = { navController.navigate(DownloadsRoute) }
                        )
                    }
                    composable<MoreRoute> {
                        MoreScreen(
                            viewModel = moreVm,
                            onOpenDownloads = { navController.navigate(DownloadsRoute) },
                            onOpenStats = { navController.navigate(StatsRoute) },
                            onOpenTracking = { navController.navigate(TrackingRoute) },
                            onOpenBackup = { navController.navigate(BackupRoute) },
                            onOpenSettings = { navController.navigate(SettingsRoute) }
                        )
                    }
                    composable<SourceBrowseRoute> { entry ->
                        val route = entry.toRoute<SourceBrowseRoute>()
                        SourceBrowseScreen(
                            sourceId = route.sourceId,
                            viewModel = sourcesVm,
                            onBack = { navController.popBackStack() },
                            onOpenManga = { mId -> navController.navigate(MangaDetailRoute(mId)) }
                        )
                    }
                    composable<MangaDetailRoute> { entry ->
                        val route = entry.toRoute<MangaDetailRoute>()
                        val detailVm: MangaDetailViewModel = viewModel(
                            key = "detail_${route.mangaId}",
                            factory = MangaDetailViewModel.factory(
                                route.mangaId,
                                container.mangaRepository,
                                container.downloadRepository,
                                container.trackingRepository
                            )
                        )
                        MangaDetailScreen(
                            viewModel = detailVm,
                            onBack = { navController.popBackStack() },
                            onOpenReader = { mId, chId -> navController.navigate(ReaderRoute(mId, chId)) }
                        )
                    }
                    composable<ReaderRoute> { entry ->
                        val route = entry.toRoute<ReaderRoute>()
                        val ctx = LocalContext.current
                        val readerVm: ReaderViewModel = viewModel(
                            key = "reader_${route.mangaId}_${route.chapterId}",
                            factory = ReaderViewModel.factory(
                                route.mangaId,
                                route.chapterId,
                                container.mangaRepository,
                                container.downloadRepository,
                                container.sourceRegistry,
                                container.preferences,
                                container.imageLoader,
                                ctx.applicationContext
                            )
                        )
                        ReaderScreen(
                            viewModel = readerVm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                    composable<DownloadsRoute> {
                        DownloadsScreen(
                            viewModel = moreVm,
                            onBack = { navController.popBackStack() },
                            onOpenReader = { mId, chId -> navController.navigate(ReaderRoute(mId, chId)) }
                        )
                    }
                    composable<StatsRoute> {
                        StatsScreen(viewModel = moreVm, onBack = { navController.popBackStack() })
                    }
                    composable<TrackingRoute> {
                        TrackingScreen(viewModel = moreVm, onBack = { navController.popBackStack() })
                    }
                    composable<BackupRoute> {
                        BackupScreen(viewModel = moreVm, onBack = { navController.popBackStack() })
                    }
                    composable<SettingsRoute> {
                        SettingsScreen(
                            viewModel = moreVm,
                            onBack = { navController.popBackStack() },
                            onOpenBackup = { navController.navigate(BackupRoute) },
                            onOpenDiagnostics = { navController.navigate(DiagnosticsRoute) }
                        )
                    }
                    composable<DiagnosticsRoute> {
                        NetworkDiagnosticsScreen(
                            container = container,
                            sourcesViewModel = sourcesVm,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }

            if (showChrome && !useRail) {
                LiquidGlassFloatingNavBar(
                    tabs = topTabs.map {
                        GlassNavTab(
                            id = it.id,
                            label = it.label,
                            selectedIcon = it.selectedIcon,
                            unselectedIcon = it.unselectedIcon,
                            testTag = it.tag
                        )
                    },
                    selectedId = selectedTabId ?: "home",
                    onSelect = { tab ->
                        topTabs.firstOrNull { it.id == tab.id }?.let { navigateTab(it.route) }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}
