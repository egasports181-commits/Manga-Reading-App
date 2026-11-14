package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System Default"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromId(id: String): ThemeMode = entries.firstOrNull { it.id == id } ?: LIGHT
    }
}

val KomaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun KomaTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    amoledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    accentPreset: AccentPreset = AccentPreset.WARM_ORANGE,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }

    val context = LocalContext.current
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark -> darkColorScheme(
            primary = accentPreset.primary,
            onPrimary = accentPreset.onPrimary,
            primaryContainer = accentPreset.primaryContainer,
            onPrimaryContainer = InkWhite,
            secondary = accentPreset.secondary,
            onSecondary = ObsidianBg,
            secondaryContainer = ObsidianSurfaceHigh,
            onSecondaryContainer = InkWhite,
            tertiary = accentPreset.tertiary,
            background = ObsidianBg,
            onBackground = InkWhite,
            surface = ObsidianSurface,
            onSurface = InkWhite,
            surfaceVariant = ObsidianSurfaceHigh,
            onSurfaceVariant = InkMuted,
            surfaceContainerLowest = ObsidianSurfaceLowest,
            surfaceContainerLow = ObsidianSurface,
            surfaceContainer = ObsidianSurfaceHigh,
            surfaceContainerHigh = ObsidianSurfaceHighest,
            surfaceContainerHighest = Color(0xFF2D3242),
            outline = ObsidianOutline,
            outlineVariant = ObsidianOutline.copy(alpha = 0.55f),
            error = StatusError,
            onError = Color.White
        )
        else -> lightColorScheme(
            primary = accentPreset.lightPrimary,
            onPrimary = Color.White,
            primaryContainer = accentPreset.lightPrimaryContainer,
            onPrimaryContainer = accentPreset.lightPrimary,
            secondary = accentPreset.secondary,
            onSecondary = DaylightInk,
            secondaryContainer = DaylightSurfaceHigh,
            onSecondaryContainer = DaylightInk,
            tertiary = accentPreset.tertiary,
            background = DaylightBg,
            onBackground = DaylightInk,
            surface = DaylightSurface,
            onSurface = DaylightInk,
            surfaceVariant = DaylightSurfaceHigh,
            onSurfaceVariant = DaylightMuted,
            surfaceContainerLowest = DaylightSurfaceLowest,
            surfaceContainerLow = DaylightSurface,
            surfaceContainer = DaylightSurfaceLowest,
            surfaceContainerHigh = DaylightSurfaceHigh,
            surfaceContainerHighest = DaylightSurfaceHighest,
            outline = DaylightOutline,
            outlineVariant = DaylightOutline.copy(alpha = 0.65f),
            error = StatusError,
            onError = Color.White
        )
    }

    val targetScheme = if (isDark && amoledBlack) {
        baseScheme.copy(
            background = AmoledBlack,
            surface = AmoledBlack,
            surfaceContainerLowest = AmoledBlack,
            surfaceContainerLow = Color(0xFF090A0E),
            surfaceContainer = Color(0xFF11131A),
            surfaceContainerHigh = Color(0xFF191C25),
            surfaceContainerHighest = Color(0xFF222633)
        )
    } else {
        baseScheme
    }

    val animatedScheme = animateColorScheme(targetScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = animatedScheme,
        typography = KomaTypography,
        shapes = KomaShapes,
        content = content
    )
}

@Composable
private fun animateColorScheme(target: ColorScheme): ColorScheme {
    val spec = tween<Color>(durationMillis = 240)
    return target.copy(
        primary = animateColorAsState(target.primary, spec, label = "primary").value,
        primaryContainer = animateColorAsState(target.primaryContainer, spec, label = "primaryContainer").value,
        background = animateColorAsState(target.background, spec, label = "background").value,
        onBackground = animateColorAsState(target.onBackground, spec, label = "onBackground").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        onSurface = animateColorAsState(target.onSurface, spec, label = "onSurface").value,
        surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, label = "scLowest").value,
        surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, label = "scLow").value,
        surfaceContainer = animateColorAsState(target.surfaceContainer, spec, label = "sc").value,
        surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, label = "scHigh").value,
        surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, label = "scHighest").value
    )
}
