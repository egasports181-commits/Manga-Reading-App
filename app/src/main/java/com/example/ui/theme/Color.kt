package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Deep Charcoal & Obsidian Dark Palette
val AmoledBlack = Color(0xFF000000)
val ObsidianBg = Color(0xFF0C0D11)
val ObsidianSurfaceLowest = Color(0xFF090A0D)
val ObsidianSurface = Color(0xFF13151C)
val ObsidianSurfaceHigh = Color(0xFF1B1E27)
val ObsidianSurfaceHighest = Color(0xFF242834)
val ObsidianOutline = Color(0xFF303545)
val InkWhite = Color(0xFFF4F4F7)
val InkMuted = Color(0xFF9BA1B4)

// Warm Off-White & Neutral Light Palette
val DaylightBg = Color(0xFFF6F5F2)
val DaylightSurfaceLowest = Color(0xFFFFFFFF)
val DaylightSurface = Color(0xFFFCFBF9)
val DaylightSurfaceHigh = Color(0xFFEFEDE8)
val DaylightSurfaceHighest = Color(0xFFE5E2DC)
val DaylightOutline = Color(0xFFD6D2C9)
val DaylightInk = Color(0xFF14151A)
val DaylightMuted = Color(0xFF606472)

// Signature Warm Orange Tokens (sophisticated persimmon-amber, balanced contrast in Dark & Light)
val WarmOrangeDark = Color(0xFFF37A37)
val WarmOrangeLight = Color(0xFFE2631D)
val WarmOrangeSoft = Color(0xFFFFA366)
val WarmOrangeContainerDark = Color(0xFF3A1E0E)
val WarmOrangeContainerLight = Color(0xFFFCE7DA)

// Expressive Accent Presets (Warm Orange is the default Koma signature)
enum class AccentPreset(
    val id: String,
    val label: String,
    val primary: Color,
    val lightPrimary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val lightPrimaryContainer: Color,
    val secondary: Color,
    val tertiary: Color
) {
    WARM_ORANGE(
        id = "warm_orange",
        label = "Warm Orange",
        primary = WarmOrangeDark,
        lightPrimary = WarmOrangeLight,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = WarmOrangeContainerDark,
        lightPrimaryContainer = WarmOrangeContainerLight,
        secondary = Color(0xFFF5A623),
        tertiary = Color(0xFF38BDF8)
    ),
    CRIMSON_INK(
        id = "crimson",
        label = "Crimson Ink",
        primary = Color(0xFFE11D48),
        lightPrimary = Color(0xFFBE123C),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4C0519),
        lightPrimaryContainer = Color(0xFFFFE4E6),
        secondary = Color(0xFFF59E0B),
        tertiary = Color(0xFF38BDF8)
    ),
    ELECTRIC_COBALT(
        id = "cobalt",
        label = "Electric Cobalt",
        primary = Color(0xFF3B82F6),
        lightPrimary = Color(0xFF2563EB),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF172554),
        lightPrimaryContainer = Color(0xFFDBEAFE),
        secondary = Color(0xFF22D3EE),
        tertiary = Color(0xFFF43F5E)
    ),
    EMERALD_JADE(
        id = "emerald",
        label = "Emerald Jade",
        primary = Color(0xFF10B981),
        lightPrimary = Color(0xFF059669),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF064E3B),
        lightPrimaryContainer = Color(0xFFD1FAE5),
        secondary = Color(0xFFFBBF24),
        tertiary = Color(0xFF60A5FA)
    ),
    AMBER_SUNSET(
        id = "amber",
        label = "Amber Gold",
        primary = Color(0xFFF59E0B),
        lightPrimary = Color(0xFFD97706),
        onPrimary = Color(0xFF1F1300),
        primaryContainer = Color(0xFF451A03),
        lightPrimaryContainer = Color(0xFFFEF3C7),
        secondary = Color(0xFFFB7185),
        tertiary = Color(0xFF34D399)
    ),
    VIOLET_DUSK(
        id = "violet",
        label = "Violet Dusk",
        primary = Color(0xFF8B5CF6),
        lightPrimary = Color(0xFF7C3AED),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF2E1065),
        lightPrimaryContainer = Color(0xFFEDE9FE),
        secondary = Color(0xFFF472B6),
        tertiary = Color(0xFF38BDF8)
    );

    companion object {
        fun fromId(id: String): AccentPreset = entries.firstOrNull { it.id == id } ?: WARM_ORANGE
    }
}

val StatusOngoing = Color(0xFF10B981)
val StatusCompleted = Color(0xFF3B82F6)
val StatusHiatus = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
