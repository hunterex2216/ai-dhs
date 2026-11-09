package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PermanentScreenCapture"
private const val CHANNEL_ID = "archer_permanent_screen_channel"
private const val NOTIFICATION_ID = 4409
private const val PREFS_SCREEN_SHARE = "archer_screen_share_prefs"
private const val KEY_PERMANENT_SCREEN_ENABLED = "permanent_screen_share_enabled"

data class ScreenCaptureSessionState(
    val hasMediaProjectionGrant: Boolean = false,
    val isCapturingFrames: Boolean = false,
    val isPermanentAutoResumeEnabled: Boolean = false,
    val capturedFrameCount: Int = 0,
    val statusBn: String = "স্ক্রিন শেয়ার স্ট্যান্ডবাই — প্রথমবার অন করলে পারমিশন নেবে, এরপর অটোমেটিক চলবে"
)

object ScreenCaptureSessionBus {
    private val _state = MutableStateFlow(ScreenCaptureSessionState())
    val state: StateFlow<ScreenCaptureSessionState> = _state.asStateFlow()

    private val _latestBitmapFlow = MutableStateFlow<Bitmap?>(null)
    val latestBitmapFlow: StateFlow<Bitmap?> = _latestBitmapFlow.asStateFlow()

    private val _permissionRequestEvents = MutableSharedFlow<Long>(extraBufferCapacity = 4)
    val permissionRequestEvents: SharedFlow<Long> = _permissionRequestEvents.asSharedFlow()

    @Volatile
    var latestCapturedBitmap: Bitmap? = null
        private set

    fun isPermanentlyReady(): Boolean {
        return _state.value.hasMediaProjectionGrant ||
            AccessibilityAutomationBus.state.value.isServiceEnabled
    }

    fun syncSavedPreference(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_SCREEN_SHARE, Context.MODE_PRIVATE)
        val saved = prefs.getBoolean(KEY_PERMANENT_SCREEN_ENABLED, true)
        _state.value = _state.value.copy(isPermanentAutoResumeEnabled = saved)
        return saved || _state.value.hasMediaProjectionGrant || AccessibilityAutomationBus.state.value.isServiceEnabled
    }

    fun setPermanentAutoResume(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_SCREEN_SHARE, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PERMANENT_SCREEN_ENABLED, enabled).apply()
        _state.value = _state.value.copy(isPermanentAutoResumeEnabled = enabled)
    }

    fun requestOneTimeScreenCapturePermission() {
        _permissionRequestEvents.tryEmit(System.currentTimeMillis())
    }

    fun onFrameCaptured(bitmap: Bitmap, fromMediaProjection: Boolean = true) {
        latestCapturedBitmap = bitmap
        _latestBitmapFlow.value = bitmap
        val nextCount = _state.value.capturedFrameCount + 1
        _state.value = _state.value.copy(
            hasMediaProjectionGrant = if (fromMediaProjection) true else _state.value.hasMediaProjectionGrant,
            isCapturingFrames = true,
            capturedFrameCount = nextCount,
            statusBn = if (fromMediaProjection || _state.value.hasMediaProjectionGrant) {
                "হাই-স্পিড লাইভ স্ক্রিন শেয়ার সচল (ফ্রেম #$nextCount)"
            } else {
                "অ্যাক্সেসিবিলিটি লাইভ স্ক্রিন ভিশন সচল (ফ্রেম #$nextCount)"
            }
        )
    }

    fun updateSessionState(
        hasGrant: Boolean,
        isCapturing: Boolean,
        statusBn: String
    ) {
        _state.value = _state.value.copy(
            hasMediaProjectionGrant = hasGrant,
            isCapturingFrames = isCapturing,
            statusBn = statusBn
        )
    }

    fun captureBestAvailableScreenFrame(onBitmapReady: (Bitmap) -> Unit) {
        val cached = latestCapturedBitmap
        if (_state.value.hasMediaProjectionGrant && _state.value.isCapturingFrames && cached != null && !cached.isRecycled) {
            onBitmapReady(cached)
            return
        }
        AccessibilityAutomationBus.requestLiveScreenFrame { bmp ->
            onFrameCaptured(bmp, fromMediaProjection = false)
            onBitmapReady(bmp)
        }
    }
}

class PermanentScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val thread = HandlerThread("ArcherScreenCaptureThread").apply { start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_CAPTURE
        if (action == ACTION_STOP_CAPTURE) {
            stopScreenProjection()
            ScreenCaptureSessionBus.updateSessionState(
                hasGrant = false,
                isCapturing = false,
                statusBn = "স্ক্রিন শেয়ার বন্ধ করা হয়েছে"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start mediaProjection foreground service: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultData != null && (resultCode == Activity.RESULT_OK || mediaProjection == null)) {
            setupMediaProjection(resultCode, resultData)
        }

        return START_NOT_STICKY
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        try {
            stopScreenProjection()
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mgr.getMediaProjection(resultCode, resultData) ?: return
            mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ScreenCaptureSessionBus.updateSessionState(
                        hasGrant = false,
                        isCapturing = false,
                        statusBn = "লাইভ স্ক্রিন শেয়ার থামানো হয়েছে"
                    )
                }
            }, backgroundHandler)

            val dm = resources.displayMetrics
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val isLowRam = am?.isLowRamDevice == true || Runtime.getRuntime().maxMemory() <= 192L * 1024L * 1024L
            val divisor = if (isLowRam) 3 else 2
            val captureWidth = ((dm.widthPixels / divisor).coerceIn(280, if (isLowRam) 480 else 640)) and -2
            val captureHeight = ((dm.heightPixels / divisor).coerceIn(480, if (isLowRam) 854 else 1180)) and -2
            val density = dm.densityDpi

            val reader = ImageReader.newInstance(
                captureWidth,
                captureHeight,
                PixelFormat.RGBA_8888,
                2
            )
            imageReader = reader

            var lastFrameTimestamp = 0L
            val frameIntervalMs = if (isLowRam) 180L else 120L
            reader.setOnImageAvailableListener({ imgReader ->
                val image = try {
                    imgReader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val now = System.currentTimeMillis()
                    if (now - lastFrameTimestamp >= frameIntervalMs) {
                        lastFrameTimestamp = now
                        val plane = image.planes.firstOrNull()
                        if (plane != null) {
                            val buffer = plane.buffer
                            buffer.rewind()
                            val pixelStride = plane.pixelStride.coerceAtLeast(4)
                            val rowStride = plane.rowStride
                            val rowPadding = (rowStride - pixelStride * captureWidth).coerceAtLeast(0)
                            val bmpWidth = captureWidth + rowPadding / pixelStride
                            val requiredBytes = bmpWidth * captureHeight * pixelStride
                            if (buffer.remaining() >= requiredBytes) {
                                val rawBmp = Bitmap.createBitmap(
                                    bmpWidth,
                                    captureHeight,
                                    Bitmap.Config.ARGB_8888
                                )
                                rawBmp.copyPixelsFromBuffer(buffer)
                                val cropped = if (bmpWidth > captureWidth) {
                                    val sub = Bitmap.createBitmap(rawBmp, 0, 0, captureWidth, captureHeight)
                                    rawBmp.recycle()
                                    sub
                                } else {
                                    rawBmp
                                }
                                ScreenCaptureSessionBus.onFrameCaptured(cropped, fromMediaProjection = true)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Frame decode error: ${e.message}")
                } finally {
                    try {
                        image.close()
                    } catch (_: Exception) {
                    }
                }
            }, backgroundHandler)

            virtualDisplay = projection.createVirtualDisplay(
                "ArcherPermanentScreenDisplay",
                captureWidth,
                captureHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                backgroundHandler
            )

            ScreenCaptureSessionBus.setPermanentAutoResume(applicationContext, true)
            ScreenCaptureSessionBus.updateSessionState(
                hasGrant = true,
                isCapturing = true,
                statusBn = "পার্মানেন্ট লাইভ স্ক্রিন শেয়ার সচল — এজেন্ট সরাসরি আপনার স্ক্রিন দেখছে"
            )
        } catch (e: Exception) {
            Log.e(TAG, "setupMediaProjection failed: ${e.message}")
            ScreenCaptureSessionBus.updateSessionState(
                hasGrant = false,
                isCapturing = false,
                statusBn = "অ্যাক্সেসিবিলিটি লাইভ স্ক্রিন ভিশন সচল আছে"
            )
        }
    }

    private fun stopScreenProjection() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            mediaProjection?.stop()
        } catch (_: Exception) {
        }
        mediaProjection = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopScreenProjection()
        AppTaskRemovedShutdownBus.shutdownEverythingOnTaskRemoved(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopScreenProjection()
        try {
            backgroundThread?.quitSafely()
        } catch (_: Exception) {
        }
        backgroundThread = null
        backgroundHandler = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ARCHER AI Permanent Screen Vision",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps live screen sharing ready so Archer AI can inspect the screen anytime without re-prompting."
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
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("ARCHER AI — পার্মানেন্ট স্ক্রিন ভিশন সচল")
            .setContentText("স্ক্রিন শেয়ার প্রস্তুত: 'স্ক্রিনে দেখ' বললেই সাথে সাথে স্ক্রিন দেখে কাজ করবে")
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START_CAPTURE = "com.example.service.action.START_SCREEN_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.example.service.action.STOP_SCREEN_CAPTURE"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startWithProjectionGrant(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, PermanentScreenCaptureService::class.java).apply {
                action = ACTION_START_CAPTURE
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            ScreenCaptureSessionBus.updateSessionState(
                hasGrant = false,
                isCapturing = false,
                statusBn = "স্ক্রিন শেয়ার বন্ধ করা হয়েছে"
            )
            try {
                val intent = Intent(context, PermanentScreenCaptureService::class.java).apply {
                    action = ACTION_STOP_CAPTURE
                }
                context.startService(intent)
            } catch (_: Exception) {
            }
        }
    }
}
