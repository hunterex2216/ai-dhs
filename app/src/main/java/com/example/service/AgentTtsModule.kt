package com.example.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TtsModuleState(
    val isAvailable: Boolean = false,
    val isSpeaking: Boolean = false,
    val statusLabel: String = "Gemini Live Native Voice (Android TTS Off)"
)

/**
 * Legacy Android TextToSpeech (TTS) is permanently disabled by user request ("এটাতে আরেকটা কি সিস্টেম যানি আছে text to speech এটা বন্ধ করেন").
 * All voice output is spoken exclusively by the Gemini Multimodal Live 24kHz native audio stream.
 */
class AgentTtsModule(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val onReadyChanged: (isReady: Boolean, languageLabel: String) -> Unit = { _, _ -> },
    @Suppress("UNUSED_PARAMETER") private val onSpeechStarted: (text: String) -> Unit = {},
    private val onSpeechCompleted: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") private val onSpeechError: (errorMessage: String) -> Unit = {}
) {
    private val _state = MutableStateFlow(TtsModuleState())
    val state: StateFlow<TtsModuleState> = _state.asStateFlow()

    var speechRate: Float = 1.0f
        private set

    var pitch: Float = 1.0f
        private set

    var voiceProfile: String = "CHARON_MALE"
        private set

    var preferFemaleVoice: Boolean = false
        private set

    var isMuted: Boolean = true

    init {
        onReadyChanged(false, "Gemini Live Native Voice (Android TTS Off)")
    }

    fun applyVoicePreset(preset: com.example.model.AgentVoicePreset) {
        voiceProfile = preset.id
        preferFemaleVoice = preset.gender == com.example.model.AgentVoiceGender.FEMALE
        pitch = preset.ttsPitch
        speechRate = preset.ttsRate
    }

    fun applyVoiceProfile(profileName: String) {
        voiceProfile = profileName.uppercase().trim()
        preferFemaleVoice = voiceProfile.contains("FEMALE") ||
            voiceProfile.contains("AOEDE") ||
            voiceProfile.contains("KORE") ||
            voiceProfile.contains("LEDA")
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.6f, 1.5f)
    }

    fun setPitch(newPitch: Float) {
        pitch = newPitch.coerceIn(0.45f, 1.45f)
    }

    fun speak(
        @Suppress("UNUSED_PARAMETER") rawText: String,
        @Suppress("UNUSED_PARAMETER") forceSpeakWhenMuted: Boolean = false
    ) {
        // Android TextToSpeech is permanently disabled; complete immediately without robotic speech.
        onSpeechCompleted()
    }

    fun stopSpeaking() {
        onSpeechCompleted()
    }

    fun shutdown() {
        onSpeechCompleted()
    }
}
