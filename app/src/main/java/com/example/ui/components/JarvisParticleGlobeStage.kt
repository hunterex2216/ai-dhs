package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.JarvisNodeModal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Exact Original Signature Globe & Circuit Colors
val MatrixGreen = Color(0xFF00FF66)
val CyberCyan = Color(0xFF00F0FF)
val CircuitOrange = Color(0xFFFF9100)
val SoulSilver = Color(0xFFE8F5E9)
val ParticleGold = Color(0xFFFFEA00)
val StopCrimson = Color(0xFFFF2E54)

private data class FibonacciSpherePoint(
    val baseX: Float,
    val baseY: Float,
    val baseZ: Float,
    val theta: Float,
    val phiIndexRatio: Float
)

@Composable
fun JarvisCircuitAndGlobeStage(
    isSpeaking: Boolean,
    isListening: Boolean,
    isThinking: Boolean,
    ttsEnabled: Boolean,
    onOpenNodeModal: (JarvisNodeModal) -> Unit,
    onGlobeTap: () -> Unit,
    onStopClick: () -> Unit,
    onToggleTts: () -> Unit,
    modifier: Modifier = Modifier,
    rmsLevel: Float = 0f,
    onOpenApiKeyClick: (() -> Unit)? = null
) {
    val isActiveVoice = isSpeaking || isListening || isThinking || rmsLevel > 0.08f
    val infiniteTransition = rememberInfiniteTransition(label = "archer_globe_transition")
    val context = androidx.compose.ui.platform.LocalContext.current

    // High-density 3D Fibonacci Sphere points
    val fibonacciPoints = remember(context) {
        val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val isLowRam = am?.isLowRamDevice == true || Runtime.getRuntime().maxMemory() <= 192L * 1024L * 1024L
        val count = if (isLowRam) 200 else 300
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        List(count) { i ->
            val y = 1f - (i.toFloat() / (count - 1).toFloat()) * 2f
            val radiusAtY = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            val x = cos(theta) * radiusAtY
            val z = sin(theta) * radiusAtY
            FibonacciSpherePoint(
                baseX = x,
                baseY = y,
                baseZ = z,
                theta = theta,
                phiIndexRatio = i.toFloat() / count.toFloat()
            )
        }
    }

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 5200 else 10800,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "globe_rotation"
    )

    val harmonicWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 980 else 2500,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "harmonic_wave_phase"
    )

    val secondaryOrbitAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "secondary_orbit"
    )

    val tertiaryRingAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "tertiary_ring_orbit"
    )

    val wavePulse by infiniteTransition.animateFloat(
        initialValue = 0.91f,
        targetValue = if (isActiveVoice) 1.18f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 340 else 1550,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "globe_pulse"
    )

    val sonicWaveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 1050 else 2400,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "sonic_wave_progress"
    )

    val circuitPulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "circuit_pulse"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(264.dp)
        ) {
            // Original Glowing Circuit Traces from 4 Left Pill Buttons into the 3D Holographic Globe
            Canvas(modifier = Modifier.fillMaxSize()) {
                val pillRightEdgeX = 128.dp.toPx()
                val globeLeftEntryX = size.width * 0.49f
                val midTurnXOuter = pillRightEdgeX + (globeLeftEntryX - pillRightEdgeX) * 0.42f
                val midTurnXInner = pillRightEdgeX + (globeLeftEntryX - pillRightEdgeX) * 0.28f
                val centerGlobeY = size.height * 0.50f

                val nodeYs = listOf(
                    size.height * 0.13f,
                    size.height * 0.38f,
                    size.height * 0.62f,
                    size.height * 0.87f
                )
                // Exact original colors for MEMORY, CHAT, SOUL, SETTINGS
                val traceColors = listOf(CyberCyan, CircuitOrange, SoulSilver, MatrixGreen)
                val entryYOffsets = listOf(-12.dp.toPx(), -4.dp.toPx(), 4.dp.toPx(), 12.dp.toPx())

                for (i in 0..3) {
                    val yStart = nodeYs[i]
                    val yEnd = centerGlobeY + entryYOffsets[i]
                    val bendX1 = if (i == 0 || i == 3) midTurnXOuter else midTurnXInner
                    val bendX2 = bendX1 + 14.dp.toPx()
                    val color = traceColors[i]

                    val path = Path().apply {
                        moveTo(pillRightEdgeX, yStart)
                        lineTo(bendX1, yStart)
                        lineTo(bendX2, yEnd)
                        lineTo(globeLeftEntryX, yEnd)
                    }

                    // Wide outer neon bloom
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.25f),
                        style = Stroke(width = 8.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Medium neon halo
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.58f),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    // Crisp bright core line
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.92f),
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Glowing connector node dot right on the pill's right border
                    drawCircle(
                        color = color.copy(alpha = 0.48f),
                        radius = 6.2.dp.toPx(),
                        center = Offset(pillRightEdgeX, yStart)
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.dp.toPx(),
                        center = Offset(pillRightEdgeX, yStart)
                    )

                    // Animated energy packet flowing along the circuit trace into the globe
                    val t = (circuitPulse + i * 0.22f) % 1f
                    val packetPos = when {
                        t < 0.35f -> {
                            val localT = t / 0.35f
                            Offset(pillRightEdgeX + (bendX1 - pillRightEdgeX) * localT, yStart)
                        }
                        t < 0.70f -> {
                            val localT = (t - 0.35f) / 0.35f
                            Offset(
                                bendX1 + (bendX2 - bendX1) * localT,
                                yStart + (yEnd - yStart) * localT
                            )
                        }
                        else -> {
                            val localT = (t - 0.70f) / 0.30f
                            Offset(bendX2 + (globeLeftEntryX - bendX2) * localT, yEnd)
                        }
                    }
                    drawCircle(
                        color = color.copy(alpha = 0.45f),
                        radius = 7.5.dp.toPx(),
                        center = packetPos
                    )
                    drawCircle(
                        color = color,
                        radius = 4.2.dp.toPx(),
                        center = packetPos
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: 4 Pill Node Buttons (MEMORY, CHAT, SOUL, SETTINGS) with exact original colors
                Column(
                    modifier = Modifier
                        .width(128.dp)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    CircuitNodePillButton(
                        label = "MEMORY",
                        icon = Icons.Default.Memory,
                        accentColor = CyberCyan,
                        onClick = { onOpenNodeModal(JarvisNodeModal.MEMORY_AND_IDS) },
                        testTag = "node_btn_memory"
                    )
                    CircuitNodePillButton(
                        label = "CHAT",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        accentColor = CircuitOrange,
                        onClick = { onOpenNodeModal(JarvisNodeModal.CHAT_AND_MESSAGES) },
                        testTag = "node_btn_chat"
                    )
                    CircuitNodePillButton(
                        label = "SOUL",
                        icon = Icons.Default.Person,
                        accentColor = SoulSilver,
                        onClick = { onOpenNodeModal(JarvisNodeModal.SOUL_AND_WAKE) },
                        testTag = "node_btn_soul"
                    )
                    CircuitNodePillButton(
                        label = "SETTINGS",
                        icon = Icons.Default.Settings,
                        accentColor = MatrixGreen,
                        onClick = { onOpenNodeModal(JarvisNodeModal.SETTINGS_AND_PERMS) },
                        testTag = "node_btn_settings"
                    )
                }

                Spacer(modifier = Modifier.width(20.dp))

                // Right Stage: Original Emerald-Cyan-Gold Color Palette + Upgraded 3D Sculpted Holographic Globe Style
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onGlobeTap() }
                        .testTag("jarvis_particle_globe")
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val center = Offset(size.width * 0.52f, size.height * 0.49f)
                        val baseRadius = size.minDimension * 0.44f
                        val rmsBoost = 1f + rmsLevel * 0.28f
                        val sphereRadius = baseRadius * (wavePulse * rmsBoost).coerceIn(0.90f, 1.22f)

                        // 1. Original Deep Multi-Layered Ambient Holographic Outer Emerald-Cyan Aura
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    MatrixGreen.copy(alpha = if (isActiveVoice) 0.52f else 0.38f),
                                    CyberCyan.copy(alpha = if (isActiveVoice) 0.36f else 0.25f),
                                    Color(0xFF063820).copy(alpha = 0.22f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = baseRadius * (if (isActiveVoice) 1.56f else 1.42f)
                            ),
                            radius = baseRadius * (if (isActiveVoice) 1.56f else 1.42f),
                            center = center
                        )

                        // 1B. Amplified Speaking/Listening Sonic Energy Wave Rings & 360° Sinusoidal Voice Waveform
                        if (isActiveVoice) {
                            val waveColors = listOf(MatrixGreen, CyberCyan, ParticleGold)
                            for (wIdx in 0..2) {
                                val phase = (sonicWaveProgress + wIdx * 0.33f) % 1f
                                val fade = (1f - phase).coerceIn(0f, 1f)
                                val ringRadius = baseRadius * (0.98f + phase * (0.46f + rmsLevel * 0.16f))
                                drawCircle(
                                    color = waveColors[wIdx].copy(alpha = (0.68f * fade).coerceIn(0f, 0.85f)),
                                    radius = ringRadius,
                                    center = center,
                                    style = Stroke(width = (1.6f + fade * 2.2f).dp.toPx())
                                )
                            }

                            val wavePath = Path()
                            val waveSteps = 72
                            val dynamicWaveAmp = (7.5f + rmsLevel * 18.5f + (if (isSpeaking) 5.5f else 2.5f)).dp.toPx()
                            for (s in 0..waveSteps) {
                                val angle = (2.0 * PI * s / waveSteps).toFloat()
                                val mod1 = sin(angle * 8f + harmonicWavePhase * 2.2f)
                                val mod2 = cos(angle * 5f - harmonicWavePhase * 1.5f)
                                val rWave = sphereRadius * 1.05f + (mod1 * 0.65f + mod2 * 0.35f) * dynamicWaveAmp
                                val wx = center.x + cos(angle) * rWave
                                val wy = center.y + sin(angle) * rWave
                                if (s == 0) wavePath.moveTo(wx, wy) else wavePath.lineTo(wx, wy)
                            }
                            wavePath.close()
                            drawPath(
                                path = wavePath,
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        MatrixGreen.copy(alpha = 0.88f),
                                        CyberCyan.copy(alpha = 0.92f),
                                        ParticleGold.copy(alpha = 0.85f),
                                        CyberCyan.copy(alpha = 0.92f),
                                        MatrixGreen.copy(alpha = 0.88f)
                                    ),
                                    center = center
                                ),
                                style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }

                        // 2. Upgraded Dual Counter-Rotating 3D Tactical HUD Radar Rings & 48-Tick Bezel (in Original Emerald, Cyan & Gold)
                        drawCircle(
                            color = MatrixGreen.copy(alpha = 0.70f),
                            radius = baseRadius * 1.07f,
                            center = center,
                            style = Stroke(width = 1.7.dp.toPx())
                        )
                        drawCircle(
                            color = CyberCyan.copy(alpha = 0.40f),
                            radius = baseRadius * 1.13f,
                            center = center,
                            style = Stroke(width = 1.1.dp.toPx())
                        )

                        // Outer rotating tactical HUD arcs (Emerald, Cyan, Gold)
                        rotate(degrees = tertiaryRingAngle, pivot = center) {
                            val arcRadius = baseRadius * 1.18f
                            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                            for (a in 0 until 4) {
                                val arcColor = when (a) {
                                    0 -> MatrixGreen.copy(alpha = 0.72f)
                                    1 -> CyberCyan.copy(alpha = 0.72f)
                                    2 -> ParticleGold.copy(alpha = 0.68f)
                                    else -> CyberCyan.copy(alpha = 0.72f)
                                }
                                drawArc(
                                    color = arcColor,
                                    startAngle = a * 90f + 10f,
                                    sweepAngle = 54f,
                                    useCenter = false,
                                    topLeft = arcTopLeft,
                                    size = arcSize,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // Inner counter-rotating segmented energy ring
                        rotate(degrees = secondaryOrbitAngle, pivot = center) {
                            val innerArcRadius = baseRadius * 1.11f
                            val innerTopLeft = Offset(center.x - innerArcRadius, center.y - innerArcRadius)
                            val innerSize = Size(innerArcRadius * 2f, innerArcRadius * 2f)
                            for (b in 0 until 6) {
                                drawArc(
                                    color = if (b % 2 == 0) CyberCyan.copy(alpha = 0.52f) else MatrixGreen.copy(alpha = 0.52f),
                                    startAngle = b * 60f + 8f,
                                    sweepAngle = 30f,
                                    useCenter = false,
                                    topLeft = innerTopLeft,
                                    size = innerSize,
                                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        // 48-Tick Precision Telemetry Compass Bezel in Original Gold, Cyan & Emerald
                        val tickCount = 48
                        val bezelInnerBase = baseRadius * 1.07f
                        for (tIdx in 0 until tickCount) {
                            val tickAngleRad = Math.toRadians((tIdx * (360.0 / tickCount)) + secondaryOrbitAngle * 0.28).toFloat()
                            val isCardinal = tIdx % 12 == 0
                            val isMajor = tIdx % 4 == 0
                            val rInner = when {
                                isCardinal -> baseRadius * 1.04f
                                isMajor -> bezelInnerBase
                                else -> baseRadius * 1.09f
                            }
                            val rOuter = when {
                                isCardinal -> baseRadius * 1.16f
                                isMajor -> baseRadius * 1.14f
                                else -> baseRadius * 1.12f
                            }
                            val tickColor = when {
                                isCardinal -> ParticleGold.copy(alpha = 0.95f)
                                isMajor -> CyberCyan.copy(alpha = 0.80f)
                                else -> MatrixGreen.copy(alpha = 0.48f)
                            }
                            drawLine(
                                color = tickColor,
                                start = Offset(
                                    center.x + cos(tickAngleRad) * rInner,
                                    center.y + sin(tickAngleRad) * rInner
                                ),
                                end = Offset(
                                    center.x + cos(tickAngleRad) * rOuter,
                                    center.y + sin(tickAngleRad) * rOuter
                                ),
                                strokeWidth = if (isCardinal) 2.1.dp.toPx() else if (isMajor) 1.5.dp.toPx() else 0.9.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }

                        // 3. 3D Shaded Inner Glass Sphere Depth + Original Intense Volumetric Emerald-Cyan Plasma Energy Core
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF062B1D).copy(alpha = 0.85f),
                                    Color(0xFF031710).copy(alpha = 0.90f),
                                    Color(0xFF052E24).copy(alpha = 0.80f),
                                    MatrixGreen.copy(alpha = 0.30f)
                                ),
                                center = Offset(center.x - sphereRadius * 0.20f, center.y - sphereRadius * 0.20f),
                                radius = sphereRadius * 1.0f
                            ),
                            radius = sphereRadius * 0.96f,
                            center = center
                        )

                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFE0FFFF).copy(alpha = if (isActiveVoice) 0.98f else 0.90f),
                                    CyberCyan.copy(alpha = if (isActiveVoice) 0.80f else 0.66f),
                                    MatrixGreen.copy(alpha = 0.48f),
                                    Color(0xFF043822).copy(alpha = 0.28f),
                                    Color.Transparent
                                ),
                                center = center,
                                radius = sphereRadius * 0.90f
                            ),
                            radius = sphereRadius * 0.90f,
                            center = center
                        )

                        // 4. Upgraded 3D Geodesic Latitude & Longitude Wireframe Grid (in Original Cyan & MatrixGreen)
                        val cosRotY = cos(rotationAngle)
                        val sinRotY = sin(rotationAngle)
                        val tiltX = 0.24f
                        val cosTiltX = cos(tiltX)
                        val sinTiltX = sin(tiltX)

                        val latitudes = listOf(-0.62f, -0.30f, 0f, 0.30f, 0.62f)
                        latitudes.forEachIndexed { latIdx, latY ->
                            val latRadius = sqrt((1f - latY * latY).coerceAtLeast(0f))
                            val latPath = Path()
                            val segments = 36
                            for (s in 0..segments) {
                                val lon = (2.0 * PI * s / segments).toFloat() + rotationAngle * 0.5f
                                val x = cos(lon) * latRadius
                                val z = sin(lon) * latRadius
                                val y3d = latY * cosTiltX - z * sinTiltX
                                val z3d = latY * sinTiltX + z * cosTiltX
                                val perspective = 2.6f / (2.6f - z3d * 0.55f)
                                val px = center.x + x * sphereRadius * 0.94f * perspective
                                val py = center.y + y3d * sphereRadius * 0.94f * perspective
                                if (s == 0) latPath.moveTo(px, py) else latPath.lineTo(px, py)
                            }
                            drawPath(
                                path = latPath,
                                color = if (latIdx == 2) MatrixGreen.copy(alpha = 0.40f) else CyberCyan.copy(alpha = 0.22f),
                                style = Stroke(width = if (latIdx == 2) 1.3.dp.toPx() else 0.85.dp.toPx())
                            )
                        }

                        for (m in 0 until 4) {
                            val baseLon = (m * PI / 4.0).toFloat() + rotationAngle
                            val merPath = Path()
                            val segments = 36
                            for (s in 0..segments) {
                                val angle = (2.0 * PI * s / segments).toFloat()
                                val rawX = cos(angle) * cos(baseLon)
                                val rawZ = cos(angle) * sin(baseLon)
                                val rawY = sin(angle)
                                val y3d = rawY * cosTiltX - rawZ * sinTiltX
                                val z3d = rawY * sinTiltX + rawZ * cosTiltX
                                val perspective = 2.6f / (2.6f - z3d * 0.55f)
                                val px = center.x + rawX * sphereRadius * 0.94f * perspective
                                val py = center.y + y3d * sphereRadius * 0.94f * perspective
                                if (s == 0) merPath.moveTo(px, py) else merPath.lineTo(px, py)
                            }
                            drawPath(
                                path = merPath,
                                color = if (m % 2 == 0) CyberCyan.copy(alpha = 0.25f) else MatrixGreen.copy(alpha = 0.25f),
                                style = Stroke(width = 0.95.dp.toPx())
                            )
                        }

                        // 5. Upgraded Quadruple 3D Gyroscopic Wireframe & Orbiting Satellites (in Original Cyan, MatrixGreen & ParticleGold)
                        val gyroTilts = listOf(-18f, 32f, -54f, 66f)
                        val gyroColors = listOf(CyberCyan, MatrixGreen, CyberCyan, ParticleGold)
                        for (gIdx in 0..3) {
                            val gAngle = gyroTilts[gIdx] + (if (gIdx % 2 == 0) secondaryOrbitAngle else tertiaryRingAngle) * 0.10f
                            val gColor = gyroColors[gIdx]
                            rotate(degrees = gAngle, pivot = center) {
                                val rx = sphereRadius * 1.08f
                                val ry = sphereRadius * (0.28f + (gIdx % 2) * 0.08f)
                                drawOval(
                                    color = gColor.copy(alpha = 0.42f),
                                    topLeft = Offset(center.x - rx, center.y - ry),
                                    size = Size(rx * 2f, ry * 2f),
                                    style = Stroke(width = 1.3.dp.toPx())
                                )
                                // Glowing 3D Orbital Satellite Node
                                val satAngle = rotationAngle * (1.3f + gIdx * 0.28f) + gIdx * 1.7f
                                val satX = center.x + cos(satAngle) * rx
                                val satY = center.y + sin(satAngle) * ry
                                drawCircle(
                                    color = gColor.copy(alpha = 0.45f),
                                    radius = 6.2.dp.toPx(),
                                    center = Offset(satX, satY)
                                )
                                drawCircle(
                                    color = Color.White,
                                    radius = 2.8.dp.toPx(),
                                    center = Offset(satX, satY)
                                )
                            }
                        }

                        // 6. Triple Counter-Rotating 3D Orbital Particle Streams (Original Cyan, MatrixGreen, ParticleGold)
                        val ringCount = 40
                        val ringTiltRad1 = Math.toRadians(-16.0).toFloat()
                        val ringTiltRad2 = Math.toRadians(68.0).toFloat()
                        val ringTiltRad3 = Math.toRadians(34.0).toFloat()
                        val cosTilt1 = cos(ringTiltRad1)
                        val sinTilt1 = sin(ringTiltRad1)
                        val cosTilt2 = cos(ringTiltRad2)
                        val sinTilt2 = sin(ringTiltRad2)
                        val cosTilt3 = cos(ringTiltRad3)
                        val sinTilt3 = sin(ringTiltRad3)

                        for (k in 0 until ringCount) {
                            val angle1 = (2.0 * PI * k / ringCount).toFloat() + rotationAngle * 1.35f
                            val rx1 = cos(angle1) * (sphereRadius * 1.07f)
                            val rz1 = sin(angle1) * (sphereRadius * 1.07f)
                            val ry1 = rz1 * sinTilt1
                            val depth1 = ((rz1 * cosTilt1 / sphereRadius) + 1f) * 0.5f
                            drawCircle(
                                color = CyberCyan.copy(alpha = (0.28f + depth1 * 0.66f).coerceIn(0.2f, 0.96f)),
                                radius = (if (k % 10 == 0) 2.5f else 1.1f + depth1 * 1.5f).dp.toPx(),
                                center = Offset(center.x + rx1, center.y + ry1)
                            )

                            val angle2 = (2.0 * PI * k / ringCount).toFloat() - rotationAngle * 1.1f
                            val rx2 = cos(angle2) * (sphereRadius * 1.04f)
                            val ry2 = sin(angle2) * (sphereRadius * 1.04f) * cosTilt2
                            val rz2 = sin(angle2) * sinTilt2
                            val depth2 = ((rz2 + 1f) * 0.5f).coerceIn(0.2f, 0.95f)
                            if (k % 2 == 0) {
                                drawCircle(
                                    color = if (k % 4 == 0) ParticleGold.copy(alpha = depth2 * 0.88f)
                                    else MatrixGreen.copy(alpha = depth2 * 0.82f),
                                    radius = (1.05f + depth2 * 1.45f).dp.toPx(),
                                    center = Offset(center.x + rx2 * 0.42f, center.y + ry2)
                                )
                            }

                            if (k % 2 == 0) {
                                val angle3 = (2.0 * PI * k / ringCount).toFloat() + rotationAngle * 1.65f
                                val rx3 = cos(angle3) * (sphereRadius * 1.12f)
                                val rz3 = sin(angle3) * (sphereRadius * 1.12f)
                                val ry3 = rz3 * sinTilt3
                                val depth3 = ((rz3 * cosTilt3 / sphereRadius) + 1f) * 0.5f
                                drawCircle(
                                    color = if (k % 6 == 0) ParticleGold.copy(alpha = (0.35f + depth3 * 0.60f).coerceIn(0.2f, 0.95f))
                                    else MatrixGreen.copy(alpha = (0.25f + depth3 * 0.65f).coerceIn(0.2f, 0.92f)),
                                    radius = (if (k % 8 == 0) 2.3f else 1.0f + depth3 * 1.3f).dp.toPx(),
                                    center = Offset(center.x + rx3 * 0.88f, center.y + ry3)
                                )
                            }
                        }

                        // 7. 3D Fibonacci Sphere with Exact Original Archer AI Color Distribution & Amplified Neural Synapse Wave
                        val waveAmplitude = when {
                            isSpeaking -> 0.175f + rmsLevel * 0.20f
                            isListening -> 0.145f + rmsLevel * 0.18f
                            isThinking -> 0.095f
                            else -> 0.024f
                        }

                        var prevSynapseOffset: Offset? = null
                        var prevSynapseColor: Color = CyberCyan

                        for (idx in fibonacciPoints.indices) {
                            val pt = fibonacciPoints[idx]

                            // Rotate around Y axis
                            val xRotY = pt.baseX * cosRotY + pt.baseZ * sinRotY
                            val zRotY = -pt.baseX * sinRotY + pt.baseZ * cosRotY

                            // Tilt around X axis for realistic 3D globe perspective
                            val y3d = pt.baseY * cosTiltX - zRotY * sinTiltX
                            val z3d = pt.baseY * sinTiltX + zRotY * cosTiltX
                            val x3d = xRotY

                            // Amplified dual-frequency 3D spherical harmonic displacement wave
                            val primaryHarmonic = sin(pt.theta * 5f + harmonicWavePhase * 1.4f) *
                                cos(pt.phiIndexRatio * 14f - harmonicWavePhase * 1.2f)
                            val secondaryHarmonic = sin(pt.theta * 9f - harmonicWavePhase * 2.1f) * 0.42f
                            val harmonicWave = 1f + waveAmplitude * (primaryHarmonic + secondaryHarmonic)

                            // Perspective projection (front of sphere looks closer & larger)
                            val perspective = 2.6f / (2.6f - z3d * 0.55f)
                            val effectiveRadius = sphereRadius * harmonicWave * perspective * 0.93f

                            val projX = center.x + x3d * effectiveRadius
                            val projY = center.y + y3d * effectiveRadius
                            val currentOffset = Offset(projX, projY)

                            val depthNormalized = ((z3d + 1f) * 0.5f).coerceIn(0.14f, 1f)
                            val dotRadius = (1.05f + depthNormalized * 2.30f).dp.toPx()

                            // Exact Signature Archer AI color distribution:
                            // Golden-yellow neural cluster on left hemisphere, emerald transition, cyber-cyan right hemisphere
                            val particleColor = when {
                                x3d < -0.20f && y3d > -0.65f && y3d < 0.68f -> {
                                    ParticleGold.copy(alpha = (0.28f + depthNormalized * 0.72f).coerceIn(0.2f, 1f))
                                }
                                x3d < -0.04f && idx % 2 == 0 -> {
                                    MatrixGreen.copy(alpha = (0.25f + depthNormalized * 0.70f).coerceIn(0.2f, 0.96f))
                                }
                                depthNormalized > 0.86f && idx % 7 == 0 -> {
                                    Color(0xFFE8FFFF).copy(alpha = 0.98f)
                                }
                                else -> {
                                    CyberCyan.copy(alpha = (0.24f + depthNormalized * 0.74f).coerceIn(0.18f, 0.98f))
                                }
                            }

                            // 3D neural constellation lines connecting front-hemisphere nodes in original colors
                            if (depthNormalized > 0.74f && idx % 5 == 0) {
                                prevSynapseOffset?.let { prev ->
                                    val dx = currentOffset.x - prev.x
                                    val dy = currentOffset.y - prev.y
                                    val maxDist = sphereRadius * 0.39f
                                    if (dx * dx + dy * dy < maxDist * maxDist) {
                                        drawLine(
                                            brush = Brush.linearGradient(
                                                colors = listOf(
                                                    prevSynapseColor.copy(alpha = 0.40f),
                                                    particleColor.copy(alpha = 0.40f)
                                                ),
                                                start = prev,
                                                end = currentOffset
                                            ),
                                            start = prev,
                                            end = currentOffset,
                                            strokeWidth = 1.0.dp.toPx(),
                                            cap = StrokeCap.Round
                                        )
                                    }
                                }
                                prevSynapseOffset = currentOffset
                                prevSynapseColor = particleColor
                            }

                            // Glow halo for front-most bright particles
                            if (depthNormalized > 0.82f && idx % 4 == 0) {
                                drawCircle(
                                    color = particleColor.copy(alpha = 0.28f),
                                    radius = dotRadius * 2.15f,
                                    center = currentOffset
                                )
                            }

                            drawCircle(
                                color = particleColor,
                                radius = dotRadius,
                                center = currentOffset
                            )
                        }

                        // 8. 3D Top-Left Specular Glass Reflection Arc (adds realistic 3D crystal sphere depth)
                        val highlightRadius = sphereRadius * 0.88f
                        drawArc(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.42f),
                                    CyberCyan.copy(alpha = 0.14f),
                                    Color.Transparent
                                )
                            ),
                            startAngle = 200f,
                            sweepAngle = 72f,
                            useCenter = false,
                            topLeft = Offset(center.x - highlightRadius, center.y - highlightRadius),
                            size = Size(highlightRadius * 2f, highlightRadius * 2f),
                            style = Stroke(width = 3.0.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }

                    // Bottom Mic Icon + SPEAKING/LISTENING status badge inside the globe
                    Surface(
                        color = Color(0xCC031410),
                        shape = RoundedCornerShape(50),
                        border = androidx.compose.foundation.BorderStroke(
                            1.1.dp,
                            MatrixGreen.copy(alpha = 0.72f)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = null,
                                tint = Color(0xFFB9F6CA),
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    isSpeaking -> "SPEAKING"
                                    isListening -> "LISTENING"
                                    isThinking -> "CONNECTING"
                                    else -> "TAP GLOBE"
                                },
                                fontFamily = FontFamily.SansSerif,
                                fontSize = 9.5.sp,
                                letterSpacing = 1.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFD8FFF0)
                            )
                        }
                    }
                }
            }
        }

        // Bottom Row under the 3D Globe: API KEY Quick Button + STOP + Speaker Toggle (Original Colors)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 20.dp, top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onOpenApiKeyClick != null) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .height(38.dp)
                        .shadow(5.dp, RoundedCornerShape(50), ambientColor = MatrixGreen, spotColor = CyberCyan)
                        .clip(RoundedCornerShape(50))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color(0xFF08291E),
                                    Color(0xFF041711)
                                )
                            )
                        )
                        .border(
                            1.5.dp,
                            Brush.horizontalGradient(listOf(MatrixGreen, CyberCyan)),
                            RoundedCornerShape(50)
                        )
                        .clickable { onOpenApiKeyClick() }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .testTag("globe_stage_api_key_btn")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "API Key",
                            tint = MatrixGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "API KEY",
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 10.5.sp,
                            letterSpacing = 1.0.sp,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .height(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF280712),
                                Color(0xFF14050B)
                            )
                        )
                    )
                    .border(
                        1.5.dp,
                        Brush.horizontalGradient(listOf(StopCrimson, Color(0xFFFF758F))),
                        RoundedCornerShape(50)
                    )
                    .clickable { onStopClick() }
                    .padding(horizontal = 18.dp, vertical = 6.dp)
                    .testTag("jarvis_stop_button")
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = StopCrimson,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "STOP",
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 11.sp,
                        letterSpacing = 1.2.sp,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (ttsEnabled) {
                            Brush.linearGradient(listOf(CyberCyan, MatrixGreen))
                        } else {
                            Brush.linearGradient(listOf(Color(0xFF0E2226), Color(0xFF071316)))
                        }
                    )
                    .border(
                        1.2.dp,
                        if (ttsEnabled) Color.White.copy(alpha = 0.7f) else CyberCyan.copy(alpha = 0.55f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable { onToggleTts() }
                    .testTag("jarvis_speaker_toggle")
            ) {
                Icon(
                    imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = "ভয়েস স্পিকার",
                    tint = if (ttsEnabled) Color(0xFF021614) else CyberCyan,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

@Composable
private fun CircuitNodePillButton(
    label: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val pillShape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .shadow(
                elevation = 6.dp,
                shape = pillShape,
                ambientColor = accentColor.copy(alpha = 0.45f),
                spotColor = CyberCyan.copy(alpha = 0.50f)
            )
            .clip(pillShape)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.22f),
                        Color(0xFF081D17),
                        Color(0xFF05110E)
                    )
                )
            )
            .border(
                width = 1.6.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        accentColor,
                        accentColor.copy(alpha = 0.55f),
                        CyberCyan.copy(alpha = 0.85f)
                    )
                ),
                shape = pillShape
            )
            .clickable { onClick() }
            .padding(horizontal = 7.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = 0.38f),
                                accentColor.copy(alpha = 0.12f)
                            )
                        )
                    )
                    .border(1.3.dp, accentColor, CircleShape)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 11.2.sp,
                    letterSpacing = 0.9.sp,
                    fontWeight = FontWeight.ExtraBold
                ),
                color = Color.White,
                maxLines = 1
            )
        }
    }
}
