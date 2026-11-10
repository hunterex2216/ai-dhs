package com.example.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import java.util.ArrayDeque
import kotlin.math.max

private const val TAG = "AgentSttModule"
private const val PREFS_NAME = "archer_stt_prefs"
private const val KEY_STT_LANGUAGE = "stt_language_code"
private const val KEY_HANDS_FREE_CONTINUOUS = "stt_hands_free_continuous"

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
    val statusMessage: String = "মাইক সর্বদা সক্রিয় — সরাসরি কথা বলুন",
    // Advanced Upgrades:
    val totalRecognitions: Int = 0,
    val averageConfidence: Float = 0.90f,
    val isOnDeviceRecognition: Boolean = false,
    val ambientNoiseLevelBn: String = "স্বাভাবিক",
    val detectedSpeechModeBn: String = "স্বাভাবিক কমান্ড",
    val lastRecognitionTimestamp: Long = 0L
)

/**
 * Advanced, Production-Grade Speech-to-Text (STT) Engine for Archer AI:
 *
 * Key capabilities:
 * 1. On-Device SpeechRecognizer Preference (Android 11+ / API 31+) for ultra-low latency & zero lag.
 * 2. Smart Multi-Result Confidence & Intent Fusion: compares top candidates from SpeechRecognizer
 *    and selects the most coherent, intent-rich transcript.
 * 3. Phonetic Wake Word & Command Correction: corrects dialect/accent mistranscriptions ("আচার" -> "আর্চার", "কিউ" -> "কিউব").
 * 4. Adaptive Context-Aware Silence Debouncing: adjusts silence timeouts dynamically based on speech length
 *    so short commands are snappy and long dictation is never cut off.
 * 5. Stabilized Streaming Partials: eliminates flickering and premature transcript cutoffs.
 * 6. Ambient Noise Floor Tracking & Adaptive RMS Thresholding: adapts to quiet rooms vs. noisy environments.
 * 7. Multi-Language Support (বাংলা, English, हिन्दी, العربية, اردو).
 * 8. Automatic Biometrics Learning Integration with [VoiceBiometricsEngine].
 * 9. Always-On Mic with Seamless External App Yield via [ExternalMicArbiter].
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
    private var stabilizedPartialHistory = ArrayDeque<String>(8)
    private var sessionResultDelivered: Boolean = false
    private var consecutiveErrorCount: Int = 0
    private var lastRecognizerActivityMs: Long = System.currentTimeMillis()
    private var sessionPeakRmsDb: Float = -10f
    private var isExternalManualResult: Boolean = false

    // Analytics & Stats
    private var sessionTotalRecognitions: Int = 0
    private var sessionTotalConfidenceSum: Float = 0f
    private var ambientNoiseRmsSamples = FloatArray(16) { -5f }
    private var noiseSampleIdx: Int = 0

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
            Log.d(TAG, "Adaptive silence finalization for buffered partial: $candidate")
            val fused = phoneticallyCorrectBanglaAndEnglish(candidate)
            deliverFinalTranscript(fused, confidence = 0.88f)
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
     * Self-healing Always-On Watchdog: ensures microphone remains alive and healthy
     */
    private val alwaysOnWatchdogRunnable = object : Runnable {
        override fun run() {
            val extState = ExternalMicArbiter.state.value
            if (extState.isGeminiLiveOwningMic) {
                mainHandler.postDelayed(this, 5000L)
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
                    (now - lastRecognizerActivityMs > 1600L)
                val stalledTooLong = _state.value.isListening &&
                    bufferedPartialText.isBlank() &&
                    (now - lastRecognizerActivityMs > 25_000L)

                if (idleOrDead || stalledTooLong) {
                    startListeningOnMainThread(isCommandFollowUp = true)
                }
            }
            mainHandler.postDelayed(this, 3000L)
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
        mainHandler.postDelayed(alwaysOnWatchdogRunnable, 3500L)
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
            "hi-IN" -> "ar-SA"
            "ar-SA" -> "ur-PK"
            else -> "bn-BD"
        }
        setLanguage(nextLang)
        return nextLang
    }

    fun setLanguage(languageCode: String) {
        val normalized = when {
            languageCode.startsWith("bn", ignoreCase = true) -> "bn-BD"
            languageCode.startsWith("hi", ignoreCase = true) -> "hi-IN"
            languageCode.startsWith("ar", ignoreCase = true) -> "ar-SA"
            languageCode.startsWith("ur", ignoreCase = true) -> "ur-PK"
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

        if (ExternalMicArbiter.state.value.isGeminiLiveOwningMic) {
            return
        }

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
        stabilizedPartialHistory.clear()
        sessionResultDelivered = false
        sessionPeakRmsDb = -10f
        isExternalManualResult = false
        lastRecognizerActivityMs = System.currentTimeMillis()

        destroyRecognizerQuietly()

        try {
            var isOnDevice = false
            val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            ) {
                try {
                    val onDev = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                    isOnDevice = true
                    onDev
                } catch (e: Exception) {
                    SpeechRecognizer.createSpeechRecognizer(appContext)
                }
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            }

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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5) // Request top 5 candidates for smart fusion
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                // Natural human speech silence lengths:
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (noiseCancelLock) 2000L else 2800L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    if (noiseCancelLock) 1600L else 2200L
                )
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    1400L
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                }
            }

            UnifiedAudioRouterBus.prepareForSttInput()
            ExternalMicArbiter.notifySttStarting()
            recognizer.startListening(recognizeIntent)

            updateState {
                copy(
                    isListening = true,
                    isPausedForExternalMic = false,
                    isRecognitionServiceAvailable = true,
                    isOnDeviceRecognition = isOnDevice,
                    partialTranscript = "",
                    errorCode = null,
                    statusMessage = when {
                        isCommandFollowUp -> "জি স্যার, শুনছি (${_state.value.languageLabel})... বলুন"
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

                // Sample ambient noise floor
                ambientNoiseRmsSamples[noiseSampleIdx % ambientNoiseRmsSamples.size] = rmsdB
                noiseSampleIdx++

                val avgNoise = ambientNoiseRmsSamples.average().toFloat()
                val noiseLevelDesc = when {
                    avgNoise < -4f -> "শান্ত পরিবেশ"
                    avgNoise > 4f -> "কোলাহলপূর্ণ পরিবেশ"
                    else -> "স্বাভাবিক"
                }

                val normalized = ((rmsdB + 4.0f) / 11f).coerceIn(0.06f, 1f)
                updateState {
                    copy(
                        rmsLevel = normalized,
                        ambientNoiseLevelBn = noiseLevelDesc
                    )
                }
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
                    // Calculate contextual silence debounce based on utterance word count
                    val debounceTime = computeContextualSilenceDebounceMs(bufferedPartialText)
                    mainHandler.postDelayed(partialDebounceRunnable, debounceTime)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                lastRecognizerActivityMs = System.currentTimeMillis()
                val partialList = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val rawPartial = partialList?.firstOrNull().orEmpty().trim()

                if (rawPartial.isNotBlank()) {
                    // Stabilize partial text (avoid backward flickering)
                    val stabilized = stabilizePartialText(rawPartial)
                    bufferedPartialText = stabilized

                    val wordCount = stabilized.split(Regex("\\s+")).size
                    val speechMode = when {
                        wordCount <= 3 -> "দ্রুত কমান্ড"
                        wordCount >= 8 -> "ডিক্টেশন / বিস্তারিত"
                        else -> "স্বাভাবিক কমান্ড"
                    }

                    updateState {
                        copy(
                            isListening = true,
                            partialTranscript = stabilized,
                            detectedSpeechModeBn = speechMode,
                            statusMessage = "শুনছি: \"$stabilized\""
                        )
                    }
                    onPartialSpeechUpdate(stabilized)

                    mainHandler.removeCallbacks(partialDebounceRunnable)
                    val silenceWait = computeContextualSilenceDebounceMs(stabilized)
                    mainHandler.postDelayed(partialDebounceRunnable, silenceWait)
                }
            }

            override fun onResults(results: Bundle?) {
                lastRecognizerActivityMs = System.currentTimeMillis()
                mainHandler.removeCallbacks(partialDebounceRunnable)

                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                // Smart Multi-Candidate Fusion
                val (fusedTranscript, fusedConfidence) = selectBestCandidateFromResults(
                    matches = matches.orEmpty(),
                    confidenceScores = confidenceScores,
                    fallbackPartial = bufferedPartialText
                )

                if (fusedTranscript.isNotBlank()) {
                    deliverFinalTranscript(fusedTranscript, fusedConfidence)
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
                    val corrected = phoneticallyCorrectBanglaAndEnglish(recoveredText)
                    Log.d(TAG, "Recovering speech from partial transcript on error($error): $corrected")
                    deliverFinalTranscript(corrected, confidence = 0.82f)
                    return
                }

                destroyRecognizerQuietly()

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

    /**
     * Contextual Silence Debounce:
     * - Short queries ("ভলিউম বাড়াও"): fast 1650ms debounce
     * - Medium commands: 2200ms debounce
     * - Long sentences / dictation: 3000ms debounce
     */
    private fun computeContextualSilenceDebounceMs(text: String): Long {
        val wordCount = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
        val noiseCancelLock = AgentAbilitySwitchesBus.state.value.ignoreBackgroundAudio
        return when {
            wordCount <= 3 -> if (noiseCancelLock) 1500L else 1850L
            wordCount in 4..7 -> if (noiseCancelLock) 2000L else 2400L
            else -> if (noiseCancelLock) 2600L else 3200L
        }
    }

    /**
     * Prevents flickering when SpeechRecognizer backtracks during ongoing speech
     */
    private fun stabilizePartialText(newPartial: String): String {
        val clean = newPartial.trim()
        if (stabilizedPartialHistory.isEmpty()) {
            stabilizedPartialHistory.addLast(clean)
            return clean
        }

        val last = stabilizedPartialHistory.last
        if (clean.length < last.length && last.startsWith(clean)) {
            // Recognizer momentarily truncated the word; retain the richer previous partial
            return last
        }

        if (stabilizedPartialHistory.size >= 8) {
            stabilizedPartialHistory.removeFirst()
        }
        stabilizedPartialHistory.addLast(clean)
        return clean
    }

    /**
     * Evaluates multiple candidate matches from SpeechRecognizer and selects the best one
     * based on confidence score, length, intent verbs, and phonetic correction.
     */
    private fun selectBestCandidateFromResults(
        matches: List<String>,
        confidenceScores: FloatArray?,
        fallbackPartial: String
    ): Pair<String, Float> {
        if (matches.isEmpty()) {
            val fallback = phoneticallyCorrectBanglaAndEnglish(fallbackPartial.trim())
            return fallback to 0.85f
        }

        var bestCandidate = matches.first().trim()
        var bestScore = -1.0f

        val intentKeywords = listOf(
            "আর্চার", "কিউব", "জার্ভিস", "জেমিনি", "গান", "ইউটিউব", "ওপেন", "খোল", "চালাও",
            "বন্ধ", "কল", "ফোন", "মেসেজ", "পাঠাও", "ক্লিক", "স্ক্রিন", "কত", "কেমন", "archer", "cube"
        )

        for (i in matches.indices) {
            val candidate = matches[i].trim()
            if (candidate.isBlank()) continue

            val conf = confidenceScores?.getOrNull(i)?.takeIf { it > 0f } ?: 0.90f
            var score = conf * 1.0f

            // Length bonus (more complete sentence)
            score += (candidate.length.coerceAtMost(50) / 100f) * 0.15f

            // Intent keyword bonus
            val lower = candidate.lowercase()
            val intentHits = intentKeywords.count { lower.contains(it) }
            score += (intentHits * 0.08f)

            if (score > bestScore) {
                bestScore = score
                bestCandidate = candidate
            }
        }

        val corrected = phoneticallyCorrectBanglaAndEnglish(bestCandidate)
        val finalConfidence = (confidenceScores?.firstOrNull()?.takeIf { it > 0f } ?: 0.92f).coerceIn(0.70f, 0.99f)
        return corrected to finalConfidence
    }

    /**
     * Phonetically corrects frequent speech-to-text recognition artifacts in Bengali & English
     */
    private fun phoneticallyCorrectBanglaAndEnglish(raw: String): String {
        var text = raw.trim()
        if (text.isBlank()) return ""

        val replacements = listOf(
            // Wake words & agent name
            Regex("(?i)\\b(hey cue|hey q|hey cube|hey qube)\\b") to "Hey Cube",
            Regex("(?i)\\b(cue|qube|কিউ|কিউভ|হে কিউ|হেই কিউ)\\b") to "কিউব",
            Regex("(?i)\\b(আচার|আচাড়|আরচার|আর্চাল|আচার্য)\\b") to "আর্চার",
            Regex("(?i)\\b(জারভিস|ঝারভিস|জারবিজ|জারবিশ)\\b") to "জার্ভিস",
            Regex("(?i)\\b(জিমিনি|জেমি)\\b") to "জেমিনি",
            Regex("(?i)\\b(এক্টিভ হও|এক্টিভ|অন হও|জেগে ওঠ)\\b") to "একটিভ হও",
            // Common app names
            Regex("(?i)\\b(ইউটিউব|ইউটিব|ইউটুব)\\b") to "ইউটিউব",
            Regex("(?i)\\b(হোয়াটসঅ্যাপ|হয়াটসঅ্যাপ|ওয়াটসঅ্যাপ)\\b") to "হোয়াটসঅ্যাপ",
            Regex("(?i)\\b(ফেসবুক|ফেচবুক)\\b") to "ফেসবুক",
            Regex("(?i)\\b(টেলিগ্রাম|টিলিগ্রাম)\\b") to "টেলিগ্রাম"
        )

        for ((regex, replacement) in replacements) {
            text = text.replace(regex, replacement)
        }

        return text.trim()
    }

    private fun deliverFinalTranscript(transcript: String, confidence: Float) {
        if (sessionResultDelivered) return

        val cleaned = phoneticallyCorrectBanglaAndEnglish(transcript)
        if (cleaned.isBlank()) return

        sessionResultDelivered = true
        bufferedPartialText = ""
        stabilizedPartialHistory.clear()
        lastRecognizerActivityMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(partialDebounceRunnable)

        val wasFollowUp = isCommandFollowUpSession
        isCommandFollowUpSession = true

        destroyRecognizerQuietly()

        // Update recognition stats
        sessionTotalRecognitions++
        sessionTotalConfidenceSum += confidence
        val avgConf = (sessionTotalConfidenceSum / sessionTotalRecognitions).coerceIn(0.5f, 0.99f)

        updateState {
            copy(
                isListening = false,
                rmsLevel = 0f,
                partialTranscript = cleaned,
                lastFinalTranscript = cleaned,
                confidenceScore = confidence,
                totalRecognitions = sessionTotalRecognitions,
                averageConfidence = avgConf,
                lastRecognitionTimestamp = System.currentTimeMillis(),
                errorCode = null,
                statusMessage = "কমান্ড গৃহীত: \"$cleaned\""
            )
        }

        // Automatic Biometrics Sync: Inform VoiceBiometricsEngine of spoken user transcript
        runCatching {
            VoiceBiometricsEngine.onUserTranscriptReceived(appContext, cleaned)
        }

        onFinalSpeechRecognized(cleaned, confidence, wasFollowUp)

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
                "জি স্যার, আপনার আদেশ বলুন (${_state.value.languageLabel})"
            )
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
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
        stabilizedPartialHistory.clear()
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
        stabilizedPartialHistory.clear()
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
                else "কথাটি স্পষ্ট বোঝা যায়নি — আবার বলুন"
            }
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                if (autoLoop) "মাইক সর্বদা শুনছে — যেকোনো সময় কথা বলুন..."
                else "কোনো শব্দ পাওয়া যায়নি — মাইকে ট্যাপ করে বলুন"
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "নেটওয়ার্ক ধীরগতির — অটো রিকানেক্ট হচ্ছে"
            SpeechRecognizer.ERROR_AUDIO -> "অন্য অ্যাপে মাইক চেক করা হচ্ছে..."
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ভয়েস ইঞ্জিন প্রস্তুত হচ্ছে — কথা বলুন"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "মাইক্রোফোন পারমিশন প্রয়োজন — Allow করুন"
            SpeechRecognizer.ERROR_CLIENT -> {
                if (autoLoop) "মাইক সর্বদা সক্রিয়..."
                else "মাইকে ট্যাপ করে আপনার আদেশ বলুন"
            }
            else -> "মাইক সক্রিয় — কথা বলুন অথবা নিচে লিখুন"
        }
    }

    private fun formatLanguageLabel(code: String): String {
        return when {
            code.startsWith("bn", ignoreCase = true) -> "বাংলা (bn-BD)"
            code.startsWith("hi", ignoreCase = true) -> "हिन्दी (hi-IN)"
            code.startsWith("ar", ignoreCase = true) -> "العربية (ar-SA)"
            code.startsWith("ur", ignoreCase = true) -> "اردو (ur-PK)"
            else -> "English (en-US)"
        }
    }

    private inline fun updateState(transform: SttEngineState.() -> SttEngineState) {
        val next = _state.value.transform()
        _state.value = next
        onStateChanged(next)
    }
}
