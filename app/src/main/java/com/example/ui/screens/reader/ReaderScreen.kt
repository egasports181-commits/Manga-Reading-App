package com.example.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import okhttp3.Headers.Companion.toHeaders
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.KomaCircularGlassButton
import com.example.ui.components.KomaElevatedCard
import com.example.ui.components.LiquidGlassFloatingPill
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.rememberCloudflareWebViewLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TapZoneAction { PREV, NEXT, TOGGLE_HUD }

fun resolveReaderTapAction(
    layout: TapZoneLayout,
    relX: Float,
    relY: Float,
    isRtl: Boolean
): TapZoneAction {
    val raw = when (layout) {
        TapZoneLayout.SIDES -> when {
            relX < 0.30f -> TapZoneAction.PREV
            relX > 0.70f -> TapZoneAction.NEXT
            else -> TapZoneAction.TOGGLE_HUD
        }
        TapZoneLayout.L_SHAPE -> when {
            relY < 0.28f && relX < 0.65f -> TapZoneAction.PREV
            relX < 0.25f && relY < 0.72f -> TapZoneAction.PREV
            relX > 0.72f || relY > 0.72f -> TapZoneAction.NEXT
            else -> TapZoneAction.TOGGLE_HUD
        }
        TapZoneLayout.KINDLISH -> when {
            relY < 0.25f -> TapZoneAction.TOGGLE_HUD
            relX < 0.25f -> TapZoneAction.PREV
            else -> TapZoneAction.NEXT
        }
        TapZoneLayout.EDGES -> when {
            relX < 0.18f -> TapZoneAction.PREV
            relX > 0.82f -> TapZoneAction.NEXT
            else -> TapZoneAction.TOGGLE_HUD
        }
    }
    return if (isRtl) {
        when (raw) {
            TapZoneAction.PREV -> TapZoneAction.NEXT
            TapZoneAction.NEXT -> TapZoneAction.PREV
            TapZoneAction.TOGGLE_HUD -> TapZoneAction.TOGGLE_HUD
        }
    } else raw
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launchWebView = rememberCloudflareWebViewLauncher {
        state.chapter?.let { viewModel.loadChapter(it.id) }
    }
    var showOverlay by remember { mutableStateOf(true) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showChaptersSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val view = LocalView.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    BackHandler(enabled = showSettingsSheet || showChaptersSheet) {
        showSettingsSheet = false
        showChaptersSheet = false
    }

    // Auto-hide HUD timer when configured
    LaunchedEffect(showOverlay, state.autoHideMs, state.currentPage, showSettingsSheet, showChaptersSheet) {
        if (showOverlay && state.autoHideMs > 0 && !showSettingsSheet && !showChaptersSheet) {
            delay(state.autoHideMs.toLong())
            showOverlay = false
        }
    }

    // Window Flags: Keep Screen Awake, Brightness, Orientation, System Bars
    DisposableEffect(
        state.keepScreenAwake,
        state.brightness,
        state.orientationLock,
        state.hideSystemBars,
        showOverlay
    ) {
        val window = activity?.window
        if (window != null) {
            if (state.keepScreenAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val lp = window.attributes
            lp.screenBrightness = state.brightness
            window.attributes = lp

            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (state.hideSystemBars && !showOverlay) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        activity?.requestedOrientation = when (state.orientationLock) {
            OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        onDispose {
            viewModel.persistCurrentSession()
            activity?.window?.let { w ->
                w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = w.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
                WindowCompat.getInsetsController(w, view).show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val bgColor = Color(state.background.colorHex)
    val contentScale = when (state.scaleMode) {
        ImageScaleMode.FIT_WIDTH -> ContentScale.FillWidth
        ImageScaleMode.FIT_HEIGHT -> ContentScale.FillHeight
        ImageScaleMode.FIT_SCREEN -> ContentScale.Fit
        ImageScaleMode.ORIGINAL -> ContentScale.None
        ImageScaleMode.CUSTOM -> ContentScale.Fit
    }
    val marginDp = state.pageMarginsDp.dp
    val isCurrentBookmarked = state.currentPage in state.bookmarkedPages

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { keyEvent ->
                if (state.volumeNavEnabled && keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.VolumeDown -> {
                            viewModel.onPageChanged(state.currentPage + 1)
                            true
                        }
                        Key.VolumeUp -> {
                            viewModel.onPageChanged(state.currentPage - 1)
                            true
                        }
                        else -> false
                    }
                } else false
            }
    ) {
        when {
            state.isLoading -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 3.dp
                    )
                    LiquidGlassFloatingPill {
                        Text(
                            text = "Loading chapter…",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            state.error != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ErrorRetryBanner(
                        message = state.error!!,
                        onRetry = { state.chapter?.let { viewModel.loadChapter(it.id) } },
                        onOpenWebView = state.cloudflareUrl?.let { cfUrl ->
                            { launchWebView(cfUrl, state.manga?.sourceName ?: "Source") }
                        }
                    )
                    OutlinedButton(onClick = onBack, shape = CircleShape) {
                        Text("Back to Manga")
                    }
                }
            }
            state.pages.isNotEmpty() -> {
                val pages = state.pages
                val mode = state.effectiveMode

                when (mode) {
                    ReadingMode.WEBTOON, ReadingMode.CONTINUOUS_VERTICAL -> {
                        val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
                        LaunchedEffect(state.currentPage) {
                            if (kotlin.math.abs(listState.firstVisibleItemIndex - state.currentPage) > 1) {
                                listState.scrollToItem(state.currentPage)
                            }
                        }
                        LaunchedEffect(listState.firstVisibleItemIndex) {
                            viewModel.onPageChanged(listState.firstVisibleItemIndex)
                        }
                        LazyColumn(
                            state = listState,
                            verticalArrangement = Arrangement.spacedBy(if (mode == ReadingMode.WEBTOON) 0.dp else 12.dp),
                            contentPadding = PaddingValues(horizontal = marginDp),
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(state.tapZonesEnabled) {
                                    detectTapGestures(
                                        onTap = { offset ->
                                            if (!state.tapZonesEnabled) {
                                                showOverlay = !showOverlay
                                                return@detectTapGestures
                                            }
                                            val relY = offset.y / size.height
                                            when {
                                                relY < 0.25f -> scope.launch {
                                                    listState.animateScrollToItem((state.currentPage - 1).coerceAtLeast(0))
                                                }
                                                relY > 0.75f -> scope.launch {
                                                    listState.animateScrollToItem((state.currentPage + 1).coerceAtMost(pages.lastIndex))
                                                }
                                                else -> showOverlay = !showOverlay
                                            }
                                        }
                                    )
                                }
                        ) {
                            itemsIndexed(pages, key = { _, p -> p.index }) { _, page ->
                                RetryablePageImage(
                                    imageUrl = page.imageUrl,
                                    headers = page.headers,
                                    contentDescription = "Page ${page.index + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer(
                                            scaleX = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                            scaleY = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f
                                        )
                                )
                            }
                        }
                    }

                    ReadingMode.VERTICAL -> {
                        val pagerState = rememberPagerState(
                            initialPage = state.currentPage.coerceIn(0, pages.lastIndex),
                            pageCount = { pages.size }
                        )
                        LaunchedEffect(pagerState.currentPage) { viewModel.onPageChanged(pagerState.currentPage) }
                        LaunchedEffect(state.currentPage) {
                            if (pagerState.currentPage != state.currentPage) {
                                pagerState.scrollToPage(state.currentPage)
                            }
                        }
                        VerticalPager(
                            state = pagerState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = marginDp)
                        ) { idx ->
                            val scrollAction: (Int) -> Unit = { target ->
                                scope.launch {
                                    if (state.transitionMode == ReaderTransitionMode.NONE) {
                                        pagerState.scrollToPage(target)
                                    } else {
                                        pagerState.animateScrollToPage(
                                            target,
                                            animationSpec = tween(if (state.transitionMode == ReaderTransitionMode.FADE) 180 else 280)
                                        )
                                    }
                                }
                            }
                            ReaderPageSurface(
                                imageUrl = pages[idx].imageUrl,
                                headers = pages[idx].headers,
                                pageIndex = idx,
                                contentScale = contentScale,
                                baseScale = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                tapZonesEnabled = state.tapZonesEnabled,
                                tapZoneLayout = state.tapZoneLayout,
                                isRtl = false,
                                onPrev = { scrollAction((idx - 1).coerceAtLeast(0)) },
                                onNext = { scrollAction((idx + 1).coerceAtMost(pages.lastIndex)) },
                                onToggleHud = { showOverlay = !showOverlay }
                            )
                        }
                    }

                    ReadingMode.DOUBLE_PAGE -> {
                        val spreads = remember(pages) { pages.chunked(2) }
                        val pagerState = rememberPagerState(
                            initialPage = (state.currentPage / 2).coerceIn(0, spreads.lastIndex),
                            pageCount = { spreads.size }
                        )
                        LaunchedEffect(pagerState.currentPage) {
                            viewModel.onPageChanged((pagerState.currentPage * 2).coerceAtMost(pages.lastIndex))
                        }
                        HorizontalPager(
                            state = pagerState,
                            reverseLayout = true,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = marginDp)
                        ) { spreadIdx ->
                            val pair = spreads[spreadIdx]
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { showOverlay = !showOverlay },
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                pair.reversed().forEach { p ->
                                    RetryablePageImage(
                                        imageUrl = p.imageUrl,
                                        headers = p.headers,
                                        contentDescription = "Page ${p.index + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        val isRtl = mode == ReadingMode.RIGHT_TO_LEFT
                        val pagerState = rememberPagerState(
                            initialPage = state.currentPage.coerceIn(0, pages.lastIndex),
                            pageCount = { pages.size }
                        )
                        LaunchedEffect(pagerState.currentPage) { viewModel.onPageChanged(pagerState.currentPage) }
                        LaunchedEffect(state.currentPage) {
                            if (pagerState.currentPage != state.currentPage) {
                                pagerState.scrollToPage(state.currentPage)
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            reverseLayout = isRtl,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = marginDp)
                        ) { idx ->
                            val scrollAction: (Int) -> Unit = { target ->
                                scope.launch {
                                    if (state.transitionMode == ReaderTransitionMode.NONE) {
                                        pagerState.scrollToPage(target)
                                    } else {
                                        pagerState.animateScrollToPage(
                                            target,
                                            animationSpec = tween(if (state.transitionMode == ReaderTransitionMode.FADE) 180 else 280)
                                        )
                                    }
                                }
                            }
                            ReaderPageSurface(
                                imageUrl = pages[idx].imageUrl,
                                headers = pages[idx].headers,
                                pageIndex = idx,
                                contentScale = contentScale,
                                baseScale = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                tapZonesEnabled = state.tapZonesEnabled,
                                tapZoneLayout = state.tapZoneLayout,
                                isRtl = isRtl,
                                onPrev = { scrollAction((idx - 1).coerceAtLeast(0)) },
                                onNext = { scrollAction((idx + 1).coerceAtMost(pages.lastIndex)) },
                                onToggleHud = { showOverlay = !showOverlay }
                            )
                        }
                    }
                }
            }
        }

        // Ultra-minimal frosted page indicator pill when HUD is hidden
        if (!showOverlay && state.pages.isNotEmpty()) {
            LiquidGlassFloatingPill(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .navigationBarsPadding(),
                onClick = { showOverlay = true }
            ) {
                if (isCurrentBookmarked) {
                    Icon(
                        Icons.Default.Bookmark,
                        contentDescription = "Bookmarked page",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = "${state.currentPage + 1} / ${state.pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Top Frosted Glass HUD Overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 14.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KomaCircularGlassButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Exit reader",
                        onClick = onBack,
                        containerSize = 40.dp
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = state.manga?.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = state.chapter?.name ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (state.isOfflineChapter) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    shape = CircleShape
                                ) {
                                    Text(
                                        text = "OFFLINE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                    KomaCircularGlassButton(
                        icon = if (isCurrentBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark page",
                        onClick = viewModel::toggleCurrentPageBookmark,
                        tint = if (isCurrentBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        containerSize = 40.dp,
                        modifier = Modifier.testTag("reader_bookmark_page_button")
                    )
                    KomaCircularGlassButton(
                        icon = Icons.Default.List,
                        contentDescription = "Chapters & Bookmarks",
                        onClick = { showChaptersSheet = true },
                        containerSize = 40.dp
                    )
                    KomaCircularGlassButton(
                        icon = Icons.Default.Tune,
                        contentDescription = "Reader settings",
                        onClick = { showSettingsSheet = true },
                        tint = MaterialTheme.colorScheme.primary,
                        containerSize = 40.dp,
                        modifier = Modifier.testTag("reader_settings_button")
                    )
                }
            }
        }

        // Bottom Frosted Glass HUD Overlay
        AnimatedVisibility(
            visible = showOverlay && state.pages.isNotEmpty(),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(26.dp),
                shadowElevation = 14.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (state.bookmarkedPages.isNotEmpty()) {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bookmark,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            state.bookmarkedPages.sorted().forEach { bm ->
                                AssistChip(
                                    onClick = { viewModel.onPageChanged(bm) },
                                    label = { Text("p.${bm + 1}") },
                                    shape = CircleShape
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = viewModel::goToPreviousChapter,
                            enabled = state.hasPrevChapter
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.NavigateBefore,
                                contentDescription = "Previous chapter"
                            )
                        }
                        Text(
                            text = "${state.currentPage + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Slider(
                            value = state.currentPage.toFloat(),
                            onValueChange = { viewModel.onPageChanged(it.toInt()) },
                            valueRange = 0f..(state.pages.lastIndex.coerceAtLeast(1)).toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.pages.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = viewModel::goToNextChapter,
                            enabled = state.hasNextChapter
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.NavigateNext,
                                contentDescription = "Next chapter"
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettingsSheet) {
        ReaderSettingsBottomSheet(
            state = state,
            onDismiss = { showSettingsSheet = false },
            onReadingMode = viewModel::setReadingMode,
            onScaleMode = viewModel::setScaleMode,
            onCustomZoom = viewModel::setCustomZoom,
            onBackground = viewModel::setBackground,
            onTapZones = viewModel::setTapZones,
            onTapZoneLayout = viewModel::setTapZoneLayout,
            onPageMargins = viewModel::setPageMarginsDp,
            onAutoHideMs = viewModel::setAutoHideMs,
            onTransitionMode = viewModel::setTransitionMode,
            onVolumeNav = viewModel::setVolumeNav,
            onKeepAwake = viewModel::setKeepAwake,
            onHideBars = viewModel::setHideSystemBars,
            onBrightness = viewModel::setBrightness,
            onOrientationLock = viewModel::setOrientationLock
        )
    }

    if (showChaptersSheet) {
        ModalBottomSheet(onDismissRequest = { showChaptersSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "Chapters",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 380.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.allChapters, key = { _, c -> c.id }) { _, ch ->
                        val active = ch.id == state.chapter?.id
                        KomaElevatedCard(
                            shape = RoundedCornerShape(16.dp),
                            containerColor = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            },
                            borderColor = if (active) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                            },
                            onClick = {
                                viewModel.persistCurrentSession()
                                viewModel.loadChapter(ch.id)
                                showChaptersSheet = false
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = ch.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (active) {
                                    Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = "Current",
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.padding(4.dp).size(16.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryablePageImage(
    imageUrl: String,
    contentDescription: String,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap()
) {
    val context = LocalContext.current
    var retryNonce by remember(imageUrl) { mutableIntStateOf(0) }
    val requestModel = remember(imageUrl, headers, retryNonce, context) {
        val url = if (retryNonce == 0) {
            imageUrl
        } else {
            "$imageUrl${if ('?' in imageUrl) '&' else '?'}kiyomiRetry=$retryNonce"
        }
        ImageRequest.Builder(context)
            .data(url)
            .apply {
                if (headers.isNotEmpty()) {
                    headers(headers.toHeaders())
                }
            }
            .build()
    }
    SubcomposeAsyncImage(
        model = requestModel,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 240.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(30.dp)
                )
            }
        },
        error = { errorState ->
            val hostHint = remember(imageUrl) {
                runCatching { java.net.URI(imageUrl).host }.getOrNull()
            }
            val reason = remember(errorState.result.throwable, hostHint) {
                NetworkErrorFormatter.describe(errorState.result.throwable, hostHint)
            }
            val cfUrl = remember(errorState.result.throwable, imageUrl, reason) {
                NetworkErrorFormatter.extractCloudflareUrl(errorState.result.throwable)
                    ?: imageUrl.takeIf { it.startsWith("http", ignoreCase = true) && reason.contains("Cloudflare", ignoreCase = true) }
            }
            val launchPageWebView = rememberCloudflareWebViewLauncher { retryNonce++ }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .heightIn(min = 240.dp)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xFF171922).copy(alpha = 0.88f),
                    shape = RoundedCornerShape(22.dp),
                    border = BorderStroke(0.75.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.BrokenImage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "Page failed to load",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                        Text(
                            text = reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.72f),
                            textAlign = TextAlign.Center,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (cfUrl != null) {
                            OutlinedButton(
                                onClick = { launchPageWebView(cfUrl, hostHint ?: "Image Host") },
                                shape = CircleShape,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("page_error_webview_button")
                            ) {
                                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Open WebView")
                            }
                        }
                        Button(onClick = { retryNonce++ }, shape = CircleShape) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Retry Page")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ReaderPageSurface(
    imageUrl: String,
    pageIndex: Int,
    contentScale: ContentScale,
    baseScale: Float,
    tapZonesEnabled: Boolean,
    tapZoneLayout: TapZoneLayout,
    isRtl: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggleHud: () -> Unit,
    headers: Map<String, String> = emptyMap()
) {
    var scale by remember(imageUrl, baseScale) { mutableFloatStateOf(baseScale) }
    var offsetX by remember(imageUrl) { mutableFloatStateOf(0f) }
    var offsetY by remember(imageUrl) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        if (scale > 1.05f) {
            offsetX += panChange.x
            offsetY += panChange.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (scale > 1.05f) Modifier.transformable(transformState) else Modifier)
            .pointerInput(tapZonesEnabled, tapZoneLayout, isRtl) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.2f) {
                            scale = baseScale
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.2f
                        }
                    },
                    onTap = { pos ->
                        if (!tapZonesEnabled) {
                            onToggleHud()
                            return@detectTapGestures
                        }
                        when (resolveReaderTapAction(tapZoneLayout, pos.x / size.width, pos.y / size.height, isRtl)) {
                            TapZoneAction.PREV -> onPrev()
                            TapZoneAction.NEXT -> onNext()
                            TapZoneAction.TOGGLE_HUD -> onToggleHud()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        RetryablePageImage(
            imageUrl = imageUrl,
            headers = headers,
            contentDescription = "Manga page ${pageIndex + 1}",
            contentScale = contentScale,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsBottomSheet(
    state: ReaderUiState,
    onDismiss: () -> Unit,
    onReadingMode: (ReadingMode) -> Unit,
    onScaleMode: (ImageScaleMode) -> Unit,
    onCustomZoom: (Float) -> Unit,
    onBackground: (ReaderBackground) -> Unit,
    onTapZones: (Boolean) -> Unit,
    onTapZoneLayout: (TapZoneLayout) -> Unit,
    onPageMargins: (Int) -> Unit,
    onAutoHideMs: (Int) -> Unit,
    onTransitionMode: (ReaderTransitionMode) -> Unit,
    onVolumeNav: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onHideBars: (Boolean) -> Unit,
    onBrightness: (Float) -> Unit,
    onOrientationLock: (OrientationLock) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Reader Controls",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                KomaElevatedCard(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Reading Mode", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReadingMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.readingMode == mode,
                                    onClick = { onReadingMode(mode) },
                                    label = { Text(mode.label) },
                                    shape = CircleShape
                                )
                            }
                        }

                        Text("Image Scaling", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImageScaleMode.entries.forEach { sc ->
                                FilterChip(
                                    selected = state.scaleMode == sc,
                                    onClick = { onScaleMode(sc) },
                                    label = { Text(sc.label) },
                                    shape = CircleShape
                                )
                            }
                        }
                        if (state.scaleMode == ImageScaleMode.CUSTOM) {
                            Text("Custom Scale: ${"%.1f".format(state.customZoom)}x", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = state.customZoom,
                                onValueChange = onCustomZoom,
                                valueRange = 0.7f..2.5f
                            )
                        }

                        Text("Side Margins: ${state.pageMarginsDp} dp", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = state.pageMarginsDp.toFloat(),
                            onValueChange = { onPageMargins(it.toInt()) },
                            valueRange = 0f..32f,
                            steps = 7
                        )
                    }
                }
            }

            item {
                KomaElevatedCard(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Tap Zone Layout", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TapZoneLayout.entries.forEach { layout ->
                                FilterChip(
                                    selected = state.tapZoneLayout == layout,
                                    onClick = { onTapZoneLayout(layout) },
                                    label = { Text(layout.label) },
                                    shape = CircleShape
                                )
                            }
                        }
                        Text(
                            state.tapZoneLayout.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text("Page Transition", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ReaderTransitionMode.entries.forEach { tm ->
                                FilterChip(
                                    selected = state.transitionMode == tm,
                                    onClick = { onTransitionMode(tm) },
                                    label = { Text(tm.label) },
                                    shape = CircleShape
                                )
                            }
                        }

                        Text("Background Color", style = MaterialTheme.typography.labelLarge)
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ReaderBackground.entries.forEach { bg ->
                                FilterChip(
                                    selected = state.background == bg,
                                    onClick = { onBackground(bg) },
                                    label = { Text(bg.label) },
                                    shape = CircleShape
                                )
                            }
                        }
                    }
                }
            }

            item {
                KomaElevatedCard(shape = RoundedCornerShape(18.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Auto-Hide Controls Timer", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Manual", 3000 to "3s", 5000 to "5s").forEach { (ms, lbl) ->
                                FilterChip(
                                    selected = state.autoHideMs == ms,
                                    onClick = { onAutoHideMs(ms) },
                                    label = { Text(lbl) },
                                    shape = CircleShape
                                )
                            }
                        }

                        Text("Screen Orientation", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OrientationLock.entries.forEach { lock ->
                                FilterChip(
                                    selected = state.orientationLock == lock,
                                    onClick = { onOrientationLock(lock) },
                                    label = { Text(lock.label) },
                                    shape = CircleShape
                                )
                            }
                        }

                        val isAutoBrightness = state.brightness < 0f
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Custom Brightness", style = MaterialTheme.typography.bodyMedium)
                            Switch(
                                checked = !isAutoBrightness,
                                onCheckedChange = { custom -> onBrightness(if (custom) 0.5f else -1f) }
                            )
                        }
                        if (!isAutoBrightness) {
                            Slider(
                                value = state.brightness.coerceIn(0.05f, 1f),
                                onValueChange = onBrightness,
                                valueRange = 0.05f..1f
                            )
                        }

                        SettingToggleRow("Tap Zones Navigation", state.tapZonesEnabled, onTapZones)
                        SettingToggleRow("Volume Button Navigation", state.volumeNavEnabled, onVolumeNav)
                        SettingToggleRow("Keep Screen Awake", state.keepScreenAwake, onKeepAwake)
                        SettingToggleRow("Hide System Bars (Immersive)", state.hideSystemBars, onHideBars)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
