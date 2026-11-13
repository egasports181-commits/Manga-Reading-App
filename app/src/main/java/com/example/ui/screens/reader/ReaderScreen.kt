package com.example.ui.screens.reader

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.domain.model.*
import com.example.ui.components.ErrorRetryBanner
import kotlinx.coroutines.launch

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
                                ZoomablePageImage(
                                    imageUrl = page.imageUrl,
                                    contentDescription = "Page ${page.index + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    baseScale = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                    modifier = Modifier.fillMaxWidth()
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
                            modifier = Modifier.fillMaxSize()
                        ) { idx ->
                            ReaderPageSurface(
                                imageUrl = pages[idx].imageUrl,
                                contentScale = contentScale,
                                baseScale = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                tapZonesEnabled = state.tapZonesEnabled,
                                isRtl = false,
                                onPrev = { scope.launch { pagerState.animateScrollToPage((idx - 1).coerceAtLeast(0)) } },
                                onNext = { scope.launch { pagerState.animateScrollToPage((idx + 1).coerceAtMost(pages.lastIndex)) } },
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
                            modifier = Modifier.fillMaxSize()
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
                                    AsyncImage(
                                        model = p.imageUrl,
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
                            modifier = Modifier.fillMaxSize()
                        ) { idx ->
                            ReaderPageSurface(
                                imageUrl = pages[idx].imageUrl,
                                contentScale = contentScale,
                                baseScale = if (state.scaleMode == ImageScaleMode.CUSTOM) state.customZoom else 1f,
                                tapZonesEnabled = state.tapZonesEnabled,
                                isRtl = isRtl,
                                onPrev = { scope.launch { pagerState.animateScrollToPage((idx - 1).coerceAtLeast(0)) } },
                                onNext = { scope.launch { pagerState.animateScrollToPage((idx + 1).coerceAtMost(pages.lastIndex)) } },
                                onToggleHud = { showOverlay = !showOverlay }
                            )
                        }
                    }
                }
            }
        }

        // Persistent discreet page indicator when HUD is hidden
        if (!showOverlay && state.pages.isNotEmpty()) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    text = "${state.currentPage + 1} / ${state.pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Top HUD Overlay
        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.82f)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Exit reader", tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.manga?.title ?: "",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(state.chapter?.name ?: "")
                                if (state.isOfflineChapter) append(" • Offline")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { showChaptersSheet = true }) {
                        Icon(Icons.Default.List, contentDescription = "Chapter list", tint = Color.White)
                    }
                    IconButton(
                        onClick = { showSettingsSheet = true },
                        modifier = Modifier.testTag("reader_settings_button")
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = "Reader settings", tint = Color.White)
                    }
                }
            }
        }

        // Bottom HUD Overlay
        AnimatedVisibility(
            visible = showOverlay && state.pages.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(color = Color.Black.copy(alpha = 0.85f)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = viewModel::goToPreviousChapter,
                            enabled = state.hasPrevChapter
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateBefore, contentDescription = "Previous chapter", tint = Color.White)
                        }
                        Text(
                            text = "${state.currentPage + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        Slider(
                            value = state.currentPage.toFloat(),
                            onValueChange = { viewModel.onPageChanged(it.toInt()) },
                            valueRange = 0f..(state.pages.lastIndex.coerceAtLeast(1)).toFloat(),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${state.pages.size}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White
                        )
                        IconButton(
                            onClick = viewModel::goToNextChapter,
                            enabled = state.hasNextChapter
                        ) {
                            Icon(Icons.AutoMirrored.Filled.NavigateNext, contentDescription = "Next chapter", tint = Color.White)
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
private fun ReaderPageSurface(
    imageUrl: String,
    contentScale: ContentScale,
    baseScale: Float,
    tapZonesEnabled: Boolean,
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
            .pointerInput(tapZonesEnabled, isRtl) {
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
                        val relX = pos.x / size.width
                        when {
                            relX < 0.30f -> if (isRtl) onNext() else onPrev()
                            relX > 0.70f -> if (isRtl) onPrev() else onNext()
                            else -> onToggleHud()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Manga page",
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

@Composable
private fun ZoomablePageImage(
    imageUrl: String,
    contentDescription: String,
    contentScale: ContentScale,
    baseScale: Float,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier.graphicsLayer(scaleX = baseScale, scaleY = baseScale)
    )
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
