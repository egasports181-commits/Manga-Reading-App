package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Deep Charcoal & Obsidian Dark Palette
val AmoledBlack = Color(0xFF000000)
val ObsidianBg = Color(0xFF08080C)
val ObsidianSurfaceLowest = Color(0xFF060609)
val ObsidianSurface = Color(0xFF121318)
val ObsidianSurfaceHigh = Color(0xFF1B1C23)
val ObsidianSurfaceHighest = Color(0xFF252731)
val ObsidianOutline = Color(0xFF2E313D)
val InkWhite = Color(0xFFF7F7FA)
val InkMuted = Color(0xFF989AA8)

// Clean Crisp White & Subtle Gray Light Palette
val DaylightBg = Color(0xFFFFFFFF)
val DaylightSurfaceLowest = Color(0xFFFFFFFF)
val DaylightSurface = Color(0xFFFAFAFC)
val DaylightSurfaceHigh = Color(0xFFF3F4F7)
val DaylightSurfaceHighest = Color(0xFFE9EBEF)
val DaylightOutline = Color(0xFFE3E4EA)
val DaylightInk = Color(0xFF111114)
val DaylightMuted = Color(0xFF6B6E7B)

// Signature Vibrant Red-Orange Tokens
val WarmOrangeDark = Color(0xFFEF5325)
val WarmOrangeLight = Color(0xFFEF4B24)
val WarmOrangeSoft = Color(0xFFFF7A45)
val WarmOrangeContainerDark = Color(0xFF3E150A)
val WarmOrangeContainerLight = Color(0xFFFFECE6)

// Detail Crimson Backdrop Accents
val DetailCrimsonTop = Color(0xFF7D0D12)
val DetailCrimsonMid = Color(0xFF2B0608)
val DetailObsidian = Color(0xFF070709)

// Expressive Accent Presets (Vibrant Warm Orange is the default Koma signature)
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
        secondary = Color(0xFFFF3B30),
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
