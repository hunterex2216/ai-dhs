package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "AgentSttModule"
private const val PREFS_NAME = "archer_stt_prefs"
private const val KEY_STT_LANGUAGE = "stt_language_code"
private const val KEY_HANDS_FREE_CONTINUOUS = "stt_hands_free_continuous"
private const val PARTIAL_SILENCE_FINALIZE_MS = 1650L

data class SttEngineState(
    val isListening: Boolean = false,
    val isHandsFreeContinuous: Boolean = true,
    val isPausedForExternalMic: Boolean = false,
    val isRecognitionServiceAvailable: Boolean = true,
    val languageCode: String = "bn-BD",
    val languageLabel: String = "বাংলা (bn-BD)",
    val partialTranscript: String = "",
    val lastFinalTranscript: String = "",
    val rmsLevel: Float = 0f,
    val confidenceScore: Float = 0f,
    val errorCode: Int? = null,
    val statusMessage: String = "মাইক সর্বদা সক্রিয় — সরাসরি কথা বলুন"
)

/**
 * Dedicated Android Speech-to-Text (STT) engine built on [SpeechRecognizer] and [RecognitionListener].
 *
 * Key capabilities:
 * - Always-On Continuous Microphone by default: stays active at all times and automatically restarts
 *   after every utterance, TTS reply, or silence timeout.
 * - Automatic External Microphone Yield via [ExternalMicArbiter]: immediately pauses and releases
 *   the microphone whenever another app or call needs the mic, and automatically resumes the instant
 *   the other app finishes using the mic.
 * - Real-time streaming partial transcripts via [RecognitionListener.onPartialResults].
 * - Automatic partial-transcript recovery & silence debounce auto-finalization.
 */
class AgentSttModule(
    private val context: Context,
    private val isTtsCurrentlySpeaking: () -> Boolean,
    private val onPartialSpeechUpdate: (String) -> Unit,
    private val onFinalSpeechRecognized: (transcript: String, confidence: Float, isFollowUp: Boolean) -> Unit,
    private val onStateChanged: (SttEngineState) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var isCommandFollowUpSession: Boolean = true
    private var bufferedPartialText: String = ""
    private var sessionResultDelivered: Boolean = false
    private var consecutiveErrorCount: Int = 0
    private var lastRecognizerActivityMs: Long = System.currentTimeMillis()
    private var sessionPeakRmsDb: Float = -10f
    private var isExternalManualResult: Boolean = false

    private val externalMicListener = object : ExternalMicArbiter.Listener {
        override fun onExternalMicTaken(reasonBn: String) {
            pauseForExternalMic(reasonBn)
        }

        override fun onExternalMicReleased() {
            updateState {
                copy(
                    isPausedForExternalMic = false,
                    statusMessage = "মাইক পুনরায় সক্রিয় — কথা বলুন..."
                )
            }
            if (!ExternalMicArbiter.state.value.isGeminiLiveOwningMic &&
                !isTtsCurrentlySpeaking() &&
                (_state.value.isHandsFreeContinuous || CubeSentinelBus.state.value.isSentinelActive) &&
                hasRecordAudioPermission()
            ) {
                startListening(isCommandFollowUp = true)
            }
        }
    }

    private val partialDebounceRunnable = Runnable {
        val candidate = bufferedPartialText.trim()
        if (candidate.isNotBlank() && !sessionResultDelivered) {
            Log.d(TAG, "Auto-finalizing buffered partial transcript after silence: $candidate")
            deliverFinalTranscript(candidate, confidence = 0.82f)
        }
    }

    private val autoRestartRunnable = Runnable {
        if (!isTtsCurrentlySpeaking() &&
            !ExternalMicArbiter.state.value.isExternalMicBusy &&
            !ExternalMicArbiter.state.value.isGeminiLiveOwningMic &&
            (_state.value.isHandsFreeContinuous || CubeSentinelBus.state.value.isSentinelActive)
        ) {
            startListening(isCommandFollowUp = isCommandFollowUpSession)
        }
    }

    /**
     * Self-healing Always-On Watchdog: ensures the microphone stays active at all times
     * whenever no external app is using the mic, Gemini Live isn't owning the mic directly,
     * and TTS isn't currently speaking.
     */
    private val alwaysOnWatchdogRunnable = object : Runnable {
        override fun run() {
            val extState = ExternalMicArbiter.state.value
            if (extState.isGeminiLiveOwningMic) {
                // Gemini Live owns the microphone; do not run legacy SpeechRecognizer watchdog
                mainHandler.postDelayed(this, 6000L)
                return
            }
            val shouldBeAlwaysOn = _state.value.isHandsFreeContinuous || CubeSentinelBus.state.value.isSentinelActive
            if (shouldBeAlwaysOn &&
                !extState.isExternalMicBusy &&
                !isTtsCurrentlySpeaking() &&
                hasRecordAudioPermission()
            ) {
                val now = System.currentTimeMillis()
                val idleOrDead = (!_state.value.isListening || speechRecognizer == null) &&
                    (now - lastRecognizerActivityMs > 1800L)
                val stalledTooLong = _state.value.isListening &&
                    bufferedPartialText.isBlank() &&
                    (now - lastRecognizerActivityMs > 28_000L)

                if (idleOrDead || stalledTooLong) {
                    startListeningOnMainThread(isCommandFollowUp = true)
                }
            }
            mainHandler.postDelayed(this, 3500L)
        }
    }

    private val _state = MutableStateFlow(
        SttEngineState(
            isHandsFreeContinuous = false,
            isRecognitionServiceAvailable = runCatching {
                SpeechRecognizer.isRecognitionAvailable(appContext)
            }.getOrDefault(true),
            languageCode = prefs.getString(KEY_STT_LANGUAGE, "bn-BD") ?: "bn-BD",
            languageLabel = formatLanguageLabel(prefs.getString(KEY_STT_LANGUAGE, "bn-BD") ?: "bn-BD")
        )
    )
    val state: StateFlow<SttEngineState> = _state.asStateFlow()

    init {
        ExternalMicArbiter.init(appContext)
        ExternalMicArbiter.addListener(externalMicListener)
        mainHandler.postDelayed(alwaysOnWatchdogRunnable, 4000L)
    }

    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun setHandsFreeContinuousMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HANDS_FREE_CONTINUOUS, enabled).apply()
        updateState {
            copy(
                isHandsFreeContinuous = enabled,
                statusMessage = if (enabled) {
                    "হ্যান্ডস-ফ্রি মাইক সর্বদা সক্রিয় — সরাসরি কথা বলুন"
                } else {
                    "হ্যান্ডস-ফ্রি মোড বন্ধ — মাইকে ট্যাপ করে কথা বলুন"
                }
            )
        }
        if (enabled &&
            hasRecordAudioPermission() &&
            !isTtsCurrentlySpeaking() &&
            !ExternalMicArbiter.state.value.isExternalMicBusy &&
            !ExternalMicArbiter.state.value.isGeminiLiveOwningMic
        ) {
            startListening(isCommandFollowUp = true)
        } else if (!enabled && !CubeSentinelBus.state.value.isSentinelActive) {
            stopListening()
        }
    }

    fun toggleHandsFreeContinuousMode(): Boolean {
        val next = !_state.value.isHandsFreeContinuous
        setHandsFreeContinuousMode(next)
        return next
    }

    fun cycleLanguage(): String {
        val nextLang = when (_state.value.languageCode) {
            "bn-BD" -> "en-US"
            "en-US" -> "hi-IN"
            else -> "bn-BD"
        }
        setLanguage(nextLang)
        return nextLang
    }

    fun setLanguage(languageCode: String) {
        val normalized = when {
            languageCode.startsWith("bn", ignoreCase = true) -> "bn-BD"
            languageCode.startsWith("hi", ignoreCase = true) -> "hi-IN"
            else -> "en-US"
        }
        prefs.edit().putString(KEY_STT_LANGUAGE, normalized).apply()
        val label = formatLanguageLabel(normalized)
        updateState {
            copy(
                languageCode = normalized,
                languageLabel = label,
                statusMessage = "ভয়েস ভাষা: $label"
            )
        }
        if (_state.value.isListening) {
            startListening(isCommandFollowUp = isCommandFollowUpSession)
        }
    }

    fun startListening(isCommandFollowUp: Boolean = true) {
        mainHandler.post {
            startListeningOnMainThread(isCommandFollowUp)
        }
    }

    private fun startListeningOnMainThread(isCommandFollowUp: Boolean) {
        mainHandler.removeCallbacks(partialDebounceRunnable)
        mainHandler.removeCallbacks(autoRestartRunnable)

        // If Gemini Live WebSocket is active, it owns the mic directly via AudioRecord
        if (ExternalMicArbiter.state.value.isGeminiLiveOwningMic) {
            return
        }

        // If another app or phone call is currently using the microphone, stay paused until it finishes
        if (ExternalMicArbiter.state.value.isExternalMicBusy) {
            val reason = ExternalMicArbiter.state.value.busyReasonBn
                .ifBlank { "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে" }
            destroyRecognizerQuietly()
            updateState {
                copy(
                    isListening = false,
                    isPausedForExternalMic = true,
                    rmsLevel = 0f,
                    statusMessage = reason
                )
            }
            return
        }

        if (!hasRecordAudioPermission()) {
            updateState {
                copy(
                    isListening = false,
                    rmsLevel = 0f,
                    statusMessage = "মাইক্রোফোন পারমিশন প্রয়োজন — মাইকে ট্যাপ করে Allow করুন"
                )
            }
            return
        }

        val available = runCatching {
            SpeechRecognizer.isRecognitionAvailable(appContext)
        }.getOrDefault(false)

        if (!available) {
            updateState {
                copy(
                    isListening = false,
                    isRecognitionServiceAvailable = false,
                    rmsLevel = 0f,
                    statusMessage = "অন-ডিভাইস ভয়েস সার্ভিস অনুপস্থিত — সিস্টেম ভয়েস পপআপ বা টাইপিং ব্যবহার করুন"
                )
            }
            return
        }

        isCommandFollowUpSession = isCommandFollowUp
        bufferedPartialText = ""
        sessionResultDelivered = false
        sessionPeakRmsDb = -10f
        isExternalManualResult = false
        lastRecognizerActivityMs = System.currentTimeMillis()

        destroyRecognizerQuietly()

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
            speechRecognizer = recognizer
            recognizer.setRecognitionListener(createRecognitionListener())

            val selectedLang = _state.value.languageCode
            val noiseCancelLock = AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
            val recognizeIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLang)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, selectedLang)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (noiseCancelLock) 1350L else 2600L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (noiseCancelLock) 950L else 2000L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    if (noiseCancelLock) 1100L else 1800L
                )
            }

            UnifiedAudioRouterBus.prepareForSttInput()
            ExternalMicArbiter.notifySttStarting()
            recognizer.startListening(recognizeIntent)
            updateState {
                copy(
                    isListening = true,
                    isPausedForExternalMic = false,
                    isRecognitionServiceAvailable = true,
                    partialTranscript = "",
                    errorCode = null,
                    statusMessage = when {
                        isCommandFollowUp -> "জি স্যার, মাইক সক্রিয় (${_state.value.languageLabel})... আপনার আদেশ বলুন"
                        _state.value.isHandsFreeContinuous -> "মাইক সর্বদা সক্রিয় — কথা বললেই কমান্ড চলবে"
                        else -> "Hey Cube গার্ড সক্রিয় — 'Hey Cube' বলুন অথবা আদেশ দিন"
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start SpeechRecognizer: ${e.message}", e)
            ExternalMicArbiter.notifySttStopped()
            updateState {
                copy(
                    isListening = false,
                    rmsLevel = 0f,
                    statusMessage = "ভয়েস রিকগনিশন রিসেট হচ্ছে..."
                )
            }
            scheduleContinuousRestartIfNeeded(delayMs = 500L)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                consecutiveErrorCount = 0
                lastRecognizerActivityMs = System.currentTimeMillis()
                ExternalMicArbiter.notifySttReadyForSpeech()
                val sentinelActive = CubeSentinelBus.state.value.isSentinelActive
                updateState {
                    copy(
                        isListening = true,
                        isPausedForExternalMic = false,
                        errorCode = null,
                        statusMessage = when {
                            isCommandFollowUpSession -> "জি স্যার, শুনছি... এখন আপনার কথা বলুন"
                            isHandsFreeContinuous -> "মাইক সর্বদা সক্রিয় — সরাসরি বলুন..."
                            sentinelActive -> "Hey Cube গার্ড সক্রিয় — 'Hey Cube' বলে ডাকুন..."
                            else -> "শুনছি... এখন আপনার কথা বলুন"
                        }
                    )
                }
            }

            override fun onBeginningOfSpeech() {
                lastRecognizerActivityMs = System.currentTimeMillis()
                mainHandler.removeCallbacks(partialDebounceRunnable)
                updateState {
                    copy(
                        isListening = true,
                        statusMessage = "কথা শনাক্ত হচ্ছে..."
                    )
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (rmsdB > sessionPeakRmsDb) {
                    sessionPeakRmsDb = rmsdB
                }
                val normalized = ((rmsdB + 3.5f) / 10f).coerceIn(0.08f, 1f)
                updateState { copy(rmsLevel = normalized) }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                lastRecognizerActivityMs = System.currentTimeMillis()
                updateState {
                    copy(
                        rmsLevel = 0f,
                        statusMessage = "আপনার কথা প্রসেস করা হচ্ছে..."
                    )
                }
                if (bufferedPartialText.isNotBlank() && !sessionResultDelivered) {
                    mainHandler.removeCallbacks(partialDebounceRunnable)
                    mainHandler.postDelayed(partialDebounceRunnable, 900L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                lastRecognizerActivityMs = System.currentTimeMillis()
                val partialList = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestPartial = partialList?.firstOrNull().orEmpty().trim()
                if (bestPartial.isNotBlank()) {
                    val noiseCancelLock = AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
                    // Ignore distant low-energy background room chatter when Noise Cancellation is ON
                    if (noiseCancelLock && sessionPeakRmsDb in -9.5f..2.1f) {
                        return
                    }
                    bufferedPartialText = bestPartial
                    updateState {
                        copy(
                            isListening = true,
                            partialTranscript = bestPartial,
                            statusMessage = "শুনছি: \"$bestPartial\""
                        )
                    }
                    onPartialSpeechUpdate(bestPartial)

                    mainHandler.removeCallbacks(partialDebounceRunnable)
                    val silenceWait = if (noiseCancelLock) 1050L else PARTIAL_SILENCE_FINALIZE_MS
                    mainHandler.postDelayed(partialDebounceRunnable, silenceWait)
                }
            }

            override fun onResults(results: Bundle?) {
                lastRecognizerActivityMs = System.currentTimeMillis()
                mainHandler.removeCallbacks(partialDebounceRunnable)
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val bestMatch = matches?.firstOrNull().orEmpty().trim()
                    .ifBlank { bufferedPartialText.trim() }
                val topConfidence = confidenceScores?.firstOrNull()?.takeIf { it > 0f } ?: 0.92f

                if (bestMatch.isNotBlank()) {
                    deliverFinalTranscript(bestMatch, topConfidence)
                } else {
                    destroyRecognizerQuietly()
                    updateState {
                        copy(
                            isListening = false,
                            rmsLevel = 0f,
                            statusMessage = "মাইক সক্রিয় — কথা বলুন..."
                        )
                    }
                    scheduleContinuousRestartIfNeeded(delayMs = 350L)
                }
            }

            override fun onError(error: Int) {
                lastRecognizerActivityMs = System.currentTimeMillis()
                mainHandler.removeCallbacks(partialDebounceRunnable)

                val recoveredText = bufferedPartialText.trim()
                if (recoveredText.isNotBlank() && !sessionResultDelivered) {
                    Log.d(TAG, "Recovering spoken command from partial transcript on error($error): $recoveredText")
                    deliverFinalTranscript(recoveredText, confidence = 0.78f)
                    return
                }

                destroyRecognizerQuietly()

                // If another app just requested/took the microphone, yield until it finishes!
                if (error == SpeechRecognizer.ERROR_AUDIO ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                    error == SpeechRecognizer.ERROR_CLIENT
                ) {
                    mainHandler.postDelayed({
                        val externalTookMic = ExternalMicArbiter.checkAndLatchExternalMicAfterSttError()
                        if (externalTookMic) {
                            updateState {
                                copy(
                                    isListening = false,
                                    isPausedForExternalMic = true,
                                    rmsLevel = 0f,
                                    errorCode = error,
                                    statusMessage = ExternalMicArbiter.state.value.busyReasonBn
                                        .ifBlank { "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে" }
                                )
                            }
                        } else if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            consecutiveErrorCount = (consecutiveErrorCount + 1).coerceAtMost(6)
                            updateState {
                                copy(
                                    isListening = false,
                                    rmsLevel = 0f,
                                    errorCode = error,
                                    statusMessage = mapSpeechErrorToMessage(error)
                                )
                            }
                            scheduleContinuousRestartIfNeeded(delayMs = 450L)
                        }
                    }, 280L)
                    return
                }

                consecutiveErrorCount = (consecutiveErrorCount + 1).coerceAtMost(6)
                val friendlyError = mapSpeechErrorToMessage(error)
                updateState {
                    copy(
                        isListening = false,
                        rmsLevel = 0f,
                        errorCode = error,
                        statusMessage = friendlyError
                    )
                }

                if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    val backoffDelay = (400L + (consecutiveErrorCount * 150L)).coerceAtMost(1400L)
                    scheduleContinuousRestartIfNeeded(delayMs = backoffDelay)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun deliverFinalTranscript(transcript: String, confidence: Float) {
        if (sessionResultDelivered) return
        val noiseCancelLock = AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
        // If Noise Cancellation / Background Audio Ignore is ON and this speech came from quiet/distant background chatter, reject it!
        if (noiseCancelLock && !isExternalManualResult && sessionPeakRmsDb in -9.5f..2.1f) {
            Log.d(TAG, "Ignored distant background chatter (peakRmsDb=$sessionPeakRmsDb): $transcript")
            sessionResultDelivered = true
            bufferedPartialText = ""
            destroyRecognizerQuietly()
            updateState {
                copy(
                    isListening = false,
                    rmsLevel = 0f,
                    partialTranscript = "",
                    statusMessage = "ব্যাকগ্রাউন্ডের কথা ইগনোর করা হয়েছে — কাছে থেকে বলুন..."
                )
            }
            scheduleContinuousRestartIfNeeded(delayMs = 350L)
            return
        }

        sessionResultDelivered = true
        bufferedPartialText = ""
        lastRecognizerActivityMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(partialDebounceRunnable)

        val cleaned = transcript.trim()
        val wasFollowUp = isCommandFollowUpSession
        isCommandFollowUpSession = true

        destroyRecognizerQuietly()

        updateState {
            copy(
                isListening = false,
                rmsLevel = 0f,
                partialTranscript = cleaned,
                lastFinalTranscript = cleaned,
                confidenceScore = confidence,
                errorCode = null,
                statusMessage = "কমান্ড গৃহীত: \"$cleaned\""
            )
        }

        onFinalSpeechRecognized(cleaned, confidence, wasFollowUp)

        // Safety fallback: if TTS does not start within 2.6s (e.g., muted or silent action),
        // automatically resume listening so the mic stays active all the time.
        scheduleContinuousRestartIfNeeded(delayMs = 2600L)
    }

    fun onExternalActivityResult(recognizedText: String) {
        val cleaned = recognizedText.trim()
        if (cleaned.isBlank()) return
        sessionResultDelivered = false
        isExternalManualResult = true
        sessionPeakRmsDb = 10f
        deliverFinalTranscript(cleaned, confidence = 0.95f)
    }

    fun buildSystemRecognizerFallbackIntent(): Intent {
        val selectedLang = _state.value.languageCode
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, selectedLang)
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "জি স্যার, আপনার আদেশ বলুন (বাংলা / English / हिन्दी)"
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    fun scheduleContinuousRestartIfNeeded(delayMs: Long = 400L) {
        mainHandler.removeCallbacks(autoRestartRunnable)
        if (!ExternalMicArbiter.state.value.isExternalMicBusy &&
            !ExternalMicArbiter.state.value.isGeminiLiveOwningMic &&
            (_state.value.isHandsFreeContinuous || CubeSentinelBus.state.value.isSentinelActive)
        ) {
            mainHandler.postDelayed(autoRestartRunnable, delayMs)
        }
    }

    fun pauseForExternalMic(reasonBn: String) {
        mainHandler.removeCallbacks(partialDebounceRunnable)
        mainHandler.removeCallbacks(autoRestartRunnable)
        bufferedPartialText = ""
        destroyRecognizerQuietly()
        updateState {
            copy(
                isListening = false,
                isPausedForExternalMic = true,
                rmsLevel = 0f,
                statusMessage = reasonBn.ifBlank {
                    "অন্য জায়গায় মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে"
                }
            )
        }
    }

    fun pauseForTtsOutput() {
        mainHandler.removeCallbacks(partialDebounceRunnable)
        mainHandler.removeCallbacks(autoRestartRunnable)
        lastRecognizerActivityMs = System.currentTimeMillis()
        if (_state.value.isListening || speechRecognizer != null) {
            destroyRecognizerQuietly()
            updateState {
                copy(
                    isListening = false,
                    rmsLevel = 0f
                )
            }
        }
    }

    fun stopListening() {
        mainHandler.removeCallbacks(partialDebounceRunnable)
        mainHandler.removeCallbacks(autoRestartRunnable)
        bufferedPartialText = ""
        destroyRecognizerQuietly()
        updateState {
            copy(
                isListening = false,
                rmsLevel = 0f,
                statusMessage = if (ExternalMicArbiter.state.value.isGeminiLiveOwningMic) {
                    "Gemini Live মাইক সক্রিয়"
                } else {
                    "ভয়েস মাইক প্রস্তুত — কথা বলুন"
                }
            )
        }
    }

    fun release() {
        ExternalMicArbiter.removeListener(externalMicListener)
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizerQuietly()
    }

    private fun destroyRecognizerQuietly() {
        ExternalMicArbiter.notifySttStopped()
        val current = speechRecognizer
        speechRecognizer = null
        if (current != null) {
            try {
                current.stopListening()
            } catch (_: Exception) {
            }
            try {
                current.cancel()
            } catch (_: Exception) {
            }
            try {
                current.destroy()
            } catch (_: Exception) {
            }
        }
    }

    private fun mapSpeechErrorToMessage(error: Int): String {
        val autoLoop = _state.value.isHandsFreeContinuous || CubeSentinelBus.state.value.isSentinelActive
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> {
                if (autoLoop) "মাইক সর্বদা সক্রিয় — আপনার কথা বলুন..."
                else "কথাটি স্পষ্ট বোঝা যায়নি — আবার মাইকে বলুন"
            }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (autoLoop) "মাইক সর্বদা শুনছে — যেকোনো সময় কথা বলুন..."
                else "কোনো শব্দ পাওয়া যায়নি — মাইকে ট্যাপ করে আবার বলুন"
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "নেটওয়ার্ক ধীরগতির — আবার চেষ্টা করা হচ্ছে"
            SpeechRecognizer.ERROR_AUDIO -> "অন্য অ্যাপে মাইক চেক করা হচ্ছে..."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ভয়েস ইঞ্জিন প্রস্তুত হচ্ছে — কথা বলুন"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোন পারমিশন নেই — Allow করুন"
            SpeechRecognizer.ERROR_CLIENT -> {
                if (autoLoop) "মাইক সর্বদা সক্রিয়..."
                else "মাইকে ট্যাপ করে আপনার আদেশ বলুন"
            }
            else -> "মাইক সক্রিয় — কথা বলুন অথবা নিচে টাইপ করুন"
        }
    }

    private fun formatLanguageLabel(code: String): String {
        return when {
            code.startsWith("bn", ignoreCase = true) -> "বাংলা (bn-BD)"
            code.startsWith("hi", ignoreCase = true) -> "हिन्दी (hi-IN)"
            else -> "English (en-US)"
        }
    }

    private inline fun updateState(transform: SttEngineState.() -> SttEngineState) {
        val next = _state.value.transform()
        _state.value = next
        onStateChanged(next)
    }
}
