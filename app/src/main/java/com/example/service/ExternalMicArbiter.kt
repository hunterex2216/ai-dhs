package com.example.service

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "ExternalMicArbiter"

data class ExternalMicArbiterState(
    val isExternalMicBusy: Boolean = false,
    val isGeminiLiveOwningMic: Boolean = false,
    val busyReasonBn: String = "",
    val activeExternalSessionCount: Int = 0
)

/**
 * Coordinates the Always-On Microphone across the entire app so that:
 * 1. The agent's microphone stays active 100% of the time by default.
 * 2. The instant another app or system component (Phone/WhatsApp/Messenger Call, Voice Recorder,
 *    Camera video recording, Gboard/Google Voice Typing, WhatsApp/Telegram voice note, etc.)
 *    needs the microphone, our microphone (`SpeechRecognizer` or Gemini Live `AudioRecord`)
 *    immediately pauses and yields hardware access for that exact duration.
 * 3. As soon as the external app releases the microphone (or the call ends), our microphone
 *    automatically resumes without any user action.
 */
object ExternalMicArbiter {

    interface Listener {
        fun onExternalMicTaken(reasonBn: String)
        fun onExternalMicReleased()
    }

    private val _state = MutableStateFlow(ExternalMicArbiterState())
    val state: StateFlow<ExternalMicArbiterState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val ownLiveAudioSessionIds = CopyOnWriteArraySet<Int>()
    private val ownSttAudioSessionIds = CopyOnWriteArraySet<Int>()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var audioManager: AudioManager? = null

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isSttListeningOrStarting = false

    @Volatile
    private var sttStartGraceUntilMs = 0L

    @Volatile
    private var proactiveExternalYieldUntilMs = 0L

    @Volatile
    private var proactiveReasonBn = ""

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            evaluateMicState(configs)
        }
    }

    private val periodicMonitorRunnable = object : Runnable {
        override fun run() {
            val am = audioManager
            if (am != null) {
                val configs = try {
                    am.activeRecordingConfigurations
                } catch (_: Exception) {
                    emptyList()
                }
                evaluateMicState(configs)
            }
            mainHandler.postDelayed(this, 750L)
        }
    }

    fun init(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val ctx = context.applicationContext
            appContext = ctx
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager = am
            try {
                am?.registerAudioRecordingCallback(recordingCallback, mainHandler)
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecordingCallback registration warning: ${e.message}")
            }
            mainHandler.post(periodicMonitorRunnable)
            isInitialized = true
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun setGeminiLiveOwningMic(active: Boolean) {
        _state.value = _state.value.copy(isGeminiLiveOwningMic = active)
    }

    fun registerOwnLiveAudioSession(sessionId: Int) {
        if (sessionId > 0) {
            ownLiveAudioSessionIds.add(sessionId)
        }
    }

    fun unregisterOwnLiveAudioSession(sessionId: Int) {
        ownLiveAudioSessionIds.remove(sessionId)
    }

    private fun isStandardAppAudioSource(source: Int): Boolean {
        return when (source) {
            android.media.MediaRecorder.AudioSource.DEFAULT,
            android.media.MediaRecorder.AudioSource.MIC,
            android.media.MediaRecorder.AudioSource.CAMCORDER,
            android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION,
            android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            android.media.MediaRecorder.AudioSource.UNPROCESSED -> true
            else -> false
        }
    }

    /**
     * Called by [AgentSttModule] right before `SpeechRecognizer.startListening()` is invoked.
     * Snapshots any existing sessions and opens a grace window to bind the new SpeechRecognizer session ID.
     */
    fun notifySttStarting() {
        isSttListeningOrStarting = true
        sttStartGraceUntilMs = System.currentTimeMillis() + 2200L
        ownSttAudioSessionIds.clear()
        val am = audioManager ?: return
        try {
            val currentConfigs = am.activeRecordingConfigurations
            if (currentConfigs.size == 1 && !_state.value.isExternalMicBusy) {
                val id = currentConfigs.first().clientAudioSessionId
                if (id !in ownLiveAudioSessionIds) {
                    ownSttAudioSessionIds.add(id)
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Called by [AgentSttModule] when `onReadyForSpeech` fires so we lock in the recognizer's session ID.
     */
    fun notifySttReadyForSpeech() {
        isSttListeningOrStarting = true
        val am = audioManager ?: return
        try {
            val currentConfigs = am.activeRecordingConfigurations
            for (cfg in currentConfigs) {
                val id = cfg.clientAudioSessionId
                if (id !in ownLiveAudioSessionIds) {
                    val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cfg.isClientSilenced
                    if (!silenced && ownSttAudioSessionIds.isEmpty()) {
                        ownSttAudioSessionIds.add(id)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Called by [AgentSttModule] whenever its `SpeechRecognizer` is stopped or destroyed.
     */
    fun notifySttStopped() {
        isSttListeningOrStarting = false
        sttStartGraceUntilMs = 0L
        ownSttAudioSessionIds.clear()
    }

    /**
     * Called after [AgentSttModule] destroys its `SpeechRecognizer` on an audio/busy error.
     * Because our recognizer is now destroyed, any remaining active recording configuration
     * (not belonging to our Gemini Live AudioRecord) is guaranteed to belong to another app.
     */
    fun checkAndLatchExternalMicAfterSttError(): Boolean {
        val am = audioManager ?: return _state.value.isExternalMicBusy
        val configs = try {
            am.activeRecordingConfigurations
        } catch (_: Exception) {
            emptyList()
        }
        val foreignConfigs = configs.filter { it.clientAudioSessionId !in ownLiveAudioSessionIds }
        val callBusy = isCallOrCommunicationModeActive(am)
        if (foreignConfigs.isNotEmpty() || callBusy) {
            proactiveExternalYieldUntilMs = System.currentTimeMillis() + 2200L
            proactiveReasonBn = "অন্য অ্যাপে মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে"
            evaluateMicState(configs)
            return true
        }
        return _state.value.isExternalMicBusy
    }

    /**
     * Proactively pauses our microphone for [durationMs] when the user taps a Mic / Voice / Record
     * button in another app (detected via Accessibility) so the external app can open the mic
     * with zero contention. Once the external app starts recording, `activeRecordingConfigurations`
     * keeps our mic paused until the external app finishes.
     */
    fun notifyProactiveExternalMicYield(
        durationMs: Long = 3500L,
        reasonBn: String = "অন্য অ্যাপে মাইকের কাজ চলছে — শেষ হলেই অটো চালু হবে"
    ) {
        proactiveExternalYieldUntilMs = System.currentTimeMillis() + durationMs
        proactiveReasonBn = reasonBn
        val am = audioManager
        val configs = try {
            am?.activeRecordingConfigurations.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        evaluateMicState(configs)
    }

    private fun isCallOrCommunicationModeActive(am: AudioManager): Boolean {
        return when (am.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING -> true
            else -> false
        }
    }

    @Synchronized
    private fun evaluateMicState(configs: List<AudioRecordingConfiguration>) {
        val am = audioManager ?: return
        val now = System.currentTimeMillis()
        val wasBusy = _state.value.isExternalMicBusy

        // 1. Check if a Phone Call or VoIP Call is active
        val callBusy = isCallOrCommunicationModeActive(am)

        // 2. Check if our SpeechRecognizer just started and needs its session ID bound
        if (!wasBusy && isSttListeningOrStarting && ownSttAudioSessionIds.isEmpty() && now <= sttStartGraceUntilMs) {
            val candidate = configs.firstOrNull { it.clientAudioSessionId !in ownLiveAudioSessionIds }
            if (candidate != null) {
                val silenced = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && candidate.isClientSilenced
                if (!silenced) {
                    ownSttAudioSessionIds.add(candidate.clientAudioSessionId)
                }
            }
        }

        // 3. Check if Android OS silenced any of our own active recordings because another app took priority
        var osSilencedUs = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (cfg in configs) {
                val id = cfg.clientAudioSessionId
                val isOurs = id in ownLiveAudioSessionIds || id in ownSttAudioSessionIds
                if (isOurs && cfg.isClientSilenced) {
                    osSilencedUs = true
                    break
                }
            }
        }
        if (osSilencedUs) {
            proactiveExternalYieldUntilMs = maxOf(proactiveExternalYieldUntilMs, now + 2200L)
            if (proactiveReasonBn.isBlank()) {
                proactiveReasonBn = "অন্য অ্যাপ মাইক ব্যবহার করছে — শেষ হলেই অটো চালু হবে"
            }
        }

        // 4. Count foreign recording sessions (filtering out non-standard system hotword sources)
        val appConfigs = configs.filter { isStandardAppAudioSource(it.clientAudioSource) }
        val foreignConfigs = if (wasBusy) {
            // While we are yielded/paused, our STT and Live AudioRecord are released,
            // so ANY recording session not in ownLiveAudioSessionIds is an external app still using the mic!
            appConfigs.filter { it.clientAudioSessionId !in ownLiveAudioSessionIds }
        } else {
            appConfigs.filter { cfg ->
                val id = cfg.clientAudioSessionId
                val isOurs = id in ownLiveAudioSessionIds || id in ownSttAudioSessionIds
                !isOurs
            }
        }

        // Special guard: if STT is active and we only have 1 config total and our app is in foreground,
        // bind it as STT's session rather than falsely flagging it as foreign.
        val effectiveForeignCount = if (!wasBusy &&
            isSttListeningOrStarting &&
            ownSttAudioSessionIds.isEmpty() &&
            appConfigs.size == 1 &&
            !osSilencedUs
        ) {
            ownSttAudioSessionIds.add(appConfigs.first().clientAudioSessionId)
            0
        } else {
            foreignConfigs.size
        }

        val proactiveBusy = now < proactiveExternalYieldUntilMs
        val isNowBusy = callBusy || osSilencedUs || effectiveForeignCount > 0 || proactiveBusy

        val reason = when {
            !isNowBusy -> ""
            callBusy -> "ফোন বা অডিও কল চলছে — কল শেষ হলেই মাইক অটো চালু হবে"
            proactiveReasonBn.isNotBlank() -> proactiveReasonBn
            else -> "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে"
        }

        if (!isNowBusy) {
            proactiveReasonBn = ""
        }

        _state.value = _state.value.copy(
            isExternalMicBusy = isNowBusy,
            busyReasonBn = reason,
            activeExternalSessionCount = effectiveForeignCount
        )

        if (!wasBusy && isNowBusy) {
            Log.d(TAG, "Yielding microphone to external app/call: $reason (foreign=$effectiveForeignCount)")
            ownSttAudioSessionIds.clear()
            mainHandler.post {
                for (listener in listeners) {
                    try {
                        listener.onExternalMicTaken(reason)
                    } catch (_: Exception) {
                    }
                }
            }
        } else if (wasBusy && !isNowBusy) {
            Log.d(TAG, "External app finished using microphone — resuming Always-On Mic")
            mainHandler.postDelayed({
                if (!_state.value.isExternalMicBusy) {
                    for (listener in listeners) {
                        try {
                            listener.onExternalMicReleased()
                        } catch (_: Exception) {
                        }
                    }
                }
            }, 300L)
        }
    }
}
