package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Core Obsidian & Ink Dark Palette (AMOLED & Elevated Slate)
val AmoledBlack = Color(0xFF000000)
val ObsidianBg = Color(0xFF0A0C12)
val ObsidianSurface = Color(0xFF12151F)
val ObsidianSurfaceHigh = Color(0xFF1A1E2B)
val ObsidianSurfaceHighest = Color(0xFF232838)
val ObsidianOutline = Color(0xFF2E3548)
val InkWhite = Color(0xFFF3F4F8)
val InkMuted = Color(0xFFA0A7BC)

// Daylight Clean Palette
val DaylightBg = Color(0xFFF7F7FA)
val DaylightSurface = Color(0xFFFFFFFF)
val DaylightSurfaceHigh = Color(0xFFEEEFF5)
val DaylightOutline = Color(0xFFD5D8E2)
val DaylightInk = Color(0xFF12141D)
val DaylightMuted = Color(0xFF5D6478)

// Expressive Accent Presets
enum class AccentPreset(
    val id: String,
    val label: String,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val secondary: Color,
    val tertiary: Color
) {
    CRIMSON_INK(
        id = "crimson",
        label = "Crimson Ink",
        primary = Color(0xFFE11D48),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF4C0519),
        secondary = Color(0xFFF59E0B),
        tertiary = Color(0xFF38BDF8)
    ),
    ELECTRIC_COBALT(
        id = "cobalt",
        label = "Electric Cobalt",
        primary = Color(0xFF3B82F6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF172554),
        secondary = Color(0xFF22D3EE),
        tertiary = Color(0xFFF43F5E)
    ),
    EMERALD_JADE(
        id = "emerald",
        label = "Emerald Jade",
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF022C22),
        primaryContainer = Color(0xFF064E3B),
        secondary = Color(0xFFFBBF24),
        tertiary = Color(0xFF60A5FA)
    ),
    AMBER_SUNSET(
        id = "amber",
        label = "Amber Gold",
        primary = Color(0xFFF59E0B),
        onPrimary = Color(0xFF1F1300),
        primaryContainer = Color(0xFF451A03),
        secondary = Color(0xFFFB7185),
        tertiary = Color(0xFF34D399)
    ),
    VIOLET_DUSK(
        id = "violet",
        label = "Violet Dusk",
        primary = Color(0xFF8B5CF6),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFF2E1065),
        secondary = Color(0xFFF472B6),
        tertiary = Color(0xFF38BDF8)
    );

    companion object {
        fun fromId(id: String): AccentPreset = entries.firstOrNull { it.id == id } ?: CRIMSON_INK
    }
}

val StatusOngoing = Color(0xFF10B981)
val StatusCompleted = Color(0xFF3B82F6)
val StatusHiatus = Color(0xFFF59E0B)
val StatusError = Color(0xFFEF4444)
