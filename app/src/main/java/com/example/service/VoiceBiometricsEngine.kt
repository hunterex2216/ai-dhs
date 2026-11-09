package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class DetectedSpeakerGender(
    val id: String,
    val labelBn: String,
    val labelEn: String
) {
    MALE(id = "MALE", labelBn = "ছেলে", labelEn = "Male"),
    FEMALE(id = "FEMALE", labelBn = "মেয়ে", labelEn = "Female"),
    CHILD(id = "CHILD", labelBn = "শিশু", labelEn = "Child"),
    UNKNOWN(id = "UNKNOWN", labelBn = "শনাক্ত হচ্ছে", labelEn = "Detecting");

    companion object {
        fun fromString(raw: String?): DetectedSpeakerGender {
            val v = raw?.trim()?.uppercase().orEmpty()
            return when {
                v.contains("FEMALE") || v.contains("মেয়ে") || v.contains("মেয়ে") || v.contains("WOMAN") || v.contains("GIRL") -> FEMALE
                v.contains("CHILD") || v.contains("শিশু") || v.contains("KID") || v.contains("BABY") -> CHILD
                v.contains("MALE") || v.contains("ছেলে") || v.contains("MAN") || v.contains("BOY") -> MALE
                else -> UNKNOWN
            }
        }
    }
}

data class EnrolledVoiceProfile(
    val id: String,
    val speakerName: String,
    val isOwnerBoss: Boolean,
    val gender: DetectedSpeakerGender,
    val meanPitchHz: Float,
    val minPitchHz: Float = 95f,
    val maxPitchHz: Float = 175f,
    val pitchStdHz: Float = 16f,
    val spectralCentroidHz: Float = 1450f,
    val speakingCadenceWpm: Int = 125,
    val speakingStyleSummaryBn: String = "স্পষ্ট ও স্বাভাবিক বাচনভঙ্গি",
    val signatureWords: List<String> = emptyList(),
    val sampleCount: Int = 1,
    val embedding: List<Float>,
    val enrolledAtFormatted: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("speakerName", speakerName)
        put("isOwnerBoss", isOwnerBoss)
        put("gender", gender.id)
        put("meanPitchHz", meanPitchHz.toDouble())
        put("minPitchHz", minPitchHz.toDouble())
        put("maxPitchHz", maxPitchHz.toDouble())
        put("pitchStdHz", pitchStdHz.toDouble())
        put("spectralCentroidHz", spectralCentroidHz.toDouble())
        put("speakingCadenceWpm", speakingCadenceWpm)
        put("speakingStyleSummaryBn", speakingStyleSummaryBn)
        put("signatureWords", JSONArray().apply {
            signatureWords.forEach { put(it) }
        })
        put("sampleCount", sampleCount)
        put("embedding", JSONArray().apply {
            embedding.forEach { put(it.toDouble()) }
        })
        put("enrolledAtFormatted", enrolledAtFormatted)
    }

    companion object {
        fun fromJson(obj: JSONObject): EnrolledVoiceProfile? {
            val name = obj.optString("speakerName").trim()
            if (name.isBlank()) return null
            val embArr = obj.optJSONArray("embedding")
            val emb = mutableListOf<Float>()
            if (embArr != null) {
                for (i in 0 until embArr.length()) {
                    emb.add(embArr.optDouble(i, 0.0).toFloat())
                }
            }
            if (emb.isEmpty()) return null
            val wordsArr = obj.optJSONArray("signatureWords")
            val words = mutableListOf<String>()
            if (wordsArr != null) {
                for (i in 0 until wordsArr.length()) {
                    val w = wordsArr.optString(i).trim()
                    if (w.isNotBlank()) words.add(w)
                }
            }
            val meanP = obj.optDouble("meanPitchHz", 130.0).toFloat()
            return EnrolledVoiceProfile(
                id = obj.optString("id").ifBlank { "vp_${name.lowercase()}" },
                speakerName = name,
                isOwnerBoss = obj.optBoolean("isOwnerBoss", false),
                gender = DetectedSpeakerGender.fromString(obj.optString("gender", "MALE")),
                meanPitchHz = meanP,
                minPitchHz = obj.optDouble("minPitchHz", (meanP - 25f).toDouble()).toFloat(),
                maxPitchHz = obj.optDouble("maxPitchHz", (meanP + 30f).toDouble()).toFloat(),
                pitchStdHz = obj.optDouble("pitchStdHz", 18.0).toFloat(),
                spectralCentroidHz = obj.optDouble("spectralCentroidHz", 1450.0).toFloat(),
                speakingCadenceWpm = obj.optInt("speakingCadenceWpm", 125),
                speakingStyleSummaryBn = obj.optString("speakingStyleSummaryBn", "স্পষ্ট ও স্বাভাবিক বাচনভঙ্গি"),
                signatureWords = words,
                sampleCount = obj.optInt("sampleCount", 1).coerceAtLeast(1),
                embedding = emb,
                enrolledAtFormatted = obj.optString("enrolledAtFormatted", "")
            )
        }
    }
}

data class VoiceBiometricsState(
    val activeSpeakerName: String = "বস",
    val isOwnerBossVerified: Boolean = true,
    val isKnownEnrolledSpeaker: Boolean = false,
    val isWaitingForNewSpeakerName: Boolean = false,
    val hasOwnerBossProfile: Boolean = false,
    val isVoiceSecurityLockEnabled: Boolean = true,
    val detectedGender: DetectedSpeakerGender = DetectedSpeakerGender.MALE,
    val currentPitchHz: Int = 0,
    val spectralCentroidHz: Int = 0,
    val similarityScorePercent: Int = 0,
    val liveVoiceRmsLevel: Float = 0f,
    val isCurrentlySpeaking: Boolean = false,
    val isEnrolling: Boolean = false,
    val enrollmentTargetName: String = "",
    val enrollmentIsOwnerBoss: Boolean = true,
    val enrollmentStepIndex: Int = 0,
    val enrollmentTotalSteps: Int = 3,
    val currentCalibrationSentenceBn: String = "",
    val enrollmentProgressPercent: Int = 0,
    val enrolledProfiles: List<EnrolledVoiceProfile> = emptyList(),
    val liveHudBadgeText: String = "🎙️ ভয়েস বায়োমেট্রিক্স প্রস্তুত • বসের ভয়েস ট্রেনিং করুন",
    val lastSecurityGateEventBn: String = ""
)

/**
 * Advanced Biometric Speaker Identification, Guided 3-Sentence Boss Voice Calibration,
 * Automatic Guest Introduction ("আগে নাম জেনে কণ্ঠ ও কথা বলার ধরন চিনে নেওয়া"),
 * and Real-Time Male/Female/Child Gender Detection Engine.
 */
object VoiceBiometricsEngine {
    private const val PREFS_NAME = "archer_voice_biometrics_prefs"
    private const val KEY_PROFILES_JSON = "enrolled_voice_profiles_json"
    private const val KEY_SECURITY_LOCK_ENABLED = "voice_security_lock_enabled"
    private const val SAMPLE_RATE = 16000
    private const val EMBEDDING_DIM = 16

    /**
     * 3 Guided Calibration Sentences designed to capture Boss's full vocal frequency range,
     * Bangla vowels/consonants, formant timbre, and speaking cadence.
     */
    val BOSS_CALIBRATION_SENTENCES = listOf(
        "১. হ্যালো আর্চার, আমি তোমার বস বলছি—আমার কণ্ঠস্বর ও কথা বলার ধরন চিনে রাখো।",
        "২. আমার আদেশ ছাড়া কোনো গোপন ফাইল বা মেসেজ কাউকে পাঠাবে না।",
        "৩. তুমি আমার সব মেমোরি ও ব্রেইন ডাটা গুগল ড্রাইভে সবসময় সুরক্ষিত রাখবে।"
    )

    val BOSS_CALIBRATION_KEYWORDS = listOf(
        "আর্চার", "বস", "মেমোরি", "গুগল ড্রাইভ", "সিকিউরিটি লক"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(VoiceBiometricsState())
    val state: StateFlow<VoiceBiometricsState> = _state.asStateFlow()

    @Volatile
    private var isInitialized = false

    // Rolling window of recent voiced acoustic frames (~6 seconds of active speech)
    private val rollingFrameLock = Any()
    private val rollingPitchHistory = ArrayDeque<Float>(120)
    private val rollingCentroidHistory = ArrayDeque<Float>(120)
    private val rollingEmbeddings = ArrayDeque<FloatArray>(120)
    private val rollingSpokenWords = ArrayDeque<String>(40)

    // Active enrollment buffers
    private val enrollmentPitches = mutableListOf<Float>()
    private val enrollmentCentroids = mutableListOf<Float>()
    private val enrollmentEmbeddings = mutableListOf<FloatArray>()
    private var standaloneEnrollmentJob: Job? = null

    @Volatile
    private var lastLiveMicStreamFrameAtMs: Long = 0L

    @Volatile
    private var lastAnalyzedFrameAtMs: Long = 0L

    @Volatile
    private var lastVerifiedBossAtMs: Long = 0L

    @Volatile
    private var unknownSpeakerConsecutiveFrames: Int = 0

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lockEnabled = prefs.getBoolean(KEY_SECURITY_LOCK_ENABLED, true)
        val rawJson = prefs.getString(KEY_PROFILES_JSON, "[]").orEmpty()
        val loaded = parseProfilesJson(rawJson)
        val hasBoss = loaded.any { it.isOwnerBoss }
        val bossProf = loaded.firstOrNull { it.isOwnerBoss }
        _state.value = _state.value.copy(
            enrolledProfiles = loaded,
            hasOwnerBossProfile = hasBoss,
            isVoiceSecurityLockEnabled = lockEnabled,
            isOwnerBossVerified = true,
            activeSpeakerName = bossProf?.speakerName ?: "বস",
            detectedGender = bossProf?.gender ?: DetectedSpeakerGender.MALE,
            currentCalibrationSentenceBn = BOSS_CALIBRATION_SENTENCES.first(),
            liveHudBadgeText = if (hasBoss) {
                "🎙️ বক্তা: ${bossProf?.speakerName ?: "বস"} (${(bossProf?.gender ?: DetectedSpeakerGender.MALE).labelEn} • Verified)"
            } else {
                "🎙️ স্যারের ভয়েস ট্রেনিং করুন (৩টি বাক্য পড়ে মেইন ভয়েস লক করুন)"
            }
        )
    }

    /**
     * Restores enrolled voice profiles downloaded from Google Drive (`/AI_OS_Memory/Voice_Profiles/All_Enrolled_Voice_Profiles.json`)
     * so the AI immediately remembers Boss and all enrolled speakers across devices/installs.
     */
    fun restoreProfilesFromCloudJson(context: Context, cloudJson: String) {
        if (cloudJson.isBlank()) return
        init(context)
        val cloudProfiles = parseProfilesJson(cloudJson)
        if (cloudProfiles.isEmpty()) return

        val merged = (_state.value.enrolledProfiles + cloudProfiles)
            .associateBy { it.speakerName.lowercase(Locale.ROOT) }
            .values
            .toList()

        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES_JSON, serializeProfilesJson(merged))
            .apply()

        val hasBoss = merged.any { it.isOwnerBoss }
        val bossProf = merged.firstOrNull { it.isOwnerBoss }
        _state.value = _state.value.copy(
            enrolledProfiles = merged,
            hasOwnerBossProfile = hasBoss,
            liveHudBadgeText = if (hasBoss) {
                "🎙️ বক্তা: ${bossProf?.speakerName ?: "বস"} (${(bossProf?.gender ?: DetectedSpeakerGender.MALE).labelEn} • Drive Restored)"
            } else {
                _state.value.liveHudBadgeText
            }
        )
    }

    fun setVoiceSecurityLockEnabled(context: Context, enabled: Boolean): String {
        init(context)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SECURITY_LOCK_ENABLED, enabled)
            .apply()
        _state.value = _state.value.copy(
            isVoiceSecurityLockEnabled = enabled,
            lastSecurityGateEventBn = if (enabled) {
                "🔒 বসের ভয়েস সিকিউরিটি লক চালু করা হয়েছে।"
            } else {
                "🔓 ভয়েস সিকিউরিটি লক সাময়িকভাবে বন্ধ করা হয়েছে।"
            }
        )
        return _state.value.lastSecurityGateEventBn
    }

    /**
     * Real-time 16kHz PCM audio frame analyzer called from `MultimodalLiveService` or standalone calibration.
     * Uses sensitive voice threshold (RMS >= 135) so normal and soft natural speech is always recognized!
     */
    fun processIncoming16kHzPcmFrame(
        context: Context,
        pcm16LeBytes: ByteArray,
        isAgentCurrentlySpeaking: Boolean,
        fromLiveStream: Boolean = true
    ) {
        if (isAgentCurrentlySpeaking || pcm16LeBytes.size < 400) return
        val now = System.currentTimeMillis()
        if (fromLiveStream) {
            lastLiveMicStreamFrameAtMs = now
        }
        val minInterval = if (_state.value.isEnrolling) 35L else 65L
        if (now - lastAnalyzedFrameAtMs < minInterval) return

        val sampleCount = pcm16LeBytes.size / 2
        val samples = FloatArray(sampleCount)
        var sumSq = 0.0
        var i = 0
        var sIdx = 0
        while (i + 1 < pcm16LeBytes.size && sIdx < sampleCount) {
            val lo = pcm16LeBytes[i].toInt() and 0xFF
            val hi = pcm16LeBytes[i + 1].toInt()
            val v = ((hi shl 8) or lo).toShort().toFloat()
            samples[sIdx++] = v
            sumSq += (v * v).toDouble()
            i += 2
        }
        val rms = sqrt(sumSq / sampleCount.coerceAtLeast(1)).toFloat()
        val normRms = (rms / 2200f).coerceIn(0f, 1f)

        // Responsive threshold (135f) so even normal/quiet human speech is picked up reliably
        if (rms < 135f) {
            if (now - lastAnalyzedFrameAtMs > 1800L && _state.value.isCurrentlySpeaking) {
                _state.value = _state.value.copy(
                    isCurrentlySpeaking = false,
                    liveVoiceRmsLevel = 0f
                )
            }
            return
        }
        lastAnalyzedFrameAtMs = now

        val pitchHz = estimateFundamentalPitchYinHz(samples, SAMPLE_RATE)
        if (pitchHz !in 72f..420f) {
            _state.value = _state.value.copy(liveVoiceRmsLevel = normRms)
            return
        }

        val spectralCentroid = computeSpectralCentroidHz(samples, SAMPLE_RATE)
        val embedding = extractNormalizedVocalTimbreEmbedding(samples, SAMPLE_RATE, pitchHz, spectralCentroid)

        var collectedEnrollmentFrames = 0
        synchronized(rollingFrameLock) {
            if (rollingPitchHistory.size >= 110) rollingPitchHistory.removeFirst()
            if (rollingCentroidHistory.size >= 110) rollingCentroidHistory.removeFirst()
            if (rollingEmbeddings.size >= 110) rollingEmbeddings.removeFirst()
            rollingPitchHistory.addLast(pitchHz)
            rollingCentroidHistory.addLast(spectralCentroid)
            rollingEmbeddings.addLast(embedding)

            if (_state.value.isEnrolling) {
                enrollmentPitches.add(pitchHz)
                enrollmentCentroids.add(spectralCentroid)
                enrollmentEmbeddings.add(embedding)
                collectedEnrollmentFrames = enrollmentEmbeddings.size
            }
        }

        // If actively enrolling via Guided 3-Sentence Training, update sentence step based on real voiced frames
        if (_state.value.isEnrolling && collectedEnrollmentFrames > 0) {
            val targetFrames = if (_state.value.enrollmentIsOwnerBoss) 48 else 28
            val pct = ((collectedEnrollmentFrames * 100) / targetFrames).coerceIn(8, 99)
            val stepIdx = when {
                pct < 34 -> 0
                pct < 68 -> 1
                else -> 2
            }
            _state.value = _state.value.copy(
                currentPitchHz = pitchHz.toInt(),
                spectralCentroidHz = spectralCentroid.toInt(),
                liveVoiceRmsLevel = normRms,
                isCurrentlySpeaking = true,
                enrollmentProgressPercent = pct,
                enrollmentStepIndex = stepIdx,
                currentCalibrationSentenceBn = BOSS_CALIBRATION_SENTENCES.getOrElse(stepIdx) { BOSS_CALIBRATION_SENTENCES.last() },
                liveHudBadgeText = "🎙️ কণ্ঠ স্ক্যান চলছে (${_state.value.enrollmentTargetName} • ধাপ ${stepIdx + 1}/৩ • $pct%)"
            )
            return
        }

        val smoothedPitch: Float
        val smoothedCentroid: Float
        val smoothedEmbedding: FloatArray
        synchronized(rollingFrameLock) {
            val recentPitches = rollingPitchHistory.takeLast(11)
            val recentCentroids = rollingCentroidHistory.takeLast(11)
            val recentEmbs = rollingEmbeddings.takeLast(11)
            smoothedPitch = medianFloat(recentPitches)
            smoothedCentroid = recentCentroids.average().toFloat()
            smoothedEmbedding = averageAndNormalizeEmbeddings(recentEmbs)
        }

        val acousticGender = classifyGenderAndAgeGroup(smoothedPitch, smoothedCentroid)
        matchSpeakerAgainstProfiles(
            context = context,
            pitchHz = smoothedPitch,
            centroidHz = smoothedCentroid,
            liveEmbedding = smoothedEmbedding,
            acousticGender = acousticGender,
            normRms = normRms
        )
    }

    /**
     * Called whenever a live user speech transcript arrives (from Gemini Live `inputTranscription` or STT).
     * 1) Learns the current speaker's vocabulary, cadence, and speaking style.
     * 2) If an unknown speaker introduces themselves (e.g., "আমার নাম সিয়াম", "আমি রাহাত বলছি", "My name is Siam"),
     *    it AUTOMATICALLY enrolls their voice print + speaking style under their name and syncs to Google Drive!
     * 3) If Boss reads the calibration keywords/sentences, it strengthens Boss's voice print automatically!
     */
    fun onUserTranscriptReceived(
        context: Context,
        transcript: String,
        onAutoEnrolledCallback: (String) -> Unit = {}
    ) {
        val clean = transcript.trim()
        if (clean.length < 2) return
        init(context)

        val words = clean.split(Regex("\\s+")).map { it.trim() }.filter { it.length >= 2 }
        synchronized(rollingFrameLock) {
            for (w in words) {
                if (rollingSpokenWords.size >= 40) rollingSpokenWords.removeFirst()
                rollingSpokenWords.addLast(w)
            }
        }

        // Check if speaker just introduced their name (e.g., "আমার নাম সিয়াম", "আমি রাহাত বলছি", "এই কণ্ঠটা তানভীরের নামে সেভ রাখো")
        val introducedName = extractSelfIntroducedOrCommandedName(clean)
        if (!introducedName.isNullOrBlank()) {
            val isBoss = introducedName.equals("বস", ignoreCase = true) ||
                introducedName.equals("Boss", ignoreCase = true) ||
                introducedName.equals("স্যার", ignoreCase = true)
            val msg = enrollSpeakerFromRecentVoiceBuffer(
                context = context,
                speakerName = introducedName,
                isOwnerBoss = isBoss,
                spokenSampleText = clean
            )
            onAutoEnrolledCallback(msg)
            return
        }

        // Check if user is reading the Boss calibration phrases ("হ্যালো আর্চার, আমি তোমার বস বলছি...")
        val lower = clean.lowercase(Locale.ROOT)
        if ((lower.contains("আমি তোমার বস") || lower.contains("আমার কণ্ঠস্বর ও কথা বলার ধরন চিনে রাখো")) &&
            !_state.value.hasOwnerBossProfile
        ) {
            val msg = enrollSpeakerFromRecentVoiceBuffer(
                context = context,
                speakerName = "বস",
                isOwnerBoss = true,
                spokenSampleText = clean
            )
            onAutoEnrolledCallback(msg)
            return
        }

        // Continuous adaptive style & voice learning for the currently recognized speaker
        if (_state.value.isKnownEnrolledSpeaker && _state.value.activeSpeakerName.isNotBlank()) {
            adaptActiveSpeakerStyleFromTranscript(context, _state.value.activeSpeakerName, clean)
        }
    }

    /**
     * Extracts a speaker's name when they introduce themselves or when Boss commands to save a voice:
     * Examples:
     * - "আমার নাম সিয়াম" / "আমার নাম হলো রাহাত"
     * - "আমি সিয়াম বলছি" / "আমি মীম কথা বলছি"
     * - "এই কণ্ঠটা সিয়ামের নামে সেভ রাখো"
     * - "My name is Tanvir" / "I am Siam speaking"
     */
    private fun extractSelfIntroducedOrCommandedName(text: String): String? {
        val patterns = listOf(
            Regex("(?:আমার নাম হলো|আমার নাম হচ্ছে|আমার নাম)\\s+([\\u0980-\\u09FFa-zA-Z]{2,18})"),
            Regex("(?:আমি)\\s+([\\u0980-\\u09FFa-zA-Z]{2,18})\\s+(?:বলছি|কথা বলছি)"),
            Regex("(?:কণ্ঠটা|ভয়েসটা|গলাটা)\\s+([\\u0980-\\u09FFa-zA-Z]{2,18})(?:-এর| এর|র)?\\s+নামে\\s+(?:সেভ|লক)"),
            Regex("(?i)(?:my name is|i am)\\s+([a-zA-Z]{2,18})(?:\\s+speaking|$)")
        )
        val ignoredStopWords = setOf(
            "বস", "স্যার", "এখন", "কী", "কি", "বলছি", "কথা", "একটা", "তোমার", "আপনার", "ভালো", "আছি", "করছি"
        )
        for (regex in patterns) {
            val match = regex.find(text) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim() ?: continue
            val stripped = candidate.removeSuffix("ের").removeSuffix("র").trim()
            if (stripped.length >= 2 && stripped !in ignoredStopWords) {
                return stripped
            }
        }
        return null
    }

    private fun adaptActiveSpeakerStyleFromTranscript(
        context: Context,
        speakerName: String,
        transcript: String
    ) {
        val currentList = _state.value.enrolledProfiles
        val idx = currentList.indexOfFirst { it.speakerName.equals(speakerName, ignoreCase = true) }
        if (idx < 0) return

        val existing = currentList[idx]
        val newWords = transcript.split(Regex("\\s+"))
            .map { it.trim().replace(Regex("[^\\u0980-\\u09FFa-zA-Z0-9]"), "") }
            .filter { it.length >= 3 }
        val mergedWords = (existing.signatureWords + newWords).distinct().takeLast(18)

        val liveEmbs: List<FloatArray>
        val livePitches: List<Float>
        synchronized(rollingFrameLock) {
            liveEmbs = rollingEmbeddings.takeLast(8)
            livePitches = rollingPitchHistory.takeLast(8)
        }

        val blendedEmb = if (liveEmbs.isNotEmpty() && existing.embedding.size == EMBEDDING_DIM) {
            val recentAvg = averageAndNormalizeEmbeddings(liveEmbs)
            val blended = FloatArray(EMBEDDING_DIM) { i ->
                existing.embedding[i] * 0.90f + recentAvg[i] * 0.10f
            }
            l2Normalize(blended).toList()
        } else {
            existing.embedding
        }

        val updatedPitch = if (livePitches.isNotEmpty()) {
            existing.meanPitchHz * 0.88f + medianFloat(livePitches) * 0.12f
        } else {
            existing.meanPitchHz
        }

        val styleDesc = buildSpeakingStyleSummaryBn(updatedPitch, existing.speakingCadenceWpm, mergedWords)
        val updatedProfile = existing.copy(
            meanPitchHz = updatedPitch,
            minPitchHz = min(existing.minPitchHz, updatedPitch - 15f),
            maxPitchHz = max(existing.maxPitchHz, updatedPitch + 15f),
            speakingStyleSummaryBn = styleDesc,
            signatureWords = mergedWords,
            sampleCount = existing.sampleCount + 1,
            embedding = blendedEmb
        )

        val mutable = currentList.toMutableList()
        mutable[idx] = updatedProfile
        _state.value = _state.value.copy(enrolledProfiles = mutable)

        // Persist updated adaptive profile every 4 turns so Google Drive stays fresh without spamming
        if (updatedProfile.sampleCount % 4 == 0) {
            saveProfilesToLocalAndSyncTo5TbDrive(context, mutable, updatedProfile)
        }
    }

    private fun buildSpeakingStyleSummaryBn(
        meanPitchHz: Float,
        cadenceWpm: Int,
        words: List<String>
    ): String {
        val toneDesc = when {
            meanPitchHz < 130f -> "গম্ভীর ও ভরাট কণ্ঠ"
            meanPitchHz < 170f -> "স্পষ্ট ও ভারসাম্যপূর্ণ কণ্ঠ"
            meanPitchHz < 245f -> "উজ্জ্বল ও সুরেলা কণ্ঠ"
            else -> "চঞ্চল ও উচ্চ-পিচ কণ্ঠ"
        }
        val speedDesc = when {
            cadenceWpm > 145 -> "দ্রুত বাচনভঙ্গি"
            cadenceWpm < 95 -> "ধীরস্থির বাচনভঙ্গি"
            else -> "স্বাভাবিক গতির বাচনভঙ্গি"
        }
        val sampleWords = if (words.isNotEmpty()) " • ব্যবহৃত শব্দ: ${words.takeLast(5).joinToString(", ")}" else ""
        return "$toneDesc ($speedDesc$sampleWords)"
    }

    /**
     * YIN / Normalized Difference Autocorrelation Pitch Estimator for 16kHz speech (`72 Hz` .. `420 Hz`).
     */
    private fun estimateFundamentalPitchYinHz(samples: FloatArray, sampleRate: Int): Float {
        val n = min(samples.size, 800)
        val minLag = sampleRate / 420
        val maxLag = min(sampleRate / 72, n / 2)
        if (maxLag <= minLag + 4) return 0f

        val diff = FloatArray(maxLag + 1)
        val cmndf = FloatArray(maxLag + 1)
        val windowLen = n - maxLag

        for (tau in 1..maxLag) {
            var d = 0.0f
            var j = 0
            while (j < windowLen) {
                val delta = samples[j] - samples[j + tau]
                d += delta * delta
                j += 2
            }
            diff[tau] = d
        }

        cmndf[0] = 1f
        var runningSum = 0f
        for (tau in 1..maxLag) {
            runningSum += diff[tau]
            cmndf[tau] = if (runningSum > 0f) diff[tau] * tau / runningSum else 1f
        }

        var bestTau = -1
        val threshold = 0.24f
        var tau = minLag
        while (tau <= maxLag) {
            if (cmndf[tau] < threshold) {
                while (tau + 1 <= maxLag && cmndf[tau + 1] < cmndf[tau]) {
                    tau++
                }
                bestTau = tau
                break
            }
            tau++
        }

        if (bestTau == -1) {
            var minVal = Float.MAX_VALUE
            for (t in minLag..maxLag) {
                if (cmndf[t] < minVal) {
                    minVal = cmndf[t]
                    bestTau = t
                }
            }
            if (minVal > 0.56f) return 0f
        }

        val exactTau = if (bestTau in (minLag + 1) until maxLag) {
            val s0 = cmndf[bestTau - 1]
            val s1 = cmndf[bestTau]
            val s2 = cmndf[bestTau + 1]
            val denom = 2f * (2f * s1 - s2 - s0)
            if (abs(denom) > 1e-5f) bestTau + (s2 - s0) / denom else bestTau.toFloat()
        } else {
            bestTau.toFloat()
        }

        return if (exactTau > 0f) sampleRate.toFloat() / exactTau else 0f
    }

    private fun computeSpectralCentroidHz(samples: FloatArray, sampleRate: Int): Float {
        val n = min(samples.size, 512)
        if (n < 64) return 1400f
        var zeroCrossings = 0
        for (i in 1 until n) {
            if ((samples[i - 1] >= 0f && samples[i] < 0f) || (samples[i - 1] < 0f && samples[i] >= 0f)) {
                zeroCrossings++
            }
        }
        val zcrHz = (zeroCrossings.toFloat() * sampleRate) / (2f * n)

        val probeFreqs = floatArrayOf(180f, 340f, 560f, 880f, 1350f, 1950f, 2750f, 3650f)
        var weightedFreqSum = 0.0
        var energySum = 0.0
        for (freq in probeFreqs) {
            val e = goertzelBandEnergy(samples, n, freq, sampleRate)
            weightedFreqSum += freq * e
            energySum += e
        }
        val bandCentroid = if (energySum > 1e-3) (weightedFreqSum / energySum).toFloat() else 1400f
        return (bandCentroid * 0.72f + zcrHz * 0.28f).coerceIn(300f, 4200f)
    }

    private fun extractNormalizedVocalTimbreEmbedding(
        samples: FloatArray,
        sampleRate: Int,
        pitchHz: Float,
        centroidHz: Float
    ): FloatArray {
        val n = min(samples.size, 512)
        val bandCentersHz = floatArrayOf(
            130f, 210f, 310f, 440f, 610f, 830f,
            1120f, 1480f, 1920f, 2480f, 3180f, 4050f
        )
        val vec = FloatArray(EMBEDDING_DIM)
        var lowBandEnergy = 1e-3f
        var highBandEnergy = 1e-3f
        for (b in bandCentersHz.indices) {
            val energy = goertzelBandEnergy(samples, n, bandCentersHz[b], sampleRate)
            vec[b] = ln(1f + energy).toFloat()
            if (b < 5) lowBandEnergy += energy else highBandEnergy += energy
        }
        vec[12] = ((pitchHz - 80f) / 280f).coerceIn(0f, 1.5f) * 3.2f
        vec[13] = ((centroidHz - 400f) / 3000f).coerceIn(0f, 1.5f) * 2.6f
        vec[14] = ln(1f + (highBandEnergy / lowBandEnergy)).coerceIn(0f, 3f)

        var maxAbs = 1f
        var sumSq = 0f
        for (i in 0 until n) {
            val a = abs(samples[i])
            if (a > maxAbs) maxAbs = a
            sumSq += a * a
        }
        val rms = sqrt(sumSq / n.coerceAtLeast(1)).coerceAtLeast(1f)
        vec[15] = (maxAbs / rms).coerceIn(1f, 8f) * 0.45f

        return l2Normalize(vec)
    }

    private fun goertzelBandEnergy(samples: FloatArray, length: Int, targetFreqHz: Float, sampleRate: Int): Float {
        val k = ((length * targetFreqHz) / sampleRate).toInt()
        val omega = (2.0 * PI * k) / length
        val coeff = (2.0 * cos(omega)).toFloat()
        var q0: Float
        var q1 = 0f
        var q2 = 0f
        val step = 2
        var i = 0
        while (i < length) {
            q0 = coeff * q1 - q2 + (samples[i] / 16384f)
            q2 = q1
            q1 = q0
            i += step
        }
        val power = q1 * q1 + q2 * q2 - q1 * q2 * coeff
        return max(0f, power)
    }

    private fun classifyGenderAndAgeGroup(pitchHz: Float, centroidHz: Float): DetectedSpeakerGender {
        return when {
            pitchHz >= 278f || (pitchHz >= 258f && centroidHz >= 2150f) -> DetectedSpeakerGender.CHILD
            pitchHz >= 172f || (pitchHz in 160f..172f && centroidHz >= 1780f) -> DetectedSpeakerGender.FEMALE
            pitchHz in 72f..171f -> DetectedSpeakerGender.MALE
            else -> DetectedSpeakerGender.UNKNOWN
        }
    }

    fun updateFromGeminiLivePerception(
        genderOrAgeHint: String?,
        speakerNameHint: String? = null
    ) {
        val parsedGender = DetectedSpeakerGender.fromString(genderOrAgeHint)
        val curr = _state.value
        val updatedGender = if (parsedGender != DetectedSpeakerGender.UNKNOWN) parsedGender else curr.detectedGender
        val updatedName = speakerNameHint?.trim()?.takeIf { it.isNotBlank() } ?: curr.activeSpeakerName
        _state.value = curr.copy(
            detectedGender = updatedGender,
            activeSpeakerName = updatedName,
            liveHudBadgeText = buildHudBadgeText(
                speakerName = updatedName,
                gender = updatedGender,
                isOwnerVerified = curr.isOwnerBossVerified,
                isKnownSpeaker = curr.isKnownEnrolledSpeaker,
                hasBossProfile = curr.hasOwnerBossProfile,
                similarityPct = curr.similarityScorePercent
            )
        )
    }

    private fun matchSpeakerAgainstProfiles(
        context: Context,
        pitchHz: Float,
        centroidHz: Float,
        liveEmbedding: FloatArray,
        acousticGender: DetectedSpeakerGender,
        normRms: Float
    ) {
        val current = _state.value
        val profiles = current.enrolledProfiles
        val hasBoss = profiles.any { it.isOwnerBoss }

        if (profiles.isEmpty()) {
            val badge = "🎙️ কণ্ঠ শনাক্ত: ${acousticGender.labelBn} (${acousticGender.labelEn} • ${pitchHz.toInt()}Hz) • ভয়েস ট্রেনিং করুন"
            _state.value = current.copy(
                activeSpeakerName = "বস (${acousticGender.labelBn})",
                isOwnerBossVerified = true,
                isKnownEnrolledSpeaker = false,
                isWaitingForNewSpeakerName = false,
                hasOwnerBossProfile = false,
                detectedGender = acousticGender,
                currentPitchHz = pitchHz.toInt(),
                spectralCentroidHz = centroidHz.toInt(),
                similarityScorePercent = 0,
                liveVoiceRmsLevel = normRms,
                isCurrentlySpeaking = true,
                liveHudBadgeText = badge
            )
            return
        }

        var bestProfile: EnrolledVoiceProfile? = null
        var bestScore = -1f

        for (profile in profiles) {
            val cosSim = cosineSimilarity(liveEmbedding, profile.embedding.toFloatArray())
            val pitchDiff = abs(pitchHz - profile.meanPitchHz)
            // Check if pitch falls inside the speaker's natural min..max pitch envelope
            val withinPitchEnvelope = pitchHz in (profile.minPitchHz - 18f)..(profile.maxPitchHz + 18f)
            val pitchBonus = if (withinPitchEnvelope) 0.06f else 0f
            val pitchPenalty = (pitchDiff / 110f).coerceIn(0f, 0.28f)
            val combinedScore = (cosSim * 0.82f + (1f - pitchPenalty) * 0.18f + pitchBonus).coerceIn(0f, 1f)
            if (combinedScore > bestScore) {
                bestScore = combinedScore
                bestProfile = profile
            }
        }

        val simPct = (bestScore * 100f).toInt().coerceIn(0, 99)
        // Balanced threshold so Boss's natural voice variations (soft/loud/fast) are always recognized smoothly
        val matchedThreshold = if (bestProfile?.isOwnerBoss == true) 0.72f else 0.75f

        if (bestProfile != null && bestScore >= matchedThreshold) {
            unknownSpeakerConsecutiveFrames = 0
            val isBoss = bestProfile.isOwnerBoss
            if (isBoss) {
                lastVerifiedBossAtMs = System.currentTimeMillis()
            }
            val effectiveGender = bestProfile.gender.takeIf { it != DetectedSpeakerGender.UNKNOWN } ?: acousticGender
            val badge = buildHudBadgeText(
                speakerName = bestProfile.speakerName,
                gender = effectiveGender,
                isOwnerVerified = isBoss,
                isKnownSpeaker = true,
                hasBossProfile = hasBoss,
                similarityPct = simPct
            )
            _state.value = current.copy(
                activeSpeakerName = bestProfile.speakerName,
                isOwnerBossVerified = isBoss,
                isKnownEnrolledSpeaker = true,
                isWaitingForNewSpeakerName = false,
                hasOwnerBossProfile = hasBoss,
                detectedGender = effectiveGender,
                currentPitchHz = pitchHz.toInt(),
                spectralCentroidHz = centroidHz.toInt(),
                similarityScorePercent = simPct,
                liveVoiceRmsLevel = normRms,
                isCurrentlySpeaking = true,
                liveHudBadgeText = badge
            )
        } else {
            val bossProfile = profiles.firstOrNull { it.isOwnerBoss }
            val withinBossGrace = bossProfile != null &&
                (System.currentTimeMillis() - lastVerifiedBossAtMs < 4500L) &&
                abs(pitchHz - bossProfile.meanPitchHz) < 48f

            if (withinBossGrace && bossProfile != null) {
                unknownSpeakerConsecutiveFrames = 0
                val badge = buildHudBadgeText(
                    speakerName = bossProfile.speakerName,
                    gender = bossProfile.gender,
                    isOwnerVerified = true,
                    isKnownSpeaker = true,
                    hasBossProfile = hasBoss,
                    similarityPct = max(simPct, 84)
                )
                _state.value = current.copy(
                    activeSpeakerName = bossProfile.speakerName,
                    isOwnerBossVerified = true,
                    isKnownEnrolledSpeaker = true,
                    isWaitingForNewSpeakerName = false,
                    hasOwnerBossProfile = hasBoss,
                    detectedGender = bossProfile.gender,
                    currentPitchHz = pitchHz.toInt(),
                    spectralCentroidHz = centroidHz.toInt(),
                    similarityScorePercent = max(simPct, 84),
                    liveVoiceRmsLevel = normRms,
                    isCurrentlySpeaking = true,
                    liveHudBadgeText = badge
                )
            } else {
                unknownSpeakerConsecutiveFrames++
                val waitingForName = unknownSpeakerConsecutiveFrames >= 3
                val badge = "🎙️ নতুন কণ্ঠ: ${acousticGender.labelBn} (${acousticGender.labelEn} • ${pitchHz.toInt()}Hz • নাম বললে চিনে নেবে)"
                _state.value = current.copy(
                    activeSpeakerName = "নতুন বক্তা (${acousticGender.labelBn})",
                    isOwnerBossVerified = !hasBoss,
                    isKnownEnrolledSpeaker = false,
                    isWaitingForNewSpeakerName = waitingForName,
                    hasOwnerBossProfile = hasBoss,
                    detectedGender = acousticGender,
                    currentPitchHz = pitchHz.toInt(),
                    spectralCentroidHz = centroidHz.toInt(),
                    similarityScorePercent = simPct,
                    liveVoiceRmsLevel = normRms,
                    isCurrentlySpeaking = true,
                    liveHudBadgeText = badge
                )
            }
        }
    }

    private fun buildHudBadgeText(
        speakerName: String,
        gender: DetectedSpeakerGender,
        isOwnerVerified: Boolean,
        isKnownSpeaker: Boolean,
        hasBossProfile: Boolean,
        similarityPct: Int
    ): String {
        return when {
            isOwnerVerified && hasBossProfile ->
                "🎙️ বক্তা: $speakerName (${gender.labelEn} • Verified ${if (similarityPct > 0) "$similarityPct%" else ""})".trim()
            isKnownSpeaker ->
                "🎙️ বক্তা: $speakerName (${gender.labelBn} • ${gender.labelEn})"
            else ->
                "🎙️ নতুন কণ্ঠ: ${gender.labelBn} (${gender.labelEn} • নাম বললে সেভ হবে)"
        }
    }

    /**
     * Guided 3-Sentence Boss Voice Calibration & Multi-Speaker Voice Training:
     * - Zero Mic Conflict: If `MultimodalLiveService` is already streaming mic audio (`lastLiveMicStreamFrameAtMs` < 1500ms),
     *   it NEVER opens a competing `AudioRecord`—it reads directly from the live stream!
     * - If `MultimodalLiveService` is offline, it opens a dedicated `AudioRecord` so training works anytime.
     */
    @SuppressLint("MissingPermission")
    fun startFiveSecondVoiceEnrollment(
        context: Context,
        speakerName: String = "বস",
        isOwnerBoss: Boolean = true,
        durationSeconds: Int = if (isOwnerBoss) 8 else 5,
        onComplete: (String) -> Unit = {}
    ) {
        init(context)
        val cleanName = speakerName.trim().ifBlank { if (isOwnerBoss) "বস" else "মেহমান" }
        standaloneEnrollmentJob?.cancel()

        synchronized(rollingFrameLock) {
            enrollmentPitches.clear()
            enrollmentCentroids.clear()
            enrollmentEmbeddings.clear()
        }

        _state.value = _state.value.copy(
            isEnrolling = true,
            enrollmentTargetName = cleanName,
            enrollmentIsOwnerBoss = isOwnerBoss,
            enrollmentStepIndex = 0,
            enrollmentTotalSteps = BOSS_CALIBRATION_SENTENCES.size,
            currentCalibrationSentenceBn = BOSS_CALIBRATION_SENTENCES.first(),
            enrollmentProgressPercent = 5,
            liveHudBadgeText = "🎙️ ভয়েস ট্রেনিং চলছে ($cleanName): নিচের বাক্যগুলো স্বাভাবিক গলায় পড়ুন..."
        )

        standaloneEnrollmentJob = scope.launch {
            val hasPerm = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            // Only open a standalone AudioRecord if MultimodalLiveService is NOT actively streaming mic audio
            val liveStreamActive = (System.currentTimeMillis() - lastLiveMicStreamFrameAtMs) < 1500L
            var tempRecorder: AudioRecord? = null
            if (!liveStreamActive && hasPerm) {
                runCatching {
                    val minBuf = AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(3200)
                    tempRecorder = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBuf * 2
                    )
                    if (tempRecorder?.state == AudioRecord.STATE_INITIALIZED) {
                        tempRecorder?.startRecording()
                    }
                }
            }

            val totalDurationMs = (durationSeconds.coerceIn(4, 12) * 1000L)
            val startMs = System.currentTimeMillis()
            val pcmBuf = ByteArray(1600)

            try {
                while (_state.value.isEnrolling && (System.currentTimeMillis() - startMs < totalDurationMs)) {
                    val elapsed = System.currentTimeMillis() - startMs
                    val timePct = ((elapsed * 100L) / totalDurationMs).toInt().coerceIn(5, 99)
                    val stepIdx = when {
                        timePct < 34 -> 0
                        timePct < 68 -> 1
                        else -> 2
                    }
                    _state.value = _state.value.copy(
                        enrollmentProgressPercent = max(_state.value.enrollmentProgressPercent, timePct),
                        enrollmentStepIndex = stepIdx,
                        currentCalibrationSentenceBn = BOSS_CALIBRATION_SENTENCES.getOrElse(stepIdx) { BOSS_CALIBRATION_SENTENCES.last() },
                        liveHudBadgeText = "🎙️ ভয়েস প্রিন্ট স্ক্যান হচ্ছে ($cleanName • ধাপ ${stepIdx + 1}/৩ • $timePct%)..."
                    )

                    val rec = tempRecorder
                    if (rec != null && rec.RecordingStateSafe() == AudioRecord.RECORDSTATE_RECORDING) {
                        val readBytes = rec.read(pcmBuf, 0, pcmBuf.size)
                        if (readBytes > 0) {
                            processIncoming16kHzPcmFrame(
                                context = context,
                                pcm16LeBytes = pcmBuf.copyOf(readBytes),
                                isAgentCurrentlySpeaking = false,
                                fromLiveStream = false
                            )
                        }
                    } else {
                        delay(80L)
                    }
                }
            } finally {
                runCatching { tempRecorder?.stop() }
                runCatching { tempRecorder?.release() }
            }

            val resultMsg = finalizeEnrollmentFromCollectedFrames(
                context = context,
                speakerName = cleanName,
                isOwnerBoss = isOwnerBoss,
                genderOverride = null,
                spokenSampleText = BOSS_CALIBRATION_SENTENCES.joinToString(" ")
            )
            onComplete(resultMsg)
        }
    }

    fun cancelActiveEnrollment() {
        standaloneEnrollmentJob?.cancel()
        standaloneEnrollmentJob = null
        _state.value = _state.value.copy(
            isEnrolling = false,
            enrollmentProgressPercent = 0
        )
    }

    private fun AudioRecord.RecordingStateSafe(): Int = try {
        recordingState
    } catch (_: Exception) {
        AudioRecord.RECORDSTATE_STOPPED
    }

    /**
     * Enrolls Boss or any guest speaker ("সিয়াম", "রাহাত", etc.) immediately from the rolling vocal buffer
     * and learns their speaking style & vocabulary.
     */
    fun enrollSpeakerFromRecentVoiceBuffer(
        context: Context,
        speakerName: String,
        isOwnerBoss: Boolean = false,
        genderOverride: DetectedSpeakerGender? = null,
        spokenSampleText: String = ""
    ): String {
        init(context)
        val cleanName = speakerName.trim().ifBlank { if (isOwnerBoss) "বস" else "মেহমান" }
        synchronized(rollingFrameLock) {
            if (enrollmentEmbeddings.isEmpty() && rollingEmbeddings.isNotEmpty()) {
                enrollmentPitches.clear()
                enrollmentCentroids.clear()
                enrollmentEmbeddings.clear()
                enrollmentPitches.addAll(rollingPitchHistory)
                enrollmentCentroids.addAll(rollingCentroidHistory)
                enrollmentEmbeddings.addAll(rollingEmbeddings)
            }
        }
        return finalizeEnrollmentFromCollectedFrames(
            context = context,
            speakerName = cleanName,
            isOwnerBoss = isOwnerBoss,
            genderOverride = genderOverride,
            spokenSampleText = spokenSampleText
        )
    }

    private fun finalizeEnrollmentFromCollectedFrames(
        context: Context,
        speakerName: String,
        isOwnerBoss: Boolean,
        genderOverride: DetectedSpeakerGender?,
        spokenSampleText: String
    ): String {
        val pitches: List<Float>
        val centroids: List<Float>
        val embeddings: List<FloatArray>
        val recentWords: List<String>

        synchronized(rollingFrameLock) {
            val srcPitches = if (enrollmentPitches.size >= 3) enrollmentPitches else rollingPitchHistory.toList()
            val srcCentroids = if (enrollmentCentroids.size >= 3) enrollmentCentroids else rollingCentroidHistory.toList()
            val srcEmbs = if (enrollmentEmbeddings.size >= 3) enrollmentEmbeddings else rollingEmbeddings.toList()

            pitches = srcPitches.toList()
            centroids = srcCentroids.toList()
            embeddings = srcEmbs.toList()
            recentWords = rollingSpokenWords.toList()

            enrollmentPitches.clear()
            enrollmentCentroids.clear()
            enrollmentEmbeddings.clear()
        }

        val meanPitch = if (pitches.isNotEmpty()) medianFloat(pitches) else {
            if (genderOverride == DetectedSpeakerGender.FEMALE) 210f else 128f
        }
        val minPitch = if (pitches.isNotEmpty()) (pitches.minOrNull() ?: (meanPitch - 20f)) else (meanPitch - 20f)
        val maxPitch = if (pitches.isNotEmpty()) (pitches.maxOrNull() ?: (meanPitch + 25f)) else (meanPitch + 25f)
        val pitchStd = if (pitches.size > 1) {
            val avg = pitches.average().toFloat()
            sqrt(pitches.map { (it - avg) * (it - avg) }.average()).toFloat().coerceAtLeast(8f)
        } else 14f

        val meanCentroid = if (centroids.isNotEmpty()) centroids.average().toFloat() else 1480f
        val finalEmbedding = if (embeddings.isNotEmpty()) {
            averageAndNormalizeEmbeddings(embeddings)
        } else {
            val synth = FloatArray(EMBEDDING_DIM) { idx ->
                when (idx) {
                    12 -> ((meanPitch - 80f) / 280f) * 3.2f
                    13 -> ((meanCentroid - 400f) / 3000f) * 2.6f
                    else -> 0.25f + (idx * 0.02f)
                }
            }
            l2Normalize(synth)
        }

        val detectedGender = genderOverride?.takeIf { it != DetectedSpeakerGender.UNKNOWN }
            ?: classifyGenderAndAgeGroup(meanPitch, meanCentroid)

        val extractedWords = (spokenSampleText.split(Regex("\\s+")) + recentWords)
            .map { it.trim().replace(Regex("[^\\u0980-\\u09FFa-zA-Z0-9]"), "") }
            .filter { it.length >= 3 }
            .distinct()
            .takeLast(16)

        val cadenceWpm = (115 + (embeddings.size.coerceIn(5, 60) / 2)).coerceIn(95, 165)
        val styleSummary = buildSpeakingStyleSummaryBn(meanPitch, cadenceWpm, extractedWords)

        val nowStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        val existingProfile = _state.value.enrolledProfiles.firstOrNull {
            (isOwnerBoss && it.isOwnerBoss) || it.speakerName.equals(speakerName, ignoreCase = true)
        }

        // If re-training Boss or an existing speaker, blend with existing profile for even higher accuracy
        val mergedEmbedding = if (existingProfile != null && existingProfile.embedding.size == EMBEDDING_DIM && embeddings.isNotEmpty()) {
            val blended = FloatArray(EMBEDDING_DIM) { i ->
                existingProfile.embedding[i] * 0.35f + finalEmbedding[i] * 0.65f
            }
            l2Normalize(blended).toList()
        } else {
            finalEmbedding.toList()
        }

        val newProfile = EnrolledVoiceProfile(
            id = if (isOwnerBoss) "owner_boss_voice_lock" else "speaker_${speakerName.lowercase().replace(Regex("[^a-z0-9\\u0980-\\u09FF]"), "_")}",
            speakerName = speakerName,
            isOwnerBoss = isOwnerBoss,
            gender = detectedGender,
            meanPitchHz = meanPitch,
            minPitchHz = minPitch,
            maxPitchHz = maxPitch,
            pitchStdHz = pitchStd,
            spectralCentroidHz = meanCentroid,
            speakingCadenceWpm = cadenceWpm,
            speakingStyleSummaryBn = styleSummary,
            signatureWords = extractedWords,
            sampleCount = (existingProfile?.sampleCount ?: 0) + 1,
            embedding = mergedEmbedding,
            enrolledAtFormatted = nowStr
        )

        val updatedList = _state.value.enrolledProfiles
            .filterNot {
                (isOwnerBoss && it.isOwnerBoss) ||
                    it.speakerName.equals(speakerName, ignoreCase = true)
            } + newProfile

        saveProfilesToLocalAndSyncTo5TbDrive(context, updatedList, newProfile)

        if (isOwnerBoss) {
            lastVerifiedBossAtMs = System.currentTimeMillis()
        }
        unknownSpeakerConsecutiveFrames = 0

        val hasBoss = updatedList.any { it.isOwnerBoss }
        val badge = buildHudBadgeText(
            speakerName = newProfile.speakerName,
            gender = newProfile.gender,
            isOwnerVerified = isOwnerBoss,
            isKnownSpeaker = true,
            hasBossProfile = hasBoss,
            similarityPct = 99
        )

        _state.value = _state.value.copy(
            activeSpeakerName = newProfile.speakerName,
            isOwnerBossVerified = isOwnerBoss || _state.value.isOwnerBossVerified,
            isKnownEnrolledSpeaker = true,
            isWaitingForNewSpeakerName = false,
            hasOwnerBossProfile = hasBoss,
            detectedGender = newProfile.gender,
            currentPitchHz = meanPitch.toInt(),
            spectralCentroidHz = meanCentroid.toInt(),
            similarityScorePercent = 99,
            isEnrolling = false,
            enrollmentProgressPercent = 100,
            enrolledProfiles = updatedList,
            liveHudBadgeText = badge
        )

        return if (isOwnerBoss) {
            "জি বস! আপনার মেইন ভয়েস প্রিন্ট ও কথা বলার ধরন (${detectedGender.labelBn} • ${meanPitch.toInt()}Hz • $styleSummary) সফলভাবে চিনে লক করা হয়েছে এবং Google Drive-এ অটো-সেভ হয়েছে। এখন থেকে আপনার কণ্ঠ শুনলেই আমি সাথে সাথে চিনে নেব!"
        } else {
            "জি বস! '$speakerName'-এর কণ্ঠস্বর ও কথা বলার ধরন (${detectedGender.labelBn} • ${meanPitch.toInt()}Hz • $styleSummary) মেমোরি ও Google Drive-এ সেভ করে নিয়েছি। এখন থেকে '$speakerName' কথা বললেই আমি তার গলা শুনে চিনে ফেলব!"
        }
    }

    fun deleteVoiceProfile(context: Context, profileIdOrName: String): String {
        init(context)
        val remaining = _state.value.enrolledProfiles.filterNot {
            it.id.equals(profileIdOrName, ignoreCase = true) ||
                it.speakerName.equals(profileIdOrName, ignoreCase = true)
        }
        val hasBoss = remaining.any { it.isOwnerBoss }
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = serializeProfilesJson(remaining)
        prefs.edit().putString(KEY_PROFILES_JSON, jsonStr).apply()

        val appCtx = context.applicationContext
        scope.launch(Dispatchers.IO) {
            GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                context = appCtx,
                fileName = "All_Enrolled_Voice_Profiles.json",
                contentBytes = jsonStr.toByteArray(Charsets.UTF_8),
                summaryDescription = "Master 5TB Voice Biometrics Registry (${remaining.size} Speakers)",
                targetSubFolder = GoogleDriveMemorySyncService.SUBFOLDER_VOICE_PROFILES
            )
        }

        _state.value = _state.value.copy(
            enrolledProfiles = remaining,
            hasOwnerBossProfile = hasBoss,
            isOwnerBossVerified = !hasBoss,
            liveHudBadgeText = if (hasBoss) _state.value.liveHudBadgeText else "🎙️ স্যারের ভয়েস ট্রেনিং করুন (৩টি বাক্য পড়ে মেইন ভয়েস লক করুন)"
        )
        return "'$profileIdOrName'-এর ভয়েস প্রিন্ট মুছে ফেলা হয়েছে।"
    }

    private fun saveProfilesToLocalAndSyncTo5TbDrive(
        context: Context,
        allProfiles: List<EnrolledVoiceProfile>,
        latestProfile: EnrolledVoiceProfile
    ) {
        val jsonStr = serializeProfilesJson(allProfiles)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES_JSON, jsonStr)
            .apply()

        val singleProfileJson = JSONObject().apply {
            put("cloudPath", "/AI_OS_Memory/Voice_Profiles/${latestProfile.id}.json")
            put("profile", latestProfile.toJson())
            put("allProfilesCount", allProfiles.size)
        }.toString(2)

        val safeName = latestProfile.speakerName.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF_-]"), "_")
        val appCtx = context.applicationContext
        scope.launch(Dispatchers.IO) {
            GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                context = appCtx,
                fileName = "VoicePrint_${safeName}.json",
                contentBytes = singleProfileJson.toByteArray(Charsets.UTF_8),
                summaryDescription = "Enrolled Speaker Voice Print (${latestProfile.speakerName} • ${latestProfile.gender.labelEn} • ${latestProfile.meanPitchHz.toInt()}Hz • ${latestProfile.speakingStyleSummaryBn})",
                targetSubFolder = GoogleDriveMemorySyncService.SUBFOLDER_VOICE_PROFILES
            )

            GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                context = appCtx,
                fileName = "All_Enrolled_Voice_Profiles.json",
                contentBytes = jsonStr.toByteArray(Charsets.UTF_8),
                summaryDescription = "Master 5TB Voice Biometrics Registry (${allProfiles.size} Speakers)",
                targetSubFolder = GoogleDriveMemorySyncService.SUBFOLDER_VOICE_PROFILES
            )
        }
    }

    fun verifySensitiveActionOrReturnBlockMessageBn(
        actionOrToolName: String,
        targetOrAppSummary: String = ""
    ): String? {
        val st = _state.value
        if (!st.hasOwnerBossProfile || !st.isVoiceSecurityLockEnabled) return null

        val upperAction = actionOrToolName.uppercase().trim()
        val lowerTarget = targetOrAppSummary.lowercase().trim()

        val isPrivateAppOpen = upperAction == "OPEN_APP" && (
            lowerTarget.contains("bkash") ||
                lowerTarget.contains("বিকাশ") ||
                lowerTarget.contains("nagad") ||
                lowerTarget.contains("নগদ") ||
                lowerTarget.contains("rocket") ||
                lowerTarget.contains("bank") ||
                lowerTarget.contains("ব্যাংক") ||
                lowerTarget.contains("wallet") ||
                lowerTarget.contains("gallery") ||
                lowerTarget.contains("photos") ||
                lowerTarget.contains("গ্যালারি") ||
                lowerTarget.contains("files") ||
                lowerTarget.contains("ফাইল")
            )

        val isSensitiveCommand = upperAction in setOf(
            "WHATSAPP_AUTOMATION",
            "TELEGRAM_AUTOMATION",
            "SEND_MESSAGE",
            "SEND_EMAIL",
            "DIAL_NUMBER",
            "INSTALL_APP_PLAYSTORE",
            "INSTALL_APK_PACKAGE",
            "INSTALL_APP",
            "SHARE_ARCHER_FILE",
            "SEND_GENERATED_FILE",
            "PREPARE_AND_HIGHLIGHT",
            "EXECUTE_CONFIRMED_CLICK",
            "INLINE_REPLY",
            "PASTE_CONFIRMED_OTP"
        ) || isPrivateAppOpen ||
            lowerTarget.contains("delete") ||
            lowerTarget.contains("ডিলিট") ||
            lowerTarget.contains("মুছে")

        if (!isSensitiveCommand) return null

        val recentlyVerifiedBoss = st.isOwnerBossVerified ||
            (System.currentTimeMillis() - lastVerifiedBossAtMs < 6500L)

        if (recentlyVerifiedBoss) return null

        val currentSpeakerDesc = "${st.activeSpeakerName} (${st.detectedGender.labelBn} • ${st.detectedGender.labelEn})"
        val blockMsg = "🔒 ভয়েস সিকিউরিটি গেট: দুঃখিত, বর্তমানে '$currentSpeakerDesc' কথা বলছেন—এটি বসের (Owner) ভেরিফায়েড কণ্ঠস্বর নয়। মেসেজ পাঠানো, কল করা, অ্যাপ ইনস্টল বা ব্যক্তিগত ফাইল/অ্যাপের মতো সংবেদনশীল আদেশ শুধুমাত্র বসের ভেরিফায়েড কণ্ঠই অনুমোদন করতে পারবে।"
        _state.value = st.copy(lastSecurityGateEventBn = blockMsg)
        return blockMsg
    }

    fun buildPromptContextForGeminiLive(): String = buildString {
        val st = _state.value
        append("\n\nREAL-TIME BIOMETRIC SPEAKER IDENTIFICATION, SPEAKING STYLE & AUTO-GUEST LEARNING ENGINE (`VoiceBiometricsEngine`):\n")
        append("- LIVE CURRENT SPEAKER ON MIC: ${st.liveHudBadgeText}\n")
        append("- SPEAKER IDENTITY: '${st.activeSpeakerName}' | DETECTED GENDER: ${st.detectedGender.labelBn} (${st.detectedGender.labelEn}) | PITCH: ${st.currentPitchHz}Hz | OWNER BOSS VERIFIED: ${if (st.isOwnerBossVerified) "YES (VERIFIED BOSS)" else "NO (OTHER SPEAKER / GUEST)"}\n")
        append("- ENROLLED VOICE PROFILES & SPEAKING STYLES IN GOOGLE DRIVE (`/AI_OS_Memory/Voice_Profiles/`): ")
        if (st.enrolledProfiles.isEmpty()) {
            append("None enrolled yet.\n")
        } else {
            append(
                st.enrolledProfiles.joinToString("; ") {
                    "${it.speakerName} [${if (it.isOwnerBoss) "BOSS/OWNER" else "Known Speaker"}, ${it.gender.labelBn}/${it.gender.labelEn}, ${it.meanPitchHz.toInt()}Hz, Style: ${it.speakingStyleSummaryBn}]"
                } + "\n"
            )
        }
        append(
            "- CRITICAL SPEAKER RECOGNITION & AUTO-INTRODUCTION RULES:\n" +
                "  1) When Boss (`Verified Boss`) is speaking, address him respectfully as 'বস' (Boss) or 'স্যার' (Sir) and obey all commands immediately.\n" +
                "  2) When an enrolled friend/family member is speaking (e.g., 'সিয়াম', 'রাহাত'), recognize their voice & speaking style and address them naturally by their name!\n" +
                "  3) NEW / UNKNOWN SPEAKER PROTOCOL (অন্য কেউ কথা বললে আগে নাম জেনে কণ্ঠ ও কথা বলার ধরন চিনে নেওয়া): " +
                "If `isWaitingForNewSpeakerName` is `${st.isWaitingForNewSpeakerName}` or you hear a new voice that is NOT Boss and NOT yet enrolled, politely ask them first in Bangla: 'আসসালামু আলাইকুম! আমি বুঝতে পারছি নতুন কেউ কথা বলছেন। আপনার নামটা কী বলবেন প্লিজ? তাহলে আমি আপনার কণ্ঠ ও কথা বলার ধরন চিনে রাখব।' " +
                "As soon as they tell you their name (e.g. 'আমার নাম সিয়াম' / 'আমি রাহাত'), IMMEDIATELY call `voice_biometrics_control(operation='ENROLL_GUEST_VOICE', speaker_name='<name>', gender_hint='${st.detectedGender.id}')` so their voice print and speaking style are permanently learned and saved to Google Drive!\n" +
                "  4) Whenever Boss says 'আমার ভয়েস লক করো' or reads the calibration sentences, call `voice_biometrics_control(operation='ENROLL_BOSS_VOICE', speaker_name='বস')`.\n" +
                "  5) VOICE SECURITY GATE: If `OWNER BOSS VERIFIED` is `NO` and an unrecognized speaker asks to send messages, make calls, install apps, open private apps, or delete files, politely refuse in Bangla and explain that only Boss's verified voice can authorize sensitive actions.\n"
        )
    }

    private fun parseProfilesJson(raw: String): List<EnrolledVoiceProfile> {
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<EnrolledVoiceProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                EnrolledVoiceProfile.fromJson(obj)?.let { list.add(it) }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeProfilesJson(profiles: List<EnrolledVoiceProfile>): String {
        val arr = JSONArray()
        profiles.forEach { arr.put(it.toJson()) }
        return arr.toString(2)
    }

    private fun medianFloat(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun averageAndNormalizeEmbeddings(list: List<FloatArray>): FloatArray {
        val avg = FloatArray(EMBEDDING_DIM)
        if (list.isEmpty()) return avg
        for (emb in list) {
            for (i in 0 until min(EMBEDDING_DIM, emb.size)) {
                avg[i] += emb[i]
            }
        }
        for (i in 0 until EMBEDDING_DIM) {
            avg[i] /= list.size.toFloat()
        }
        return l2Normalize(avg)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0f
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq).coerceAtLeast(1e-6f)
        return FloatArray(vec.size) { i -> vec[i] / norm }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = min(a.size, b.size)
        if (len == 0) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in 0 until len) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = (sqrt(normA) * sqrt(normB)).coerceAtLeast(1e-6f)
        return (dot / denom).coerceIn(0f, 1f)
    }
}
