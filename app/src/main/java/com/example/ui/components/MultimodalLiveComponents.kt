package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.service.LiveConnectionEngine
import com.example.service.MultimodalLiveSessionState
import com.example.service.ScreenCaptureSessionBus
import com.example.ui.theme.PulseRose
import java.util.concurrent.Executors

@Composable
fun MultimodalLiveStudioBar(
    liveState: MultimodalLiveSessionState,
    onToggleLiveSession: () -> Unit,
    onOpenLiveApiHub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val barShape = RoundedCornerShape(12.dp)
    val isLive = liveState.isLiveActive

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(if (isLive) Color(0xFF1E0812) else Color(0xFF061B15))
            .border(
                width = 1.3.dp,
                color = if (isLive) PulseRose else MatrixGreen,
                shape = barShape
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag("multimodal_live_studio_bar"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 1. Primary Start / Stop Multimodal Live Button (Voice + Camera + Screen + Phone Tools)
        Surface(
            color = if (isLive) PulseRose else MatrixGreen,
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier
                .weight(1.25f)
                .clickable { onToggleLiveSession() }
                .testTag("btn_start_multimodal_live")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (isLive) Icons.Default.Stop else Icons.Default.RadioButtonChecked,
                    contentDescription = "Multimodal Live Toggle",
                    tint = if (isLive) Color.White else Color(0xFF02130B),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = if (isLive) {
                        "LIVE ON • VOICE+CAM+SCREEN"
                    } else {
                        "START MULTIMODAL LIVE"
                    },
                    color = if (isLive) Color.White else Color(0xFF02130B),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // 2. Open Multimodal Live API Hub & Key/WebSocket Exporter Button ("বের করে দেওয়া")
        Surface(
            color = Color(0xFF092327),
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.1.dp, CyberCyan),
            modifier = Modifier
                .weight(0.95f)
                .clickable { onOpenLiveApiHub() }
                .testTag("btn_open_live_api_hub")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = "Multimodal Live API Hub",
                    tint = CyberCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "LIVE API KEY & WS",
                    color = CyberCyan,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.2.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultimodalLiveHudOverlayCard(
    liveState: MultimodalLiveSessionState,
    isFrontCamera: Boolean,
    visibleScreenText: String,
    onToggleMicMute: () -> Unit,
    onToggleCameraStream: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleScreenStream: () -> Unit,
    onToggleToolsArmed: () -> Unit,
    onTriggerBargeIn: () -> Unit,
    onSwitchEngine: (LiveConnectionEngine) -> Unit,
    onCameraBitmapCaptured: (android.graphics.Bitmap) -> Unit,
    onAnalyzeLiveNow: () -> Unit,
    onOpenLiveApiHub: () -> Unit,
    onStopLiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPerm = granted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF071A15)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, PulseRose.copy(alpha = 0.9f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("multimodal_live_hud_card")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Top Status Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(PulseRose)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "MULTIMODAL LIVE • ${liveState.engineMode.badgeText}",
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = "${liveState.fpsTelemetry} • ফ্রেম #${liveState.framesSentCount}",
                    color = CyberCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp
                )
            }

            // Modality Status Pills: MIC LIVE | CAM STREAM | SCREEN LIVE | TOOLS ARMED
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                LiveModalityPill(
                    label = if (liveState.isMicMuted) "MIC MUTED" else "MIC LIVE",
                    active = !liveState.isMicMuted,
                    activeColor = MatrixGreen,
                    onClick = onToggleMicMute,
                    tag = "live_pill_mic"
                )
                LiveModalityPill(
                    label = if (liveState.isCameraStreamEnabled) {
                        if (isFrontCamera) "CAM: FRONT" else "CAM: BACK"
                    } else {
                        "CAM OFF"
                    },
                    active = liveState.isCameraStreamEnabled,
                    activeColor = CyberCyan,
                    onClick = onToggleCameraStream,
                    tag = "live_pill_cam"
                )
                LiveModalityPill(
                    label = if (liveState.isScreenStreamEnabled) "SCREEN LIVE" else "SCREEN OFF",
                    active = liveState.isScreenStreamEnabled,
                    activeColor = MatrixGreen,
                    onClick = onToggleScreenStream,
                    tag = "live_pill_screen"
                )
                LiveModalityPill(
                    label = if (liveState.isToolsArmed) "TOOLS ARMED" else "TOOLS OFF",
                    active = liveState.isToolsArmed,
                    activeColor = CircuitOrange,
                    onClick = onToggleToolsArmed,
                    tag = "live_pill_tools"
                )
            }

            // Split View: Live Camera Feed (Left) + Live Screen & Waveform Telemetry (Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Camera Preview Box
                Box(
                    modifier = Modifier
                        .weight(0.95f)
                        .height(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF030D0A))
                        .border(1.dp, CyberCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (liveState.isCameraStreamEnabled && hasCameraPerm) {
                        LiveCameraXPreviewBox(
                            isFrontCamera = isFrontCamera,
                            onFrameCaptured = onCameraBitmapCaptured,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(6.dp)
                        ) {
                            Icon(
                                imageVector = if (liveState.isCameraStreamEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                                contentDescription = null,
                                tint = CyberCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (!liveState.isCameraStreamEnabled) {
                                    "ক্যামেরা স্ট্রিম বন্ধ"
                                } else {
                                    "লাইভ ক্যামেরা ফিড চালু করুন"
                                },
                                color = Color.White,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (liveState.isCameraStreamEnabled && !hasCameraPerm) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    color = MatrixGreen,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.clickable {
                                        cameraPermLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                ) {
                                    Text(
                                        text = "ক্যামেরা পারমিশন দিন",
                                        color = Color.Black,
                                        fontSize = 8.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Lens flip mini badge
                    Surface(
                        color = Color(0xAA02100C),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .clickable { onFlipCamera() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Flip Camera",
                                tint = CyberCyan,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = if (isFrontCamera) "FRONT" else "BACK",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Live Screen Share Feed + Audio Waveform Column
                val latestScreenBitmap by ScreenCaptureSessionBus.latestBitmapFlow.collectAsStateWithLifecycle()

                Box(
                    modifier = Modifier
                        .weight(1.05f)
                        .height(108.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF04120E))
                        .border(1.dp, MatrixGreen.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .clickable { onToggleScreenStream() },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = latestScreenBitmap
                    if (liveState.isScreenStreamEnabled && bmp != null && !bmp.isRecycled) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live Screen Share Preview",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color(0xCC02100C),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "● SCREEN LIVE",
                                color = MatrixGreen,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ScreenShare,
                                    contentDescription = null,
                                    tint = MatrixGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (liveState.isScreenStreamEnabled) {
                                        "স্ক্রিন শেয়ার চালু..."
                                    } else {
                                        "স্ক্রিন শেয়ার চালু করতে ট্যাপ করুন"
                                    },
                                    color = MatrixGreen,
                                    fontSize = 9.2.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Text(
                                text = "স্ক্রিন টেক্সট: ${visibleScreenText.take(55)}",
                                color = Color(0xFFD0F5EA),
                                fontSize = 8.5.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            // 16-Bar Live Audio Waveform
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(20.dp),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                liveState.audioWaveformLevels.forEachIndexed { idx, level ->
                                    val barColor = if (idx % 2 == 0) MatrixGreen else CyberCyan
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((20f * level).dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(barColor)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Live Tool Execution Telemetry Banner
            Surface(
                color = Color(0xFF0B231D),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, CircuitOrange.copy(alpha = 0.65f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = liveState.lastToolExecuted,
                    color = CircuitOrange,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }

            // Action Row: Barge-In | Analyze Live Stream Now | API Hub | Stop Live
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = onTriggerBargeIn,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .testTag("btn_live_barge_in")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, tint = MatrixGreen, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("বার্জ-ইন (থামিয়ে বলুন)", fontSize = 9.sp, color = MatrixGreen, maxLines = 1)
                }

                Button(
                    onClick = onAnalyzeLiveNow,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CyberCyan,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .weight(1.05f)
                        .height(34.dp)
                        .testTag("btn_analyze_live_stream_now")
                ) {
                    Text("⚡ লাইভ ভিশন বিশ্লেষণ", fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onStopLiveSession,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(34.dp)
                        .testTag("btn_stop_live_hud")
                ) {
                    Text("বন্ধ", fontSize = 9.sp, color = PulseRose)
                }
            }
        }
    }
}

@Composable
private fun LiveModalityPill(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    tag: String
) {
    Surface(
        color = if (active) activeColor.copy(alpha = 0.18f) else Color(0xFF0A1815),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (active) activeColor else Color(0xFF28463E)),
        modifier = Modifier
            .clickable { onClick() }
            .testTag(tag)
    ) {
        Text(
            text = label,
            color = if (active) activeColor else Color(0xFF8AA8A4),
            fontSize = 8.8.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun LiveCameraXPreviewBox(
    isFrontCamera: Boolean,
    onFrameCaptured: (android.graphics.Bitmap) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(isFrontCamera, lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val listener = Runnable {
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                var lastCaptureTime = 0L
                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val now = System.currentTimeMillis()
                    if (now - lastCaptureTime >= 1600L) {
                        lastCaptureTime = now
                        try {
                            val bmp = imageProxy.toBitmap()
                            onFrameCaptured(bmp)
                        } catch (_: Exception) {
                        }
                    }
                    imageProxy.close()
                }

                val selector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    selector,
                    preview,
                    imageAnalysis
                )
            } catch (_: Exception) {
            }
        }
        cameraProviderFuture.addListener(listener, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultimodalLiveApiHubSheet(
    liveState: MultimodalLiveSessionState,
    customApiKey: String,
    onSaveCustomApiKey: (String) -> Unit,
    onSelectEngineMode: (LiveConnectionEngine) -> Unit,
    onToggleLiveSession: () -> Unit,
    onCopyConfigItem: (String, String) -> Unit,
    onExportLiveApiToArcherFolder: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val cfg = liveState.exportConfig
    var customKeyDraft by rememberSaveable(customApiKey) { mutableStateOf(customApiKey) }
    var hubKeyStatusHint by rememberSaveable { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current

    DisposableEffect(Unit) {
        onDispose {
            runCatching { textToolbar.hide() }
            runCatching { focusManager.clearFocus(force = true) }
        }
    }

    val safeClose = {
        runCatching { textToolbar.hide() }
        runCatching { focusManager.clearFocus(force = true) }
        onClose()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("multimodal_live_api_hub_modal")
    ) {
        PremiumModalShell(
            badgeText = "MULTIMODAL LIVE API HUB • WS & KEY EXPORTER",
            title = "লাইভ এপিআই, ওয়েবসকেট ও কি (Key) স্টুডিও",
            subtitle = "উপরে সরাসরি আপনার Gemini API Key বসান বা পেস্ট করুন • ভয়েস + ক্যামেরা + স্ক্রিন শেয়ার",
            accentColor = CyberCyan,
            secondaryAccent = MatrixGreen,
            onClose = safeClose
        ) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // 1. Custom Gemini Live API Key Input, 1-Tap Clipboard Paste & Override (RIGHT AT THE TOP!)
                item {
                    PremiumSectionCard(
                        title = "১. 🔑 আপনার Gemini API Key এখানে বসান বা পেস্ট করুন",
                        subtitle = if (customApiKey.isNotBlank()) {
                            "কাস্টম API Key সক্রিয় (${customApiKey.take(10)}...) • নিচে চাইলে পরিবর্তন করুন"
                        } else {
                            "নিচের বক্সে AIzaSy... কি টাইপ করুন অথবা '📋 পেস্ট করুন' বাটনে চাপুন"
                        },
                        icon = Icons.Default.Key,
                        accentColor = MatrixGreen,
                        badgeText = if (customApiKey.isNotBlank()) "CUSTOM KEY" else "PASTE KEY"
                    ) {
                        OutlinedTextField(
                            value = customKeyDraft,
                            onValueChange = {
                                customKeyDraft = it
                                hubKeyStatusHint = ""
                            },
                            label = {
                                Text(
                                    text = "এখানে আপনার Gemini API Key বসান (AIzaSy...)",
                                    color = MatrixGreen,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "AIzaSy... (Custom Gemini API Key)",
                                    color = Color(0xFF90B8D8),
                                    fontSize = 11.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MatrixGreen,
                                unfocusedBorderColor = CyberCyan,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = MatrixGreen,
                                focusedContainerColor = Color(0xFF0A2034),
                                unfocusedContainerColor = Color(0xFF071626)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("input_custom_live_api_key")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PremiumActionButton(
                                text = "📋 পেস্ট করুন",
                                primaryColor = CyberCyan,
                                secondaryColor = MatrixGreen,
                                onClick = {
                                    runCatching {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            customKeyDraft = clipText
                                            hubKeyStatusHint = "✓ ক্লিপবোর্ড থেকে API Key পেস্ট হয়েছে! এখন 'সেভ করুন' চাপুন।"
                                        } else {
                                            hubKeyStatusHint = "⚠ ক্লিপবোর্ডে কোনো টেক্সট পাওয়া যায়নি।"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "btn_paste_custom_live_key"
                            )

                            PremiumActionButton(
                                text = "✅ সেভ করুন",
                                primaryColor = MatrixGreen,
                                secondaryColor = CyberCyan,
                                onClick = {
                                    runCatching { textToolbar.hide() }
                                    runCatching { focusManager.clearFocus(force = true) }
                                    onSaveCustomApiKey(customKeyDraft.trim())
                                    hubKeyStatusHint = if (customKeyDraft.isNotBlank()) {
                                        "✓ আপনার Gemini API Key সফলভাবে সেভ হয়েছে!"
                                    } else {
                                        "✓ বিল্ট-ইন ক্লাউড AI সক্রিয় করা হয়েছে!"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        hubKeyStatusHint,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "btn_save_custom_live_key"
                            )

                            PremiumActionButton(
                                text = "🗑️ ক্লিয়ার",
                                primaryColor = PulseRose,
                                secondaryColor = CircuitOrange,
                                isOutlined = true,
                                onClick = {
                                    customKeyDraft = ""
                                    onSaveCustomApiKey("")
                                    hubKeyStatusHint = "কাস্টম কি মুছে বিল্ট-ইন ক্লাউড AI সক্রিয় করা হয়েছে।"
                                },
                                modifier = Modifier.weight(0.75f)
                            )
                        }

                        if (hubKeyStatusHint.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = hubKeyStatusHint,
                                color = MatrixGreen,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 2. Start / Stop Live & Dual-Engine Mode Selector
                item {
                    PremiumSectionCard(
                        title = "২. মাল্টিমোডাল লাইভ ইঞ্জিন ও সেশন কন্ট্রোল",
                        subtitle = liveState.engineMode.labelBn,
                        icon = Icons.Default.GraphicEq,
                        accentColor = MatrixGreen,
                        badgeText = if (liveState.isLiveActive) "LIVE ON" else "READY"
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            LiveConnectionEngine.entries.forEach { engine ->
                                val selected = liveState.engineMode == engine
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectEngineMode(engine) },
                                    label = {
                                        Text(
                                            text = engine.badgeText,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) Color.Black else Color.White
                                        )
                                    },
                                    colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MatrixGreen,
                                        containerColor = Color(0xFF071D18)
                                    ),
                                    modifier = Modifier.testTag("chip_live_engine_${engine.id}")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        PremiumActionButton(
                            text = if (liveState.isLiveActive) {
                                "লাইভ সেশন বন্ধ করুন (STOP MULTIMODAL LIVE)"
                            } else {
                                "এখনই মাল্টিমোডাল লাইভ চালু করুন (VOICE + CAM + SCREEN)"
                            },
                            icon = if (liveState.isLiveActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                            primaryColor = if (liveState.isLiveActive) PulseRose else MatrixGreen,
                            secondaryColor = if (liveState.isLiveActive) CircuitOrange else CyberCyan,
                            onClick = onToggleLiveSession,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "hub_btn_toggle_live_session"
                        )
                    }
                }

                // 3 & 4. Developer WebSocket & JSON Export — Collapsed by default so screen stays clean
                item {
                    var showAdvancedExport by rememberSaveable { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🛠️ ডেভেলপার WebSocket ও JSON এক্সপোর্ট:",
                                color = CyberCyan,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (showAdvancedExport) PulseRose.copy(alpha = 0.25f) else Color(0xFF0A1D19),
                                border = BorderStroke(1.dp, if (showAdvancedExport) PulseRose else CyberCyan),
                                modifier = Modifier.clickable { showAdvancedExport = !showAdvancedExport }
                            ) {
                                Text(
                                    text = if (showAdvancedExport) "✕ বন্ধ করুন" else "📦 অ্যাডভান্সড এক্সপোর্ট খুলুন",
                                    color = Color.White,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        if (showAdvancedExport) {
                            PremiumSectionCard(
                                title = "বের করে দেওয়া Multimodal Live API Key ও WebSocket তথ্য",
                                subtitle = if (liveState.lastCopiedLabel.isNotBlank()) {
                                    "✓ সর্বশেষ কপি করা হয়েছে: ${liveState.lastCopiedLabel}"
                                } else {
                                    "যেকোনো কি বা ওয়েবসকেট লিংক ওয়ান-ট্যাপে কপি করুন"
                                },
                                icon = Icons.Default.Key,
                                accentColor = CyberCyan,
                                badgeText = "CREDENTIALS"
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ExportableCredentialRow(
                                        title = "Built-in Firebase Gemini API Key",
                                        value = cfg.builtInFirebaseApiKey.ifBlank { "App Check Managed Cloud Key" },
                                        onCopy = {
                                            onCopyConfigItem(
                                                "Built-in Gemini API Key",
                                                cfg.builtInFirebaseApiKey
                                            )
                                        },
                                        tag = "btn_copy_builtin_live_key"
                                    )

                                    ExportableCredentialRow(
                                        title = "Active Effective Live API Key",
                                        value = cfg.effectiveApiKey.ifBlank { "Built-in Cloud Stream Active" },
                                        onCopy = {
                                            onCopyConfigItem(
                                                "Effective Live API Key",
                                                cfg.effectiveApiKey
                                            )
                                        },
                                        tag = "btn_copy_effective_live_key"
                                    )

                                    ExportableCredentialRow(
                                        title = "Project ID & App Package",
                                        value = "Project: ${cfg.projectId} | Pkg: ${cfg.packageName}",
                                        onCopy = {
                                            onCopyConfigItem(
                                                "Project & Package Info",
                                                "project_id=${cfg.projectId}\napp_id=${cfg.applicationId}\npackage=${cfg.packageName}"
                                            )
                                        },
                                        tag = "btn_copy_project_info"
                                    )

                                    ExportableCredentialRow(
                                        title = "Multimodal Live WebSocket URL (BidiGenerateContent)",
                                        value = cfg.fullWebsocketUrlWithKey,
                                        onCopy = {
                                            onCopyConfigItem(
                                                "Multimodal Live WebSocket URL",
                                                cfg.fullWebsocketUrlWithKey
                                            )
                                        },
                                        tag = "btn_copy_live_ws_url"
                                    )
                                }
                            }

                            PremiumSectionCard(
                                title = "WebSocket Setup JSON, Tools Schema ও 'archer' ফোল্ডার এক্সপোর্ট",
                                subtitle = "সম্পূর্ণ ওয়েবসকেট পেলোড ও টুল স্কিমা কপি বা ZIP আকারে সেভ করুন",
                                icon = Icons.Default.FolderZip,
                                accentColor = CyberCyan,
                                badgeText = "ZIP BUNDLE"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PremiumActionButton(
                                        text = "Setup JSON কপি",
                                        icon = Icons.Default.ContentCopy,
                                        primaryColor = MatrixGreen,
                                        secondaryColor = CyberCyan,
                                        isOutlined = true,
                                        onClick = {
                                            onCopyConfigItem(
                                                "WebSocket Setup JSON",
                                                cfg.setupPayloadJson
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        testTag = "btn_copy_setup_json"
                                    )

                                    PremiumActionButton(
                                        text = "Tools JSON কপি",
                                        icon = Icons.Default.Code,
                                        primaryColor = CyberCyan,
                                        secondaryColor = MatrixGreen,
                                        isOutlined = true,
                                        onClick = {
                                            onCopyConfigItem(
                                                "Live Function Tools JSON",
                                                cfg.toolsDeclarationJson
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                        testTag = "btn_copy_tools_json"
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                PremiumActionButton(
                                    text = "📦 'archer' ফোল্ডারে Live API Key ও কোড সেভ করুন (ZIP)",
                                    icon = Icons.Default.FolderZip,
                                    primaryColor = CyberCyan,
                                    secondaryColor = MatrixGreen,
                                    onClick = onExportLiveApiToArcherFolder,
                                    modifier = Modifier.fillMaxWidth(),
                                    testTag = "btn_export_live_api_to_archer"
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
private fun ExportableCredentialRow(
    title: String,
    value: String,
    onCopy: () -> Unit,
    tag: String
) {
    Surface(
        color = Color(0xFF051618),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.1.dp, CyberCyan.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MatrixGreen,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onCopy,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = CyberCyan.copy(alpha = 0.2f),
                    contentColor = CyberCyan
                ),
                modifier = Modifier
                    .height(34.dp)
                    .testTag(tag)
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy",
                    tint = CyberCyan,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("কপি", fontSize = 10.5.sp, color = CyberCyan, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}
