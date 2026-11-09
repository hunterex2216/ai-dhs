package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.MultimodalLiveSessionState
import com.example.service.TouchEffectBus

/**
 * Luxury glassmorphic Live Control Strip with glowing status LED, tactile circular action pills,
 * and gradient LIVE/STOP primary button.
 */
@Composable
fun LiveCompactBar(
    liveState: MultimodalLiveSessionState,
    onToggleLive: () -> Unit,
    onToggleMic: () -> Unit,
    onToggleScreen: () -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val live = liveState.isLiveActive
    val green = Color(0xFF00FF9C)
    val cyan = Color(0xFF00F0FF)
    val amber = Color(0xFFFFB300)
    val red = Color(0xFFFF4D6A)
    val grey = Color(0xFF6B8F82)
    val shape = RoundedCornerShape(16.dp)

    val connected = live && liveState.isWebSocketConnected
    val ctx = LocalContext.current
    val touchFx by TouchEffectBus.enabled.collectAsState()
    val dotColor = when {
        !live -> cyan
        connected -> green
        else -> amber
    }
    val statusText = when {
        !live -> "GEMINI LIVE • কথা বলতে চাপুন"
        !connected -> liveState.statusMessageBn.take(70).ifBlank { "সংযোগ হচ্ছে…" }
        liveState.isAgentSpeakingLive -> "বলছে… (মাঝে বললেই থেমে যাবে)"
        else -> "শুনছি… (কাজের কথা বলুন)"
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    colors = if (live) {
                        listOf(Color(0xFF0B291F), Color(0xFF061712), Color(0xFF1A0912))
                    } else {
                        listOf(Color(0xFF08221A), Color(0xFF05130E), Color(0xFF071D21))
                    }
                )
            )
            .border(
                width = 1.3.dp,
                brush = Brush.horizontalGradient(
                    colors = if (live) listOf(green, cyan, red) else listOf(green.copy(alpha = 0.7f), cyan.copy(alpha = 0.6f))
                ),
                shape = shape
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(dotColor.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
        }

        Text(
            text = statusText,
            color = Color(0xFFE0FFF4),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        if (live) {
            // Mic Pill
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (liveState.isMicMuted) red.copy(alpha = 0.22f) else green.copy(alpha = 0.18f))
                    .border(1.dp, if (liveState.isMicMuted) red else green, CircleShape)
                    .clickable { onToggleMic() }
            ) {
                Icon(
                    imageVector = if (liveState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "mic",
                    tint = if (liveState.isMicMuted) red else green,
                    modifier = Modifier.size(16.dp)
                )
            }

            // Main Screen Share Pill
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (liveState.isScreenStreamEnabled) green.copy(alpha = 0.22f) else Color(0xFF0D241E))
                    .border(1.dp, if (liveState.isScreenStreamEnabled) green else grey, CircleShape)
                    .clickable { onToggleScreen() }
            ) {
                Icon(
                    imageVector = if (liveState.isScreenStreamEnabled) Icons.Default.ScreenShare else Icons.Default.StopScreenShare,
                    contentDescription = "screen",
                    tint = if (liveState.isScreenStreamEnabled) green else grey,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Touch FX Toggle Pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (touchFx) cyan.copy(alpha = 0.18f) else Color(0xFF0A1D18))
                .border(1.dp, if (touchFx) cyan else grey.copy(alpha = 0.5f), CircleShape)
                .clickable { TouchEffectBus.set(ctx, !touchFx) }
        ) {
            Icon(
                imageVector = Icons.Default.TouchApp,
                contentDescription = "touch effect",
                tint = if (touchFx) cyan else grey,
                modifier = Modifier.size(16.dp)
            )
        }

        // API Hub Settings Pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Color(0xFF0B221D))
                .border(1.dp, cyan.copy(alpha = 0.55f), CircleShape)
                .clickable { onOpenHub() }
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "settings",
                tint = cyan,
                modifier = Modifier.size(16.dp)
            )
        }

        // Primary Gradient LIVE / STOP Pill Button
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(11.dp))
                .background(
                    if (live) {
                        Brush.horizontalGradient(listOf(red, Color(0xFFFF758F)))
                    } else {
                        Brush.horizontalGradient(listOf(green, cyan))
                    }
                )
                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(11.dp))
                .clickable { onToggleLive() }
                .padding(horizontal = 13.dp, vertical = 6.dp)
        ) {
            Text(
                text = if (live) "STOP" else "LIVE",
                color = if (live) Color.White else Color(0xFF02140C),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp
            )
        }
    }
}
