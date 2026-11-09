package com.example.service

import android.app.ActivityManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class StudioSoundscapePreset(
    val id: String,
    val titleBn: String,
    val subtitleBn: String,
    val assetFileName: String,
    val baseFreqHz: Double,
    val binauralBeatHz: Double
) {
    JARVIS_CORE_HUM(
        id = "JARVIS_CORE_HUM",
        titleBn = "⚡ জারভিস রিঅ্যাক্টর কোর (Procedural DSP)",
        subtitleBn = "গভীর সাইবার অ্যাম্বিয়েন্ট ড্রোন ও হোলোগ্রাফিক পালস (Zero Storage)",
        assetFileName = "procedural://jarvis_reactor_core",
        baseFreqHz = 110.0,
        binauralBeatHz = 7.83
    ),
    NEURAL_ALPHA_FOCUS(
        id = "NEURAL_ALPHA_FOCUS",
        titleBn = "🧠 নিউরাল আলফা ফোকাস (10Hz Binaural)",
        subtitleBn = "ডিপ কোডিং, পড়াশোনা ও মনোযোগ বাড়ানোর প্রিমিয়াম সাউন্ডস্কেপ",
        assetFileName = "procedural://neural_alpha_focus",
        baseFreqHz = 216.0,
        binauralBeatHz = 10.0
    ),
    CYBER_RAIN_LOFI(
        id = "CYBER_RAIN_LOFI",
        titleBn = "🌧️ নিওন সাইবার রেইন ও সিন্থ প্যাড",
        subtitleBn = "রাতের বেলা শান্তভাবে কাজ করার জন্য প্রিমিয়াম অ্যাকোস্টিক আবহ",
        assetFileName = "procedural://cyber_rain_synth",
        baseFreqHz = 174.0,
        binauralBeatHz = 6.0
    ),
    QUANTUM_CHIME_MATRIX(
        id = "QUANTUM_CHIME_MATRIX",
        titleBn = "✨ কোয়ান্টাম ক্রিস্টাল হারমোনিক (528Hz)",
        subtitleBn = "ক্রিস্টাল ক্লিয়ার স্টুডিও রেজোন্যান্স ও রিল্যাক্সিং ফ্রিকোয়েন্সি",
        assetFileName = "procedural://quantum_crystal_528",
        baseFreqHz = 528.0,
        binauralBeatHz = 4.5
    )
}

data class OfflineStudioPackState(
    val isPlayingSoundscape: Boolean = false,
    val activePreset: StudioSoundscapePreset = StudioSoundscapePreset.JARVIS_CORE_HUM,
    val studioPackInstalled: Boolean = true,
    val totalOfflinePackSizeMb: String = "0.2 MB (Ultra-Light)",
    val loadedModulesCount: Int = 4
)

object OfflineStudioAssetEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null
    @Volatile
    private var activeTrack: AudioTrack? = null

    private val _state = MutableStateFlow(OfflineStudioPackState())
    val state: StateFlow<OfflineStudioPackState> = _state.asStateFlow()

    fun inspectBundledAssets(context: Context) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        _state.value = _state.value.copy(
            studioPackInstalled = true,
            totalOfflinePackSizeMb = if (isLowRam) "Ultra-Lite Low-RAM Mode" else "Zero-Storage DSP Mode",
            loadedModulesCount = 4
        )
    }

    fun selectAndTogglePreset(context: Context, preset: StudioSoundscapePreset) {
        if (_state.value.isPlayingSoundscape && _state.value.activePreset == preset) {
            stopSoundscape()
        } else {
            startSoundscape(context, preset)
        }
    }

    fun startSoundscape(context: Context, preset: StudioSoundscapePreset = _state.value.activePreset) {
        stopSoundscape()
        _state.value = _state.value.copy(
            isPlayingSoundscape = true,
            activePreset = preset
        )
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isLowRam = am?.isLowRamDevice == true
        playJob = scope.launch {
            val sampleRate = if (isLowRam) 16000 else 24000
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            val track = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@launch

            activeTrack = track
            runCatching { track.play() }

            val chunkSamples = if (isLowRam) 1024 else 2048
            val pcmBuffer = ShortArray(chunkSamples)
            var sampleIndex = 0L
            val f1 = preset.baseFreqHz
            val f2 = preset.baseFreqHz + preset.binauralBeatHz
            val fSub = preset.baseFreqHz * 0.5

            try {
                while (isActive && _state.value.isPlayingSoundscape) {
                    for (i in 0 until chunkSamples) {
                        val t = (sampleIndex + i).toDouble() / sampleRate.toDouble()
                        val slowEnv = 0.65 + 0.35 * sin(2.0 * PI * 0.18 * t)
                        val wave = (
                            0.45 * sin(2.0 * PI * f1 * t) +
                                0.35 * sin(2.0 * PI * f2 * t) +
                                0.20 * sin(2.0 * PI * fSub * t)
                            ) * slowEnv
                        pcmBuffer[i] = (wave * 5200.0).toInt().coerceIn(-32000, 32000).toShort()
                    }
                    sampleIndex += chunkSamples
                    track.write(pcmBuffer, 0, chunkSamples)
                }
            } catch (_: Exception) {
            } finally {
                runCatching {
                    track.stop()
                    track.release()
                }
            }
        }
    }

    fun stopSoundscape() {
        _state.value = _state.value.copy(isPlayingSoundscape = false)
        playJob?.cancel()
        playJob = null
        runCatching {
            activeTrack?.stop()
            activeTrack?.release()
        }
        activeTrack = null
    }
}
