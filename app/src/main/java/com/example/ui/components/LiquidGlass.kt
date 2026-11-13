package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun rememberIsDarkSurface(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

@Composable
fun liquidGlassBackgroundBrush(): Brush {
    val isDark = rememberIsDarkSurface()
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    return if (isDark) {
        Brush.verticalGradient(
            colors = listOf(
                base.copy(alpha = 0.86f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.88f),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f)
            )
        )
    }
}

@Composable
fun liquidGlassBorderStroke(width: Dp = 0.75.dp): BorderStroke {
    val isDark = rememberIsDarkSurface()
    val color = if (isDark) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.Black.copy(alpha = 0.08f)
    }
    return BorderStroke(width, color)
}

/**
 * Subtle translucent Apple-inspired Liquid Glass container for navigation chrome,
 * toolbars, floating controls, search bars, and overlay pills (never used on content cards).
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    shadowElevation: Dp = 10.dp,
    borderWidth: Dp = 0.75.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = rememberIsDarkSurface()
    val borderColor = if (isDark) Color.White.copy(alpha = 0.15f) else Color.Black.copy(alpha = 0.08f)
    val topSpecular = if (isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.55f)
    val baseBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.84f)
    }

    Surface(
        modifier = modifier.shadow(
            elevation = shadowElevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = 0.28f),
            spotColor = Color.Black.copy(alpha = 0.28f)
        ),
        shape = shape,
        color = Color.Transparent,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            baseBg.copy(alpha = (baseBg.alpha + 0.05f).coerceAtMost(0.95f)),
                            baseBg
                        )
                    )
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(topSpecular, Color.Transparent),
                        endY = 40f
                    )
                ),
            content = content
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable (() -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isDark = rememberIsDarkSurface()
    val barBg = if (isDark) {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    } else {
        Color.White.copy(alpha = 0.86f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg)
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent
            )
        )
        HorizontalDivider(
            thickness = 0.5.dp,
            color = if (isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
        )
    }
}

data class GlassNavTab(
    val id: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val badgeCount: Int = 0
)

@Composable
fun LiquidGlassFloatingNavBar(
    tabs: List<GlassNavTab>,
    selectedId: String,
    onSelect: (GlassNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        LiquidGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            shadowElevation = 14.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = tab.id == selectedId
                    val pillColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "navPillBg"
                    )
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "navPillFg"
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(pillColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(bounded = true),
                                onClick = { onSelect(tab) }
                            )
                            .padding(vertical = 6.dp, horizontal = 2.dp)
                            .testTag(tab.testTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label,
                                    tint = contentColor,
                                    modifier = Modifier.size(21.dp)
                                )
                                if (tab.badgeCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .align(Alignment.TopEnd)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary)
                                    )
                                }
                            }
                            Text(
                                text = tab.label,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = contentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LiquidGlassFloatingPill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    LiquidGlassSurface(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier.clip(CircleShape).clickable(onClick = onClick)
                } else Modifier
            ),
        shape = CircleShape,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content
        )
    }
}
