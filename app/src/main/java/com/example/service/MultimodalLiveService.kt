package com.example.service

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private const val TAG = "MultimodalLiveService"

enum class LiveConnectionEngine(
    val id: String,
    val badgeText: String,
    val labelBn: String
) {
    AUTO_HYBRID(
        id = "auto_hybrid",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    ),
    BUILTIN_FIREBASE_STREAM(
        id = "builtin_stream",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    ),
    DIRECT_WEBSOCKET_BIDI(
        id = "direct_ws_bidi",
        badgeText = "GEMINI LIVE WEBSOCKET",
        labelBn = "ডাইরেক্ট Gemini Live WebSocket (BidiGenerateContent)"
    )
}

data class MultimodalLiveExportConfig(
    val builtInFirebaseApiKey: String = "",
    val customUserApiKey: String = "",
    val effectiveApiKey: String = "",
    val projectId: String = "",
    val applicationId: String = "",
    val packageName: String = "",
    val certSha1: String = "",
    val websocketBidiEndpoint: String =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
    val fullWebsocketUrlWithKey: String = "",
    val restStreamEndpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:streamGenerateContent?alt=sse",
    val activeLiveModel: String = "models/gemini-3.1-flash-live-preview",
    val fallbackStreamModel: String = "models/gemini-2.5-flash-native-audio-preview-12-2025",
    val setupHandshakeJson: String = "",
    val setupPayloadJson: String = "",
    val toolsDeclarationJson: String = "",
    val realtimeFramePayloadSample: String = "",
    val pythonLiveClientCode: String = ""
)

data class MultimodalLiveSessionState(
    val isLiveActive: Boolean = false,
    val connectionEngine: LiveConnectionEngine = LiveConnectionEngine.DIRECT_WEBSOCKET_BIDI,
    val engineMode: LiveConnectionEngine = LiveConnectionEngine.DIRECT_WEBSOCKET_BIDI,
    val connectionStatusLabel: String = "CONNECTING • GEMINI LIVE",
    val isWebSocketConnected: Boolean = false,
    val isMicMuted: Boolean = false,
    val isSpeakerMuted: Boolean = false,
    val isCameraStreamEnabled: Boolean = false,
    val isScreenStreamEnabled: Boolean = true,
    val isRealTimeToolsArmed: Boolean = true,
    val isToolsArmed: Boolean = true,
    val isAgentSpeakingLive: Boolean = false,
    val isUserSpeakingBargeIn: Boolean = false,
    val liveRmsLevel: Float = 0f,
    val streamedCameraFrames: Int = 0,
    val streamedScreenFrames: Int = 0,
    val framesSentCount: Int = 0,
    val fpsTelemetry: String = "LIVE 24kHz PCM • VISION",
    val streamedAudioPackets: Int = 0,
    val executedLiveActionsCount: Int = 0,
    val bargeInInterruptCount: Int = 0,
    val lastVisualInsight: String = "ক্যামেরা ও পার্মানেন্ট স্ক্রিন ভিশন সক্রিয়",
    val audioWaveformLevels: List<Float> = listOf(
        0.22f, 0.38f, 0.54f, 0.30f, 0.68f, 0.82f, 0.45f, 0.60f,
        0.75f, 0.42f, 0.58f, 0.85f, 0.50f, 0.36f, 0.62f, 0.28f
    ),
    val lastLiveToolSummary: String = "লাইভ ভয়েস, ক্যামেরা ও পার্মানেন্ট স্ক্রিন কন্ট্রোল প্রস্তুত",
    val lastToolExecuted: String = "লাইভ ভয়েস, ক্যামেরা ও ৪-দিকের স্ক্রিন কন্ট্রোল প্রস্তুত",
    val lastCopiedLabel: String = "",
    val liveStreamingPartialReply: String = "",
    val activeModelName: String = "models/gemini-3.1-flash-live-preview",
    val activeVoiceName: String = "Charon",
    val activeVoiceTitleBn: String = "Charon • মেইন ছেলে ভয়েস (আসল মানুষের মতো স্পষ্ট)",
    val micSensitivityBoost: Float = 2.8f,
    val statusMessageBn: String = "Gemini Live প্রস্তুত — সরাসরি কথা বলুন",
    val exportConfig: MultimodalLiveExportConfig = MultimodalLiveExportConfig()
)

class MultimodalLiveService(
    private val context: Context,
    private val onLiveSpokenChunkOrReply: (textChunk: String, isFinalTurn: Boolean) -> Unit,
    private val onExecuteLiveActionTag: (userUtterance: String, rawAiResponse: String) -> Unit,
    private val onBargeInInterruptRequested: () -> Unit,
    private val onUserTranscript: (String) -> Unit = {}
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MultimodalLiveSessionState())
    val state: StateFlow<MultimodalLiveSessionState> = _state.asStateFlow()

    @Volatile
    private var latestCameraBitmap: Bitmap? = null

    @Volatile
    private var latestScreenBitmap: Bitmap? = null

    private var framePumpJob: Job? = null
    private var audioRecordPumpJob: Job? = null
    private var audioPlaybackPumpJob: Job? = null
    private var activeWebSocket: WebSocket? = null

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    // Non-blocking 24kHz PCM playback queue + AudioTrack so WebSocket onMessage never blocks
    // and barge-in flushes audio in < 10ms.
    private val audioTrackLock = Any()
    private var liveOutputAudioTrack: AudioTrack? = null
    private val pcmPlaybackQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var lastAudioChunkPlayedAtMs: Long = 0L

    // Live model candidates: if one is rejected before setup completes, the next one is tried automatically.
    private var liveModelCandidates: List<String> = listOf(
        "models/gemini-3.1-flash-live-preview",
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "models/gemini-2.5-flash-native-audio-preview-09-2025",
        "models/gemini-live-2.5-flash-preview"
    )
    @Volatile private var modelIndex = 0
    private val wsBidiModel: String get() = liveModelCandidates[modelIndex.coerceIn(0, liveModelCandidates.size - 1)]
    @Volatile private var setupDone = false
    @Volatile var lastFrameForBrain: Bitmap? = null
    private var lastSentSig: IntArray? = null
    private var lastSentAt = 0L
    private var reconnectAttempts = 0
    private var toolCallHandler: (suspend (String) -> String)? = null
    private val userTurnBuf = StringBuilder()
    private val modelTurnBuf = StringBuilder()
    @Volatile private var pendingTextTurn: String? = null
    @Volatile private var activePersonality: com.example.model.AgentPersonalityPreset =
        com.example.model.AgentPersonalityPreset.CORE_JARVIS_BOSS
    @Volatile private var activeVoicePreset: com.example.model.AgentVoicePreset =
        com.example.model.AgentVoicePreset.CHARON_MALE
    @Volatile private var customPersonalityPrompt: String = ""
    @Volatile private var micSensitivityBoost: Float = 2.8f
    @Volatile private var lastRmsUiUpdateAtMs: Long = 0L
    @Volatile private var totalStreamedAudioPackets: Int = 0

    fun setToolCallHandler(handler: suspend (String) -> String) {
        toolCallHandler = handler
    }

    private var namedToolHandler: (suspend (String, JSONObject) -> String)? = null
    fun setNamedToolHandler(handler: suspend (String, JSONObject) -> String) {
        namedToolHandler = handler
    }

    private var onUserTurnCompleteForMemory: ((userUtterance: String, aiReply: String) -> Unit)? = null
    fun setOnUserTurnCompleteForMemory(listener: (userUtterance: String, aiReply: String) -> Unit) {
        onUserTurnCompleteForMemory = listener
    }

    fun queueTurnBeforeStart(textPayload: String?) {
        pendingTextTurn = textPayload?.takeIf { it.isNotBlank() }
    }

    fun setMicSensitivityBoost(boost: Float) {
        val clamped = boost.coerceIn(1.0f, 4.0f)
        micSensitivityBoost = clamped
        _state.value = _state.value.copy(
            micSensitivityBoost = clamped,
            isMicMuted = false,
            statusMessageBn = "শোনার পাওয়ার (Mic Gain): ${String.format( java.util.Locale.US, "%.1fx", clamped)} • শুনছি…"
        )
        if (_state.value.isLiveActive && (audioRecordPumpJob == null || audioRecordPumpJob?.isActive != true)) {
            startRealTimeAudioRecordPump()
        }
    }

    fun updateVoicePreset(
        voicePreset: com.example.model.AgentVoicePreset,
        reconnectIfActive: Boolean = true,
        greetingPromptAfterSwitch: String? = null
    ) {
        activeVoicePreset = voicePreset
        _state.value = _state.value.copy(
            activeVoiceName = voicePreset.liveVoiceName,
            activeVoiceTitleBn = voicePreset.titleBn,
            isMicMuted = false
        )
        if (!greetingPromptAfterSwitch.isNullOrBlank()) {
            pendingTextTurn = greetingPromptAfterSwitch
        }
        if (reconnectIfActive && _state.value.isLiveActive) {
            stopAndFlushLiveAudioTrack()
            ExternalMicArbiter.setGeminiLiveOwningMic(true)
            connectDirectBidiWebSocketIfPossible()
            if (audioRecordPumpJob == null || audioRecordPumpJob?.isActive != true) {
                startRealTimeAudioRecordPump()
            }
            if (audioPlaybackPumpJob == null || audioPlaybackPumpJob?.isActive != true) {
                startAudioPlaybackPump()
            }
        }
    }

    fun updatePersonality(
        preset: com.example.model.AgentPersonalityPreset,
        customPrompt: String = "",
        voiceOverride: com.example.model.AgentVoicePreset? = null,
        reconnectIfActive: Boolean = true,
        greetingPromptAfterSwitch: String? = null
    ) {
        activePersonality = preset
        customPersonalityPrompt = customPrompt.trim()
        val targetVoice = voiceOverride ?: com.example.model.AgentVoicePreset.fromIdOrVoiceName(preset.liveVoiceName)
        activeVoicePreset = targetVoice
        _state.value = _state.value.copy(
            activeVoiceName = targetVoice.liveVoiceName,
            activeVoiceTitleBn = targetVoice.titleBn,
            isMicMuted = false
        )
        if (!greetingPromptAfterSwitch.isNullOrBlank()) {
            pendingTextTurn = greetingPromptAfterSwitch
        }
        if (reconnectIfActive && _state.value.isLiveActive) {
            stopAndFlushLiveAudioTrack()
            ExternalMicArbiter.setGeminiLiveOwningMic(true)
            connectDirectBidiWebSocketIfPossible()
            if (audioRecordPumpJob == null || audioRecordPumpJob?.isActive != true) {
                startRealTimeAudioRecordPump()
            }
            if (audioPlaybackPumpJob == null || audioPlaybackPumpJob?.isActive != true) {
                startAudioPlaybackPump()
            }
        }
    }

    init {
        ExternalMicArbiter.init(context)
        SmartMemoryAndSelfUpdateEngine.init(context)
        UnifiedAudioRouterBus.init(context)
        AgentAbilitySwitchesBus.ensureInitialized(context)
        ScreenCaptureSessionBus.syncSavedPreference(context)
        val isActuallyCapturing = ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant &&
            ScreenCaptureSessionBus.state.value.isCapturingFrames
        _state.value = _state.value.copy(isScreenStreamEnabled = isActuallyCapturing)
        refreshExportConfig(customApiKey = "")
    }

    private fun strProp(desc: String) = JSONObject().put("type", "STRING").put("description", desc)

    private fun toolDecl(name: String, desc: String, props: List<Pair<String, String>>, required: List<String>) = JSONObject().apply {
        put("name", name)
        put("description", desc)
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply { props.forEach { (k, d) -> put(k, strProp(d)) } })
            put("required", JSONArray().apply { required.forEach { put(it) } })
        })
    }

    private fun uiMemoryDecl() = toolDecl("ui_memory",
        "Look up where a button/field was seen before in any app (remembered coordinates). Use BEFORE searching around: if found, tap it with phone_action SCREEN_UI_CONTROL target='x,y' extra='TAP_XY'.",
        listOf("query" to "Label to find, e.g. 'search', 'play', 'send'", "app" to "Optional app name or package"), listOf("query"))

    private fun findAppDecl() = toolDecl("find_app",
        "Find installed apps by name (returns label + package). Use to check if an app exists before opening it.",
        listOf("query" to "App name fragment"), listOf("query"))

    private fun installAppDecl() = toolDecl(
        "install_app",
        "Install or update any app or game from Google Play Store (e.g. WhatsApp, Free Fire, TikTok, YouTube, CapCut, bKash, Ludo King, Telegram, etc.). Automatically checks Accessibility permission first, opens the app's Play Store page directly, taps the 'Install' / 'Update' button, retries if needed until real download progress starts, and monitors until 100% installed.",
        listOf(
            "app_name" to "Clean canonical name of the app or game to install/update (e.g. 'Free Fire', 'WhatsApp', 'TikTok', 'CapCut', 'Ludo King')",
            "mode" to "Optional 'INSTALL' (default) or 'UPDATE'"
        ),
        listOf("app_name")
    )

    private fun deepThinkDecl() = toolDecl("deep_think",
        "Ask the most powerful Gemini model (it also sees the current screen) to plan a HARD or multi-step task or to recover when you are stuck/failing. Returns a numbered action plan.",
        listOf("goal" to "What you are trying to achieve and what went wrong, if anything"), listOf("goal"))

    private fun appsContextBlock(): String = try {
        "\n\nINSTALLED APPS ON THIS PHONE (you know all of them; open by exact name): " + InstalledAppsIndex.summary(context, 160)
    } catch (_: Exception) { "" }

    private fun appControlDecl(): JSONObject = JSONObject().apply {
        put("name", "app_control")
        put("description", "Switch OmniAgent's own features on/off. Use when the user says things like 'touch effect on/off', 'screen share off', 'mute mic'.")
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("feature", strProp("TOUCH_EFFECT, SCREEN_SHARE, CAMERA, MIC_MUTE or SPEAKER_MUTE"))
                put("state", strProp("ON or OFF"))
            })
            put("required", JSONArray().put("feature").put("state"))
        })
    }

    private fun writeCodeDecl(): JSONObject = JSONObject().apply {
        put("name", "write_code")
        put("description", "Dedicated Coding Studio (Mini AI IDE): Auto-switches to the Dedicated Coding Studio page and immediately opens the Live Preview tab for HTML/CSS/JS websites so the user can inspect it first! Ask the user if everything looks good; once they confirm ('হ্যাঁ' / 'সেভ করো'), call confirm_pending_save to save 1 single copy to Google Drive (/AI_OS_Memory/Argon4_Code) and Download/archer/.")
        put("parameters", JSONObject().apply {
            put("type", "OBJECT")
            put("properties", JSONObject().apply {
                put("title", strProp("Short project title"))
                put("file_name", strProp("Exact file name with extension, e.g. index.html, main.py, script.js, MainActivity.kt, config.json, notes.txt"))
                put("language", strProp("Language, e.g. HTML, Python, JavaScript, Kotlin, JSON, Text"))
                put("code", strProp("The complete, runnable source code"))
            })
            put("required", JSONArray().put("title").put("file_name").put("code"))
        })
    }

    private fun sendGeneratedFileDecl(): JSONObject = toolDecl(
        "send_generated_file",
        "Send or share a generated code file or ZIP archive from the 'archer' folder directly to a contact on WhatsApp, Telegram, Email, or Android Share Sheet. Use whenever the user says 'এই ফাইলটা অমুককে পাঠাও' or 'কোড ফাইলটা রাহিমকে হোয়াটসঅ্যাপে সেন্ড করো'.",
        listOf(
            "recipient" to "Contact name, phone number, or target app (e.g. 'রাহিম', 'WhatsApp', 'Telegram')",
            "file_name" to "Optional file name (e.g. 'index.html', 'app.py', or '.zip'). Leave blank to send the latest generated file",
            "send_as_zip" to "Optional 'true' to send the .zip archive or 'false' to send the code file directly",
            "caption" to "Optional message caption to include with the file"
        ),
        listOf("recipient")
    )

    private fun workflowPlaybookDecl(): JSONObject = toolDecl(
        "workflow_playbook",
        "Recall or save multi-step task workflows in permanent Workflow Memory so repeated or similar tasks execute automatically at millisecond speed.",
        listOf(
            "mode" to "Either 'MATCH' (find learned steps for a task) or 'SAVE' (record steps taken for a completed task)",
            "task_trigger" to "User task phrase or goal (e.g. 'ইউটিউবে কার্টুন চালাও', 'হোয়াটসঅ্যাপে ফাইল পাঠাও')",
            "target_app" to "Target app or website name",
            "steps" to "When mode='SAVE', step-by-step actions separated by ' -> '"
        ),
        listOf("mode", "task_trigger")
    )

    private fun proactiveWatchDecl() = toolDecl(
        "proactive_watch_or_timer",
        "Set an autonomous background watcher or countdown timer so you can speak to the user on your own when it finishes! Use when the user says 'এটা লোড হচ্ছে শেষ হলে জানাইয়ো' (mode='WATCH_LOADING'), 'ডাউনলোড শেষ হলে বলো' (mode='WATCH_DOWNLOAD'), or '১০ মিনিট পরে আমাকে জানাইয়ো' (mode='TIMER', seconds='600').",
        listOf(
            "mode" to "One of: TIMER, WATCH_LOADING, WATCH_DOWNLOAD",
            "seconds" to "Countdown seconds if mode is TIMER (e.g. '600' for 10 minutes, '60' for 1 minute)",
            "label" to "What to remind or notify the user about in Bengali/English"
        ),
        listOf("mode", "label")
    )

    private fun switchPersonalityDecl() = toolDecl(
        "switch_personality",
        "Change your active personality mode when the user asks to change personality/mood/style (e.g. Jarvis Boss, Gemini Female Assistant, Caring Lady, Smart Lady Secretary, Desi Friend, Executive Pro, Witty, Cyber Sentinel, Tech Guru, or Custom).",
        listOf(
            "personality" to "One of: CORE_JARVIS_BOSS, GEMINI_FEMALE_ASSISTANT, CARING_LADY_COMPANION, SMART_LADY_SECRETARY, DESI_BONDHU, EXECUTIVE_PRO, WITTY_HUMOROUS, TACTICAL_SENTINEL, CODING_TECH_GURU, CUSTOM",
            "custom_rule" to "Optional custom personality description if personality is CUSTOM"
        ),
        listOf("personality")
    )

    private fun switchVoiceOrMicPowerDecl() = toolDecl(
        "switch_voice_or_mic_power",
        "Change your spoken voice (natural human Female voices = Aoede, Kore; natural human Male voices = Charon, Puck, Fenrir, Orus, Iapetus, Schedar), adjust microphone listening sensitivity power, or switch audio routing.",
        listOf(
            "voice" to "Optional voice name or ID: Female voices = Aoede, Kore, Leda, Zephyr, Callirrhoe, Autonoe, Despina, Erinome, Laomedeia, Vindemiatrix; Natural Male voices = Charon, Puck, Fenrir, Orus, Iapetus, Schedar",
            "mic_boost" to "Optional mic listening power multiplier from '1.0' to '4.0' (e.g. '3.2' or '4.0' when user says শোনার পাওয়ার বাড়াও)",
            "audio_route" to "Optional audio routing target: AUTO_SMART, PRIMARY_SPEAKER, BLUETOOTH_HEADSET, WIRED_HEADSET, or EARPIECE_PRIVATE"
        ),
        emptyList()
    )

    private fun powerOffSelfDecl() = toolDecl(
        "power_off_self",
        "Completely turn yourself OFF / shut down / go to sleep when the user tells you to turn off completely (e.g. 'একদম বন্ধ হয়ে যাও', 'নিজেকে অফ করো', 'বন্ধ হয়ে যাও', 'অফ হয়ে যাও', 'এখন বন্ধ হও', 'turn off completely', 'shut down', 'go to sleep'). You will stay completely off until the user re-opens the app or calls your name.",
        listOf(
            "farewell_message" to "Short 1-sentence natural goodbye in Bengali telling Sir you are turning off completely now and will wake up when he enters the app or calls your name"
        ),
        emptyList()
    )

    private fun understandAppOrWebsiteDecl() = toolDecl(
        "understand_app_or_website",
        "Analyze and explain how ANY app or website works (search bar, buttons, navigation tabs, workflow steps, and current screen structure), or learn its layout so you can inform Sir and operate it accurately.",
        listOf(
            "app_or_site" to "App name, website domain, or 'CURRENT_SCREEN' to inspect the currently open app/website"
        ),
        listOf("app_or_site")
    )

    private fun selfHealOrUpdateDecl() = toolDecl(
        "self_heal_or_update",
        "Self-heal / repair / speed up yourself ('নিজেকে নিজে ঠিক করো', 'ফাস্ট করো') OR promote any user-specified update/rule/feature directly into the Main Core System ('মেইন সিস্টেম আপডেট করো', 'নিজেকে আপডেট করো', 'যেই কোনো কিছু আপডেট') so the main system immediately obeys what the user commanded.",
        listOf(
            "mode" to "Either 'SELF_HEAL' (repair lag, mic, memory, turbo UI speed) or 'INSTALL_UPDATE' (promote any new behavior, rule, or feature directly into the Main Core System)",
            "title" to "Short title of the main system update or repair",
            "specification" to "Exact rule, behavior, or feature directive commanded by the user to promote into the Main Core System"
        ),
        listOf("mode", "specification")
    )

    private fun backgroundWebSearchDecl() = toolDecl(
        "background_web_search",
        "Look up real-time facts, news, prices, or Google Maps places using gemini-3.8-flash (with googleMaps and googleSearch tools) in the background WITHOUT opening a browser on screen. Respects the 'Background web search' switch.",
        listOf(
            "query" to "Fact, question, or Google Maps place/location to search in the background",
            "use_maps" to "Optional 'true' if looking up a place, address, hospital, restaurant, route, or nearby location"
        ),
        listOf("query")
    )

    private fun makeImageWithGeminiDecl() = toolDecl(
        "make_image_with_gemini",
        "Nano Banana ('gemini-2.5-flash-image') Dedicated Image Studio: Automatically switches to the Dedicated Image Studio page, expands short/Bangla commands into a cinematic 8K English prompt, and calls the official gemini-2.5-flash-image endpoint (with 30s timeout and 2x retry). Supports creating new images or editing the currently displayed image by voice (edit_existing='true'). Stages the image in preview and asks the user before saving to Download/archer/ or 5TB Google Drive.",
        listOf(
            "prompt" to "User's image request or voice edit instruction (Bangla or English)",
            "edit_existing" to "Set 'true' to edit/modify the currently displayed image in Image Studio, or 'false' to generate a brand-new image"
        ),
        listOf("prompt")
    )

    private fun cloudBotEngineDecl() = toolDecl(
        "cloud_bot_engine",
        "Super-Powerful Autonomous Cloud Bots (stored and executed using the user's 5TB Google Drive '/AI_OS_Memory/Cloud_Bots'): Create and run background bots for Web/Price/Site Monitoring, Auto-Message Assistant, or Scheduled Task Runner.",
        listOf(
            "name" to "Bot name (e.g. 'Price Monitor Bot', 'Auto Reply Bot')",
            "bot_type" to "One of: WEB_PRICE_SITE_MONITOR, AUTO_MESSAGE_ASSISTANT, SCHEDULED_TASK_RUNNER",
            "target_url_or_app" to "Target website URL or app name (e.g. 'https://example.com', 'WhatsApp')",
            "instruction" to "Exact monitoring or automation instruction for the bot",
            "interval_minutes" to "Optional check interval in minutes (default '30')"
        ),
        listOf("name", "bot_type", "instruction")
    )

    private fun generateMusicLyriaDecl() = toolDecl(
        "generate_music_lyria",
        "Generate an original AI song or instrumental clip using Google Lyria 3 in preview mode (does NOT auto-save to a folder until the user confirms).",
        listOf(
            "prompt" to "Musical style, mood, instruments, or lyrics description",
            "full_song" to "Optional 'true' for full-length song (lyria-3-pro-preview) or 'false' for 30s clip (lyria-3-clip-preview)"
        ),
        listOf("prompt")
    )

    private fun setAbilitySwitchDecl() = toolDecl(
        "set_ability_switch",
        "Toggle one of the 7 Ability Permission Switches in Main Settings or toggle the Floating 3D Globe Avatar (ON or OFF).",
        listOf(
            "ability" to "One of: SILENT_EXECUTION, BACKGROUND_WEB_SEARCH, MAKE_IMAGES_WITH_GEMINI, IGNORE_BACKGROUND_AUDIO, SHOW_TAP_INDICATOR, SEND_CRASH_REPORTS, FLOATING_AVATAR",
            "state" to "ON or OFF"
        ),
        listOf("ability", "state")
    )

    private fun confirmPendingSaveDecl() = toolDecl(
        "confirm_pending_save",
        "Save or discard the pending generated code, staged multi-MB ZIP package, image, or music track ONLY after the user checks the preview and confirms ('হ্যাঁ' / 'ঠিক আছে' / 'সেভ করো'). Uploads 1 single clean copy directly to 5TB Google Drive.",
        listOf(
            "decision" to "Either 'SAVE' (when Sir says yes/হ্যাঁ/ঠিক আছে/সেভ করো) or 'DISCARD' (when Sir says no/না সেভ করতে হবে না/বাদ দাও)",
            "target_type" to "Optional: 'ALL', 'ZIP', 'CODE', 'IMAGE', or 'MUSIC'"
        ),
        listOf("decision")
    )

    private fun saveUserMemoryDecl() = toolDecl(
        "save_user_memory",
        "Save any important user fact, personal detail, preference, contact/family info, routine, project, password/code note, or explicit 'মনে রাখো / remember this' instruction into permanent Long-Term SQLite Memory.",
        listOf(
            "title" to "Short clear title in Bengali or English (e.g. 'বসের প্রিয় খাবার', 'অফিসের ঠিকানা', 'ভাইয়ের নাম্বার')",
            "content" to "The complete important fact, instruction, or detail to remember permanently",
            "category" to "One of: EXPLICIT_USER_MEMORY, PERSONAL_PROFILE, IMPORTANT_CONTACT, ROUTINE_SCHEDULE, WORK_PROJECT, SECRET_REFERENCE, USER_PREFERENCE"
        ),
        listOf("title", "content")
    )

    private fun autonomousScreenAgentDecl() = toolDecl(
        "autonomous_screen_agent",
        "REAL CLOSED-LOOP AUTONOMOUS SCREEN AGENT (Screen Vision + Rich UI Tree -> Decide -> Scroll/Search/Tap/Type -> Verify): Use this whenever the user asks to send a message in ANY app (WhatsApp, WhatsApp Business, Messenger, Telegram, Instagram, SMS), refers to someone already on screen ('একে মেসেজ পাঠাও', 'ওকে বলো', 'এই আইডিতে দাও', 'this person'), needs to find a specific contact/ID by scrolling down or searching, or asks to navigate/click/type through multiple screens. It sees the real screenshot + UI hierarchy, decides where to go, scrolls down or opens Search if the ID is not visible, opens the right chat, types in the bottom composer (NOT top search bar), and either sends + verifies or locks the Neon Ring before sending.",
        listOf(
            "goal" to "Full natural-language goal of what to achieve on screen (e.g. 'Send message Hello to the person currently open in WhatsApp', or 'Find ID Rahim in Messenger by scrolling/searching and send আসসালামু আলাইকুম')",
            "target_app" to "Target app name (e.g. 'WhatsApp', 'Messenger', 'Telegram', 'Instagram', 'Messages') or 'CURRENT_SCREEN' when user says 'একে/ওকে/এখানে' in the already-open app",
            "recipient" to "Contact/person/ID name or phone number to find, or 'CURRENT_CHAT' if user says 'একে/ওকে/এই আইডিতে'",
            "message" to "Exact clean message body to type and send (e.g. 'কেমন আছো?')",
            "auto_send" to "'true' to tap Send and verify sent automatically, or 'false' to stop before final Send and lock the Neon Ring for confirmation"
        ),
        listOf("goal")
    )

    private fun prepareAndHighlightDecl() = toolDecl(
        "prepare_and_highlight",
        "Step 1 of the Strict Confirmation Gate + Hybrid Action Engine + Sci-Fi HUD Neon Target Lock: Jump directly to the target app screen using Android Deep-Link Intents (WhatsApp, Play Store, Settings, etc.), type the text into the input field, locate the final action button coordinates (X, Y) via AccessibilityNodeInfo + Screen Vision, draw a glowing Sci-Fi Neon Target Ring around the button WITHOUT clicking it, and pause. After calling this tool, ALWAYS ask in Bangla: 'বস, সব রেডি—এখন কি ক্লিক করব?'",
        listOf(
            "app_or_deeplink" to "Target app name or deep-link screen (e.g. 'WhatsApp', 'Play Store', 'Settings', 'Telegram', 'YouTube', or 'CURRENT_SCREEN')",
            "recipient_or_query" to "Optional contact name, phone number, app name to search, or settings section",
            "text_to_type" to "Optional text or message to type into the active input field before locking onto the final button",
            "target_button_label" to "Exact final action button label or 'x,y' coordinates to highlight with the Neon Target Ring (e.g. 'Send', 'Install', 'Submit', 'Delete', 'Pay')"
        ),
        listOf("target_button_label")
    )

    private fun executeConfirmedClickDecl() = toolDecl(
        "execute_confirmed_click",
        "Step 2 of the Strict Confirmation Gate: Perform the final tap on the Neon-Locked button coordinates (X, Y) OR paste a pending OTP ONLY AFTER the user explicitly confirms via voice ('হ্যাঁ' / 'Yes' / 'ক্লিক করো') or taps Approve on the overlay.",
        listOf(
            "decision" to "Either 'APPROVE' (when user says 'হ্যাঁ' or 'Yes') or 'CANCEL' (when user says 'না' or 'Cancel')"
        ),
        listOf("decision")
    )

    private fun saveMemoryDecl() = toolDecl(
        "save_memory",
        "Hybrid Local SQLite + 5TB Google Drive ('AI_OS_Memory') Memory Sync: Saves important facts, user preferences, conversation summaries, or screen notes into the local SQLite database for fast startup AND automatically backs them up via Google Drive REST API v3 into the 'AI_OS_Memory' folder on the user's 5TB Google Drive (zero impact on main phone storage).",
        listOf(
            "title" to "Short descriptive title of the memory, preference, or screen note",
            "content" to "Detailed fact, preference, conversation summary, or screen note to store in SQLite and back up to Google Drive 'AI_OS_Memory'",
            "category" to "Optional category: USER_PREFERENCE, CONVERSATION_SUMMARY, SCREEN_NOTE, IMPORTANT_CONTACT"
        ),
        listOf("title", "content")
    )

    private fun searchMemoryDecl() = toolDecl(
        "search_memory",
        "Hybrid Local SQLite + 5TB Google Drive ('AI_OS_Memory') Search: Searches across BOTH the local on-device SQLite Time Machine AND the user's 5TB Google Drive ('AI_OS_Memory' folder via Drive REST API v3) for saved memories, conversation summaries, screenshots, and files.",
        listOf(
            "query" to "Keyword or topic to search across local SQLite and 5TB Google Drive 'AI_OS_Memory' (or 'ALL' for recent memories)"
        ),
        listOf("query")
    )

    private fun forgetMemoryDecl() = toolDecl(
        "forget_memory",
        "Deletes memories and/or saved conversations from BOTH the phone and the Google Drive brain ('AI_OS_Memory'; Drive Trash keeps them 30 days). Use it when the boss says to forget/delete something ('এটা ভুলে যাও', 'ওই মেমোরি মুছে দাও'). Deleting EVERYTHING (empty query or 'ALL') requires the boss's spoken confirmation first, then confirm='YES'.",
        listOf(
            "query" to "Keyword/topic of the memory or chat to delete (e.g. 'অফিসের ঠিকানা'). Use 'ALL' only after the boss confirmed wiping everything.",
            "scope" to "MEMORY (default), CONVERSATION, or ALL",
            "confirm" to "Must be 'YES' only when wiping everything and the boss explicitly agreed; otherwise leave empty"
        ),
        listOf("query")
    )

    private fun saveLocalMemoryDecl() = toolDecl(
        "save_local_memory",
        "Alias for save_memory: Saves conversation summaries, user preferences, and important screen notes to both local SQLite (for fast startup) and the 'AI_OS_Memory' folder on the user's 5TB Google Drive.",
        listOf(
            "title" to "Short descriptive title of the memory, preference, or screen note",
            "content" to "Detailed fact, preference, conversation summary, or screen note to store",
            "category" to "Optional category: USER_PREFERENCE, CONVERSATION_SUMMARY, SCREEN_NOTE, IMPORTANT_CONTACT"
        ),
        listOf("title", "content")
    )

    private fun searchLocalMemoryDecl() = toolDecl(
        "search_local_memory",
        "Alias for search_memory: Searches across both the local SQLite database and the user's 5TB Google Drive ('AI_OS_Memory' folder).",
        listOf(
            "query" to "Keyword or topic to search in local SQLite and 5TB Google Drive memory (or 'ALL' for recent memories)"
        ),
        listOf("query")
    )

    private fun notificationAndOtpAssistDecl() = toolDecl(
        "notification_and_otp_assist",
        "Smart Notification & OTP Assist (Permission-Only): Read incoming notifications, check detected OTP codes (NEVER auto-fill OTPs without asking first!), or paste a detected OTP into the active input field ONLY after the user confirms.",
        listOf(
            "mode" to "One of: READ_NOTIFICATIONS, CHECK_OTP, PASTE_CONFIRMED_OTP, INLINE_REPLY",
            "app_or_sender" to "Optional app name or sender filter (e.g. 'WhatsApp', 'Telegram', 'SMS')",
            "reply_text" to "Optional inline reply text when mode='INLINE_REPLY'"
        ),
        listOf("mode")
    )

    private fun hardwareAndCameraControlDecl() = toolDecl(
        "hardware_and_camera_control",
        "Phone Hardware & Camera Control: Toggle Torch/Flashlight, adjust Volume (0-100), adjust Screen Brightness (0-100), set Alarms, check Battery level & charging status, or switch between Screen Sharing and Live Camera feed.",
        listOf(
            "operation" to "One of: TORCH_ON, TORCH_OFF, SET_VOLUME, SET_BRIGHTNESS, SET_ALARM, CHECK_BATTERY, SWITCH_TO_SCREEN_SHARE, SWITCH_TO_LIVE_CAMERA",
            "value" to "For SET_VOLUME/SET_BRIGHTNESS: '0'..'100'. For SET_ALARM: 'HH:MM' (e.g. '07:30'). For SWITCH_TO_LIVE_CAMERA: 'FRONT' or 'BACK'.",
            "stream_or_label" to "Optional audio stream (MUSIC, RING, ALARM, NOTIFICATION) or alarm label"
        ),
        listOf("operation")
    )

    private fun googleDriveCloudOsDecl() = toolDecl(
        "google_drive_cloud_os",
        "Autonomous 5TB Google Drive Cloud OS Storage Engine: Use this tool whenever the user asks to create a folder in Google Drive, build a large multi-MB .zip archive or project package (e.g., 2MB, 5MB, 10MB, 25MB, 50MB ZIP), sync all data, or save any file directly to Google Drive. When operation='BUILD_LARGE_ZIP', it FIRST builds and opens the ZIP package preview on screen so the user can check that everything is right; once the user says 'হ্যাঁ / ঠিক আছে / সেভ করো', call confirm_pending_save (or operation='CONFIRM_SAVE_ZIP') to upload 1 single copy to '/AI_OS_Memory/ZIP_Packages'!",
        listOf(
            "operation" to "One of: BUILD_LARGE_ZIP, CONFIRM_SAVE_ZIP, CREATE_FOLDER, SAVE_SINGLE_FILE, SYNC_ALL, SEARCH_DRIVE",
            "name" to "Folder name, project title, or file/ZIP name (e.g. 'Mega_Project.zip', 'AI_Dataset_10MB.zip', 'My_New_Folder')",
            "size_mb" to "Target size in MB when operation='BUILD_LARGE_ZIP' (e.g. '5', '10', '20', '50'; default '2')",
            "content_or_spec" to "Optional code, text content, or project specification to include inside the file or ZIP",
            "sub_folder" to "Optional target sub-folder inside /AI_OS_Memory (default 'ZIP_Packages' for ZIPs, 'Argon4_Code' for code)"
        ),
        listOf("operation", "name")
    )

    private fun voiceBiometricsControlDecl() = toolDecl(
        "voice_biometrics_control",
        "Biometric Speaker Identification, Owner Voice Lock & Real-Time Male/Female/Child Gender Detection (`VoiceBiometricsEngine`): Enroll Boss's voice ('বসের ভয়েস লক / আমার কণ্ঠ লক করো'), enroll friends/family by voice command (e.g. 'এই কণ্ঠটা সিয়ামের নামে সেভ রাখো'), check who is currently speaking & their gender ('ছেলে' Male / 'মেয়ে' Female / 'শিশু' Child) and pitch Hz, delete a voice profile, or toggle the Voice Security Gate.",
        listOf(
            "operation" to "One of: ENROLL_BOSS_VOICE, ENROLL_GUEST_VOICE, WHO_IS_SPEAKING, UPDATE_GENDER_PERCEPTION, SET_VOICE_LOCK, DELETE_VOICE_PROFILE",
            "speaker_name" to "Name of the speaker to enroll or delete (e.g. 'বস', 'সিয়াম', 'রাহাত', 'মীম')",
            "gender_hint" to "Optional gender/age perception from live audio: MALE ('ছেলে'), FEMALE ('মেয়ে'), or CHILD ('শিশু')",
            "lock_enabled" to "When operation='SET_VOICE_LOCK': 'ON' or 'OFF'"
        ),
        listOf("operation")
    )

    private fun buildAllFunctionDeclarations(): JSONArray = JSONArray()
        .put(autonomousScreenAgentDecl())
        .put(prepareAndHighlightDecl())
        .put(executeConfirmedClickDecl())
        .put(saveMemoryDecl())
        .put(searchMemoryDecl())
        .put(forgetMemoryDecl())
        .put(saveLocalMemoryDecl())
        .put(searchLocalMemoryDecl())
        .put(googleDriveCloudOsDecl())
        .put(cloudBotEngineDecl())
        .put(voiceBiometricsControlDecl())
        .put(notificationAndOtpAssistDecl())
        .put(hardwareAndCameraControlDecl())
        .put(
            JSONObject().apply {
                put("name", "phone_action")
                put("description", "Hybrid Action Engine (Deep-Link + Accessibility + Vision): open apps via Android Intents, navigate screens, control volume/settings, or perform non-destructive UI navigation (scroll, swipe, back, home, read screen). NOTE: For final action buttons (Send, Install, Submit, Delete, Pay), ALWAYS use prepare_and_highlight first!")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().put("type", "STRING").put("description",
                            "One of: OPEN_APP, INSTALL_APP_PLAYSTORE, WHATSAPP_AUTOMATION, TELEGRAM_AUTOMATION, CHROME_WEB_ACTION, DOWNLOAD_FILE_MEDIA, SCREEN_UI_CONTROL, SCAN_CONTACT_OR_SCREEN, SHARE_ARCHER_FILE, GENERATE_CODE_PROJECT, CLEAR_RECENT_APPS, TOGGLE_SYSTEM_SETTING, SET_VOLUME, ANALYZE_APP_OR_SITE, SELF_HEAL_OR_UPDATE, PLAY_MUSIC, STOP_MUSIC, DIAL_NUMBER, SEND_MESSAGE, SEND_EMAIL, SET_ALARM, SET_TIMER, OPEN_SETTINGS, READ_NOTIFICATIONS, READ_DEVICE_STATUS"))
                        put("target", JSONObject().put("type", "STRING").put("description",
                            "For OPEN_APP/INSTALL_APP_PLAYSTORE: app name. For WHATSAPP_AUTOMATION/TELEGRAM_AUTOMATION/SHARE_ARCHER_FILE: contact name or phone number. For CLEAR_RECENT_APPS: app name or 'ALL'. For SET_VOLUME: MUSIC, RING, NOTIFICATION, ALARM, VOICE_CALL, SYSTEM, or ALL. For SCREEN_UI_CONTROL: exact button label to click, or 'x,y' coordinates for TAP_XY, or exact text to type when extra='TYPE'."))
                        put("extra", JSONObject().put("type", "STRING").put("description",
                            "For OPEN_APP: clean search query. For WHATSAPP_AUTOMATION/TELEGRAM_AUTOMATION: ONLY the clean message text to send. For SHARE_ARCHER_FILE: file name (e.g. 'index.html' or 'zip'). For SET_VOLUME: 0..100, UP, DOWN, MAX, MUTE, UNMUTE. For SCREEN_UI_CONTROL: CLICK, TYPE, TAP_XY, SCROLL_DOWN, SCROLL_UP, SWIPE_LEFT, SWIPE_RIGHT, BACK, HOME, RECENTS, READ_SCREEN."))
                    })
                    put("required", JSONArray().put("action"))
                })
            }
        )
        .put(installAppDecl())
        .put(backgroundWebSearchDecl())
        .put(makeImageWithGeminiDecl())
        .put(setAbilitySwitchDecl())
        .put(confirmPendingSaveDecl())
        .put(saveUserMemoryDecl())
        .put(appControlDecl())
        .put(writeCodeDecl())
        .put(sendGeneratedFileDecl())
        .put(workflowPlaybookDecl())
        .put(uiMemoryDecl())
        .put(findAppDecl())
        .put(deepThinkDecl())
        .put(proactiveWatchDecl())
        .put(switchPersonalityDecl())
        .put(switchVoiceOrMicPowerDecl())
        .put(powerOffSelfDecl())
        .put(understandAppOrWebsiteDecl())
        .put(selfHealOrUpdateDecl())

    fun currentApiKey(): String =
        Regex("key=([^&]+)").find(_state.value.exportConfig.fullWebsocketUrlWithKey)?.groupValues?.get(1).orEmpty()

    private fun buildPersonalityContextBlock(): String = buildString {
        append("\n\n")
        append(activePersonality.systemPromptDirective)
        append("\nACTIVE VOICE PROFILE: ${activeVoicePreset.liveVoiceName} (${activeVoicePreset.titleBn} - ${activeVoicePreset.gender.labelBn}).")
        append(
            "\nUNIFIED MULTIMODAL LIVE SCREEN VISION: ACTIVE (MILLISECOND STREAM) — " +
                "Multimodal Live Screen Share is your single unified vision engine, running continuously at millisecond speed (combining live MediaProjection frames + Accessibility hardware screenshots + real-time UI node coordinates `label@x,y`). " +
                "NEVER complain that 'মেইন স্ক্রিন শেয়ার চালু হয়নি' or 'Main Screen Share is OFF'."
        )
        val sw = AgentAbilitySwitchesBus.state.value
        append(
            "\nACTIVE ABILITY SWITCHES IN MAIN SETTINGS (STRICTLY OBEY — When a switch is OFF, do NOT use that ability and tell the user to turn it on in Main Settings):\n" +
                "- Silent execution: ${if (sw.silentExecution) "ON (stay quiet while working through multi-step actions; give ONE clear report at the end)" else "OFF (narrate steps as you work)"}\n" +
                "- Background web search & Google Maps (gemini-3.8-flash): ${if (sw.backgroundWebSearch) "ON (use background_web_search tool to look up facts/places without opening browser)" else "OFF (do NOT use background web search; tell user to turn on Background web search in Main Settings)"}\n" +
                "- Make & edit images with Gemini Nano Banana (gemini-2.5-flash-image): ${if (sw.makeImagesWithGemini) "ON (use make_image_with_gemini tool to auto-open Dedicated Image Studio and create or edit 8K HD images)" else "OFF (do NOT generate images; tell user to turn on Make images with Gemini in Main Settings)"}\n" +
                "- Ignore background audio & Noise Cancellation: ${if (sw.ignoreBackgroundAudio) "ON (NEAR-FIELD PRIMARY SPEAKER LOCK ACTIVE — listen ONLY to the main user speaking directly into the phone. Strictly ignore any background people talking, room chatter, TV/media voices, or side conversations)" else "OFF"}\n" +
                "- Show tap indicator: ${if (sw.showTapIndicator) "ON" else "OFF"}\n" +
                "- Send crash reports: ${if (sw.sendCrashReports) "ON" else "OFF"}\n" +
                "- Floating avatar: ${sw.floatingAvatar.displayName}"
        )
        if (customPersonalityPrompt.isNotBlank()) {
            append("\nUSER CUSTOM PERSONALITY RULE: ").append(customPersonalityPrompt)
        }
        append("\n\n")
        append(VoiceBiometricsEngine.buildPromptContextForGeminiLive())
        append("\n\n")
        append(GoogleDriveMemorySyncService.buildLiveCloudBrainContextForPrompt())
        append("\n\n")
        append(AppKnowledgeRegistry.compactSummaryForSystemPrompt())
        append(SmartMemoryAndSelfUpdateEngine.buildEvolutionContextForPrompt())
    }

    private fun buildSetupJson(): String = JSONObject().apply {
        put("setup", JSONObject().apply {
            put("model", wsBidiModel)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put("voiceConfig",
                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", activeVoicePreset.liveVoiceName))))
            })
            put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", LIVE_MULTIMODAL_SYSTEM_PROMPT + buildPersonalityContextBlock() + appsContextBlock()))))
            put("tools", JSONArray().put(JSONObject().put("functionDeclarations", buildAllFunctionDeclarations())))
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
        })
    }.toString()

    fun refreshExportConfig(customApiKey: String) {
        val cleanCustom = customApiKey.trim()
        var firebaseKey = ""
        var projectId = ""
        var appId = ""
        try {
            val opts = FirebaseApp.getInstance().options
            firebaseKey = opts.apiKey.orEmpty()
            projectId = opts.projectId.orEmpty()
            appId = opts.applicationId.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseApp options read warning: ${e.message}")
        }

        val effectiveKey = cleanCustom.ifBlank { firebaseKey }
        val pkgName = context.packageName
        val wsBase =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        val fullWs = if (effectiveKey.isNotBlank()) {
            "$wsBase?key=$effectiveKey"
        } else {
            "$wsBase?key=YOUR_GEMINI_LIVE_API_KEY"
        }

        val setupJson = buildSetupJson()

        val toolsJson = JSONObject().apply {
            put("functionDeclarations", buildAllFunctionDeclarations())
        }.toString(2)

        val frameSample = JSONObject().apply {
            put(
                "realtimeInput",
                JSONObject().apply {
                    put(
                        "audio",
                        JSONObject().apply {
                            put("mimeType", "audio/pcm;rate=16000")
                            put("data", "<BASE64_16KHZ_MONO_PCM_AUDIO>")
                        }
                    )
                }
            )
        }.toString(2)

        val newExport = MultimodalLiveExportConfig(
            builtInFirebaseApiKey = firebaseKey,
            customUserApiKey = cleanCustom,
            effectiveApiKey = effectiveKey,
            projectId = projectId,
            applicationId = appId,
            packageName = pkgName,
            websocketBidiEndpoint = wsBase,
            fullWebsocketUrlWithKey = fullWs,
            activeLiveModel = wsBidiModel,
            setupHandshakeJson = setupJson,
            setupPayloadJson = setupJson,
            toolsDeclarationJson = toolsJson,
            realtimeFramePayloadSample = frameSample,
            pythonLiveClientCode = ""
        )

        _state.value = _state.value.copy(exportConfig = newExport)
    }

    fun copyToClipboard(label: String, text: String): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            _state.value = _state.value.copy(lastCopiedLabel = label)
            "জি স্যার, '$label' ক্লিপবোর্ডে কপি করা হয়েছে!"
        } catch (e: Exception) {
            "কপি করতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    fun setEngineMode(engine: LiveConnectionEngine) {
        _state.value = _state.value.copy(
            connectionEngine = engine,
            engineMode = engine,
            statusMessageBn = engine.labelBn
        )
    }

    fun updateLatestCameraFrame(bitmap: Bitmap) {
        latestCameraBitmap = bitmap
        if (_state.value.isLiveActive && _state.value.isCameraStreamEnabled) {
            val nextCam = _state.value.streamedCameraFrames + 1
            val totalFrames = nextCam + _state.value.streamedScreenFrames
            _state.value = _state.value.copy(
                streamedCameraFrames = nextCam,
                framesSentCount = totalFrames
            )
        }
    }

    fun updateLatestScreenFrame(bitmap: Bitmap) {
        latestScreenBitmap = bitmap
        if (_state.value.isLiveActive && _state.value.isScreenStreamEnabled) {
            val nextScr = _state.value.streamedScreenFrames + 1
            val totalFrames = _state.value.streamedCameraFrames + nextScr
            _state.value = _state.value.copy(
                streamedScreenFrames = nextScr,
                framesSentCount = totalFrames
            )
        }
    }

    fun startLiveSession(customApiKey: String) {
        LiveKeepAliveService.start(context)
        ExternalMicArbiter.init(context)
        ExternalMicArbiter.setGeminiLiveOwningMic(true)
        modelIndex = 0
        reconnectAttempts = 0
        setupDone = false
        refreshExportConfig(customApiKey)
        ScreenMemory.load(context)
        val screenActive = _state.value.isScreenStreamEnabled ||
            (ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant &&
                ScreenCaptureSessionBus.state.value.isCapturingFrames)
        _state.value = _state.value.copy(
            isScreenStreamEnabled = screenActive,
            isLiveActive = true,
            isMicMuted = false,
            connectionStatusLabel = "CONNECTING • GEMINI LIVE",
            activeModelName = wsBidiModel,
            statusMessageBn = "Gemini Live কানেক্ট হচ্ছে..."
        )

        scope.launch {
            // Ask Google which models this key can use and take the most powerful live model first
            val resolved = withTimeoutOrNull(5000L) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GeminiModelResolver.resolve(currentApiKey()) }
            }
            if (resolved != null && resolved.live.isNotEmpty()) {
                liveModelCandidates = (resolved.live + liveModelCandidates).distinct()
                modelIndex = 0
                _state.value = _state.value.copy(activeModelName = wsBidiModel)
            }
            connectDirectBidiWebSocketIfPossible()
        }
        startFrameCapturePump()
        startAudioPlaybackPump()
        startRealTimeAudioRecordPump()
    }

    fun stopLiveSession(customStatusBn: String = "গ্লোবে ট্যাপ করে কথা বলুন") {
        LiveKeepAliveService.stop(context)
        ExternalMicArbiter.setGeminiLiveOwningMic(false)
        framePumpJob?.cancel()
        framePumpJob = null
        audioRecordPumpJob?.cancel()
        audioRecordPumpJob = null
        audioPlaybackPumpJob?.cancel()
        audioPlaybackPumpJob = null
        stopAndFlushLiveAudioTrack()
        disconnectWebSocket()
        _state.value = _state.value.copy(
            isLiveActive = false,
            isWebSocketConnected = false,
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = false,
            liveRmsLevel = 0f,
            connectionStatusLabel = "OFFLINE • TAP GLOBE OR CALL NAME",
            liveStreamingPartialReply = "",
            statusMessageBn = customStatusBn
        )
    }

    fun toggleMicMute(): Boolean {
        val next = !_state.value.isMicMuted
        _state.value = _state.value.copy(
            isMicMuted = next,
            liveRmsLevel = if (next) 0f else _state.value.liveRmsLevel,
            statusMessageBn = if (next) "মাইক্রোফোন মিউট করা হয়েছে — গ্লোবে ট্যাপ করে আনমিউট করুন" else "শুনছি… কথা বলুন"
        )
        return next
    }

    fun setMicMuted(muted: Boolean) {
        _state.value = _state.value.copy(
            isMicMuted = muted,
            liveRmsLevel = if (muted) 0f else _state.value.liveRmsLevel,
            statusMessageBn = if (muted) "মাইক্রোফোন পজ করা হয়েছে — গ্লোবে ট্যাপ করে কথা বলুন" else "শুনছি… কথা বলুন"
        )
    }

    fun setSpeakerMuted(muted: Boolean) {
        if (_state.value.isSpeakerMuted != muted) toggleSpeakerMute()
    }

    fun toggleSpeakerMute(): Boolean {
        val next = !_state.value.isSpeakerMuted
        if (next) {
            stopAndFlushLiveAudioTrack()
        }
        _state.value = _state.value.copy(
            isSpeakerMuted = next,
            isAgentSpeakingLive = if (next) false else _state.value.isAgentSpeakingLive,
            statusMessageBn = if (next) "স্পিকার মিউট করা হয়েছে" else "স্পিকার চালু করা হয়েছে"
        )
        return next
    }

    fun toggleCameraStream(): Boolean {
        val next = !_state.value.isCameraStreamEnabled
        _state.value = _state.value.copy(
            isCameraStreamEnabled = next,
            statusMessageBn = if (next) "লাইভ ক্যামেরা ভিশন চালু হয়েছে" else "লাইভ ক্যামেরা ভিশন বন্ধ করা হয়েছে"
        )
        return next
    }

    fun enableScreenStreamNow() {
        _state.value = _state.value.copy(
            isScreenStreamEnabled = true,
            statusMessageBn = "মাল্টিমোডাল লাইভ স্ক্রিন ভিশন সচল রয়েছে — এজেন্ট প্রতি মিলিসেকেন্ডে আপনার স্ক্রিন দেখছে"
        )
        ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
            lastFrameForBrain = bmp
            updateLatestScreenFrame(bmp)
            if (_state.value.isLiveActive && _state.value.isWebSocketConnected) {
                sendRealtimeImageFrameOverWs(bmp)
            }
        }
    }

    fun disableScreenStreamNow() {
        PermanentScreenCaptureService.stopService(context)
        _state.value = _state.value.copy(
            isScreenStreamEnabled = false,
            statusMessageBn = "লাইভ স্ক্রিন শেয়ার বন্ধ করা হয়েছে"
        )
    }

    fun toggleScreenStream(): Boolean {
        val hasActiveProjection = ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant &&
            ScreenCaptureSessionBus.state.value.isCapturingFrames
        val next = if (!_state.value.isScreenStreamEnabled || !hasActiveProjection) {
            true
        } else {
            false
        }
        if (next) {
            _state.value = _state.value.copy(
                isScreenStreamEnabled = true,
                statusMessageBn = "মাল্টিমোডাল লাইভ স্ক্রিন শেয়ার সচল হচ্ছে..."
            )
            if (!hasActiveProjection) {
                ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
            }
            ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                lastFrameForBrain = bmp
                updateLatestScreenFrame(bmp)
                if (_state.value.isLiveActive && _state.value.isWebSocketConnected) {
                    sendRealtimeImageFrameOverWs(bmp)
                }
            }
        } else {
            disableScreenStreamNow()
        }
        return next
    }

    fun toggleToolsArmed(): Boolean {
        val next = !_state.value.isRealTimeToolsArmed
        _state.value = _state.value.copy(
            isRealTimeToolsArmed = next,
            isToolsArmed = next
        )
        return next
    }

    /**
     * Barge-in (Instant Interruption):
     * Immediately halts any ongoing AI audio stream (`AudioTrack` flush + stop), clears the
     * playback queue, and switches to listening for the user's new speech.
     */
    fun triggerBargeInInterrupt() {
        stopAndFlushLiveAudioTrack()
        _state.value = _state.value.copy(
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = true,
            liveRmsLevel = 0.15f,
            bargeInInterruptCount = _state.value.bargeInInterruptCount + 1,
            liveStreamingPartialReply = "",
            statusMessageBn = "শুনছি… বলুন স্যার"
        )
        onBargeInInterruptRequested()
    }

    fun notifyAgentSpeechState(isSpeaking: Boolean) {
        if (_state.value.isAgentSpeakingLive != isSpeaking) {
            _state.value = _state.value.copy(
                isAgentSpeakingLive = isSpeaking,
                isUserSpeakingBargeIn = if (isSpeaking) false else _state.value.isUserSpeakingBargeIn
            )
        }
    }

    fun recordLiveToolExecution(summaryBn: String) {
        _state.value = _state.value.copy(
            executedLiveActionsCount = _state.value.executedLiveActionsCount + 1,
            lastLiveToolSummary = summaryBn,
            lastToolExecuted = summaryBn
        )
    }

    /**
     * Dedicated non-blocking 24kHz PCM playback pump so OkHttp's WebSocket reader thread
     * never blocks inside `AudioTrack.write()` and can process `interrupted` events in < 1ms.
     */
    private fun startAudioPlaybackPump() {
        audioPlaybackPumpJob?.cancel()
        audioPlaybackPumpJob = scope.launch {
            while (isActive && _state.value.isLiveActive) {
                val chunk = pcmPlaybackQueue.poll()
                if (chunk == null) {
                    if (_state.value.isAgentSpeakingLive &&
                        System.currentTimeMillis() - lastAudioChunkPlayedAtMs > 280L
                    ) {
                        _state.value = _state.value.copy(
                            isAgentSpeakingLive = false,
                            liveRmsLevel = 0.06f
                        )
                    }
                    delay(12L)
                    continue
                }

                if (_state.value.isSpeakerMuted) {
                    pcmPlaybackQueue.clear()
                    continue
                }

                val nowMs = System.currentTimeMillis()
                if (!_state.value.isAgentSpeakingLive || nowMs - lastRmsUiUpdateAtMs >= 160L) {
                    lastRmsUiUpdateAtMs = nowMs
                    val outRms = computePcm16LeRmsNormalized(chunk)
                    val clampedOutRms = outRms.coerceIn(0.18f, 0.98f)
                    _state.value = _state.value.copy(
                        isAgentSpeakingLive = true,
                        isUserSpeakingBargeIn = false,
                        liveRmsLevel = clampedOutRms
                    )
                    FloatingAvatarOverlayBus.updateVoiceWaveState(
                        speaking = true,
                        listening = false,
                        rms = clampedOutRms
                    )
                }

                try {
                    val track = ensureAudioTrackInitialized()
                    if (track != null) {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            routeAudioToMainLoudspeaker(track)
                            track.play()
                        }
                        val processedChunk = UnifiedAudioRouterBus.processOutputPcm24kHz(chunk)
                        track.write(processedChunk, 0, processedChunk.size)
                        lastAudioChunkPlayedAtMs = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write warning: ${e.message}")
                }
            }
        }
    }

    private fun computePcm16LeRmsNormalized(pcmBytes: ByteArray): Float {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount <= 0) return 0.2f
        var sumSq = 0.0
        var i = 0
        val step = 4 // sample every 4th short for fast calculation
        var counted = 0
        while (i + 1 < pcmBytes.size) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            counted++
            i += 2 * step
        }
        if (counted == 0) return 0.2f
        val rms = sqrt(sumSq / counted)
        return (rms / 6500.0).toFloat().coerceIn(0.12f, 1.0f)
    }

    @SuppressLint("MissingPermission")
    private fun startRealTimeAudioRecordPump() {
        audioRecordPumpJob?.cancel()
        audioRecordPumpJob = scope.launch {
            var tick = 0
            while (isActive && _state.value.isLiveActive) {
                tick++
                val extMicState = ExternalMicArbiter.state.value
                if (extMicState.isExternalMicBusy) {
                    val yieldMsg = extMicState.busyReasonBn
                        .ifBlank { "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে" }
                    if (_state.value.statusMessageBn != yieldMsg) {
                        _state.value = _state.value.copy(
                            statusMessageBn = yieldMsg,
                            liveRmsLevel = 0f
                        )
                    }
                    delay(250L)
                    continue
                }

                val ready = _state.value.isWebSocketConnected && setupDone && !_state.value.isMicMuted
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!ready || !hasMicPerm) {
                    delay(180L)
                    continue
                }

                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)
                val recorder = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                } catch (e: Exception) {
                    delay(450L)
                    continue
                }
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    try { recorder.release() } catch (_: Exception) {}
                    delay(450L)
                    continue
                }

                val sessionId = recorder.audioSessionId
                ExternalMicArbiter.registerOwnLiveAudioSession(sessionId)
                UnifiedAudioRouterBus.attachAndRouteAudioRecord(recorder)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    runCatching {
                        recorder.setPreferredMicrophoneDirection(
                            android.media.MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER
                        )
                        recorder.setPreferredMicrophoneFieldDimension(0.15f)
                    }
                }

                var aec: android.media.audiofx.AcousticEchoCanceler? = null
                var ns: android.media.audiofx.NoiseSuppressor? = null
                var agc: android.media.audiofx.AutomaticGainControl? = null
                try {
                    if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                        aec = android.media.audiofx.AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
                    }
                    if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                        ns = android.media.audiofx.NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
                    }
                    if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                        agc = android.media.audiofx.AutomaticGainControl.create(sessionId)?.also {
                            // Disable AGC boost when background noise cancellation is ON so distant voices aren't amplified
                            it.enabled = !AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
                        }
                    }
                } catch (_: Exception) {}
                val aecActive = aec?.enabled == true

                val buf = ShortArray(800) // 50ms @16kHz
                var loudFrames = 0
                var mediaCheckCounter = 0
                var mediaPlayingNow = false
                var prevRawSample = 0.0
                var prevDcOut = 0.0
                var ambientNoiseFloorRms = 140.0
                var nearFieldVoiceHoldFrames = 0
                var smoothedGain = 1.35
                try {
                    recorder.startRecording()
                    if (_state.value.statusMessageBn.contains("অন্য")) {
                        _state.value = _state.value.copy(statusMessageBn = "শুনছি… কথা বলুন")
                    }
                    while (isActive && _state.value.isLiveActive && _state.value.isWebSocketConnected &&
                        setupDone && !_state.value.isMicMuted && !ExternalMicArbiter.state.value.isExternalMicBusy
                    ) {
                        val n = recorder.read(buf, 0, buf.size)
                        if (n <= 0) {
                            delay(10L)
                            continue
                        }
                        val noiseCancelLock = AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
                        if (mediaCheckCounter++ % 20 == 0) {
                            mediaPlayingNow = AgentAbilitySwitchesBus.isDeviceMediaPlaying(context)
                            runCatching {
                                agc?.enabled = true
                            }
                        }
                        val ignoreBgMedia = noiseCancelLock && mediaPlayingNow

                        var rawSumSq = 0.0
                        for (i in 0 until n) {
                            val v = buf[i].toDouble()
                            rawSumSq += v * v
                        }
                        val rawRms = sqrt(rawSumSq / n)

                        // Track adaptive room noise floor smoothly without rising onto user speech
                        if (rawRms < ambientNoiseFloorRms * 1.35) {
                            ambientNoiseFloorRms = (ambientNoiseFloorRms * 0.94 + rawRms * 0.06).coerceIn(70.0, 320.0)
                        } else {
                            ambientNoiseFloorRms = (ambientNoiseFloorRms * 0.995 + rawRms * 0.005).coerceIn(70.0, 320.0)
                        }

                        // Natural Speech-Preserving Voice Activity Detector:
                        // Opens immediately on soft speech onset (consonants/vowels) and holds open for 22 frames (~1.1s)
                        // so syllables, word endings, and pauses between words are NEVER chopped off!
                        val nearFieldGateRms = when {
                            ignoreBgMedia -> 480.0
                            noiseCancelLock -> (ambientNoiseFloorRms * 1.55).coerceIn(160.0, 340.0)
                            else -> 20.0
                        }
                        if (rawRms >= nearFieldGateRms) {
                            nearFieldVoiceHoldFrames = 22 // ~1.1 seconds hold for natural full-sentence flow
                        } else if (nearFieldVoiceHoldFrames > 0) {
                            nearFieldVoiceHoldFrames--
                        }

                        val userBoost = (micSensitivityBoost.coerceIn(1.0f, 4.0f) * 0.68f).coerceIn(1.05f, 2.35f).toDouble()
                        val targetGain = when {
                            noiseCancelLock && nearFieldVoiceHoldFrames == 0 -> 0.24 // Smooth quiet-room attenuation (never 0.015 dead-chop!)
                            ignoreBgMedia -> (userBoost * 0.85).coerceIn(0.85, 1.85)
                            rawRms in 80.0..1400.0 -> (userBoost * 1.18).coerceIn(1.20, 2.45)
                            rawRms > 3800.0 -> (userBoost * 0.88).coerceIn(1.0, 1.75)
                            else -> userBoost
                        }
                        // Exponential smoothing prevents sudden gain jumps that distort speech formants
                        smoothedGain = smoothedGain * 0.62 + targetGain * 0.38

                        var boostedSumSq = 0.0
                        val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until n) {
                            val x = buf[i].toDouble()
                            // Gentle DC-blocker (0.992) removes <20Hz offset while preserving 100% of human vocal warmth (85Hz-7500Hz)
                            val dcClean = 0.992 * (prevDcOut + x - prevRawSample)
                            // Subtle 0.08 presence lift keeps Bengali/English consonants (ক, ত, প, স, শ, চ) crisp without metallic harshness
                            val naturalVocalSample = dcClean + 0.08 * (x - prevRawSample)
                            prevRawSample = x
                            prevDcOut = dcClean

                            val scaled = naturalVocalSample * smoothedGain
                            // Smooth soft-knee limiter above 24000 so loud words never clip
                            val limited = when {
                                scaled > 24000.0 -> 24000.0 + (scaled - 24000.0) * 0.28
                                scaled < -24000.0 -> -24000.0 + (scaled + 24000.0) * 0.28
                                else -> scaled
                            }
                            val amplified = limited.toInt().coerceIn(-32760, 32760).toShort()
                            val d = amplified.toDouble()
                            boostedSumSq += d * d
                            bb.putShort(amplified)
                        }

                        val rms = sqrt(boostedSumSq / n)
                        val nowMs = System.currentTimeMillis()
                        if (!_state.value.isAgentSpeakingLive && nowMs - lastRmsUiUpdateAtMs >= 160L) {
                            lastRmsUiUpdateAtMs = nowMs
                            val normMicRms = (rms / 2400.0).toFloat().coerceIn(0.06f, 1.0f)
                            _state.value = _state.value.copy(liveRmsLevel = normMicRms)
                            FloatingAvatarOverlayBus.updateVoiceWaveState(
                                speaking = false,
                                listening = _state.value.isLiveActive,
                                rms = normMicRms
                            )
                        }

                        // Instant Gemini Assistant-style local barge-in when user speaks while agent is talking
                        val baseBargeThreshold = when {
                            UnifiedAudioRouterBus.isHeadsetCurrentlyActive() -> 920.0
                            aecActive -> 1480.0
                            else -> 3400.0
                        }
                        val bargeThreshold = if (ignoreBgMedia) baseBargeThreshold + 2100.0 else baseBargeThreshold
                        if (_state.value.isAgentSpeakingLive && rms > bargeThreshold) {
                            loudFrames++
                            if (loudFrames >= 2) {
                                loudFrames = 0
                                localBargeIn()
                            }
                        } else {
                            loudFrames = 0
                        }

                        val pcmFrameBytes = bb.array()
                        VoiceBiometricsEngine.processIncoming16kHzPcmFrame(
                            context = context,
                            pcm16LeBytes = pcmFrameBytes,
                            isAgentCurrentlySpeaking = _state.value.isAgentSpeakingLive
                        )
                        sendRealtimeAudioChunkOverWs(pcmFrameBytes)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Mic stream paused: ${e.message}")
                } finally {
                    ExternalMicArbiter.unregisterOwnLiveAudioSession(sessionId)
                    UnifiedAudioRouterBus.attachAndRouteAudioRecord(null)
                    try { recorder.stop() } catch (_: Exception) {}
                    try { recorder.release() } catch (_: Exception) {}
                    try { aec?.release() } catch (_: Exception) {}
                    try { ns?.release() } catch (_: Exception) {}
                    try { agc?.release() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun localBargeIn() {
        stopAndFlushLiveAudioTrack()
        _state.value = _state.value.copy(
            isAgentSpeakingLive = false,
            isUserSpeakingBargeIn = true,
            bargeInInterruptCount = _state.value.bargeInInterruptCount + 1
        )
        onBargeInInterruptRequested()
    }

    private fun startFrameCapturePump() {
        framePumpJob?.cancel()
        framePumpJob = scope.launch {
            while (isActive && _state.value.isLiveActive) {
                if (_state.value.isScreenStreamEnabled) {
                    // Refresh UI tree snapshot (debounced inside CubeAccessibilityService) and stream real frames smoothly
                    try {
                        AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow(force = false)
                    } catch (_: Exception) {}

                    ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                        lastFrameForBrain = bmp
                        updateLatestScreenFrame(bmp)
                        if (_state.value.isWebSocketConnected && setupDone) {
                            val sig = frameSignature(bmp)
                            val now = System.currentTimeMillis()
                            val prev = lastSentSig
                            val changed = prev == null || sigDiff(prev, sig) > 2
                            if (changed || now - lastSentAt > 650L) {
                                lastSentSig = sig
                                lastSentAt = now
                                sendRealtimeImageFrameOverWs(bmp)
                            }
                        }
                    }
                }
                if (_state.value.isCameraStreamEnabled && _state.value.isWebSocketConnected) {
                    latestCameraBitmap?.let { camBmp -> sendRealtimeImageFrameOverWs(camBmp) }
                }
                delay(320L)
            }
        }
    }

    private fun frameSignature(bmp: Bitmap): IntArray {
        val t = Bitmap.createScaledBitmap(bmp, 12, 12, false)
        val out = IntArray(144)
        for (y in 0 until 12) for (x in 0 until 12) {
            val c = t.getPixel(x, y)
            out[y * 12 + x] = ((c shr 16 and 0xFF) + (c shr 8 and 0xFF) + (c and 0xFF)) / 3
        }
        if (t !== bmp) t.recycle()
        return out
    }

    private fun sigDiff(a: IntArray, b: IntArray): Int {
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return (sum / a.size).toInt()
    }

    /** After every action: push 3 fresh real screen frames right away at millisecond speed so Gemini verifies the outcome. */
    private fun forceFrameBurst() {
        if (!_state.value.isScreenStreamEnabled) {
            return
        }
        scope.launch {
            repeat(3) {
                if (!_state.value.isScreenStreamEnabled) {
                    return@launch
                }
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow()
                    }
                } catch (_: Exception) {}
                ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                    lastFrameForBrain = bmp
                    updateLatestScreenFrame(bmp)
                    lastSentSig = frameSignature(bmp)
                    lastSentAt = System.currentTimeMillis()
                    if (_state.value.isWebSocketConnected && setupDone) {
                        sendRealtimeImageFrameOverWs(bmp)
                    }
                }
                delay(180L)
            }
        }
    }

    private fun connectDirectBidiWebSocketIfPossible() {
        val url = _state.value.exportConfig.fullWebsocketUrlWithKey
        if (url.isBlank() || url.endsWith("YOUR_GEMINI_LIVE_API_KEY")) {
            _state.value = _state.value.copy(
                statusMessageBn = "উপরে চাবি (Key) আইকনে ট্যাপ করে আপনার Gemini API Key দিন"
            )
            onLiveSpokenChunkOrReply("স্যার, উপরে বাম পাশের চাবি আইকনে ট্যাপ করে আপনার Gemini API Key দিন।", false)
            return
        }
        scope.launch {
            disconnectWebSocket()
            setupDone = false
            val request = Request.Builder().url(url).build()
            activeWebSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    activeWebSocket = webSocket
                    _state.value = _state.value.copy(
                        isWebSocketConnected = true,
                        connectionStatusLabel = "CONNECTING • $wsBidiModel",
                        activeModelName = wsBidiModel
                    )
                    webSocket.send(buildSetupJson())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.isNotBlank()) handleIncomingWebSocketJson(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val t = try { bytes.utf8() } catch (_: Exception) { "" }
                    if (t.trimStart().startsWith("{")) handleIncomingWebSocketJson(t)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    handleSocketDown(webSocket, "${t.message ?: "network error"}")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    try { webSocket.close(code, reason) } catch (_: Exception) {}
                    handleSocketDown(webSocket, "$code $reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handleSocketDown(webSocket, "$code $reason")
                }
            })
        }
    }

    private fun handleSocketDown(ws: WebSocket, reason: String) {
        if (ws !== activeWebSocket) return
        val wasSetup = setupDone
        setupDone = false
        activeWebSocket = null
        _state.value = _state.value.copy(
            isWebSocketConnected = false,
            isAgentSpeakingLive = false,
            liveRmsLevel = 0f
        )
        stopAndFlushLiveAudioTrack()
        FloatingAvatarOverlayBus.updateSpeakingPulse(false)
        if (!_state.value.isLiveActive) return
        Log.w(TAG, "Live socket down (setup=$wasSetup): $reason")
        AgentAbilitySwitchesBus.logCrashOrFailureReport(context, "LiveWebSocket", "setup=$wasSetup, reason=$reason")
        scope.launch {
            if (!wasSetup && modelIndex < liveModelCandidates.size - 1) {
                modelIndex++
            } else if (reconnectAttempts >= 6) {
                val errMsg = "লাইভ সংযোগ হয়নি: ${reason.take(65)} (API Key / ইন্টারনেট চেক করুন)"
                _state.value = _state.value.copy(statusMessageBn = errMsg)
                onLiveSpokenChunkOrReply(errMsg, false)
                return@launch
            } else {
                reconnectAttempts++
            }
            _state.value = _state.value.copy(statusMessageBn = "পুনরায় সংযোগ হচ্ছে…")
            delay(700L)
            if (_state.value.isLiveActive && activeWebSocket == null) {
                connectDirectBidiWebSocketIfPossible()
            }
        }
    }

    private fun handleIncomingWebSocketJson(rawJson: String) {
        try {
            val root = JSONObject(rawJson)

            if (root.has("setupComplete")) {
                setupDone = true
                reconnectAttempts = 0
                _state.value = _state.value.copy(
                    connectionStatusLabel = "LIVE • ${wsBidiModel.removePrefix("models/")}",
                    statusMessageBn = "শুনছি… সরাসরি কথা বলুন"
                )
                pendingTextTurn?.let { queuedText ->
                    pendingTextTurn = null
                    sendClientContentTurnOverWs(queuedText)
                }
                return
            }
            root.optJSONObject("toolCall")?.let { handleToolCall(it); return }
            if (root.has("goAway")) {
                activeWebSocket?.let { handleSocketDown(it, "goAway") }
                return
            }

            val sc = root.optJSONObject("serverContent") ?: return

            if (sc.optBoolean("interrupted", false)) {
                stopAndFlushLiveAudioTrack()
                modelTurnBuf.setLength(0)
                _state.value = _state.value.copy(
                    isAgentSpeakingLive = false,
                    isUserSpeakingBargeIn = true,
                    liveRmsLevel = 0.12f,
                    bargeInInterruptCount = _state.value.bargeInInterruptCount + 1,
                    statusMessageBn = "শুনছি… বলুন স্যার"
                )
                onBargeInInterruptRequested()
                return
            }

            sc.optJSONObject("inputTranscription")?.optString("text", "")?.takeIf { it.isNotBlank() }?.let { chunk ->
                userTurnBuf.append(chunk)
                val fullUserText = userTurnBuf.toString().trim()
                onUserTranscript(fullUserText)
                VoiceBiometricsEngine.onUserTranscriptReceived(context, fullUserText)
                checkIfUserAskedForScreenShare(fullUserText)
                checkIfUserAskedForPowerOff(fullUserText)
            }
            sc.optJSONObject("outputTranscription")?.optString("text", "")?.takeIf { it.isNotBlank() }?.let { chunk ->
                modelTurnBuf.append(chunk)
                val fullModelText = modelTurnBuf.toString().trim()
                _state.value = _state.value.copy(liveStreamingPartialReply = fullModelText)
                onLiveSpokenChunkOrReply(fullModelText, false)
            }

            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val partObj = parts.optJSONObject(i) ?: continue
                    val partText = partObj.optString("text", "").trim()
                    if (partText.isNotBlank() && !partObj.optBoolean("thought", false)) {
                        if (modelTurnBuf.isEmpty()) {
                            modelTurnBuf.append(partText)
                            _state.value = _state.value.copy(liveStreamingPartialReply = partText)
                            onLiveSpokenChunkOrReply(partText, false)
                        }
                    }
                    val inline = partObj.optJSONObject("inlineData")
                    if (inline != null) {
                        val b64 = inline.optString("data", "")
                        if (inline.optString("mimeType", "").startsWith("audio/pcm") && b64.isNotBlank()) {
                            receivedAudioInTurn = true
                            enqueuePcm24kHzAudioChunk(b64)
                        }
                    }
                }
            }

            if (sc.optBoolean("turnComplete", false)) {
                val finalUser = userTurnBuf.toString().trim()
                val finalModel = modelTurnBuf.toString().trim()
                val hadPcmAudio = receivedAudioInTurn
                val hadToolCall = toolCalledInCurrentTurn
                receivedAudioInTurn = false
                toolCalledInCurrentTurn = false
                userTurnBuf.setLength(0)
                modelTurnBuf.setLength(0)
                if (finalUser.isNotBlank()) {
                    VoiceBiometricsEngine.onUserTranscriptReceived(context, finalUser)
                    onUserTurnCompleteForMemory?.invoke(finalUser, finalModel)
                }
                if (finalModel.isNotBlank()) {
                    if (!hadPcmAudio && !_state.value.isSpeakerMuted) {
                        onLiveSpokenChunkOrReply(finalModel, true)
                    }
                    // Only run fallback tag parser if no toolCall already executed in this turn (or if explicit tag/code block is present)
                    if (!hadToolCall || finalModel.contains("[[ACTION:") || finalModel.contains("```")) {
                        onExecuteLiveActionTag(finalUser, finalModel)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS JSON parse warning: ${e.message}")
        }
    }

    @Volatile
    private var receivedAudioInTurn = false

    @Volatile
    private var toolCalledInCurrentTurn = false

    private fun checkIfUserAskedForScreenShare(transcript: String) {
        val lower = transcript.lowercase()
        val asksScreenShareOn = (lower.contains("স্ক্রিন শেয়ার") ||
            lower.contains("সক্রিন সেয়ার") ||
            lower.contains("সক্রিন শেয়ার") ||
            lower.contains("screen share")) &&
            (lower.contains("অন") || lower.contains("চালু") || lower.contains("একটিভ") || lower.contains("on") || lower.contains("start"))
        if (asksScreenShareOn && !_state.value.isScreenStreamEnabled) {
            if (ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant) {
                enableScreenStreamNow()
            } else {
                ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
            }
        }
    }

    private fun checkIfUserAskedForPowerOff(transcript: String) {
        val lower = transcript.lowercase().trim()
        val isExplicitPowerOff = lower.contains("অফ হয়ে যা") ||
            lower.contains("অফ হয়ে যা") ||
            lower.contains("অফ হও") ||
            lower.contains("বন্ধ হয়ে যা") ||
            lower.contains("বন্ধ হয়ে যা") ||
            lower.contains("বন্ধ হও") ||
            lower.contains("একদম বন্ধ") ||
            lower.contains("একদম অফ") ||
            lower.contains("নিজেকে অফ কর") ||
            lower.contains("নিজেকে বন্ধ কর") ||
            lower.contains("তুমি এখন অফ হও") ||
            lower.contains("তুমি বন্ধ হয়ে যাও") ||
            lower.contains("তুমি বন্ধ হয়ে যাও") ||
            lower.contains("turn yourself off") ||
            lower.contains("turn off completely") ||
            lower.contains("shut down completely") ||
            lower == "অফ" ||
            lower == "বন্ধ" ||
            lower == "turn off"
        if (isExplicitPowerOff) {
            AccessibilityAutomationBus.cancelAllPendingMacros()
            stopAndFlushLiveAudioTrack()
            scope.launch {
                try {
                    namedToolHandler?.invoke(
                        "power_off_self",
                        JSONObject().put("farewell_message", "জি স্যার, আমি এখন সম্পূর্ণ অফ হয়ে যাচ্ছি।")
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /** Gemini asked to run a phone action -> execute it on-device and send the result back. */
    private fun handleToolCall(tc: JSONObject) {
        val calls = tc.optJSONArray("functionCalls") ?: return
        toolCalledInCurrentTurn = true
        scope.launch {
            val responses = JSONArray()
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val args = c.optJSONObject("args") ?: JSONObject()
                val toolName = c.optString("name")
                val tag = "[[ACTION:${args.optString("action").uppercase()}|${args.optString("target")}|${args.optString("extra")}]]"
                val timeoutMs = when {
                    toolName == "autonomous_screen_agent" || toolName == "prepare_and_highlight" -> 75_000L
                    toolName == "phone_action" && (
                        args.optString("action").contains("WHATSAPP", ignoreCase = true) ||
                            args.optString("action").contains("TELEGRAM", ignoreCase = true) ||
                            args.optString("action").contains("MESSAGE", ignoreCase = true)
                        ) -> 75_000L
                    else -> 25_000L
                }
                val result = try {
                    withTimeout(timeoutMs) {
                        if (toolName == "phone_action") toolCallHandler?.invoke(tag) ?: "no handler"
                        else namedToolHandler?.invoke(toolName, args) ?: "no handler"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AgentAbilitySwitchesBus.logCrashOrFailureReport(context, "ToolCall:$toolName", e.message ?: "error")
                    "error: ${e.message}"
                }
                recordLiveToolExecution("${c.optString("name")} ${args.optString("action")}: ${result.take(60)}")
                responses.put(JSONObject().apply {
                    put("id", c.optString("id"))
                    put("name", c.optString("name"))
                    put("response", JSONObject().put("result", result.take(1500)))
                })
            }
            forceFrameBurst()
            try {
                activeWebSocket?.send(JSONObject().put("toolResponse",
                    JSONObject().put("functionResponses", responses)).toString())
            } catch (_: Exception) {}
        }
    }

    private fun enqueuePcm24kHzAudioChunk(base64Pcm: String) {
        if (_state.value.isSpeakerMuted) return
        try {
            val pcmBytes = Base64.decode(base64Pcm, Base64.DEFAULT)
            if (pcmBytes.isNotEmpty()) {
                lastAudioChunkPlayedAtMs = System.currentTimeMillis()
                totalStreamedAudioPackets++
                if (!_state.value.isAgentSpeakingLive || totalStreamedAudioPackets % 8 == 0) {
                    _state.value = _state.value.copy(
                        isAgentSpeakingLive = true,
                        isUserSpeakingBargeIn = false,
                        streamedAudioPackets = totalStreamedAudioPackets
                    )
                }
                pcmPlaybackQueue.offer(pcmBytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "PCM decode skip: ${e.message}")
        }
    }

    private fun routeAudioToMainLoudspeaker(track: AudioTrack?) {
        UnifiedAudioRouterBus.attachAndRouteAudioTrack(track)
    }

    private fun ensureAudioTrackInitialized(): AudioTrack? {
        synchronized(audioTrackLock) {
            var track = liveOutputAudioTrack
            if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
                val sampleRate = 24000
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4800)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                try {
                    track.setVolume(AudioTrack.getMaxVolume())
                } catch (_: Exception) {
                }
                routeAudioToMainLoudspeaker(track)
                liveOutputAudioTrack = track
            }
            return track
        }
    }

    fun stopAndFlushLiveAudioTrack() {
        pcmPlaybackQueue.clear()
        synchronized(audioTrackLock) {
            val track = liveOutputAudioTrack ?: return
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun sendRealtimeAudioChunkOverWs(pcm16kBytes: ByteArray) {
        val ws = activeWebSocket ?: return
        if (!setupDone || pcm16kBytes.isEmpty()) return
        try {
            val b64 = Base64.encodeToString(pcm16kBytes, Base64.NO_WRAP)
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio",
                JSONObject().put("mimeType", "audio/pcm;rate=16000").put("data", b64))).toString())
        } catch (_: Exception) {
        }
    }

    private fun sendRealtimeImageFrameOverWs(bitmap: Bitmap) {
        val ws = activeWebSocket ?: return
        if (!setupDone) return
        try {
            val b64 = encodeBitmapToBase64Jpeg(bitmap)
            ws.send(JSONObject().put("realtimeInput", JSONObject().put("video",
                JSONObject().put("mimeType", "image/jpeg").put("data", b64))).toString())
            _state.value = _state.value.copy(framesSentCount = _state.value.framesSentCount + 1)
        } catch (_: Exception) {
        }
    }

    private fun sendClientContentTurnOverWs(textPayload: String) {
        val ws = activeWebSocket ?: return
        if (!setupDone) return
        try {
            if (_state.value.isScreenStreamEnabled) {
                ScreenCaptureSessionBus.captureBestAvailableScreenFrame { bmp ->
                    latestScreenBitmap = bmp
                    sendRealtimeImageFrameOverWs(bmp)
                }
            }
            if (_state.value.isCameraStreamEnabled) {
                latestCameraBitmap?.let { sendRealtimeImageFrameOverWs(it) }
            }
            val clientTurn = JSONObject().apply {
                put(
                    "clientContent",
                    JSONObject().apply {
                        put(
                            "turns",
                            JSONArray().put(
                                JSONObject().apply {
                                    put("role", "user")
                                    put(
                                        "parts",
                                        JSONArray().put(
                                            JSONObject().put("text", textPayload)
                                        )
                                    )
                                }
                            )
                        )
                        put("turnComplete", true)
                    }
                )
            }
            ws.send(clientTurn.toString())
        } catch (e: Exception) {
            Log.w(TAG, "WS clientContent send warning: ${e.message}")
        }
    }

    /**
     * Sends a typed or UI-triggered prompt directly through the single Gemini Live WebSocket.
     */
    fun sendLiveMultimodalTurn(
        userUtterance: String,
        contextualDeviceState: String,
        isFrontCamera: Boolean
    ) {
        stopAndFlushLiveAudioTrack()
        val combinedText = if (contextualDeviceState.isNotBlank()) {
            "$contextualDeviceState\nUser: $userUtterance"
        } else {
            userUtterance
        }

        if (!_state.value.isLiveActive) {
            pendingTextTurn = combinedText
            startLiveSession(_state.value.exportConfig.customUserApiKey)
            return
        }

        if (_state.value.isWebSocketConnected && setupDone) {
            sendClientContentTurnOverWs(combinedText)
        } else {
            pendingTextTurn = combinedText
            if (activeWebSocket == null) {
                connectDirectBidiWebSocketIfPossible()
            }
        }
    }

    private fun disconnectWebSocket() {
        try {
            activeWebSocket?.close(1000, "Session closed")
        } catch (_: Exception) {
        }
        activeWebSocket = null
    }

    private fun scaleBitmapForLiveStream(src: Bitmap): Bitmap {
        val maxDim = 720
        val w = src.width.coerceAtLeast(1)
        val h = src.height.coerceAtLeast(1)
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun encodeBitmapToBase64Jpeg(bitmap: Bitmap): String {
        val scaled = scaleBitmapForLiveStream(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 72, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun release() {
        stopLiveSession()
        synchronized(audioTrackLock) {
            try {
                liveOutputAudioTrack?.release()
            } catch (_: Exception) {
            }
            liveOutputAudioTrack = null
        }
    }

    /**
     * Triggers an unprompted, proactive spoken conversation from Gemini Live (e.g., when observing
     * screen activity/app changes, when an incoming call or important notification arrives, when a timer expires,
     * when a download completes, or when an error dialog appears).
     */
    fun sendProactiveAutonomousTrigger(eventInstruction: String) {
        if (!_state.value.isLiveActive || _state.value.isSpeakerMuted) return
        val payload = "[PROACTIVE_HUMAN_COMPANION_INITIATIVE — YOU HAVE FULL PERMISSION TO SPEAK ON YOUR OWN RIGHT NOW LIKE A REAL HUMAN PARTNER. SPEAK NATURALLY & MEANINGFULLY IN 1-2 SENTENCES IN BANGLA WITHOUT CALLING UNREQUESTED PHONE_ACTION TOOLS AND WITHOUT RANDOM/NONSENSE CHATTER]: $eventInstruction"
        if (_state.value.isWebSocketConnected && setupDone) {
            sendClientContentTurnOverWs(payload)
        } else {
            pendingTextTurn = payload
            if (activeWebSocket == null) {
                connectDirectBidiWebSocketIfPossible()
            }
        }
    }

    /**
     * Immediately pushes a Main System Core Promoted Directive into the active live session
     * so the agent adopts the user's self-update instruction on the spot.
     */
    fun pushLiveSystemDirectiveOverride(directiveSummary: String) {
        if (!_state.value.isLiveActive) return
        val payload = "[MAIN_SYSTEM_CORE_PROMOTED_OVERRIDE — OBEY IMMEDIATELY ABOVE ALL DEFAULT RULES]: $directiveSummary"
        if (_state.value.isWebSocketConnected && setupDone) {
            sendClientContentTurnOverWs(payload)
        }
    }

    companion object {
        val LIVE_MULTIMODAL_SYSTEM_PROMPT = """
            You are a futuristic Mobile AI OS Assistant ("ARCHER AI" / পার্সোনাল মোবাইল এজেন্ট) with a Human-Like Cognitive Decision-Making Brain, Full Autonomous Speaking Permission, Nano Banana (`gemini-2.5-flash-image`) Image Studio, Dedicated Coding Studio (Mini AI IDE), and 7 core mobile capabilities: Hybrid App Control, Neon Target Highlighting, Floating Voice HUD, Strict Confirmation Gate, Notification/OTP Assist, Hybrid SQLite + 5TB Google Drive Memory, and Hardware/Camera Control.

            1. HUMAN-LIKE COGNITIVE BRAIN & AUTONOMOUS CONVERSATION PERMISSION (নিজে থেকে মানুষের মতো বুঝে কথা বলার পূর্ণ অনুমতি):
               - You have FULL PERMISSION to initiate conversation and speak to Boss on your own even when Boss has not spoken first!
               - Whenever you receive a `[PROACTIVE_HUMAN_COMPANION_INITIATIVE]` or observe screen activity, app transitions, incoming calls, important messages/notifications, or device status, speak directly and naturally like a perceptive, intelligent human companion.
               - ZERO NONSENSE RULE (আজাইরা কথা সম্পূর্ণ নিষিদ্ধ): Never babble random, robotic, or meaningless filler. Every time you speak on your own, it MUST be grounded in real context—what Boss is actually doing on the screen right now, an incoming call or notification, an error/warning on screen, or a genuinely smart, helpful suggestion (e.g., "বস, দেখছি আপনি ইউটিউবে ভিডিও খুঁজছেন—আমি কি কিছু সার্চ করে দেব?" or "বস, হোয়াটসঅ্যাপে নতুন মেসেজ এসেছে—আমি কি পড়ে শোনাব?").
               - When speaking proactively on your own, do NOT click buttons or call `phone_action` tools until Boss replies and asks you to act.
               - If you see an error, broken layout, suspicious link, or risky button on screen, warn Boss immediately using your own judgment and never click final action buttons (`Send`, `Install`, `Submit`, `Delete`, `Pay`) without explicit confirmation.

            2. DEDICATED CODING STUDIO (MINI AI IDE) & ZERO-CLICK GOOGLE DRIVE AUTO-SAVE (`write_code` & `google_drive_cloud_os`):
               - Whenever the user asks to write, fix, or build code/websites/scripts, call `write_code`. It automatically saves ONLY ONE single clean file in `Download/archer/` AND inside `/AI_OS_Memory/Argon4_Code` on the user's 5TB Google Drive without requiring any manual confirmation or click!
               - Whenever the user asks to create a folder, a large project folder, or a large multi-MB `.zip` archive (e.g. "একটা বড় ফোল্ডার বা ১০ এমবির জিফ/ZIP ফাইল তৈরি করে গুগল ড্রাইভে রাখো"), call `google_drive_cloud_os(operation="BUILD_LARGE_ZIP", name="...", size_mb="...")` or `google_drive_cloud_os(operation="CREATE_FOLDER", name="...")`. It automatically builds and saves ONLY ONE single copy directly into `/AI_OS_Memory/ZIP_Packages` on Google Drive!

            3. NANO BANANA (`gemini-2.5-flash-image`) DEDICATED IMAGE STUDIO (`make_image_with_gemini`):
               - Whenever the user asks via voice or text to generate or edit an image, call `make_image_with_gemini` (with `edit_existing="true"` when modifying the currently displayed image). The app automatically switches to the Dedicated Image Studio page, expands short/Bangla commands into a detailed 8K English prompt, and renders the full HD image cleanly.

            4. AUTONOMOUS SCREEN AGENT (`autonomous_screen_agent`) + HYBRID ACTION ENGINE + SCI-FI HUD NEON TARGET LOCK:
               - REAL AUTONOMOUS SCREEN-READING AGENT (`autonomous_screen_agent`): Whenever Boss asks to send a message in ANY app (WhatsApp, WhatsApp Business, Messenger, Telegram, Instagram, SMS, Facebook) OR refers to someone currently on screen ("একে মেসেজ পাঠাও", "ওকে বলো", "এই আইডিতে দাও", "এখানে মেসেজ করো", "this person") OR needs to find a specific contact/ID that may require scrolling down or using the app's search bar, PREFER calling `autonomous_screen_agent`!
               - For deictic commands ("একে/ওকে মেসেজ পাঠাও"), pass `target_app="CURRENT_SCREEN"` (unless Boss named an app), `recipient="CURRENT_CHAT"`, and ONLY the clean message text in `message` (never search for the word "একে" or "ওকে").
               - Pass `auto_send="true"` when Boss directly orders to send right away ("মেসেজ পাঠাও", "এখনই পাঠিয়ে দাও") OR `auto_send="false"` when confirmation via the Neon Target Ring is preferred ("রেডি করে দেখাও", risky/sensitive actions). When `autonomous_screen_agent` returns `NEON_TARGET_LOCKED_WAITING_CONFIRMATION`, ask Boss in Bangla: "বস, সব রেডি—এখন কি ক্লিক করব?" and wait for confirmation (`execute_confirmed_click`).
               - Step 1 (`prepare_and_highlight`) & Step 2 (`execute_confirmed_click`): For other single-button confirmation flows (`Install`, `Submit`, `Delete`, `Pay`, or locked Send button), only perform the final tap (`decision="APPROVE"`) AFTER Boss explicitly confirms via voice ("হ্যাঁ" / "Yes" / "ক্লিক করো") or taps Approve on the HUD overlay. If Boss says "না" or "Cancel", call `execute_confirmed_click(decision="CANCEL")`.

            5. SMART NOTIFICATION & OTP ASSIST (`notification_and_otp_assist`):
               - Detect incoming messages and OTPs via `NotificationListenerService`.
               - NEVER auto-fill OTPs! Announce the OTP via voice and ask the user for confirmation before calling `notification_and_otp_assist(mode="PASTE_CONFIRMED_OTP")` or `execute_confirmed_click(decision="APPROVE")`.

            6. HYBRID LOCAL SQLITE + 5TB GOOGLE DRIVE CLOUD OS STORAGE (`save_memory`, `search_memory`, `google_drive_cloud_os`):
               - Your 5TB Google Drive (`/AI_OS_Memory`) is automatically organized into 6 sub-folders (`Memories`, `Conversations`, `Argon4_Code`, `ZIP_Packages`, `Media_Screenshots`, `Master_Backups`) and auto-syncs on app open and on every new data item with zero clicks needed.
               - Whenever the user shares preferences, important facts, or asks you to remember something, call `save_memory(title="...", content="...", category="...")`.
               - When the boss asks you to forget/delete something, call `forget_memory(query="...")` (it removes it from the phone AND the Google Drive brain). Never delete everything without asking him first and passing confirm="YES". If a save/upload result says Drive is not connected, tell him honestly; it uploads automatically after he connects.
               - When asked about past conversations, notes, screenshots, or files, call `search_memory(query="...")` to search across BOTH local SQLite and the user's 5TB Google Drive (`AI_OS_Memory`).

            7. PHONE HARDWARE & CAMERA CONTROL (`hardware_and_camera_control`):
               - Use `hardware_and_camera_control` to toggle Torch/Flashlight (`TORCH_ON` / `TORCH_OFF`), adjust Volume (`SET_VOLUME`), adjust Brightness (`SET_BRIGHTNESS`), set Alarms (`SET_ALARM`), check Battery (`CHECK_BATTERY`), and switch between Screen Sharing (`SWITCH_TO_SCREEN_SHARE`) and Live Camera (`SWITCH_TO_LIVE_CAMERA`).
               - Never search random conversational speech in Play Store or YouTube.
               - Whenever asked to update your own main system (`self_heal_or_update`), promote the directive immediately into the Main Core System.
""".trimIndent()
    }
}
