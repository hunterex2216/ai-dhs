package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.model.WakeWordPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceBridgeState(
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = true,
    val ttsEnabled: Boolean = false,
    val isTtsReady: Boolean = false,
    val isSttAvailable: Boolean = true,
    val handsFreeContinuousMode: Boolean = true,
    val isHandsFreeContinuous: Boolean = true,
    val ttsLanguageLabel: String = "Gemini Live Native Voice (TTS Off)",
    val languageLabel: String = "বাংলা (bn-BD)",
    val sttLanguageCode: String = "bn-BD",
    val languageCode: String = "bn-BD",
    val rmsLevel: Float = 0f,
    val statusHint: String = "মাইক সর্বদা সক্রিয় — সরাসরি কথা বলুন",
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val voiceProfile: String = "DEEP_JARVIS",
    val partialTranscript: String = "",
    val lastSpokenText: String = "",
    val errorHint: String? = null
)

/**
 * Coordinates `AgentSttModule` (Voice Input) while keeping legacy Android `TextToSpeech`
 * permanently disabled so only Gemini Multimodal Live native 24kHz audio speaks.
 */
class VoiceAgentBridge(
    context: Context,
    private val onWakeWordGreetingTriggered: (rawWakePhrase: String, greetingText: String) -> Unit,
    private val onPartialTranscriptChanged: (partialText: String) -> Unit = {},
    private val onFinalTranscript: (recognizedText: String, wasWokenByCube: Boolean) -> Unit,
    private val onSpeechPlayingStateChanged: (isSpeaking: Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(VoiceBridgeState())
    val state: StateFlow<VoiceBridgeState> = _state.asStateFlow()

    private var listenForCommandAfterGreeting = false
    private var activeSessionWokenByCube = false

    private val ttsModule: AgentTtsModule
    private val sttModule: AgentSttModule

    init {
        ttsModule = AgentTtsModule(
            context = appContext,
            onReadyChanged = { ready, langLabel ->
                _state.value = _state.value.copy(
                    isTtsReady = ready,
                    ttsLanguageLabel = langLabel,
                    isMuted = true,
                    ttsEnabled = false
                )
            },
            onSpeechStarted = { spokenText ->
                _state.value = _state.value.copy(
                    isSpeaking = false,
                    lastSpokenText = spokenText,
                    errorHint = null
                )
            },
            onSpeechCompleted = {
                _state.value = _state.value.copy(isSpeaking = false)
                onSpeechPlayingStateChanged(false)
                handlePostSpeechLifecycle()
            },
            onSpeechError = { errMsg ->
                _state.value = _state.value.copy(
                    isSpeaking = false,
                    errorHint = errMsg
                )
                onSpeechPlayingStateChanged(false)
                handlePostSpeechLifecycle()
            }
        )

        sttModule = AgentSttModule(
            context = appContext,
            isTtsCurrentlySpeaking = { false },
            onPartialSpeechUpdate = { partial ->
                _state.value = _state.value.copy(partialTranscript = partial)
                onPartialTranscriptChanged(partial)
            },
            onFinalSpeechRecognized = { commandText, _, isFollowUp ->
                val woken = isFollowUp || activeSessionWokenByCube
                activeSessionWokenByCube = false
                _state.value = _state.value.copy(
                    partialTranscript = commandText,
                    errorHint = null
                )
                onFinalTranscript(commandText, woken)
            },
            onStateChanged = { sttState ->
                _state.value = _state.value.copy(
                    isListening = sttState.isListening,
                    isSttAvailable = sttState.isRecognitionServiceAvailable,
                    handsFreeContinuousMode = sttState.isHandsFreeContinuous,
                    isHandsFreeContinuous = sttState.isHandsFreeContinuous,
                    sttLanguageCode = sttState.languageCode,
                    languageCode = sttState.languageCode,
                    languageLabel = sttState.languageLabel,
                    rmsLevel = sttState.rmsLevel,
                    statusHint = sttState.statusMessage
                )
            }
        )

        val initialStt = sttModule.state.value
        _state.value = _state.value.copy(
            isSttAvailable = initialStt.isRecognitionServiceAvailable,
            sttLanguageCode = initialStt.languageCode,
            languageCode = initialStt.languageCode,
            languageLabel = initialStt.languageLabel,
            handsFreeContinuousMode = initialStt.isHandsFreeContinuous,
            isHandsFreeContinuous = initialStt.isHandsFreeContinuous,
            speechRate = ttsModule.speechRate,
            pitch = ttsModule.pitch,
            voiceProfile = ttsModule.voiceProfile
        )
    }

    private fun handlePostSpeechLifecycle() {
        val shouldListenForWakeFollowUp = listenForCommandAfterGreeting
        listenForCommandAfterGreeting = false
        val sentinelOn = CubeSentinelBus.state.value.isSentinelActive
        val handsFreeOn = _state.value.handsFreeContinuousMode
        val extState = ExternalMicArbiter.state.value
        if (extState.isGeminiLiveOwningMic || extState.isExternalMicBusy) {
            return
        }
        if (shouldListenForWakeFollowUp || handsFreeOn || sentinelOn) {
            if (sentinelOn) {
                CubeSentinelBus.updatePhase(WakeWordPhase.AWAITING_BOSS_COMMAND)
            }
            mainHandler.postDelayed({
                if (!ExternalMicArbiter.state.value.isGeminiLiveOwningMic &&
                    !ExternalMicArbiter.state.value.isExternalMicBusy &&
                    !_state.value.isSpeaking
                ) {
                    sttModule.startListening(isCommandFollowUp = true)
                }
            }, 220L)
        }
    }

    fun speak(text: String, forceSpeakWhenMuted: Boolean = false) {
        if (text.isNotBlank()) {
            _state.value = _state.value.copy(lastSpokenText = text)
        }
        // Legacy Android TTS is disabled; complete immediately without speaking robotic TTS.
        handlePostSpeechLifecycle()
    }

    fun speakAndThenListen(text: String) {
        if (text.isNotBlank()) {
            _state.value = _state.value.copy(lastSpokenText = text)
        }
        listenForCommandAfterGreeting = true
        handlePostSpeechLifecycle()
    }

    fun replayLastSpoken() {
        // Legacy TTS is disabled
    }

    fun testTtsVoice() {
        // Legacy TTS is disabled
    }

    fun stopSpeaking() {
        listenForCommandAfterGreeting = false
        ttsModule.stopSpeaking()
        _state.value = _state.value.copy(isSpeaking = false)
        onSpeechPlayingStateChanged(false)
    }

    fun toggleTts(): Boolean = false

    fun toggleMute(): Boolean {
        ttsModule.isMuted = true
        _state.value = _state.value.copy(
            isMuted = true,
            ttsEnabled = false,
            isSpeaking = false
        )
        return true
    }

    fun setSpeechRate(rate: Float) {
        ttsModule.setSpeechRate(rate)
        _state.value = _state.value.copy(speechRate = ttsModule.speechRate)
    }

    fun setPitch(newPitch: Float) {
        ttsModule.setPitch(newPitch)
        _state.value = _state.value.copy(pitch = ttsModule.pitch)
    }

    fun applyVoicePreset(preset: com.example.model.AgentVoicePreset) {
        ttsModule.applyVoicePreset(preset)
        _state.value = _state.value.copy(
            voiceProfile = ttsModule.voiceProfile,
            pitch = ttsModule.pitch,
            speechRate = ttsModule.speechRate
        )
    }

    fun applyVoiceProfile(profileName: String) {
        ttsModule.applyVoiceProfile(profileName)
        _state.value = _state.value.copy(
            voiceProfile = ttsModule.voiceProfile,
            pitch = ttsModule.pitch,
            speechRate = ttsModule.speechRate
        )
    }

    fun toggleHandsFreeContinuousMode(): Boolean {
        val next = sttModule.toggleHandsFreeContinuousMode()
        _state.value = _state.value.copy(
            handsFreeContinuousMode = next,
            isHandsFreeContinuous = next
        )
        return next
    }

    fun setHandsFreeContinuousMode(enabled: Boolean) {
        sttModule.setHandsFreeContinuousMode(enabled)
        _state.value = _state.value.copy(
            handsFreeContinuousMode = enabled,
            isHandsFreeContinuous = enabled
        )
    }

    fun startListening(isManualCommandTap: Boolean = true) {
        _state.value = _state.value.copy(errorHint = null)
        sttModule.startListening(isCommandFollowUp = isManualCommandTap)
    }

    fun stopListening(keepSentinelPhase: Boolean = false) {
        sttModule.stopListening()
        if (!keepSentinelPhase && CubeSentinelBus.state.value.isSentinelActive) {
            CubeSentinelBus.updatePhase(WakeWordPhase.LISTENING_FOR_WAKE_WORD)
        }
    }

    fun createSystemVoicePromptIntent(): Intent = sttModule.buildSystemRecognizerFallbackIntent()

    fun onExternalSpeechResult(spokenText: String) {
        sttModule.onExternalActivityResult(spokenText)
    }

    fun toggleLanguage(): String {
        val next = sttModule.cycleLanguage()
        val s = sttModule.state.value
        _state.value = _state.value.copy(
            sttLanguageCode = next,
            languageCode = next,
            languageLabel = s.languageLabel
        )
        return next
    }

    fun setSttLanguage(languageCode: String) {
        sttModule.setLanguage(languageCode)
        val s = sttModule.state.value
        _state.value = _state.value.copy(
            sttLanguageCode = s.languageCode,
            languageCode = s.languageCode,
            languageLabel = s.languageLabel
        )
    }

    fun triggerHeyCubeWakeSequence(inlineCommand: String = "", rawWakePhrase: String = "Hey Cube") {
        activeSessionWokenByCube = true
        if (inlineCommand.isNotBlank()) {
            CubeSentinelBus.updatePhase(WakeWordPhase.PROCESSING_COMMAND)
            onFinalTranscript(inlineCommand, true)
            return
        }
        val greeting = "জি স্যার, বলুন আমি কী করব?"
        listenForCommandAfterGreeting = true
        CubeSentinelBus.updatePhase(WakeWordPhase.GREETING_BOSS)
        onWakeWordGreetingTriggered(rawWakePhrase, greeting)
        handlePostSpeechLifecycle()
    }

    fun release() {
        sttModule.release()
        ttsModule.shutdown()
    }
}
