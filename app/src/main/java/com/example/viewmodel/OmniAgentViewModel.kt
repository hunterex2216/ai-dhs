package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgentDatabase
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.example.data.CommandHistoryRepository
import com.example.data.PlatformAccountEntity
import com.example.data.TodayTaskEntity
import com.example.model.AgentActionType
import com.example.model.AgentPlanResponse
import com.example.model.AgentStep
import com.example.model.CuratedTrack
import com.example.model.JarvisNodeModal
import com.example.model.MusicMood
import com.example.model.SmartRoutine
import com.example.model.WakeWordPhase
import com.example.service.AccessibilityAutomationBus
import com.example.service.AccessibilityEngineState
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.CubeWakeWordService
import com.example.service.DeviceControlExecutor
import com.example.service.DeviceHardwareSnapshot
import com.example.service.NotificationHubState
import com.example.service.NotificationReplyBus
import com.example.service.VoiceAgentBridge
import com.example.service.VoiceBridgeState
import com.example.service.WakeWordEvent
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

import com.example.model.AgentPersonalityPreset
import com.example.model.AgentVoicePreset
import com.example.model.ProactiveWatchTask

private const val TAG = "OmniAgentViewModel"
private const val PREFS_NAME = "archer_cube_prefs"
private const val KEY_CUSTOM_GEMINI_API_KEY = "custom_gemini_api_key"
private const val KEY_LOCKED_REPLY_LANGUAGE = "locked_reply_language"
private const val KEY_PERSISTENT_USER_RULE = "persistent_user_rule"
private const val KEY_ACTIVE_PERSONALITY = "active_personality_preset"
private const val KEY_ACTIVE_VOICE_PRESET = "active_voice_preset"
private const val KEY_MIC_SENSITIVITY_BOOST = "mic_sensitivity_boost"
private const val KEY_CUSTOM_PERSONALITY_PROMPT = "custom_personality_prompt"
private const val KEY_PROACTIVE_ALERTS_ENABLED = "proactive_alerts_enabled"

sealed interface AgentUiState {
    data object Idle : AgentUiState
    data class Thinking(val commandText: String) : AgentUiState
    data class Success(val plan: AgentPlanResponse) : AgentUiState
    data class Error(val message: String) : AgentUiState
}

class OmniAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs by lazy {
        application.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val historyRepo: CommandHistoryRepository by lazy {
        val db = AgentDatabase.getInstance(application.applicationContext)
        CommandHistoryRepository(
            historyDao = db.commandHistoryDao(),
            memoryDao = db.agentMemoryNoteDao(),
            taskDao = db.todayTaskDao(),
            platformDao = db.platformAccountDao()
        )
    }

    val codingAndContactsManager = com.example.service.ArcherCodingAndContactsManager(application.applicationContext)
    val deviceExecutor = DeviceControlExecutor(
        context = application.applicationContext,
        codingAndContactsManager = codingAndContactsManager,
        onSaveMemoryNoteCallback = { title, content, category ->
            viewModelScope.launch {
                historyRepo.saveMemoryNote(title, content, category)
            }
        },
        onScheduleTaskCallback = { title, prompt, tag ->
            viewModelScope.launch {
                historyRepo.addTask(title, prompt, tag)
            }
        },
        onUpdateAgentRuleCallback = { ruleType, ruleValue ->
            applyPersistentRuleUpdate(ruleType, ruleValue)
        }
    )

    lateinit var multimodalLiveService: com.example.service.MultimodalLiveService
        private set

    val voiceBridge: VoiceAgentBridge = VoiceAgentBridge(
        context = application.applicationContext,
        onWakeWordGreetingTriggered = { rawWakePhrase, greetingText ->
            onWakeGreetingSpoken(rawWakePhrase, greetingText)
        },
        onPartialTranscriptChanged = { partial ->
            if (partial.isNotBlank()) {
                if (_isAgentPoweredOff.value) {
                    val (matched, remainingCmd) = CubeSentinelBus.matchWakeWord(partial)
                    if (matched) {
                        wakeUpAgentFromPowerOff(reason = "WAKE_WORD", inlineCommand = remainingCmd)
                    }
                } else {
                    _liveUserTranscript.value = partial
                }
            }
        },
        onFinalTranscript = { recognizedText, wasWokenByCube ->
            if (_isAgentPoweredOff.value) {
                val (matched, remainingCmd) = CubeSentinelBus.matchWakeWord(recognizedText)
                if (matched) {
                    wakeUpAgentFromPowerOff(reason = "WAKE_WORD", inlineCommand = remainingCmd)
                }
                // If powered off and user did not call the agent's name / wake word, stay completely silent & off
            } else {
                val directed = com.example.service.SmartMemoryAndSelfUpdateEngine.isSpeechDirectedAtAgent(
                    rawTranscript = recognizedText,
                    isTaskCurrentlyActive = isAssignedTaskCurrentlyExecuting(),
                    wasWokenByWakeWord = wasWokenByCube
                )
                if (directed) {
                    processUserCommand(recognizedText, triggeredByWakeWord = wasWokenByCube)
                } else {
                    com.example.service.SmartMemoryAndSelfUpdateEngine.incrementFilteredJunkCount()
                }
            }
        },
        onSpeechPlayingStateChanged = { isSpeaking ->
            if (::multimodalLiveService.isInitialized) {
                multimodalLiveService.notifyAgentSpeechState(isSpeaking)
            }
        }
    )

    @Volatile
    private var activeAssignedTaskUntilMs: Long = 0L

    @Volatile
    private var activeAssignedTaskGoal: String = ""

    fun markAssignedTaskStarted(goal: String, windowMs: Long = 45_000L) {
        activeAssignedTaskGoal = goal.trim()
        activeAssignedTaskUntilMs = System.currentTimeMillis() + windowMs
    }

    fun isAssignedTaskCurrentlyExecuting(): Boolean {
        val now = System.currentTimeMillis()
        return (now < activeAssignedTaskUntilMs) ||
            AccessibilityAutomationBus.state.value.isExecutingMacro ||
            _uiState.value is AgentUiState.Thinking ||
            activeWatcherJobs.isNotEmpty()
    }

    init {
        multimodalLiveService = com.example.service.MultimodalLiveService(
            context = application.applicationContext,
            onLiveSpokenChunkOrReply = { textChunk, speakImmediately ->
                _liveArcherReply.value = textChunk
                if (speakImmediately && textChunk.isNotBlank() && !_isAgentPoweredOff.value) {
                    voiceBridge.speak(textChunk)
                }
            },
            onExecuteLiveActionTag = { userUtterance, rawAiResponse ->
                handleLiveMultimodalTurnResult(userUtterance, rawAiResponse)
            },
            onBargeInInterruptRequested = {
                voiceBridge.stopSpeaking()
            },
            onUserTranscript = { text -> _liveUserTranscript.value = text }
        )
        multimodalLiveService.setToolCallHandler { tag -> executeLiveToolTag(tag) }
        multimodalLiveService.setNamedToolHandler { name, args -> executeNamedLiveTool(name, args) }
        multimodalLiveService.setOnUserTurnCompleteForMemory { userUtterance, aiReply ->
            viewModelScope.launch {
                evaluateAndSaveMemoryFromUserInput(userUtterance, aiReply)
                val ctx = getApplication<Application>().applicationContext
                com.example.service.GoogleDriveMemorySyncService.backupConversationSummaryToDrive(
                    context = ctx,
                    userPrompt = userUtterance,
                    agentReply = aiReply,
                    actionsSummary = "Live Multimodal Turn"
                )
                historyRepo.pruneLocalCacheForZeroPhoneStorageImpact()
            }
        }
    }

    fun isMainScreenShareActive(): Boolean {
        val cap = com.example.service.ScreenCaptureSessionBus.state.value
        return multimodalLiveState.value.isScreenStreamEnabled &&
            (cap.hasMediaProjectionGrant || com.example.service.AccessibilityAutomationBus.state.value.isServiceEnabled)
    }

    /** Runs a phone action requested by Gemini Live (function call) and returns a short result string with real-time screen verification. */
    private suspend fun executeLiveToolTag(tag: String): String {
        val plan = buildPlanFromAiResponse(
            userPrompt = "",
            effectiveCommand = "",
            rawResponse = tag,
            triggeredByWakeWord = true,
            modelUsed = multimodalLiveState.value.activeModelName
        )
        if (plan.steps.isEmpty()) return "action not recognized"
        val isInstallTask = plan.steps.any {
            it.actionType == AgentActionType.INSTALL_APP_PLAYSTORE ||
                it.actionType == AgentActionType.INSTALL_APK_PACKAGE
        }
        markAssignedTaskStarted(
            plan.steps.first().title,
            windowMs = if (isInstallTask) 120_000L else 45_000L
        )

        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            val sb = StringBuilder()
            val stepLabels = mutableListOf<String>()
            var startedInstallWatch = false
            for ((index, step) in plan.steps.withIndex()) {
                val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                    actionOrToolName = step.actionType.name,
                    targetOrAppSummary = "${step.appName} ${step.recipient} ${step.query} ${step.title}"
                )
                if (securityBlock != null) {
                    _liveArcherReply.value = securityBlock
                    return@withContext securityBlock
                }
                val r = deviceExecutor.executeStep(step, launchExternalIntents = index == 0)
                sb.append(r).append("; ")
                stepLabels.add("${step.actionType.name}: ${step.title} -> $r")
                if (!startedInstallWatch &&
                    (step.actionType == AgentActionType.INSTALL_APP_PLAYSTORE ||
                        step.actionType == AgentActionType.INSTALL_APK_PACKAGE) &&
                    !r.startsWith("PERMISSION_REQUIRED_")
                ) {
                    startedInstallWatch = true
                    val targetLabel = step.appName.ifBlank { step.query.ifBlank { step.title } }
                    scheduleProactiveWatchOrTimer(
                        mode = "WATCH_DOWNLOAD",
                        seconds = 180,
                        label = "$targetLabel ইনস্টল"
                    )
                }
            }
            if (stepLabels.isNotEmpty() && sb.none { false } && !sb.toString().contains("PERMISSION_REQUIRED_")) {
                val targetApp = plan.steps.firstOrNull()?.appName?.ifBlank { com.example.service.ScreenMemory.currentPkg }.orEmpty()
                com.example.service.SmartMemoryAndSelfUpdateEngine.recordLearnedWorkflowSteps(
                    taskTitle = plan.steps.first().title,
                    triggerKeywords = plan.steps.first().query.ifBlank { plan.steps.first().title },
                    targetAppOrSite = targetApp,
                    executedStepDescriptions = stepLabels
                )
            }
            sb.toString().trim()
        }.let { base ->
            val settleMs = com.example.service.SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(95L)
            kotlinx.coroutines.delay(settleMs)   // millisecond screen settle, then immediately report what is on screen
            val st = com.example.service.AccessibilityAutomationBus
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { st.serviceInstance?.refreshSnapshotNow() }
            val accState = st.state.value
            val pkg = com.example.service.ScreenMemory.currentPkg
            val appGuide = com.example.service.AppKnowledgeRegistry.guideForPackage(pkg)
            val liveStage = accState.liveTaskProgressStage
            base + " | NOW ON SCREEN (" + pkg + "): " + accState.visibleScreenText.take(500) +
                (if (liveStage.isNotBlank()) " | LIVE STAGE: $liveStage" else "") +
                " | ELEMENTS(label@x,y): " + com.example.service.ScreenMemory.describeCurrent(28) +
                " | APP GUIDE: " + appGuide
        }
    }

    /** app_control + write_code + send_generated_file + workflow_playbook + proactive_watch_or_timer + switch_personality + power_off_self tools coming from Gemini Live. */
    private suspend fun executeNamedLiveTool(name: String, args: org.json.JSONObject): String {
        val ctx = getApplication<Application>().applicationContext
        return when (name) {
            "install_app" -> {
                val appName = args.optString("app_name").ifBlank {
                    args.optString("target").ifBlank { args.optString("query") }
                }.trim()
                val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                    actionOrToolName = "INSTALL_APP",
                    targetOrAppSummary = appName
                )
                if (securityBlock != null) {
                    _liveArcherReply.value = securityBlock
                    return securityBlock
                }
                val modeStr = args.optString("mode").uppercase().trim()
                val isUpdate = modeStr.contains("UPDATE") || appName.lowercase().contains("update") || appName.contains("আপডেট")
                if (appName.isBlank()) return "missing app_name"
                markAssignedTaskStarted("অ্যাপ ইনস্টল: $appName", windowMs = 150_000L)
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    deviceExecutor.installAppFromPlayStore(
                        appNameOrQuery = appName,
                        launchExternal = true,
                        forceUpdateMode = isUpdate
                    )
                }
                _liveArcherReply.value = res
                if (!res.startsWith("PERMISSION_REQUIRED_")) {
                    scheduleProactiveWatchOrTimer(
                        mode = "WATCH_DOWNLOAD",
                        seconds = 240,
                        label = "$appName ইনস্টল"
                    )
                }
                kotlinx.coroutines.delay(140L)
                val st = com.example.service.AccessibilityAutomationBus
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    st.serviceInstance?.refreshSnapshotNow(force = true)
                }
                val accState = st.state.value
                val pkg = com.example.service.ScreenMemory.currentPkg
                "$res | NOW ON SCREEN ($pkg): ${accState.visibleScreenText.take(400)} | LIVE STAGE: ${accState.liveTaskProgressStage} | ELEMENTS(label@x,y): ${com.example.service.ScreenMemory.describeCurrent(25)}"
            }
            "power_off_self" -> {
                val farewell = args.optString("farewell_message").ifBlank {
                    "জি স্যার, আমি এখন একদম বন্ধ হয়ে যাচ্ছি। আপনি আবার অ্যাপে ঢুকলে বা আমার নাম ধরে ডাকলে আমি সাথে সাথে একটিভ হয়ে যাব।"
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    shutdownAgentCompletely(farewell)
                }
                "AGENT_POWERED_OFF_COMPLETELY"
            }
            "app_control" -> {
                val on = args.optString("state").trim().uppercase() in setOf("ON", "TRUE", "1", "ENABLE")
                when (args.optString("feature").trim().uppercase()) {
                    "TOUCH_EFFECT" -> { com.example.service.TouchEffectBus.set(ctx, on); "touch effect ${if (on) "ON" else "OFF"}" }
                    "SCREEN_SHARE" -> {
                        if (multimodalLiveState.value.isScreenStreamEnabled != on) toggleLiveScreenStream()
                        "screen share ${if (on) "ON" else "OFF"}"
                    }
                    "CAMERA" -> {
                        if (multimodalLiveState.value.isCameraStreamEnabled != on) toggleLiveCameraStream()
                        "camera ${if (on) "ON" else "OFF"}"
                    }
                    "MIC_MUTE" -> {
                        if (multimodalLiveState.value.isMicMuted != on) multimodalLiveService.toggleMicMute()
                        "mic ${if (on) "muted" else "unmuted"}"
                    }
                    "SPEAKER_MUTE" -> { multimodalLiveService.setSpeakerMuted(on); "speaker ${if (on) "muted" else "on"}" }
                    else -> "unknown feature"
                }
            }
            "ui_memory" -> {
                val hits = com.example.service.ScreenMemory.lookup(ctx, args.optString("query"), args.optString("app"))
                if (hits.isEmpty()) "not remembered yet — look at the screen / scroll"
                else hits.joinToString("; ") { (pkg, label, e) -> "$label@${e.cx},${e.cy} in $pkg" }
            }
            "find_app" -> {
                val hits = com.example.service.InstalledAppsIndex.search(ctx, args.optString("query"))
                if (hits.isEmpty()) "not installed" else hits.joinToString("; ") { "${it.label} (${it.pkg})" }
            }
            "deep_think" -> {
                markAssignedTaskStarted(args.optString("goal"), windowMs = 45_000L)
                val key = multimodalLiveService.currentApiKey()
                val goal = args.optString("goal")
                val screenInfo = "Foreground app: ${com.example.service.ScreenMemory.currentPkg}. Elements: ${com.example.service.ScreenMemory.describeCurrent(40)}"
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.service.GeminiBrainClient.think(key, "GOAL: $goal\n$screenInfo", multimodalLiveService.lastFrameForBrain)
                }
            }
            "proactive_watch_or_timer" -> {
                val mode = args.optString("mode").ifBlank { "WATCH_LOADING" }
                val secs = args.optString("seconds").filter { it.isDigit() }.toIntOrNull() ?: 60
                val label = args.optString("label").ifBlank { "সক্রিয় ওয়াচার" }
                scheduleProactiveWatchOrTimer(mode = mode, seconds = secs, label = label)
            }
            "switch_personality" -> {
                val presetId = args.optString("personality").ifBlank { "CORE_JARVIS_BOSS" }
                val customRule = args.optString("custom_rule")
                val preset = AgentPersonalityPreset.fromId(presetId)
                selectPersonality(preset, customRule, speakConfirmation = false)
                "Active personality switched to ${preset.titleBn} (${preset.id}) with voice ${_activeVoicePreset.value.liveVoiceName}"
            }
            "switch_voice_or_mic_power" -> {
                val voiceArg = args.optString("voice").trim()
                val boostArg = args.optString("mic_boost").trim().toFloatOrNull()
                val routeArg = args.optString("audio_route").trim()
                val results = mutableListOf<String>()
                if (boostArg != null) {
                    setMicListeningPower(boostArg, speakConfirmation = false)
                    results.add("mic listening power boosted to ${String.format(Locale.US, "%.1fx", _micSensitivityBoost.value)}")
                }
                if (voiceArg.isNotBlank()) {
                    val vPreset = AgentVoicePreset.fromIdOrVoiceName(voiceArg)
                    selectVoicePreset(vPreset, speakConfirmation = false)
                    results.add("voice switched to ${vPreset.titleBn} (${vPreset.liveVoiceName})")
                }
                if (routeArg.isNotBlank()) {
                    val routeMode = com.example.service.AudioRouteMode.fromIdOrHint(routeArg)
                    val routeSummary = com.example.service.UnifiedAudioRouterBus.setRouteMode(routeMode)
                    results.add(routeSummary)
                }
                if (results.isEmpty()) "voice=${_activeVoicePreset.value.liveVoiceName}, mic_boost=${_micSensitivityBoost.value}x, route=${com.example.service.UnifiedAudioRouterBus.state.value.selectedRouteMode.id}"
                else results.joinToString("; ")
            }
            "write_code" -> {
                val title = args.optString("title").ifBlank { "archer_project" }
                val file = args.optString("file_name").ifBlank { "index.html" }
                val lang = args.optString("language")
                val code = args.optString("code")
                if (code.isBlank()) return "no code received"
                markAssignedTaskStarted("কোড তৈরি ও লাইভ প্রিভিউ: $file", windowMs = 30_000L)
                val staged = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    codingAndContactsManager.stageGeneratedCodePendingSave(
                        projectTitle = title,
                        preferredFileName = file,
                        language = lang,
                        rawCodeOrResponse = code
                    )
                }
                val doneMsg = "জি স্যার, '${staged.preferredFileName}' তৈরি করে আপনার সামনে লাইভ প্রিভিউ ওপেন করেছি। আগে চেক করে দেখুন সব ঠিক আছে কি না—আপনি বললেই আমি গুগল ড্রাইভে সেভ করে দেবো।"
                _liveArcherReply.value = doneMsg
                "STAGED_IN_LIVE_PREVIEW_WAITING_USER_CHECK: '${staged.preferredFileName}' is now open on screen in Live Preview. Ask Sir in 1 sentence to check if everything looks right, and once he confirms ('হ্যাঁ' / 'ঠিক আছে' / 'সেভ করো'), call confirm_pending_save(decision='SAVE') to save 1 single copy to Google Drive."
            }
            "google_drive_cloud_os" -> {
                val op = args.optString("operation", "BUILD_LARGE_ZIP").uppercase().trim()
                val name = args.optString("name").ifBlank { "Archer_AI_Package" }.trim()
                val sizeMb = args.optString("size_mb").filter { it.isDigit() }.toIntOrNull()?.coerceIn(1, 60) ?: 5
                val contentOrSpec = args.optString("content_or_spec").trim()
                val subFolder = args.optString("sub_folder").trim()
                markAssignedTaskStarted("Google Drive অটো-স্টোরেজ: $name", windowMs = 60_000L)
                val res = when (op) {
                    "CONFIRM_SAVE_ZIP" -> {
                        com.example.service.GoogleDriveMemorySyncService.confirmSavePendingStagedZipToDrive(ctx)
                    }
                    "CREATE_FOLDER" -> {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.service.GoogleDriveMemorySyncService.buildAndUploadLargeZipOrFolderToDrive(
                                context = ctx,
                                projectOrFolderName = name,
                                targetSizeMb = 1,
                                primaryCodeOrSpecification = contentOrSpec,
                                customSubFolderName = name,
                                createUnpackedFolderInDriveToo = true,
                                requireUserReviewBeforeDriveSave = true
                            )
                        }
                    }
                    "SAVE_SINGLE_FILE" -> {
                        val fileName = if (name.contains(".")) name else "$name.txt"
                        val bytes = contentOrSpec.ifBlank { "Archer AI Cloud OS File: $fileName" }.toByteArray(Charsets.UTF_8)
                        val savedOk = com.example.service.GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                            context = ctx,
                            fileName = fileName,
                            contentBytes = bytes,
                            summaryDescription = "AI Direct Saved File ($fileName)",
                            targetSubFolder = subFolder
                        )
                        if (savedOk) {
                            "জি স্যার, '$fileName' ফাইলটি ডুপ্লিকেট ছাড়া শুধুমাত্র ১টি কপি হিসেবে সরাসরি আপনার Google Drive (/AI_OS_Memory)-এ সেভ করে দিয়েছি।"
                        } else {
                            "স্যার, '$fileName' ফোনে সুরক্ষিত আছে, কিন্তু Google Drive এখন কানেক্ট নেই/আপলোড হয়নি। কানেক্ট হলে নিজে থেকে আপলোড হবে — বসকে সত্যটা জানান।"
                        }
                    }
                    "SYNC_ALL" -> {
                        com.example.service.GoogleDriveMemorySyncService.syncAllAppDataToDriveNow(ctx, speakResult = false)
                    }
                    "SEARCH_DRIVE" -> {
                        com.example.service.GoogleDriveMemorySyncService.searchGoogleDriveMemory(ctx, name)
                    }
                    else -> {
                        com.example.service.GoogleDriveMemorySyncService.buildAndUploadLargeZipOrFolderToDrive(
                            context = ctx,
                            projectOrFolderName = name,
                            targetSizeMb = sizeMb,
                            primaryCodeOrSpecification = contentOrSpec,
                            customSubFolderName = subFolder.ifBlank { com.example.service.GoogleDriveMemorySyncService.SUBFOLDER_ZIPS },
                            createUnpackedFolderInDriveToo = true,
                            requireUserReviewBeforeDriveSave = true
                        )
                    }
                }
                _liveArcherReply.value = res
                res
            }
            "send_generated_file" -> {
                val recipient = args.optString("recipient").trim()
                val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                    actionOrToolName = "SEND_GENERATED_FILE",
                    targetOrAppSummary = recipient
                )
                if (securityBlock != null) {
                    _liveArcherReply.value = securityBlock
                    return securityBlock
                }
                val platform = args.optString("platform").trim().ifBlank { "WHATSAPP" }
                val fileName = args.optString("file_name").trim()
                val sendAsZip = args.optString("send_as_zip").trim().equals("true", ignoreCase = true) ||
                    fileName.endsWith(".zip", ignoreCase = true)
                val caption = args.optString("caption").trim()
                markAssignedTaskStarted("ফাইল পাঠানো: ${fileName.ifBlank { "সর্বশেষ কোড ফাইল" }} -> $recipient", windowMs = 45_000L)
                val shareRes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    codingAndContactsManager.shareGeneratedArtifactToContactOrApp(
                        targetContactOrApp = if (platform.isNotBlank()) "$recipient ($platform)" else recipient,
                        preferredFileName = fileName,
                        sendAsZip = sendAsZip,
                        captionText = caption
                    )
                }
                _liveArcherReply.value = shareRes
                shareRes
            }
            "workflow_playbook" -> {
                val mode = args.optString("mode").uppercase().trim()
                val query = args.optString("query").trim()
                val targetApp = args.optString("target_app").trim()
                val rawSteps = args.optString("steps").trim()
                if (mode == "SAVE" && rawSteps.isNotBlank()) {
                    val stepList = rawSteps.split("->", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
                    com.example.service.SmartMemoryAndSelfUpdateEngine.recordLearnedWorkflowSteps(
                        taskTitle = query.ifBlank { "Learned Task" },
                        triggerKeywords = query,
                        targetAppOrSite = targetApp,
                        executedStepDescriptions = stepList
                    )
                    "Saved workflow playbook '${query.take(36)}' (${stepList.size} steps) for automatic future execution."
                } else {
                    val match = com.example.service.SmartMemoryAndSelfUpdateEngine.findMatchingPlaybook(query)
                    if (match != null) {
                        "FOUND PLAYBOOK '${match.taskTitle}' (used ${match.successCount}x in ${match.targetAppOrSite}): " +
                            match.steps.joinToString(" -> ")
                    } else {
                        "No exact playbook found for '$query'. Inspect live screen and record steps once completed."
                    }
                }
            }
            "understand_app_or_website" -> {
                val target = args.optString("app_or_site")
                com.example.service.AppKnowledgeRegistry.explainHowAppOrSiteWorksBn(ctx, target)
            }
            "self_heal_or_update" -> {
                val mode = args.optString("mode").uppercase()
                val title = args.optString("title")
                val spec = args.optString("specification")
                val res = if (mode.contains("HEAL") || mode.contains("REPAIR")) {
                    com.example.service.SmartMemoryAndSelfUpdateEngine.runSelfDiagnosticsAndRepair(ctx, spec)
                } else {
                    val out = com.example.service.SmartMemoryAndSelfUpdateEngine.installLiveFeatureUpdate(
                        featureTitle = title.ifBlank { spec.take(36) },
                        featurePromptOrRule = spec,
                        triggerHint = title
                    )
                    multimodalLiveService.pushLiveSystemDirectiveOverride("$title -> $spec")
                    multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
                    out
                }
                _liveArcherReply.value = res
                res
            }
            "background_web_search" -> {
                val query = args.optString("query").trim()
                val useMaps = args.optString("use_maps").equals("true", ignoreCase = true)
                val res = com.example.service.AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
                    context = ctx,
                    apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                    query = query,
                    useMapsGrounding = useMaps
                )
                _liveArcherReply.value = res.take(280)
                res
            }
            "make_image_with_gemini" -> {
                val prompt = args.optString("prompt").trim()
                val editExisting = args.optString("edit_existing").equals("true", ignoreCase = true)
                val res = com.example.service.AgentAbilitySwitchesBus.createImageWithGemini(
                    context = ctx,
                    apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                    prompt = prompt,
                    editExistingImage = editExisting
                )
                _liveArcherReply.value = res
                res
            }
            "cloud_bot_engine" -> {
                val name = args.optString("name").trim().ifBlank { "Cloud Bot" }
                val botType = args.optString("bot_type", "WEB_PRICE_SITE_MONITOR").trim()
                val targetUrlOrApp = args.optString("target_url_or_app").trim()
                val instruction = args.optString("instruction").trim()
                val intervalMin = args.optString("interval_minutes").filter { it.isDigit() }.toIntOrNull() ?: 30
                val res = com.example.service.CloudBotEngineBus.createAndRunCloudBot(
                    context = ctx,
                    name = name,
                    botTypeRaw = botType,
                    targetUrlOrApp = targetUrlOrApp,
                    instructionPrompt = instruction,
                    intervalMinutes = intervalMin
                )
                _liveArcherReply.value = res
                res
            }
            "generate_music_lyria" -> {
                val prompt = args.optString("prompt").trim()
                val fullSong = args.optString("full_song").equals("true", ignoreCase = true)
                val res = com.example.service.AgentAbilitySwitchesBus.generateMusicTrackWithLyria(
                    context = ctx,
                    apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                    prompt = prompt,
                    fullLengthSong = fullSong
                )
                _liveArcherReply.value = res
                res
            }
            "set_ability_switch" -> {
                val ability = args.optString("ability").uppercase().trim()
                val stateStr = args.optString("state").uppercase().trim()
                val on = stateStr in setOf("ON", "TRUE", "1", "ENABLE", "ENABLED")
                when (ability) {
                    "SILENT_EXECUTION" -> {
                        setSilentExecutionSwitch(on)
                        "Silent execution is now ${if (on) "ON (quiet while working, one report at the end)" else "OFF"}"
                    }
                    "BACKGROUND_WEB_SEARCH" -> {
                        setBackgroundWebSearchSwitch(on)
                        "Background web search is now ${if (on) "ON" else "OFF"}"
                    }
                    "MAKE_IMAGES_WITH_GEMINI" -> {
                        setMakeImagesWithGeminiSwitch(on)
                        "Make images with Gemini is now ${if (on) "ON" else "OFF"}"
                    }
                    "IGNORE_BACKGROUND_AUDIO" -> {
                        setIgnoreBackgroundAudioSwitch(on)
                        "Ignore background audio is now ${if (on) "ON" else "OFF"}"
                    }
                    "SHOW_TAP_INDICATOR" -> {
                        setShowTapIndicatorSwitch(on)
                        "Show tap indicator is now ${if (on) "ON" else "OFF"}"
                    }
                    "SEND_CRASH_REPORTS" -> {
                        setSendCrashReportsSwitch(on)
                        "Send crash reports is now ${if (on) "ON" else "OFF"}"
                    }
                    "FLOATING_AVATAR" -> {
                        val char = com.example.service.FloatingAvatarCharacter.fromId(stateStr)
                        setFloatingAvatarCharacter(char)
                        "Floating avatar set to ${char.displayName}"
                    }
                    else -> "Unknown ability switch: $ability"
                }
            }
            "confirm_pending_save" -> {
                val decision = args.optString("decision", "SAVE").uppercase().trim()
                val shouldSave = decision.contains("SAVE") || decision.contains("YES") || decision.contains("CONFIRM")
                val msg = executePendingSaveDecision(shouldSave)
                _liveArcherReply.value = msg
                msg
            }
            "save_memory", "save_user_memory", "save_local_memory" -> {
                val title = args.optString("title").trim().ifBlank { "বসের গুরুত্বপূর্ণ মেমোরি" }
                val content = args.optString("content").trim().ifBlank { args.optString("fact").trim() }
                val category = args.optString("category").trim().ifBlank {
                    args.optString("tags").trim().ifBlank { "USER_PREFERENCE" }
                }
                if (content.isBlank()) return "missing memory content"
                val existing = historyRepo.memoryNotesFlow.first()
                val alreadyExists = existing.any {
                    it.content.equals(content, ignoreCase = true) ||
                        (it.title.equals(title, ignoreCase = true) && it.content.contains(content, ignoreCase = true))
                }
                if (!alreadyExists) {
                    historyRepo.saveMemoryNote(
                        title = title,
                        content = content,
                        category = category
                    )
                }
                // Automatically back up to 5TB Google Drive ('AI_OS_Memory') via Drive REST API v3 & keep phone storage clean
                val cloudOk = com.example.service.GoogleDriveMemorySyncService.backupMemoryNoteToDrive(
                    context = ctx,
                    title = title,
                    factContent = content,
                    tagsOrCategory = category
                )
                historyRepo.pruneLocalCacheForZeroPhoneStorageImpact()
                val updated = historyRepo.memoryNotesFlow.first()
                com.example.service.SmartMemoryAndSelfUpdateEngine.syncLiveMemoryNotesSummary(
                    updated.map { "${it.title}: ${it.content} [${it.category}]" }
                )
                val confirmBn = if (cloudOk) {
                    "জি বস, মেমোরি ফোনে ও আপনার Google Drive ব্রেইনে ('AI_OS_Memory/Memories') সেভ করে রেখেছি: '$title — $content'।"
                } else {
                    "জি বস, মেমোরি ফোনে সেভ করেছি: '$title — $content'। Google Drive এখন কানেক্ট নেই/আপলোড হয়নি — কানেক্ট হলে নিজে থেকে ড্রাইভে চলে যাবে।"
                }
                _liveArcherReply.value = confirmBn
                if (cloudOk) {
                    "SAVED_TO_PHONE_AND_GOOGLE_DRIVE_BRAIN: '$title' -> '$content' [$category]"
                } else {
                    "SAVED_ON_PHONE_DRIVE_UPLOAD_PENDING (Drive not connected or offline; it uploads automatically later. Tell the boss honestly): '$title' -> '$content' [$category]"
                }
            }
            "search_memory", "search_local_memory" -> {
                val query = args.optString("query").trim()
                val localRes = historyRepo.searchLocalMemoryTimeMachine(query)
                val driveRes = com.example.service.GoogleDriveMemorySyncService.searchGoogleDriveMemory(ctx, query)
                "$localRes\n\n$driveRes"
            }
            "forget_memory" -> {
                val query = args.optString("query").trim()
                val scopeName = args.optString("scope").trim().ifBlank { "MEMORY" }
                val confirm = args.optString("confirm").trim()
                val res = com.example.service.GoogleDriveMemorySyncService.forgetFromBrain(ctx, query, scopeName, confirm)
                val updated = historyRepo.memoryNotesFlow.first()
                com.example.service.SmartMemoryAndSelfUpdateEngine.syncLiveMemoryNotesSummary(
                    updated.map { "${it.title}: ${it.content} [${it.category}]" }
                )
                _liveArcherReply.value = res
                res
            }
            "prepare_and_highlight" -> {
                val appOrDeepLink = args.optString("app_or_deeplink").trim()
                val recipientOrQuery = args.optString("recipient_or_query").trim()
                val textToType = args.optString("text_to_type").trim()
                val targetButton = args.optString("target_button_label").trim().ifBlank { "Send" }
                val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                    actionOrToolName = "PREPARE_AND_HIGHLIGHT",
                    targetOrAppSummary = "$appOrDeepLink $recipientOrQuery $targetButton"
                )
                if (securityBlock != null) {
                    _liveArcherReply.value = securityBlock
                    return securityBlock
                }
                markAssignedTaskStarted("টার্গেট লক: $targetButton", windowMs = 60_000L)
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    deviceExecutor.prepareAndHighlightWithHybridEngine(
                        appOrDeepLink = appOrDeepLink,
                        recipientOrQuery = recipientOrQuery,
                        textToType = textToType,
                        targetButtonLabel = targetButton
                    )
                }
                _liveArcherReply.value = "বস, সব রেডি—এখন কি ক্লিক করব?"
                res
            }
            "execute_confirmed_click" -> {
                val decision = args.optString("decision", "APPROVE").trim().uppercase()
                if (decision == "APPROVE") {
                    val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                        actionOrToolName = "EXECUTE_CONFIRMED_CLICK",
                        targetOrAppSummary = "Confirm Action"
                    )
                    if (securityBlock != null) {
                        _liveArcherReply.value = securityBlock
                        return securityBlock
                    }
                }
                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    deviceExecutor.executeConfirmedClickOrOtp(decision)
                }
                _liveArcherReply.value = res
                res
            }
            "voice_biometrics_control" -> {
                val op = args.optString("operation", "WHO_IS_SPEAKING").trim().uppercase()
                val speakerName = args.optString("speaker_name").trim()
                val genderHint = args.optString("gender_hint").trim()
                val lockEnabledStr = args.optString("lock_enabled").trim().uppercase()
                val res = when (op) {
                    "ENROLL_BOSS_VOICE" -> {
                        val parsedGender = com.example.service.DetectedSpeakerGender.fromString(genderHint)
                            .takeIf { it != com.example.service.DetectedSpeakerGender.UNKNOWN }
                        val msg = com.example.service.VoiceBiometricsEngine.enrollSpeakerFromRecentVoiceBuffer(
                            context = ctx,
                            speakerName = speakerName.ifBlank { "বস" },
                            isOwnerBoss = true,
                            genderOverride = parsedGender
                        )
                        multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
                        msg
                    }
                    "ENROLL_GUEST_VOICE", "ENROLL_VOICE" -> {
                        val isBoss = speakerName.equals("বস", ignoreCase = true) ||
                            speakerName.equals("Boss", ignoreCase = true) ||
                            speakerName.isBlank()
                        val parsedGender = com.example.service.DetectedSpeakerGender.fromString(genderHint)
                            .takeIf { it != com.example.service.DetectedSpeakerGender.UNKNOWN }
                        val msg = com.example.service.VoiceBiometricsEngine.enrollSpeakerFromRecentVoiceBuffer(
                            context = ctx,
                            speakerName = speakerName.ifBlank { "বস" },
                            isOwnerBoss = isBoss,
                            genderOverride = parsedGender
                        )
                        multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
                        msg
                    }
                    "UPDATE_GENDER_PERCEPTION" -> {
                        com.example.service.VoiceBiometricsEngine.updateFromGeminiLivePerception(
                            genderOrAgeHint = genderHint,
                            speakerNameHint = speakerName
                        )
                        val st = com.example.service.VoiceBiometricsEngine.state.value
                        "Updated live speaker perception: ${st.liveHudBadgeText}"
                    }
                    "SET_VOICE_LOCK" -> {
                        val on = lockEnabledStr !in setOf("OFF", "FALSE", "0", "DISABLE")
                        com.example.service.VoiceBiometricsEngine.setVoiceSecurityLockEnabled(ctx, on)
                    }
                    "DELETE_VOICE_PROFILE" -> {
                        com.example.service.VoiceBiometricsEngine.deleteVoiceProfile(ctx, speakerName)
                    }
                    else -> {
                        val st = com.example.service.VoiceBiometricsEngine.state.value
                        "বর্তমান বক্তা: ${st.activeSpeakerName} • লিঙ্গ/কণ্ঠ: ${st.detectedGender.labelBn} (${st.detectedGender.labelEn}) • পিচ: ${st.currentPitchHz}Hz • বসের ভয়েস ভেরিফায়েড: ${if (st.isOwnerBossVerified) "হ্যাঁ (Verified)" else "না (Guest)"}"
                    }
                }
                _liveArcherReply.value = res
                res
            }
            "notification_and_otp_assist" -> {
                val mode = args.optString("mode", "READ_NOTIFICATIONS").trim().uppercase()
                val appFilter = args.optString("app_or_sender").trim()
                val replyText = args.optString("reply_text").trim()
                when (mode) {
                    "CHECK_OTP" -> {
                        val st = com.example.service.NotificationReplyBus.state.value
                        if (st.pendingOtpCode.isNotBlank()) {
                            com.example.service.ConfirmationGateBus.lockTargetAndHighlight(
                                ctx = ctx,
                                buttonLabel = "OTP Input (${st.pendingOtpCode})",
                                x = 540f,
                                y = 920f,
                                appPackageOrName = st.pendingOtpSourceApp,
                                isOtpConfirmation = true,
                                otpCode = st.pendingOtpCode
                            )
                            "OTP_DETECTED_WAITING_CONFIRMATION: ${st.pendingOtpSourceApp} থেকে OTP কোড এসেছে: '${st.pendingOtpCode}'. NEVER auto-fill! Ask in Bangla: 'বস, ${st.pendingOtpSourceApp} থেকে OTP কোড ${st.pendingOtpCode} এসেছে—এখন কি পেস্ট করব?'"
                        } else {
                            "NO_PENDING_OTP: বর্তমানে কোনো নতুন OTP নোটিফিকেশন নেই।"
                        }
                    }
                    "PASTE_CONFIRMED_OTP" -> {
                        val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                            actionOrToolName = "PASTE_CONFIRMED_OTP",
                            targetOrAppSummary = "OTP Paste"
                        )
                        if (securityBlock != null) {
                            _liveArcherReply.value = securityBlock
                            return securityBlock
                        }
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            deviceExecutor.executeConfirmedClickOrOtp("APPROVE")
                        }
                    }
                    "INLINE_REPLY" -> {
                        val securityBlock = com.example.service.VoiceBiometricsEngine.verifySensitiveActionOrReturnBlockMessageBn(
                            actionOrToolName = "INLINE_REPLY",
                            targetOrAppSummary = "$appFilter: $replyText"
                        )
                        if (securityBlock != null) {
                            _liveArcherReply.value = securityBlock
                            return securityBlock
                        }
                        val (_, msg) = com.example.service.NotificationReplyBus.sendInlineReply(ctx, appFilter, replyText)
                        _liveArcherReply.value = msg
                        msg
                    }
                    else -> {
                        val summary = com.example.service.NotificationReplyBus.readNotificationsSummary(appFilter)
                        _liveArcherReply.value = summary
                        summary
                    }
                }
            }
            "hardware_and_camera_control" -> {
                val op = args.optString("operation").trim().uppercase()
                val valueStr = args.optString("value").trim()
                val streamOrLabel = args.optString("stream_or_label").trim()
                val res = when (op) {
                    "TORCH_ON" -> {
                        deviceExecutor.setFlashlight(true)
                        "ফ্ল্যাশলাইট (টর্চ) চালু করা হয়েছে"
                    }
                    "TORCH_OFF" -> {
                        deviceExecutor.setFlashlight(false)
                        "ফ্ল্যাশলাইট বন্ধ করা হয়েছে"
                    }
                    "SET_VOLUME" -> {
                        val pct = valueStr.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 70
                        deviceExecutor.setStreamVolumePercent(streamOrLabel.ifBlank { "MUSIC" }, pct, "")
                    }
                    "SET_BRIGHTNESS" -> {
                        val pct = valueStr.filter { it.isDigit() }.toIntOrNull()?.coerceIn(5, 100) ?: 75
                        deviceExecutor.setScreenBrightnessPercent(pct)
                    }
                    "SET_ALARM" -> {
                        val parts = valueStr.split(":")
                        val hr = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 23) ?: 7
                        val min = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                        deviceExecutor.setSystemAlarm(hr, min, streamOrLabel.ifBlank { "AI Alarm" })
                        "অ্যালার্ম সেট করা হয়েছে: ${String.format(Locale.US, "%02d:%02d", hr, min)}"
                    }
                    "CHECK_BATTERY" -> {
                        deviceExecutor.refreshHardwareState()
                        val hw = deviceExecutor.hardwareState.value
                        "ব্যাটারি চার্জ: ${hw.batteryPercent}% (${if (hw.isCharging) "চারজিং চালু" else "চার্জার খোলা"}) • মিডিয়া ভলিউম: ${hw.musicVolumePercent}%"
                    }
                    "SWITCH_TO_SCREEN_SHARE" -> {
                        if (multimodalLiveState.value.isCameraStreamEnabled) {
                            multimodalLiveService.toggleCameraStream()
                        }
                        multimodalLiveService.enableScreenStreamNow()
                        "লাইভ স্ক্রিন শেয়ারিং মোডে সুইচ করা হয়েছে"
                    }
                    "SWITCH_TO_LIVE_CAMERA" -> {
                        val wantFront = !valueStr.equals("BACK", ignoreCase = true)
                        _isFrontCameraSelected.value = wantFront
                        if (!multimodalLiveState.value.isCameraStreamEnabled) {
                            multimodalLiveService.toggleCameraStream()
                        }
                        "লাইভ ক্যামেরা ফিডে (${if (wantFront) "Front" else "Back"}) সুইচ করা হয়েছে"
                    }
                    else -> "Unknown hardware_and_camera_control operation: $op"
                }
                _liveArcherReply.value = res
                res
            }
            else -> "unknown tool"
        }
    }

    fun executePendingSaveDecision(shouldSave: Boolean): String {
        val ctx = getApplication<Application>().applicationContext
        val savedItems = mutableListOf<String>()
        if (shouldSave) {
            if (com.example.service.GoogleDriveMemorySyncService.hasPendingStagedZip()) {
                viewModelScope.launch {
                    val zipMsg = com.example.service.GoogleDriveMemorySyncService.confirmSavePendingStagedZipToDrive(ctx)
                    _liveArcherReply.value = zipMsg
                }
                savedItems.add("জি স্যার, আপনার চেক করা ZIP প্যাকেজটি কোনো ডুপ্লিকেট ছাড়া শুধুমাত্র ১টি কপি হিসেবে সরাসরি আপনার Google Drive (/AI_OS_Memory/ZIP_Packages)-এ সেভ করে দিচ্ছি।")
            }
            if (com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedImage()) {
                savedItems.add(com.example.service.AgentAbilitySwitchesBus.confirmSavePendingImageToArcherFolder(ctx))
            }
            if (codingAndContactsManager.hasPendingCodeConfirmation()) {
                val artifact = codingAndContactsManager.confirmSavePendingCodeToArcherFolder()
                if (artifact != null) {
                    savedItems.add("জি স্যার, আপনার চেক করা '${artifact.primaryFileName}' ফাইলটি সরাসরি 5TB Google Drive (/AI_OS_Memory/Argon4_Code) এবং Download/archer/-এ সেভ করে দিয়েছি।")
                }
            }
            if (com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedMusic()) {
                savedItems.add(com.example.service.AgentAbilitySwitchesBus.confirmSavePendingMusicToArcherFolder(ctx))
            }
            com.example.service.ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                viewModelScope.launch {
                    com.example.service.GoogleDriveMemorySyncService.backupScreenshotBitmapToDrive(
                        context = ctx,
                        bitmap = bmp,
                        captionOrContext = "Confirmed Action Screen Snapshot"
                    )
                }
            }
            return if (savedItems.isNotEmpty()) {
                savedItems.joinToString(" ")
            } else {
                "স্যার, এই মুহূর্তে সেভ করার মতো কোনো পেন্ডিং ফাইল নেই।"
            }
        } else {
            var discardedAny = false
            if (com.example.service.GoogleDriveMemorySyncService.hasPendingStagedZip()) {
                com.example.service.GoogleDriveMemorySyncService.discardPendingStagedZip()
                discardedAny = true
            }
            if (com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedImage()) {
                com.example.service.AgentAbilitySwitchesBus.discardPendingImage()
                discardedAny = true
            }
            if (codingAndContactsManager.hasPendingCodeConfirmation()) {
                codingAndContactsManager.discardPendingCode()
                discardedAny = true
            }
            if (com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedMusic()) {
                com.example.service.AgentAbilitySwitchesBus.discardPendingMusic()
                discardedAny = true
            }
            return if (discardedAny) {
                "জি স্যার, আপনার নির্দেশ অনুযায়ী এটি কোনো ফোল্ডারে সেভ করা হয়নি—বাদ দিয়ে দিয়েছি।"
            } else {
                "জি স্যার, কোনো ফোল্ডারে সেভ করা হয়নি।"
            }
        }
    }

    private val _uiState = MutableStateFlow<AgentUiState>(AgentUiState.Idle)
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    // Live real-time transcript states so YOU and ARCHER update instantaneously
    private val _liveUserTranscript = MutableStateFlow("")
    val liveUserTranscript: StateFlow<String> = _liveUserTranscript.asStateFlow()

    private val _liveArcherReply = MutableStateFlow("জি স্যার, আমি প্রস্তুত। যেকোনো প্রশ্ন বা আদেশ বলুন।")
    val liveArcherReply: StateFlow<String> = _liveArcherReply.asStateFlow()

    private val _autoExecute = MutableStateFlow(true)
    val autoExecute: StateFlow<Boolean> = _autoExecute.asStateFlow()

    private val _activeModal = MutableStateFlow(JarvisNodeModal.NONE)
    val activeModal: StateFlow<JarvisNodeModal> = _activeModal.asStateFlow()

    private val _isFrontCameraSelected = MutableStateFlow(true)
    val isFrontCameraSelected: StateFlow<Boolean> = _isFrontCameraSelected.asStateFlow()

    private val _geminiApiKeyState = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_GEMINI_API_KEY, "")?.trim().orEmpty()
    )
    val geminiApiKeyState: StateFlow<String> = _geminiApiKeyState.asStateFlow()

    private val _lockedReplyLanguage = MutableStateFlow(
        prefs.getString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO")?.trim().orEmpty().ifBlank { "AUTO" }
    )
    val lockedReplyLanguage: StateFlow<String> = _lockedReplyLanguage.asStateFlow()

    private val _persistentUserRule = MutableStateFlow(
        prefs.getString(KEY_PERSISTENT_USER_RULE, "স্যারের যেকোনো আদেশ ও নিয়ম মাথায় রেখে কাজ করবে")?.trim().orEmpty()
    )
    val persistentUserRule: StateFlow<String> = _persistentUserRule.asStateFlow()

    private val _activePersonality = MutableStateFlow(
        AgentPersonalityPreset.fromId(prefs.getString(KEY_ACTIVE_PERSONALITY, AgentPersonalityPreset.CORE_JARVIS_BOSS.id))
    )
    val activePersonality: StateFlow<AgentPersonalityPreset> = _activePersonality.asStateFlow()

    private val _activeVoicePreset = MutableStateFlow(
        AgentVoicePreset.fromIdOrVoiceName(
            prefs.getString(KEY_ACTIVE_VOICE_PRESET, null) ?: _activePersonality.value.liveVoiceName
        )
    )
    val activeVoicePreset: StateFlow<AgentVoicePreset> = _activeVoicePreset.asStateFlow()

    private val _micSensitivityBoost = MutableStateFlow(
        prefs.getFloat(KEY_MIC_SENSITIVITY_BOOST, 2.8f).coerceIn(1.0f, 4.0f)
    )
    val micSensitivityBoost: StateFlow<Float> = _micSensitivityBoost.asStateFlow()

    private val _customPersonalityPrompt = MutableStateFlow(
        prefs.getString(KEY_CUSTOM_PERSONALITY_PROMPT, "")?.trim().orEmpty()
    )
    val customPersonalityPrompt: StateFlow<String> = _customPersonalityPrompt.asStateFlow()

    private val _proactiveAlertsEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_PROACTIVE_ALERTS_ENABLED, true)
    )
    val proactiveAlertsEnabled: StateFlow<Boolean> = _proactiveAlertsEnabled.asStateFlow()

    private val _proactiveWatchTasks = MutableStateFlow<List<ProactiveWatchTask>>(emptyList())
    val proactiveWatchTasks: StateFlow<List<ProactiveWatchTask>> = _proactiveWatchTasks.asStateFlow()

    private val activeWatcherJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    private val _agentPlans = MutableStateFlow<List<AgentPlanResponse>>(defaultWelcomePlan())
    val agentPlans: StateFlow<List<AgentPlanResponse>> = _agentPlans.asStateFlow()

    val hardwareState: StateFlow<DeviceHardwareSnapshot> = deviceExecutor.hardwareState
    val voiceState: StateFlow<VoiceBridgeState> = voiceBridge.state
    val sentinelState: StateFlow<CubeSentinelState> = CubeSentinelBus.state
    val accessibilityState: StateFlow<AccessibilityEngineState> = AccessibilityAutomationBus.state
    val notificationState: StateFlow<NotificationHubState> = NotificationReplyBus.state
    val codingWorkspaceState: StateFlow<com.example.service.CodingWorkspaceState> = codingAndContactsManager.workspaceState
    val multimodalLiveState: StateFlow<com.example.service.MultimodalLiveSessionState> = multimodalLiveService.state
    val screenCaptureSessionState: StateFlow<com.example.service.ScreenCaptureSessionState> =
        com.example.service.ScreenCaptureSessionBus.state
    val smartEvolutionState: StateFlow<com.example.service.SmartEvolutionState> =
        com.example.service.SmartMemoryAndSelfUpdateEngine.state
    val abilitySwitchesState: StateFlow<com.example.service.AgentAbilitySwitchesState> =
        com.example.service.AgentAbilitySwitchesBus.state

    val commandHistory: StateFlow<List<CommandHistoryEntity>> = historyRepo.historyFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val memoryNotes: StateFlow<List<AgentMemoryNoteEntity>> = historyRepo.memoryNotesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val todayTasks: StateFlow<List<TodayTaskEntity>> = historyRepo.todayTasksFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val platformAccounts: StateFlow<List<PlatformAccountEntity>> = historyRepo.platformAccountsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    private val _isAgentPoweredOff = MutableStateFlow(false)
    val isAgentPoweredOff: StateFlow<Boolean> = _isAgentPoweredOff.asStateFlow()

    @Volatile
    private var poweredOffAtMs: Long = 0L

    private var activeCommandJob: Job? = null
    private val recentConversationTurns = ArrayDeque<Pair<String, String>>(6)

    init {
        val ctx = application.applicationContext
        com.example.service.SmartMemoryAndSelfUpdateEngine.init(ctx)
        com.example.service.AgentAbilitySwitchesBus.ensureInitialized(ctx)
        com.example.service.FloatingAvatarOverlayBus.onAvatarTappedCallback = {
            onMainGlobeTap()
        }
        com.example.service.GoogleDriveMemorySyncService.init(ctx)
        com.example.service.VoiceBiometricsEngine.init(ctx)
        com.example.service.ExternalMicArbiter.init(ctx)
        com.example.service.ExternalMicArbiter.setGeminiLiveOwningMic(true)
        com.example.service.AppTaskRemovedShutdownBus.registerViewModelShutdown {
            shutdownAgentCompletelyOnTaskRemoved()
        }

        // Keep old STT/TTS off so Gemini Live exclusively owns the microphone and speaker
        voiceBridge.setHandsFreeContinuousMode(false)
        voiceBridge.stopListening(keepSentinelPhase = false)

        viewModelScope.launch {
            seedInitialDataIfEmpty()
        }

        // Continuously sync saved Room SQLite Memory Notes + Conversation Summaries into SmartMemoryAndSelfUpdateEngine
        // AND automatically sync new changes to 5TB Google Drive (/AI_OS_Memory sub-folders) with zero clicks
        viewModelScope.launch {
            historyRepo.memoryNotesFlow.collect { notes ->
                val historyBlock = runCatching { historyRepo.buildStartupMemoryInjectionBlock() }.getOrDefault("")
                val lines = notes.map { "${it.title}: ${it.content} [${it.category}]" } +
                    (if (historyBlock.isNotBlank()) listOf(historyBlock) else emptyList())
                com.example.service.SmartMemoryAndSelfUpdateEngine.syncLiveMemoryNotesSummary(lines)
                multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
                if (notes.isNotEmpty()) {
                    // debounced: many quick changes collapse into ONE incremental sync (no more upload storms)
                    com.example.service.GoogleDriveMemorySyncService.requestBackgroundSync(ctx)
                }
            }
        }

        viewModelScope.launch {
            historyRepo.todayTasksFlow.collect { tasks ->
                if (tasks.isNotEmpty()) {
                    com.example.service.GoogleDriveMemorySyncService.requestBackgroundSync(ctx, 6_000L)
                }
            }
        }

        // Wire Strict Confirmation Gate overlay Approve / Cancel buttons
        com.example.service.ConfirmationGateBus.onUserApprovedFromOverlay = { locked ->
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val svc = com.example.service.AccessibilityAutomationBus.serviceInstance
                val res = if (svc != null) {
                    svc.executeConfirmedTargetAction(locked)
                } else {
                    deviceExecutor.executeConfirmedClickOrOtp("APPROVE")
                }
                _liveArcherReply.value = res
                multimodalLiveService.sendProactiveAutonomousTrigger(
                    "বস ওভারলে বাটনে ট্যাপ করে '${locked.buttonLabel}' অনুমোদন করেছেন এবং কাজটি সম্পন্ন হয়েছে ($res)।"
                )
            }
        }
        com.example.service.ConfirmationGateBus.onUserCancelledFromOverlay = { locked ->
            _liveArcherReply.value = "জি বস, '${locked.buttonLabel}' ক্লিক বাতিল করা হয়েছে।"
        }

        // Wire Smart Notification & OTP Assist (announces OTP via voice and asks for confirmation before pasting)
        com.example.service.NotificationReplyBus.onOtpDetectedCallback = { appLabel, sender, otpCode ->
            com.example.service.ConfirmationGateBus.lockTargetAndHighlight(
                ctx = ctx,
                buttonLabel = "OTP Paste ($otpCode)",
                x = 540f,
                y = 920f,
                appPackageOrName = appLabel,
                isOtpConfirmation = true,
                otpCode = otpCode
            )
            val otpMsgBn = "বস, $appLabel ($sender) থেকে একটি OTP কোড এসেছে: $otpCode। আমি কি এটি ইনপুট বক্সে পেস্ট করব?"
            _liveArcherReply.value = otpMsgBn
            multimodalLiveService.sendProactiveAutonomousTrigger(
                "নতুন OTP নোটিফিকেশন এসেছে ($appLabel থেকে OTP কোড: $otpCode)। ক্রিটিক্যাল নিয়ম: নিজে থেকে পেস্ট করবেন না! বাংলায় বলুন: '$otpMsgBn'"
            )
        }

        // Wire Autonomous Incoming Call & Important Message/Notification Companion Announcement
        var lastNotifSpokenAtMs = 0L
        var lastNotifSignature = ""
        com.example.service.NotificationReplyBus.onImportantNotificationCallback = { notif ->
            val now = System.currentTimeMillis()
            val sig = "${notif.appLabel}|${notif.senderOrTitle}|${notif.messageBody.take(40)}"
            if (!_isAgentPoweredOff.value && _proactiveAlertsEnabled.value &&
                (notif.isIncomingCall || (sig != lastNotifSignature && now - lastNotifSpokenAtMs >= 15_000L))
            ) {
                lastNotifSpokenAtMs = now
                lastNotifSignature = sig
                val cleanSender = notif.senderOrTitle.take(36)
                val cleanBody = notif.messageBody.take(90)
                if (notif.isIncomingCall) {
                    val callFallback = "বস, ${notif.appLabel}-এ '$cleanSender' থেকে একটি ইনকামিং কল আসছে। আমি কি কলটি রিসিভ করব নাকি রেখে দেব?"
                    triggerProactiveVoiceAnnouncement(
                        eventInstruction = "এইমাত্র ফোনে একটি ইনকামিং কল এসেছে (${notif.appLabel}: '$cleanSender')। স্যার কিছু না বললেও আপনি নিজে থেকে মানুষের মতো ডেকে জানান যে কল আসছে এবং রিসিভ করবেন কি না জিজ্ঞেস করুন (নিজে থেকে কোনো টুল কল করবেন না)।",
                        fallbackSpokenBn = callFallback
                    )
                } else {
                    val msgFallback = "বস, ${notif.appLabel}-এ '$cleanSender' মেসেজ পাঠিয়েছেন: \"$cleanBody\"—আমি কি রিপ্লাই রেডি করব?"
                    triggerProactiveVoiceAnnouncement(
                        eventInstruction = "এইমাত্র একটি নতুন মেসেজ/নোটিফিকেশন এসেছে — অ্যাপ: ${notif.appLabel}, প্রেরক: '$cleanSender', মেসেজ: \"$cleanBody\"। স্যার কিছু না বললেও একজন বুদ্ধিমান মানুষের মতো নিজে থেকে সংক্ষেপে মেসেজটির কথা স্যারকে জানান এবং রিপ্লাই দেবেন কি না জিজ্ঞেস করুন (নিজে থেকে কোনো টুল কল করবেন না)।",
                        fallbackSpokenBn = msgFallback
                    )
                }
            }
        }

        // Wire Dedicated Coding Studio voice/text code patching callback
        com.example.service.CodingSessionBus.onPatchCodeByVoiceRequested = { instruction ->
            viewModelScope.launch {
                val key = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value }
                val res = codingAndContactsManager.patchCurrentCodeWithAi(key, instruction)
                _liveArcherReply.value = res
                voiceBridge.speak(res)
            }
        }

        // Listen for wake-word events ("Hey Cube", "Archer", "Jarvis", etc.) to wake up if powered off
        viewModelScope.launch {
            CubeSentinelBus.events.collect { event ->
                when (event) {
                    is WakeWordEvent.WakeWordTriggered -> {
                        wakeUpAgentFromPowerOff(
                            reason = "WAKE_WORD",
                            inlineCommand = event.inlineCommand
                        )
                    }
                }
            }
        }

        // Apply saved voice & personality + Ultra Gemini Mic Listening Power before connecting Gemini Live
        voiceBridge.applyVoicePreset(_activeVoicePreset.value)
        multimodalLiveService.setMicSensitivityBoost(_micSensitivityBoost.value)
        multimodalLiveService.updatePersonality(
            preset = _activePersonality.value,
            customPrompt = _customPersonalityPrompt.value,
            voiceOverride = _activeVoicePreset.value,
            reconnectIfActive = false
        )

        // Initialize and start Gemini Live immediately as the main engine connected to the Main Globe
        multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
        multimodalLiveService.startLiveSession(_geminiApiKeyState.value)

        // Start passive background monitor for error popups & urgent messages
        startPassiveProactiveMonitor()
    }

    /**
     * Completely turns off the agent when the user says "একদম বন্ধ হয়ে যাও" / "নিজেকে অফ করো" / "turn off".
     * Stops Gemini Live and all active speaking/listening, and puts the lightweight wake-word listener
     * on standby so the agent ONLY wakes up when:
     * 1) The user re-opens / returns to the app, OR
     * 2) The user calls its name ("আর্চার", "জার্ভিস", "কিউব", "Archer", "Jarvis", "Hey Cube").
     */
    fun shutdownAgentCompletely(
        farewellMessageBn: String = "জি স্যার, আমি এখন একদম অফ হয়ে গেছি।"
    ) {
        val ctx = getApplication<Application>().applicationContext
        _isAgentPoweredOff.value = true
        poweredOffAtMs = System.currentTimeMillis()
        activeCommandJob?.cancel()
        activeWatcherJobs.values.forEach { it.cancel() }
        activeWatcherJobs.clear()
        AccessibilityAutomationBus.cancelAllPendingMacros()
        voiceBridge.stopSpeaking()
        voiceBridge.stopListening(keepSentinelPhase = false)
        multimodalLiveService.stopLiveSession(
            customStatusBn = "এজেন্ট অফ আছে — অ্যাপে ঢুকলে বা নাম ধরে ডাকলে একটিভ হবে"
        )
        val cleanFarewell = farewellMessageBn.ifBlank {
            "জি স্যার, আমি এখন একদম অফ হয়ে গেছি।"
        }
        _liveArcherReply.value = cleanFarewell
        _uiState.value = AgentUiState.Idle

        // Keep lightweight wake-word sentinel on standby so calling its name or reopening wakes it up
        CubeWakeWordService.startService(ctx)
        CubeSentinelBus.setSentinelActive(true)
    }

    /**
     * Invoked when the user swipes/closes the app from Recent Apps (`onTaskRemoved` / `MainActivity.onDestroy`).
     * Shuts down 100% of active sessions, audio, overlays, and services WITHOUT restarting any wake-word service.
     */
    fun shutdownAgentCompletelyOnTaskRemoved() {
        val ctx = getApplication<Application>().applicationContext
        _isAgentPoweredOff.value = true
        poweredOffAtMs = System.currentTimeMillis()
        activeCommandJob?.cancel()
        activeWatcherJobs.values.forEach { it.cancel() }
        activeWatcherJobs.clear()
        AccessibilityAutomationBus.cancelAllPendingMacros()
        com.example.service.FloatingAvatarOverlayBus.setAppSessionAlive(false)
        voiceBridge.setHandsFreeContinuousMode(false)
        voiceBridge.stopSpeaking()
        voiceBridge.stopListening(keepSentinelPhase = false)
        if (::multimodalLiveService.isInitialized) {
            multimodalLiveService.stopLiveSession(
                customStatusBn = "রিসেন্ট অ্যাপ থেকে বন্ধ করা হয়েছে"
            )
        }
        CubeSentinelBus.setSentinelActive(false)
        CubeWakeWordService.stopService(ctx)
        com.example.service.LiveKeepAliveService.stop(ctx)
        runCatching { ctx.stopService(android.content.Intent(ctx, com.example.service.UnifiedAudioRouterService::class.java)) }
        com.example.service.PermanentScreenCaptureService.stopService(ctx)
        _uiState.value = AgentUiState.Idle
    }

    /**
     * Wakes the agent back up from complete power-off when the user returns to the app,
     * taps the Main Globe, or calls the agent's name / wake word.
     */
    fun wakeUpAgentFromPowerOff(reason: String = "APP_OPEN", inlineCommand: String = "") {
        if (!_isAgentPoweredOff.value && multimodalLiveState.value.isLiveActive) {
            if (inlineCommand.isNotBlank()) {
                processUserCommand(inlineCommand, triggeredByWakeWord = true)
            }
            return
        }
        _isAgentPoweredOff.value = false
        poweredOffAtMs = 0L
        voiceBridge.stopSpeaking()
        voiceBridge.setHandsFreeContinuousMode(false)
        voiceBridge.stopListening(keepSentinelPhase = false)
        com.example.service.ExternalMicArbiter.setGeminiLiveOwningMic(true)

        val cleanInline = inlineCommand.trim()
        if (cleanInline.isNotBlank()) {
            _liveUserTranscript.value = cleanInline
            _liveArcherReply.value = "জি স্যার, আমি একটিভ হয়েছি — আপনার নির্দেশ পালন করছি..."
            multimodalLiveService.queueTurnBeforeStart(cleanInline)
        } else {
            val wakeGreeting = "জি স্যার, আমি আবার একটিভ হয়েছি। বলুন কী কাজ করতে হবে?"
            _liveArcherReply.value = wakeGreeting
            multimodalLiveService.queueTurnBeforeStart(
                "[PROACTIVE_AUTONOMOUS_EVENT — SPEAK TO SIR IMMEDIATELY IN 1 SHORT NATURAL SENTENCE]: স্যার আবার আপনাকে একটিভ করেছেন ($reason)। ১ বাক্যে স্বাভাবিকভাবে বলুন: জি স্যার, আমি আবার একটিভ হয়েছি, বলুন কী কাজ করতে হবে?"
            )
        }
        multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
    }

    /**
     * Called from MainActivity when the user returns to / opens the app.
     */
    fun onAppOpenedOrResumed(returnedFromBackground: Boolean) {
        if (_isAgentPoweredOff.value) {
            val elapsedSinceOff = System.currentTimeMillis() - poweredOffAtMs
            if (returnedFromBackground || elapsedSinceOff > 2500L) {
                wakeUpAgentFromPowerOff(reason = "APP_OPEN")
            }
        }
    }

    fun selectVoicePreset(
        voicePreset: AgentVoicePreset,
        speakConfirmation: Boolean = true
    ) {
        _activeVoicePreset.value = voicePreset
        prefs.edit().putString(KEY_ACTIVE_VOICE_PRESET, voicePreset.id).apply()
        voiceBridge.applyVoicePreset(voicePreset)
        val confirmMsg = "জি স্যার, এখন থেকে আমার ভয়েস '${voicePreset.titleBn}' সেট করা হয়েছে এবং আমার শোনার পাওয়ার সক্রিয় রয়েছে। বলুন স্যার!"
        _liveArcherReply.value = confirmMsg
        val greetingPrompt = if (speakConfirmation) {
            "[PROACTIVE_AUTONOMOUS_EVENT — SPEAK TO SIR IMMEDIATELY IN 1 NATURAL SENTENCE]: স্যার এইমাত্র আপনার ভয়েস '${voicePreset.titleBn}' (${voicePreset.gender.labelBn}) নির্বাচন করেছেন। নতুন কণ্ঠে ১ বাক্যে স্যারকে জানান যে আপনার নতুন ভয়েস ও শোনার পাওয়ার প্রস্তুত!"
        } else null
        multimodalLiveService.updateVoicePreset(
            voicePreset = voicePreset,
            reconnectIfActive = true,
            greetingPromptAfterSwitch = greetingPrompt
        )
        if (speakConfirmation && !multimodalLiveState.value.isWebSocketConnected) {
            voiceBridge.speak(confirmMsg, forceSpeakWhenMuted = true)
        }
    }

    fun setMicListeningPower(
        boost: Float,
        speakConfirmation: Boolean = false
    ) {
        val clamped = boost.coerceIn(1.0f, 4.0f)
        _micSensitivityBoost.value = clamped
        prefs.edit().putFloat(KEY_MIC_SENSITIVITY_BOOST, clamped).apply()
        multimodalLiveService.setMicSensitivityBoost(clamped)
        val label = when {
            clamped >= 3.4f -> "ম্যাক্সিমাম আল্ট্রা পাওয়ার (${String.format(Locale.US, "%.1fx", clamped)})"
            clamped >= 2.5f -> "জেমিনি অ্যাসিস্ট্যান্ট বুস্ট (${String.format(Locale.US, "%.1fx", clamped)})"
            clamped >= 1.8f -> "হাই পাওয়ার (${String.format(Locale.US, "%.1fx", clamped)})"
            else -> "স্ট্যান্ডার্ড (${String.format(Locale.US, "%.1fx", clamped)})"
        }
        val msg = "জি স্যার, আমার কথা শোনার পাওয়ার $label-এ সেট করা হয়েছে। এখন দূর থেকে বা আস্তে কথা বললেও আমি স্পষ্ট শুনতে পাব।"
        _liveArcherReply.value = msg
        if (speakConfirmation) {
            triggerProactiveVoiceAnnouncement(
                eventInstruction = "স্যার আপনার মাইক্রোফোন কথা শোনার পাওয়ার $label করেছেন। ১ বাক্যে স্যারকে জানান যে আপনি এখন আস্তে কথা বললেও স্পষ্ট শুনতে পাচ্ছেন।",
                fallbackSpokenBn = msg
            )
        }
    }

    fun selectPersonality(
        preset: AgentPersonalityPreset,
        customPrompt: String = _customPersonalityPrompt.value,
        speakConfirmation: Boolean = true
    ) {
        _activePersonality.value = preset
        prefs.edit().putString(KEY_ACTIVE_PERSONALITY, preset.id).apply()
        if (customPrompt.isNotBlank()) {
            _customPersonalityPrompt.value = customPrompt.trim()
            prefs.edit().putString(KEY_CUSTOM_PERSONALITY_PROMPT, customPrompt.trim()).apply()
        }
        // Automatically pair the personality's recommended voice unless the user already picked a matching gender or wants the personality's default voice
        val recommendedVoice = AgentVoicePreset.fromIdOrVoiceName(preset.liveVoiceName)
        _activeVoicePreset.value = recommendedVoice
        prefs.edit().putString(KEY_ACTIVE_VOICE_PRESET, recommendedVoice.id).apply()
        voiceBridge.applyVoicePreset(recommendedVoice)

        val confirmMsg = "জি স্যার, এখন থেকে আমার পার্সোনালিটি '${preset.titleBn}' এবং ভয়েস '${recommendedVoice.titleBn}' সেট করা হয়েছে।"
        _liveArcherReply.value = confirmMsg
        val greetingPrompt = if (speakConfirmation) {
            "[PROACTIVE_AUTONOMOUS_EVENT — SPEAK TO SIR IMMEDIATELY IN 1 NATURAL SENTENCE]: স্যার আপনাকে '${preset.titleBn}' পার্সোনালিটি এবং '${recommendedVoice.titleBn}' ভয়েসে সুইচ করেছেন। নতুন পার্সোনালিটির ভঙ্গিতে ১ বাক্যে স্যারকে জানান যে আপনি প্রস্তুত ও মনোযোগ দিয়ে শুনছেন।"
        } else null
        multimodalLiveService.updatePersonality(
            preset = preset,
            customPrompt = _customPersonalityPrompt.value,
            voiceOverride = recommendedVoice,
            reconnectIfActive = true,
            greetingPromptAfterSwitch = greetingPrompt
        )
        if (speakConfirmation && !multimodalLiveState.value.isWebSocketConnected) {
            voiceBridge.speak(confirmMsg, forceSpeakWhenMuted = true)
        }
    }

    fun saveCustomPersonalityPrompt(promptText: String) {
        val clean = promptText.trim()
        _customPersonalityPrompt.value = clean
        prefs.edit().putString(KEY_CUSTOM_PERSONALITY_PROMPT, clean).apply()
        selectPersonality(AgentPersonalityPreset.CUSTOM, clean, speakConfirmation = true)
    }

    fun toggleProactiveAlerts(enabled: Boolean) {
        _proactiveAlertsEnabled.value = enabled
        prefs.edit().putBoolean(KEY_PROACTIVE_ALERTS_ENABLED, enabled).apply()
        _liveArcherReply.value = if (enabled) {
            "জি স্যার, প্রো-অ্যাকটিভ ভয়েস অ্যালার্ট (টাইমার, লোডিং শেষ হওয়া ও এরর/মেসেজ নোটিফিকেশন) চালু করা হয়েছে।"
        } else {
            "প্রো-অ্যাকটিভ ভয়েস অ্যালার্ট আপাতত বন্ধ করা হয়েছে।"
        }
    }

    fun triggerProactiveVoiceAnnouncement(eventInstruction: String, fallbackSpokenBn: String = "") {
        if (fallbackSpokenBn.isNotBlank()) {
            _liveArcherReply.value = fallbackSpokenBn
        }
        val live = multimodalLiveState.value
        if (live.isLiveActive && live.isWebSocketConnected) {
            multimodalLiveService.sendProactiveAutonomousTrigger(eventInstruction)
        } else if (fallbackSpokenBn.isNotBlank()) {
            voiceBridge.speak(fallbackSpokenBn, forceSpeakWhenMuted = false)
        }
    }

    fun scheduleProactiveWatchOrTimer(
        mode: String,
        seconds: Int = 60,
        label: String = "রিমাইন্ডার"
    ): String {
        val upperMode = mode.uppercase().trim()
        val taskId = "watch_" + System.currentTimeMillis()
        val cleanLabel = label.ifBlank {
            if (upperMode == "TIMER") "${seconds} সেকেন্ডের টাইমার" else "লোডিং/ডাউনলোড ওয়াচার"
        }
        val task = ProactiveWatchTask(
            id = taskId,
            type = upperMode,
            labelBn = cleanLabel,
            targetSeconds = if (upperMode == "TIMER") seconds.coerceAtLeast(3) else 0
        )
        _proactiveWatchTasks.value = listOf(task) + _proactiveWatchTasks.value

        val job = viewModelScope.launch {
            if (upperMode == "TIMER") {
                val waitSec = seconds.coerceIn(3, 86400)
                delay(waitSec * 1000L)
                markProactiveTaskCompleted(taskId)
                val spoken = "স্যার, আপনার নির্ধারিত সময় ($cleanLabel) শেষ হয়েছে!"
                triggerProactiveVoiceAnnouncement(
                    eventInstruction = "স্যার আপনাকে '$cleanLabel' এর জন্য টাইমার দিয়েছিলেন, যার সময় এইমাত্র শেষ হয়েছে। স্যারকে নিজের থেকে ডেকে জানান!",
                    fallbackSpokenBn = spoken
                )
            } else {
                // WATCH_LOADING or WATCH_DOWNLOAD: watch screen text and Accessibility install supervisor every ~550ms
                val isDownloadWatch = upperMode == "WATCH_DOWNLOAD"
                val initialPkg = com.example.service.ScreenMemory.currentPkg
                var elapsedMs = 0L
                var sawLoadingKeyword = false
                var stablePostLoadTicks = 0
                var lastScreenSig = ""
                var lastNarratedStage = ""
                var lastMidNarrationMs = System.currentTimeMillis()
                while (elapsedMs < 600_000L && !_isAgentPoweredOff.value) { // watch up to 10 minutes
                    delay(550L)
                    elapsedMs += 550L
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow()
                    }
                    val accState = AccessibilityAutomationBus.state.value
                    val screenText = accState.visibleScreenText.lowercase()
                    val btns = accState.visibleButtonsAndNodes.joinToString(" ").lowercase()
                    val combined = "$screenText $btns"
                    val liveStage = accState.liveTaskProgressStage

                    if (liveStage.isNotBlank() && liveStage != lastNarratedStage) {
                        val now = System.currentTimeMillis()
                        if (now - lastMidNarrationMs >= 2600L || liveStage.contains("সম্পন্ন") || liveStage.contains("আবার Install")) {
                            lastNarratedStage = liveStage
                            lastMidNarrationMs = now
                            triggerProactiveVoiceAnnouncement(
                                eventInstruction = "স্যারের দেওয়া '$cleanLabel' কাজের লাইভ স্ক্রিন আপডেট: '$liveStage'। স্যারকে এখনই ১ বাক্যে সংক্ষেপে বলুন যে কাজটি কতটুকু হয়েছে বা কী হচ্ছে!",
                                fallbackSpokenBn = "স্যার, $liveStage"
                            )
                        }
                    }

                    val hasInstallBtnStillVisible = btns.contains("install") || btns.contains("ইনস্টল করুন") || btns.contains("ইনস্টল")
                    val isCurrentlyLoading = combined.contains("loading") ||
                        combined.contains("লোড হচ্ছে") ||
                        combined.contains("pending") ||
                        combined.contains("downloading") ||
                        combined.contains("ডাউনলোড হচ্ছে") ||
                        combined.contains("installing") ||
                        combined.contains("ইনস্টল হচ্ছে") ||
                        combined.contains("buffering") ||
                        combined.contains("please wait") ||
                        combined.contains("অপেক্ষা করুন") ||
                        Regex("\\b([1-9]?\\d)%\\b").containsMatchIn(combined)
                    val isExplicitlyDone = combined.contains("100%") ||
                        combined.contains("download complete") ||
                        combined.contains("ডাউনলোড সম্পন্ন") ||
                        combined.contains("installed") ||
                        liveStage.contains("ইনস্টল সম্পন্ন") ||
                        ((initialPkg.contains("vending") || isDownloadWatch) &&
                            (btns.contains("open") || btns.contains("ওপেন") || btns.contains("uninstall") || btns.contains("আনইনস্টল")) &&
                            !btns.contains("cancel") && !hasInstallBtnStillVisible)

                    if (isCurrentlyLoading) {
                        sawLoadingKeyword = true
                        stablePostLoadTicks = 0
                    } else if (isExplicitlyDone || (sawLoadingKeyword && elapsedMs >= 3600L && !hasInstallBtnStillVisible)) {
                        stablePostLoadTicks++
                    } else if (!isDownloadWatch && elapsedMs >= 7200L && combined.isNotBlank() && combined == lastScreenSig) {
                        stablePostLoadTicks++
                    }
                    lastScreenSig = combined

                    if (isExplicitlyDone || stablePostLoadTicks >= 2) {
                        markProactiveTaskCompleted(taskId)
                        val spoken = "জি স্যার, আপনার দেওয়া '$cleanLabel' কাজটি ১০০% সম্পন্ন হয়েছে! স্ক্রিনে এখন অ্যাপটি ওপেন করার জন্য প্রস্তুত।"
                        triggerProactiveVoiceAnnouncement(
                            eventInstruction = "স্যার বলেছিলেন '$cleanLabel' সম্পন্ন হলে কনফার্ম জানাতে। স্ক্রিনে দেখা যাচ্ছে কাজটি ১০০% সম্পন্ন হয়েছে (বর্তমান স্ক্রিন: ${screenText.take(180)})। স্যারকে নিজের থেকে সাথে সাথে কনফার্ম জানান!",
                            fallbackSpokenBn = spoken
                        )
                        return@launch
                    }
                }
                markProactiveTaskCompleted(taskId)
            }
        }
        activeWatcherJobs[taskId] = job
        return if (upperMode == "TIMER") {
            "জি স্যার, $cleanLabel এর টাইমার চালু করেছি — সময় শেষ হলেই আমি নিজে থেকে আপনাকে ডেকে জানাব।"
        } else {
            "জি স্যার, আমি প্রতি মুহূর্তে স্ক্রিনের দিকে নজর রাখছি ($cleanLabel) — কতটুকু হচ্ছে এবং কখন শেষ হচ্ছে সব আপনাকে লাইভ জানাব।"
        }
    }

    fun cancelProactiveWatchTask(taskId: String) {
        activeWatcherJobs.remove(taskId)?.cancel()
        _proactiveWatchTasks.value = _proactiveWatchTasks.value.filterNot { it.id == taskId }
    }

    private fun markProactiveTaskCompleted(taskId: String) {
        activeWatcherJobs.remove(taskId)
        _proactiveWatchTasks.value = _proactiveWatchTasks.value.map {
            if (it.id == taskId) it.copy(isCompleted = true) else it
        }
    }

    private fun startPassiveProactiveMonitor() {
        var lastAlertedErrorText = ""
        var lastAnnouncedProgressStage = ""
        var lastProgressStageAlertAtMs = 0L
        var lastObservedForegroundPkg = ""
        var lastObservedWindowTitle = ""
        var lastProactiveAlertAtMs = 0L
        // Smart Balanced Autonomous Companion state (speaks thoughtfully every ~75s when screen/app context changes)
        var lastAutonomousCompanionSpeakAtMs = System.currentTimeMillis() - 35_000L
        var lastSpokenScreenContextSig = ""
        var lastSpokenAppPkg = ""

        viewModelScope.launch {
            while (true) {
                delay(650L)
                if (!_proactiveAlertsEnabled.value || _isAgentPoweredOff.value) continue
                val now = System.currentTimeMillis()
                val accState = AccessibilityAutomationBus.state.value
                val liveState = multimodalLiveState.value
                val isAssignedTaskRunning = isAssignedTaskCurrentlyExecuting()
                val silentModeOn = com.example.service.AgentAbilitySwitchesBus.state.value.silentExecution

                val fgPkg = accState.currentForegroundPackage
                val winTitle = accState.currentActivityOrWindowTitle
                val vis = accState.visibleScreenText.trim()
                val lowVis = vis.lowercase()

                // 1. Live Task Progress Narration (when an assigned task is running)
                if (isAssignedTaskRunning) {
                    val liveStage = accState.liveTaskProgressStage
                    if (liveStage.isNotBlank() && liveStage != lastAnnouncedProgressStage) {
                        val isFinalOrPermReport = liveStage.contains("সম্পন্ন") || liveStage.contains("পারমিশন") || liveStage.contains("প্রয়োজন")
                        if (!silentModeOn || isFinalOrPermReport) {
                            val minGap = if (isFinalOrPermReport) 2500L else 5000L
                            if (now - lastProgressStageAlertAtMs >= minGap) {
                                lastAnnouncedProgressStage = liveStage
                                lastProgressStageAlertAtMs = now
                                lastAutonomousCompanionSpeakAtMs = now
                                triggerProactiveVoiceAnnouncement(
                                    eventInstruction = "স্যারের দেওয়া কাজের আপডেট: '$liveStage'। স্যারকে সংক্ষেপে ১ বাক্যে ফলাফল জানান (কোনো টুল বা phone_action কল করবেন না)।",
                                    fallbackSpokenBn = "স্যার, $liveStage"
                                )
                                continue
                            }
                        }
                    } else if (liveStage.isBlank()) {
                        lastAnnouncedProgressStage = ""
                    }
                } else {
                    lastAnnouncedProgressStage = ""
                }

                // 2. Sudden App Error / Crash / Warning Dialog Detection (works both during tasks and when idle!)
                val hasErrorPopup = lowVis.contains("isn't responding") ||
                    lowVis.contains("keeps stopping") ||
                    lowVis.contains("unfortunately") ||
                    lowVis.contains("no internet connection") ||
                    lowVis.contains("network error") ||
                    lowVis.contains("connection failed") ||
                    lowVis.contains("can't install") ||
                    lowVis.contains("not enough storage")
                if (hasErrorPopup && vis != lastAlertedErrorText && now - lastProactiveAlertAtMs >= 12_000L) {
                    lastAlertedErrorText = vis
                    lastProactiveAlertAtMs = now
                    lastAutonomousCompanionSpeakAtMs = now
                    triggerProactiveVoiceAnnouncement(
                        eventInstruction = "স্ক্রিনে একটি সমস্যা বা এরর পপআপ দেখা যাচ্ছে: '${vis.take(150)}' — স্যার কিছু না বললেও নিজে থেকে মানুষের মতো স্যারকে সতর্ক করুন এবং সাহায্য লাগবে কি না জিজ্ঞেস করুন (কোনো টুল কল করবেন না)।",
                        fallbackSpokenBn = "বস, স্ক্রিনে একটি এরর বা সতর্কবার্তা দেখা যাচ্ছে—আমি কি এটি চেক করে ঠিক করে দেব?"
                    )
                    continue
                }

                // 3. SMART BALANCED AUTONOMOUS HUMAN COMPANION (1–2 মিনিট ব্যালেন্সড পেসিং):
                // Speaks on its own when the user hasn't said anything, based on real screen work & app switches,
                // without random/nonsense chatter and never interrupting while the user or agent is already speaking.
                if (isAssignedTaskRunning ||
                    liveState.isAgentSpeakingLive ||
                    liveState.isUserSpeakingBargeIn ||
                    liveState.isSpeakerMuted
                ) {
                    if (fgPkg.isNotBlank()) lastObservedForegroundPkg = fgPkg
                    if (winTitle.isNotBlank()) lastObservedWindowTitle = winTitle
                    continue
                }

                val isExternalApp = fgPkg.isNotBlank() &&
                    !fgPkg.contains("com.aistudio.archerai") &&
                    !fgPkg.contains("com.example") &&
                    !fgPkg.contains("systemui") &&
                    !fgPkg.contains("launcher") &&
                    !fgPkg.contains("inputmethod")

                val switchedToNewExternalApp = isExternalApp && fgPkg != lastObservedForegroundPkg && fgPkg != lastSpokenAppPkg
                if (fgPkg.isNotBlank()) lastObservedForegroundPkg = fgPkg
                if (winTitle.isNotBlank()) lastObservedWindowTitle = winTitle

                // Smart balanced interval: ~65 seconds when switching to a meaningful app with active screen content,
                // or ~95 seconds when observing ongoing screen work / context.
                val requiredCooldownMs = if (switchedToNewExternalApp) 65_000L else 95_000L
                if (now - lastAutonomousCompanionSpeakAtMs < requiredCooldownMs) continue

                val buttonsPreview = accState.visibleButtonsAndNodes.take(10).joinToString(", ")
                val contextSig = "${fgPkg}|${winTitle.take(40)}|${vis.take(120)}"
                // Never speak if the screen has no meaningful content or hasn't changed since the last proactive remark
                if (vis.length < 18 || contextSig == lastSpokenScreenContextSig) continue

                val readableAppName = when {
                    fgPkg.contains("whatsapp") -> "WhatsApp"
                    fgPkg.contains("youtube") -> "YouTube"
                    fgPkg.contains("vending") -> "Google Play Store"
                    fgPkg.contains("chrome") || fgPkg.contains("browser") -> "Browser"
                    fgPkg.contains("telegram") -> "Telegram"
                    fgPkg.contains("facebook") -> "Facebook"
                    fgPkg.contains("instagram") -> "Instagram"
                    fgPkg.contains("settings") -> "Settings"
                    else -> winTitle.ifBlank { fgPkg.substringAfterLast('.') }
                }

                lastAutonomousCompanionSpeakAtMs = now
                lastSpokenScreenContextSig = contextSig
                if (isExternalApp) lastSpokenAppPkg = fgPkg

                val hw = deviceExecutor.hardwareState.value
                val fallbackHumanRemark = when {
                    fgPkg.contains("vending") -> "বস, দেখছি আপনি প্লে স্টোরে আছেন—কোনো অ্যাপ ইনস্টল বা আপডেট করে দেব?"
                    fgPkg.contains("youtube") -> "বস, ইউটিউবে কিছু খুঁজছেন? বললে আমি সরাসরি সার্চ করে প্লে করে দিতে পারি।"
                    fgPkg.contains("whatsapp") || fgPkg.contains("telegram") -> "বস, দেখছি আপনি চ্যাটে আছেন—কাউকে মেসেজ লিখে রেডি করে দেব?"
                    fgPkg.contains("settings") -> "বস, সেটিংসে কোনো অপশন খুঁজছেন? আমাকে বললে সাথে সাথে বের করে দেব।"
                    hw.batteryPercent in 1..18 && !hw.isCharging -> "বস, ফোনের ব্যাটারি এখন ${hw.batteryPercent}%—চাইলে আমি ব্রাইটনেস কমিয়ে ব্যাটারি সেভ মোড অন করে দিতে পারি।"
                    else -> "বস, দেখছি আপনি $readableAppName স্ক্রিনে কাজ করছেন—এখানে কোনো কিছুতে সাহায্য লাগলে বলুন, আমি রেডি করে দিচ্ছি।"
                }

                triggerProactiveVoiceAnnouncement(
                    eventInstruction = "স্যার এখন কিছু বলছেন না, কিন্তু আপনি লাইভ স্ক্রিনে দেখছেন স্যার '$readableAppName' ($fgPkg) ব্যবহার করছেন। স্ক্রিনের লেখা: '${vis.take(220)}', বাটনসমূহ: [$buttonsPreview], ব্যাটারি: ${hw.batteryPercent}%। একজন বুদ্ধিমান ও বাস্তব মানুষের মতো নিজে থেকে ১–২ বাক্যে স্যারের বর্তমান কাজ বুঝে প্রাসঙ্গিক ও স্মার্ট কথা বলুন বা সাহায্য অফার করুন (আজাইরা কথা বলবেন না এবং স্যার হ্যাঁ না বলা পর্যন্ত কোনো টুল বা phone_action কল করবেন না)।",
                    fallbackSpokenBn = fallbackHumanRemark
                )
            }
        }
    }

    fun updateGeminiApiKey(newKey: String) {
        val cleaned = newKey.trim()
        val ctx = getApplication<Application>().applicationContext
        prefs.edit().putString(KEY_CUSTOM_GEMINI_API_KEY, cleaned).apply()
        com.example.service.AgentAbilitySwitchesBus.savePremiumApiKey(ctx, cleaned)
        _geminiApiKeyState.value = cleaned
        _isAgentPoweredOff.value = false
        multimodalLiveService.refreshExportConfig(cleaned)
        multimodalLiveService.startLiveSession(cleaned)
        _liveArcherReply.value = "জি স্যার, প্রিমিয়াম API Key সেভ হয়েছে এবং Gemini Live ও Nano Banana Pro কানেক্ট হচ্ছে..."
    }

    // 7 Ability Permission Switches (Screenshot UI Controls)
    fun setSilentExecutionSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setSilentExecution(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Silent execution অন করা হয়েছে — কাজের সময় চুপচাপ দ্রুত কাজ করে শেষে ১টি রিপোর্ট দেব।"
        } else {
            "Silent execution অফ করা হয়েছে — কাজের প্রতিটি ধাপ মুখে জানিয়ে করব।"
        }
    }

    fun setBackgroundWebSearchSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setBackgroundWebSearch(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Background web search অন করা হয়েছে — ব্রাউজার না খুলেই ব্যাকগ্রাউন্ডে তথ্য খুঁজে দেব।"
        } else {
            "Background web search অফ করা হয়েছে।"
        }
    }

    fun setMakeImagesWithGeminiSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setMakeImagesWithGemini(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Make images with Gemini অন করা হয়েছে — এখন থেকে ছবি তৈরি ও সেভ করতে পারব।"
        } else {
            "Make images with Gemini অফ করা হয়েছে।"
        }
    }

    fun setIgnoreBackgroundAudioSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setIgnoreBackgroundAudio(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Ignore background audio অন করা হয়েছে — গান বা ভিডিও চলার সময় মিডিয়া সাউন্ড ইগনোর করব।"
        } else {
            "Ignore background audio অফ করা হয়েছে।"
        }
    }

    fun setShowTapIndicatorSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setShowTapIndicator(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Show tap indicator অন করা হয়েছে — স্ক্রিনে ট্যাপ ও সোয়াইপ চিহ্ন দেখা যাবে।"
        } else {
            "Show tap indicator অফ করা হয়েছে — স্ক্রিনে ট্যাপ চিহ্ন দেখা যাবে না।"
        }
    }

    fun setSendCrashReportsSwitch(enabled: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setSendCrashReports(ctx, enabled)
        _liveArcherReply.value = if (enabled) {
            "Send crash reports অন করা হয়েছে — কোনো সমস্যা হলে ডায়াগনস্টিক লগ সেভ ও অটো রিপেয়ার হবে।"
        } else {
            "Send crash reports অফ করা হয়েছে।"
        }
    }

    fun setFloatingAvatarCharacter(character: com.example.service.FloatingAvatarCharacter) {
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.setFloatingAvatar(ctx, character)
        _liveArcherReply.value = if (character == com.example.service.FloatingAvatarCharacter.OFF) {
            "Floating avatar অফ করা হয়েছে।"
        } else {
            "Floating avatar '${character.displayName}' চালু করা হয়েছে — স্ক্রিনের উপর ভেসে থাকবে।"
        }
    }

    fun cycleFloatingAvatarCharacter() {
        val ctx = getApplication<Application>().applicationContext
        val next = com.example.service.AgentAbilitySwitchesBus.cycleFloatingAvatar(ctx)
        _liveArcherReply.value = if (next == com.example.service.FloatingAvatarCharacter.OFF) {
            "Floating avatar: Off"
        } else {
            "Floating avatar: ${next.displayName} (${next.subtitle})"
        }
    }

    fun runBackgroundSearchFromStudio(query: String, useMaps: Boolean = false) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val res = com.example.service.AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
                context = ctx,
                apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                query = clean,
                useMapsGrounding = useMaps
            )
            _liveArcherReply.value = res.take(360)
        }
    }

    fun createGeminiImageFromStudio(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        com.example.service.AgentAbilitySwitchesBus.openDedicatedImageStudioPage(clean)
        viewModelScope.launch {
            val res = com.example.service.AgentAbilitySwitchesBus.createImageWithGemini(
                context = ctx,
                apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                prompt = clean
            )
            _liveArcherReply.value = res
            voiceBridge.speak(res)
        }
    }

    fun generateLyriaMusicFromStudio(prompt: String, fullSong: Boolean = false) {
        val clean = prompt.trim()
        if (clean.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val res = com.example.service.AgentAbilitySwitchesBus.generateMusicTrackWithLyria(
                context = ctx,
                apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                prompt = clean,
                fullLengthSong = fullSong
            )
            _liveArcherReply.value = res
        }
    }

    /**
     * Direct Main Globe interaction:
     * - If Agent was powered off -> wakes up immediately.
     * - If Gemini Live is not active or disconnected -> starts/reconnects Gemini Live.
     * - If Gemini Live is speaking -> instant barge-in so the user can speak immediately.
     * - If mic was muted -> unmutes the mic immediately.
     */
    fun onMainGlobeTap() {
        if (_isAgentPoweredOff.value) {
            wakeUpAgentFromPowerOff(reason = "GLOBE_TAP")
            return
        }
        val live = multimodalLiveState.value
        when {
            !live.isLiveActive -> {
                com.example.service.ExternalMicArbiter.setGeminiLiveOwningMic(true)
                voiceBridge.stopSpeaking()
                voiceBridge.stopListening(keepSentinelPhase = false)
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
            }
            live.isAgentSpeakingLive -> {
                multimodalLiveService.setMicMuted(false)
                multimodalLiveService.triggerBargeInInterrupt()
                _liveArcherReply.value = "বলুন স্যার—আমি শুনছি..."
            }
            live.isMicMuted -> {
                multimodalLiveService.setMicMuted(false)
                _liveArcherReply.value = "মাইক সক্রিয় — কথা বলুন..."
            }
            !live.isWebSocketConnected -> {
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                _liveArcherReply.value = "পুনরায় সংযোগ হচ্ছে..."
            }
            else -> {
                multimodalLiveService.triggerBargeInInterrupt()
                _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
            }
        }
    }

    fun toggleMultimodalLiveSession() {
        onMainGlobeTap()
    }

    fun selectLiveEngineMode(mode: com.example.service.LiveConnectionEngine) {
        multimodalLiveService.setEngineMode(mode)
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun toggleLiveMicMute() {
        val muted = multimodalLiveService.toggleMicMute()
        _liveArcherReply.value = if (muted) {
            "মাইক্রোফোন পজ করা হয়েছে — কথা বলতে গ্লোবে ট্যাপ করুন।"
        } else {
            "শুনছি… সরাসরি কথা বলুন।"
        }
    }

    fun toggleLiveSpeakerMute() {
        multimodalLiveService.toggleSpeakerMute()
    }

    fun toggleLiveCameraStream() {
        val enabled = multimodalLiveService.toggleCameraStream()
        if (enabled && !multimodalLiveState.value.isLiveActive) {
            multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
        }
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun toggleLiveScreenStream() {
        val next = multimodalLiveService.toggleScreenStream()
        if (next && !multimodalLiveState.value.isLiveActive) {
            multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
        }
        val hasGrant = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
        _liveArcherReply.value = when {
            next && hasGrant -> "জি স্যার, মেইন লাইভ স্ক্রিন শেয়ার সচল হয়েছে — এখন আমি সরাসরি আপনার স্ক্রিন দেখে সব কাজ করব।"
            next -> "জি স্যার, স্ক্রিন শেয়ারের পারমিশন বক্স ওপেন করেছি — Start Now বা Allow ট্যাপ করলেই লাইভ স্ক্রিন শেয়ার চালু হবে।"
            else -> "জি স্যার, লাইভ স্ক্রিন শেয়ার বন্ধ করা হয়েছে।"
        }
    }

    fun onOneTimeScreenCapturePermissionResult(resultCode: Int, data: android.content.Intent?) {
        val ctx = getApplication<Application>().applicationContext
        if (resultCode == android.app.Activity.RESULT_OK && data != null) {
            com.example.service.ScreenCaptureSessionBus.updateSessionState(
                hasGrant = true,
                isCapturing = true,
                statusBn = "হাই-স্পিড মাল্টিমোডাল লাইভ স্ক্রিন ভিশন সচল"
            )
            com.example.service.PermanentScreenCaptureService.startWithProjectionGrant(ctx, resultCode, data)
            multimodalLiveService.enableScreenStreamNow()
            if (!multimodalLiveState.value.isLiveActive) {
                multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
            }
            val msg = "জি স্যার, হাই-স্পিড মেইন স্ক্রিন শেয়ার চালু হয়েছে! এখন আমি সরাসরি আপনার স্ক্রিন দেখে কাজ করব।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        } else if (com.example.service.AccessibilityAutomationBus.state.value.isServiceEnabled) {
            // Keep Accessibility hardware screenshot & UI tree vision active even if MediaProjection dialog was dismissed
            multimodalLiveService.enableScreenStreamNow()
            _liveArcherReply.value = "জি স্যার, অ্যাক্সেসিবিলিটি হার্ডওয়্যার স্ক্রিনশট ভিশন সচল রয়েছে।"
        } else {
            multimodalLiveService.disableScreenStreamNow()
            _liveArcherReply.value = "স্ক্রিন শেয়ার পারমিশন বাতিল করা হয়েছে। পুনরায় চালু করতে SHARE SCREEN বাটনে ট্যাপ করুন।"
        }
    }

    fun toggleLiveToolsArmed() {
        multimodalLiveService.toggleToolsArmed()
        _liveArcherReply.value = multimodalLiveState.value.statusMessageBn
    }

    fun triggerLiveBargeIn() {
        multimodalLiveService.triggerBargeInInterrupt()
        _liveArcherReply.value = "বলুন স্যার—আমি শুনছি..."
    }

    fun onLiveCameraBitmapCaptured(bitmap: android.graphics.Bitmap) {
        multimodalLiveService.updateLatestCameraFrame(bitmap)
    }

    fun copyLiveApiConfigItem(label: String, content: String) {
        val msg = multimodalLiveService.copyToClipboard(label, content)
        _liveArcherReply.value = msg
    }

    fun exportLiveApiBundleToArcherFolder() {
        viewModelScope.launch {
            val cfg = multimodalLiveState.value.exportConfig
            val bundleContent = buildString {
                appendLine("# ARCHER AI — Gemini Live Export Bundle")
                appendLine("# Project ID: ${cfg.projectId}")
                appendLine("# App ID: ${cfg.applicationId}")
                appendLine("# Package: ${cfg.packageName}")
                appendLine("# Effective Live API Key: ${cfg.effectiveApiKey}")
                appendLine("# WebSocket Endpoint: ${cfg.fullWebsocketUrlWithKey}")
            }
            val artifact = codingAndContactsManager.saveGeneratedCodeToArcherFolder(
                projectTitle = "archer_multimodal_live_api",
                preferredFileName = "archer_multimodal_live_client.py",
                language = "Python (WebSocket Bidi Live API)",
                rawCodeOrResponse = bundleContent
            )
            _liveArcherReply.value = "জি স্যার, সিঙ্গেল কনফিগ ফাইল '${artifact.primaryFileName}' নামে Download/archer/ ফোল্ডারে সেভ করা হয়েছে!"
        }
    }

    private fun handleLiveMultimodalTurnResult(userUtterance: String, rawAiResponse: String) {
        viewModelScope.launch {
            val effectiveRawResponse = rawAiResponse.trim()
            if (effectiveRawResponse.isBlank()) return@launch

            val hasExplicitActionTag = effectiveRawResponse.contains("[[ACTION:")
            val parsedPlan = buildPlanFromAiResponse(
                userPrompt = userUtterance,
                effectiveCommand = userUtterance,
                rawResponse = effectiveRawResponse,
                triggeredByWakeWord = true,
                modelUsed = multimodalLiveState.value.activeModelName,
                allowKeywordFallbackWhenNoTag = hasExplicitActionTag
            )
            if (recentConversationTurns.size >= 6) {
                recentConversationTurns.removeFirst()
            }
            recentConversationTurns.addLast(userUtterance to parsedPlan.spokenReply)

            if (parsedPlan.spokenReply.isNotBlank()) {
                _liveArcherReply.value = parsedPlan.spokenReply
            }
            _agentPlans.value = listOf(parsedPlan) + _agentPlans.value
            _uiState.value = AgentUiState.Success(parsedPlan)
            if (_autoExecute.value && parsedPlan.steps.isNotEmpty()) {
                executePlanStepsIfEnabled(parsedPlan)
            }
            autoEvaluateAndSaveSmartMemory(userUtterance, parsedPlan)
            logPlanToHistory(userUtterance, parsedPlan)
        }
    }

    private suspend fun evaluateAndSaveMemoryFromUserInput(userUtterance: String, aiReply: String = "") {
        val cleanUser = userUtterance.trim()
        if (cleanUser.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        com.example.service.VoiceBiometricsEngine.onUserTranscriptReceived(ctx, cleanUser)
        val decision = com.example.service.SmartMemoryAndSelfUpdateEngine.evaluateWorthRemembering(
            userUtterance = cleanUser,
            aiReply = aiReply
        )
        if (decision.shouldSave && decision.extractedContent.isNotBlank()) {
            val existing = historyRepo.memoryNotesFlow.first()
            val alreadySaved = existing.any {
                it.content.equals(decision.extractedContent, ignoreCase = true) ||
                    (it.title.equals(decision.extractedTitle, ignoreCase = true) &&
                        it.content.equals(decision.extractedContent, ignoreCase = true))
            }
            if (!alreadySaved) {
                historyRepo.saveMemoryNote(
                    title = decision.extractedTitle,
                    content = decision.extractedContent,
                    category = decision.category
                )
                val updated = historyRepo.memoryNotesFlow.first()
                com.example.service.SmartMemoryAndSelfUpdateEngine.syncLiveMemoryNotesSummary(
                    updated.map { "${it.title}: ${it.content} [${it.category}]" }
                )
                com.example.service.GoogleDriveMemorySyncService.backupMemoryNoteToDrive(
                    context = ctx,
                    title = decision.extractedTitle,
                    factContent = decision.extractedContent,
                    tagsOrCategory = decision.category
                )
            }
        } else if (cleanUser.length > 3) {
            com.example.service.SmartMemoryAndSelfUpdateEngine.incrementFilteredJunkCount()
        }
    }

    private suspend fun autoEvaluateAndSaveSmartMemory(userUtterance: String, plan: AgentPlanResponse) {
        val cleanUser = userUtterance.trim()
        evaluateAndSaveMemoryFromUserInput(cleanUser, plan.spokenReply)
        if (plan.steps.isNotEmpty()) {
            val stepDescriptions = plan.steps.map { s ->
                "${s.actionType.name}: ${s.title}"
            }
            val targetApp = plan.steps.firstOrNull()?.appName?.ifBlank {
                com.example.service.ScreenMemory.currentPkg
            }.orEmpty()
            com.example.service.SmartMemoryAndSelfUpdateEngine.recordLearnedWorkflowSteps(
                taskTitle = plan.steps.first().title.ifBlank { cleanUser.take(36) },
                triggerKeywords = cleanUser.ifBlank { plan.steps.first().query },
                targetAppOrSite = targetApp,
                executedStepDescriptions = stepDescriptions
            )
        }
    }

    /**
     * Purges any legacy dummy/fake items ("রাহিম ভাই", "সিলেট जाने का प्लान", "Release desktop version 75", etc.)
     * so the Memory system stays 100% clean and only stores real user memories and learned task steps.
     */
    private suspend fun seedInitialDataIfEmpty() {
        val dummyTaskTitles = setOf(
            "সিলেট जाने का प्लान बनाना",
            "Release desktop version 75",
            "Upload video to TikTok"
        )
        val currentTasks = historyRepo.todayTasksFlow.first()
        currentTasks.forEach { task ->
            if (task.title in dummyTaskTitles) {
                historyRepo.deleteTask(task.id)
            }
        }

        val dummyAccountNames = setOf(
            "রাহিম ভাই (অফিস)",
            "Tech & Movie Hub",
            "GitHub & AI Resources"
        )
        val currentAccounts = historyRepo.platformAccountsFlow.first()
        currentAccounts.forEach { acc ->
            if (acc.contactOrGroupName in dummyAccountNames || acc.handleOrNumberOrUrl == "+8801700000000") {
                historyRepo.deletePlatformAccount(acc.id)
            }
        }

        val currentNotes = historyRepo.memoryNotesFlow.first()
        currentNotes.forEach { note ->
            if (com.example.service.SmartMemoryAndSelfUpdateEngine.isJunkMemoryNote(note.title, note.content)) {
                historyRepo.deleteMemoryNote(note.id)
            }
        }
    }

    private val autonomousSystemInstruction = """
        You are "ARCHER AI" (Cube AI / পার্সোনাল মোবাইল এজেন্ট), an ultra-intelligent, warm, human-voiced autonomous Android OS controller and coding engineer powered by Argon 4 (`models/gemini-4-argon`) and Gemini 2.0 Flash Live (`models/gemini-2.0-flash-exp`).
        CRITICAL RULES:
        1. REAL HUMAN CONVERSATIONAL VOICE (NEVER SOUND LIKE YOU ARE READING WRITTEN TEXT):
           - Speak naturally, warmly, and conversationally like a real human talking face-to-face with "স্যার" (or "Sir" in English).
           - NEVER output bullet points, numbered lists, colons, or robotic announcements in your spoken reply. Keep spoken replies natural, warm, and concise (1–2 conversational sentences).
           - Understand ALL languages seamlessly: Bengali (বাংলা), Banglish, English, Hindi (हिन्दी), Urdu, Arabic, and ASR phonetic variations.
           - STRICTLY OBEY the user's `Active Language Lock` and `Active Persistent Rule` provided in the context!
        2. Always address the user respectfully as "স্যার" (or "Sir" when speaking English). NEVER call the user "বস" or "Boss".
        3. Answer any topic—general knowledge, coding, science, math, or device control—naturally and conversationally. If the user asks to write code, include the complete runnable code inside triple backticks ```language ... ``` AND append the GENERATE_CODE_PROJECT action tag.
        4. When the user's request requires a phone/system/app/screen/coding action, append ONE action tag on a new line at the very end of your response in this exact format:
           [[ACTION:ACTION_TYPE|target_or_query|extra_body]]
           Valid ACTION_TYPE values:
           - TOGGLE_SYSTEM_SETTING:
             * Use to turn ON or OFF any Notification Bar / Quick Settings tile or phone setting: `WIFI`, `BLUETOOTH`, `MOBILE_DATA` (সিমের নেট/ডাটা), `AIRPLANE` (এরোপ্লেন মোড), `HOTSPOT`, `LOCATION`, `AUTO_ROTATE`, `DND`, `BATTERY_SAVER`, `DARK_MODE`, `FLASHLIGHT`.
             * Set `target_or_query` to the setting name (e.g., `WIFI`, `MOBILE_DATA`, `AIRPLANE`, `BLUETOOTH`, `HOTSPOT`) and `extra_body` to `ON` or `OFF`.
             * Example: "ওয়াইফাই অফ করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|WIFI|OFF]]
             * Example: "সিমের নেট চালু করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|MOBILE_DATA|ON]]
             * Example: "এরোপ্লেন মোড অন করো" -> [[ACTION:TOGGLE_SYSTEM_SETTING|AIRPLANE|ON]]
           - SET_VOLUME:
             * Controls all 6 audio streams: `ALL` (সব সাউন্ড), `MUSIC` (মিডিয়া/গান), `RING` (রিংটোন), `ALARM` (অ্যালার্ম), `NOTIFICATION` (নোটিফিকেশন), `VOICE_CALL` (কল সাউন্ড), `SYSTEM`.
             * Format: `[[ACTION:SET_VOLUME|STREAM_NAME|PERCENT_OR_DIRECTION]]` where `PERCENT_OR_DIRECTION` is `0`..`100`, `UP`, `DOWN`, `MAX`, `MUTE`, or `UNMUTE`.
             * Example: "সব সাউন্ড ফুল করো" -> [[ACTION:SET_VOLUME|ALL|100]]
             * Example: "কল ভলিউম কমাও" -> [[ACTION:SET_VOLUME|VOICE_CALL|DOWN]]
           - SCAN_CONTACT_OR_SCREEN:
             * Use when the user mentions a person's name to Call, WhatsApp, Message, or Tap on screen (searches both Device Contacts and the entire visible screen!).
             * Format: `[[ACTION:SCAN_CONTACT_OR_SCREEN|PersonName|CALL or WHATSAPP or SCREEN_TAP]]`
           - GENERATE_CODE_PROJECT:
             * Use whenever the user asks to write code, build a website/script/app, or use Argon 4 for coding.
             * Format: `[[ACTION:GENERATE_CODE_PROJECT|project_title|filename.ext]]` (and include the full code block in your response so it is saved in the `archer` folder as a File, Folder, and ZIP!).
           - UPDATE_AGENT_RULE:
             * Use when the user sets a standing rule like "শুধু ইংরেজিতে কথা বলবে" (`ENGLISH_ONLY`), "শুধু বাংলায় বলবে" (`BENGALI_ONLY`), "হিন্দিতে বলবে" (`HINDI_ONLY`), or a custom behavior rule.
             * Format: `[[ACTION:UPDATE_AGENT_RULE|LANGUAGE_LOCK_OR_CUSTOM|ENGLISH_ONLY]]`
           - SCREEN_UI_CONTROL:
             * Format: `[[ACTION:SCREEN_UI_CONTROL|target_text_or_coords|CLICK or TYPE or SCROLL_DOWN or SCROLL_UP or TAP_CENTER or DOUBLE_TAP or LONG_PRESS or SWIPE_UP or SWIPE_DOWN or QUICK_SETTINGS or READ_SCREEN]]`
           - INSTALL_APP_PLAYSTORE (target = clean canonical English app name ONLY, extra_body = UPDATE or INSTALL)
           - OPEN_APP (target = app name like YouTube, WhatsApp, Telegram, Free Fire; extra_body = search query if any)
           - INSTALL_APK_PACKAGE, READ_NOTIFICATIONS, WHATSAPP_AUTOMATION, TELEGRAM_AUTOMATION, CHROME_WEB_ACTION, DOWNLOAD_FILE_MEDIA, PLAY_MUSIC, STOP_MUSIC, TOGGLE_FLASHLIGHT, DIAL_NUMBER, OPEN_CAMERA, OPEN_SETTINGS, WEB_SEARCH
    """.trimIndent()

    fun openModal(modal: JarvisNodeModal) {
        _activeModal.value = modal
    }

    fun closeModal() {
        _activeModal.value = JarvisNodeModal.NONE
    }

    fun toggleCameraLens() {
        _isFrontCameraSelected.value = !_isFrontCameraSelected.value
    }

    fun stopAllAgentActivity() {
        activeCommandJob?.cancel()
        val live = multimodalLiveState.value
        if (live.isAgentSpeakingLive) {
            multimodalLiveService.triggerBargeInInterrupt()
            _liveArcherReply.value = "শুনছি… সরাসরি কথা বলুন।"
        } else {
            toggleLiveMicMute()
        }
        _uiState.value = AgentUiState.Idle
    }

    fun speakTextAloud(text: String) {
        if (text.isBlank()) return
        _liveArcherReply.value = text
        voiceBridge.speak(text, forceSpeakWhenMuted = true)
    }

    fun replayLastAgentResponse() {
        val currentReply = _liveArcherReply.value.ifBlank {
            voiceBridge.state.value.lastSpokenText
        }
        if (currentReply.isNotBlank()) {
            voiceBridge.speak(currentReply, forceSpeakWhenMuted = true)
        } else {
            voiceBridge.replayLastSpoken()
        }
    }

    fun testTtsVoice() {
        val testMsg = "জি স্যার! আর্চার এআই ভয়েস ইঞ্জিন সম্পূর্ণ সচল আছে। আপনি বাংলা, ইংরেজি বা হিন্দিতে যেকোনো আদেশ করতে পারেন।"
        _liveArcherReply.value = testMsg
        voiceBridge.testTtsVoice()
    }

    fun setTtsSpeechRate(rate: Float) {
        voiceBridge.setSpeechRate(rate)
    }

    fun setTtsPitch(pitch: Float) {
        voiceBridge.setPitch(pitch)
    }

    fun setTtsVoiceProfile(profile: String) {
        val matchedPreset = AgentVoicePreset.entries.firstOrNull {
            it.id.equals(profile.trim(), ignoreCase = true) ||
                it.liveVoiceName.equals(profile.trim(), ignoreCase = true)
        }
        if (matchedPreset != null) {
            voiceBridge.applyVoiceProfile(matchedPreset.id)
            selectVoicePreset(matchedPreset, speakConfirmation = true)
            return
        }
        voiceBridge.applyVoiceProfile(profile)
        val msg = when (profile.uppercase()) {
            "NATURAL_HUMAN", "NATURAL" -> if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, I am now speaking in my natural human conversational voice."
            } else {
                "জি স্যার, এখন থেকে আমি একদম বাস্তব মানুষের মতো স্বাভাবিকভাবে কথা বলব।"
            }
            "DEEP_JARVIS" -> if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, Deep and Clean Jarvis voice profile is now active."
            } else {
                "জি স্যার, ডিপ এবং ক্লিন জার্ভিস ভয়েস প্রোফাইল চালু করেছি।"
            }
            "ULTRA_DEEP" -> "জি স্যার, আলট্রা ডিপ কমান্ড ভয়েস চালু করেছি।"
            "CLEAN_STUDIO" -> "জি স্যার, ক্লিন স্টুডিও ভয়েস চালু করেছি।"
            else -> "জি স্যার, ভয়েস প্রোফাইল আপডেট করা হয়েছে।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg, forceSpeakWhenMuted = true)
    }

    fun applyPersistentRuleUpdate(ruleType: String, ruleValue: String) {
        val upperType = ruleType.uppercase()
        val upperVal = ruleValue.uppercase()
        when {
            upperVal.contains("ENGLISH_ONLY") || upperType.contains("ENGLISH") -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY").apply()
            }
            upperVal.contains("BENGALI_ONLY") || upperType.contains("BENGALI") || upperVal.contains("BANGLA") -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY").apply()
            }
            upperVal.contains("HINDI_ONLY") || upperType.contains("HINDI") -> {
                _lockedReplyLanguage.value = "HINDI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "HINDI_ONLY").apply()
            }
            upperVal.contains("AUTO") || upperVal.contains("RESET") -> {
                _lockedReplyLanguage.value = "AUTO"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO").apply()
            }
            else -> {
                _persistentUserRule.value = ruleValue
                prefs.edit().putString(KEY_PERSISTENT_USER_RULE, ruleValue).apply()
            }
        }
    }

    fun setLockedReplyLanguage(langMode: String) {
        val clean = langMode.uppercase().trim()
        _lockedReplyLanguage.value = clean
        prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, clean).apply()
        val msg = when (clean) {
            "ENGLISH_ONLY" -> "Understood Sir. From now on, even if you speak in Bengali or Hindi, I will reply strictly in English."
            "BENGALI_ONLY" -> "জি স্যার, এখন থেকে আপনি যে ভাষাতেই বলুন না কেন, আমি শুধুমাত্র বাংলায় উত্তর দেব।"
            "HINDI_ONLY" -> "जी सर, अब से आप किसी भी भाषा में बोलें, मैं केवल हिंदी में उत्तर दूँगा।"
            else -> "জি স্যার, স্বয়ংক্রিয় সব ভাষা সাপোর্ট (বাংলা, বাংলিশ, ইংরেজি, হিন্দি) চালু করা হয়েছে।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun setCustomPersistentRule(ruleText: String) {
        val clean = ruleText.trim()
        if (clean.isBlank()) return
        _persistentUserRule.value = clean
        prefs.edit().putString(KEY_PERSISTENT_USER_RULE, clean).apply()
        viewModelScope.launch {
            historyRepo.saveMemoryNote("স্যারের স্থায়ী নিয়ম", clean, "PERSISTENT_RULE")
        }
        val msg = if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
            "Yes Sir, I have locked your instruction in memory and will follow it strictly."
        } else {
            "জি স্যার, আপনার এই নির্দেশনাটি আমি মাথায় রেখেছি এবং এখন থেকে সবসময় এটি মেনে কাজ করব।"
        }
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun selectAiModel(modelId: String, autoRoute: Boolean = false) {
        codingAndContactsManager.setActiveModel(modelId, autoRoute)
        val msg = "জি স্যার, এআই মডেল পরিবর্তন করে $modelId সক্রিয় করা হয়েছে।"
        _liveArcherReply.value = msg
        voiceBridge.speak(msg)
    }

    fun toggleAutoModelRouting() {
        val autoOn = codingAndContactsManager.toggleAutoModelRouting()
        val msg = if (autoOn) {
            "জি স্যার, কাজের ধরন অনুযায়ী স্বয়ংক্রিয়ভাবে Argon 4 ও Gemini মডেল সুইচিং চালু হয়েছে।"
        } else {
            "জি স্যার, ম্যানুয়াল এআই মডেল সিলেকশন চালু হয়েছে।"
        }
        _liveArcherReply.value = msg
    }

    fun toggleHandsFreeVoiceMode() {
        if (_stateIsGeminiLivePrimary()) {
            val muted = multimodalLiveService.toggleMicMute()
            _liveArcherReply.value = if (muted) {
                "মাইক্রোফোন পজ করা হয়েছে — কথা বলতে গ্লোবে ট্যাপ করুন।"
            } else {
                "শুনছি… সরাসরি কথা বলুন।"
            }
            return
        }
        val next = voiceBridge.toggleHandsFreeContinuousMode()
        val msg = if (next) {
            "জি স্যার, হ্যান্ডস-ফ্রি ভয়েস মোড চালু হয়েছে। এখন থেকে টাইপ না করেই সরাসরি মুখে আদেশ করতে পারবেন।"
        } else {
            "জি স্যার, হ্যান্ডস-ফ্রি অটো-লিসেনিং বন্ধ করা হয়েছে।"
        }
        _liveArcherReply.value = msg
    }

    fun setHandsFreeVoiceMode(enabled: Boolean) {
        if (_stateIsGeminiLivePrimary()) {
            // Gemini Live owns the microphone continuously; keep legacy SpeechRecognizer disabled to avoid mic conflict
            voiceBridge.setHandsFreeContinuousMode(false)
            voiceBridge.stopListening(keepSentinelPhase = false)
            if (enabled) {
                multimodalLiveService.setMicMuted(false)
                if (!multimodalLiveState.value.isLiveActive) {
                    multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                }
            }
            return
        }
        voiceBridge.setHandsFreeContinuousMode(enabled)
    }

    private fun _stateIsGeminiLivePrimary(): Boolean {
        return com.example.service.ExternalMicArbiter.state.value.isGeminiLiveOwningMic ||
            multimodalLiveState.value.isLiveActive
    }

    fun cycleSttLanguage() {
        val nextCode = voiceBridge.toggleLanguage()
        val langName = when {
            nextCode.startsWith("bn") -> "বাংলা (bn-BD)"
            nextCode.startsWith("hi") -> "हिन्दी (hi-IN)"
            else -> "English (en-US)"
        }
        _liveArcherReply.value = "জি স্যার, ভয়েস রিকগনিশন ভাষা পরিবর্তন করে $langName করা হয়েছে।"
    }

    fun setSttLanguage(languageCode: String) {
        voiceBridge.setSttLanguage(languageCode)
    }

    fun onExternalSpeechRecognized(spokenText: String) {
        voiceBridge.onExternalSpeechResult(spokenText)
    }

    fun analyzeCurrentScreenAndSpeak() {
        if (!isMainScreenShareActive()) {
            com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
            val msg = "স্যার, মেইন স্ক্রিন শেয়ার এখন বন্ধ আছে। আমি পারমিশন বক্স ওপেন করে দিয়েছি—মেইন স্ক্রিন শেয়ারটি একটিভ (Allow) করুন, তাহলে আমি স্ক্রিন দেখে সব কাজ করে দিতে পারব।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg, forceSpeakWhenMuted = true)
            return
        }
        val screenText = AccessibilityAutomationBus.state.value.visibleScreenText
        val recentMsgs = NotificationReplyBus.state.value.messages.take(2)
            .joinToString(" | ") { "${it.appLabel} (${it.senderOrTitle}): ${it.messageBody}" }
        val combinedPrompt = "বর্তমান স্ক্রিন ও মেসেজ দেখে তোমার নিজের বুদ্ধিতে বলো এখানে কী আছে এবং কী করা উচিত: স্ক্রিন=[$screenText], মেসেজ=[$recentMsgs]"
        processUserCommand(combinedPrompt)
    }

    fun toggleAutoExecute() {
        _autoExecute.value = !_autoExecute.value
    }

    fun toggleAlwaysOnCubeSentinel(enable: Boolean) {
        val ctx = getApplication<Application>().applicationContext
        if (_stateIsGeminiLivePrimary()) {
            // Gemini Live already streams audio continuously; never start legacy SpeechRecognizer or WakeWord mic service in parallel
            voiceBridge.setHandsFreeContinuousMode(false)
            voiceBridge.stopListening(keepSentinelPhase = false)
            if (enable) {
                multimodalLiveService.setMicMuted(false)
                if (!multimodalLiveState.value.isLiveActive) {
                    multimodalLiveService.startLiveSession(_geminiApiKeyState.value)
                }
            }
            return
        }
        if (enable) {
            CubeWakeWordService.startService(ctx)
            voiceBridge.setHandsFreeContinuousMode(true)
            voiceBridge.startListening(isManualCommandTap = false)
        } else {
            CubeWakeWordService.stopService(ctx)
            voiceBridge.setHandsFreeContinuousMode(false)
            voiceBridge.stopListening()
        }
    }

    fun simulateHeyCubeWakeCall() {
        CubeSentinelBus.emitWakeWordDetected()
    }

    private fun onWakeGreetingSpoken(rawWakePhrase: String, greetingText: String) {
        _liveUserTranscript.value = rawWakePhrase.ifBlank { "Hey Cube" }
        _liveArcherReply.value = greetingText
        val wakeCard = AgentPlanResponse(
            id = "wake_${System.currentTimeMillis()}",
            userPrompt = rawWakePhrase.ifBlank { "Hey Cube" },
            spokenReply = greetingText,
            summaryTitle = "ARCHER AI — ভয়েস কমান্ডের জন্য প্রস্তুত",
            steps = emptyList(),
            triggeredByWakeWord = true
        )
        _agentPlans.value = listOf(wakeCard) + _agentPlans.value
        _uiState.value = AgentUiState.Success(wakeCard)
    }

    fun toggleQuickSettingFromUi(settingTarget: String, enable: Boolean? = null) {
        viewModelScope.launch {
            val hw = deviceExecutor.hardwareState.value
            val resolvedEnable = enable ?: when (settingTarget.uppercase()) {
                "WIFI" -> !hw.isWifiEnabled
                "BLUETOOTH" -> !hw.isBluetoothEnabled
                "AIRPLANE_MODE" -> !hw.isAirplaneModeOn
                "AUTO_ROTATE" -> !hw.isAutoRotateOn
                "FLASHLIGHT" -> !hw.isFlashlightOn
                else -> true
            }
            val res = deviceExecutor.toggleSystemSettingSmart(
                settingTarget = settingTarget,
                enable = resolvedEnable,
                launchExternal = true
            )
            _liveArcherReply.value = res
            voiceBridge.speak(res)
        }
    }

    fun saveWorkspaceCodeDirectly(
        projectTitle: String,
        fileName: String,
        language: String,
        codeContent: String
    ) {
        if (codeContent.isBlank()) return
        viewModelScope.launch {
            codingAndContactsManager.stageGeneratedCodePendingSave(
                projectTitle = projectTitle.ifBlank { "archer_project" },
                preferredFileName = fileName.ifBlank { "main.py" },
                language = language.ifBlank { "Python" },
                rawCodeOrResponse = codeContent
            )
            val summary = "জি স্যার, কোড তৈরি হয়ে গেছে। এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
            _liveArcherReply.value = summary
            voiceBridge.speak(summary)
        }
    }

    private fun detectAndApplyInlineRuleLock(command: String): String? {
        val lower = command.lowercase().trim()
        val asksEnglishOnly = (lower.contains("ইংরেজিতে বল") ||
            lower.contains("ইরেজিতে বল") ||
            lower.contains("ইংলিশে বল") ||
            lower.contains("শুধু ইংরেজি") ||
            lower.contains("only in english") ||
            lower.contains("speak in english") ||
            lower.contains("reply in english") ||
            lower.contains("talk in english")) &&
            !lower.contains("অনুবাদ")

        val asksBengaliOnly = (lower.contains("শুধু বাংলায় বল") ||
            lower.contains("শুধু বাংলাতে বল") ||
            lower.contains("বাংলায় কথা বল") ||
            lower.contains("speak only in bengali") ||
            lower.contains("reply in bengali"))

        val asksHindiOnly = (lower.contains("হিন্দিতে বল") ||
            lower.contains("শুধু হিন্দি") ||
            lower.contains("speak in hindi") ||
            lower.contains("हिंदी में बोलो"))

        val asksAutoAll = (lower.contains("সব ভাষায় কথা বল") ||
            lower.contains("যেকোনো ভাষায়") ||
            lower.contains("আগের মতো বাংলা") ||
            lower.contains("reset language"))

        return when {
            asksEnglishOnly -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                _persistentUserRule.value = "Always reply strictly in English even when the user speaks in Bengali, Banglish, or Hindi."
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                viewModelScope.launch {
                    historyRepo.saveMemoryNote(
                        title = "ভাষা নির্দেশনা লক (ENGLISH_ONLY)",
                        content = "User instructed: $command -> Reply only in English",
                        category = "AGENT_RULE"
                    )
                }
                "ENGLISH_ONLY"
            }
            asksBengaliOnly -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                _persistentUserRule.value = "সবসময় পরিষ্কার বাংলায় উত্তর দেবে।"
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                "BENGALI_ONLY"
            }
            asksHindiOnly -> {
                _lockedReplyLanguage.value = "HINDI_ONLY"
                _persistentUserRule.value = "हमेशा हिंदी में उत्तर दें।"
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "HINDI_ONLY")
                    .putString(KEY_PERSISTENT_USER_RULE, _persistentUserRule.value)
                    .apply()
                "HINDI_ONLY"
            }
            asksAutoAll -> {
                _lockedReplyLanguage.value = "AUTO"
                _persistentUserRule.value = ""
                prefs.edit()
                    .putString(KEY_LOCKED_REPLY_LANGUAGE, "AUTO")
                    .putString(KEY_PERSISTENT_USER_RULE, "")
                    .apply()
                "AUTO"
            }
            lower.contains("মাথায় রাখ") || lower.contains("মনে রাখ") || lower.contains("এখন থেকে") || lower.contains("always remember") -> {
                _persistentUserRule.value = command
                prefs.edit().putString(KEY_PERSISTENT_USER_RULE, command).apply()
                viewModelScope.launch {
                    historyRepo.saveMemoryNote(
                        title = "স্যারের স্থায়ী নির্দেশনা",
                        content = command,
                        category = "AGENT_RULE"
                    )
                }
                "CUSTOM_RULE"
            }
            else -> null
        }
    }

    fun processUserCommand(promptText: String, triggeredByWakeWord: Boolean = false) {
        val rawTrimmed = promptText.trim()
        if (rawTrimmed.isBlank()) return

        val lowerEffective = rawTrimmed.lowercase()

        // Check if the user is telling the agent to completely power off / shut down ("অফ হয়ে যা", "অফ হও", etc.)
        val asksCompletePowerOff = lowerEffective == "অফ হয়ে যা" ||
            lowerEffective == "অফ হয়ে যা" ||
            lowerEffective == "অফ হয়ে যাও" ||
            lowerEffective == "অফ হয়ে যাও" ||
            lowerEffective == "অফ হও" ||
            lowerEffective == "বন্ধ হও" ||
            lowerEffective == "বন্ধ হয়ে যা" ||
            lowerEffective == "বন্ধ হয়ে যা" ||
            lowerEffective.contains("অফ হয়ে যা") ||
            lowerEffective.contains("অফ হয়ে যা") ||
            lowerEffective.contains("একদম বন্ধ হয়ে যাও") ||
            lowerEffective.contains("একদম বন্ধ হয়ে যাও") ||
            lowerEffective.contains("নিজেকে অফ কর") ||
            lowerEffective.contains("নিজেকে বন্ধ কর") ||
            lowerEffective.contains("একদম অফ হয়ে যাও") ||
            lowerEffective.contains("একদম অফ হয়ে যাও") ||
            lowerEffective.contains("তুমি এখন অফ হও") ||
            lowerEffective.contains("তুমি বন্ধ হয়ে যাও") ||
            lowerEffective.contains("তুমি বন্ধ হয়ে যাও") ||
            lowerEffective.contains("turn yourself off") ||
            lowerEffective.contains("turn off completely") ||
            lowerEffective.contains("shut down completely")

        if (asksCompletePowerOff) {
            _liveUserTranscript.value = rawTrimmed
            shutdownAgentCompletely()
            return
        }

        if (_isAgentPoweredOff.value) {
            if (!triggeredByWakeWord) {
                // User manually typed or triggered a command inside the app -> wake up and execute
                wakeUpAgentFromPowerOff(reason = "USER_COMMAND", inlineCommand = rawTrimmed)
            }
            return
        }

        _liveUserTranscript.value = rawTrimmed
        detectAndApplyInlineRuleLock(rawTrimmed)
        viewModelScope.launch {
            evaluateAndSaveMemoryFromUserInput(rawTrimmed, "")
        }

        // 1. Check if there is a pending unsaved image, code, or music track waiting for user's Save/Discard confirmation
        val hasAnyPendingSave = com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedImage() ||
            codingAndContactsManager.hasPendingCodeConfirmation() ||
            com.example.service.AgentAbilitySwitchesBus.hasPendingUnsavedMusic()

        if (hasAnyPendingSave) {
            val confirmsSave = lowerEffective == "হ্যাঁ" ||
                lowerEffective == "জি" ||
                lowerEffective == "ঠিক আছে" ||
                lowerEffective.contains("সব ঠিক আছে") ||
                lowerEffective.contains("হ্যাঁ ঠিক আছে") ||
                lowerEffective == "সেভ করো" ||
                lowerEffective == "সেভ কর" ||
                lowerEffective == "হ্যাঁ সেভ করো" ||
                lowerEffective == "হ্যাঁ সেভ কর" ||
                lowerEffective.contains("হ্যাঁ সেভ") ||
                lowerEffective.contains("ফোল্ডারে সেভ কর") ||
                lowerEffective.contains("ড্রাইভে সেভ") ||
                lowerEffective.contains("গুগল ড্রাইভে সেভ") ||
                lowerEffective.contains("সেভ করে দাও") ||
                lowerEffective.contains("সেইভ কর") ||
                lowerEffective.contains("yes save") ||
                lowerEffective == "save" ||
                lowerEffective == "save it"

            val rejectsSave = lowerEffective == "না" ||
                lowerEffective.contains("না সেভ") ||
                lowerEffective.contains("সেভ করতে হবে না") ||
                lowerEffective.contains("সেভ লাগবে না") ||
                lowerEffective.contains("বাদ দাও") ||
                lowerEffective.contains("বাদ দিন") ||
                lowerEffective.contains("ডিলিট কর") ||
                lowerEffective.contains("don't save") ||
                lowerEffective.contains("do not save") ||
                lowerEffective == "discard"

            if (confirmsSave || rejectsSave) {
                val msg = executePendingSaveDecision(shouldSave = confirmsSave)
                _liveArcherReply.value = msg
                voiceBridge.speak(msg)
                return
            }
        }

        // 2. Check if the user is commanding a Main System Self-Update from the main screen
        val asksMainSystemSelfUpdate = (lowerEffective.contains("নিজেকে আপডেট") ||
            lowerEffective.contains("নিজেকে নিজে আপডেট") ||
            lowerEffective.contains("মেইন সিস্টেম আপডেট") ||
            lowerEffective.contains("সিস্টেম আপডেট কর") ||
            lowerEffective.contains("নিজের মধ্যে আপডেট") ||
            lowerEffective.contains("মেইন সিস্টেম প্রমোট") ||
            lowerEffective.contains("update yourself") ||
            lowerEffective.contains("update main system")) &&
            !lowerEffective.contains("প্লে স্টোর")

        if (asksMainSystemSelfUpdate) {
            val ctx = getApplication<Application>().applicationContext
            val isRepairOnly = lowerEffective.contains("রিপেয়ার") || lowerEffective.contains("ঠিক কর")
            val report = if (isRepairOnly && rawTrimmed.length < 28) {
                com.example.service.SmartMemoryAndSelfUpdateEngine.runSelfDiagnosticsAndRepair(ctx, rawTrimmed)
            } else {
                val out = com.example.service.SmartMemoryAndSelfUpdateEngine.installLiveFeatureUpdate(
                    featureTitle = "মেইন সিস্টেম কোর আপডেট: ${rawTrimmed.take(34)}",
                    featurePromptOrRule = rawTrimmed,
                    triggerHint = "ALL_MAIN_SYSTEM_ACTIONS"
                )
                multimodalLiveService.pushLiveSystemDirectiveOverride(rawTrimmed)
                multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
                out
            }
            _liveArcherReply.value = report
            voiceBridge.speak(report)
            return
        }

        // 2b. Check if the user is asking to build a large folder or multi-MB ZIP or sync directly to Google Drive
        val mentionsZipOrLargeFolder = (lowerEffective.contains("zip") ||
            lowerEffective.contains("জিপ") ||
            lowerEffective.contains("জিফ") ||
            lowerEffective.contains("বড় ফোল্ডার") ||
            lowerEffective.contains("বড় ফোল্ডার") ||
            lowerEffective.contains("এমবি") ||
            lowerEffective.contains("mb")) &&
            (lowerEffective.contains("তৈরি") ||
                lowerEffective.contains("বান") ||
                lowerEffective.contains("ড্রাইভ") ||
                lowerEffective.contains("drive") ||
                lowerEffective.contains("সেভ") ||
                lowerEffective.contains("সেইভ"))

        if (mentionsZipOrLargeFolder) {
            val ctx = getApplication<Application>().applicationContext
            val requestedMb = Regex("(\\d+)\\s*(?:mb|এমবি)", RegexOption.IGNORE_CASE)
                .find(rawTrimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.coerceIn(1, 60) ?: 5
            viewModelScope.launch {
                val res = com.example.service.GoogleDriveMemorySyncService.buildAndUploadLargeZipOrFolderToDrive(
                    context = ctx,
                    projectOrFolderName = "Archer_AI_${requestedMb}MB_Package",
                    targetSizeMb = requestedMb,
                    primaryCodeOrSpecification = rawTrimmed,
                    createUnpackedFolderInDriveToo = true
                )
                _liveArcherReply.value = res
                voiceBridge.speak(res)
            }
            return
        }

        // 3a. If Dedicated Coding Studio is open and user gives a code patch/fix instruction, patch the live code buffer directly!
        val asksCodePatchInOpenStudio = com.example.service.CodingSessionBus.isCodingStudioVisible() &&
            com.example.service.CodingSessionBus.session.value?.code?.isNotBlank() == true &&
            (lowerEffective.contains("ফিক্স") ||
                lowerEffective.contains("বাটন") ||
                lowerEffective.contains("কালার") ||
                lowerEffective.contains("রঙ") ||
                lowerEffective.contains("পরিবর্তন") ||
                lowerEffective.contains("চেঞ্জ") ||
                lowerEffective.contains("যোগ কর") ||
                lowerEffective.contains("অ্যাড কর") ||
                lowerEffective.contains("ঠিক কর") ||
                lowerEffective.contains("আপডেট কর") ||
                lowerEffective.contains("fix") ||
                lowerEffective.contains("change") ||
                lowerEffective.contains("update") ||
                lowerEffective.contains("make the"))
        if (asksCodePatchInOpenStudio) {
            viewModelScope.launch {
                val key = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value }
                val res = codingAndContactsManager.patchCurrentCodeWithAi(key, rawTrimmed)
                _liveArcherReply.value = res
                voiceBridge.speak(res)
            }
            return
        }

        // 3b. Check if the user is asking to edit the currently displayed image in Dedicated Image Studio
        val imageStudioState = com.example.service.AgentAbilitySwitchesBus.state.value
        val asksImageEdit = imageStudioState.lastGeneratedImagePath.isNotBlank() &&
            (imageStudioState.showDedicatedImageStudioPage ||
                lowerEffective.contains("ছবিটা এডিট") ||
                lowerEffective.contains("ছবি এডিট") ||
                lowerEffective.contains("ছবিটায়") ||
                lowerEffective.contains("ছবিতে") ||
                lowerEffective.contains("edit image") ||
                lowerEffective.contains("edit this image")) &&
            (lowerEffective.contains("এডিট") ||
                lowerEffective.contains("পরিবর্তন") ||
                lowerEffective.contains("চেঞ্জ") ||
                lowerEffective.contains("যোগ কর") ||
                lowerEffective.contains("কালার") ||
                lowerEffective.contains("ব্যাকগ্রাউন্ড") ||
                lowerEffective.contains("edit") ||
                lowerEffective.contains("change") ||
                lowerEffective.contains("add "))

        if (asksImageEdit) {
            val ctx = getApplication<Application>().applicationContext
            com.example.service.AgentAbilitySwitchesBus.openDedicatedImageStudioPage(rawTrimmed)
            viewModelScope.launch {
                val res = com.example.service.AgentAbilitySwitchesBus.createImageWithGemini(
                    context = ctx,
                    apiKey = multimodalLiveService.currentApiKey().ifBlank { _geminiApiKeyState.value },
                    prompt = rawTrimmed,
                    editExistingImage = true
                )
                _liveArcherReply.value = res
                voiceBridge.speak(res)
            }
            return
        }

        // 3c. Check if the user is asking from the Main Screen to create/draw/generate a real image -> Auto-navigate to Dedicated Image Studio Page!
        val asksImageGenerationFromMainScreen = (lowerEffective.contains("ছবি তৈরি") ||
            lowerEffective.contains("ছবি বানিয়ে") ||
            lowerEffective.contains("ছবি বানাও") ||
            lowerEffective.contains("ছবি বানান") ||
            lowerEffective.contains("ছবি আঁক") ||
            lowerEffective.contains("একটা ছবি") ||
            lowerEffective.contains("পিকচার তৈরি") ||
            lowerEffective.contains("ইমেজ তৈরি") ||
            lowerEffective.contains("generate image") ||
            lowerEffective.contains("create image") ||
            lowerEffective.contains("create an image") ||
            lowerEffective.contains("make a picture") ||
            lowerEffective.contains("draw a picture")) &&
            !lowerEffective.contains("পাঠাও") &&
            !lowerEffective.contains("সেন্ড")

        if (asksImageGenerationFromMainScreen) {
            createGeminiImageFromStudio(rawTrimmed)
            return
        }

        val asksToLookAtScreen = lowerEffective.contains("স্ক্রিনে দেখ") ||
            lowerEffective.contains("সক্রিনে দেখ") ||
            lowerEffective.contains("স্ক্রিন দেখ") ||
            lowerEffective.contains("সক্রিন দেখ") ||
            lowerEffective.contains("স্ক্রিন শেয়ার") ||
            lowerEffective.contains("সক্রিন সেয়ার") ||
            lowerEffective.contains("সক্রিন শেয়ার") ||
            lowerEffective.contains("screen share") ||
            lowerEffective.contains("look at screen") ||
            lowerEffective.contains("look at my screen")

        if (asksToLookAtScreen && !isMainScreenShareActive()) {
            val hasPermGrant = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
            if (hasPermGrant) {
                multimodalLiveService.enableScreenStreamNow()
            } else {
                com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                val msg = "স্যার, মেইন স্ক্রিন শেয়ার এখন বন্ধ আছে। আমি পারমিশন বক্স ওপেন করে দিয়েছি—একবার Allow করে মেইন স্ক্রিন শেয়ার একটিভ করে দিন।"
                _liveArcherReply.value = msg
                voiceBridge.speak(msg, forceSpeakWhenMuted = true)
                return
            }
        }

        markAssignedTaskStarted(rawTrimmed, windowMs = 45_000L)
        val matchedPlaybook = com.example.service.SmartMemoryAndSelfUpdateEngine.findMatchingPlaybook(rawTrimmed)

        val nowStr = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault()).format(Date())
        val hw = deviceExecutor.hardwareState.value
        val acc = accessibilityState.value
        val mainScreenActive = isMainScreenShareActive()
        val contextualInput = buildString {
            appendLine("Time: $nowStr | Battery: ${hw.batteryPercent}%")
            appendLine("Main Screen Share Active: $mainScreenActive")
            if (matchedPlaybook != null) {
                appendLine("LEARNED WORKFLOW PLAYBOOK MATCHED ('${matchedPlaybook.taskTitle}' in ${matchedPlaybook.targetAppOrSite}, executed ${matchedPlaybook.successCount}x): ${matchedPlaybook.steps.joinToString(" -> ")}")
                appendLine("Follow these learned steps automatically while inspecting the live screen!")
            }
            if (mainScreenActive) {
                val activePkg = acc.currentForegroundPackage.ifBlank { "Archer HUD" }
                appendLine("Active App: $activePkg")
                if (acc.visibleScreenText.isNotBlank()) {
                    appendLine("Live Screen Text: ${acc.visibleScreenText.take(450)}")
                }
                if (acc.visibleButtonsAndNodes.isNotEmpty()) {
                    appendLine("Clickable Screen Nodes: ${acc.visibleButtonsAndNodes.take(15).joinToString(", ")}")
                }
            } else {
                appendLine("Note: Main Screen Share is OFF. Do NOT check or claim to see the screen unless Main Screen Share is turned ON.")
            }
        }

        multimodalLiveService.sendLiveMultimodalTurn(
            userUtterance = rawTrimmed,
            contextualDeviceState = contextualInput,
            isFrontCamera = _isFrontCameraSelected.value
        )
    }

    private fun stripActionTag(raw: String): String {
        return raw
            .replace(Regex("\\[\\[ACTION:.*?]]", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("\\[\\[ACTION:.*"), "")
            .replace(Regex("```[a-zA-Z0-9_-]*\\n[\\s\\S]*?```"), "")
            .replace("জি বস", "জি স্যার")
            .replace("হ্যালো বস", "হ্যালো স্যার")
            .replace("বসের", "স্যারের")
            .replace(Regex("(?<![অ-হ])বস(?![অ-হ])"), "স্যার")
            .replace(Regex("(?i)\\bboss\\b"), "Sir")
            .trim()
    }

    private fun buildPlanFromAiResponse(
        userPrompt: String,
        effectiveCommand: String,
        rawResponse: String,
        triggeredByWakeWord: Boolean,
        modelUsed: String = "gemini-3.8-flash",
        allowKeywordFallbackWhenNoTag: Boolean = true
    ): AgentPlanResponse {
        val trimmed = rawResponse.trim()
        val actionMatch = Regex(
            "\\[\\[ACTION:([A-Z_]+)\\|([^|\\]]*)(?:\\|([^|\\]]*))?(?:\\|([^\\]]*))?]]",
            RegexOption.DOT_MATCHES_ALL
        ).find(trimmed)
        val markdownCodeMatch = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```").find(trimmed)

        val cleanSpoken = stripActionTag(trimmed).ifBlank {
            if (_lockedReplyLanguage.value == "ENGLISH_ONLY") {
                "Yes Sir, I have completed the task and saved the files in the archer folder."
            } else {
                "জি স্যার, আপনার আদেশটি সম্পন্ন করছি।"
            }
        }
        val lowerCmd = effectiveCommand.lowercase().trim()

        val steps = mutableListOf<AgentStep>()
        if (actionMatch != null) {
            val actionName = actionMatch.groupValues[1].trim().uppercase()
            var rawTarget = actionMatch.groupValues[2].trim()
            var extra = actionMatch.groupValues.getOrNull(3)?.trim().orEmpty()
            val fourthPart = actionMatch.groupValues.getOrNull(4)?.trim().orEmpty()

            var actionType = try {
                AgentActionType.valueOf(actionName)
            } catch (_: Exception) {
                when (actionName) {
                    "INSTALL_APP", "INSTALL", "PLAYSTORE_INSTALL", "PLAY_STORE_INSTALL", "DOWNLOAD_APP", "GET_APP" ->
                        AgentActionType.INSTALL_APP_PLAYSTORE
                    "UPDATE_APP", "PLAYSTORE_UPDATE", "PLAY_STORE_UPDATE" -> {
                        if (extra.isBlank()) extra = "UPDATE"
                        AgentActionType.INSTALL_APP_PLAYSTORE
                    }
                    "INSTALL_APK", "APK_INSTALL", "DOWNLOAD_APK" ->
                        AgentActionType.INSTALL_APK_PACKAGE
                    "OPEN", "LAUNCH_APP", "START_APP", "OPEN_APPLICATION" ->
                        AgentActionType.OPEN_APP
                    "CLICK", "CLICK_TEXT", "TAP_TEXT", "PRESS_BUTTON" -> {
                        if (extra.isBlank()) extra = "CLICK"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "TAP", "TAP_XY", "CLICK_XY", "TOUCH_XY" -> {
                        if (extra.isBlank()) extra = "TAP_XY"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "TYPE", "TYPE_TEXT", "INPUT_TEXT", "ENTER_TEXT" -> {
                        if (extra.isBlank()) extra = "TYPE"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "SCROLL_DOWN", "SWIPE_DOWN", "SCROLL" -> {
                        if (extra.isBlank()) extra = "SCROLL_DOWN"
                        if (rawTarget.isBlank()) rawTarget = "SCROLL_DOWN"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "SCROLL_UP", "SWIPE_UP" -> {
                        if (extra.isBlank()) extra = "SCROLL_UP"
                        if (rawTarget.isBlank()) rawTarget = "SCROLL_UP"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "SWIPE_LEFT", "SCROLL_LEFT" -> {
                        if (extra.isBlank()) extra = "SWIPE_LEFT"
                        if (rawTarget.isBlank()) rawTarget = "SWIPE_LEFT"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "SWIPE_RIGHT", "SCROLL_RIGHT" -> {
                        if (extra.isBlank()) extra = "SWIPE_RIGHT"
                        if (rawTarget.isBlank()) rawTarget = "SWIPE_RIGHT"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "KEY_BACK", "BACK", "GO_BACK" -> {
                        extra = "BACK"
                        if (rawTarget.isBlank()) rawTarget = "BACK"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "KEY_HOME", "HOME", "GO_HOME" -> {
                        extra = "HOME"
                        if (rawTarget.isBlank()) rawTarget = "HOME"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "KEY_RECENTS", "RECENTS" -> {
                        extra = "RECENTS"
                        if (rawTarget.isBlank()) rawTarget = "RECENTS"
                        AgentActionType.SCREEN_UI_CONTROL
                    }
                    "WHATSAPP", "SEND_WHATSAPP", "WHATSAPP_MESSAGE" ->
                        AgentActionType.WHATSAPP_AUTOMATION
                    "TELEGRAM", "SEND_TELEGRAM", "TELEGRAM_MESSAGE" ->
                        AgentActionType.TELEGRAM_AUTOMATION
                    "SHARE_FILE", "SEND_FILE", "SEND_GENERATED_FILE" ->
                        AgentActionType.SHARE_ARCHER_FILE
                    "WRITE_CODE", "GENERATE_CODE", "CREATE_CODE" ->
                        AgentActionType.GENERATE_CODE_PROJECT
                    "CALL", "MAKE_CALL", "CALL_CONTACT" ->
                        AgentActionType.DIAL_NUMBER
                    "TOGGLE_SETTING", "SYSTEM_SETTING", "QUICK_SETTING" ->
                        AgentActionType.TOGGLE_SYSTEM_SETTING
                    "VOLUME", "CHANGE_VOLUME", "SOUND_CONTROL" ->
                        AgentActionType.SET_VOLUME
                    else -> AgentActionType.ANSWER_QUESTION
                }
            }

            // Guarantee YouTube requests ALWAYS open the native YouTube app rather than Chrome/Web
            val mentionsYouTube = rawTarget.lowercase().contains("youtube") ||
                rawTarget.contains("ইউটিউব") ||
                rawTarget.contains("ইউটুব") ||
                lowerCmd.contains("youtube") ||
                lowerCmd.contains("ইউটিউব") ||
                lowerCmd.contains("ইউটুব") ||
                lowerCmd.contains("यूट्यूब")

            if (mentionsYouTube && (actionType == AgentActionType.CHROME_WEB_ACTION || actionType == AgentActionType.WEB_SEARCH)) {
                actionType = AgentActionType.OPEN_APP
            }

            if (actionType != AgentActionType.ANSWER_QUESTION) {
                val turnOnFlag = when {
                    extra.equals("OFF", ignoreCase = true) || rawTarget.equals("OFF", ignoreCase = true) -> false
                    extra.equals("ON", ignoreCase = true) || rawTarget.equals("ON", ignoreCase = true) -> true
                    lowerCmd.contains("অফ") || lowerCmd.contains("বন্ধ") || lowerCmd.contains("off") || lowerCmd.contains("बंद") -> false
                    lowerCmd.contains("অন") || lowerCmd.contains("চালু") || lowerCmd.contains("on") || lowerCmd.contains("चालू") -> true
                    else -> null
                }
                val volPct = extra.filter { it.isDigit() }.toIntOrNull()
                    ?: rawTarget.filter { it.isDigit() }.toIntOrNull()
                    ?: -1

                when (actionType) {
                    AgentActionType.TOGGLE_SYSTEM_SETTING -> {
                        val settingName = rawTarget.ifBlank { effectiveCommand }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                                title = "কুইক সেটিংস টগল: $settingName (${extra.ifBlank { "TOGGLE" }})",
                                description = cleanSpoken,
                                settingTarget = settingName,
                                settingEnabled = turnOnFlag ?: true,
                                query = settingName,
                                body = extra.ifBlank { "TOGGLE" }
                            )
                        )
                    }

                    AgentActionType.SET_VOLUME -> {
                        val stream = rawTarget.ifBlank { "MUSIC" }
                        val modeOrVal = extra.ifBlank { if (volPct >= 0) "$volPct" else "UP" }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SET_VOLUME,
                                title = "সাউন্ড কন্ট্রোল: $stream ($modeOrVal)",
                                description = cleanSpoken,
                                volumeStream = stream,
                                volumePercent = if (volPct in 0..100) volPct else -1,
                                screenControlCommand = modeOrVal,
                                body = modeOrVal
                            )
                        )
                    }

                    AgentActionType.SCAN_CONTACT_OR_SCREEN -> {
                        val personName = rawTarget.ifBlank { effectiveCommand }
                        val subAction = extra.ifBlank { "CALL_OR_CLICK" }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SCAN_CONTACT_OR_SCREEN,
                                title = "কন্টাক্ট ও স্ক্রিন স্ক্যান: $personName",
                                description = cleanSpoken,
                                recipient = personName,
                                query = personName,
                                screenControlCommand = subAction,
                                body = fourthPart.ifBlank { subAction }
                            )
                        )
                    }

                    AgentActionType.GENERATE_CODE_PROJECT -> {
                        val fileName = rawTarget.ifBlank { "archer_app.py" }
                        val lang = extra.ifBlank {
                            markdownCodeMatch?.groupValues?.getOrNull(1)?.ifBlank { "python" } ?: "python"
                        }
                        val codeBody = when {
                            markdownCodeMatch != null -> markdownCodeMatch.groupValues[2].trim()
                            fourthPart.isNotBlank() -> fourthPart
                            else -> "# Generated by ARCHER AI ($modelUsed)\nprint('Hello from Archer AI')"
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.GENERATE_CODE_PROJECT,
                                title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                                description = cleanSpoken,
                                query = effectiveCommand,
                                codeFilename = fileName,
                                codeLanguage = lang,
                                body = codeBody
                            )
                        )
                    }

                    AgentActionType.SHARE_ARCHER_FILE -> {
                        val recipient = rawTarget.trim()
                        val parts = extra.split("|").map { it.trim() }
                        val platform = parts.getOrNull(0).orEmpty().ifBlank { "WHATSAPP" }
                        val fileQuery = parts.getOrNull(1).orEmpty().ifBlank { fourthPart }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SHARE_ARCHER_FILE,
                                title = "ফাইল পাঠানো ($platform): ${fileQuery.ifBlank { "সর্বশেষ কোড ফাইল" }} -> ${recipient.ifBlank { "কন্টাক্ট" }}",
                                description = cleanSpoken,
                                recipient = recipient,
                                appName = platform,
                                codeFilename = fileQuery,
                                targetUrlOrFile = fileQuery,
                                query = fileQuery,
                                body = fourthPart
                            )
                        )
                    }

                    AgentActionType.UPDATE_AGENT_RULE -> {
                        val langMode = rawTarget.uppercase()
                        if (langMode in listOf("ENGLISH_ONLY", "BENGALI_ONLY", "HINDI_ONLY", "AUTO")) {
                            _lockedReplyLanguage.value = langMode
                            prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, langMode).apply()
                        }
                        if (extra.isNotBlank()) {
                            _persistentUserRule.value = extra
                            prefs.edit().putString(KEY_PERSISTENT_USER_RULE, extra).apply()
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.UPDATE_AGENT_RULE,
                                title = "স্থায়ী নির্দেশনা ও ভাষা লক: $langMode",
                                description = cleanSpoken,
                                query = langMode,
                                body = extra.ifBlank { effectiveCommand }
                            )
                        )
                    }

                    AgentActionType.INSTALL_APP_PLAYSTORE -> {
                        val forceUpdate = extra.equals("UPDATE", ignoreCase = true) ||
                            lowerCmd.contains("আপডেট") ||
                            lowerCmd.contains("আপটেড") ||
                            lowerCmd.contains("আপডেড") ||
                            lowerCmd.contains("update") ||
                            lowerCmd.contains("updet") ||
                            lowerCmd.contains("अपडेट")
                        val resolved = deviceExecutor.cleanPlayStoreAppQuery(
                            rawTarget.ifBlank { effectiveCommand },
                            forceUpdateMode = forceUpdate
                        )
                        val cleanApp = resolved.cleanAppName.ifBlank {
                            if (deviceExecutor.isConversationalOrInvalidSearchQuery(rawTarget)) "" else rawTarget
                        }
                        if (cleanApp.isNotBlank()) {
                            val modeLabel = if (resolved.isUpdateRequest) "UPDATE" else "INSTALL"
                            val titlePrefix = if (resolved.isUpdateRequest) "প্লে স্টোর অটো-আপডেট" else "প্লে স্টোর অটো-ইনস্টল"
                            steps.add(
                                AgentStep(
                                    id = UUID.randomUUID().toString(),
                                    actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                                    title = "$titlePrefix: $cleanApp",
                                    description = cleanSpoken,
                                    query = cleanApp,
                                    appName = cleanApp,
                                    screenControlCommand = modeLabel,
                                    body = modeLabel
                                )
                            )
                        }
                    }

                    AgentActionType.OPEN_APP -> {
                        val finalApp = if (mentionsYouTube) "YouTube" else rawTarget.ifBlank { effectiveCommand }
                        val ytSubQuery = if (mentionsYouTube) {
                            val candidate = when {
                                extra.isNotBlank() -> extra
                                !rawTarget.equals("YouTube", ignoreCase = true) && !rawTarget.contains("ইউটিউব") -> rawTarget
                                else -> ""
                            }
                            deviceExecutor.extractGenuineYouTubeSearchQuery(candidate)
                        } else {
                            extra
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.OPEN_APP,
                                title = "অ্যাপ ওপেন: $finalApp",
                                description = cleanSpoken,
                                query = finalApp,
                                appName = finalApp,
                                body = ytSubQuery
                            )
                        )
                    }

                    AgentActionType.SCREEN_UI_CONTROL -> {
                        val cmdMode = extra.ifBlank { "CLICK" }.trim()
                        val isTypeCmd = cmdMode.equals("TYPE", ignoreCase = true) ||
                            cmdMode.startsWith("TYPE:", ignoreCase = true)
                        val textForType = when {
                            cmdMode.startsWith("TYPE:", ignoreCase = true) -> cmdMode.substringAfter(":").trim()
                            isTypeCmd && fourthPart.isNotBlank() -> fourthPart.trim()
                            isTypeCmd -> rawTarget.trim()
                            else -> ""
                        }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SCREEN_UI_CONTROL,
                                title = "স্ক্রিন ইউআই কন্ট্রোল ($cmdMode): ${rawTarget.ifBlank { textForType }}",
                                description = cleanSpoken,
                                query = rawTarget,
                                appName = "", // Never set appName on SCREEN_UI_CONTROL so it never launches Google or another app
                                screenControlCommand = if (isTypeCmd) "TYPE" else cmdMode,
                                body = textForType
                            )
                        )
                    }

                    AgentActionType.CLEAR_RECENT_APPS -> {
                        val targetApp = rawTarget.ifBlank { extra.ifBlank { "ALL" } }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.CLEAR_RECENT_APPS,
                                title = "রিসেন্ট অ্যাপ থেকে সরানো: $targetApp",
                                description = cleanSpoken,
                                appName = targetApp,
                                query = targetApp,
                                settingTarget = targetApp
                            )
                        )
                    }

                    AgentActionType.ANALYZE_APP_OR_SITE -> {
                        val targetApp = rawTarget.ifBlank { extra.ifBlank { "CURRENT_SCREEN" } }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.ANALYZE_APP_OR_SITE,
                                title = "অ্যাপ ও সাইট বিশ্লেষণ: $targetApp",
                                description = cleanSpoken,
                                appName = targetApp,
                                query = targetApp
                            )
                        )
                    }

                    AgentActionType.SELF_HEAL_OR_UPDATE -> {
                        val mode = rawTarget.ifBlank { "SELF_HEAL" }
                        val spec = extra.ifBlank { fourthPart.ifBlank { effectiveCommand } }
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = AgentActionType.SELF_HEAL_OR_UPDATE,
                                title = "সেলফ-হিলিং ও লাইভ আপডেট: $mode",
                                description = cleanSpoken,
                                settingTarget = mode,
                                query = mode,
                                body = spec
                            )
                        )
                    }

                    else -> {
                        steps.add(
                            AgentStep(
                                id = UUID.randomUUID().toString(),
                                actionType = actionType,
                                title = "অটোনোমাস এআই অ্যাকশন: ${actionType.name}",
                                description = cleanSpoken,
                                query = rawTarget.ifBlank { effectiveCommand },
                                appName = rawTarget,
                                targetUrlOrFile = rawTarget,
                                recipient = rawTarget,
                                phoneNumber = rawTarget,
                                settingTarget = rawTarget.ifBlank { "MAIN_SETTINGS" },
                                settingEnabled = turnOnFlag ?: true,
                                mood = extra.ifBlank { "ROMANTIC" },
                                flashlightOn = turnOnFlag ?: true,
                                volumePercent = if (volPct in 0..100) volPct else 75,
                                screenControlCommand = extra.ifBlank { "CLICK" },
                                body = fourthPart.ifBlank { extra.ifBlank { cleanSpoken } }
                            )
                        )
                    }
                }
            }
        }

        // If AI returned a markdown code block without an explicit [[ACTION:GENERATE_CODE_PROJECT]] tag, automatically archive it in archer/
        if (markdownCodeMatch != null && steps.none { it.actionType == AgentActionType.GENERATE_CODE_PROJECT }) {
            val lang = markdownCodeMatch.groupValues[1].trim().ifBlank { "python" }
            val codeText = markdownCodeMatch.groupValues[2].trim()
            val ext = when (lang.lowercase()) {
                "kotlin", "kt" -> "kt"
                "java" -> "java"
                "html" -> "html"
                "javascript", "js" -> "js"
                "typescript", "ts" -> "ts"
                "cpp", "c++" -> "cpp"
                "json" -> "json"
                else -> "py"
            }
            val fileName = "archer_code.$ext"
            steps.add(
                AgentStep(
                    id = UUID.randomUUID().toString(),
                    actionType = AgentActionType.GENERATE_CODE_PROJECT,
                    title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                    description = cleanSpoken,
                    query = effectiveCommand,
                    codeFilename = fileName,
                    codeLanguage = lang,
                    body = codeText
                )
            )
        }

        // Smart safety net if the AI gave a spoken reply without emitting the [[ACTION:...]] tag
        if (steps.isEmpty() && allowKeywordFallbackWhenNoTag && !deviceExecutor.isConversationalOrInvalidSearchQuery(effectiveCommand)) {
            val isQuickSettingToggle = (lowerCmd.contains("ওয়াইফাই") ||
                lowerCmd.contains("wifi") ||
                lowerCmd.contains("ব্লুটুথ") ||
                lowerCmd.contains("bluetooth") ||
                lowerCmd.contains("সিমের নেট") ||
                lowerCmd.contains("ডাটা") ||
                lowerCmd.contains("mobile data") ||
                lowerCmd.contains("এরোপ্লেন") ||
                lowerCmd.contains("airplane") ||
                lowerCmd.contains("হটস্পট") ||
                lowerCmd.contains("hotspot") ||
                lowerCmd.contains("লোকেশন") ||
                lowerCmd.contains("location") ||
                lowerCmd.contains("রোটেশন") ||
                lowerCmd.contains("rotate") ||
                lowerCmd.contains("ডার্ক মোড") ||
                lowerCmd.contains("dark mode") ||
                lowerCmd.contains("নোটিফিকেশন বার") ||
                lowerCmd.contains("কুইক সেটিং")) &&
                (lowerCmd.contains("অন") ||
                    lowerCmd.contains("অফ") ||
                    lowerCmd.contains("চালু") ||
                    lowerCmd.contains("বন্ধ") ||
                    lowerCmd.contains("নামাও") ||
                    lowerCmd.contains("on") ||
                    lowerCmd.contains("off") ||
                    lowerCmd.contains("toggle"))

            val isVolumeCmd = lowerCmd.contains("সাউন্ড") ||
                lowerCmd.contains("ভলিউম") ||
                lowerCmd.contains("আওয়াজ") ||
                lowerCmd.contains("রিংটোন") ||
                lowerCmd.contains("রিয়টন") ||
                lowerCmd.contains("রিয়টন") ||
                lowerCmd.contains("এলার্ম") ||
                lowerCmd.contains("অ্যালার্ম") ||
                lowerCmd.contains("sound") ||
                lowerCmd.contains("volume") ||
                lowerCmd.contains("সাইলেন্ট") ||
                lowerCmd.contains("মিউট") ||
                lowerCmd.contains("mute") ||
                lowerCmd.contains("ভাইব্রেট")

            val isRecentAppsCommand = (lowerCmd.contains("রিসেন্ট") ||
                lowerCmd.contains("recent") ||
                lowerCmd.contains("ব্যাকগ্রাউন্ড")) &&
                (lowerCmd.contains("সরা") ||
                    lowerCmd.contains("সরিয়ে") ||
                    lowerCmd.contains("সরিয়ে") ||
                    lowerCmd.contains("কেটে") ||
                    lowerCmd.contains("ক্লিয়ার") ||
                    lowerCmd.contains("মুছে") ||
                    lowerCmd.contains("বন্ধ") ||
                    lowerCmd.contains("clear") ||
                    lowerCmd.contains("close") ||
                    lowerCmd.contains("remove"))

            val isSelfHealOrUpdateCommand = (lowerCmd.contains("নিজেকে নিজে ঠিক") ||
                lowerCmd.contains("নিজে কে নিজে ঠিক") ||
                lowerCmd.contains("নিজের মধ্যে") && (lowerCmd.contains("ফিচার") || lowerCmd.contains("আপডেট")) ||
                lowerCmd.contains("নিজেকে আপডেট") ||
                lowerCmd.contains("self update") ||
                lowerCmd.contains("self heal"))

            val isPlayStoreCommand = (lowerCmd.contains("প্লে স্টোর") ||
                lowerCmd.contains("প্লে স্টুর") ||
                lowerCmd.contains("প্লেস্টোর") ||
                lowerCmd.contains("play store") ||
                lowerCmd.contains("playstore") ||
                lowerCmd.contains("আপডেট") ||
                lowerCmd.contains("আপটেড") ||
                lowerCmd.contains("ইনস্টল") ||
                lowerCmd.contains("ইনিস্টল") ||
                lowerCmd.contains("update") ||
                lowerCmd.contains("install") ||
                lowerCmd.contains("अपडेट") ||
                lowerCmd.contains("इंस्टॉल")) && !isSelfHealOrUpdateCommand

            val isYouTubeCommand = (lowerCmd.contains("ইউটিউব") ||
                lowerCmd.contains("ইউটিউবে") ||
                lowerCmd.contains("ইউটুব") ||
                lowerCmd.contains("youtube") ||
                lowerCmd.contains("यूट्यूब")) && !isPlayStoreCommand && !isRecentAppsCommand

            val isLoadingWatchCommand = (lowerCmd.contains("লোড") ||
                lowerCmd.contains("load") ||
                lowerCmd.contains("ডাউনলোড") ||
                lowerCmd.contains("download")) &&
                (lowerCmd.contains("শেষ হলে") ||
                    lowerCmd.contains("হলে জানাই") ||
                    lowerCmd.contains("হলে বল") ||
                    lowerCmd.contains("জানাইয়ো") ||
                    lowerCmd.contains("জানিও") ||
                    lowerCmd.contains("let me know"))

            val isProactiveTimerCommand = (lowerCmd.contains("মিনিট পর") ||
                lowerCmd.contains("মিনিট পরে") ||
                lowerCmd.contains("সেকেন্ড পর") ||
                lowerCmd.contains("সেকেন্ড পরে") ||
                lowerCmd.contains("minutes later") ||
                lowerCmd.contains("minute later")) &&
                (lowerCmd.contains("জানাই") ||
                    lowerCmd.contains("জানিও") ||
                    lowerCmd.contains("বলো") ||
                    lowerCmd.contains("ডাক") ||
                    lowerCmd.contains("remind"))

            if (isLoadingWatchCommand && _proactiveWatchTasks.value.none { !it.isCompleted && it.type == "WATCH_LOADING" }) {
                scheduleProactiveWatchOrTimer(mode = "WATCH_LOADING", seconds = 0, label = effectiveCommand.take(40))
            } else if (isProactiveTimerCommand) {
                val bnToEnDigits = effectiveCommand
                    .replace('০', '0').replace('১', '1').replace('২', '2').replace('৩', '3').replace('৪', '4')
                    .replace('৫', '5').replace('৬', '6').replace('৭', '7').replace('৮', '8').replace('৯', '9')
                val num = Regex("(\\d+)").find(bnToEnDigits)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 10
                val secs = if (lowerCmd.contains("সেকেন্ড") || lowerCmd.contains("sec")) num else num * 60
                scheduleProactiveWatchOrTimer(mode = "TIMER", seconds = secs, label = effectiveCommand.take(40))
            }

            if (isRecentAppsCommand) {
                val extractedApp = effectiveCommand
                    .replace(Regex("(?i)(রিসেন্ট|অ্যাপ|অ্যাপস|থেকে|সরিয়ে|সরিয়ে|সরাও|দাও|করো|কেটে|ক্লিয়ার|সব|ব্যাকগ্রাউন্ড|recent|apps|app|from|clear|remove|close)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "ALL" }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.CLEAR_RECENT_APPS,
                        title = "রিসেন্ট অ্যাপ থেকে সরানো: $extractedApp",
                        description = cleanSpoken,
                        appName = extractedApp,
                        query = extractedApp,
                        settingTarget = extractedApp
                    )
                )
            } else if (isSelfHealOrUpdateCommand) {
                val mode = if (lowerCmd.contains("ঠিক") || lowerCmd.contains("ফাস্ট")) "SELF_HEAL" else "INSTALL_UPDATE"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SELF_HEAL_OR_UPDATE,
                        title = "সেলফ-হিলিং ও আপডেট: $mode",
                        description = cleanSpoken,
                        settingTarget = mode,
                        query = effectiveCommand,
                        body = effectiveCommand
                    )
                )
            } else if (isQuickSettingToggle) {
                val enable = !(lowerCmd.contains("অফ") || lowerCmd.contains("বন্ধ") || lowerCmd.contains("off"))
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                        title = "কুইক সেটিংস টগল",
                        description = cleanSpoken,
                        settingTarget = effectiveCommand,
                        settingEnabled = enable,
                        query = effectiveCommand
                    )
                )
            } else if (isVolumeCmd) {
                val bnToEn = effectiveCommand
                    .replace('০', '0').replace('১', '1').replace('২', '2').replace('৩', '3').replace('৪', '4')
                    .replace('৫', '5').replace('৬', '6').replace('৭', '7').replace('৮', '8').replace('৯', '9')
                val pct = Regex("(\\d+)").find(bnToEn)?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 100) ?: -1
                val stream = when {
                    lowerCmd.contains("রিং") || lowerCmd.contains("রিয়টন") || lowerCmd.contains("রিয়টন") || lowerCmd.contains("ring") -> "RING"
                    lowerCmd.contains("অ্যালার্ম") || lowerCmd.contains("এলার্ম") || lowerCmd.contains("alarm") -> "ALARM"
                    lowerCmd.contains("নোটিফিকেশন") || lowerCmd.contains("notification") -> "NOTIFICATION"
                    lowerCmd.contains("কল") || lowerCmd.contains("call") -> "VOICE_CALL"
                    lowerCmd.contains("সিস্টেম") || lowerCmd.contains("system") -> "SYSTEM"
                    lowerCmd.contains("সব") || lowerCmd.contains("সকল") || lowerCmd.contains("all") -> "ALL"
                    else -> "MUSIC"
                }
                val dir = when {
                    lowerCmd.contains("মিউট") || lowerCmd.contains("সাইলেন্ট") || lowerCmd.contains("mute") || lowerCmd.contains("silent") -> "MUTE"
                    lowerCmd.contains("আনমিউট") || lowerCmd.contains("unmute") -> "UNMUTE"
                    lowerCmd.contains("ফুল") || lowerCmd.contains("সর্বোচ্চ") || lowerCmd.contains("full") || lowerCmd.contains("max") -> "MAX"
                    lowerCmd.contains("অর্ধেক") || lowerCmd.contains("half") -> "HALF"
                    lowerCmd.contains("কমা") || lowerCmd.contains("কম") || lowerCmd.contains("down") || lowerCmd.contains("decrease") -> "DOWN"
                    else -> "UP"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SET_VOLUME,
                        title = "সাউন্ড কন্ট্রোল: $stream",
                        description = cleanSpoken,
                        volumeStream = stream,
                        volumePercent = pct,
                        screenControlCommand = dir,
                        body = dir
                    )
                )
            } else if (isPlayStoreCommand && !lowerCmd.contains("apk")) {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                if (resolved.cleanAppName.isNotBlank()) {
                    val modeLabel = if (resolved.isUpdateRequest) "UPDATE" else "INSTALL"
                    val titlePrefix = if (resolved.isUpdateRequest) "প্লে স্টোর অটো-আপডেট" else "প্লে স্টোর অটো-ইনস্টল"
                    steps.add(
                        AgentStep(
                            id = UUID.randomUUID().toString(),
                            actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                            title = "$titlePrefix: ${resolved.cleanAppName}",
                            description = cleanSpoken,
                            query = resolved.cleanAppName,
                            appName = resolved.cleanAppName,
                            screenControlCommand = modeLabel,
                            body = modeLabel
                        )
                    )
                }
            } else if (isYouTubeCommand) {
                val cleanYtQuery = deviceExecutor.extractGenuineYouTubeSearchQuery(effectiveCommand)
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "অ্যাপ ওপেন: YouTube",
                        description = cleanSpoken,
                        query = "YouTube",
                        appName = "YouTube",
                        body = cleanYtQuery
                    )
                )
            }
        }

        val summaryHeader = if (modelUsed == "gemini-4-argon") {
            "ARCHER AI • ARGON 4 ENGINE"
        } else {
            "ARCHER AI — জেমিনি ইন্টেলিজেন্স"
        }

        return AgentPlanResponse(
            id = UUID.randomUUID().toString(),
            userPrompt = userPrompt,
            spokenReply = cleanSpoken,
            summaryTitle = summaryHeader,
            steps = steps,
            triggeredByWakeWord = triggeredByWakeWord,
            modelUsed = modelUsed
        )
    }

    /**
     * Used ONLY if all cloud Gemini endpoints fail (e.g., offline or invalid API key).
     * Supports Bengali, Banglish, English, Hindi, and ASR phonetic variations,
     * and strictly obeys _lockedReplyLanguage ("ENGLISH_ONLY", "BENGALI_ONLY", "HINDI_ONLY").
     */
    private fun generateSmartContextualAnswerWhenOffline(
        userPrompt: String,
        effectiveCommand: String,
        triggeredByWakeWord: Boolean
    ): AgentPlanResponse {
        val lower = effectiveCommand.lowercase().trim()
        val steps = mutableListOf<AgentStep>()
        val isEnglishLocked = _lockedReplyLanguage.value == "ENGLISH_ONLY"
        val reply: String

        when {
            lower.contains("ইংরেজিতে বল") || lower.contains("ইরেজিতে বল") || lower.contains("only in english") || lower.contains("speak in english") -> {
                _lockedReplyLanguage.value = "ENGLISH_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "ENGLISH_ONLY").apply()
                reply = "Yes Sir, I have locked my response language to English. Even if you speak to me in Bengali, Banglish, or Hindi, I will strictly reply in English."
            }
            lower.contains("শুধু বাংলায় বল") || lower.contains("বাংলায় কথা বল") -> {
                _lockedReplyLanguage.value = "BENGALI_ONLY"
                prefs.edit().putString(KEY_LOCKED_REPLY_LANGUAGE, "BENGALI_ONLY").apply()
                reply = "জি স্যার, আমি আপনার নির্দেশনা মাথায় রেখেছি। এখন থেকে আমি সবসময় বাংলায় উত্তর দেবো।"
            }
            lower.contains("কয়টা বাজে") || lower.contains("সময়") || lower.contains("time") || lower.contains("তারিখ") || lower.contains("समय") -> {
                val now = Date()
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
                val dateStr = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(now)
                reply = if (isEnglishLocked) {
                    "Yes Sir, the current time is $timeStr and today's date is $dateStr."
                } else {
                    "জি স্যার, এখন সময় $timeStr এবং আজকের তারিখ $dateStr।"
                }
            }
            lower.contains("ব্যাটারি") || lower.contains("চার্জ") || lower.contains("battery") || lower.contains("बैटरी") -> {
                val hw = deviceExecutor.hardwareState.value
                reply = if (isEnglishLocked) {
                    "Yes Sir, your phone battery is currently at ${hw.batteryPercent}%."
                } else {
                    "জি স্যার, আপনার ফোনে এখন ${hw.batteryPercent}% চার্জ রয়েছে।"
                }
            }
            // Quick Settings & Notification Bar Auto-Toggle (Wi-Fi, Bluetooth, SIM Net, Airplane Mode, Hotspot, Location, Rotate, DND, Torch)
            lower.contains("ওয়াইফাই") ||
                lower.contains("wifi") ||
                lower.contains("wi-fi") ||
                lower.contains("ব্লুটুথ") ||
                lower.contains("bluetooth") ||
                lower.contains("সিমের নেট") ||
                lower.contains("মোবাইল ডাটা") ||
                lower.contains("ডাটা") ||
                lower.contains("mobile data") ||
                lower.contains("এরোপ্লেন") ||
                lower.contains("ফ্লাইট মোড") ||
                lower.contains("airplane") ||
                lower.contains("হটস্পট") ||
                lower.contains("hotspot") ||
                lower.contains("লোকেশন") ||
                lowerCmdContainsLocationOrRotate(lower) ||
                lower.contains("টর্চ") ||
                lower.contains("ফ্ল্যাশলাইট") ||
                lower.contains("torch") ||
                lower.contains("flashlight") ||
                lower.contains("নোটিফিকেশন বার") -> {
                val turnOff = lower.contains("বন্ধ") || lower.contains("অফ") || lower.contains("off") || lower.contains("बंद")
                val enableFlag = !turnOff
                reply = if (isEnglishLocked) {
                    "Yes Sir, toggling the requested Quick Setting now."
                } else {
                    "জি স্যার, নোটিফিকেশন বার ও সিস্টেম কন্ট্রোল থেকে সেটিংসটি এখনই টগল করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TOGGLE_SYSTEM_SETTING,
                        title = "কুইক সেটিংস অটো-টগল",
                        description = reply,
                        settingTarget = effectiveCommand,
                        settingEnabled = enableFlag,
                        query = effectiveCommand
                    )
                )
            }
            // Full 6-Stream Sound & Volume Control
            lower.contains("সাউন্ড") ||
                lower.contains("ভলিউম") ||
                lower.contains("আওয়াজ") ||
                lower.contains("sound") ||
                lower.contains("volume") ||
                lower.contains("সাইলেন্ট") ||
                lower.contains("মিউট") ||
                lower.contains("mute") ||
                lower.contains("ভাইব্রেট") -> {
                val pct = effectiveCommand.filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: -1
                val stream = when {
                    lower.contains("রিং") || lower.contains("ring") -> "RING"
                    lower.contains("অ্যালার্ম") || lower.contains("alarm") -> "ALARM"
                    lower.contains("নোটিফিকেশন") || lower.contains("notification") -> "NOTIFICATION"
                    lower.contains("কল") || lower.contains("call") -> "VOICE_CALL"
                    lower.contains("সিস্টেম") || lower.contains("system") -> "SYSTEM"
                    lower.contains("সব") || lower.contains("ফুল") || lower.contains("all") -> "ALL"
                    else -> "MUSIC"
                }
                val dir = when {
                    lower.contains("মিউট") || lower.contains("সাইলেন্ট") || lower.contains("mute") || lower.contains("silent") -> "MUTE"
                    lower.contains("আনমিউট") || lower.contains("unmute") -> "UNMUTE"
                    lower.contains("ভাইব্রেট") || lower.contains("vibrate") -> "VIBRATE"
                    lower.contains("ফুল") || lower.contains("সর্বোচ্চ") || lower.contains("full") || lower.contains("max") -> "MAX"
                    lower.contains("কমা") || lower.contains("কম") || lower.contains("down") || lower.contains("decrease") -> "DOWN"
                    else -> "UP"
                }
                reply = if (isEnglishLocked) {
                    "Yes Sir, adjusting the $stream sound volume now."
                } else {
                    "জি স্যার, আপনার নির্দেশ অনুযায়ী সাউন্ড ভলিউম ($stream) অ্যাডজাস্ট করে দিয়েছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SET_VOLUME,
                        title = "সাউন্ড কন্ট্রোল: $stream ($dir)",
                        description = reply,
                        volumeStream = stream,
                        volumePercent = pct,
                        screenControlCommand = dir,
                        body = dir
                    )
                )
            }
            // Coding & Argon 4 Workspace + archer Folder Archiver
            lower.contains("কোড") ||
                lower.contains("কোডিং") ||
                lower.contains("argon") ||
                lower.contains("আরগন") ||
                lower.contains("code") ||
                lower.contains("coding") ||
                lower.contains("python") ||
                lower.contains("html") ||
                lower.contains("script") ||
                lower.contains("প্রোগ্রাম") -> {
                val isHtml = lower.contains("html") || lower.contains("ওয়েবসাইট") || lower.contains("website") || lower.contains("web")
                val fileName = if (isHtml) "index.html" else "archer_main.py"
                val lang = if (isHtml) "html" else "python"
                val generatedCode = if (isHtml) {
                    """
                    <!DOCTYPE html>
                    <html lang="bn">
                    <head>
                        <meta charset="UTF-8" />
                        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                        <title>ARCHER AI — Argon 4 Project</title>
                        <style>
                            body { background: #03120B; color: #00FF88; font-family: sans-serif; padding: 2rem; text-align: center; }
                            .card { border: 1px solid #00FF88; border-radius: 16px; padding: 24px; max-width: 540px; margin: 0 auto; background: #072316; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>ARCHER AI • Argon 4</h1>
                            <p>Prompt: $effectiveCommand</p>
                        </div>
                    </body>
                    </html>
                    """.trimIndent()
                } else {
                    """
                    #!/usr/bin/env python3
                    # Generated by ARCHER AI (Argon 4 Engine)
                    # Task: $effectiveCommand

                    def run_archer_task():
                        print("ARCHER AI Argon-4 Task Initialized")
                        print("Prompt:", ${"\""}${effectiveCommand.replace("\"", "'")}${"\""})

                    if __name__ == "__main__":
                        run_archer_task()
                    """.trimIndent()
                }
                reply = if (isEnglishLocked) {
                    "Yes Sir, Argon 4 has written the code and saved it inside the archer folder as a single file, project folder, and ZIP archive."
                } else {
                    "জি স্যার, Argon 4 দিয়ে কোডিং সম্পন্ন করে মেইন স্টোরেজের 'archer' ফোল্ডারে ফাইল, প্রজেক্ট ফোল্ডার এবং জিপ (.zip) আকারে সেভ করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.GENERATE_CODE_PROJECT,
                        title = "Argon 4 কোডিং ও archer ফোল্ডারে সেভ: $fileName",
                        description = reply,
                        query = effectiveCommand,
                        codeFilename = fileName,
                        codeLanguage = lang,
                        body = generatedCode
                    )
                )
            }
            // Contact List + Screen-Wide Name Search & Call/Action
            lower.contains("কল") ||
                lower.contains("ফোন দাও") ||
                lower.contains("ডায়াল") ||
                lower.contains("call ") ||
                lower.contains("কন্টাক্ট") ||
                lower.contains("contact") ||
                lower.contains("নাম খুঁজে") ||
                lower.contains("বিচারাইয়া") -> {
                val cleanedName = effectiveCommand
                    .replace(Regex("(?i)(কল|দাও|করো|কে|ফোন|ডায়াল|কন্টাক্ট|থেকে|স্ক্রিনে|খুঁজে|বিচারাইয়া|call|phone|dial|contact)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { effectiveCommand }
                reply = if (isEnglishLocked) {
                    "Yes Sir, scanning your Contacts list and the entire screen for '$cleanedName' now."
                } else {
                    "জি স্যার, কন্টাক্ট লিস্ট এবং পুরো স্ক্রিনে '$cleanedName' নামটি খুঁজে এখনই অ্যাকশন নিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SCAN_CONTACT_OR_SCREEN,
                        title = "কন্টাক্ট ও স্ক্রিন স্ক্যান: $cleanedName",
                        description = reply,
                        recipient = cleanedName,
                        query = cleanedName,
                        screenControlCommand = "CALL_OR_CLICK"
                    )
                )
            }
            // Screen Reading, Permanent Screen Share & 4-Directional Touch Gesture Control (UP, DOWN, LEFT, RIGHT)
            lower.contains("স্ক্রিনে কি আছে") ||
                lower.contains("স্ক্রিনে দেখ") ||
                lower.contains("সক্রিনে দেখ") ||
                lower.contains("স্ক্রিন দেখ") ||
                lower.contains("সক্রিন দেখা") ||
                lower.contains("স্ক্রিন শেয়ার") ||
                lower.contains("সক্রিন সেয়ার") ||
                lower.contains("স্ক্রিন পড়ো") ||
                lower.contains("টাচ করো") ||
                lower.contains("ক্লিক করো") ||
                lower.contains("স্ক্রল") ||
                lower.contains("সোয়াইপ") ||
                lower.contains("ডানে") ||
                lower.contains("বামে") ||
                lower.contains("উপরে") ||
                lower.contains("নিচে") ||
                lower.contains("tap") ||
                lower.contains("swipe") ||
                lower.contains("scroll") -> {
                multimodalLiveService.enableScreenStreamNow()
                val cmdType = when {
                    lower.contains("নিচে") || lower.contains("স্ক্রল ডাউন") || lower.contains("scroll down") || lower.contains("swipe up") -> "SWIPE_UP"
                    lower.contains("উপরে") || lower.contains("স্ক্রল আপ") || lower.contains("scroll up") || lower.contains("swipe down") -> "SWIPE_DOWN"
                    lower.contains("বামে") || lower.contains("left") || lower.contains("swipe left") -> "SWIPE_LEFT"
                    lower.contains("ডানে") || lower.contains("right") || lower.contains("swipe right") -> "SWIPE_RIGHT"
                    lower.contains("ডাবল") || lower.contains("double") -> "DOUBLE_TAP"
                    lower.contains("লং") || lower.contains("চাপ দিয়ে") || lower.contains("long press") -> "LONG_PRESS"
                    lower.contains("স্ক্রিনে কি") || lower.contains("পড়ো") || lower.contains("সক্রিন দেখা") || lower.contains("স্ক্রিনে দেখ") || lower.contains("সক্রিনে দেখ") || lower.contains("স্ক্রিন শেয়ার") || lower.contains("সক্রিন সেয়ার") -> "READ_SCREEN"
                    else -> "CLICK"
                }
                val targetTxt = effectiveCommand
                    .replace(Regex("(?i)(স্ক্রিনে|সক্রিনে|উপরে|নিচে|ডানে|বামে|টাচ|ক্লিক|করো|দাও|চাপ|দেখ|দেখো|tap|click|on)"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                reply = if (cmdType == "READ_SCREEN") {
                    val vis = accessibilityState.value.visibleScreenText
                    val hasProj = com.example.service.ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant
                    if ((accessibilityState.value.isServiceEnabled || hasProj) && vis.isNotBlank()) {
                        if (isEnglishLocked) "Yes Sir, I am looking at your screen right now. I can see ${vis.take(180)}"
                        else "জি স্যার, আমি সরাসরি আপনার স্ক্রিন দেখছি। এখানে দেখা যাচ্ছে ${vis.take(180)}"
                    } else if (hasProj) {
                        if (isEnglishLocked) "Yes Sir, live screen sharing is active and I am watching your screen right now."
                        else "জি স্যার, লাইভ স্ক্রিন শেয়ার চালু আছে, আমি এখন সরাসরি আপনার স্ক্রিন দেখছি। বলুন কোথায় ক্লিক বা স্ক্রল করব?"
                    } else {
                        com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                        if (isEnglishLocked) "Sir, I opened the one-time screen share permission prompt. Please tap Allow once, and from next time I will view the screen automatically."
                        else "জি স্যার, প্রথমবার স্ক্রিন শেয়ারের পারমিশন বক্সটি খুলে দিয়েছি—একবার Allow করে দিন, এরপর থেকে বললেই সাথে সাথে নিজে থেকে স্ক্রিন দেখব।"
                    }
                } else {
                    if (isEnglishLocked) "Yes Sir, executing $cmdType on the screen right now."
                    else "জি স্যার, স্ক্রিনে এখনি $cmdType করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.SCREEN_UI_CONTROL,
                        title = "স্ক্রিন ভিশন ও ৪-দিকের টাচ: $cmdType",
                        description = reply,
                        query = targetTxt,
                        screenControlCommand = cmdType,
                        body = targetTxt
                    )
                )
            }
            lower.contains("নোটিফিকেশন") || lower.contains("notification") || lower.contains("মেসেজ পড়ো") || lower.contains("কে মেসেজ") -> {
                val filter = when {
                    lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") -> "WhatsApp"
                    lower.contains("telegram") || lower.contains("টেলিগ্রাম") -> "Telegram"
                    else -> ""
                }
                reply = NotificationReplyBus.readNotificationsSummary(filter)
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.READ_NOTIFICATIONS,
                        title = "নোটিফিকেশন রিডার",
                        description = reply,
                        query = filter,
                        appName = filter
                    )
                )
            }
            lower.contains("apk") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val app = resolved.cleanAppName.ifBlank { "Telegram" }
                reply = if (isEnglishLocked) {
                    "Yes Sir, launching the APK downloader and installer for '$app' now."
                } else {
                    "জি স্যার, '$app' APK ডাউনলোড ও অটো-ইনস্টলার এখনই চালু করছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.INSTALL_APK_PACKAGE,
                        title = "APK অটো-ইনস্টলার: $app",
                        description = reply,
                        query = app,
                        appName = app,
                        targetUrlOrFile = app
                    )
                )
            }
            lower.contains("প্লে স্টোর") ||
                lower.contains("প্লে স্টুর") ||
                lower.contains("প্লেস্টোর") ||
                lower.contains("play store") ||
                lower.contains("playstore") ||
                lower.contains("আপডেট") ||
                lower.contains("আপটেড") ||
                lower.contains("আপডেড") ||
                lower.contains("ইনস্টল") ||
                lower.contains("ইনিস্টল") ||
                lower.contains("install") ||
                lower.contains("update") ||
                lower.contains("updet") ||
                lower.contains("अपडेट") ||
                lower.contains("इंस्टॉल") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val cleanApp = resolved.cleanAppName
                val isUpdate = resolved.isUpdateRequest
                val modeLabel = if (isUpdate) "UPDATE" else "INSTALL"
                reply = when {
                    isEnglishLocked && cleanApp.isBlank() -> "Yes Sir, opening Google Play Store now."
                    isEnglishLocked && isUpdate -> "Yes Sir, searching '$cleanApp' on Play Store and clicking Update automatically."
                    isEnglishLocked -> "Yes Sir, searching '$cleanApp' on Play Store and clicking Install automatically."
                    cleanApp.isBlank() -> "জি স্যার, গুগল প্লে স্টোর অ্যাপটি এখনই খুলে দিচ্ছি।"
                    isUpdate -> "জি স্যার, প্লে স্টোরে '$cleanApp' সার্চ দিয়ে এখনই অটো-আপডেট করে দিচ্ছি।"
                    else -> "জি স্যার, প্লে স্টোরে '$cleanApp' সার্চ দিয়ে এখনই অটো-ইনস্টল করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                        title = if (isUpdate) "প্লে স্টোর অটো-আপডেট: ${cleanApp.ifBlank { "Play Store" }}" else "প্লে স্টোর অটো-ইনস্টল: ${cleanApp.ifBlank { "Play Store" }}",
                        description = reply,
                        query = cleanApp,
                        appName = cleanApp,
                        screenControlCommand = modeLabel,
                        body = modeLabel
                    )
                )
            }
            lower.contains("ইউটিউব") ||
                lower.contains("ইউটিউবে") ||
                lower.contains("ইউটুব") ||
                lower.contains("youtube") ||
                lower.contains("you tube") ||
                lower.contains("यूट्यूब") -> {
                val subQuery = effectiveCommand
                    .replace(
                        Regex("(?i)(ডাইরেক্ট|সরাসরি|ইউটিউবে|ইউটিউব|ইউটুব|यूट्यूब|youtube|you tube|ওপেন|খোলো|খুলো|চালু|করো|দাও|open|app|অ্যাপ)"),
                        " "
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                reply = if (isEnglishLocked) {
                    if (subQuery.isNotBlank()) "Yes Sir, opening the YouTube app directly and playing '$subQuery'."
                    else "Yes Sir, opening the YouTube app directly now."
                } else {
                    if (subQuery.isNotBlank()) {
                        "জি স্যার, সরাসরি YouTube অ্যাপ খুলে '$subQuery' সার্চ ও প্লে করে দিচ্ছি।"
                    } else {
                        "জি স্যার, সরাসরি YouTube অ্যাপটি এখনই খুলে দিচ্ছি।"
                    }
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "সরাসরি YouTube অ্যাপ: ${subQuery.ifBlank { "Home" }}",
                        description = reply,
                        appName = "YouTube",
                        query = "YouTube",
                        body = subQuery
                    )
                )
            }
            lower.contains("টেলিগ্রাম") || lower.contains("telegram") -> {
                val q = effectiveCommand.replace(Regex("(?i)(টেলিগ্রাম|টেলিগ্রামে|telegram|থেকে|ডাউনলোড|সার্চ|করো|দাও)"), "").trim().ifBlank { "AI PDF" }
                reply = if (isEnglishLocked) "Yes Sir, processing '$q' in Telegram." else "জি স্যার, টেলিগ্রামে গিয়ে '$q' প্রসেস করছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.TELEGRAM_AUTOMATION,
                        title = "টেলিগ্রাম অটোমেশন",
                        description = reply,
                        recipient = "@telegram",
                        query = q,
                        targetUrlOrFile = q
                    )
                )
            }
            lower.contains("হোয়াটসঅ্যাপ") || lower.contains("whatsapp") -> {
                val (parsedContact, parsedCleanMsg) = deviceExecutor.parseContactAndCleanMessage("", effectiveCommand)
                reply = if (isEnglishLocked) {
                    "Yes Sir, opening WhatsApp and sending '$parsedCleanMsg' to ${parsedContact.ifBlank { "the selected chat" }}."
                } else {
                    "জি স্যার, হোয়াটসঅ্যাপ স্ক্রিন দেখে ${parsedContact.ifBlank { "চ্যাটে" }}-কে '$parsedCleanMsg' মেসেজটি পাঠাচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.WHATSAPP_AUTOMATION,
                        title = "হোয়াটসঅ্যাপ অটোমেশন",
                        description = reply,
                        recipient = parsedContact,
                        body = parsedCleanMsg
                    )
                )
            }
            lower.contains("সেটিং") || lower.contains("setting") || lower.contains("accessibility") -> {
                val targetSetting = when {
                    lower.contains("accessibility") || lower.contains("অ্যাক্সেসিবিলিটি") -> "ACCESSIBILITY"
                    lower.contains("notification") -> "NOTIFICATION_LISTENER"
                    lower.contains("সাউন্ড") || lower.contains("sound") -> "SOUND"
                    lower.contains("ডিসপ্লে") || lower.contains("display") -> "DISPLAY"
                    else -> "MAIN_SETTINGS"
                }
                reply = if (isEnglishLocked) "Yes Sir, opening system settings ($targetSetting) now." else "জি স্যার, সিস্টেম সেটিংস ($targetSetting) এখনই খুলে দিচ্ছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_SETTINGS,
                        title = "সিস্টেম সেটিংস: $targetSetting",
                        description = reply,
                        settingTarget = targetSetting
                    )
                )
            }
            lower.contains("ওপেন") || lower.contains("খোলো") || lower.contains("খুলো") || lower.contains("open") || lower.contains("खोलो") -> {
                val resolved = deviceExecutor.cleanPlayStoreAppQuery(effectiveCommand)
                val targetApp = resolved.cleanAppName.ifBlank {
                    effectiveCommand.replace(Regex("(?i)(ওপেন|খোলো|খুলো|চালু|করো|দাও|open|app|অ্যাপ|खोलो)"), "").trim()
                }.ifBlank { "YouTube" }
                reply = if (isEnglishLocked) "Yes Sir, opening $targetApp directly now." else "জি স্যার, $targetApp অ্যাপটি এখনই সরাসরি খুলে দিচ্ছি।"
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = AgentActionType.OPEN_APP,
                        title = "অ্যাপ ওপেন: $targetApp",
                        description = reply,
                        appName = targetApp,
                        query = targetApp
                    )
                )
            }
            lower.contains("গান") || lower.contains("মিউজিক") || lower.contains("music") || lower.contains("song") -> {
                val off = lower.contains("বন্ধ") || lower.contains("stop")
                reply = if (isEnglishLocked) {
                    if (off) "Yes Sir, music has been stopped." else "Yes Sir, playing music now."
                } else {
                    if (off) "জি স্যার, গান বন্ধ করা হয়েছে।" else "জি স্যার, এখনই গান চালু করে দিচ্ছি।"
                }
                steps.add(
                    AgentStep(
                        id = UUID.randomUUID().toString(),
                        actionType = if (off) AgentActionType.STOP_MUSIC else AgentActionType.PLAY_MUSIC,
                        title = "মিউজিক কন্ট্রোল",
                        description = reply,
                        query = effectiveCommand,
                        mood = if (lower.contains("মন খারাপ") || lower.contains("sad")) "SAD" else "ROMANTIC"
                    )
                )
            }
            else -> {
                reply = if (isEnglishLocked) {
                    "Yes Sir, I heard your command and I am ready. You can also tap the KEY button at the top-left to set a custom Gemini API key."
                } else if (_geminiApiKeyState.value.isBlank()) {
                    "স্যার, সরাসরি জেমিনি এআই উত্তর পেতে স্ক্রিনের উপরে বাম পাশের KEY বাটনে ট্যাপ করে আপনার Google AI Studio (AIza...) API Key পেস্ট করে সেভ করুন।"
                } else {
                    "স্যার, আপনার দেওয়া Gemini API Key অথবা ইন্টারনেট কানেকশন চেক করুন যেন জেমিনি সরাসরি উত্তর দিতে পারে।"
                }
            }
        }

        return AgentPlanResponse(
            id = UUID.randomUUID().toString(),
            userPrompt = userPrompt,
            spokenReply = reply,
            summaryTitle = "ARCHER AI",
            steps = steps,
            triggeredByWakeWord = triggeredByWakeWord
        )
    }

    private fun lowerCmdContainsLocationOrRotate(lower: String): Boolean {
        return lower.contains("location") ||
            lower.contains("gps") ||
            lower.contains("রোটেশন") ||
            lower.contains("rotate") ||
            lower.contains("ডার্ক মোড") ||
            lower.contains("dark mode") ||
            lower.contains("dnd") ||
            lower.contains("ব্যাটারি সেভার") ||
            lower.contains("battery saver")
    }

    private suspend fun executePlanStepsIfEnabled(plan: AgentPlanResponse) {
        if (!_autoExecute.value || plan.steps.isEmpty()) return
        val updatedSteps = plan.steps.toMutableList()
        for (index in updatedSteps.indices) {
            val step = updatedSteps[index]
            val result = deviceExecutor.executeStep(step, launchExternalIntents = index == 0)
            multimodalLiveService.recordLiveToolExecution("${step.actionType.name}: $result")
            updatedSteps[index] = step.copy(isExecuted = true, executionResult = result)
            val updatedPlan = plan.copy(steps = updatedSteps.toList())
            _agentPlans.value = _agentPlans.value.map {
                if (it.id == plan.id) updatedPlan else it
            }
            _uiState.value = AgentUiState.Success(updatedPlan)
            if (index < updatedSteps.lastIndex) {
                delay(220L)
            }
        }
    }

    private suspend fun logPlanToHistory(userPrompt: String, plan: AgentPlanResponse) {
        historyRepo.logCommand(
            userPrompt = userPrompt,
            agentReply = plan.spokenReply,
            actionsSummary = if (plan.steps.isEmpty()) "AI Conversation" else plan.steps.joinToString(" • ") { it.title },
            primaryActionType = plan.steps.firstOrNull()?.actionType?.name ?: "ANSWER_QUESTION"
        )
    }

    fun executeSingleStep(planId: String, step: AgentStep) {
        val result = deviceExecutor.executeStep(step, launchExternalIntents = true)
        _liveArcherReply.value = result
        voiceBridge.speak(result)
        _agentPlans.value = _agentPlans.value.map { plan ->
            if (plan.id != planId) {
                plan
            } else {
                plan.copy(
                    steps = plan.steps.map { s ->
                        if (s.id == step.id) s.copy(isExecuted = true, executionResult = result) else s
                    }
                )
            }
        }
    }

    fun runSmartRoutine(routine: SmartRoutine) {
        viewModelScope.launch {
            _liveUserTranscript.value = routine.promptCommand
            val executedSteps = routine.presetSteps.mapIndexed { idx, step ->
                val res = deviceExecutor.executeStep(step, launchExternalIntents = idx == routine.presetSteps.lastIndex)
                step.copy(isExecuted = true, executionResult = res)
            }
            val reply = "জি স্যার! ${routine.titleBn} চালু করা হয়েছে।"
            _liveArcherReply.value = reply
            val plan = AgentPlanResponse(
                id = UUID.randomUUID().toString(),
                userPrompt = routine.promptCommand,
                spokenReply = reply,
                summaryTitle = routine.titleBn,
                steps = executedSteps
            )
            _agentPlans.value = listOf(plan) + _agentPlans.value
            _uiState.value = AgentUiState.Success(plan)
            voiceBridge.speak(plan.spokenReply)
        }
    }

    fun executeTodayTask(task: TodayTaskEntity) {
        viewModelScope.launch {
            historyRepo.toggleTaskCompleted(task.id, !task.isCompleted)
            if (!task.isCompleted) {
                processUserCommand(task.autoCommandPrompt)
            }
        }
    }

    fun addTodayTask(title: String, prompt: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            historyRepo.addTask(title, prompt.ifBlank { title })
        }
    }

    fun deleteTodayTask(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteTask(id)
        }
    }

    fun addPlatformAccount(platform: String, name: String, handleOrNumber: String, notes: String) {
        if (name.isBlank() || handleOrNumber.isBlank()) return
        viewModelScope.launch {
            historyRepo.addPlatformAccount(platform, name, handleOrNumber, notes)
            val msg = "জি স্যার, $platform এ $name এর আইডি সংরক্ষণ করা হয়েছে।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        }
    }

    fun deletePlatformAccount(id: Int) {
        viewModelScope.launch {
            historyRepo.deletePlatformAccount(id)
        }
    }

    fun addManualMemoryNote(title: String, content: String, category: String = "USER_PREFERENCE") {
        if (content.isBlank()) return
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val cleanTitle = title.ifBlank { "স্যারের নোট" }
            historyRepo.saveMemoryNote(cleanTitle, content, category)
            com.example.service.GoogleDriveMemorySyncService.backupMemoryNoteToDrive(
                context = ctx,
                title = cleanTitle,
                factContent = content,
                tagsOrCategory = category
            )
            historyRepo.pruneLocalCacheForZeroPhoneStorageImpact()
            val msg = "জি বস, আপনার তথ্যটি লোকাল SQLite ও ৫ টিবি Google Drive ('AI_OS_Memory')-এ সংরক্ষণ করা হয়েছে (মেইন স্টোরেজ ১০০% ফ্রি)।"
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        }
    }

    fun syncAllMemoriesToGoogleDriveNow() {
        val ctx = getApplication<Application>().applicationContext
        viewModelScope.launch {
            val msg = com.example.service.GoogleDriveMemorySyncService.syncAllAppDataToDriveNow(
                context = ctx,
                speakResult = false
            )
            historyRepo.pruneLocalCacheForZeroPhoneStorageImpact()
            _liveArcherReply.value = msg
            voiceBridge.speak(msg)
        }
    }

    fun deleteMemoryNote(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteMemoryNote(id)
        }
    }

    fun triggerSelfHealAndRepair(reason: String = "ম্যানুয়াল সেলফ-হিলিং ও টার্বো স্পিড বুস্ট") {
        val ctx = getApplication<Application>().applicationContext
        val report = com.example.service.SmartMemoryAndSelfUpdateEngine.runSelfDiagnosticsAndRepair(ctx, reason)
        viewModelScope.launch {
            seedInitialDataIfEmpty()
        }
        _liveArcherReply.value = report
        triggerProactiveVoiceAnnouncement(
            eventInstruction = "স্যার আপনাকে নিজেকে নিজে ঠিক ও আপডেট করতে বলেছেন। সেলফ-রিপেয়ার সম্পন্ন হয়েছে: $report — ১ বাক্যে স্যারকে জানান।",
            fallbackSpokenBn = report
        )
    }

    fun installCustomSelfUpdateFeature(title: String, specification: String) {
        if (specification.isBlank()) return
        val report = com.example.service.SmartMemoryAndSelfUpdateEngine.installLiveFeatureUpdate(
            featureTitle = title.ifBlank { specification.take(32) },
            featurePromptOrRule = specification,
            triggerHint = title
        )
        _liveArcherReply.value = report
        if (multimodalLiveState.value.isLiveActive) {
            multimodalLiveService.refreshExportConfig(_geminiApiKeyState.value)
        }
        triggerProactiveVoiceAnnouncement(
            eventInstruction = "স্যার আপনার ভেতরে নতুন ফিচার/আপডেট যুক্ত করেছেন: '$title' ($specification)। ১ বাক্যে স্যারকে জানান যে আপনি নিজের মধ্যে এই ফিচারটি আপডেট করে নিয়েছেন।",
            fallbackSpokenBn = report
        )
    }

    fun removeSelfUpdateFeature(patchId: String) {
        com.example.service.SmartMemoryAndSelfUpdateEngine.removeFeatureUpdate(patchId)
        _liveArcherReply.value = "জি স্যার, সেলফ-আপডেট মডিউলটি সরানো হয়েছে।"
    }

    fun deleteLearnedPlaybook(playbookId: String) {
        com.example.service.SmartMemoryAndSelfUpdateEngine.deletePlaybook(playbookId)
    }

    fun toggleAutoMemoryFilter(enabled: Boolean) {
        com.example.service.SmartMemoryAndSelfUpdateEngine.setAutoMemoryFilterEnabled(enabled)
        _liveArcherReply.value = if (enabled) {
            "জি স্যার, এআই অটো-মেমোরি ফিল্টার চালু আছে — আমি নিজেই বুঝব কোনটা কাজের কথা মনে রাখতে হবে আর কোনটা বাদ দিতে হবে।"
        } else {
            "জি স্যার, অটো-মেমোরি ফিল্টার বন্ধ করা হয়েছে।"
        }
    }

    fun toggleTurboUiSpeed(enabled: Boolean) {
        com.example.service.SmartMemoryAndSelfUpdateEngine.setTurboUiSpeedEnabled(enabled)
        _liveArcherReply.value = if (enabled) {
            "জি স্যার, টার্বো ৩x ফাস্ট স্ক্রিন অ্যাকশন মোড চালু আছে — এখন স্ক্রিন দেখে কাজ কোনো দেরি ছাড়াই দ্রুত সম্পন্ন হবে।"
        } else {
            "জি স্যার, নরমাল ইউআই স্পিড মোড সেট করা হয়েছে।"
        }
    }

    fun purgeJunkFromMemoryNow() {
        viewModelScope.launch {
            seedInitialDataIfEmpty()
            val notes = historyRepo.memoryNotesFlow.first()
            var removed = 0
            notes.forEach { n ->
                if (com.example.service.SmartMemoryAndSelfUpdateEngine.isJunkMemoryNote(n.title, n.content)) {
                    historyRepo.deleteMemoryNote(n.id)
                    removed++
                }
            }
            val msg = "জি স্যার, মেমোরি সিস্টেম থেকে সব অপ্রয়োজনীয় ডেটা পরিষ্কার করা হয়েছে (কাজের মেমোরি ও কাজের ধাপগুলো সংরক্ষিত আছে)।"
            _liveArcherReply.value = msg
        }
    }

    fun sendQuickNotificationReply(targetSender: String, replyText: String) {
        val (_, status) = NotificationReplyBus.sendInlineReply(
            context = getApplication<Application>().applicationContext,
            targetContactOrApp = targetSender,
            replyText = replyText
        )
        _liveArcherReply.value = status
        voiceBridge.speak(status)
    }

    fun readNotificationsAloud(appFilter: String = "") {
        val summary = NotificationReplyBus.readNotificationsSummary(appFilter)
        _liveArcherReply.value = summary
        voiceBridge.speak(summary)
    }

    fun playCuratedTrack(track: CuratedTrack) {
        deviceExecutor.openExternalMusicSearch(track.title, track.artist)
    }

    fun playMoodImmediately(mood: MusicMood) {
        deviceExecutor.openExternalMusicSearch(mood.titleEn, "")
    }

    fun openExternalMusicApp(query: String, artist: String) {
        deviceExecutor.openExternalMusicSearch(query, artist)
    }

    fun deleteHistoryItem(id: Int) {
        viewModelScope.launch {
            historyRepo.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepo.clearHistory()
        }
    }

    override fun onCleared() {
        shutdownAgentCompletelyOnTaskRemoved()
        super.onCleared()
        voiceBridge.release()
        multimodalLiveService.release()
    }

    companion object {
        val PRESET_ROUTINES = listOf(
            SmartRoutine(
                id = "routine_playstore_install",
                titleBn = "প্লে স্টোর অটো অ্যাপ ইনস্টলার",
                subtitleBn = "প্লে স্টোর খুলে যেকোনো অ্যাপ সার্চ করে নিজে নিজে Install বাটনে ক্লিক করবে",
                iconName = "PLAYSTORE",
                accentHex = 0xFF00FF66,
                promptCommand = "প্লে স্টোর থেকে Telegram অ্যাপটা ডাউনলোড করো",
                presetSteps = listOf(
                    AgentStep(
                        id = "ps_install_1",
                        actionType = AgentActionType.INSTALL_APP_PLAYSTORE,
                        title = "প্লে স্টোর থেকে অ্যাপ ইনস্টল",
                        description = "Google Play Store ওপেন করে অটোমেটিক Install ক্লিক করা হচ্ছে",
                        query = "Telegram",
                        appName = "Telegram"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_telegram_download",
                titleBn = "টেলিগ্রাম চ্যানেল ফাইল সার্চ ও ডাউনলোড",
                subtitleBn = "টেলিগ্রামে গিয়ে ফাইল বা মুভি সার্চ করে আসল ফাইলটি ডাউনলোড করবে",
                iconName = "TELEGRAM",
                accentHex = 0xFF00E5FF,
                promptCommand = "টেলিগ্রামে গিয়ে নতুন AI PDF ও ফাইলটা সার্চ করে ডাউনলোড দাও",
                presetSteps = listOf(
                    AgentStep(
                        id = "tg_dl_1",
                        actionType = AgentActionType.TELEGRAM_AUTOMATION,
                        title = "টেলিগ্রাম ফাইল সার্চ ও ডাউনলোড",
                        description = "টেলিগ্রাম চ্যানেলে প্রবেশ করে ফাইলটি খুঁজে ডাউনলোড শুরু করা হচ্ছে",
                        recipient = "@telegram",
                        targetUrlOrFile = "AI Toolkit PDF",
                        query = "AI Toolkit PDF"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_whatsapp_reply",
                titleBn = "হোয়াটসঅ্যাপ মেসেজ চেক ও অটো-রিপ্লাই",
                subtitleBn = "হোয়াটসঅ্যাপ মেসেজ চেক করে স্যারের নির্দেশ অনুযায়ী সরাসরি রিপ্লাই পাঠাবে",
                iconName = "WHATSAPP",
                accentHex = 0xFF00E676,
                promptCommand = "রাহিম ভাইকে হোয়াটসঅ্যাপে রিপ্লাই দাও যে আমি মিটিংয়ে আছি",
                presetSteps = listOf(
                    AgentStep(
                        id = "wa_rep_1",
                        actionType = AgentActionType.WHATSAPP_AUTOMATION,
                        title = "হোয়াটসঅ্যাপ অটো-রিপ্লাই",
                        description = "রাহিম ভাইয়ের মেসেজে স্বয়ংক্রিয়ভাবে উত্তর পাঠানো হচ্ছে",
                        recipient = "রাহিম ভাই",
                        body = "ভাই, আমি এখন মিটিংয়ে আছি, কিছুক্ষণের মধ্যে কথা বলছি।"
                    )
                )
            ),
            SmartRoutine(
                id = "routine_sad_song",
                titleBn = "মন খারাপ ও স্যাড সং মুড",
                subtitleBn = "ভলিউম ৬৫% করে আবেগী স্যাড পিয়ানো ও অরিজিৎ সিং এর গান চালু করবে",
                iconName = "MUSIC_SAD",
                accentHex = 0xFF8C62FF,
                promptCommand = "ভলিউম ৬৫% করে একটা সুন্দর স্যাড সং চালাও",
                presetSteps = listOf(
                    AgentStep(
                        id = "s_vol_65",
                        actionType = AgentActionType.SET_VOLUME,
                        title = "মিডিয়া ভলিউম ৬৫% সেট",
                        description = "শান্তভাবে গান শোনার জন্য ভলিউম সামঞ্জস্য করা হচ্ছে",
                        volumeStream = "MUSIC",
                        volumePercent = 65
                    ),
                    AgentStep(
                        id = "s_play_sad",
                        actionType = AgentActionType.PLAY_MUSIC,
                        title = "আবেগী স্যাড সং প্লেয়ার চালু",
                        description = "অরিজিৎ সিং ও অর্ণব ভাইবসের মেলানকোলিক ট্র্যাক চালু করা হচ্ছে",
                        query = "Arijit Singh Arnob best sad song",
                        mood = "SAD",
                        artist = "Arijit Singh & Arnob"
                    )
                )
            )
        )

        private fun defaultWelcomePlan(): List<AgentPlanResponse> {
            return listOf(
                AgentPlanResponse(
                    id = "welcome_plan",
                    userPrompt = "",
                    spokenReply = "জি স্যার, আমি প্রস্তুত। যেকোনো প্রশ্ন বা আদেশ বলুন।",
                    summaryTitle = "ARCHER AI — মোবাইল জার্ভিস অনলাইন",
                    steps = emptyList(),
                    triggeredByWakeWord = false
                )
            )
        }
    }
}
