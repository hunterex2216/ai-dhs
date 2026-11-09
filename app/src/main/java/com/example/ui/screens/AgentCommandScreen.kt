package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ChangeHistory
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LaptopChromebook
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AgentMemoryNoteEntity
import com.example.data.PlatformAccountEntity
import com.example.data.TodayTaskEntity
import com.example.model.AgentPlanResponse
import com.example.model.AgentStep
import com.example.model.JarvisNodeModal
import com.example.model.MusicMood
import com.example.model.WakeWordPhase
import androidx.compose.material.icons.filled.Code
import com.example.service.AccessibilityEngineState
import com.example.service.CodingWorkspaceState
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.DeviceHardwareSnapshot
import com.example.service.LiveConnectionEngine
import com.example.service.MultimodalLiveSessionState
import com.example.service.NotificationHubState
import com.example.service.SavedArcherArtifact
import com.example.service.VoiceBridgeState
import com.example.ui.components.ChatAndUniversalCrossAppSheet
import com.example.ui.components.CodingAndArgonWorkspaceSheet
import com.example.ui.components.CyberCyan
import com.example.ui.components.JarvisCircuitAndGlobeStage
import com.example.ui.components.LiveCameraAndScreenVisionSheet
import com.example.ui.components.MatrixGreen
import com.example.ui.components.MemoryAndPlatformIdsSheet
import com.example.ui.components.MultimodalLiveApiHubSheet
import com.example.ui.components.MultimodalLiveHudOverlayCard
import com.example.ui.components.MultimodalLiveStudioBar
import com.example.ui.components.LiveCompactBar
import com.example.ui.components.SettingsAndPermissionsSheet
import com.example.ui.components.SoulAndMusicWakeSheet
import com.example.ui.theme.PulseRose
import com.example.viewmodel.AgentUiState

@Composable
fun AgentCommandScreen(
    uiState: AgentUiState,
    plans: List<AgentPlanResponse>,
    hardwareState: DeviceHardwareSnapshot,
    voiceState: VoiceBridgeState,
    sentinelState: CubeSentinelState,
    accessibilityState: AccessibilityEngineState,
    notificationState: NotificationHubState,
    memoryNotes: List<AgentMemoryNoteEntity>,
    todayTasks: List<TodayTaskEntity>,
    platformAccounts: List<PlatformAccountEntity>,
    activeModal: JarvisNodeModal,
    isFrontCameraSelected: Boolean,
    geminiApiKey: String,
    onSaveGeminiApiKey: (String) -> Unit,
    onSendCommand: (String) -> Unit,
    onSimulateHeyCube: () -> Unit,
    onToggleAlwaysOnSentinel: (Boolean) -> Unit,
    onExecuteStep: (String, AgentStep) -> Unit,
    onSelectMood: (MusicMood) -> Unit,
    onOpenExternalMusic: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onTogglePlayMusic: () -> Unit,
    onToggleTts: () -> Unit,
    onStartVoiceListening: () -> Unit,
    onStopVoiceListening: () -> Unit,
    onOpenModal: (JarvisNodeModal) -> Unit,
    onToggleCameraFacing: () -> Unit,
    onLaunchSystemCamera: () -> Unit,
    onAddMemoryNote: (String, String) -> Unit,
    onDeleteMemoryNote: (Int) -> Unit,
    onSavePlatformAccount: (String, String, String, String) -> Unit,
    onDeletePlatformAccount: (Int) -> Unit,
    onToggleTodayTask: (TodayTaskEntity) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationListenerSettings: () -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onVolumeChange: (String, Int) -> Unit,
    onOpenSystemSetting: (String) -> Unit,
    onTestScreenMacro: (String, String) -> Unit,
    onInstallFromPlayStore: (String) -> Unit,
    onChromeAction: (String) -> Unit,
    onTelegramDownload: (String, String) -> Unit,
    onQuickReplyNotification: (String, String) -> Unit,
    onAnalyzeScreenWithAi: () -> Unit,
    modifier: Modifier = Modifier,
    liveUserTranscript: String = "",
    liveArcherReply: String = "",
    onSpeakText: (String) -> Unit = {},
    onReplayLastSpeech: () -> Unit = {},
    onTestTtsVoice: () -> Unit = {},
    onChangeSpeechRate: (Float) -> Unit = {},
    onChangeSpeechPitch: (Float) -> Unit = {},
    onSelectVoiceProfile: (String) -> Unit = {},
    onToggleHandsFreeVoice: () -> Unit = {},
    onSetHandsFreeVoice: (Boolean) -> Unit = {},
    onCycleSttLanguage: () -> Unit = {},
    onSelectSttLanguage: (String) -> Unit = {},
    onExternalSpeechRecognized: (String) -> Unit = {},
    codingWorkspaceState: CodingWorkspaceState = CodingWorkspaceState(),
    lockedReplyLanguage: String = "AUTO",
    persistentUserRule: String = "",
    onSetLockedLanguage: (String) -> Unit = {},
    onSavePersistentRule: (String) -> Unit = {},
    onToggleQuickSettingTile: (String, Boolean?) -> Unit = { _, _ -> },
    onSelectAiModel: (String) -> Unit = {},
    onToggleAutoModelRouting: () -> Unit = {},
    onSaveManualCodeToArcher: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onShareArcherArtifact: (SavedArcherArtifact) -> Unit = {},
    multimodalLiveState: MultimodalLiveSessionState = MultimodalLiveSessionState(),
    onToggleMultimodalLive: () -> Unit = {},
    onSelectLiveEngineMode: (LiveConnectionEngine) -> Unit = {},
    onToggleLiveMicMute: () -> Unit = {},
    onToggleLiveCameraStream: () -> Unit = {},
    onToggleLiveScreenStream: () -> Unit = {},
    onToggleLiveToolsArmed: () -> Unit = {},
    onTriggerLiveBargeIn: () -> Unit = {},
    onLiveCameraBitmapCaptured: (android.graphics.Bitmap) -> Unit = {},
    onCopyLiveApiConfigItem: (String, String) -> Unit = { _, _ -> },
    onExportLiveApiBundleToArcher: () -> Unit = {},
    activePersonality: com.example.model.AgentPersonalityPreset = com.example.model.AgentPersonalityPreset.CORE_JARVIS_BOSS,
    activeVoicePreset: com.example.model.AgentVoicePreset = com.example.model.AgentVoicePreset.CHARON_MALE,
    micSensitivityBoost: Float = 2.8f,
    customPersonalityPrompt: String = "",
    proactiveAlertsEnabled: Boolean = true,
    proactiveWatchTasks: List<com.example.model.ProactiveWatchTask> = emptyList(),
    onSelectPersonality: (com.example.model.AgentPersonalityPreset) -> Unit = {},
    onSelectVoicePreset: (com.example.model.AgentVoicePreset) -> Unit = {},
    onChangeMicSensitivityBoost: (Float) -> Unit = {},
    onSaveCustomPersonality: (String) -> Unit = {},
    onToggleProactiveAlerts: (Boolean) -> Unit = {},
    onStartProactiveWatch: (String, Int, String) -> Unit = { _, _, _ -> },
    onCancelProactiveWatch: (String) -> Unit = {}
) {
    var showAllHeadlines by rememberSaveable { mutableStateOf(false) }
    var typedCommand by rememberSaveable { mutableStateOf("") }
    var isDirectScreenAutoTypeMode by rememberSaveable { mutableStateOf(false) }
    var showQuickGeminiKeyDialog by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    var hasContactsAndCallPerm by rememberSaveable {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        )
    }

    val contactsAndCallPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        hasContactsAndCallPerm =
            grants[Manifest.permission.READ_CONTACTS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
    }

    val systemRecognizerFallbackLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val topText = matches?.firstOrNull().orEmpty().trim()
            if (topText.isNotBlank()) {
                onExternalSpeechRecognized(topText)
            }
        }
    }

    val launchSpeechCapture: () -> Unit = {
        val serviceAvailable = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(true)
        if (serviceAvailable) {
            onStartVoiceListening()
        } else {
            runCatching {
                val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceState.languageCode)
                    putExtra(
                        RecognizerIntent.EXTRA_PROMPT,
                        "জি স্যার, আপনার আদেশ বলুন (${voiceState.languageLabel})"
                    )
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                systemRecognizerFallbackLauncher.launch(fallbackIntent)
            }.onFailure {
                onStartVoiceListening()
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onSetHandsFreeVoice(true)
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMicPerm) {
            onSetHandsFreeVoice(true)
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val requestOrToggleVoiceListening: () -> Unit = {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasMicPerm) {
            onSetHandsFreeVoice(true)
            launchSpeechCapture()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val alwaysOnPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val micGranted = grants[Manifest.permission.RECORD_AUDIO] == true
        if (micGranted) {
            onToggleAlwaysOnSentinel(true)
            onSetHandsFreeVoice(true)
        }
    }

    val liveSessionPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onToggleMultimodalLive()
    }

    val requestOrToggleMultimodalLive: () -> Unit = {
        if (multimodalLiveState.isLiveActive) {
            onToggleMultimodalLive()
        } else {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamPerm = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMicPerm) {
                onToggleMultimodalLive()
            } else {
                liveSessionPermissionsLauncher.launch(
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                )
            }
        }
    }

    val latestPlan = plans.firstOrNull()
    val archerTranscriptText = when {
        liveArcherReply.isNotBlank() -> liveArcherReply
        latestPlan != null && latestPlan.spokenReply.isNotBlank() -> latestPlan.spokenReply
        else -> "জি স্যার, আমি প্রস্তুত। আপনার যেকোনো প্রশ্ন বা আদেশ বলুন।"
    }
    val userTranscriptText = when {
        typedCommand.isNotBlank() -> typedCommand
        voiceState.isListening && voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
        voiceState.isListening -> "শুনছি... এখন আপনার কথা বলুন"
        uiState is AgentUiState.Thinking -> uiState.commandText
        liveUserTranscript.isNotBlank() -> liveUserTranscript
        voiceState.partialTranscript.isNotBlank() -> voiceState.partialTranscript
        latestPlan != null && latestPlan.userPrompt.isNotBlank() -> latestPlan.userPrompt
        else -> "মাইকে বলুন অথবা নিচে টাইপ করুন..."
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF020B06))
    ) {
        // Rich Cyber-Emerald Matrix Grid & Radial Glow Background matching the reference screenshots
        ArcherEmeraldCyberBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Exact Top Header Bar: Emerald Bar + History/Key Icon (Left) + Bot Icon "ARCHER AI" (Center) + Neon Green Bug/Chip Settings Button (Right)
                ArcherTopHeaderBar(
                    isSentinelActive = sentinelState.isSentinelActive,
                    hasCustomGeminiKey = geminiApiKey.isNotBlank(),
                    onOpenGeminiKeyDialog = { showQuickGeminiKeyDialog = true },
                    onTapLogoWake = {
                        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        alwaysOnPermissionsLauncher.launch(perms.toTypedArray())
                    },
                    onOpenSettingsModal = { onOpenModal(JarvisNodeModal.SETTINGS_AND_PERMS) }
                )

                // 2. Clean 2-Button Top Action Row: SHARE SCREEN (Direct Live Cast Toggle) | ARGON 4 (Main Coding Studio)
                val screenCaptureState by com.example.service.ScreenCaptureSessionBus.state.collectAsState()
                val isScreenShareActiveNow = multimodalLiveState.isScreenStreamEnabled &&
                    (screenCaptureState.hasMediaProjectionGrant || accessibilityState.isServiceEnabled)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArcherBeveledTopButton(
                        label = if (isScreenShareActiveNow) "● SCREEN SHARE ON" else "SHARE SCREEN",
                        icon = Icons.Default.LaptopChromebook,
                        hasDoubleFrame = isScreenShareActiveNow,
                        onClick = onToggleLiveScreenStream,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hud_btn_share_screen")
                    )
                    ArcherBeveledTopButton(
                        label = "ARGON 4 STUDIO",
                        icon = Icons.Default.Code,
                        hasDoubleFrame = true,
                        onClick = { onOpenModal(JarvisNodeModal.CODING_AND_ARGON) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("hud_btn_argon_coding")
                    )
                }

                // 2B. Slim live bar (replaces the old text-heavy HUD)
                LiveCompactBar(
                    liveState = multimodalLiveState,
                    onToggleLive = requestOrToggleMultimodalLive,
                    onToggleMic = onToggleLiveMicMute,
                    onToggleScreen = onToggleLiveScreenStream,
                    onOpenHub = { onOpenModal(JarvisNodeModal.MULTIMODAL_LIVE_API_HUB) }
                )

                // 2B-2. Real-Time Biometric Speaker Identification, Owner Voice Lock & Male/Female Gender HUD Pill
                VoiceBiometricsHudPillBar(
                    onSpeakStatus = onSpeakText
                )

                // 2C. 5TB Google Drive ('AI_OS_Memory') Quick Connect, Live Cloud Brain & Custom API / Script Slot
                val driveSyncState by com.example.service.GoogleDriveMemorySyncService.state.collectAsState()
                var showDriveApiSetupDialog by remember { mutableStateOf(false) }
                var universalApiInput by remember(driveSyncState.universalApiInput) {
                    mutableStateOf(driveSyncState.universalApiInput)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF061611))
                        .border(
                            1.dp,
                            if (driveSyncState.isConnected) MatrixGreen.copy(alpha = 0.65f) else CyberCyan.copy(alpha = 0.55f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (driveSyncState.isConnected) {
                                "☁️ 5TB Drive Brain: ${driveSyncState.linkedEmail} (/${driveSyncState.driveFolderName})"
                            } else {
                                "☁️ 5TB Google Drive (/AI_OS_Memory) • কানেক্ট করুন বা API বসান"
                            },
                            color = if (driveSyncState.isConnected) MatrixGreen else CyberCyan,
                            fontSize = 10.8.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (driveSyncState.isConnected) {
                                "অটো-সিঙ্ক ও লাইভ ব্রেইন সচল: মেমোরি ${driveSyncState.backedUpMemoriesCount} • চ্যাট ${driveSyncState.backedUpConversationsCount} • কোড ${driveSyncState.backedUpScreenshotsAndFilesCount} • ZIP ${driveSyncState.backedUpZipPackagesCount}" +
                                    (if (driveSyncState.confirmationEmailSent) " • 📧 জিমেইল কনফার্মড" else "")
                            } else {
                                driveSyncState.lastSyncStatusBn
                            },
                            color = Color(0xFFB5D8CC),
                            fontSize = 9.2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(5.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (driveSyncState.isConnected) {
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.triggerFullSyncFromUi(
                                        context = context,
                                        onStatusSpoken = onSpeakText
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("main_sync_all_drive_button")
                            ) {
                                Text(
                                    text = if (driveSyncState.isSyncing) "⏳..." else "🔄 Sync",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.openOrShareToGoogleDriveApp(
                                        context = context,
                                        onStatusSpoken = onSpeakText
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("main_open_google_drive_app_button")
                            ) {
                                Text(
                                    text = "📂 Drive",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        } else {
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.startDirectDriveAuthorization(
                                        context = context,
                                        onStatusSpoken = onSpeakText
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                ),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("main_connect_google_drive_button")
                            ) {
                                Text(
                                    text = "☁️ Connect",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { showDriveApiSetupDialog = true },
                            border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.8f)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                            modifier = Modifier
                                .height(30.dp)
                                .testTag("main_drive_api_setup_button")
                        ) {
                            Text(
                                text = "🔑 API",
                                color = MatrixGreen,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                if (showDriveApiSetupDialog) {
                    AlertDialog(
                        onDismissRequest = { showDriveApiSetupDialog = false },
                        containerColor = Color(0xFF0B192C),
                        title = {
                            Text(
                                text = "☁️ 5TB Google Drive API ও লাইভ ক্লাউড ব্রেইন সেটআপ",
                                color = MatrixGreen,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        },
                        text = {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "স্যার, এই AI-এর সমস্ত মেমোরি, আগের কথোপকথন ও ভয়েস প্রোফাইল `/AI_OS_Memory` ফোল্ডারে স্বয়ংক্রিয়ভাবে সেভ ও রিস্টোর হয়। আপনি সরাসরি Google Sign-In করতে পারেন অথবা নিচের ১টি বক্সে মেইন API / Token / Apps Script URL / Drive ফোল্ডার লিংক বসান — সিস্টেম নিজেই চিনে কানেক্ট করবে। কানেক্ট হলে আপনার জিমেইলে কনফার্মেশন মেইল যাবে:",
                                    color = Color(0xFFD7F7EA),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )

                                OutlinedTextField(
                                    value = universalApiInput,
                                    onValueChange = { universalApiInput = it },
                                    label = { Text("🔑 মেইন API / লিংক / টোকেন (১টি বক্স — প্রি-লোডেড)", color = MatrixGreen, fontSize = 10.5.sp) },
                                    placeholder = { Text("AIzaSy... / ya29... / script.google.com/.../exec", fontSize = 10.sp) },
                                    minLines = 2,
                                    maxLines = 4,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("drive_universal_api_input")
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val builtInKey = com.example.service.GoogleDriveMemorySyncService.BUILT_IN_DRIVE_API_KEY
                                            universalApiInput = builtInKey
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Google_Drive_API_Key", builtInKey))
                                            android.widget.Toast.makeText(
                                                context,
                                                "✓ মেইন Drive API Key বক্সে বসানো ও কপি করা হয়েছে!",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        },
                                        border = BorderStroke(1.dp, CyberCyan),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("🔑 মেইন API কপি ও বসান", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            val pasted = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                            if (pasted.isNotBlank()) universalApiInput = pasted
                                        },
                                        border = BorderStroke(1.dp, MatrixGreen),
                                        modifier = Modifier.weight(1f).testTag("drive_universal_paste_button")
                                    ) {
                                        Text("📋 পেস্ট করুন", color = MatrixGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            val scriptCode = com.example.service.GoogleDriveMemorySyncService.getReadyToPasteAppsScriptCode()
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("AI_OS_Drive_Bridge", scriptCode))
                                            android.widget.Toast.makeText(
                                                context,
                                                "✓ Apps Script কোড কপি হয়েছে! script.google.com-এ পেস্ট করে Web App URL এখানে দিন।",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        },
                                        border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("📋 Apps Script কোড কপি", color = Color(0xFFFBBF24), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            com.example.service.GoogleDriveMemorySyncService.startDirectDriveAuthorization(
                                                context = context,
                                                onStatusSpoken = onSpeakText
                                            )
                                            showDriveApiSetupDialog = false
                                        },
                                        border = BorderStroke(1.dp, CyberCyan),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("☁️ Gmail সাইন-ইন", color = CyberCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.saveUniversalApiInput(
                                        context = context,
                                        rawInput = universalApiInput,
                                        onStatusSpoken = onSpeakText
                                    )
                                    showDriveApiSetupDialog = false
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MatrixGreen,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier.testTag("save_drive_api_config_button")
                            ) {
                                Text("✅ কানেক্ট করুন", fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDriveApiSetupDialog = false }) {
                                Text("বন্ধ করুন", color = Color(0xFF94A3B8))
                            }
                        }
                    )
                }

                // 3. Exact Neon-Green Rounded TRANSCRIPT Box (ARCHER on Left | YOU on Right)
                ArcherTranscriptBox(
                    archerText = archerTranscriptText,
                    userText = userTranscriptText,
                    isListening = voiceState.isListening,
                    isSpeaking = voiceState.isSpeaking,
                    isTyping = typedCommand.isNotBlank(),
                    onReplaySpeech = onReplayLastSpeech,
                    onStopSpeech = onStopSpeaking,
                    onTapTranscript = { onOpenModal(JarvisNodeModal.CHAT_AND_MESSAGES) }
                )

                // 4. Center Stage: 4 Left Pill Nodes (MEMORY, CHAT, SOUL, SETTINGS) + Circuit Traces + 3D Particle Globe + API KEY/STOP/Speaker
                JarvisCircuitAndGlobeStage(
                    isSpeaking = multimodalLiveState.isAgentSpeakingLive || voiceState.isSpeaking,
                    isListening = (multimodalLiveState.isLiveActive && multimodalLiveState.isWebSocketConnected &&
                        !multimodalLiveState.isAgentSpeakingLive) || voiceState.isListening,
                    isThinking = uiState is AgentUiState.Thinking,
                    rmsLevel = maxOf(multimodalLiveState.liveRmsLevel, voiceState.rmsLevel),
                    ttsEnabled = voiceState.ttsEnabled,
                    onOpenNodeModal = onOpenModal,
                    // Globe tap = Gemini Live (main assistant): tap to start, tap again to stop
                    onGlobeTap = { requestOrToggleMultimodalLive() },
                    onStopClick = {
                        if (multimodalLiveState.isLiveActive) requestOrToggleMultimodalLive()
                        else {
                            onStopSpeaking()
                            onStopVoiceListening()
                        }
                    },
                    onToggleTts = onToggleTts,
                    onOpenApiKeyClick = { showQuickGeminiKeyDialog = true }
                )

                // 5. TODAY TASKS & 5TB CLOUD BOT ACTIVITY
                ArcherTodayTasksCard(
                    tasks = todayTasks,
                    onToggleTask = onToggleTodayTask,
                    onOpenTasksModal = { onOpenModal(JarvisNodeModal.CHAT_AND_MESSAGES) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 5B. Hands-Free Speech-to-Text (RecognitionListener) Control Bar
            ArcherHandsFreeVoiceControlStrip(
                voiceState = voiceState,
                onToggleHandsFree = {
                    val hasMicPerm = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMicPerm) {
                        onToggleHandsFreeVoice()
                    } else {
                        onToggleHandsFreeVoice()
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onCycleLanguage = onCycleSttLanguage,
                onTriggerVoiceNow = requestOrToggleVoiceListening
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 6. Visible Archer Cyber Typing & Auto-Type Bar right at the bottom of the main screen
            ArcherCyberTypingBar(
                typedText = typedCommand,
                onTypedTextChange = { typedCommand = it },
                isDirectAutoTypeMode = isDirectScreenAutoTypeMode,
                onToggleAutoTypeMode = { isDirectScreenAutoTypeMode = !isDirectScreenAutoTypeMode },
                isListening = voiceState.isListening,
                isThinking = uiState is AgentUiState.Thinking,
                onMicClick = {
                    requestOrToggleVoiceListening()
                },
                onSendClick = {
                    val trimmed = typedCommand.trim()
                    if (trimmed.isNotBlank()) {
                        if (isDirectScreenAutoTypeMode) {
                            onTestScreenMacro("TYPE", trimmed)
                            onSendCommand("স্ক্রিনে টাইপ করো এবং পাঠাও: $trimmed")
                        } else {
                            onSendCommand(trimmed)
                        }
                        typedCommand = ""
                        focusManager.clearFocus()
                    }
                }
            )
        }

        // Modal Overlays for MEMORY, CHAT, SOUL, SETTINGS, LIVE CAMERA/SCREEN, HEADLINE DETAIL
        when (activeModal) {
            JarvisNodeModal.MEMORY_AND_IDS -> {
                MemoryAndPlatformIdsSheet(
                    memories = memoryNotes,
                    accounts = platformAccounts,
                    onAddAccount = onSavePlatformAccount,
                    onDeleteAccount = onDeletePlatformAccount,
                    onAddMemory = onAddMemoryNote,
                    onDeleteMemory = onDeleteMemoryNote,
                    onRunCommand = onSendCommand,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.CHAT_AND_MESSAGES -> {
                ChatAndUniversalCrossAppSheet(
                    notificationState = notificationState,
                    plans = plans,
                    voiceState = voiceState,
                    onToggleVoiceListening = requestOrToggleVoiceListening,
                    onSendCommand = { cmd ->
                        onSendCommand(cmd)
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onInstallFromPlayStore = { query ->
                        onInstallFromPlayStore(query)
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onChromeAction = { urlOrFile ->
                        onChromeAction(urlOrFile)
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onTelegramDownload = { channel, fileQuery ->
                        onTelegramDownload(channel, fileQuery)
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onQuickReplyNotification = onQuickReplyNotification,
                    onExecuteStep = onExecuteStep,
                    onSpeakText = onSpeakText,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.SOUL_AND_WAKE -> {
                SoulAndMusicWakeSheet(
                    sentinelState = sentinelState,
                    onToggleSentinel = onToggleAlwaysOnSentinel,
                    onSimulateHeyCube = onSimulateHeyCube,
                    onSelectMood = onSelectMood,
                    onTogglePlayMusic = onTogglePlayMusic,
                    onOpenExternalMusic = onOpenExternalMusic,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) },
                    activePersonality = activePersonality,
                    activeVoicePreset = activeVoicePreset,
                    micSensitivityBoost = micSensitivityBoost,
                    customPersonalityPrompt = customPersonalityPrompt,
                    proactiveAlertsEnabled = proactiveAlertsEnabled,
                    proactiveWatchTasks = proactiveWatchTasks,
                    onSelectPersonality = onSelectPersonality,
                    onSelectVoicePreset = onSelectVoicePreset,
                    onChangeMicSensitivityBoost = onChangeMicSensitivityBoost,
                    onSaveCustomPersonality = onSaveCustomPersonality,
                    onToggleProactiveAlerts = onToggleProactiveAlerts,
                    onStartProactiveWatch = onStartProactiveWatch,
                    onCancelProactiveWatch = onCancelProactiveWatch
                )
            }

            JarvisNodeModal.SETTINGS_AND_PERMS -> {
                SettingsAndPermissionsSheet(
                    accessibilityState = accessibilityState,
                    notificationState = notificationState,
                    hardwareState = hardwareState,
                    voiceState = voiceState,
                    geminiApiKey = geminiApiKey,
                    onSaveGeminiApiKey = onSaveGeminiApiKey,
                    onToggleTts = onToggleTts,
                    onTestTtsVoice = onTestTtsVoice,
                    onChangeSpeechRate = onChangeSpeechRate,
                    onChangeSpeechPitch = onChangeSpeechPitch,
                    onSelectVoiceProfile = onSelectVoiceProfile,
                    onToggleHandsFreeStt = { enabled ->
                        onSetHandsFreeVoice(enabled)
                        if (enabled) {
                            val hasMicPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasMicPerm) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    onSelectSttLanguage = onSelectSttLanguage,
                    onTriggerVoiceListen = requestOrToggleVoiceListening,
                    lockedReplyLanguage = lockedReplyLanguage,
                    persistentUserRule = persistentUserRule,
                    onSetLockedLanguage = onSetLockedLanguage,
                    onSavePersistentRule = onSavePersistentRule,
                    onToggleQuickSettingTile = onToggleQuickSettingTile,
                    hasContactsAndCallPermission = hasContactsAndCallPerm,
                    onRequestContactsAndCallPermission = {
                        contactsAndCallPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.CALL_PHONE
                            )
                        )
                    },
                    onOpenCodingWorkspace = {
                        onOpenModal(JarvisNodeModal.CODING_AND_ARGON)
                    },
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenNotificationSettings = onOpenNotificationListenerSettings,
                    onToggleFlashlight = onToggleFlashlight,
                    onVolumeChange = onVolumeChange,
                    onOpenSystemSetting = onOpenSystemSetting,
                    onTestScreenMacro = onTestScreenMacro,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.CODING_AND_ARGON -> {
                CodingAndArgonWorkspaceSheet(
                    workspaceState = codingWorkspaceState,
                    onSelectModel = onSelectAiModel,
                    onToggleAutoRouting = onToggleAutoModelRouting,
                    onGenerateCodeWithAi = { prompt ->
                        onSendCommand(prompt)
                    },
                    onSaveManualCodeToArcher = onSaveManualCodeToArcher,
                    onShareArtifact = onShareArcherArtifact,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.LIVE_CAMERA_VISION -> {
                LiveCameraAndScreenVisionSheet(
                    isFrontCamera = isFrontCameraSelected,
                    visibleScreenText = accessibilityState.visibleScreenText,
                    onFlipCamera = onToggleCameraFacing,
                    onLaunchSystemCamera = onLaunchSystemCamera,
                    onAnalyzeScreenWithAi = {
                        onAnalyzeScreenWithAi()
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onSendVisionPrompt = { prompt ->
                        onSendCommand(prompt)
                        onOpenModal(JarvisNodeModal.NONE)
                    },
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.MULTIMODAL_LIVE_API_HUB -> {
                MultimodalLiveApiHubSheet(
                    liveState = multimodalLiveState,
                    customApiKey = geminiApiKey,
                    onSaveCustomApiKey = onSaveGeminiApiKey,
                    onSelectEngineMode = onSelectLiveEngineMode,
                    onToggleLiveSession = requestOrToggleMultimodalLive,
                    onCopyConfigItem = onCopyLiveApiConfigItem,
                    onExportLiveApiToArcherFolder = onExportLiveApiBundleToArcher,
                    onClose = { onOpenModal(JarvisNodeModal.NONE) }
                )
            }

            JarvisNodeModal.NONE -> Unit
        }

        if (showQuickGeminiKeyDialog) {
            QuickGeminiApiKeyModal(
                currentKey = geminiApiKey,
                onSaveKey = { newKey ->
                    onSaveGeminiApiKey(newKey)
                    showQuickGeminiKeyDialog = false
                },
                onTestKeyWithPrompt = { newKey ->
                    onSaveGeminiApiKey(newKey)
                    showQuickGeminiKeyDialog = false
                    onSendCommand("তোমার জেমিনি এআই ব্রেইন কি সক্রিয় হয়েছে? সংক্ষেপে জানাও।")
                },
                onOpenFullLiveHub = {
                    showQuickGeminiKeyDialog = false
                    onOpenModal(JarvisNodeModal.MULTIMODAL_LIVE_API_HUB)
                },
                onDismiss = { showQuickGeminiKeyDialog = false }
            )
        }

        // Full-Screen Lock-Screen Wake HUD Overlay
        AnimatedVisibility(
            visible = sentinelState.isLockScreenHudMode,
            modifier = Modifier.fillMaxSize()
        ) {
            LockScreenCubeWakeOverlay(
                sentinelState = sentinelState,
                voiceState = voiceState,
                onTriggerHeyCube = onSimulateHeyCube,
                onStartListening = {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onQuickVoiceCommand = { cmd ->
                    CubeSentinelBus.setLockScreenHudMode(false)
                    onSendCommand(cmd)
                },
                onExitLockScreenMode = {
                    CubeSentinelBus.setLockScreenHudMode(false)
                }
            )
        }
    }
}

@Composable
private fun ArcherHandsFreeVoiceControlStrip(
    voiceState: VoiceBridgeState,
    onToggleHandsFree: () -> Unit,
    onCycleLanguage: () -> Unit,
    onTriggerVoiceNow: () -> Unit
) {
    val stripShape = RoundedCornerShape(12.dp)
    val shortLangBadge = when {
        voiceState.languageCode.startsWith("bn") -> "বাংলা"
        voiceState.languageCode.startsWith("hi") -> "हिन्दी"
        else -> "ENG"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(stripShape)
            .background(
                if (voiceState.isListening) Color(0xFF1A0911)
                else Color(0xFF061A12)
            )
            .border(
                width = 1.1.dp,
                color = when {
                    voiceState.isListening -> PulseRose
                    voiceState.isHandsFreeContinuous -> MatrixGreen
                    else -> CyberCyan.copy(alpha = 0.55f)
                },
                shape = stripShape
            )
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("hands_free_voice_strip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 1. Hands-Free Continuous Mode Toggle Pill
        Surface(
            color = if (voiceState.isHandsFreeContinuous) MatrixGreen else Color(0xFF0B241A),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(
                1.dp,
                if (voiceState.isHandsFreeContinuous) MatrixGreen else CyberCyan.copy(alpha = 0.6f)
            ),
            modifier = Modifier
                .clickable { onToggleHandsFree() }
                .testTag("hands_free_mode_toggle_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Hands-Free Voice Mode",
                    tint = if (voiceState.isHandsFreeContinuous) Color(0xFF02140B) else MatrixGreen,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (voiceState.isHandsFreeContinuous) "HANDS-FREE ON" else "HANDS-FREE",
                    color = if (voiceState.isHandsFreeContinuous) Color(0xFF02140B) else MatrixGreen,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 8.8.sp
                )
            }
        }

        // 2. Live STT Status / Partial Transcript Preview
        Text(
            text = when {
                voiceState.isListening && voiceState.partialTranscript.isNotBlank() ->
                    "🎙️ \"${voiceState.partialTranscript}\""
                voiceState.isListening ->
                    "● শুনছি (${shortLangBadge})... এখন মুখে আদেশ বলুন"
                voiceState.isHandsFreeContinuous ->
                    "হ্যান্ডস-ফ্রি মাইক প্রস্তুত — টাইপ ছাড়াই কথা বলুন"
                else ->
                    voiceState.statusHint
            },
            color = if (voiceState.isListening) Color.White else Color(0xFFC8F7E5),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 3. Language Switcher Pill (বাংলা / ENG / हिन्दी)
        Surface(
            color = Color(0xFF082226),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.75f)),
            modifier = Modifier
                .clickable { onCycleLanguage() }
                .testTag("stt_language_cycle_btn")
        ) {
            Text(
                text = "🌐 $shortLangBadge",
                color = CyberCyan,
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 8.8.sp,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
            )
        }

        // 4. Direct Voice Trigger Pill ("বলুন / থামান")
        Surface(
            color = if (voiceState.isListening) PulseRose else CyberCyan,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .clickable { onTriggerVoiceNow() }
                .testTag("hands_free_speak_now_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = "Speak Command",
                    tint = if (voiceState.isListening) Color.White else Color(0xFF021619),
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = if (voiceState.isListening) "থামান" else "বলুন",
                    color = if (voiceState.isListening) Color.White else Color(0xFF021619),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun ArcherCyberTypingBar(
    typedText: String,
    onTypedTextChange: (String) -> Unit,
    isDirectAutoTypeMode: Boolean,
    onToggleAutoTypeMode: () -> Unit,
    isListening: Boolean,
    isThinking: Boolean,
    onMicClick: () -> Unit,
    onSendClick: () -> Unit
) {
    val barShape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(Color(0xFF050F0B))
            .border(1.4.dp, MatrixGreen.copy(alpha = 0.85f), barShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Toggle between AI Command Typing & Direct Screen Auto-Typing
        Surface(
            color = if (isDirectAutoTypeMode) MatrixGreen else Color(0xFF0B2219),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, if (isDirectAutoTypeMode) MatrixGreen else CyberCyan.copy(alpha = 0.6f)),
            modifier = Modifier
                .clickable { onToggleAutoTypeMode() }
                .testTag("toggle_auto_type_mode_btn")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = "Auto-Type Mode",
                    tint = if (isDirectAutoTypeMode) Color.Black else MatrixGreen,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isDirectAutoTypeMode) "AUTO-TYPE" else "TYPE",
                    color = if (isDirectAutoTypeMode) Color.Black else MatrixGreen,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.5.sp
                )
            }
        }

        OutlinedTextField(
            value = typedText,
            onValueChange = onTypedTextChange,
            placeholder = {
                Text(
                    text = if (isDirectAutoTypeMode) {
                        "যেকোনো অ্যাপে অটো-টাইপ করতে লিখুন..."
                    } else {
                        "এখানে টাইপ করুন বা আদেশ লিখুন (AI নিজে সিদ্ধান্ত নেবে)..."
                    },
                    fontSize = 11.sp,
                    color = Color(0xFF88B4A8),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendClick() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MatrixGreen,
                unfocusedBorderColor = Color(0xFF1D4234),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = MatrixGreen
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("agent_command_input")
        )

        IconButton(
            onClick = onMicClick,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isListening) PulseRose else Color(0xFF0C261C))
                .border(1.dp, if (isListening) PulseRose else CyberCyan, CircleShape)
                .testTag("voice_mic_button")
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = "Voice",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(
            onClick = onSendClick,
            enabled = typedText.isNotBlank() && !isThinking,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (typedText.isNotBlank()) MatrixGreen else Color(0xFF0C2219))
                .testTag("send_command_button")
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (typedText.isNotBlank()) Color(0xFF02120A) else Color(0xFF527A6E),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ArcherEmeraldCyberBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 1. Base vertical deep emerald-green gradient
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF0E3921),
                    Color(0xFF072314),
                    Color(0xFF04140B),
                    Color(0xFF020B06),
                    Color(0xFF010603)
                )
            )
        )

        // 2. Top-center emerald-green ambient radial spotlight
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    MatrixGreen.copy(alpha = 0.22f),
                    Color(0xFF0B3B21).copy(alpha = 0.14f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.5f, size.height * 0.14f),
                radius = size.width * 0.85f
            ),
            radius = size.width * 0.85f,
            center = Offset(size.width * 0.5f, size.height * 0.14f)
        )

        // 3. Center-right emerald-cyan holographic bloom behind the 3D globe
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    MatrixGreen.copy(alpha = 0.20f),
                    CyberCyan.copy(alpha = 0.12f),
                    Color(0xFF052E19).copy(alpha = 0.10f),
                    Color.Transparent
                ),
                center = Offset(size.width * 0.68f, size.height * 0.54f),
                radius = size.width * 0.72f
            ),
            radius = size.width * 0.72f,
            center = Offset(size.width * 0.68f, size.height * 0.54f)
        )

        // 4. Subtle Cyber-Emerald Matrix Grid Lines across the background
        val gridSpacing = 28.dp.toPx()
        val gridColor = MatrixGreen.copy(alpha = 0.048f)
        var x = 0f
        while (x < size.width) {
            drawLine(
                color = gridColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 0.8.dp.toPx()
            )
            x += gridSpacing
        }
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 0.8.dp.toPx()
            )
            y += gridSpacing
        }

        // 5. Subtle floating emerald/cyan ambient stars in the background
        val starOffsets = listOf(
            Offset(0.12f, 0.22f),
            Offset(0.86f, 0.19f),
            Offset(0.28f, 0.34f),
            Offset(0.74f, 0.31f),
            Offset(0.18f, 0.68f),
            Offset(0.91f, 0.72f),
            Offset(0.44f, 0.79f),
            Offset(0.62f, 0.86f)
        )
        starOffsets.forEachIndexed { idx, rel ->
            drawCircle(
                color = if (idx % 2 == 0) MatrixGreen.copy(alpha = 0.28f) else CyberCyan.copy(alpha = 0.24f),
                radius = 1.6.dp.toPx(),
                center = Offset(size.width * rel.x, size.height * rel.y)
            )
        }
    }
}

@Composable
private fun ArcherTopHeaderBar(
    isSentinelActive: Boolean,
    hasCustomGeminiKey: Boolean,
    onOpenGeminiKeyDialog: () -> Unit,
    onTapLogoWake: () -> Unit,
    onOpenSettingsModal: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color(0xFF0A2918),
                        Color(0xFF103822),
                        Color(0xFF0A2918)
                    )
                )
            )
            .border(1.dp, MatrixGreen.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        // Left: Green History/Clock Circle + Gemini Key Pill
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clickable { onOpenGeminiKeyDialog() }
                .testTag("top_gemini_key_btn"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = "History & API Key",
                tint = MatrixGreen,
                modifier = Modifier.size(22.dp)
            )
            Surface(
                color = if (hasCustomGeminiKey) MatrixGreen.copy(alpha = 0.22f) else Color(0xFF071F12),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.65f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        tint = if (hasCustomGeminiKey) MatrixGreen else CyberCyan,
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = if (hasCustomGeminiKey) "KEY ✓" else "KEY",
                        color = Color.White,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 8.5.sp
                    )
                }
            }
        }

        // Center: Glowing Robot/Delta Icon + ARCHER AI title (exact match to screenshot)
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clickable { onTapLogoWake() }
                .testTag("hey_cube_top_badge"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = "Archer AI Core",
                tint = if (isSentinelActive) MatrixGreen else CyberCyan,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = "ARCHER AI",
                color = Color(0xFFE8FFF4),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.5.sp,
                letterSpacing = 1.8.sp
            )
        }

        // Right: Solid Bright Neon-Green Rounded Square Bug/Chip Settings Button (exact match to screenshot)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(34.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MatrixGreen)
                .clickable { onOpenSettingsModal() }
                .testTag("top_settings_gear_btn")
        ) {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = "Settings",
                tint = Color(0xFF03180C),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun QuickGeminiApiKeyModal(
    currentKey: String,
    onSaveKey: (String) -> Unit,
    onTestKeyWithPrompt: (String) -> Unit,
    onOpenFullLiveHub: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var draftKey by rememberSaveable(currentKey) { mutableStateOf(currentKey) }
    var statusMessage by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val textToolbar = androidx.compose.ui.platform.LocalTextToolbar.current

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            runCatching { textToolbar.hide() }
            runCatching { focusManager.clearFocus(force = true) }
        }
    }

    val safeDismiss = {
        runCatching { textToolbar.hide() }
        runCatching { focusManager.clearFocus(force = true) }
        onDismiss()
    }

    val pasteFromClipboard: () -> Unit = {
        runCatching {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            if (clipText.isNotBlank()) {
                draftKey = clipText
                statusMessage = "✓ ক্লিপবোর্ড থেকে API Key পেস্ট করা হয়েছে! এখন 'সেভ করুন' চাপুন।"
            } else {
                statusMessage = "⚠ ক্লিপবোর্ডে কোনো টেক্সট পাওয়া যায়নি। নিচে বক্সে টাইপ বা পেস্ট করুন।"
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020A14))
            .padding(16.dp)
            .testTag("quick_gemini_key_modal")
    ) {
        Surface(
            color = Color(0xFF071526),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(
                width = 2.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(CyberCyan, MatrixGreen, Color(0xFF2979FF))
                )
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = MatrixGreen,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "🔑 GEMINI API KEY এখানে বসান",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp,
                                letterSpacing = 0.6.sp
                            )
                            Text(
                                text = if (currentKey.isNotBlank()) {
                                    "সক্রিয় কি: ${currentKey.take(10)}... (কাস্টম কি চালু আছে)"
                                } else {
                                    "বিল্ট-ইন ক্লাউড AI চালু • চাইলে নিজের AIza... কি বসান"
                                },
                                color = CyberCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        color = Color(0xFF2B0916),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, PulseRose),
                        modifier = Modifier.clickable { safeDismiss() }
                    ) {
                        Text(
                            text = "✕ বন্ধ",
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                Text(
                    text = "নিচের বক্সে ট্যাপ করে আপনার Google AI Studio-এর Gemini API Key (AIzaSy...) টাইপ করুন অথবা '📋 ক্লিপবোর্ড থেকে পেস্ট' বাটনে ট্যাপ করুন:",
                    color = Color(0xFFD7F7EA),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                OutlinedTextField(
                    value = draftKey,
                    onValueChange = {
                        draftKey = it
                        statusMessage = ""
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
                            text = "AIzaSy... (খালি রাখলে বিল্ট-ইন ক্লাউড AI চলবে)",
                            color = Color(0xFF90B8D8),
                            fontSize = 11.sp
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MatrixGreen,
                        unfocusedBorderColor = CyberCyan,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = MatrixGreen,
                        focusedContainerColor = Color(0xFF0B2038),
                        unfocusedContainerColor = Color(0xFF08182B)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("quick_gemini_key_input")
                )

                // Quick Clipboard Paste + Clear Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = pasteFromClipboard,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2979FF),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("quick_paste_gemini_key_btn")
                    ) {
                        Text("📋 ক্লিপবোর্ড থেকে পেস্ট", fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            draftKey = ""
                            statusMessage = "বক্স খালি করা হয়েছে।"
                        },
                        border = BorderStroke(1.2.dp, PulseRose),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(0.7f)
                    ) {
                        Text("🗑️ ক্লিয়ার", color = PulseRose, fontWeight = FontWeight.ExtraBold, fontSize = 11.5.sp)
                    }
                }

                if (statusMessage.isNotBlank()) {
                    Text(
                        text = statusMessage,
                        color = MatrixGreen,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            runCatching { textToolbar.hide() }
                            runCatching { focusManager.clearFocus(force = true) }
                            onSaveKey(draftKey.trim())
                            android.widget.Toast.makeText(
                                context,
                                if (draftKey.isNotBlank()) "✓ আপনার Gemini API Key সেভ হয়েছে!" else "✓ বিল্ট-ইন ক্লাউড AI সক্রিয় করা হয়েছে!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MatrixGreen,
                            contentColor = Color(0xFF03180E)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_save_gemini_key_btn")
                    ) {
                        Text("✅ API Key সেভ করুন", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            runCatching { textToolbar.hide() }
                            runCatching { focusManager.clearFocus(force = true) }
                            onTestKeyWithPrompt(draftKey.trim())
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCyan,
                            contentColor = Color(0xFF02161C)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_test_gemini_key_btn")
                    ) {
                        Text("⚡ সেভ ও টেস্ট AI", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            runCatching { textToolbar.hide() }
                            runCatching { focusManager.clearFocus(force = true) }
                            draftKey = ""
                            onSaveKey("")
                        },
                        border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("বিল্ট-ইন ক্লাউড AI চালান", color = CyberCyan, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onOpenFullLiveHub,
                        border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("🌐 Live WS ও Key স্টুডিও", color = MatrixGreen, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArcherBeveledTopButton(
    label: String,
    icon: ImageVector,
    hasDoubleFrame: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outerCut = CutCornerShape(
        topStart = 10.dp,
        topEnd = 10.dp,
        bottomStart = 10.dp,
        bottomEnd = 10.dp
    )
    val innerCut = CutCornerShape(
        topStart = 8.dp,
        topEnd = 8.dp,
        bottomStart = 8.dp,
        bottomEnd = 8.dp
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(45.dp)
            .clip(outerCut)
            .background(
                if (hasDoubleFrame) {
                    Brush.linearGradient(
                        colors = listOf(
                            MatrixGreen.copy(alpha = 0.85f),
                            CyberCyan.copy(alpha = 0.85f),
                            Color(0xFFE8FFF6).copy(alpha = 0.9f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            CyberCyan.copy(alpha = 0.45f),
                            MatrixGreen.copy(alpha = 0.45f)
                        )
                    )
                }
            )
            .border(
                width = if (hasDoubleFrame) 1.8.dp else 1.4.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(Color(0xFFE0FFF2), CyberCyan, MatrixGreen)
                ),
                shape = outerCut
            )
            .clickable { onClick() }
            .padding(if (hasDoubleFrame) 2.5.dp else 1.5.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(innerCut)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0E2920),
                            Color(0xFF06130E)
                        )
                    )
                )
                .border(1.dp, CyberCyan.copy(alpha = 0.65f), innerCut)
                .padding(horizontal = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MatrixGreen,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 9.5.sp,
                    letterSpacing = 0.6.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArcherTranscriptBox(
    archerText: String,
    userText: String,
    isListening: Boolean,
    isSpeaking: Boolean,
    isTyping: Boolean,
    onReplaySpeech: () -> Unit,
    onStopSpeech: () -> Unit,
    onTapTranscript: () -> Unit
) {
    val boxShape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(boxShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B301E),
                        Color(0xFF061B11),
                        Color(0xFF030F09)
                    )
                )
            )
            .border(
                width = 1.8.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(MatrixGreen, CyberCyan, MatrixGreen)
                ),
                shape = boxShape
            )
            .clickable { onTapTranscript() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag("jarvis_transcript_box")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "TRANSCRIPT",
                    color = MatrixGreen,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.3.sp
                )

                Surface(
                    color = if (isSpeaking) PulseRose.copy(alpha = 0.25f) else Color(0xFF08291D),
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(
                        1.dp,
                        if (isSpeaking) PulseRose else CyberCyan.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier
                        .clickable {
                            if (isSpeaking) {
                                onStopSpeech()
                            } else {
                                onReplaySpeech()
                            }
                        }
                        .testTag("transcript_tts_replay_btn")
                ) {
                    Text(
                        text = if (isSpeaking) "⏹ থামান" else "🔊 শুনুন",
                        color = if (isSpeaking) Color.White else CyberCyan,
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 8.8.sp,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.5.dp)
                    )
                }
            }

            Text(
                text = when {
                    isSpeaking -> "🔊 VOICE SPEAKING"
                    isListening -> "● LIVE MIC"
                    isTyping -> "● LIVE TYPING"
                    else -> "TAP TO OPEN CONSOLE"
                },
                color = when {
                    isSpeaking -> CyberCyan
                    isListening -> PulseRose
                    isTyping -> CyberCyan
                    else -> MatrixGreen.copy(alpha = 0.75f)
                },
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1.1f)) {
                Text(
                    text = "ARCHER",
                    color = MatrixGreen.copy(alpha = 0.9f),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.9.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = archerText,
                    color = MatrixGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(0.95f),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = "YOU",
                    color = CyberCyan,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.9.sp,
                    textAlign = TextAlign.End
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = userText,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.End,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ArcherSystemTelemetryStrip(
    batteryPercent: Int,
    isWifiOnline: Boolean
) {
    val stripShape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(stripShape)
            .background(Color(0xFF061E12))
            .border(1.dp, MatrixGreen.copy(alpha = 0.45f), stripShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.BatteryChargingFull,
                contentDescription = null,
                tint = MatrixGreen,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = "BAT ${batteryPercent}% ⚡",
                color = MatrixGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.5.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = CyberCyan,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "RAM 2.4GB FREE",
                color = CyberCyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.5.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = Color(0xFFFF9100),
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (isWifiOnline) "WI-FI ONLINE" else "OFFLINE",
                color = Color(0xFFFF9100),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 9.5.sp
            )
        }
    }
}

@Composable
private fun ArcherMessageSignalBar(
    latestSender: String,
    latestBody: String,
    onAskWhoSentMessage: () -> Unit,
    onAskWhatIsMessage: () -> Unit,
    onTestIncomingCall: () -> Unit
) {
    val barShape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(barShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF072215),
                        Color(0xFF04140C)
                    )
                )
            )
            .border(1.2.dp, MatrixGreen.copy(alpha = 0.55f), barShape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = "💬 NEW MESSAGE SIGNAL WAITING (বলুন: 'কোথা থেকে মেসেজ আসছে?')",
            color = Color.White,
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 9.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Pill 1: ১. কোথা থেকে মেসেজ আসছে?
            Surface(
                color = Color(0xFF062326),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.1.dp, CyberCyan),
                modifier = Modifier
                    .weight(1.25f)
                    .clickable { onAskWhoSentMessage() }
                    .testTag("signal_btn_who_sent")
            ) {
                Text(
                    text = "১. কোথা থেকে মেসেজ আসছে?",
                    color = CyberCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.8.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }

            // Pill 2: ২. কী মেসেজ?
            Surface(
                color = Color(0xFF072816),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.1.dp, MatrixGreen),
                modifier = Modifier
                    .weight(0.85f)
                    .clickable { onAskWhatIsMessage() }
                    .testTag("signal_btn_what_message")
            ) {
                Text(
                    text = "২. কী মেসেজ?",
                    color = MatrixGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)
                )
            }

            // Pill 3: 📞 টেস্ট ইনকামিং কল
            Surface(
                color = Color(0xFF241606),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.1.dp, Color(0xFFFF9100)),
                modifier = Modifier
                    .weight(1.1f)
                    .clickable { onTestIncomingCall() }
                    .testTag("signal_btn_test_call")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        tint = Color(0xFFFF9100),
                        modifier = Modifier.size(11.dp)
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Text(
                        text = "টেস্ট ইনকামিং কল",
                        color = Color(0xFFFF9100),
                        fontWeight = FontWeight.Bold,
                        fontSize = 8.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ArcherTodayTasksCard(
    tasks: List<TodayTaskEntity>,
    onToggleTask: (TodayTaskEntity) -> Unit,
    onOpenTasksModal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardShape = RoundedCornerShape(16.dp)
    val leftCount = tasks.count { !it.isCompleted }.coerceAtLeast(0)
    val completedCount = tasks.count { it.isCompleted }
    val totalCount = tasks.size.coerceAtLeast(1)
    val progress = ((completedCount + 1).toFloat() / (totalCount + 1).toFloat()).coerceIn(0.35f, 1f)

    Column(
        modifier = modifier
            .clip(cardShape)
            .background(Color(0xFF060E0B))
            .border(1.2.dp, Color(0xFF90B4AE).copy(alpha = 0.7f), cardShape)
            .padding(horizontal = 12.dp, vertical = 12.dp)
            .testTag("today_tasks_panel"),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenTasksModal() },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TODAY TASKS",
                    color = Color.White,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "${leftCount} left",
                    color = Color(0xFFB0CFCB),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 3.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.LocalFireDepartment,
                    contentDescription = null,
                    tint = Color(0xFFFF9100),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "1",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp)
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = CyberCyan,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
        ) {
            val y = size.height / 2f
            drawLine(
                color = Color(0xFF1A3330),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            val activeEndX = size.width * progress
            drawLine(
                color = CyberCyan,
                start = Offset(0f, y),
                end = Offset(activeEndX, y),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = CyberCyan.copy(alpha = 0.45f),
                radius = 6.dp.toPx(),
                center = Offset(activeEndX * 0.45f, y)
            )
            drawCircle(
                color = Color.White,
                radius = 2.5.dp.toPx(),
                center = Offset(activeEndX * 0.45f, y)
            )
        }

        tasks.take(4).forEach { task ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleTask(task) }
                    .padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (task.isCompleted) MatrixGreen else Color.Transparent)
                        .border(1.4.dp, CyberCyan, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = task.title,
                    color = if (task.isCompleted) Color(0xFF7B9E99) else Color(0xFFEAF7F5),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LockScreenCubeWakeOverlay(
    sentinelState: CubeSentinelState,
    voiceState: VoiceBridgeState,
    onTriggerHeyCube: () -> Unit,
    onStartListening: () -> Unit,
    onQuickVoiceCommand: (String) -> Unit,
    onExitLockScreenMode: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF020806), Color(0xFF071A12), Color(0xFF020604))
                )
            )
            .padding(24.dp)
            .testTag("lock_screen_wake_overlay")
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ARCHER AI LOCK-SCREEN SENTINEL",
                    style = MaterialTheme.typography.labelLarge,
                    color = MatrixGreen
                )
                OutlinedButton(
                    onClick = onExitLockScreenMode,
                    border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.4f))
                ) {
                    Icon(
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = MatrixGreen,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("আনলক ভিউ", color = MatrixGreen)
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(150.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    CyberCyan.copy(alpha = 0.45f),
                                    MatrixGreen.copy(alpha = 0.2f),
                                    Color.Transparent
                                )
                            )
                        )
                        .clickable { onTriggerHeyCube() },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(MatrixGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Archer Core",
                            tint = Color(0xFF04141C),
                            modifier = Modifier.size(44.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "ডাকুন: \"Hey Cube\" / \"Archer\"",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    color = Color(0xFF091B14),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "ARCHER AI:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MatrixGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "\"${sentinelState.bossGreeting}\"",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartListening,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (voiceState.isListening) PulseRose else MatrixGreen,
                        contentColor = Color(0xFF04141C)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                ) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (voiceState.isListening) "শুনছি... আপনার আদেশ বলুন" else "মাইকে আদেশ বলুন",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceBiometricsHudPillBar(
    onSpeakStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val bioState by com.example.service.VoiceBiometricsEngine.state.collectAsState()
    var showBossCalibrationDialog by remember { mutableStateOf(false) }
    var showGuestEnrollDialog by remember { mutableStateOf(false) }
    var guestNameInput by remember { mutableStateOf("") }

    val borderColor = when {
        bioState.isEnrolling -> Color(0xFFF59E0B)
        bioState.isOwnerBossVerified && bioState.hasOwnerBossProfile -> MatrixGreen.copy(alpha = 0.78f)
        bioState.isKnownEnrolledSpeaker -> CyberCyan.copy(alpha = 0.78f)
        else -> Color(0xFF334155)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0F19))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("hud_voice_biometrics_pill")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bioState.liveHudBadgeText,
                    color = when {
                        bioState.isEnrolling -> Color(0xFFFBBF24)
                        bioState.isOwnerBossVerified && bioState.hasOwnerBossProfile -> MatrixGreen
                        bioState.isKnownEnrolledSpeaker -> CyberCyan
                        else -> Color(0xFFE2E8F0)
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val activeProfileStyle = bioState.enrolledProfiles
                    .firstOrNull { it.speakerName == bioState.activeSpeakerName }
                    ?.speakingStyleSummaryBn
                    .orEmpty()
                Text(
                    text = buildString {
                        append("কণ্ঠ: ${bioState.detectedGender.labelBn}")
                        if (bioState.currentPitchHz > 0) {
                            append(" • ${bioState.currentPitchHz}Hz")
                        }
                        if (activeProfileStyle.isNotBlank()) {
                            append(" • ধরন: $activeProfileStyle")
                        }
                        append(" • প্রোফাইল: ${bioState.enrolledProfiles.size}টি")
                    },
                    color = Color(0xFF94A3B8),
                    fontSize = 9.3.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { showBossCalibrationDialog = true },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (bioState.hasOwnerBossProfile) MatrixGreen else Color(0xFF38BDF8),
                        contentColor = Color.Black
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("enroll_boss_voice_lock_button")
                ) {
                    Text(
                        text = if (bioState.isEnrolling && bioState.enrollmentIsOwnerBoss) {
                            "⏳ ধাপ ${bioState.enrollmentStepIndex + 1}/৩ (${bioState.enrollmentProgressPercent}%)"
                        } else if (bioState.hasOwnerBossProfile) {
                            "🎙️ বসের ভয়েস ট্রেনিং"
                        } else {
                            "🔒 বসের ভয়েস লক"
                        },
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                OutlinedButton(
                    onClick = { showGuestEnrollDialog = true },
                    border = BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.7f)),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 7.dp, vertical = 2.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("enroll_guest_voice_button")
                ) {
                    Text(
                        text = "➕ বক্তা (${bioState.enrolledProfiles.size})",
                        color = Color(0xFF38BDF8),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (bioState.isEnrolling && bioState.currentCalibrationSentenceBn.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "🗣️ এখন বলুন (ধাপ ${bioState.enrollmentStepIndex + 1}/৩): \"${bioState.currentCalibrationSentenceBn}\"",
                color = Color(0xFFFBBF24),
                fontSize = 10.2.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }

        if (bioState.lastSecurityGateEventBn.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = bioState.lastSecurityGateEventBn,
                color = Color(0xFFF87171),
                fontSize = 9.5.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (showBossCalibrationDialog) {
        AlertDialog(
            onDismissRequest = { showBossCalibrationDialog = false },
            containerColor = Color(0xFF0F172A),
            title = {
                Text(
                    text = "🎙️ স্যার/বসের মেইন ভয়েস লক ও ৩-বাক্য ক্যালিব্রেশন",
                    color = MatrixGreen,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "নিচের বাটনে ট্যাপ করে এই ৩টি বাক্য বা ওয়ার্ডগুলো স্বাভাবিক গলায় বলুন। সিস্টেম আপনার মেইন কণ্ঠস্বর, পিচ (${bioState.currentPitchHz}Hz) ও কথা বলার ধরন চিনে নেবে:",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.2.sp,
                        lineHeight = 15.sp
                    )

                    com.example.service.VoiceBiometricsEngine.BOSS_CALIBRATION_SENTENCES.forEachIndexed { idx, sentence ->
                        val isCurrentStep = bioState.isEnrolling && bioState.enrollmentStepIndex == idx
                        Surface(
                            color = if (isCurrentStep) Color(0xFF1E3A2F) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = if (isCurrentStep) 1.5.dp else 1.dp,
                                color = if (isCurrentStep) MatrixGreen else Color(0xFF334155)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(
                                    text = "বাক্য ${idx + 1}:",
                                    color = if (isCurrentStep) MatrixGreen else CyberCyan,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Text(
                                    text = "\"$sentence\"",
                                    color = Color.White,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Text(
                        text = "🔑 ক্যালিব্রেশন ওয়ার্ডসমূহ: ${com.example.service.VoiceBiometricsEngine.BOSS_CALIBRATION_KEYWORDS.joinToString(" • ")}",
                        color = CyberCyan,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (bioState.isEnrolling) {
                        Surface(
                            color = Color(0xFF1E293B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFBBF24)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "🎙️ কণ্ঠের সিগন্যাল শোনা হচ্ছে: ${bioState.enrollmentProgressPercent}% (পিচ: ${bioState.currentPitchHz}Hz)",
                                    color = Color(0xFFFBBF24),
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF0F172A))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(bioState.liveVoiceRmsLevel.coerceIn(0.08f, 1f))
                                            .height(8.dp)
                                            .background(MatrixGreen)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "🤖 অটো-গেস্ট লার্নিং চালু আছে: অন্য কেউ কথা বললে AI আগে তার নাম জিজ্ঞেস করবে এবং নাম বললেই তার কণ্ঠ ও কথা বলার সব ধরন স্বয়ংক্রিয়ভাবে চিনে Google Drive-এ সেভ করে নেবে!",
                        color = Color(0xFFA7F3D0),
                        fontSize = 10.2.sp,
                        lineHeight = 14.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        com.example.service.VoiceBiometricsEngine.startFiveSecondVoiceEnrollment(
                            context = context,
                            speakerName = "বস",
                            isOwnerBoss = true,
                            onComplete = { msg ->
                                onSpeakStatus(msg)
                            }
                        )
                    },
                    enabled = !bioState.isEnrolling,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MatrixGreen,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.testTag("start_boss_guided_calibration_button")
                ) {
                    Text(
                        text = if (bioState.isEnrolling) "⏳ কণ্ঠ শোনা হচ্ছে (${bioState.enrollmentProgressPercent}%)..." else "🎙️ এখন আমার কণ্ঠ চিনে নাও (Start)",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.5.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBossCalibrationDialog = false }) {
                    Text("বন্ধ করুন", color = Color(0xFF94A3B8))
                }
            }
        )
    }

    if (showGuestEnrollDialog) {
        AlertDialog(
            onDismissRequest = { showGuestEnrollDialog = false },
            containerColor = Color(0xFF1E293B),
            title = {
                Text(
                    text = "🎙️ পরিচিত বক্তার তালিকা ও নতুন কণ্ঠ সেভ",
                    color = Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "অন্য কেউ কথা বলার সময় শুধু নিজের নাম বললেই (যেমন: 'আমার নাম সিয়াম') AI নিজে থেকেই তার কণ্ঠ ও কথা বলার ধরন চিনে নেয়! এছাড়া নিচে নাম লিখে ম্যানুয়ালিও সেভ করতে পারেন:",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.2.sp
                    )
                    OutlinedTextField(
                        value = guestNameInput,
                        onValueChange = { guestNameInput = it },
                        label = { Text("বক্তার নাম (যেমন: সিয়াম, রাহাত, মীম)", color = Color(0xFF94A3B8)) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("guest_voice_name_input")
                    )
                    if (bioState.enrolledProfiles.isNotEmpty()) {
                        Text(
                            text = "সেভ করা ভয়েস প্রিন্ট ও কথা বলার ধরন (${bioState.enrolledProfiles.size}):",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        bioState.enrolledProfiles.forEach { prof ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "• ${prof.speakerName} (${prof.gender.labelBn} • ${prof.meanPitchHz.toInt()}Hz)",
                                        color = if (prof.isOwnerBoss) MatrixGreen else Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "ধরন: ${prof.speakingStyleSummaryBn}" +
                                            if (prof.signatureWords.isNotEmpty()) " | শব্দ: ${prof.signatureWords.take(4).joinToString(", ")}" else "",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 9.5.sp
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        com.example.service.VoiceBiometricsEngine.deleteVoiceProfile(context, prof.id)
                                    }
                                ) {
                                    Text("মুছুন", color = Color(0xFFF87171), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                    if (bioState.hasOwnerBossProfile) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🔒 সংবেদনশীল আদেশে বসের ভয়েস সিকিউরিটি গেট",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                            Switch(
                                checked = bioState.isVoiceSecurityLockEnabled,
                                onCheckedChange = { enabled ->
                                    com.example.service.VoiceBiometricsEngine.setVoiceSecurityLockEnabled(context, enabled)
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val targetName = guestNameInput.trim().ifBlank { "মেহমান" }
                        showGuestEnrollDialog = false
                        guestNameInput = ""
                        com.example.service.VoiceBiometricsEngine.startFiveSecondVoiceEnrollment(
                            context = context,
                            speakerName = targetName,
                            isOwnerBoss = targetName.equals("বস", ignoreCase = true) || targetName.equals("Boss", ignoreCase = true),
                            onComplete = { msg -> onSpeakStatus(msg) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF38BDF8),
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.testTag("confirm_enroll_guest_voice_button")
                ) {
                    Text("🎙️ কণ্ঠ রেকর্ড ও সেভ করুন", fontWeight = FontWeight.ExtraBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuestEnrollDialog = false }) {
                    Text("বন্ধ করুন", color = Color(0xFF94A3B8))
                }
            }
        )
    }
}


