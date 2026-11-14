package com.example.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.SubcomposeAsyncImage
import com.example.domain.model.*
import com.example.network.NetworkErrorFormatter
import com.example.ui.components.ErrorRetryBanner
import com.example.ui.components.LiquidGlassFloatingPill
import com.example.ui.components.LiquidGlassSurface
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
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            state.error != null -> {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ErrorRetryBanner(
                        message = state.error!!,
                        onRetry = { state.chapter?.let { viewModel.loadChapter(it.id) } }
                    )
                    OutlinedButton(onClick = onBack) { Text("Back to Manga") }
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
                            modifier = Modifier.fillMaxSize().padding(horizontal = marginDp)
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
                            modifier = Modifier.fillMaxSize().padding(horizontal = marginDp)
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
                                        contentDescription = "Page ${p.index + 1}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.weight(1f).fillMaxHeight()
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
                            modifier = Modifier.fillMaxSize().padding(horizontal = marginDp)
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

        // Discreet floating liquid glass page pill when HUD is hidden
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
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        // Top Liquid Glass HUD Overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit reader")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.manga?.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(state.chapter?.name ?: "")
                                if (state.isOfflineChapter) append(" • Offline")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(
                        onClick = viewModel::toggleCurrentPageBookmark,
                        modifier = Modifier.testTag("reader_bookmark_page_button")
                    ) {
                        Icon(
                            if (isCurrentBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                            contentDescription = "Bookmark page",
                            tint = if (isCurrentBookmarked) MaterialTheme.colorScheme.primary else LocalContentColor.current
                        )
                    }
                    IconButton(onClick = { showChaptersSheet = true }) {
                        Icon(Icons.Default.List, contentDescription = "Chapters & Bookmarks")
                    }
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("reader_settings_button")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Reader settings")
                    }
                }
            }
        }

        // Bottom Liquid Glass HUD Overlay
        AnimatedVisibility(
            visible = showOverlay && state.pages.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 12.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                    label = { Text("p.${bm + 1}") }
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
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous chapter")
                        }
                        Text(
                            text = "${state.currentPage + 1}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Slider(
                            value = state.currentPage.toFloat(),
                            onValueChange = { viewModel.onPageChanged(it.toInt()) },
                            valueRange = 0f..(state.pages.lastIndex.coerceAtLeast(1)).toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.pages.size}",
                            style = MaterialTheme.typography.labelLarge
                        )
                        IconButton(
                            onClick = viewModel::goToNextChapter,
                            enabled = state.hasNextChapter
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next chapter")
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
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "Chapters",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(state.allChapters, key = { _, c -> c.id }) { _, ch ->
                        val active = ch.id == state.chapter?.id
                        ListItem(
                            headlineContent = {
                                Text(
                                    ch.name,
                                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingContent = {
                                if (active) Icon(Icons.Default.PlayArrow, contentDescription = "Current")
                            },
                            modifier = Modifier.clickable {
                                viewModel.persistCurrentSession()
                                viewModel.loadChapter(ch.id)
                                showChaptersSheet = false
                            }
                        )
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
    modifier: Modifier = Modifier
) {
    var retryNonce by remember(imageUrl) { mutableIntStateOf(0) }
    val requestModel = remember(imageUrl, retryNonce) {
        if (retryNonce == 0) imageUrl else "$imageUrl${if ('?' in imageUrl) '&' else '?'}komaRetry=$retryNonce"
    }
    SubcomposeAsyncImage(
        model = requestModel,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
        loading = {
            Box(
                modifier = Modifier.fillMaxSize().heightIn(min = 240.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
        },
        error = { errorState ->
            val hostHint = remember(imageUrl) {
                runCatching { java.net.URI(imageUrl).host }.getOrNull()
            }
            val reason = remember(errorState.result.throwable, hostHint) {
                NetworkErrorFormatter.describe(errorState.result.throwable, hostHint)
            }
            Column(
                modifier = Modifier.fillMaxSize().heightIn(min = 240.dp).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.height(8.dp))
                Text("Page failed to load", style = MaterialTheme.typography.bodySmall, color = Color.White)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { retryNonce++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Retry Page")
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
    onToggleHud: () -> Unit
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
            item { Text("Reader Controls", style = MaterialTheme.typography.headlineSmall) }

            item {
                Text("Reading Mode", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReadingMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.readingMode == mode,
                            onClick = { onReadingMode(mode) },
                            label = { Text(mode.label) }
                        )
                    }
                }
            }

            item {
                Text("Tap Zone Layout", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TapZoneLayout.entries.forEach { layout ->
                        FilterChip(
                            selected = state.tapZoneLayout == layout,
                            onClick = { onTapZoneLayout(layout) },
                            label = { Text(layout.label) }
                        )
                    }
                }
                Text(
                    state.tapZoneLayout.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                Text("Image Scaling", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ImageScaleMode.entries.forEach { sc ->
                        FilterChip(
                            selected = state.scaleMode == sc,
                            onClick = { onScaleMode(sc) },
                            label = { Text(sc.label) }
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
            }

            item {
                Text("Side Margins: ${state.pageMarginsDp} dp", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = state.pageMarginsDp.toFloat(),
                    onValueChange = { onPageMargins(it.toInt()) },
                    valueRange = 0f..32f,
                    steps = 7
                )
            }

            item {
                Text("Page Transition", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderTransitionMode.entries.forEach { tm ->
                        FilterChip(
                            selected = state.transitionMode == tm,
                            onClick = { onTransitionMode(tm) },
                            label = { Text(tm.label) }
                        )
                    }
                }
            }

            item {
                Text("Background Color", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ReaderBackground.entries.forEach { bg ->
                        FilterChip(
                            selected = state.background == bg,
                            onClick = { onBackground(bg) },
                            label = { Text(bg.label) }
                        )
                    }
                }
            }

            item {
                Text("Auto-Hide Controls Timer", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Manual", 3000 to "3s", 5000 to "5s").forEach { (ms, lbl) ->
                        FilterChip(
                            selected = state.autoHideMs == ms,
                            onClick = { onAutoHideMs(ms) },
                            label = { Text(lbl) }
                        )
                    }
                }
            }

            item {
                Text("Screen Orientation", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OrientationLock.entries.forEach { lock ->
                        FilterChip(
                            selected = state.orientationLock == lock,
                            onClick = { onOrientationLock(lock) },
                            label = { Text(lock.label) }
                        )
                    }
                }
            }

            item {
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
            }

            item {
                SettingToggleRow("Tap Zones Navigation", state.tapZonesEnabled, onTapZones)
                SettingToggleRow("Volume Button Navigation", state.volumeNavEnabled, onVolumeNav)
                SettingToggleRow("Keep Screen Awake", state.keepScreenAwake, onKeepAwake)
                SettingToggleRow("Hide System Bars (Immersive)", state.hideSystemBars, onHideBars)
            }
        }
    }
}

@Composable
private fun SettingToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
