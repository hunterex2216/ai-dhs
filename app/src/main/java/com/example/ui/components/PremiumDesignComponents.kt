package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// =========================================================================================
// OFFICIAL DARK SLATE & SKY-BLUE ENTERPRISE THEME TOKENS
// Background: #0B0F19 | Surface/Card: #1E293B | Inner Surface: #0F172A | Border: #334155
// Primary Accent: #38BDF8 (Sky Blue) | Active/Success: #10B981 (Emerald) | Danger: #EF4444
// =========================================================================================
val OfficialSlateBg = Color(0xFF0B0F19)
val OfficialSlateCard = Color(0xFF1E293B)
val OfficialSlateInner = Color(0xFF0F172A)
val OfficialSlateBorder = Color(0xFF334155)
val OfficialSkyBlue = Color(0xFF38BDF8)
val OfficialRoyalBlue = Color(0xFF2563EB)
val OfficialEmerald = Color(0xFF10B981)
val OfficialTextPrimary = Color(0xFFF8FAFC)
val OfficialTextSecondary = Color(0xFF94A3B8)
val OfficialDangerRed = Color(0xFFEF4444)

// Exported semantic aliases mapped to the Official Dark Slate & Sky-Blue palette
// so all existing screens and modals automatically inherit the clean enterprise look.
val RoseNeon = Color(0xFFEF4444)         // Clean Enterprise Coral/Red for close/danger
val SapphireBlue = Color(0xFF38BDF8)     // Official Sky Blue
val TitaniumCyan = Color(0xFF38BDF8)     // Official Sky Blue
val RoyalViolet = Color(0xFF6366F1)      // Clean Indigo Accent
val MintTurquoise = Color(0xFF10B981)    // Clean Emerald Active Status
val ElectricMagenta = Color(0xFF38BDF8)  // Mapped to Official Sky Blue (no clashing neon magenta)
val CoralSunset = Color(0xFF0EA5E9)      // Mapped to Deep Sky Blue

val LuxuryGold = OfficialSkyBlue
val ChampagneHighlight = Color(0xFFBAE6FD)

val ExecutiveSlateDark = OfficialSlateBg
val ExecutiveSlateMid = OfficialSlateInner
val ExecutiveSlateLight = OfficialSlateCard
val ExecutiveBorder = OfficialSlateBorder

val DeepObsidian = OfficialSlateBg
val GlassEmeraldDark = OfficialSlateInner
val GlassEmeraldMid = OfficialSlateCard
val GlassCyanDark = OfficialSlateInner

/**
 * Official Dark Slate & Sky-Blue Enterprise Modal Shell:
 * - Deep Matte Slate `#0B0F19` background
 * - `#1E293B` elevated header & cards
 * - Subtle `1.dp` `#334155` borders and `#38BDF8` active tab indicator
 */
@Composable
fun PremiumModalShell(
    badgeText: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    secondaryAccent: Color = OfficialSkyBlue,
    onClose: () -> Unit,
    tabs: List<Pair<String, String>> = emptyList(),
    selectedTab: String = "",
    onSelectTab: (String) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val outerShape = RoundedCornerShape(20.dp)
    val headerShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 12.dp, bottomEnd = 12.dp)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF5070A12))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = outerShape,
            color = OfficialSlateBg,
            border = BorderStroke(1.dp, OfficialSlateBorder),
            modifier = Modifier
                .fillMaxSize()
                .testTag("jarvis_active_modal")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Official Enterprise Slate Header Bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(headerShape)
                        .background(OfficialSlateCard)
                        .border(1.dp, OfficialSlateBorder, headerShape)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(OfficialSkyBlue)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    if (badgeText.isNotBlank()) {
                                        Text(
                                            text = badgeText,
                                            color = OfficialSkyBlue,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = 0.6.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = title,
                                        color = OfficialTextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            text = subtitle,
                                            color = OfficialTextSecondary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Clean Enterprise Close Button
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(OfficialDangerRed.copy(alpha = 0.16f))
                                    .border(1.dp, OfficialDangerRed.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                                    .clickable { onClose() }
                                    .testTag("modal_close_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "বন্ধ করুন",
                                    tint = Color(0xFFFCA5A5),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Official Dark Slate & Sky-Blue Navigation Tabs
                        if (tabs.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                tabs.forEach { (tabId, tabLabel) ->
                                    val isSelected = selectedTab == tabId
                                    val tabShape = RoundedCornerShape(10.dp)
                                    Box(
                                        modifier = Modifier
                                            .clip(tabShape)
                                            .background(
                                                if (isSelected) OfficialSkyBlue else OfficialSlateInner
                                            )
                                            .border(
                                                width = 1.dp,
                                                color = if (isSelected) OfficialSkyBlue else OfficialSlateBorder,
                                                shape = tabShape
                                            )
                                            .clickable { onSelectTab(tabId) }
                                            .padding(horizontal = 12.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = tabLabel,
                                            color = if (isSelected) OfficialSlateBg else OfficialTextPrimary,
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                                            fontSize = 11.5.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Main Modal Body Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Official Dark Slate Section Card (`#1E293B` surface, `1.dp` `#334155` border, `#38BDF8` accent).
 */
@Composable
fun PremiumSectionCard(
    title: String,
    subtitle: String = "",
    icon: ImageVector? = null,
    accentColor: Color = OfficialSkyBlue,
    badgeText: String = "",
    modifier: Modifier = Modifier,
    headerTrailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        shape = cardShape,
        color = OfficialSlateCard,
        border = BorderStroke(1.dp, OfficialSlateBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (icon != null) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(OfficialSkyBlue.copy(alpha = 0.14f))
                                .border(1.dp, OfficialSkyBlue.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = OfficialSkyBlue,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = OfficialTextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (subtitle.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = subtitle,
                                color = OfficialTextSecondary,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (headerTrailing != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    headerTrailing()
                } else if (badgeText.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = OfficialSlateInner,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.55f))
                    ) {
                        Text(
                            text = badgeText,
                            color = OfficialSkyBlue,
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * Official Dark Slate & Sky-Blue Action Button
 */
@Composable
fun PremiumActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primaryColor: Color = OfficialSkyBlue,
    secondaryColor: Color = OfficialRoyalBlue,
    isOutlined: Boolean = false,
    testTag: String = ""
) {
    val shape = RoundedCornerShape(10.dp)
    val baseMod = if (testTag.isNotBlank()) modifier.testTag(testTag) else modifier

    if (isOutlined) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = baseMod
                .height(40.dp)
                .clip(shape)
                .background(OfficialSlateInner)
                .border(1.dp, primaryColor.copy(alpha = 0.65f), shape)
                .clickable { onClick() }
                .padding(horizontal = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = primaryColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    color = OfficialTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    } else {
        val isBrightSky = primaryColor == OfficialSkyBlue || primaryColor == TitaniumCyan || primaryColor == SapphireBlue
        val contentColor = if (isBrightSky) OfficialSlateBg else Color.White
        Box(
            contentAlignment = Alignment.Center,
            modifier = baseMod
                .height(40.dp)
                .clip(shape)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            primaryColor,
                            primaryColor.copy(alpha = 0.88f)
                        )
                    )
                )
                .border(1.dp, primaryColor.copy(alpha = 0.8f), shape)
                .clickable { onClick() }
                .padding(horizontal = 14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = text,
                    color = contentColor,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Official Dark Slate Hardware Control Center Tile:
 * - Inactive: `#0F172A` surface with `1.dp` `#334155` border
 * - Active: `#1E293B` surface with `1.2.dp` `#38BDF8` (or `#10B981`) border and glowing status dot
 */
@Composable
fun PremiumControlCenterTile(
    title: String,
    statusLabel: String,
    icon: ImageVector,
    isActive: Boolean,
    activeColor: Color = OfficialSkyBlue,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    val effectiveAccent = if (isActive) OfficialSkyBlue else OfficialSlateBorder
    val borderColor by animateColorAsState(
        targetValue = if (isActive) OfficialSkyBlue else OfficialSlateBorder,
        label = "tile_border"
    )

    val tileShape = RoundedCornerShape(14.dp)
    val taggedMod = if (testTag.isNotBlank()) modifier.testTag(testTag) else modifier

    Box(
        modifier = taggedMod
            .clip(tileShape)
            .background(
                if (isActive) OfficialSkyBlue.copy(alpha = 0.14f) else OfficialSlateInner
            )
            .border(
                width = if (isActive) 1.3.dp else 1.dp,
                color = borderColor,
                shape = tileShape
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isActive) OfficialSkyBlue else OfficialSlateCard
                        )
                        .border(
                            1.dp,
                            if (isActive) OfficialSkyBlue else OfficialSlateBorder,
                            RoundedCornerShape(10.dp)
                        )
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = title,
                        tint = if (isActive) OfficialSlateBg else OfficialSkyBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = OfficialTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLabel,
                        color = if (isActive) OfficialSkyBlue else OfficialTextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }

            // Status LED
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (isActive) OfficialEmerald else OfficialSlateBorder)
            )
        }
    }
}

/**
 * Official Dark Slate 6-Channel Studio Volume Mixer Row
 */
@Composable
fun PremiumStudioVolumeChannel(
    label: String,
    percent: Int,
    accentColor: Color = OfficialSkyBlue,
    onPercentChange: (Int) -> Unit,
    sliderTestTag: String
) {
    val safePercent = percent.coerceIn(0, 100)
    Surface(
        color = OfficialSlateInner,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, OfficialSlateBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    color = OfficialTextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // -15% Step Button
                    Surface(
                        color = OfficialSlateCard,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OfficialSlateBorder),
                        modifier = Modifier.clickable {
                            onPercentChange((safePercent - 15).coerceAtLeast(0))
                        }
                    ) {
                        Text(
                            text = "−15%",
                            color = OfficialTextPrimary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Percentage Badge
                    Surface(
                        color = OfficialSkyBlue.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "$safePercent%",
                            color = OfficialSkyBlue,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    // +15% Step Button
                    Surface(
                        color = OfficialSlateCard,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable {
                            onPercentChange((safePercent + 15).coerceAtMost(100))
                        }
                    ) {
                        Text(
                            text = "+15%",
                            color = OfficialSkyBlue,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Clean Segmented LED Meter (Sky Blue -> Emerald)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
            ) {
                val segments = 24
                val gap = 3.dp.toPx()
                val segWidth = (size.width - gap * (segments - 1)) / segments
                val activeSegments = ((safePercent / 100f) * segments).roundToInt()
                for (i in 0 until segments) {
                    val segColor = when {
                        i >= activeSegments -> Color(0xFF1E293B)
                        i > segments * 0.80f -> OfficialEmerald
                        else -> OfficialSkyBlue
                    }
                    drawRoundRect(
                        color = segColor,
                        topLeft = Offset(i * (segWidth + gap), 0f),
                        size = Size(segWidth, size.height),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }

            Slider(
                value = safePercent.toFloat(),
                onValueChange = { onPercentChange(it.roundToInt()) },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = OfficialSkyBlue,
                    activeTrackColor = OfficialSkyBlue,
                    inactiveTrackColor = OfficialSlateCard
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .testTag(sliderTestTag)
            )
        }
    }
}

/**
 * Official Dark Slate Voice Studio Card
 */
@Composable
fun PremiumVoiceStudioTile(
    titleBn: String,
    subtitleBn: String,
    voiceCode: String,
    isFemale: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    val cardShape = RoundedCornerShape(14.dp)
    val taggedMod = if (testTag.isNotBlank()) modifier.testTag(testTag) else modifier

    Surface(
        shape = cardShape,
        color = if (isSelected) OfficialSkyBlue.copy(alpha = 0.14f) else OfficialSlateInner,
        border = BorderStroke(
            width = if (isSelected) 1.4.dp else 1.dp,
            color = if (isSelected) OfficialSkyBlue else OfficialSlateBorder
        ),
        modifier = taggedMod
            .fillMaxWidth()
            .clip(cardShape)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = OfficialSlateCard,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, if (isSelected) OfficialSkyBlue else OfficialSlateBorder)
                ) {
                    Text(
                        text = if (isFemale) "👩 FEMALE • $voiceCode" else "🎙️ MALE • $voiceCode",
                        color = if (isSelected) OfficialSkyBlue else OfficialTextSecondary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp)
                    )
                }

                if (isSelected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = OfficialEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = OfficialSkyBlue,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = titleBn,
                color = OfficialTextPrimary,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtitleBn,
                color = OfficialTextSecondary,
                fontSize = 10.5.sp,
                lineHeight = 13.5.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Official Dark Slate Permission Status Row:
 * Emerald Green (`#10B981`) when granted, Sky Blue (`#38BDF8`) action button when needed.
 */
@Composable
fun PremiumPermissionStatusRow(
    stepNumber: String,
    title: String,
    description: String,
    isGranted: Boolean,
    buttonTextGranted: String = "সক্রিয় ✓",
    buttonTextDenied: String = "অন করুন",
    accentColor: Color = OfficialSkyBlue,
    onClick: () -> Unit,
    buttonTestTag: String = ""
) {
    val rowShape = RoundedCornerShape(14.dp)
    val statusColor = if (isGranted) OfficialEmerald else OfficialSkyBlue

    Surface(
        color = OfficialSlateInner,
        shape = rowShape,
        border = BorderStroke(
            1.dp,
            if (isGranted) OfficialEmerald.copy(alpha = 0.55f) else OfficialSlateBorder
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.16f))
                        .border(1.dp, statusColor, CircleShape)
                ) {
                    Text(
                        text = stepNumber,
                        color = statusColor,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = OfficialTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.5.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        color = OfficialTextSecondary,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            PremiumActionButton(
                text = if (isGranted) buttonTextGranted else buttonTextDenied,
                onClick = onClick,
                primaryColor = statusColor,
                secondaryColor = OfficialSkyBlue,
                isOutlined = isGranted,
                testTag = buttonTestTag,
                modifier = Modifier.width(106.dp)
            )
        }
    }
}
