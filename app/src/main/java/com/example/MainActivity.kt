package com.example

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.service.AccessibilityAutomationBus
import com.example.service.CubeSentinelBus
import com.example.service.CubeWakeWordService
import com.example.service.NotificationReplyBus
import com.example.service.ScreenCaptureSessionBus
import com.example.ui.screens.AgentCommandScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.configureAppCheck
import com.example.viewmodel.OmniAgentViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.example.service.TouchEffectBus.load(this)
        com.example.service.FloatingAvatarOverlayBus.init(this)
        com.example.service.FloatingAvatarOverlayBus.setAppSessionAlive(true)
        com.example.service.FloatingAvatarOverlayBus.setAppInForeground(true)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        // Enable lock-screen visibility and screen wake so "Hey Cube / Archer" works even when locked
        enableLockScreenWakeFlags()

        enableEdgeToEdge()

        // STEP 2: Initialize Compose UI with SafeComposeTextToolbar to prevent DecorView FloatingActionMode race errors
        setContent {
            val hostView = LocalView.current
            val safeTextToolbar = remember(hostView) { SafeComposeTextToolbar(hostView) }
            CompositionLocalProvider(LocalTextToolbar provides safeTextToolbar) {
                MyApplicationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFF030A07)
                    ) {
                        OmniAgentApp()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        com.example.service.FloatingAvatarOverlayBus.setAppSessionAlive(true)
        com.example.service.FloatingAvatarOverlayBus.setAppInForeground(true)
    }

    override fun onResume() {
        super.onResume()
        com.example.service.FloatingAvatarOverlayBus.setAppSessionAlive(true)
        com.example.service.FloatingAvatarOverlayBus.setAppInForeground(true)
        AccessibilityAutomationBus.syncPermissionState(this)
        NotificationReplyBus.syncPermissionState(this)
        ScreenCaptureSessionBus.syncSavedPreference(this)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            com.example.service.FloatingAvatarOverlayBus.setAppInForeground(false)
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            com.example.service.AppTaskRemovedShutdownBus.shutdownEverythingOnTaskRemoved(applicationContext)
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureAppCheck(this, intent)
        if (intent.getBooleanExtra(CubeWakeWordService.EXTRA_WOKE_BY_CUBE, false)) {
            CubeSentinelBus.emitWakeWordDetected()
        }
    }

    @Suppress("DEPRECATION")
    private fun enableLockScreenWakeFlags() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            } else {
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {
        }
    }
}

@Composable
fun OmniAgentApp(
    viewModel: OmniAgentViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var returnedFromBackground by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    returnedFromBackground = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.onAppOpenedOrResumed(returnedFromBackground)
                    returnedFromBackground = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onOneTimeScreenCapturePermissionResult(result.resultCode, result.data)
    }

    val driveIntentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        com.example.service.GoogleDriveMemorySyncService.handleAuthorizationIntentResult(
            context = context,
            data = result.data,
            onStatusSpoken = { msg -> viewModel.speakTextAloud(msg) }
        )
    }

    val pendingDriveIntentSender by com.example.service.GoogleDriveMemorySyncService.pendingAuthIntentSender.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDriveIntentSender) {
        val senderReq = pendingDriveIntentSender ?: return@LaunchedEffect
        com.example.service.GoogleDriveMemorySyncService.consumePendingAuthIntentSender()
        try {
            driveIntentSenderLauncher.launch(senderReq)
        } catch (_: Exception) {
        }
    }

    val driveAuthRecoveryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        com.example.service.GoogleDriveMemorySyncService.onAuthRecoveryResult(
            context = context,
            data = result.data,
            onStatusSpoken = { msg -> viewModel.speakTextAloud(msg) }
        )
    }

    val pendingDriveAuthIntent by com.example.service.GoogleDriveMemorySyncService.pendingAuthRecoveryIntent.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDriveAuthIntent) {
        val recoveryIntent = pendingDriveAuthIntent ?: return@LaunchedEffect
        com.example.service.GoogleDriveMemorySyncService.consumePendingAuthRecoveryIntent()
        try {
            driveAuthRecoveryLauncher.launch(recoveryIntent)
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(Unit) {
        ScreenCaptureSessionBus.permissionRequestEvents.collect {
            try {
                val projectionManager =
                    context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            } catch (_: Exception) {
            }
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val plans by viewModel.agentPlans.collectAsStateWithLifecycle()
    val hardwareState by viewModel.hardwareState.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val sentinelState by viewModel.sentinelState.collectAsStateWithLifecycle()
    val accessibilityState by viewModel.accessibilityState.collectAsStateWithLifecycle()
    val notificationState by viewModel.notificationState.collectAsStateWithLifecycle()
    val memoryNotes by viewModel.memoryNotes.collectAsStateWithLifecycle()
    val todayTasks by viewModel.todayTasks.collectAsStateWithLifecycle()
    val platformAccounts by viewModel.platformAccounts.collectAsStateWithLifecycle()
    val activeModal by viewModel.activeModal.collectAsStateWithLifecycle()
    val isFrontCameraSelected by viewModel.isFrontCameraSelected.collectAsStateWithLifecycle()
    val geminiApiKey by viewModel.geminiApiKeyState.collectAsStateWithLifecycle()
    val liveUserTranscript by viewModel.liveUserTranscript.collectAsStateWithLifecycle()
    val liveArcherReply by viewModel.liveArcherReply.collectAsStateWithLifecycle()
    val codingWorkspaceState by viewModel.codingWorkspaceState.collectAsStateWithLifecycle()
    val lockedReplyLanguage by viewModel.lockedReplyLanguage.collectAsStateWithLifecycle()
    val persistentUserRule by viewModel.persistentUserRule.collectAsStateWithLifecycle()
    val multimodalLiveState by viewModel.multimodalLiveState.collectAsStateWithLifecycle()
    val activePersonality by viewModel.activePersonality.collectAsStateWithLifecycle()
    val activeVoicePreset by viewModel.activeVoicePreset.collectAsStateWithLifecycle()
    val micSensitivityBoost by viewModel.micSensitivityBoost.collectAsStateWithLifecycle()
    val customPersonalityPrompt by viewModel.customPersonalityPrompt.collectAsStateWithLifecycle()
    val proactiveAlertsEnabled by viewModel.proactiveAlertsEnabled.collectAsStateWithLifecycle()
    val proactiveWatchTasks by viewModel.proactiveWatchTasks.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = Color(0xFF030A07)
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        AgentCommandScreen(
            uiState = uiState,
            plans = plans,
            liveUserTranscript = liveUserTranscript,
            liveArcherReply = liveArcherReply,
            hardwareState = hardwareState,
            voiceState = voiceState,
            sentinelState = sentinelState,
            accessibilityState = accessibilityState,
            notificationState = notificationState,
            memoryNotes = memoryNotes,
            todayTasks = todayTasks,
            platformAccounts = platformAccounts,
            activeModal = activeModal,
            isFrontCameraSelected = isFrontCameraSelected,
            geminiApiKey = geminiApiKey,
            onSaveGeminiApiKey = { viewModel.updateGeminiApiKey(it) },
            onSendCommand = { viewModel.processUserCommand(it) },
            onSimulateHeyCube = { viewModel.simulateHeyCubeWakeCall() },
            onToggleAlwaysOnSentinel = { viewModel.toggleAlwaysOnCubeSentinel(it) },
            onExecuteStep = { planId, step -> viewModel.executeSingleStep(planId, step) },
            onSelectMood = { viewModel.playMoodImmediately(it) },
            onOpenExternalMusic = { query, artist -> viewModel.openExternalMusicApp(query, artist) },
            onStopSpeaking = { viewModel.stopAllAgentActivity() },
            onTogglePlayMusic = { viewModel.deviceExecutor.toggleSystemMediaPlayPause() },
            onToggleTts = { viewModel.toggleLiveSpeakerMute() },
            onSpeakText = { viewModel.speakTextAloud(it) },
            onReplayLastSpeech = { viewModel.replayLastAgentResponse() },
            onTestTtsVoice = { viewModel.testTtsVoice() },
            onChangeSpeechRate = { viewModel.setTtsSpeechRate(it) },
            onChangeSpeechPitch = { viewModel.setTtsPitch(it) },
            onSelectVoiceProfile = { viewModel.setTtsVoiceProfile(it) },
            onToggleHandsFreeVoice = { viewModel.toggleHandsFreeVoiceMode() },
            onSetHandsFreeVoice = { viewModel.setHandsFreeVoiceMode(it) },
            onCycleSttLanguage = { viewModel.cycleSttLanguage() },
            onSelectSttLanguage = { viewModel.setSttLanguage(it) },
            onExternalSpeechRecognized = { viewModel.onExternalSpeechRecognized(it) },
            codingWorkspaceState = codingWorkspaceState,
            lockedReplyLanguage = lockedReplyLanguage,
            persistentUserRule = persistentUserRule,
            onSetLockedLanguage = { viewModel.setLockedReplyLanguage(it) },
            onSavePersistentRule = { viewModel.setCustomPersistentRule(it) },
            onToggleQuickSettingTile = { target, enable ->
                viewModel.toggleQuickSettingFromUi(target, enable)
            },
            onSelectAiModel = { viewModel.selectAiModel(it) },
            onToggleAutoModelRouting = { viewModel.toggleAutoModelRouting() },
            onSaveManualCodeToArcher = { title, fileName, lang, code ->
                viewModel.saveWorkspaceCodeDirectly(title, fileName, lang, code)
            },
            onShareArcherArtifact = { artifact ->
                viewModel.codingAndContactsManager.shareCodeOrProject(artifact)
            },
            multimodalLiveState = multimodalLiveState,
            onToggleMultimodalLive = { viewModel.onMainGlobeTap() },
            onSelectLiveEngineMode = { viewModel.selectLiveEngineMode(it) },
            onToggleLiveMicMute = { viewModel.toggleLiveMicMute() },
            onToggleLiveCameraStream = { viewModel.toggleLiveCameraStream() },
            onToggleLiveScreenStream = { viewModel.toggleLiveScreenStream() },
            onToggleLiveToolsArmed = { viewModel.toggleLiveToolsArmed() },
            onTriggerLiveBargeIn = { viewModel.triggerLiveBargeIn() },
            onLiveCameraBitmapCaptured = { viewModel.onLiveCameraBitmapCaptured(it) },
            onCopyLiveApiConfigItem = { label, content ->
                viewModel.copyLiveApiConfigItem(label, content)
            },
            onExportLiveApiBundleToArcher = { viewModel.exportLiveApiBundleToArcherFolder() },
            activePersonality = activePersonality,
            activeVoicePreset = activeVoicePreset,
            micSensitivityBoost = micSensitivityBoost,
            customPersonalityPrompt = customPersonalityPrompt,
            proactiveAlertsEnabled = proactiveAlertsEnabled,
            proactiveWatchTasks = proactiveWatchTasks,
            onSelectPersonality = { viewModel.selectPersonality(it) },
            onSelectVoicePreset = { viewModel.selectVoicePreset(it) },
            onChangeMicSensitivityBoost = { viewModel.setMicListeningPower(it) },
            onSaveCustomPersonality = { viewModel.saveCustomPersonalityPrompt(it) },
            onToggleProactiveAlerts = { viewModel.toggleProactiveAlerts(it) },
            onStartProactiveWatch = { watchType, delaySeconds, keyword ->
                viewModel.scheduleProactiveWatchOrTimer(
                    mode = if (watchType.contains("TIMER", ignoreCase = true)) "TIMER" else "WATCH_LOADING",
                    seconds = delaySeconds,
                    label = keyword
                )
            },
            onCancelProactiveWatch = { viewModel.cancelProactiveWatchTask(it) },
            onStartVoiceListening = { viewModel.onMainGlobeTap() },
            onStopVoiceListening = { viewModel.stopAllAgentActivity() },
            onOpenModal = { viewModel.openModal(it) },
            onToggleCameraFacing = { viewModel.toggleCameraLens() },
            onLaunchSystemCamera = {
                viewModel.deviceExecutor.openCameraApp(false)
                viewModel.speakTextAloud("জি স্যার, ক্যামেরা চালু করা হয়েছে।")
            },
            onAddMemoryNote = { title, content ->
                viewModel.addManualMemoryNote(title, content)
            },
            onDeleteMemoryNote = { viewModel.deleteMemoryNote(it) },
            onSavePlatformAccount = { platform, name, handle, notes ->
                viewModel.addPlatformAccount(platform, name, handle, notes)
            },
            onDeletePlatformAccount = { viewModel.deletePlatformAccount(it) },
            onToggleTodayTask = { viewModel.executeTodayTask(it) },
            onOpenAccessibilitySettings = {
                viewModel.deviceExecutor.openSettingsScreen("ACCESSIBILITY")
                viewModel.speakTextAloud("জি স্যার, অ্যাক্সেসিবিলিটি সেটিংস খোলা হয়েছে।")
            },
            onOpenNotificationListenerSettings = {
                viewModel.deviceExecutor.openSettingsScreen("NOTIFICATION_LISTENER")
                viewModel.speakTextAloud("জি স্যার, নোটিফিকেশন সেটিংস খোলা হয়েছে।")
            },
            onToggleFlashlight = { enable ->
                viewModel.deviceExecutor.setFlashlight(enable)
                viewModel.speakTextAloud(
                    if (enable) "জি স্যার, ফ্ল্যাশলাইট চালু করা হয়েছে।" else "জি স্যার, ফ্ল্যাশলাইট বন্ধ করা হয়েছে।"
                )
            },
            onVolumeChange = { stream, pct ->
                val res = viewModel.deviceExecutor.setStreamVolumePercent(stream, pct, "")
                viewModel.speakTextAloud(res)
            },
            onOpenSystemSetting = { target ->
                viewModel.deviceExecutor.openSettingsScreen(target)
                viewModel.speakTextAloud("জি স্যার, $target সেটিংস খোলা হয়েছে।")
            },
            onTestScreenMacro = { cmd, query ->
                val res = viewModel.deviceExecutor.executeUniversalScreenControl(
                    commandType = cmd,
                    targetText = query,
                    inputText = query,
                    targetApp = "",
                    launchExternal = true
                )
                viewModel.speakTextAloud(res)
            },
            onInstallFromPlayStore = { appQuery ->
                val res = viewModel.deviceExecutor.installApkOrAppPackage(appQuery, true)
                viewModel.speakTextAloud(res)
            },
            onChromeAction = { urlOrQuery ->
                viewModel.deviceExecutor.openChromeOrWeb(urlOrQuery, "")
                viewModel.speakTextAloud("জি স্যার, ক্রোম ব্রাউজারে $urlOrQuery খোলা হচ্ছে।")
            },
            onTelegramDownload = { channel, fileQuery ->
                val res = viewModel.deviceExecutor.executeTelegramAutomation(
                    channelOrUser = channel,
                    messageOrSearch = fileQuery,
                    isDownloadRequest = true,
                    launchExternal = true
                )
                viewModel.speakTextAloud(res)
            },
            onQuickReplyNotification = { sender, reply ->
                viewModel.sendQuickNotificationReply(sender, reply)
            },
            onAnalyzeScreenWithAi = {
                viewModel.analyzeCurrentScreenAndSpeak()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
            com.example.ui.components.LiveCodingOverlay()
            com.example.ui.components.DedicatedImageStudioOverlay(
                apiKey = geminiApiKey,
                onStatusSpoken = { msg: String ->
                    viewModel.speakTextAloud(msg)
                }
            )
        }
    }
}

/**
 * Prevents Android's `DecorView: Destroying unexpected ActionMode instance of TYPE_FLOATING` error
 * by ensuring `ActionMode.finish()` is strictly idempotent and never invoked re-entrantly when
 * Compose's `onPasteRequested`/`onCopyRequested`/`onCutRequested` callbacks invoke `hide()`.
 */
private class SafeComposeTextToolbar(private val view: View) : TextToolbar {
    private var actionMode: ActionMode? = null
    private var isFinishingActionMode = false
    private var contentRect: Rect = Rect.Zero
    private var onCopyRequested: (() -> Unit)? = null
    private var onPasteRequested: (() -> Unit)? = null
    private var onCutRequested: (() -> Unit)? = null
    private var onSelectAllRequested: (() -> Unit)? = null

    override var status: TextToolbarStatus = TextToolbarStatus.Hidden
        private set

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            populateMenu(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.clear()
            populateMenu(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            // Detach active reference first so nested `textToolbar.hide()` inside the action callback
            // does not trigger a duplicate `mode.finish()` on DecorView's FloatingActionMode.
            actionMode = null
            status = TextToolbarStatus.Hidden
            isFinishingActionMode = true
            try {
                when (item.itemId) {
                    MENU_COPY -> onCopyRequested?.invoke()
                    MENU_PASTE -> onPasteRequested?.invoke()
                    MENU_CUT -> onCutRequested?.invoke()
                    MENU_SELECT_ALL -> onSelectAllRequested?.invoke()
                    else -> return false
                }
                runCatching { mode.finish() }
            } finally {
                isFinishingActionMode = false
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) {
                actionMode = null
                status = TextToolbarStatus.Hidden
            }
        }

        override fun onGetContentRect(mode: ActionMode, view: View?, outRect: android.graphics.Rect) {
            outRect.set(
                contentRect.left.toInt(),
                contentRect.top.toInt(),
                contentRect.right.toInt(),
                contentRect.bottom.toInt()
            )
        }
    }

    private fun populateMenu(menu: Menu) {
        onCopyRequested?.let {
            menu.add(0, MENU_COPY, 0, android.R.string.copy)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        onPasteRequested?.let {
            menu.add(0, MENU_PASTE, 1, android.R.string.paste)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        onCutRequested?.let {
            menu.add(0, MENU_CUT, 2, android.R.string.cut)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        onSelectAllRequested?.let {
            menu.add(0, MENU_SELECT_ALL, 3, android.R.string.selectAll)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
    }

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        this.contentRect = rect
        this.onCopyRequested = onCopyRequested
        this.onPasteRequested = onPasteRequested
        this.onCutRequested = onCutRequested
        this.onSelectAllRequested = onSelectAllRequested

        val current = actionMode
        if (current == null) {
            status = TextToolbarStatus.Shown
            actionMode = runCatching {
                view.startActionMode(callback, ActionMode.TYPE_FLOATING)
            }.getOrNull()
            if (actionMode == null) {
                status = TextToolbarStatus.Hidden
            }
        } else {
            runCatching {
                current.invalidate()
                current.invalidateContentRect()
            }
        }
    }

    override fun hide() {
        status = TextToolbarStatus.Hidden
        if (isFinishingActionMode) return
        val current = actionMode ?: return
        actionMode = null
        isFinishingActionMode = true
        try {
            runCatching { current.finish() }
        } finally {
            isFinishingActionMode = false
        }
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_CUT = 3
        private const val MENU_SELECT_ALL = 4
    }
}

