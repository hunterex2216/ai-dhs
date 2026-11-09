package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a staged final action button or OTP paste locked by the Sci-Fi HUD Neon Target Lock
 * waiting for the user's explicit human-in-the-loop confirmation ("হ্যাঁ" / "Yes" or Approve tap).
 */
data class LockedTargetAction(
    val id: String = "lock_${System.currentTimeMillis()}",
    val buttonLabel: String,
    val targetX: Float,
    val targetY: Float,
    val boundsLeft: Int = (targetX - 90f).toInt(),
    val boundsTop: Int = (targetY - 42f).toInt(),
    val boundsRight: Int = (targetX + 90f).toInt(),
    val boundsBottom: Int = (targetY + 42f).toInt(),
    val appPackageOrName: String = "",
    val typedTextPreview: String = "",
    val isOtpConfirmation: Boolean = false,
    val otpCode: String = "",
    val lockedAtMs: Long = System.currentTimeMillis()
)

/**
 * Central bus for Feature #2 (SCI-FI HUD NEON TARGET LOCK) & Feature #4 (STRICT CONFIRMATION GATE).
 * Keeps the screen 100% visible (no black ghost mode) and draws a glowing Neon Target Ring around
 * `(X, Y)` until the user confirms via voice ("হ্যাঁ" / "Yes") or taps Approve on the HUD bar.
 */
object ConfirmationGateBus {
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null

    private val _lockedTarget = MutableStateFlow<LockedTargetAction?>(null)
    val lockedTarget: StateFlow<LockedTargetAction?> = _lockedTarget.asStateFlow()

    var onUserApprovedFromOverlay: ((LockedTargetAction) -> Unit)? = null
    var onUserCancelledFromOverlay: ((LockedTargetAction) -> Unit)? = null

    private var targetLockView: SciFiNeonTargetLockView? = null
    private var actionBarView: SciFiConfirmHudBarView? = null
    private var wm: WindowManager? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    /**
     * Checks if a button label represents a sensitive final action (Send, Install, Submit, Delete, Pay)
     * that MUST go through the 2-step Strict Confirmation Gate (`prepare_and_highlight` -> `execute_confirmed_click`).
     */
    fun isSensitiveFinalActionLabel(label: String): Boolean {
        val lower = label.trim().lowercase()
        if (lower.isBlank()) return false
        val finalKeywords = listOf(
            "send", "install", "submit", "delete", "pay", "confirm", "buy", "purchase", "uninstall", "transfer",
            "সেন্ড", "পাঠান", "পাঠাও", "ইনস্টল", "সাবমিট", "ডিলিট", "মুছুন", "পেমেন্ট", "পে করুন", "কিনুন"
        )
        return finalKeywords.any { lower == it || lower.contains(it) }
    }

    fun lockTargetAndHighlight(
        ctx: Context?,
        buttonLabel: String,
        x: Float,
        y: Float,
        bounds: Rect? = null,
        appPackageOrName: String = "",
        typedTextPreview: String = "",
        isOtpConfirmation: Boolean = false,
        otpCode: String = ""
    ): LockedTargetAction {
        if (ctx != null) appCtx = ctx.applicationContext
        val locked = LockedTargetAction(
            buttonLabel = buttonLabel.ifBlank { "Target Button" },
            targetX = x,
            targetY = y,
            boundsLeft = bounds?.left ?: (x - 95f).toInt(),
            boundsTop = bounds?.top ?: (y - 46f).toInt(),
            boundsRight = bounds?.right ?: (x + 95f).toInt(),
            boundsBottom = bounds?.bottom ?: (y + 46f).toInt(),
            appPackageOrName = appPackageOrName,
            typedTextPreview = typedTextPreview,
            isOtpConfirmation = isOtpConfirmation,
            otpCode = otpCode
        )
        _lockedTarget.value = locked
        main.post { showOrUpdateNeonTargetOverlay(locked) }
        return locked
    }

    fun clearTargetLock() {
        _lockedTarget.value = null
        main.post { hideNeonTargetOverlay() }
    }

    fun consumeLockedTarget(): LockedTargetAction? {
        val current = _lockedTarget.value
        clearTargetLock()
        return current
    }

    private fun resolveOverlayHostContext(): Pair<Context, Int>? {
        val svc = AccessibilityAutomationBus.serviceInstance
        if (svc != null) {
            return svc to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        val fallback = appCtx
        if (fallback != null && Settings.canDrawOverlays(fallback)) {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            return fallback to type
        }
        return null
    }

    private fun showOrUpdateNeonTargetOverlay(target: LockedTargetAction) {
        val (hostCtx, windowType) = resolveOverlayHostContext() ?: return
        try {
            val manager = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm = manager

            // 1. Full-screen transparent non-touchable Neon Target Ring canvas (NO GHOST MODE — screen 100% visible)
            val existingCanvas = targetLockView
            if (existingCanvas != null) {
                existingCanvas.updateTarget(target)
            } else {
                val ringView = SciFiNeonTargetLockView(hostCtx, target)
                val ringLp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                )
                manager.addView(ringView, ringLp)
                targetLockView = ringView
            }

            // 2. Compact touchable Sci-Fi HUD Approve/Cancel Bar so user can tap Approve OR say "হ্যাঁ" / "Yes"
            try {
                actionBarView?.let { manager.removeView(it) }
            } catch (_: Exception) {}
            val density = hostCtx.resources.displayMetrics.density
            val barWidth = (310 * density).toInt()
            val barHeight = (66 * density).toInt()
            val barView = SciFiConfirmHudBarView(
                ctx = hostCtx,
                target = target,
                onApprove = {
                    val approved = consumeLockedTarget() ?: target
                    onUserApprovedFromOverlay?.invoke(approved)
                },
                onCancel = {
                    val cancelled = consumeLockedTarget() ?: target
                    onUserCancelledFromOverlay?.invoke(cancelled)
                }
            )
            val barLp = WindowManager.LayoutParams(
                barWidth,
                barHeight,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                val screenH = hostCtx.resources.displayMetrics.heightPixels
                // Place HUD bar neatly above or below the target so it never covers the locked button itself
                y = if (target.targetY > screenH * 0.45f) {
                    (target.boundsTop - barHeight - (18 * density).toInt()).coerceAtLeast((56 * density).toInt())
                } else {
                    (target.boundsBottom + (18 * density).toInt()).coerceAtMost(screenH - barHeight - (70 * density).toInt())
                }
            }
            manager.addView(barView, barLp)
            actionBarView = barView
        } catch (_: Exception) {
        }
    }

    private fun hideNeonTargetOverlay() {
        try {
            targetLockView?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        try {
            actionBarView?.let { wm?.removeView(it) }
        } catch (_: Exception) {}
        targetLockView = null
        actionBarView = null
    }
}

/**
 * Transparent Sci-Fi HUD Canvas that draws a glowing Neon Target Ring, rotating tactical brackets,
 * and crosshair over the exact `(X, Y)` button coordinates while keeping the screen 100% visible.
 */
private class SciFiNeonTargetLockView(
    ctx: Context,
    @Volatile private var target: LockedTargetAction
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val startUptime = SystemClock.uptimeMillis()

    private val neonCyan = Color.parseColor("#00F0FF")
    private val neonEmerald = Color.parseColor("#00FF66")
    private val neonRose = Color.parseColor("#FF1E6E")
    private val neonGold = Color.parseColor("#FFEA00")

    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.8f * d
        color = neonCyan
    }
    private val dashedRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * d
        color = neonEmerald
        pathEffect = DashPathEffect(floatArrayOf(12f * d, 8f * d), 0f)
    }
    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * d
        strokeCap = Paint.Cap.ROUND
        color = neonGold
    }
    private val glowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E604161E")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11.5f * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val boxRect = RectF()
    private val arcRect = RectF()
    private val badgeRect = RectF()

    fun updateTarget(newTarget: LockedTargetAction) {
        target = newTarget
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val t = target
        val nowMs = SystemClock.uptimeMillis() - startUptime
        val pulse = 0.5f + 0.5f * sin(nowMs / 180.0).toFloat()
        val rotDeg = (nowMs % 3600L).toFloat() / 3600f * 360f

        val cx = t.targetX
        val cy = t.targetY
        val baseRadius = maxOf(
            (t.boundsRight - t.boundsLeft) * 0.52f,
            (t.boundsBottom - t.boundsTop) * 0.62f,
            34f * d
        ).coerceAtMost(130f * d)

        // 1. Soft Neon Radial Aura around target (keeps button text 100% readable)
        glowFillPaint.shader = RadialGradient(
            cx,
            cy,
            baseRadius * 1.45f,
            intArrayOf(
                Color.argb(28, 0, 255, 102),
                Color.argb(65, 0, 240, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.72f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, baseRadius * 1.45f, glowFillPaint)
        glowFillPaint.shader = null

        // 2. Pulsing Neon Target Ring & Rotating Tactical Arcs
        val ringR = baseRadius * (0.94f + 0.10f * pulse)
        outerRingPaint.color = if (t.isOtpConfirmation) neonGold else neonCyan
        outerRingPaint.alpha = (195 + (60 * pulse).toInt()).coerceIn(0, 255)
        c.drawCircle(cx, cy, ringR, outerRingPaint)

        c.save()
        c.rotate(rotDeg, cx, cy)
        dashedRingPaint.color = neonEmerald
        c.drawCircle(cx, cy, baseRadius * 1.14f, dashedRingPaint)
        c.restore()

        arcRect.set(cx - ringR * 1.22f, cy - ringR * 1.22f, cx + ringR * 1.22f, cy + ringR * 1.22f)
        bracketPaint.color = neonRose
        for (i in 0..3) {
            c.drawArc(arcRect, -rotDeg * 1.3f + i * 90f, 32f, false, bracketPaint)
        }

        // 3. Bounding-Box Sci-Fi Corner Brackets around AccessibilityNodeInfo bounds
        boxRect.set(
            t.boundsLeft.toFloat() - 6f * d,
            t.boundsTop.toFloat() - 6f * d,
            t.boundsRight.toFloat() + 6f * d,
            t.boundsBottom.toFloat() + 6f * d
        )
        val cornerLen = 14f * d
        bracketPaint.color = neonGold
        // Top-Left
        c.drawLine(boxRect.left, boxRect.top, boxRect.left + cornerLen, boxRect.top, bracketPaint)
        c.drawLine(boxRect.left, boxRect.top, boxRect.left, boxRect.top + cornerLen, bracketPaint)
        // Top-Right
        c.drawLine(boxRect.right, boxRect.top, boxRect.right - cornerLen, boxRect.top, bracketPaint)
        c.drawLine(boxRect.right, boxRect.top, boxRect.right, boxRect.top + cornerLen, bracketPaint)
        // Bottom-Left
        c.drawLine(boxRect.left, boxRect.bottom, boxRect.left + cornerLen, boxRect.bottom, bracketPaint)
        c.drawLine(boxRect.left, boxRect.bottom, boxRect.left, boxRect.bottom - cornerLen, bracketPaint)
        // Bottom-Right
        c.drawLine(boxRect.right, boxRect.bottom, boxRect.right - cornerLen, boxRect.bottom, bracketPaint)
        c.drawLine(boxRect.right, boxRect.bottom, boxRect.right, boxRect.bottom - cornerLen, bracketPaint)

        // 4. Precision Center Crosshair
        val ch = 9f * d
        outerRingPaint.strokeWidth = 1.8f * d
        c.drawLine(cx - ch, cy, cx + ch, cy, outerRingPaint)
        c.drawLine(cx, cy - ch, cx, cy + ch, outerRingPaint)
        outerRingPaint.strokeWidth = 2.8f * d

        // 5. Coordinate & Target Label Tag right above the ring
        val tagText = "🎯 LOCKED: ${t.buttonLabel.take(20)} (${t.targetX.toInt()}, ${t.targetY.toInt()})"
        val textW = labelTextPaint.measureText(tagText) + 22f * d
        val badgeTop = (boxRect.top - 28f * d).coerceAtLeast(18f * d)
        badgeRect.set(cx - textW / 2f, badgeTop, cx + textW / 2f, badgeTop + 22f * d)
        c.drawRoundRect(badgeRect, 8f * d, 8f * d, labelBgPaint)
        outerRingPaint.strokeWidth = 1.3f * d
        c.drawRoundRect(badgeRect, 8f * d, 8f * d, outerRingPaint)
        outerRingPaint.strokeWidth = 2.8f * d
        c.drawText(tagText, cx, badgeRect.centerY() + 4f * d, labelTextPaint)

        postInvalidateOnAnimation()
    }
}

/**
 * Interactive Human-in-the-Loop Confirmation Bar displayed alongside the Neon Target Ring.
 * Lets the user tap "✅ হ্যাঁ, ক্লিক করুন" (Approve) or "✕ বাতিল" (Cancel), or confirm via voice ("হ্যাঁ" / "Yes").
 */
private class SciFiConfirmHudBarView(
    ctx: Context,
    private val target: LockedTargetAction,
    private val onApprove: () -> Unit,
    private val onCancel: () -> Unit
) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F004141C")
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * d
        color = Color.parseColor("#00F0FF")
    }
    private val approveBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#00E676")
    }
    private val cancelBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF1E6E")
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11.2f * d
        isFakeBoldText = true
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90F4FF")
        textSize = 9.6f * d
    }
    private val btnTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#02160C")
        textSize = 10.5f * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val cancelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10.2f * d
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }

    private val cardRect = RectF()
    private val approveRect = RectF()
    private val cancelRect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        cardRect.set(2f * d, 2f * d, w - 2f * d, h - 2f * d)
        c.drawRoundRect(cardRect, 14f * d, 14f * d, bgPaint)
        c.drawRoundRect(cardRect, 14f * d, 14f * d, borderPaint)

        val promptTitle = if (target.isOtpConfirmation) {
            "🔐 OTP: ${target.otpCode} — পেস্ট করব?"
        } else {
            "বস, সব রেডি—এখন কি ক্লিক করব?"
        }
        val subTitle = "🎯 ${target.buttonLabel.take(18)} • বলুন 'হ্যাঁ/Yes' বা ট্যাপ করুন"
        c.drawText(promptTitle, 12f * d, 24f * d, titlePaint)
        c.drawText(subTitle, 12f * d, 41f * d, subPaint)

        val btnW = 74f * d
        val cancelW = 46f * d
        val btnTop = 13f * d
        val btnBottom = h - 13f * d

        cancelRect.set(w - cancelW - 10f * d, btnTop, w - 10f * d, btnBottom)
        approveRect.set(cancelRect.left - btnW - 6f * d, btnTop, cancelRect.left - 6f * d, btnBottom)

        c.drawRoundRect(approveRect, 10f * d, 10f * d, approveBtnPaint)
        c.drawText("✅ হ্যাঁ", approveRect.centerX(), approveRect.centerY() + 4f * d, btnTextPaint)

        c.drawRoundRect(cancelRect, 10f * d, 10f * d, cancelBtnPaint)
        c.drawText("✕ না", cancelRect.centerX(), cancelRect.centerY() + 4f * d, cancelTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val x = event.x
            val y = event.y
            if (approveRect.contains(x, y)) {
                onApprove()
                return true
            }
            if (cancelRect.contains(x, y)) {
                onCancel()
                return true
            }
        }
        return true
    }
}

/**
 * ON/OFF switch for the on-screen touch effect ("Show tap indicator").
 * Synced with `AgentAbilitySwitchesBus` and controllable by voice via `app_control` or `set_ability_switch`.
 */
object TouchEffectBus {
    private const val PREFS = "omni_touch_fx"
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun load(ctx: Context) {
        val saved = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("on", false)
        _enabled.value = saved
        AgentAbilitySwitchesBus.syncTapIndicatorFromTouchBus(saved)
    }

    fun set(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("on", on).apply()
        setEnabled(on)
        AgentAbilitySwitchesBus.syncTapIndicatorFromTouchBus(on)
    }

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (!on) TouchEffectOverlay.hide()
    }
}

/**
 * Feature #3: FLOATING AI ORB & REAL-TIME AUDIO WAVEFORM.
 * Draggable Floating 3D Holographic AI Orb on the screen edge that animates with a real-time
 * multi-bar audio equalizer + 360° sonic waveform whenever the user or the AI speaks.
 */
object FloatingAvatarOverlayBus {
    private val main = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private val _character = MutableStateFlow(FloatingAvatarCharacter.ARCHER)
    val character: StateFlow<FloatingAvatarCharacter> = _character.asStateFlow()

    private val _isAppInForeground = MutableStateFlow(true)
    val isAppInForeground: StateFlow<Boolean> = _isAppInForeground.asStateFlow()

    private val _isSpeakingPulse = MutableStateFlow(false)
    val isSpeakingPulse: StateFlow<Boolean> = _isSpeakingPulse.asStateFlow()

    private val _isListeningPulse = MutableStateFlow(false)
    val isListeningPulse: StateFlow<Boolean> = _isListeningPulse.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel: StateFlow<Float> = _rmsLevel.asStateFlow()

    @Volatile
    var isAppSessionAlive: Boolean = true
        private set

    private var avatarView: FloatingAvatarBubbleView? = null
    private var wm: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    var onAvatarTappedCallback: (() -> Unit)? = null

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        ConfirmationGateBus.init(ctx)
    }

    /**
     * Marks whether the main application session is alive (open or minimized) vs. completely swiped away
     * from Recent Apps. When `alive == false`, all floating overlays are immediately destroyed and prevented
     * from re-opening until the user launches the app again.
     */
    fun setAppSessionAlive(alive: Boolean) {
        isAppSessionAlive = alive
        if (!alive) {
            _isAppInForeground.value = false
            _isSpeakingPulse.value = false
            _isListeningPulse.value = false
            _rmsLevel.value = 0f
            hideOverlayWindow()
            ConfirmationGateBus.clearTargetLock()
            TouchEffectOverlay.hide()
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        if (!isAppSessionAlive) {
            hideOverlayWindow()
            return
        }
        _isAppInForeground.value = inForeground
        main.post {
            if (!isAppSessionAlive || inForeground || _character.value == FloatingAvatarCharacter.OFF) {
                hideOverlayWindow()
            } else {
                ensureOrUpdateOverlayWindow(_character.value)
            }
        }
    }

    fun setCharacter(char: FloatingAvatarCharacter) {
        _character.value = char
        main.post {
            if (!isAppSessionAlive || char == FloatingAvatarCharacter.OFF || _isAppInForeground.value) {
                hideOverlayWindow()
            } else {
                ensureOrUpdateOverlayWindow(char)
            }
        }
    }

    fun updateSpeakingPulse(speaking: Boolean) {
        if (!isAppSessionAlive) return
        if (_isSpeakingPulse.value != speaking) {
            _isSpeakingPulse.value = speaking
            main.post {
                avatarView?.setVoiceState(
                    isSpeaking = speaking,
                    isListening = _isListeningPulse.value,
                    rms = _rmsLevel.value
                )
            }
        }
    }

    fun updateVoiceWaveState(speaking: Boolean, listening: Boolean, rms: Float) {
        if (!isAppSessionAlive) return
        _isSpeakingPulse.value = speaking
        _isListeningPulse.value = listening
        _rmsLevel.value = rms.coerceIn(0f, 1f)
        main.post {
            avatarView?.setVoiceState(
                isSpeaking = speaking,
                isListening = listening,
                rms = rms.coerceIn(0f, 1f)
            )
        }
    }

    fun refreshIfAccessibilityConnected() {
        if (!isAppSessionAlive) {
            hideOverlayWindow()
            return
        }
        val current = _character.value
        if (current != FloatingAvatarCharacter.OFF && !_isAppInForeground.value) {
            main.post { ensureOrUpdateOverlayWindow(current) }
        }
    }

    private fun ensureOrUpdateOverlayWindow(char: FloatingAvatarCharacter) {
        if (!isAppSessionAlive || _isAppInForeground.value || char == FloatingAvatarCharacter.OFF) {
            hideOverlayWindow()
            return
        }
        val svc = AccessibilityAutomationBus.serviceInstance
        val fallbackCtx = appCtx
        val hostCtx: Context = svc ?: if (fallbackCtx != null && Settings.canDrawOverlays(fallbackCtx)) {
            fallbackCtx
        } else {
            return
        }
        val windowType = if (svc != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        try {
            val manager = hostCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val existing = avatarView
            if (existing != null) {
                existing.setCharacter(char)
                return
            }
            val density = hostCtx.resources.displayMetrics.density
            val sizePx = (84 * density).toInt()
            val lp = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = (10 * density).toInt()
                y = (155 * density).toInt()
            }
            val view = FloatingAvatarBubbleView(
                ctx = hostCtx,
                initialChar = char,
                onDragBy = { dx, dy ->
                    try {
                        lp.x = (lp.x - dx).coerceAtLeast(0)
                        lp.y = (lp.y + dy).coerceAtLeast(40)
                        manager.updateViewLayout(avatarView, lp)
                    } catch (_: Exception) {}
                },
                onTapped = {
                    val locked = ConfirmationGateBus.lockedTarget.value
                    if (locked != null) {
                        val approved = ConfirmationGateBus.consumeLockedTarget() ?: locked
                        ConfirmationGateBus.onUserApprovedFromOverlay?.invoke(approved)
                    } else {
                        onAvatarTappedCallback?.invoke()
                    }
                }
            )
            manager.addView(view, lp)
            wm = manager
            layoutParams = lp
            avatarView = view
        } catch (_: Exception) {
        }
    }

    fun hideOverlayWindow() {
        val currentView = avatarView
        val currentWm = wm
        avatarView = null
        layoutParams = null
        main.post {
            if (currentView != null) {
                try {
                    currentWm?.removeViewImmediate(currentView)
                } catch (_: Exception) {
                    try {
                        currentWm?.removeView(currentView)
                    } catch (_: Exception) {}
                }
                try {
                    val accWm = AccessibilityAutomationBus.serviceInstance
                        ?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    accWm?.removeViewImmediate(currentView)
                } catch (_: Exception) {}
                try {
                    val appWm = appCtx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    appWm?.removeViewImmediate(currentView)
                } catch (_: Exception) {}
            }
        }
    }
}

private data class MiniGlobeNode(
    val baseX: Float,
    val baseY: Float,
    val baseZ: Float,
    val theta: Float,
    val phiRatio: Float
)

private class FloatingAvatarBubbleView(
    ctx: Context,
    initialChar: FloatingAvatarCharacter,
    private val onDragBy: (Int, Int) -> Unit,
    private val onTapped: () -> Unit
) : View(ctx) {
    private var currentChar: FloatingAvatarCharacter = initialChar
    private var speaking: Boolean = false
    private var listening: Boolean = true
    private var rmsLevel: Float = 0f
    private val d = ctx.resources.displayMetrics.density
    private val startUptime = SystemClock.uptimeMillis()

    private val colorMatrixGreen = Color.parseColor("#00FF66")
    private val colorCyberCyan = Color.parseColor("#00F0FF")
    private val colorParticleGold = Color.parseColor("#FFEA00")

    private val spherePoints: List<MiniGlobeNode> = run {
        val count = 96
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        List(count) { i ->
            val y = 1f - (i.toFloat() / (count - 1).toFloat()) * 2f
            val rAtY = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            MiniGlobeNode(
                baseX = cos(theta) * rAtY,
                baseY = y,
                baseZ = sin(theta) * rAtY,
                theta = theta,
                phiRatio = i.toFloat() / count.toFloat()
            )
        }
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val eqBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.0f * d
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0FFF4")
        textSize = 7.2f * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val pillBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#D9031510")
    }
    private val pillBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f * d
        color = colorMatrixGreen
    }
    private val arcRect = RectF()
    private val pillRect = RectF()
    private val wavePath = Path()

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    fun setCharacter(c: FloatingAvatarCharacter) {
        currentChar = c
        invalidate()
    }

    fun setVoiceState(isSpeaking: Boolean, isListening: Boolean, rms: Float) {
        speaking = isSpeaking
        listening = isListening
        rmsLevel = rms
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        val nowMs = SystemClock.uptimeMillis() - startUptime
        val isActiveVoice = speaking || listening || rmsLevel > 0.06f

        val cx = width / 2f
        val cy = height / 2f - 4f * d
        val baseRadius = minOf(width, height) * 0.28f

        val rotAngle = (nowMs % 6400L).toFloat() / 6400f * (2f * PI.toFloat())
        val wavePhase = (nowMs % (if (isActiveVoice) 950L else 2200L)).toFloat() /
            (if (isActiveVoice) 950f else 2200f) * (2f * PI.toFloat())
        val sonicProgress = (nowMs % 1050L).toFloat() / 1050f

        val pulseFactor = if (isActiveVoice) {
            1.0f + 0.14f * sin(wavePhase) + rmsLevel * 0.22f
        } else {
            1.0f + 0.03f * sin(wavePhase)
        }
        val sphereRadius = baseRadius * pulseFactor.coerceIn(0.90f, 1.24f)

        // 1. Deep Holographic Emerald-Cyan Plasma Aura & Core
        corePaint.shader = RadialGradient(
            cx,
            cy,
            baseRadius * 1.48f,
            intArrayOf(
                Color.argb(235, 224, 255, 255),
                Color.argb(195, 0, 240, 255),
                Color.argb(145, 0, 255, 102),
                Color.argb(190, 4, 28, 18),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.30f, 0.58f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, baseRadius * 1.45f, corePaint)
        corePaint.shader = null

        // 2. Expanding Sonic Energy Wave Rings & 360° Sinusoidal Voice Waveform
        if (isActiveVoice) {
            val waveColors = intArrayOf(colorMatrixGreen, colorCyberCyan, colorParticleGold)
            for (wIdx in 0..2) {
                val p = (sonicProgress + wIdx * 0.33f) % 1f
                val fade = (1f - p).coerceIn(0f, 1f)
                val ringR = baseRadius * (0.98f + p * (0.50f + rmsLevel * 0.18f))
                val col = waveColors[wIdx]
                strokePaint.color = Color.argb(
                    (190f * fade).toInt().coerceIn(0, 255),
                    Color.red(col),
                    Color.green(col),
                    Color.blue(col)
                )
                strokePaint.strokeWidth = (1.1f + fade * 1.6f) * d
                c.drawCircle(cx, cy, ringR, strokePaint)
            }

            wavePath.reset()
            val steps = 48
            val amp = (3.2f + rmsLevel * 8.5f + (if (speaking) 2.6f else 1.2f)) * d
            for (s in 0..steps) {
                val ang = (2.0 * PI * s / steps).toFloat()
                val mod1 = sin(ang * 8f + wavePhase * 2.2f)
                val mod2 = cos(ang * 5f - wavePhase * 1.5f)
                val rWave = sphereRadius * 1.06f + (mod1 * 0.65f + mod2 * 0.35f) * amp
                val wx = cx + cos(ang) * rWave
                val wy = cy + sin(ang) * rWave
                if (s == 0) wavePath.moveTo(wx, wy) else wavePath.lineTo(wx, wy)
            }
            wavePath.close()
            strokePaint.color = if (speaking) colorMatrixGreen else colorCyberCyan
            strokePaint.strokeWidth = 1.6f * d
            c.drawPath(wavePath, strokePaint)
        }

        // 3. Counter-Rotating Tactical HUD Radar Arcs
        val arcR = baseRadius * 1.15f
        arcRect.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR)
        val hudDeg = (nowMs % 9000L).toFloat() / 9000f * 360f
        strokePaint.strokeWidth = 1.5f * d
        for (a in 0..3) {
            strokePaint.color = when (a) {
                0 -> colorMatrixGreen
                1 -> colorCyberCyan
                2 -> colorParticleGold
                else -> colorCyberCyan
            }
            c.drawArc(arcRect, hudDeg + a * 90f + 10f, 52f, false, strokePaint)
        }

        // 4. 3D Fibonacci Particle Sphere with Spherical Harmonic Wave Displacement
        val cosRotY = cos(rotAngle)
        val sinRotY = sin(rotAngle)
        val cosTiltX = cos(0.24f)
        val sinTiltX = sin(0.24f)
        val waveAmp = if (isActiveVoice) 0.175f + rmsLevel * 0.20f else 0.025f

        for (i in spherePoints.indices) {
            val pt = spherePoints[i]
            val xRotY = pt.baseX * cosRotY + pt.baseZ * sinRotY
            val zRotY = -pt.baseX * sinRotY + pt.baseZ * cosRotY
            val y3d = pt.baseY * cosTiltX - zRotY * sinTiltX
            val z3d = pt.baseY * sinTiltX + zRotY * cosTiltX

            val primaryH = sin(pt.theta * 5f + wavePhase * 1.4f) * cos(pt.phiRatio * 14f - wavePhase * 1.2f)
            val secondaryH = sin(pt.theta * 9f - wavePhase * 2.1f) * 0.42f
            val harmonicWave = 1f + waveAmp * (primaryH + secondaryH)

            val perspective = 2.6f / (2.6f - z3d * 0.55f)
            val effR = sphereRadius * harmonicWave * perspective * 0.92f
            val px = cx + xRotY * effR
            val py = cy + y3d * effR

            val depth = ((z3d + 1f) * 0.5f).coerceIn(0.18f, 1f)
            val dotR = (0.75f + depth * 1.25f) * d
            val baseCol = when {
                xRotY < -0.18f && y3d > -0.65f && y3d < 0.68f -> colorParticleGold
                xRotY < -0.02f && i % 2 == 0 -> colorMatrixGreen
                else -> colorCyberCyan
            }
            dotPaint.color = Color.argb(
                (65 + (depth * 190).toInt()).coerceIn(60, 255),
                Color.red(baseCol),
                Color.green(baseCol),
                Color.blue(baseCol)
            )
            c.drawCircle(px, py, dotR, dotPaint)
        }

        // 5. Real-Time Multi-Bar Audio Equalizer Waveform Pill at the bottom of the Floating AI Orb
        pillRect.set(cx - 24f * d, height - 15.5f * d, cx + 24f * d, height - 1.5f * d)
        c.drawRoundRect(pillRect, 7f * d, 7f * d, pillBgPaint)
        c.drawRoundRect(pillRect, 7f * d, 7f * d, pillBorderPaint)

        if (isActiveVoice) {
            val barCount = 9
            val totalBarsWidth = 34f * d
            val startX = cx - totalBarsWidth / 2f
            val stepX = totalBarsWidth / (barCount - 1)
            val centerY = pillRect.centerY()
            for (b in 0 until barCount) {
                val barPhase = wavePhase * 2.4f + b * 0.75f
                val env = 1f - abs(b - (barCount - 1) / 2f) / ((barCount - 1) / 2f) * 0.45f
                val halfH = ((1.8f + (rmsLevel * 5.2f + (if (speaking) 2.2f else 0.9f)) * abs(sin(barPhase))) * env * d)
                    .coerceIn(1.5f * d, 5.5f * d)
                eqBarPaint.color = if (b % 2 == 0) colorMatrixGreen else colorCyberCyan
                val bx = startX + b * stepX
                c.drawLine(bx, centerY - halfH, bx, centerY + halfH, eqBarPaint)
            }
        } else {
            c.drawText("AI ORB", cx, pillRect.centerY() + 2.6f * d, labelPaint)
        }

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - lastRawX).toInt()
                val dy = (event.rawY - lastRawY).toInt()
                if (abs(dx) > 1 || abs(dy) > 1) {
                    onDragBy(dx, dy)
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val totalMove = abs(event.rawX - downRawX) + abs(event.rawY - downRawY)
                if (totalMove < 12f * d) {
                    onTapped()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * Draws a glowing ripple at every tap and a trail for every swipe the agent performs, on top of ANY app.
 * Uses an accessibility overlay window (no extra permission) and never intercepts touches.
 */
object TouchEffectOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: RippleView? = null
    private var wm: WindowManager? = null

    fun tap(x: Float, y: Float) = post { ensure()?.add(Fx(x, y, x, y, false)) }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = post { ensure()?.add(Fx(x1, y1, x2, y2, true)) }

    private fun post(r: () -> Unit) {
        if (!TouchEffectBus.enabled.value) return
        main.post { try { r() } catch (_: Exception) {} }
    }

    private fun ensure(): RippleView? {
        view?.let { return it }
        val svc = AccessibilityAutomationBus.serviceInstance ?: return null
        val manager = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = RippleView(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        manager.addView(v, lp)
        wm = manager
        view = v
        return v
    }

    fun hide() {
        main.post {
            try { view?.let { wm?.removeView(it) } } catch (_: Exception) {}
            view = null
        }
    }
}

private class Fx(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val swipe: Boolean) {
    val start: Long = SystemClock.uptimeMillis()
    val dur: Long = if (swipe) 750L else 650L
}

private class RippleView(ctx: Context) : View(ctx) {
    private val fxs = CopyOnWriteArrayList<Fx>()
    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * d; color = Color.parseColor("#00FF9C")
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#00E5FF") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6f * d; strokeCap = Paint.Cap.ROUND; color = Color.parseColor("#00E5FF")
    }

    fun add(fx: Fx) { fxs.add(fx); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        for (fx in fxs) {
            val t = (now - fx.start).toFloat() / fx.dur
            if (t >= 1f) { fxs.remove(fx); continue }
            val fade = 1f - t
            if (!fx.swipe) {
                ring.alpha = (230 * fade).toInt()
                c.drawCircle(fx.x1, fx.y1, (14f + 46f * t) * d, ring)
                fill.alpha = (150 * fade).toInt()
                c.drawCircle(fx.x1, fx.y1, 11f * d * (1f - 0.4f * t), fill)
            } else {
                val p = (1f - (1f - t.coerceAtMost(0.6f) / 0.6f) * (1f - t.coerceAtMost(0.6f) / 0.6f))
                val cx = fx.x1 + (fx.x2 - fx.x1) * p
                val cy = fx.y1 + (fx.y2 - fx.y1) * p
                line.alpha = (200 * fade).toInt()
                c.drawLine(fx.x1, fx.y1, cx, cy, line)
                fill.alpha = (230 * fade).toInt()
                c.drawCircle(cx, cy, 13f * d, fill)
                ring.alpha = (200 * fade).toInt()
                c.drawCircle(cx, cy, 24f * d, ring)
            }
        }
        if (fxs.isNotEmpty()) postInvalidateOnAnimation()
    }
}
