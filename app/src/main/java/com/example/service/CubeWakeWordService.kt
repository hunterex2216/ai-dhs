package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.model.WakeWordPhase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class CubeSentinelState(
    val isSentinelActive: Boolean = true,
    val phase: WakeWordPhase = WakeWordPhase.LISTENING_FOR_WAKE_WORD,
    val wakeWords: List<String> = listOf(
        "hey archer", "archer", "আর্চার", "হেই আর্চার", "হে আর্চার", "ও আর্চার", "এই আর্চার",
        "hey jarvis", "jarvis", "জার্ভিস", "জারভিস", "হেই জার্ভিস", "হে জার্ভিস", "ও জার্ভিস", "এই জার্ভিস",
        "hey cube", "cube", "হে কিউব", "কিউব", "হেই কিউব", "ও কিউব", "এই কিউব",
        "hey gemini", "gemini", "জেমিনি", "হেই জেমিনি",
        "একটিভ হও", "অ্যাক্টিভ হও", "চালু হও", "জেগে ওঠো", "কথা শোনো", "wake up"
    ),
    val bossGreeting: String = "জি স্যার, বলুন কি আদেশ? আপনি কি জানতে চান বা কি কাজ করতে হবে?",
    val isLockScreenHudMode: Boolean = false,
    val wakeTriggerCount: Int = 0,
    val lastWakeTimestamp: Long = 0L
)

sealed interface WakeWordEvent {
    data class WakeWordTriggered(val inlineCommand: String = "") : WakeWordEvent
}

object CubeSentinelBus {
    private val _state = MutableStateFlow(CubeSentinelState())
    val state: StateFlow<CubeSentinelState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WakeWordEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<WakeWordEvent> = _events.asSharedFlow()

    fun setSentinelActive(active: Boolean) {
        _state.value = _state.value.copy(
            isSentinelActive = active,
            phase = if (active) WakeWordPhase.LISTENING_FOR_WAKE_WORD else WakeWordPhase.INACTIVE
        )
    }

    fun updatePhase(phase: WakeWordPhase) {
        _state.value = _state.value.copy(phase = phase)
    }

    fun updateBossGreeting(newGreeting: String) {
        if (newGreeting.isNotBlank()) {
            _state.value = _state.value.copy(bossGreeting = newGreeting.trim())
        }
    }

    fun addCustomWakeWord(word: String) {
        val cleaned = word.trim().lowercase()
        if (cleaned.isNotBlank() && !_state.value.wakeWords.contains(cleaned)) {
            _state.value = _state.value.copy(
                wakeWords = _state.value.wakeWords + cleaned
            )
        }
    }

    fun setLockScreenHudMode(enabled: Boolean) {
        _state.value = _state.value.copy(isLockScreenHudMode = enabled)
    }

    fun emitWakeWordDetected(inlineCommand: String = "") {
        _state.value = _state.value.copy(
            phase = WakeWordPhase.GREETING_BOSS,
            wakeTriggerCount = _state.value.wakeTriggerCount + 1,
            lastWakeTimestamp = System.currentTimeMillis()
        )
        _events.tryEmit(WakeWordEvent.WakeWordTriggered(inlineCommand))
    }

    /**
     * Checks if spoken text contains any configured wake word ("Hey Cube", "Cube", "কিউব", etc.).
     * Returns Pair(matched: Boolean, remainingInlineCommand: String).
     */
    fun matchWakeWord(transcript: String): Pair<Boolean, String> {
        val normalized = transcript.trim().lowercase()
        if (normalized.isBlank()) return false to ""

        // Check longer phrases first (e.g. "hey cube" before "cube")
        val sortedWakeWords = _state.value.wakeWords.sortedByDescending { it.length }
        for (wake in sortedWakeWords) {
            val idx = normalized.indexOf(wake)
            if (idx >= 0) {
                val afterWake = transcript.substring((idx + wake.length).coerceAtMost(transcript.length)).trim()
                val cleanedCommand = afterWake.trimStart(',', '.', '!', '?', '-', ':')
                return true to cleanedCommand
            }
        }
        return false to ""
    }
}

class CubeWakeWordService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquirePartialWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SENTINEL -> {
                CubeSentinelBus.setSentinelActive(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SIMULATE_HEY_CUBE -> {
                wakeUpScreenAndBringToFront()
                CubeSentinelBus.emitWakeWordDetected()
            }
            else -> {
                CubeSentinelBus.setSentinelActive(true)
                val notification = buildForegroundNotification()
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } catch (_: Exception) {
                    // Fallback if microphone foreground permission not yet granted
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun acquirePartialWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OmniAgent::CubeAlwaysOnWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Up to 4 hours continuous guard per session
            }
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeUpScreenAndBringToFront() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenWake = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OmniAgent::CubeScreenWake"
            )
            screenWake.acquire(10_000L)
        } catch (_: Exception) {
        }

        try {
            val launchIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_WOKE_BY_CUBE, true)
            }
            startActivity(launchIntent)
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hey Cube Voice Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "লক স্ক্রিন ও ব্যাকগ্রাউন্ডে 'Hey Cube' ভয়েস ডিটেকশন সক্রিয় রাখে"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerIntent = Intent(this, CubeWakeWordService::class.java).apply {
            action = ACTION_SIMULATE_HEY_CUBE
        }
        val pendingTrigger = PendingIntent.getService(
            this,
            101,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, CubeWakeWordService::class.java).apply {
            action = ACTION_STOP_SENTINEL
        }
        val pendingStop = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Hey Cube ভয়েস গার্ড সক্রিয় আছে")
            .setContentText("\"Hey Cube\" বা \"Cube\" বলে ডাকলেই বলবে: জি স্যার, কি আদেশ?")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(0, "Hey Cube ডাকুন", pendingTrigger)
            .addAction(0, "গার্ড বন্ধ করুন", pendingStop)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        CubeSentinelBus.setSentinelActive(false)
        AppTaskRemovedShutdownBus.shutdownEverythingOnTaskRemoved(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "cube_wake_guard_channel"
        const val NOTIFICATION_ID = 4041
        const val ACTION_START_SENTINEL = "com.example.action.START_CUBE_SENTINEL"
        const val ACTION_STOP_SENTINEL = "com.example.action.STOP_CUBE_SENTINEL"
        const val ACTION_SIMULATE_HEY_CUBE = "com.example.action.SIMULATE_HEY_CUBE"
        const val EXTRA_WOKE_BY_CUBE = "extra_woke_by_cube"

        fun startService(context: Context) {
            val intent = Intent(context, CubeWakeWordService::class.java).apply {
                action = ACTION_START_SENTINEL
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                CubeSentinelBus.setSentinelActive(true)
            }
        }

        fun stopService(context: Context) {
            CubeSentinelBus.setSentinelActive(false)
            val intent = Intent(context, CubeWakeWordService::class.java).apply {
                action = ACTION_STOP_SENTINEL
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
