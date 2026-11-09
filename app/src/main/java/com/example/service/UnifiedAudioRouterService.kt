package com.example.service

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "UnifiedAudioRouter"

enum class AudioRouteMode(
    val id: String,
    val titleBn: String,
    val badgeLabel: String
) {
    AUTO_SMART(
        id = "AUTO_SMART",
        titleBn = "🔄 অটো স্মার্ট (Headset ⇄ Speaker)",
        badgeLabel = "AUTO SMART"
    ),
    PRIMARY_SPEAKER(
        id = "PRIMARY_SPEAKER",
        titleBn = "🔊 মেইন লাউডস্পিকার (Primary Speaker)",
        badgeLabel = "SPEAKER"
    ),
    BLUETOOTH_HEADSET(
        id = "BLUETOOTH_HEADSET",
        titleBn = "🎧 ব্লুটুথ হেডসেট / ইয়ারবাডস",
        badgeLabel = "BLUETOOTH"
    ),
    WIRED_HEADSET(
        id = "WIRED_HEADSET",
        titleBn = "🔌 তারযুক্ত / USB হেডফোন",
        badgeLabel = "WIRED/USB"
    ),
    EARPIECE_PRIVATE(
        id = "EARPIECE_PRIVATE",
        titleBn = "👂 প্রাইভেট ইয়ারপিস ( কানের স্পিকার )",
        badgeLabel = "EARPIECE"
    );

    companion object {
        fun fromIdOrHint(raw: String): AudioRouteMode {
            val clean = raw.trim().uppercase()
            return when {
                clean.contains("SPEAKER") || clean.contains("LOUDSPEAKER") || clean.contains("স্পিকার") || clean.contains("লাউড") -> PRIMARY_SPEAKER
                clean.contains("BLUETOOTH") || clean.contains("BT") || clean.contains("ব্লুটুথ") || clean.contains("ইয়ারবাড") || clean.contains("এয়ারপড") -> BLUETOOTH_HEADSET
                clean.contains("WIRED") || clean.contains("USB") || clean.contains("HEADPHONE") || clean.contains("তারযুক্ত") || clean.contains("হেডফোন") || clean.contains("ইয়ারফোন") -> WIRED_HEADSET
                clean.contains("EARPIECE") || clean.contains("PRIVATE") || clean.contains("ইয়ারপিস") || clean.contains("কানে") -> EARPIECE_PRIVATE
                else -> AUTO_SMART
            }
        }
    }
}

enum class ActiveAudioEndpointType(
    val labelBn: String,
    val shortBadge: String
) {
    PRIMARY_SPEAKER("মেইন লাউডস্পিকার (Primary Speaker)", "SPEAKER"),
    BLUETOOTH_HEADSET("ব্লুটুথ হেডসেট / ইয়ারবাডস", "BLUETOOTH"),
    WIRED_OR_USB_HEADSET("তারযুক্ত / USB হেডফোন", "WIRED/USB"),
    EARPIECE("ফোন ইয়ারপিস (Earpiece)", "EARPIECE")
}

data class ConnectedAudioEndpoint(
    val id: Int,
    val productName: String,
    val type: ActiveAudioEndpointType,
    val isInputCapable: Boolean,
    val isOutputCapable: Boolean,
    val androidDeviceType: Int
)

data class UnifiedAudioRouterState(
    val selectedRouteMode: AudioRouteMode = AudioRouteMode.AUTO_SMART,
    val activeOutputEndpoint: ActiveAudioEndpointType = ActiveAudioEndpointType.PRIMARY_SPEAKER,
    val activeInputEndpoint: ActiveAudioEndpointType = ActiveAudioEndpointType.PRIMARY_SPEAKER,
    val activeOutputDeviceName: String = "মেইন লাউডস্পিকার",
    val activeInputDeviceName: String = "প্রাইমারি মাইক্রোফোন",
    val isBluetoothHeadsetConnected: Boolean = false,
    val bluetoothDeviceName: String = "",
    val isBluetoothScoActive: Boolean = false,
    val isWiredHeadsetConnected: Boolean = false,
    val wiredDeviceName: String = "",
    val isVoiceClarityDspEnabled: Boolean = true,
    val connectedEndpoints: List<ConnectedAudioEndpoint> = emptyList(),
    val lastRouteEventBn: String = "স্মার্ট অডিও রাউটিং সক্রিয় — স্পিকার ও হেডসেট অটো-সুইচ প্রস্তুত"
)

/**
 * Singleton state & controller bus for Unified Audio Processing and Dynamic Routing.
 * Seamlessly routes both Agent Voice Output (`AudioTrack` 24kHz PCM + Android `TextToSpeech`)
 * and User Voice Commands (`AudioRecord` 16kHz PCM + Android `SpeechRecognizer`) between the
 * Primary Speaker and connected Bluetooth / Wired / USB headsets in real time.
 */
object UnifiedAudioRouterBus {
    private const val PREFS_NAME = "archer_unified_audio_router_prefs"
    private const val KEY_ROUTE_MODE = "preferred_audio_route_mode"
    private const val KEY_CLARITY_DSP = "voice_clarity_dsp_enabled"

    private val _state = MutableStateFlow(UnifiedAudioRouterState())
    val state: StateFlow<UnifiedAudioRouterState> = _state.asStateFlow()

    @Volatile
    private var initialized = false
    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeAudioTrackRef: AudioTrack? = null

    @Volatile
    private var activeAudioRecordRef: AudioRecord? = null

    private var audioFocusRequest: AudioFocusRequest? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevicesAndApplyRouting("নতুন অডিও ডিভাইস সংযুক্ত হয়েছে")
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshDevicesAndApplyRouting("অডিও ডিভাইস বিচ্ছিন্ন হয়েছে — প্রাইমারি স্পিকারে সুইচ করা হয়েছে")
        }
    }

    private val headsetBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                Intent.ACTION_HEADSET_PLUG,
                AudioManager.ACTION_AUDIO_BECOMING_NOISY,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED,
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    mainHandler.postDelayed({
                        refreshDevicesAndApplyRouting("হেডসেট/ব্লুটুথ সংযোগ পরিবর্তিত হয়েছে")
                    }, 150L)
                }
            }
        }
    }

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        if (initialized) return
        initialized = true

        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager = am

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedMode = AudioRouteMode.fromIdOrHint(
            prefs.getString(KEY_ROUTE_MODE, AudioRouteMode.AUTO_SMART.id).orEmpty()
        )
        val clarityDsp = prefs.getBoolean(KEY_CLARITY_DSP, true)
        _state.value = _state.value.copy(
            selectedRouteMode = savedMode,
            isVoiceClarityDspEnabled = clarityDsp
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(headsetBroadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(headsetBroadcastReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Receiver registration note: ${e.message}")
        }

        // Start the companion background service to keep routing alive during screen-off / cross-app sessions
        try {
            ctx.startService(Intent(ctx, UnifiedAudioRouterService::class.java))
        } catch (_: Exception) {
        }

        refreshDevicesAndApplyRouting("ইউনিফাইড অডিও প্রসেসিং ও রাউটার প্রস্তুত")
    }

    fun setRouteMode(mode: AudioRouteMode): String {
        val ctx = appContext
        if (ctx != null) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ROUTE_MODE, mode.id)
                .apply()
        }
        _state.value = _state.value.copy(selectedRouteMode = mode)
        refreshDevicesAndApplyRouting("অডিও রুট মোড পরিবর্তন: ${mode.titleBn}")
        val snap = _state.value
        return "জি স্যার, অডিও রাউটিং '${mode.titleBn}' মোডে সেট করা হয়েছে (আউটপুট: ${snap.activeOutputDeviceName}, মাইক: ${snap.activeInputDeviceName})।"
    }

    fun setVoiceClarityDspEnabled(enabled: Boolean) {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putBoolean(KEY_CLARITY_DSP, enabled)
            ?.apply()
        _state.value = _state.value.copy(
            isVoiceClarityDspEnabled = enabled,
            lastRouteEventBn = if (enabled) {
                "ভয়েস ক্ল্যারিটি ও অটো-লেভেলিং DSP চালু করা হয়েছে"
            } else {
                "ভয়েস ক্ল্যারিটি DSP বন্ধ করা হয়েছে"
            }
        )
    }

    /**
     * Registers the active Gemini Live 24kHz `AudioTrack` and immediately routes it
     * to the resolved output device (Bluetooth headset, Wired headset, or Primary Speaker).
     */
    fun attachAndRouteAudioTrack(track: AudioTrack?) {
        activeAudioTrackRef = track
        applyOutputRoutingInternal(track)
    }

    /**
     * Registers the active Gemini Live 16kHz `AudioRecord` and immediately routes it
     * to the resolved input microphone (Bluetooth SCO mic, Wired inline mic, or Primary Mic).
     */
    fun attachAndRouteAudioRecord(recorder: AudioRecord?) {
        activeAudioRecordRef = recorder
        applyInputRoutingInternal(recorder)
    }

    /**
     * Prepares audio routing right before Android `TextToSpeech` speaks so TTS voices
     * also follow the exact same speaker/headset routing policy.
     */
    fun prepareForTtsOutput() {
        applyOutputRoutingInternal(activeAudioTrackRef)
    }

    /**
     * Prepares audio routing right before `SpeechRecognizer` starts listening so voice commands
     * are captured seamlessly from a connected Bluetooth/wired headset or phone mic.
     */
    fun prepareForSttInput() {
        applyInputRoutingInternal(activeAudioRecordRef)
    }

    /**
     * Returns whether an external headset (Bluetooth or Wired/USB) is currently the active output,
     * allowing the barge-in detector to use a much more sensitive threshold because headset playbacks
     * do not bleed into the microphone like the loudspeaker does.
     */
    fun isHeadsetCurrentlyActive(): Boolean {
        val out = _state.value.activeOutputEndpoint
        return out == ActiveAudioEndpointType.BLUETOOTH_HEADSET ||
            out == ActiveAudioEndpointType.WIRED_OR_USB_HEADSET
    }

    /**
     * Unified Output Audio DSP Processor for 24kHz 16-bit PCM chunks from Gemini Live:
     * Applies clarity presence boost & soft-clipping protection tailored to whether the user
     * is listening on the Primary Speaker vs. Bluetooth/Wired Headset.
     */
    fun processOutputPcm24kHz(rawPcmBytes: ByteArray): ByteArray {
        if (!_state.value.isVoiceClarityDspEnabled || rawPcmBytes.size < 4) {
            return rawPcmBytes
        }
        val gainMultiplier = when (_state.value.activeOutputEndpoint) {
            ActiveAudioEndpointType.PRIMARY_SPEAKER -> 1.18 // Extra presence & loudness on phone speaker
            ActiveAudioEndpointType.BLUETOOTH_HEADSET -> 1.06 // Clean balanced headroom for Bluetooth codecs
            ActiveAudioEndpointType.WIRED_OR_USB_HEADSET -> 1.05
            ActiveAudioEndpointType.EARPIECE -> 1.10
        }
        if (gainMultiplier == 1.0) return rawPcmBytes

        val out = ByteArray(rawPcmBytes.size)
        val inBuf = ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        val outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val sampleCount = rawPcmBytes.size / 2
        for (i in 0 until sampleCount) {
            val s = inBuf.short.toDouble() * gainMultiplier
            // Soft knee limiter to prevent harsh digital clipping at peak volumes
            val clamped = when {
                s > 30000.0 -> 30000.0 + (s - 30000.0) * 0.25
                s < -30000.0 -> -30000.0 + (s + 30000.0) * 0.25
                else -> s
            }.toInt().coerceIn(-32760, 32760).toShort()
            outBuf.putShort(clamped)
        }
        return out
    }

    /**
     * Scans all connected Input and Output audio peripherals and applies the active routing policy
     * immediately to `AudioManager`, `AudioTrack`, and `AudioRecord`.
     */
    @Suppress("DEPRECATION")
    fun refreshDevicesAndApplyRouting(reasonBn: String = "") {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

            val btOut = outputs.firstOrNull { isBluetoothDeviceType(it.type) }
            val wiredOut = outputs.firstOrNull { isWiredOrUsbDeviceType(it.type) }
            val speakerOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpieceOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

            val btIn = inputs.firstOrNull { isBluetoothDeviceType(it.type) }
            val wiredIn = inputs.firstOrNull { isWiredOrUsbDeviceType(it.type) }
            val builtinIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

            val endpointMap = linkedMapOf<String, ConnectedAudioEndpoint>()
            (outputs + inputs).forEach { dev ->
                val epType = classifyEndpointType(dev.type) ?: return@forEach
                val prodName = dev.productName?.toString()?.trim().orEmpty().ifBlank { epType.labelBn }
                val key = "${epType.name}_$prodName"
                val existing = endpointMap[key]
                endpointMap[key] = ConnectedAudioEndpoint(
                    id = dev.id,
                    productName = prodName,
                    type = epType,
                    isInputCapable = (existing?.isInputCapable == true) || dev.isSource,
                    isOutputCapable = (existing?.isOutputCapable == true) || dev.isSink,
                    androidDeviceType = dev.type
                )
            }

            val mode = _state.value.selectedRouteMode
            val targetOutputDevice: AudioDeviceInfo? = when (mode) {
                AudioRouteMode.AUTO_SMART -> btOut ?: wiredOut ?: speakerOut ?: earpieceOut
                AudioRouteMode.PRIMARY_SPEAKER -> speakerOut ?: btOut ?: wiredOut
                AudioRouteMode.BLUETOOTH_HEADSET -> btOut ?: wiredOut ?: speakerOut
                AudioRouteMode.WIRED_HEADSET -> wiredOut ?: btOut ?: speakerOut
                AudioRouteMode.EARPIECE_PRIVATE -> earpieceOut ?: wiredOut ?: speakerOut
            }

            val targetInputDevice: AudioDeviceInfo? = when (mode) {
                AudioRouteMode.AUTO_SMART -> btIn ?: wiredIn ?: builtinIn
                AudioRouteMode.PRIMARY_SPEAKER -> builtinIn ?: wiredIn ?: btIn
                AudioRouteMode.BLUETOOTH_HEADSET -> btIn ?: wiredIn ?: builtinIn
                AudioRouteMode.WIRED_HEADSET -> wiredIn ?: btIn ?: builtinIn
                AudioRouteMode.EARPIECE_PRIVATE -> wiredIn ?: builtinIn
            }

            val resolvedOutputType = classifyEndpointType(targetOutputDevice?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                ?: ActiveAudioEndpointType.PRIMARY_SPEAKER
            val resolvedInputType = classifyEndpointType(targetInputDevice?.type ?: AudioDeviceInfo.TYPE_BUILTIN_MIC)
                ?: ActiveAudioEndpointType.PRIMARY_SPEAKER

            val outName = formatFriendlyDeviceName(targetOutputDevice, resolvedOutputType, isOutput = true)
            val inName = formatFriendlyDeviceName(targetInputDevice, resolvedInputType, isOutput = false)

            _state.value = _state.value.copy(
                activeOutputEndpoint = resolvedOutputType,
                activeInputEndpoint = resolvedInputType,
                activeOutputDeviceName = outName,
                activeInputDeviceName = inName,
                isBluetoothHeadsetConnected = btOut != null || btIn != null,
                bluetoothDeviceName = (btOut?.productName ?: btIn?.productName)?.toString().orEmpty().ifBlank {
                    if (btOut != null || btIn != null) "Bluetooth Headset" else ""
                },
                isWiredHeadsetConnected = wiredOut != null || wiredIn != null,
                wiredDeviceName = (wiredOut?.productName ?: wiredIn?.productName)?.toString().orEmpty().ifBlank {
                    if (wiredOut != null || wiredIn != null) "Wired/USB Headset" else ""
                },
                connectedEndpoints = endpointMap.values.toList(),
                lastRouteEventBn = if (reasonBn.isNotBlank()) {
                    "$reasonBn • আউটপুট: $outName | মাইক: $inName"
                } else {
                    "আউটপুট: $outName • মাইক: $inName"
                }
            )

            applySystemAndPeripheralRouting(am, targetOutputDevice, targetInputDevice, resolvedOutputType)
        } catch (e: Exception) {
            Log.w(TAG, "Audio routing refresh warning: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun applyOutputRoutingInternal(track: AudioTrack?) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            val btOut = outputs.firstOrNull { isBluetoothDeviceType(it.type) }
            val wiredOut = outputs.firstOrNull { isWiredOrUsbDeviceType(it.type) }
            val speakerOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            val earpieceOut = outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }

            val targetOutputDevice = when (_state.value.selectedRouteMode) {
                AudioRouteMode.AUTO_SMART -> btOut ?: wiredOut ?: speakerOut ?: earpieceOut
                AudioRouteMode.PRIMARY_SPEAKER -> speakerOut ?: btOut ?: wiredOut
                AudioRouteMode.BLUETOOTH_HEADSET -> btOut ?: wiredOut ?: speakerOut
                AudioRouteMode.WIRED_HEADSET -> wiredOut ?: btOut ?: speakerOut
                AudioRouteMode.EARPIECE_PRIVATE -> earpieceOut ?: wiredOut ?: speakerOut
            }
            val resolvedType = classifyEndpointType(targetOutputDevice?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                ?: ActiveAudioEndpointType.PRIMARY_SPEAKER

            applySystemAndPeripheralRouting(am, targetOutputDevice, null, resolvedType)
            if (track != null && targetOutputDevice != null) {
                track.setPreferredDevice(targetOutputDevice)
            }

            // Ensure STREAM_MUSIC is audible
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0 && curVol == 0) {
                val targetVol = (maxVol * 0.60f).toInt().coerceAtLeast(1)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyOutputRoutingInternal warning: ${e.message}")
        }
    }

    private fun applyInputRoutingInternal(recorder: AudioRecord?) {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            val btIn = inputs.firstOrNull { isBluetoothDeviceType(it.type) }
            val wiredIn = inputs.firstOrNull { isWiredOrUsbDeviceType(it.type) }
            val builtinIn = inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

            val targetInputDevice = when (_state.value.selectedRouteMode) {
                AudioRouteMode.AUTO_SMART -> btIn ?: wiredIn ?: builtinIn
                AudioRouteMode.PRIMARY_SPEAKER -> builtinIn ?: wiredIn ?: btIn
                AudioRouteMode.BLUETOOTH_HEADSET -> btIn ?: wiredIn ?: builtinIn
                AudioRouteMode.WIRED_HEADSET -> wiredIn ?: btIn ?: builtinIn
                AudioRouteMode.EARPIECE_PRIVATE -> wiredIn ?: builtinIn
            }
            if (recorder != null && targetInputDevice != null) {
                recorder.setPreferredDevice(targetInputDevice)
            }
            if (targetInputDevice != null && isBluetoothDeviceType(targetInputDevice.type)) {
                enableBluetoothScoIfNeeded(am, true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyInputRoutingInternal warning: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun applySystemAndPeripheralRouting(
        am: AudioManager,
        targetOut: AudioDeviceInfo?,
        targetIn: AudioDeviceInfo?,
        resolvedOutputType: ActiveAudioEndpointType
    ) {
        try {
            when (resolvedOutputType) {
                ActiveAudioEndpointType.BLUETOOTH_HEADSET -> {
                    am.isSpeakerphoneOn = false
                    enableBluetoothScoIfNeeded(am, true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && targetOut != null) {
                        try {
                            val commDevs = am.availableCommunicationDevices
                            val btComm = commDevs.firstOrNull { isBluetoothDeviceType(it.type) }
                            if (btComm != null) {
                                am.setCommunicationDevice(btComm)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                ActiveAudioEndpointType.WIRED_OR_USB_HEADSET -> {
                    enableBluetoothScoIfNeeded(am, false)
                    am.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val commDevs = am.availableCommunicationDevices
                            val wiredComm = commDevs.firstOrNull { isWiredOrUsbDeviceType(it.type) }
                            if (wiredComm != null) {
                                am.setCommunicationDevice(wiredComm)
                            } else {
                                am.clearCommunicationDevice()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                ActiveAudioEndpointType.EARPIECE -> {
                    enableBluetoothScoIfNeeded(am, false)
                    am.isSpeakerphoneOn = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val commDevs = am.availableCommunicationDevices
                            val earComm = commDevs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                            if (earComm != null) {
                                am.setCommunicationDevice(earComm)
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
                ActiveAudioEndpointType.PRIMARY_SPEAKER -> {
                    enableBluetoothScoIfNeeded(am, false)
                    if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
                        am.mode = AudioManager.MODE_NORMAL
                    }
                    am.isSpeakerphoneOn = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        try {
                            val commDevs = am.availableCommunicationDevices
                            val spkComm = commDevs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                            if (spkComm != null) {
                                am.setCommunicationDevice(spkComm)
                            } else {
                                am.clearCommunicationDevice()
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                activeAudioTrackRef?.let { track ->
                    if (targetOut != null) {
                        try { track.setPreferredDevice(targetOut) } catch (_: Exception) {}
                    }
                }
                activeAudioRecordRef?.let { rec ->
                    if (targetIn != null) {
                        try { rec.setPreferredDevice(targetIn) } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "System routing apply note: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableBluetoothScoIfNeeded(am: AudioManager, enable: Boolean) {
        try {
            if (enable) {
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                _state.value = _state.value.copy(isBluetoothScoActive = true)
            } else {
                if (am.isBluetoothScoOn) {
                    am.isBluetoothScoOn = false
                    am.stopBluetoothSco()
                }
                _state.value = _state.value.copy(isBluetoothScoActive = false)
            }
        } catch (_: Exception) {
        }
    }

    private fun isBluetoothDeviceType(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && (
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                    type == AudioDeviceInfo.TYPE_BLE_BROADCAST
                ))
    }

    private fun isWiredOrUsbDeviceType(type: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    }

    private fun classifyEndpointType(type: Int): ActiveAudioEndpointType? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return ActiveAudioEndpointType.PRIMARY_SPEAKER
        return when {
            isBluetoothDeviceType(type) -> ActiveAudioEndpointType.BLUETOOTH_HEADSET
            isWiredOrUsbDeviceType(type) -> ActiveAudioEndpointType.WIRED_OR_USB_HEADSET
            type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ActiveAudioEndpointType.EARPIECE
            type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                type == AudioDeviceInfo.TYPE_BUILTIN_MIC -> ActiveAudioEndpointType.PRIMARY_SPEAKER
            else -> null
        }
    }

    private fun formatFriendlyDeviceName(
        dev: AudioDeviceInfo?,
        endpointType: ActiveAudioEndpointType,
        isOutput: Boolean
    ): String {
        if (dev == null) {
            return if (isOutput) "মেইন লাউডস্পিকার" else "প্রাইমারি মাইক্রোফোন"
        }
        val rawName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.productName?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        return when (endpointType) {
            ActiveAudioEndpointType.BLUETOOTH_HEADSET -> {
                if (rawName.isNotBlank() && !rawName.equals("Builtin", ignoreCase = true)) {
                    "🎧 ব্লুটুথ ($rawName)"
                } else {
                    "🎧 ব্লুটুথ হেডসেট"
                }
            }
            ActiveAudioEndpointType.WIRED_OR_USB_HEADSET -> {
                if (rawName.isNotBlank() && !rawName.equals("Builtin", ignoreCase = true)) {
                    "🔌 হেডফোন ($rawName)"
                } else {
                    "🔌 তারযুক্ত/USB হেডফোন"
                }
            }
            ActiveAudioEndpointType.EARPIECE -> "👂 প্রাইভেট ইয়ারপিস"
            ActiveAudioEndpointType.PRIMARY_SPEAKER -> {
                if (isOutput) "🔊 মেইন লাউডস্পিকার" else "🎙️ প্রাইমারি ফোন মাইক"
            }
        }
    }
}

/**
 * Bound & Started Android Service that maintains continuous audio peripheral monitoring
 * and unified voice/command routing between the primary speaker and connected Bluetooth/wired headsets.
 */
class UnifiedAudioRouterService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): UnifiedAudioRouterService = this@UnifiedAudioRouterService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        UnifiedAudioRouterBus.init(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        UnifiedAudioRouterBus.init(applicationContext)
        val requestedRoute = intent?.getStringExtra(EXTRA_ROUTE_MODE).orEmpty()
        if (requestedRoute.isNotBlank()) {
            UnifiedAudioRouterBus.setRouteMode(AudioRouteMode.fromIdOrHint(requestedRoute))
        } else {
            UnifiedAudioRouterBus.refreshDevicesAndApplyRouting()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppTaskRemovedShutdownBus.shutdownEverythingOnTaskRemoved(applicationContext)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val EXTRA_ROUTE_MODE = "extra_route_mode"
    }
}
