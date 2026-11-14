package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R

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
                base.copy(alpha = 0.88f),
                MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f),
                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f)
            )
        )
    }
}

@Composable
fun liquidGlassBorderStroke(width: Dp = 0.75.dp): BorderStroke {
    val isDark = rememberIsDarkSurface()
    val color = if (isDark) {
        Color.White.copy(alpha = 0.14f)
    } else {
        Color.Black.copy(alpha = 0.07f)
    }
    return BorderStroke(width, color)
}

/**
 * Refined translucent glass container for floating navigation chrome, headers,
 * search capsules, and overlay pills.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    shadowElevation: Dp = 10.dp,
    borderWidth: Dp = 0.75.dp,
    tintOverlay: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = rememberIsDarkSurface()
    val borderColor = if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.08f)
    val topSpecular = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.65f)
    val baseBg = tintOverlay ?: if (isDark) {
        Color(0xFF1A1C24).copy(alpha = 0.86f)
    } else {
        Color(0xFFFFFFFF).copy(alpha = 0.88f)
    }

    Surface(
        modifier = modifier.shadow(
            elevation = shadowElevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (isDark) 0.36f else 0.14f),
            spotColor = Color.Black.copy(alpha = if (isDark) 0.36f else 0.14f)
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
                            baseBg.copy(alpha = (baseBg.alpha + 0.06f).coerceAtMost(0.96f)),
                            baseBg
                        )
                    )
                )
                .background(
                    Brush.verticalGradient(
                        colors = listOf(topSpecular, Color.Transparent),
                        endY = 48f
                    )
                ),
            content = content
        )
    }
}

/**
 * Layered rounded card container with subtle hairline border for consistent depth
 * across Dark and Light themes.
 */
@Composable
fun KomaElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val clickableMod = if (onClick != null) {
        Modifier.clip(shape).clickable(onClick = onClick)
    } else {
        Modifier
    }
    Surface(
        modifier = modifier.then(clickableMod),
        shape = shape,
        color = containerColor,
        border = BorderStroke(0.75.dp, borderColor),
        tonalElevation = 1.dp
    ) {
        Column(content = content)
    }
}

/**
 * Circular frosted icon button for headers and directional controls, enforcing >= 48.dp touch target.
 */
@Composable
fun KomaCircularGlassButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    containerSize: Dp = 40.dp
) {
    val isDark = rememberIsDarkSurface()
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(containerSize)
            .clip(CircleShape)
            .background(
                if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiquidGlassTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    showBrandMark: Boolean = false,
    navigationIcon: @Composable (() -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isDark = rememberIsDarkSurface()
    val barBg = if (isDark) {
        MaterialTheme.colorScheme.background.copy(alpha = 0.90f)
    } else {
        MaterialTheme.colorScheme.background.copy(alpha = 0.92f)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(barBg)
    ) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (showBrandMark) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_koma_logo),
                                contentDescription = "Koma logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
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
            color = if (isDark) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
        )
    }
}

data class GlassNavTab(
    val id: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val badgeCount: Int = 0,
    val isCenterHighlighted: Boolean = false
)

@Composable
fun LiquidGlassFloatingNavBar(
    tabs: List<GlassNavTab>,
    selectedId: String,
    onSelect: (GlassNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = rememberIsDarkSurface()
    val barBg = if (isDark) Color(0xFF101116).copy(alpha = 0.96f) else Color(0xFFFFFFFF).copy(alpha = 0.98f)
    val dividerColor = if (isDark) Color.White.copy(alpha = 0.08f) else Color(0xFFE5E6EC)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 14.dp,
                ambientColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.08f),
                spotColor = Color.Black.copy(alpha = if (isDark) 0.45f else 0.08f)
            ),
        color = barBg
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(thickness = 0.75.dp, color = dividerColor)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEach { tab ->
                    val selected = tab.id == selectedId
                    if (tab.isCenterHighlighted) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp)
                                .heightIn(min = 50.dp)
                                .shadow(
                                    elevation = if (selected) 10.dp else 6.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    ambientColor = Color(0xFFFF5228).copy(alpha = 0.55f),
                                    spotColor = Color(0xFFFF5228).copy(alpha = 0.55f)
                                )
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color(0xFFFF4224),
                                            Color(0xFFFF7A3B)
                                        )
                                    )
                                )
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(bounded = true, color = Color.White),
                                    onClick = { onSelect(tab) }
                                )
                                .padding(vertical = 6.dp, horizontal = 6.dp)
                                .testTag(tab.testTag),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = tab.selectedIcon,
                                    contentDescription = tab.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(21.dp)
                                )
                                Text(
                                    text = tab.label,
                                    fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White,
                                    maxLines = 1,
                                    letterSpacing = 0.6.sp
                                )
                            }
                        }
                    } else {
                        val contentColor by animateColorAsState(
                            targetValue = if (selected) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            label = "navPillFg"
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 50.dp)
                                .clip(RoundedCornerShape(14.dp))
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
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Box {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label,
                                        tint = contentColor,
                                        modifier = Modifier.size(22.dp)
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
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
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
