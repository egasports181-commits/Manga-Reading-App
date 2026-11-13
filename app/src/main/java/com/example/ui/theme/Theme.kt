package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "System Default"),
    DARK("dark", "Dark"),
    LIGHT("light", "Light");

    companion object {
        fun fromId(id: String): ThemeMode = entries.firstOrNull { it.id == id } ?: DARK
    }
}

val KomaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun KomaTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    amoledBlack: Boolean = false,
    dynamicColor: Boolean = false,
    accentPreset: AccentPreset = AccentPreset.CRIMSON_INK,
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
            surfaceContainerLowest = ObsidianBg,
            surfaceContainerLow = ObsidianSurface,
            surfaceContainer = ObsidianSurfaceHigh,
            surfaceContainerHigh = ObsidianSurfaceHighest,
            outline = ObsidianOutline,
            outlineVariant = ObsidianOutline.copy(alpha = 0.6f),
            error = StatusError,
            onError = Color.White
        )
        else -> lightColorScheme(
            primary = accentPreset.primary,
            onPrimary = accentPreset.onPrimary,
            primaryContainer = accentPreset.primary.copy(alpha = 0.14f),
            onPrimaryContainer = accentPreset.primary,
            secondary = accentPreset.secondary,
            onSecondary = Color.White,
            tertiary = accentPreset.tertiary,
            background = DaylightBg,
            onBackground = DaylightInk,
            surface = DaylightSurface,
            onSurface = DaylightInk,
            surfaceVariant = DaylightSurfaceHigh,
            onSurfaceVariant = DaylightMuted,
            outline = DaylightOutline,
            outlineVariant = DaylightOutline.copy(alpha = 0.6f),
            error = StatusError,
            onError = Color.White
        )
    }

    val finalScheme = if (isDark && amoledBlack) {
        baseScheme.copy(
            background = AmoledBlack,
            surface = AmoledBlack,
            surfaceContainerLowest = AmoledBlack,
            surfaceContainerLow = Color(0xFF090A0F),
            surfaceContainer = Color(0xFF10121A),
            surfaceContainerHigh = Color(0xFF171A24)
        )
    } else {
        baseScheme
    }

    MaterialTheme(
        colorScheme = finalScheme,
        typography = KomaTypography,
        shapes = KomaShapes,
        content = content
    )
}
