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
import androidx.core.content.ContextCompat

/**
 * Central coordinator that completely stops all background/foreground services, Gemini Live audio/WebSocket,
 * wake-word listeners, screen capture, and floating overlays whenever the user swipes/removes the app from Recent Apps.
 */
object AppTaskRemovedShutdownBus {
    @Volatile
    private var viewModelShutdownCallback: (() -> Unit)? = null

    fun registerViewModelShutdown(callback: () -> Unit) {
        viewModelShutdownCallback = callback
    }

    fun shutdownEverythingOnTaskRemoved(context: Context) {
        val appCtx = context.applicationContext
        // 1. Immediately destroy and lock out the Floating AI Orb, Neon Target Ring, and Touch Overlays
        FloatingAvatarOverlayBus.setAppSessionAlive(false)
        ConfirmationGateBus.clearTargetLock()
        TouchEffectOverlay.hide()

        // 2. Cancel all pending Accessibility macros
        AccessibilityAutomationBus.cancelAllPendingMacros()

        // 3. Stop ViewModel / Gemini Live WebSocket / Mic / Speaker / TTS / Music
        try {
            viewModelShutdownCallback?.invoke()
        } catch (_: Exception) {
        }
        ExternalMicArbiter.setGeminiLiveOwningMic(false)

        // 4. Stop all foreground and background services
        LiveKeepAliveService.stop(appCtx)
        CubeWakeWordService.stopService(appCtx)
        PermanentScreenCaptureService.stopService(appCtx)
        try {
            appCtx.stopService(Intent(appCtx, UnifiedAudioRouterService::class.java))
        } catch (_: Exception) {
        }

        // 5. Clear any lingering foreground notifications
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancelAll()
        } catch (_: Exception) {
        }
    }
}

/**
 * Keeps Gemini Live (mic + connection) alive when the app is minimized, and cleanly shuts down
 * everything when the user swipes the app away from Recent Apps.
 */
class LiveKeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CH, "Archer Live", NotificationManager.IMPORTANCE_LOW))
        }
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, LiveKeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CH) else Notification.Builder(this)
        val n = b.setContentTitle("ARCHER Live চলছে")
            .setContentText("আমি শুনছি ও স্ক্রিন দেখছি — বন্ধ করতে চাপুন")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "STOP", stopPi).build())
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(ID, n)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AppTaskRemovedShutdownBus.shutdownEverythingOnTaskRemoved(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    companion object {
        private const val CH = "archer_live"
        private const val ID = 4107
        const val ACTION_STOP = "com.example.service.LIVE_STOP"

        fun start(ctx: Context) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, LiveKeepAliveService::class.java)) } catch (_: Exception) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, LiveKeepAliveService::class.java)) } catch (_: Exception) {}
        }
    }
}
