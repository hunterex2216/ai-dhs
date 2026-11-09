package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingUiMacro(
    val id: String = "macro_${System.currentTimeMillis()}",
    val targetPackageHint: String = "",
    val textToType: String = "",
    val contactRecipientToOpen: String = "",
    val isDirectChatMessage: Boolean = false,
    val contactSearchTyped: Boolean = false,
    val isPlayStoreInstallLoop: Boolean = false,
    val targetAppName: String = "",
    val knownPackage: String = "",
    val isUpdateInstall: Boolean = false,
    val lastInstallClickAtMs: Long = 0L,
    val installRetryCount: Int = 0,
    val openedDetailsCard: Boolean = false,
    val verifiedDownloadStarted: Boolean = false,
    val clickTargets: List<String> = emptyList(),
    val secondaryClickTargets: List<String> = emptyList(),
    val targetViewIds: List<String> = emptyList(),
    val scrollDirection: String = "", // DOWN, UP, LEFT, RIGHT
    val globalAction: String = "", // BACK, HOME, NOTIFICATIONS, QUICK_SETTINGS, RECENTS
    val quickSettingTileTargets: List<String> = emptyList(),
    val closeShadeAfterClick: Boolean = true,
    val gestureType: String = "", // TAP_XY, TAP_CENTER, DOUBLE_TAP, LONG_PRESS, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT
    val gestureX: Float = 540f,
    val gestureY: Float = 1100f,
    val maxAutoScrollRetries: Int = 0,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long = 0L,
    val searchBarOpenedOnce: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class AccessibilityEngineState(
    val isServiceEnabled: Boolean = false,
    val currentForegroundPackage: String = "",
    val currentActivityOrWindowTitle: String = "",
    val visibleScreenText: String = "স্ক্রিন রিডার প্রস্তুত — যেকোনো অ্যাপ বা ওয়েবসাইট বিশ্লেষণ করা যাবে",
    val visibleButtonsAndNodes: List<String> = emptyList(),
    val lastTouchPoint: String = "টাচ রেডি (X: 540, Y: 1100)",
    val lastAutomationResult: String = "কুইক সেটিংস টগল, অটো-ক্লিক, স্ক্রিন টাচ ও কন্টাক্ট স্ক্যানার প্রস্তুত",
    val liveTaskProgressStage: String = "",
    val pendingMacro: PendingUiMacro? = null
) {
    val isExecutingMacro: Boolean
        get() = pendingMacro != null
}

object AccessibilityAutomationBus {
    private val _state = MutableStateFlow(AccessibilityEngineState())
    val state: StateFlow<AccessibilityEngineState> = _state.asStateFlow()

    var serviceInstance: CubeAccessibilityService? = null

    fun setServiceConnected(connected: Boolean, instance: CubeAccessibilityService?) {
        serviceInstance = instance
        _state.value = _state.value.copy(isServiceEnabled = connected)
        if (connected) {
            FloatingAvatarOverlayBus.refreshIfAccessibilityConnected()
        } else {
            FloatingAvatarOverlayBus.hideOverlayWindow()
        }
    }

    fun cancelAllPendingMacros() {
        _state.value = _state.value.copy(
            pendingMacro = null,
            liveTaskProgressStage = "",
            lastAutomationResult = "অটোমেশন থামানো হয়েছে"
        )
    }

    fun requestLiveScreenFrame(onBitmapReady: (Bitmap) -> Unit) {
        val projectedBmp = ScreenCaptureSessionBus.latestCapturedBitmap
        if (ScreenCaptureSessionBus.state.value.hasMediaProjectionGrant &&
            ScreenCaptureSessionBus.state.value.isCapturingFrames &&
            projectedBmp != null &&
            !projectedBmp.isRecycled
        ) {
            onBitmapReady(projectedBmp)
            return
        }
        val svc = serviceInstance
        if (svc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            svc.captureHardwareScreenshotOrFallback(onBitmapReady)
        } else if (projectedBmp != null && !projectedBmp.isRecycled) {
            onBitmapReady(projectedBmp)
        } else {
            onBitmapReady(renderLiveScreenMatrixBitmap())
        }
    }

    fun renderLiveScreenMatrixBitmap(): Bitmap {
        val snap = _state.value
        val bmp = Bitmap.createBitmap(384, 384, Bitmap.Config.RGB_565)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(4, 18, 14))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 136)
            textSize = 16f
        }
        canvas.drawText("ARCHER LIVE SCREEN STREAM", 16f, 32f, paint)
        paint.color = Color.rgb(0, 229, 255)
        paint.textSize = 14f
        canvas.drawText("PKG: ${snap.currentForegroundPackage.ifBlank { "com.aistudio.archerai" }}", 16f, 58f, paint)
        canvas.drawText("TOUCH: ${snap.lastTouchPoint}", 16f, 82f, paint)
        paint.color = Color.WHITE
        paint.textSize = 13f
        val lines = snap.visibleScreenText.chunked(34).take(9)
        var y = 114f
        for (line in lines) {
            canvas.drawText(line, 16f, y, paint)
            y += 22f
        }
        paint.color = Color.rgb(0, 255, 136)
        val btns = "NODES: " + snap.visibleButtonsAndNodes.take(5).joinToString(", ")
        canvas.drawText(btns.take(38), 16f, 340f, paint)
        return bmp
    }

    fun syncPermissionState(context: Context): Boolean {
        val enabledServices = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val isEnabled = serviceInstance != null ||
            enabledServices.contains(context.packageName, ignoreCase = true)
        if (_state.value.isServiceEnabled != isEnabled) {
            _state.value.let {
                _state.value = it.copy(isServiceEnabled = isEnabled)
            }
        }
        return isEnabled
    }

    fun updateScreenSnapshot(
        pkg: String,
        screenText: String,
        buttons: List<String> = emptyList(),
        activityOrTitle: String = "",
        progressStage: String = ""
    ) {
        _state.value = _state.value.copy(
            currentForegroundPackage = pkg,
            currentActivityOrWindowTitle = if (activityOrTitle.isNotBlank()) activityOrTitle else _state.value.currentActivityOrWindowTitle,
            visibleScreenText = screenText.ifBlank { _state.value.visibleScreenText },
            visibleButtonsAndNodes = if (buttons.isNotEmpty()) buttons else _state.value.visibleButtonsAndNodes,
            liveTaskProgressStage = if (progressStage.isNotBlank()) progressStage else _state.value.liveTaskProgressStage
        )
    }

    fun updateTouchTelemetry(touchSummary: String) {
        _state.value = _state.value.copy(
            lastTouchPoint = touchSummary,
            lastAutomationResult = touchSummary
        )
    }

    fun queueMacro(macro: PendingUiMacro) {
        val label = when {
            macro.isPlayStoreInstallLoop -> "Play Store Auto-Install & Verify: ${macro.targetAppName}"
            macro.quickSettingTileTargets.isNotEmpty() -> "Quick Settings: ${macro.quickSettingTileTargets.first()}"
            macro.gestureType.isNotBlank() -> "Touch: ${macro.gestureType}"
            macro.isDirectChatMessage -> "Chat Message -> ${macro.contactRecipientToOpen.ifBlank { "Open Chat" }}"
            else -> (macro.clickTargets + macro.secondaryClickTargets).joinToString("/")
                .ifBlank { macro.textToType.ifBlank { macro.globalAction } }
        }
        _state.value = _state.value.copy(
            pendingMacro = macro,
            lastAutomationResult = "অ্যাক্সেসিবিলিটি ম্যাক্রো সক্রিয়: $label"
        )
        serviceInstance?.tryExecutePendingMacro()
    }

    fun updatePendingMacro(macro: PendingUiMacro?, statusMessage: String) {
        _state.value = _state.value.copy(
            pendingMacro = macro,
            lastAutomationResult = statusMessage
        )
    }

    fun recordMacroSuccess(summary: String) {
        _state.value = _state.value.copy(
            pendingMacro = null,
            lastAutomationResult = summary
        )
    }
}

class CubeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var quickSettingsShadeOpenedForMacroId: String = ""

    private val subSecondScreenWatcher = object : Runnable {
        override fun run() {
            var hasPending = false
            try {
                hasPending = AccessibilityAutomationBus.state.value.pendingMacro != null
                if (hasPending) {
                    refreshSnapshotNow(force = true)
                    tryExecutePendingMacro()
                }
            } catch (_: Exception) {
            } finally {
                val nextDelay = if (hasPending) 240L else 1200L
                handler.postDelayed(this, nextDelay)
            }
        }
    }

    override fun onServiceConnected() {
        TouchEffectBus.load(applicationContext)
        super.onServiceConnected()
        AccessibilityAutomationBus.setServiceConnected(true, this)
        handler.removeCallbacks(subSecondScreenWatcher)
        handler.postDelayed(subSecondScreenWatcher, 240L)
    }

    @Volatile private var lastRealShot: Bitmap? = null
    @Volatile private var lastShotAt = 0L
    @Volatile private var lastRefreshAt = 0L
    @Volatile private var lastSnapshotElems: List<UiElem> = emptyList()
    @Volatile private var isMacroStepInFlight = false
    @Volatile private var lastClickSignature: String = ""
    @Volatile private var lastClickAtUptimeMs: Long = 0L

    /**
     * Step 1 of the Strict Confirmation Gate (`prepare_and_highlight`):
     * Types `textToTypeFirst` into the active input field (if provided), locates the target button's exact
     * `(X, Y)` bounds via `AccessibilityNodeInfo` + ScreenMemory, draws the glowing Sci-Fi HUD Neon Target Ring
     * around `(X, Y)`, and pauses WITHOUT clicking.
     */
    fun prepareAndHighlightTargetOnScreen(
        buttonLabelOrCoord: String,
        textToTypeFirst: String = "",
        appPackageHint: String = ""
    ): LockedTargetAction {
        refreshSnapshotNow(force = true)
        if (textToTypeFirst.isNotBlank()) {
            typeTextWithoutSubmitting(textToTypeFirst.trim())
            refreshSnapshotNow(force = true)
        }

        val cleanTarget = buttonLabelOrCoord.trim()
        val coordParts = cleanTarget.split(',', ' ').mapNotNull { it.trim().toFloatOrNull() }
        if (coordParts.size >= 2) {
            val tx = coordParts[0]
            val ty = coordParts[1]
            return ConfirmationGateBus.lockTargetAndHighlight(
                ctx = applicationContext,
                buttonLabel = "Target (${tx.toInt()}, ${ty.toInt()})",
                x = tx,
                y = ty,
                bounds = Rect((tx - 88f).toInt(), (ty - 42f).toInt(), (tx + 88f).toInt(), (ty + 42f).toInt()),
                appPackageOrName = appPackageHint.ifBlank { ScreenMemory.currentPkg },
                typedTextPreview = textToTypeFirst
            )
        }

        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root != null && cleanTarget.isNotBlank()) {
            val matched = findBestScoringCandidateWithoutClicking(root, cleanTarget)
            if (matched != null) {
                val r = Rect()
                (matched.clickableAncestor ?: matched.node).getBoundsInScreen(r)
                return ConfirmationGateBus.lockTargetAndHighlight(
                    ctx = applicationContext,
                    buttonLabel = matched.matchedLabel,
                    x = matched.tapX.toFloat(),
                    y = matched.tapY.toFloat(),
                    bounds = r,
                    appPackageOrName = appPackageHint.ifBlank { ScreenMemory.currentPkg },
                    typedTextPreview = textToTypeFirst
                )
            }
        }

        // Fallback to ScreenMemory remembered coordinates or sensible bottom-right action button position
        val remembered = ScreenMemory.lookup(applicationContext, cleanTarget, appPackageHint).firstOrNull()
        val metrics = resources.displayMetrics
        val fallbackX = remembered?.third?.cx?.toFloat() ?: (metrics.widthPixels * 0.86f)
        val fallbackY = remembered?.third?.cy?.toFloat() ?: (metrics.heightPixels * 0.88f)
        return ConfirmationGateBus.lockTargetAndHighlight(
            ctx = applicationContext,
            buttonLabel = remembered?.second ?: cleanTarget.ifBlank { "Action Button" },
            x = fallbackX,
            y = fallbackY,
            bounds = Rect((fallbackX - 90f).toInt(), (fallbackY - 42f).toInt(), (fallbackX + 90f).toInt(), (fallbackY + 42f).toInt()),
            appPackageOrName = appPackageHint.ifBlank { ScreenMemory.currentPkg },
            typedTextPreview = textToTypeFirst
        )
    }

    /**
     * Step 2 of the Strict Confirmation Gate (`execute_confirmed_click`):
     * Executes the final tap at the locked `(X, Y)` coordinates (or pastes a confirmed OTP) ONLY after
     * the user explicitly confirms via voice ("হ্যাঁ" / "Yes") or taps Approve on the HUD overlay.
     */
    fun executeConfirmedTargetAction(locked: LockedTargetAction): String {
        ConfirmationGateBus.clearTargetLock()
        if (locked.isOtpConfirmation && locked.otpCode.isNotBlank()) {
            val pasted = typeTextWithoutSubmitting(locked.otpCode)
            return if (pasted) {
                "OTP_PASTED_SUCCESS: বসের অনুমতির পর OTP কোড '${locked.otpCode}' ইনপুট বক্সে পেস্ট করা হয়েছে।"
            } else {
                "OTP_CLIPBOARD_READY: OTP কোড '${locked.otpCode}' প্রস্তুত।"
            }
        }

        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root != null && locked.buttonLabel.isNotBlank() && !locked.buttonLabel.startsWith("Target (")) {
            val matched = findBestScoringCandidateWithoutClicking(root, locked.buttonLabel)
            if (matched != null && executeVerifiedSingleClick(matched)) {
                return "CONFIRMED_CLICK_EXECUTED: '${matched.matchedLabel}' at (${matched.tapX}, ${matched.tapY})"
            }
        }

        TouchEffectOverlay.tap(locked.targetX, locked.targetY)
        val path = Path().apply { moveTo(locked.targetX, locked.targetY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        AccessibilityAutomationBus.updateTouchTelemetry(
            "কনফার্মড ট্যাপ: '${locked.buttonLabel}' (${locked.targetX.toInt()}, ${locked.targetY.toInt()})"
        )
        return "CONFIRMED_CLICK_EXECUTED: '${locked.buttonLabel}' at (${locked.targetX.toInt()}, ${locked.targetY.toInt()}) [dispatched=$dispatched]"
    }

    private fun typeTextWithoutSubmitting(text: String): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                cur.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (cur.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return true
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findBestScoringCandidateWithoutClicking(
        root: AccessibilityNodeInfo,
        targetText: String
    ): ScoredClickCandidate? {
        val lowerTarget = targetText.trim().lowercase()
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(720)
        val screenH = metrics.heightPixels.coerceAtLeast(1280)
        val maxContainerArea = (screenW * screenH * 0.60f).toInt()

        var bestCandidate: ScoredClickCandidate? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 320) {
            val cur = queue.removeFirst()
            visited++
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
            if (!cur.isVisibleToUser || isSearchBoxOrEditableNode(cur)) continue
            val txt = cur.text?.toString()?.trim().orEmpty()
            val desc = cur.contentDescription?.toString()?.trim().orEmpty()
            if (txt.isEmpty() && desc.isEmpty()) continue
            val lowerTxt = txt.lowercase()
            val lowerDesc = desc.lowercase()
            if (isConflictingInstallText(lowerTarget, "$lowerTxt $lowerDesc")) continue

            val isExact = lowerTxt == lowerTarget || lowerDesc == lowerTarget
            val isContains = lowerTxt.contains(lowerTarget) || lowerDesc.contains(lowerTarget)
            if (!isExact && !isContains) continue

            val rect = Rect()
            cur.getBoundsInScreen(rect)
            if (rect.width() < 8 || rect.height() < 8) continue
            if (rect.width() * rect.height() > maxContainerArea) continue

            val clickableTarget = resolveTightClickableAncestor(cur, screenW, screenH)
            var score = if (isExact) 100 else 65
            if (cur.isClickable || clickableTarget != null) score += 25
            if (bestCandidate == null || score > bestCandidate.score) {
                bestCandidate = ScoredClickCandidate(
                    node = cur,
                    clickableAncestor = clickableTarget,
                    score = score,
                    tapX = rect.centerX(),
                    tapY = rect.centerY(),
                    matchedLabel = txt.ifBlank { desc }.take(40)
                )
            }
        }
        return bestCandidate
    }

    /**
     * Screen-First Immediate Single Click:
     * 1. Forces a fresh live screen snapshot (`refreshSnapshotNow(force = true)`) so the agent sees the real screen first.
     * 2. Scores all visible interactive elements matching `targets` or `targetViewIds`.
     * 3. Clicks strictly ONCE on the highest-scoring target and cancels any leftover macro so zero extra clicks occur.
     */
    fun inspectScreenAndClickSingleTargetNow(
        targets: List<String>,
        targetViewIds: List<String> = emptyList(),
        excludeTopSearchHeader: Boolean = false
    ): String? {
        refreshSnapshotNow(force = true)
        for (viewId in targetViewIds) {
            if (viewId.isNotBlank() && findAndClickByViewIdAcrossWindows(viewId)) {
                AccessibilityAutomationBus.cancelAllPendingMacros()
                return viewId.substringAfterLast('/')
            }
        }
        for (target in targets) {
            val clean = target.trim()
            if (clean.isNotBlank() &&
                findAndClickByTextAcrossWindows(
                    targetText = clean,
                    exactOrButtonPreferred = true,
                    excludeTopSearchHeader = excludeTopSearchHeader
                )
            ) {
                AccessibilityAutomationBus.cancelAllPendingMacros()
                return clean
            }
        }
        return null
    }

    /** Fresh UI tree WITH coordinates -> bus + persistent per-app memory. Debounced to keep mobile UI smooth. */
    fun refreshSnapshotNow(force: Boolean = false, activityTitleHint: String = ""): List<UiElem> {
        val nowUptime = android.os.SystemClock.uptimeMillis()
        if (!force && nowUptime - lastRefreshAt < 200L && lastSnapshotElems.isNotEmpty()) {
            return lastSnapshotElems
        }
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return lastSnapshotElems
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isNotBlank() &&
            !pkg.contains("inputmethod", ignoreCase = true) &&
            !pkg.contains("keyboard", ignoreCase = true) &&
            pkg != "com.android.systemui"
        ) {
            FloatingAvatarOverlayBus.setAppInForeground(pkg == packageName)
        }
        val elems = ArrayList<UiElem>()
        walkNodes(root, elems, 0)
        lastRefreshAt = nowUptime
        lastSnapshotElems = elems
        if (pkg.isNotBlank() && pkg != packageName && elems.isNotEmpty()) {
            ScreenMemory.record(applicationContext, pkg, elems)
            val fullText = elems.take(65).joinToString(" | ") { it.label }
            val detectedStage = detectLiveProgressStageFromScreen(pkg, fullText)
            AccessibilityAutomationBus.updateScreenSnapshot(
                pkg = pkg,
                screenText = fullText,
                buttons = elems.filter { it.clickable || it.editable }.take(30).map { "${it.label}@${it.cx},${it.cy}" },
                activityOrTitle = activityTitleHint,
                progressStage = detectedStage
            )
        }
        return elems
    }

    private fun detectLiveProgressStageFromScreen(pkg: String, screenText: String): String {
        val lower = screenText.lowercase()
        val pctMatch = Regex("(\\d{1,3})\\s*%").find(screenText)?.value.orEmpty()
        val isPlayStore = pkg == "com.android.vending" || pkg.contains("packageinstaller")
        val hasCancelBtn = lower.contains("| cancel") || lower.contains("cancel |") || lower.contains("বাতিল")
        val hasOpenOrPlayBtn = lower.contains("| open") || lower.contains("open |") ||
            lower.contains("খুলুন") || lower.contains("ওপেন") ||
            lower.contains("| play") || lower.contains("play |") || lower.contains("চালান")
        val hasUninstallBtn = lower.contains("uninstall") || lower.contains("আনইনস্টল")
        val hasInstallBtn = Regex("(^|\\|\\s*)(install|ইনস্টল করুন|ইনস্টল|update|আপডেট করুন)(\\s*\\||$)").containsMatchIn(lower)
        return when {
            isPlayStore && (lower.contains("installing") || lower.contains("ইনস্টল হচ্ছে")) ->
                "PLAYSTORE_INSTALLING: প্লে স্টোরে অ্যাপটি এখন ইনস্টল হচ্ছে..."
            isPlayStore && pctMatch.isNotBlank() ->
                "PLAYSTORE_DOWNLOADING_$pctMatch: প্লে স্টোরে অ্যাপ ডাউনলোড হচ্ছে ($pctMatch সম্পন্ন)..."
            isPlayStore && (lower.contains("pending") || lower.contains("পেন্ডিং") || lower.contains("waiting for") || hasCancelBtn) ->
                "PLAYSTORE_PENDING: প্লে স্টোরে ডাউনলোড প্রসেস শুরু হয়েছে (Pending/Downloading)..."
            isPlayStore && (hasOpenOrPlayBtn || hasUninstallBtn) && !hasInstallBtn ->
                "PLAYSTORE_INSTALLED_READY: অ্যাপ ইনস্টল সম্পন্ন ও কনফার্ম হয়েছে, এখন ওপেন করার জন্য প্রস্তুত!"
            lower.contains("allow") && (lower.contains("permission") || lower.contains("পারমিশন")) ->
                "SYSTEM_PERMISSION_DIALOG: স্ক্রিনে একটি পারমিশন বক্স এসেছে"
            else -> ""
        }
    }

    private fun walkNodes(node: AccessibilityNodeInfo?, out: MutableList<UiElem>, depth: Int) {
        if (node == null || depth > 26 || out.size >= 220) return
        try {
            if (node.isVisibleToUser) {
                val raw = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
                    .ifBlank { node.viewIdResourceName?.substringAfter('/').orEmpty() }
                val label = raw.replace('\n', ' ').trim().take(60)
                if (label.isNotBlank()) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        val isClickableOrParent = node.isClickable ||
                            node.isLongClickable ||
                            node.parent?.isClickable == true ||
                            node.parent?.parent?.isClickable == true
                        out.add(
                            UiElem(
                                label = label,
                                cx = r.centerX(),
                                cy = r.centerY(),
                                clickable = isClickableOrParent,
                                editable = node.isEditable
                            )
                        )
                    }
                }
            }
            for (i in 0 until node.childCount) walkNodes(node.getChild(i), out, depth + 1)
        } catch (_: Exception) {}
    }

    fun captureHardwareScreenshotOrFallback(onBitmapReady: (Bitmap) -> Unit) {
        val nowMs = android.os.SystemClock.uptimeMillis()
        if (nowMs - lastShotAt < 750L) {
            val cached = lastRealShot
            if (cached != null && !cached.isRecycled) {
                onBitmapReady(cached)
            } else {
                onBitmapReady(AccessibilityAutomationBus.renderLiveScreenMatrixBitmap())
            }
            return
        }
        lastShotAt = nowMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBmp = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                val softBmp = hwBmp?.copy(Bitmap.Config.ARGB_8888, false)
                                screenshot.hardwareBuffer.close()
                                if (softBmp != null) {
                                    lastRealShot = softBmp
                                    onBitmapReady(softBmp)
                                } else {
                                    val fallback = lastRealShot?.takeIf { !it.isRecycled }
                                        ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap()
                                    onBitmapReady(fallback)
                                }
                            } catch (_: Exception) {
                                val fallback = lastRealShot?.takeIf { !it.isRecycled }
                                    ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap()
                                onBitmapReady(fallback)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            val fallback = lastRealShot?.takeIf { !it.isRecycled }
                                ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap()
                            onBitmapReady(fallback)
                        }
                    }
                )
            } catch (_: Exception) {
                val fallback = lastRealShot?.takeIf { !it.isRecycled }
                    ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap()
                onBitmapReady(fallback)
            }
        } else {
            val fallback = lastRealShot?.takeIf { !it.isRecycled }
                ?: AccessibilityAutomationBus.renderLiveScreenMatrixBitmap()
            onBitmapReady(fallback)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        var activityTitle = ""
        if (pkg.isNotBlank() && pkg != packageName && event != null) {
            val evType = event.eventType
            if (evType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val clsName = event.className?.toString().orEmpty()
                val winTitle = event.text?.joinToString(" ").orEmpty().trim()
                activityTitle = winTitle.ifBlank { clsName.substringAfterLast('.') }
            }
            if (evType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                evType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED
            ) {
                val evText = event.text?.joinToString(" ").orEmpty().lowercase()
                val desc = event.contentDescription?.toString().orEmpty().lowercase()
                val viewId = try {
                    event.source?.viewIdResourceName.orEmpty().lowercase()
                } catch (_: Exception) {
                    ""
                }
                val combined = "$evText $desc $viewId"
                val isExternalMicTrigger = combined.contains("voice") ||
                    combined.contains("mic") ||
                    combined.contains("record") ||
                    combined.contains("audio_btn") ||
                    combined.contains("speech") ||
                    combined.contains("dictat") ||
                    combined.contains("ভয়েস") ||
                    combined.contains("মাইক") ||
                    combined.contains("রেকর্ড")
                if (isExternalMicTrigger) {
                    ExternalMicArbiter.notifyProactiveExternalMicYield(
                        durationMs = 3800L,
                        reasonBn = "অন্য অ্যাপে মাইক ব্যবহৃত হচ্ছে — কাজ শেষ হলেই অটো সক্রিয় হবে"
                    )
                }
            }
        }

        if (pkg.isNotBlank() && pkg != packageName) {
            refreshSnapshotNow(
                force = activityTitle.isNotBlank(),
                activityTitleHint = activityTitle
            )
        }

        if (AccessibilityAutomationBus.state.value.pendingMacro != null) {
            tryExecutePendingMacro()
        }
    }

    fun tryExecutePendingMacro() {
        if (isMacroStepInFlight) return
        val macro = AccessibilityAutomationBus.state.value.pendingMacro ?: return
        val now = System.currentTimeMillis()
        val maxDurationMs = if (macro.isPlayStoreInstallLoop) 60_000L else 6_500L
        if (now - macro.createdAt > maxDurationMs) {
            AccessibilityAutomationBus.recordMacroSuccess("অটোমেশন সম্পন্ন হয়েছে")
            return
        }
        // Prevent rapid spam-tapping from concurrent onAccessibilityEvent + subSecondScreenWatcher ticks
        val minCooldownMs = if (macro.isPlayStoreInstallLoop) 1500L else 1100L
        if (now - macro.lastAttemptAtMs < minCooldownMs) {
            return
        }
        if (!macro.isPlayStoreInstallLoop && macro.attemptCount >= 2) {
            AccessibilityAutomationBus.recordMacroSuccess("অটোমেশন ধাপ সম্পন্ন হয়েছে")
            return
        }

        isMacroStepInFlight = true
        try {
            // Always inspect and refresh the live screen first before taking any UI action!
            refreshSnapshotNow(force = true)

            // 0A. Handle Notification Shade / Quick Settings Tile Auto-Toggle
            if (macro.quickSettingTileTargets.isNotEmpty()) {
                executeQuickSettingsTileToggle(macro)
                return
            }

            // 0B. Handle Direct Screen Touch / Gesture (dispatchGesture)
            if (macro.gestureType.isNotBlank()) {
                executeScreenTouchGesture(macro)
                return
            }

            // 0C. Handle Global Actions (Back, Home, Notifications, Quick Settings, Recents, Clear Recent Apps)
            if (macro.globalAction.isNotBlank()) {
                val actionUpper = macro.globalAction.uppercase()
                if (actionUpper == "CLEAR_RECENT_APPS" || actionUpper == "RECENTS_CLEAR") {
                    executeClearRecentAppsMacro(macro)
                    return
                }
                when (actionUpper) {
                    "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
                    "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
                    "NOTIFICATIONS" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
                    "QUICK_SETTINGS" -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
                    "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
                AccessibilityAutomationBus.recordMacroSuccess("সিস্টেম নেভিগেশন সম্পন্ন: ${macro.globalAction}")
                return
            }

            val root = rootInActiveWindow ?: return
            val activePkg = root.packageName?.toString().orEmpty()

            // Never execute external app macros on our own app's UI
            if (activePkg == packageName && macro.targetPackageHint.isNotBlank() && macro.targetPackageHint != packageName) {
                return
            }

            // 0D. Closed-Loop Self-Healing Play Store & Package Installer Supervisor:
            if (macro.isPlayStoreInstallLoop &&
                (activePkg == "com.android.vending" ||
                    activePkg.contains("packageinstaller") ||
                    activePkg.contains("google.android.gms") ||
                    macro.targetPackageHint.isBlank())
            ) {
                executePlayStoreInstallSupervisor(root, macro)
                return
            }

            // 0E. Screen-Guided Chat Messaging (WhatsApp, Telegram, Messenger, SMS)
            if (macro.isDirectChatMessage) {
                executeScreenGuidedChatMessagingMacro(root, macro)
                return
            }

            // 1. Handle 4-Directional Scroll / Swipe Action (UP, DOWN, LEFT, RIGHT)
            if (macro.scrollDirection.isNotBlank()) {
                val dir = macro.scrollDirection.uppercase()
                when {
                    dir.contains("LEFT") || dir.contains("বাম") -> {
                        executeScreenTouchGesture(macro.copy(gestureType = "SWIPE_LEFT"))
                    }
                    dir.contains("RIGHT") || dir.contains("ডান") -> {
                        executeScreenTouchGesture(macro.copy(gestureType = "SWIPE_RIGHT"))
                    }
                    else -> {
                        val forward = dir != "UP" && dir != "SCROLL_UP" && !dir.contains("উপর")
                        val scrolled = performScrollOnNode(root, forward)
                        if (!scrolled) {
                            dispatchSwipeGesture(isScrollDown = forward)
                        }
                        AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন স্ক্রল সম্পন্ন (${macro.scrollDirection})")
                    }
                }
                return
            }

            // Stamp attempt count & timestamp immediately so no concurrent tick can re-run the same click
            val steppedMacro = macro.copy(
                attemptCount = macro.attemptCount + 1,
                lastAttemptAtMs = now
            )
            AccessibilityAutomationBus.updatePendingMacro(
                macro = steppedMacro,
                statusMessage = AccessibilityAutomationBus.state.value.lastAutomationResult
            )

            // 2. Handle Auto-Typing into editable text field
            var typedOk = false
            if (steppedMacro.textToType.isNotBlank()) {
                typedOk = findAndTypeText(root, steppedMacro.textToType, steppedMacro.targetViewIds)
                if (!typedOk && !steppedMacro.searchBarOpenedOnce) {
                    val openedSearchBar = findAndClickByTextAcrossWindows("Search YouTube", exactOrButtonPreferred = true, exactOnly = true) ||
                        findAndClickByTextAcrossWindows("Search apps & games", exactOrButtonPreferred = true, exactOnly = true) ||
                        findAndClickByTextAcrossWindows("Search", exactOrButtonPreferred = true, exactOnly = true) ||
                        findAndClickByTextAcrossWindows("সার্চ", exactOrButtonPreferred = true, exactOnly = true)
                    if (openedSearchBar) {
                        AccessibilityAutomationBus.updatePendingMacro(
                            macro = steppedMacro.copy(searchBarOpenedOnce = true, lastAttemptAtMs = System.currentTimeMillis()),
                            statusMessage = "সার্চ বার ওপেন করা হয়েছে, টেক্সট টাইপ করা হচ্ছে..."
                        )
                        handler.postDelayed({
                            val freshRoot = rootInActiveWindow
                            if (freshRoot != null && findAndTypeText(freshRoot, steppedMacro.textToType, steppedMacro.targetViewIds)) {
                                AccessibilityAutomationBus.recordMacroSuccess("সার্চ বারে টাইপ ও সাবমিট সম্পন্ন: \"${steppedMacro.textToType}\"")
                            }
                        }, SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(420L))
                        return
                    }
                }
            }

            // 3. Handle Auto-Clicking primary target View IDs or primary button text (single verified click)
            var clickedPrimaryLabel: String? = null
            if (!typedOk && steppedMacro.targetViewIds.isNotEmpty()) {
                for (viewId in steppedMacro.targetViewIds) {
                    if (findAndClickByViewIdAcrossWindows(viewId)) {
                        clickedPrimaryLabel = viewId.substringAfterLast('/')
                        break
                    }
                }
            }

            if (clickedPrimaryLabel == null && steppedMacro.clickTargets.isNotEmpty()) {
                for (targetText in steppedMacro.clickTargets) {
                    if (findAndClickByTextAcrossWindows(targetText, exactOrButtonPreferred = true)) {
                        clickedPrimaryLabel = targetText
                        break
                    }
                }
            }

            // If primary click succeeded, finish immediately (zero extra clicks!)
            if (clickedPrimaryLabel != null && !typedOk) {
                AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন দেখে '$clickedPrimaryLabel'-এ নিখুঁতভাবে ১ বার ক্লিক করা হয়েছে")
                return
            }

            // 4. Secondary target (e.g., video card in YouTube, app card in Play Store, or person's name in Contacts/WhatsApp)
            if (!typedOk && clickedPrimaryLabel == null && steppedMacro.secondaryClickTargets.isNotEmpty()) {
                for (secondaryText in steppedMacro.secondaryClickTargets) {
                    if (findAndClickByTextAcrossWindows(
                            targetText = secondaryText,
                            exactOrButtonPreferred = false,
                            excludeTopSearchHeader = true
                        )
                    ) {
                        val nextStageMacro = steppedMacro.copy(
                            secondaryClickTargets = emptyList(),
                            lastAttemptAtMs = System.currentTimeMillis()
                        )
                        if (nextStageMacro.clickTargets.isEmpty() && nextStageMacro.textToType.isBlank()) {
                            // Immediately complete macro after 1 single click so it NEVER clicks the video/card a second time!
                            AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন দেখে '$secondaryText' নিখুঁতভাবে সিলেক্ট ও ওপেন করা হয়েছে")
                            return
                        }
                        AccessibilityAutomationBus.updatePendingMacro(
                            macro = nextStageMacro,
                            statusMessage = "'$secondaryText' সিলেক্ট করা হয়েছে, পরবর্তী ধাপের জন্য স্ক্রিন দেখা হচ্ছে..."
                        )
                        return
                    }
                }
            }

            // 5. If searching for a contact/name across the whole screen and it's further down the list, auto-scroll and retry
            if (!typedOk && clickedPrimaryLabel == null && steppedMacro.maxAutoScrollRetries > 0) {
                val scrolled = performScrollOnNode(root, true) || dispatchSwipeGesture(isScrollDown = true)
                if (scrolled) {
                    val retryMacro = steppedMacro.copy(
                        maxAutoScrollRetries = steppedMacro.maxAutoScrollRetries - 1,
                        lastAttemptAtMs = System.currentTimeMillis()
                    )
                    AccessibilityAutomationBus.updatePendingMacro(
                        macro = retryMacro,
                        statusMessage = "স্ক্রিনে খোঁজা হচ্ছে (স্ক্রল বাকি: ${retryMacro.maxAutoScrollRetries})..."
                    )
                    return
                }
            }

            if (typedOk) {
                if (steppedMacro.clickTargets.isNotEmpty() || steppedMacro.targetViewIds.isNotEmpty()) {
                    AccessibilityAutomationBus.recordMacroSuccess("টাইপ সম্পন্ন: \"${steppedMacro.textToType}\"")
                    handler.postDelayed({
                        var followUpClicked = false
                        for (viewId in steppedMacro.targetViewIds) {
                            if (viewId.contains("send", ignoreCase = true) || viewId.contains("button1", ignoreCase = true)) {
                                if (findAndClickByViewIdAcrossWindows(viewId)) {
                                    followUpClicked = true
                                    break
                                }
                            }
                        }
                        if (!followUpClicked) {
                            for (t in steppedMacro.clickTargets) {
                                if (findAndClickByTextAcrossWindows(t, exactOrButtonPreferred = true, exactOnly = true)) break
                            }
                        }
                    }, SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(380L))
                } else {
                    AccessibilityAutomationBus.recordMacroSuccess("টাইপ সম্পন্ন: '${steppedMacro.textToType}'")
                }
            }
        } finally {
            isMacroStepInFlight = false
        }
    }

    /**
     * Closed-Loop Self-Healing Play Store & APK Package Installer Supervisor:
     * - Checks the live screen every ~80ms.
     * - Never stops after a single blind click!
     * - Auto-dismisses Play Store dialogs ("Download over any network" -> "OK" / "Continue" / "Accept").
     * - Keeps clicking "Install" / "ইনস্টল করুন" / "Update" (with coordinate gesture tap fallback) until
     *   real installation progress ("Pending...", "%", "Downloading...", "Installing...", "Cancel") is verified on screen.
     * - Monitors until final installation ("Open" / "Play" / "Uninstall" or PackageManager confirmation) is complete!
     */
    private fun executePlayStoreInstallSupervisor(root: AccessibilityNodeInfo, macro: PendingUiMacro) {
        val now = System.currentTimeMillis()
        val appTitle = macro.targetAppName.ifBlank { "App" }
        val elems = refreshSnapshotNow()
        val pkg = root.packageName?.toString().orEmpty()
        val screenText = AccessibilityAutomationBus.state.value.visibleScreenText
        val lower = screenText.lowercase()

        // 1. Check if a system Package Installer or Play Store confirmation dialog is open ("Continue", "Accept")
        for (viewId in macro.targetViewIds) {
            if (findAndClickByViewIdAcrossWindows(viewId)) {
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = macro.copy(lastInstallClickAtMs = now, lastAttemptAtMs = now),
                    statusMessage = "কনফার্মেশন বাটনে অটো-ক্লিক করা হয়েছে ($appTitle)..."
                )
                return
            }
        }
        if (pkg.contains("packageinstaller") || lower.contains("download") || lower.contains("permission")) {
            val dialogConfirmLabels = listOf(
                "Continue", "Accept", "চালিয়ে যান", "গ্রহণ করুন"
            )
            for (dlgBtn in dialogConfirmLabels) {
                if (findAndClickByTextAcrossWindows(dlgBtn, exactOrButtonPreferred = true, exactOnly = true)) {
                    AccessibilityAutomationBus.updatePendingMacro(
                        macro = macro.copy(lastInstallClickAtMs = now, lastAttemptAtMs = now),
                        statusMessage = "পপআপ ডায়ালগ '$dlgBtn' অটো-কনফার্ম করা হয়েছে ($appTitle)..."
                    )
                    return
                }
            }
        }

        // 2. Check if real Download / Installation progress is genuinely visible on the screen right now
        val pctMatch = Regex("(\\d{1,3})\\s*%").find(screenText)?.value.orEmpty()
        val isInstallingNow = lower.contains("installing") || lower.contains("ইনস্টল হচ্ছে")
        val isPendingOrDownloading = pctMatch.isNotBlank() ||
            lower.contains("pending") ||
            lower.contains("পেন্ডিং") ||
            lower.contains("downloading") ||
            lower.contains("ডাউনলোড হচ্ছে") ||
            lower.contains("waiting for") ||
            elems.any { e ->
                e.clickable && (
                    e.label.equals("Cancel", ignoreCase = true) ||
                        e.label.equals("বাতিল", ignoreCase = true)
                    )
            }

        val installButtonLabels = if (macro.isUpdateInstall) {
            listOf("Update", "আপডেট করুন", "আপডেট", "Install", "ইনস্টল করুন", "ইনস্টল")
        } else {
            listOf("Install", "ইনস্টল করুন", "ইনস্টল", "Update", "আপডেট করুন", "আপডেট")
        }

        val installElemOnScreen = elems.firstOrNull { e ->
            val cleanLbl = e.label.trim()
            !cleanLbl.contains("Uninstall", ignoreCase = true) &&
                !cleanLbl.contains("আনইনস্টল") &&
                installButtonLabels.any { target ->
                    cleanLbl.equals(target, ignoreCase = true) ||
                        cleanLbl.startsWith("$target ", ignoreCase = true)
                }
        }

        val hasOpenOrPlayElem = elems.any { e ->
            val cleanLbl = e.label.trim()
            e.clickable && (
                cleanLbl.equals("Open", ignoreCase = true) ||
                    cleanLbl.equals("খুলুন", ignoreCase = true) ||
                    cleanLbl.equals("ওপেন", ignoreCase = true) ||
                    cleanLbl.equals("Play", ignoreCase = true) ||
                    cleanLbl.equals("চালান", ignoreCase = true)
                )
        }
        val hasUninstallElem = elems.any { e ->
            val cleanLbl = e.label.trim()
            cleanLbl.equals("Uninstall", ignoreCase = true) || cleanLbl.contains("আনইনস্টল")
        }

        // 3. Check if Installation is 100% Completed & Confirmed (including if app was already installed)!
        val isPackageNowInstalled = !macro.isUpdateInstall &&
            macro.knownPackage.isNotBlank() &&
            macro.installRetryCount > 0 &&
            try {
                applicationContext.packageManager.getLaunchIntentForPackage(macro.knownPackage) != null
            } catch (_: Exception) {
                false
            }

        val isScreenConfirmedInstalled = !isInstallingNow &&
            !isPendingOrDownloading &&
            installElemOnScreen == null &&
            (hasOpenOrPlayElem || hasUninstallElem)

        if (isPackageNowInstalled || isScreenConfirmedInstalled) {
            val confirmedStage = "PLAYSTORE_INSTALLED_READY: স্যার, '$appTitle' সফলভাবে ইনস্টল সম্পন্ন হয়েছে এবং স্ক্রিন দেখে কনফার্ম করা হয়েছে!"
            AccessibilityAutomationBus.updateScreenSnapshot(
                pkg = pkg,
                screenText = screenText,
                progressStage = confirmedStage
            )
            AccessibilityAutomationBus.recordMacroSuccess(confirmedStage)
            return
        }

        // 4. If real Download / Install progress is visible on screen, mark verifiedDownloadStarted = true and keep watching!
        if (isInstallingNow || isPendingOrDownloading) {
            val progressDesc = when {
                isInstallingNow -> "এখন ইনস্টল হচ্ছে (Installing)..."
                pctMatch.isNotBlank() -> "ডাউনলোড হচ্ছে ($pctMatch সম্পন্ন)..."
                else -> "ডাউনলোড প্রসেস শুরু হয়েছে (Pending/Downloading)..."
            }
            val verifiedStage = "PLAYSTORE_INSTALL_IN_PROGRESS: স্যার, স্ক্রিনে কনফার্ম হয়েছি '$appTitle' $progressDesc"
            AccessibilityAutomationBus.updateScreenSnapshot(
                pkg = pkg,
                screenText = screenText,
                progressStage = verifiedStage
            )
            if (!macro.verifiedDownloadStarted) {
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = macro.copy(verifiedDownloadStarted = true, lastAttemptAtMs = now),
                    statusMessage = verifiedStage
                )
            }
            return
        }

        // 5. If "Install" button is on screen, click it up to max 3 times (with 2.2s spacing, single tap per tick)
        if (now - macro.lastInstallClickAtMs < 2200L) {
            return
        }
        if (macro.installRetryCount >= 3) {
            AccessibilityAutomationBus.recordMacroSuccess("'$appTitle'-এর ইনস্টল বাটনে ক্লিক সম্পন্ন হয়েছে")
            return
        }

        var clickedInstallNow = false
        for (btnLabel in installButtonLabels) {
            if (findAndClickByTextAcrossWindows(btnLabel, exactOrButtonPreferred = true, excludeTopSearchHeader = true, exactOnly = true)) {
                clickedInstallNow = true
                break
            }
        }

        // Only use coordinate tap fallback if node click didn't fire
        if (!clickedInstallNow && installElemOnScreen != null) {
            val cx = installElemOnScreen.cx.toFloat()
            val cy = installElemOnScreen.cy.toFloat()
            TouchEffectOverlay.tap(cx, cy)
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L))
                .build()
            dispatchGesture(gesture, null, null)
            clickedInstallNow = true
        }

        if (clickedInstallNow) {
            val nextAttempt = macro.installRetryCount + 1
            val retryStage = if (nextAttempt == 1) {
                "PLAYSTORE_CLICKED_INSTALL: প্লে স্টোরে '$appTitle'-এর ইনস্টল বাটনে চাপ দেওয়া হয়েছে..."
            } else {
                "PLAYSTORE_RETRY_INSTALL_#$nextAttempt: '$appTitle'-এর ইনস্টল বাটনে ট্যাপ করা হয়েছে (চেষ্টা #$nextAttempt)..."
            }
            AccessibilityAutomationBus.updateScreenSnapshot(
                pkg = pkg,
                screenText = screenText,
                progressStage = retryStage
            )
            AccessibilityAutomationBus.updatePendingMacro(
                macro = macro.copy(
                    lastInstallClickAtMs = now,
                    lastAttemptAtMs = now,
                    installRetryCount = nextAttempt
                ),
                statusMessage = retryStage
            )
            return
        }

        // 5B. If Play Store is showing an active focused search field (keyboard open), press IME_ENTER once to submit search!
        val focusedSearchNode = findTopSearchEditableNode(root)
        if (focusedSearchNode != null && focusedSearchNode.isFocused && !macro.searchBarOpenedOnce) {
            submitImeEnterIfSearchField(focusedSearchNode)
            AccessibilityAutomationBus.updatePendingMacro(
                macro = macro.copy(
                    searchBarOpenedOnce = true,
                    lastInstallClickAtMs = now,
                    lastAttemptAtMs = now
                ),
                statusMessage = "প্লে স্টোর সার্চ রেজাল্ট ওপেন করা হচ্ছে ($appTitle)..."
            )
            return
        }

        // 6. If "Install" button is not visible yet (e.g. search results list), click the app title card ONCE to open its Details page!
        if (!macro.openedDetailsCard) {
            for (cardTarget in (macro.secondaryClickTargets + listOf(appTitle)).distinct()) {
                if (cardTarget.isNotBlank() &&
                    findAndClickByTextAcrossWindows(
                        targetText = cardTarget,
                        exactOrButtonPreferred = false,
                        excludeTopSearchHeader = true
                    )
                ) {
                    AccessibilityAutomationBus.updatePendingMacro(
                        macro = macro.copy(
                            openedDetailsCard = true,
                            lastInstallClickAtMs = now,
                            lastAttemptAtMs = now
                        ),
                        statusMessage = "সার্চ রেজাল্ট থেকে '$cardTarget' অ্যাপ পেজ ওপেন করা হয়েছে..."
                    )
                    return
                }
            }
        }

        // 7. If neither Install button nor app card was found, scroll down at most once or finish cleanly
        if (macro.maxAutoScrollRetries > 0) {
            if (performScrollOnNode(root, true) || dispatchSwipeGesture(isScrollDown = true)) {
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = macro.copy(
                        maxAutoScrollRetries = macro.maxAutoScrollRetries - 1,
                        lastInstallClickAtMs = now,
                        lastAttemptAtMs = now
                    ),
                    statusMessage = "প্লে স্টোরে স্ক্রল করে '$appTitle' খোঁজা হচ্ছে..."
                )
                return
            }
        }
        if (macro.attemptCount >= 3) {
            AccessibilityAutomationBus.recordMacroSuccess("প্লে স্টোরে '$appTitle' ওপেন সম্পন্ন হয়েছে")
        }
    }

    /**
     * Multi-stage screen-guided chat messaging:
     * 1. Inspects the live screen every 340ms.
     * 2. Distinguishes between a top Search bar and a bottom Chat Message input box.
     * 3. If a recipient name is specified and we are not inside the chat yet, clicks the contact on screen
     *    (or searches the contact name in the top search bar and clicks the matching row).
     * 4. Once the bottom Chat Message input box is visible on screen, types ONLY the clean message text
     *    into the message box and clicks Send.
     */
    private fun executeScreenGuidedChatMessagingMacro(root: AccessibilityNodeInfo, macro: PendingUiMacro) {
        val cleanRecipient = macro.contactRecipientToOpen.trim()
        val cleanMessage = macro.textToType.trim()
        val chatInputNode = findBottomChatMessageInputNode(root, macro.targetViewIds)

        // Stage 1: Need to open the recipient's chat conversation first
        if (cleanRecipient.isNotBlank() && chatInputNode == null) {
            // Try clicking the recipient's name directly on the current chat list or search results
            if (findAndClickByTextAcrossWindows(cleanRecipient, exactOrButtonPreferred = false)) {
                val openedContactMacro = macro.copy(contactRecipientToOpen = "")
                if (cleanMessage.isBlank()) {
                    AccessibilityAutomationBus.recordMacroSuccess("'$cleanRecipient'-এর চ্যাট স্ক্রিন ওপেন করা হয়েছে")
                    return
                }
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = openedContactMacro,
                    statusMessage = "'$cleanRecipient'-এর চ্যাট ওপেন হয়েছে, এখন মেসেজ বক্স দেখে মেসেজ টাইপ করা হচ্ছে..."
                )
                handler.postDelayed({ tryExecutePendingMacro() }, 380L)
                return
            }

            // If recipient not directly visible on list, use the top search bar to find the contact
            if (!macro.contactSearchTyped) {
                val topSearchNode = findTopSearchEditableNode(root)
                if (topSearchNode != null) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            cleanRecipient
                        )
                    }
                    topSearchNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    if (topSearchNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                        submitImeEnterIfSearchField(topSearchNode)
                        AccessibilityAutomationBus.updatePendingMacro(
                            macro = macro.copy(contactSearchTyped = true),
                            statusMessage = "সার্চ বারে '$cleanRecipient' লিখে খোঁজা হচ্ছে..."
                        )
                        handler.postDelayed({ tryExecutePendingMacro() }, 450L)
                        return
                    }
                } else {
                    val clickedSearchIcon = findAndClickByViewIdAcrossWindows("com.whatsapp:id/menuitem_search") ||
                        findAndClickByViewIdAcrossWindows("org.telegram.messenger:id/menu_search") ||
                        findAndClickByTextAcrossWindows("Search", exactOrButtonPreferred = true) ||
                        findAndClickByTextAcrossWindows("খুঁজুন", exactOrButtonPreferred = true) ||
                        findAndClickByTextAcrossWindows("সার্চ", exactOrButtonPreferred = true)
                    if (clickedSearchIcon) {
                        AccessibilityAutomationBus.updatePendingMacro(
                            macro = macro,
                            statusMessage = "সার্চ বক্স ওপেন করা হয়েছে, '$cleanRecipient' টাইপ করা হচ্ছে..."
                        )
                        handler.postDelayed({ tryExecutePendingMacro() }, 360L)
                        return
                    }
                }
            }

            // If list is scrollable and contact isn't visible yet, scroll down once
            if (macro.maxAutoScrollRetries > 0) {
                if (performScrollOnNode(root, true) || dispatchSwipeGesture(isScrollDown = true)) {
                    AccessibilityAutomationBus.updatePendingMacro(
                        macro = macro.copy(maxAutoScrollRetries = macro.maxAutoScrollRetries - 1),
                        statusMessage = "স্ক্রিন দেখে '$cleanRecipient' খোঁজা হচ্ছে..."
                    )
                }
            }
            return
        }

        // Stage 2: We are inside the chat conversation (or no contact navigation needed)
        if (cleanMessage.isBlank()) {
            AccessibilityAutomationBus.recordMacroSuccess("চ্যাট স্ক্রিন ওপেন সম্পন্ন")
            return
        }

        val targetInputNode = chatInputNode ?: findBottomChatMessageInputNode(root, emptyList())
        if (targetInputNode != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    cleanMessage
                )
            }
            targetInputNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = targetInputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (typed) {
                AccessibilityAutomationBus.updatePendingMacro(
                    macro = null,
                    statusMessage = "মেসেজ টাইপ সম্পন্ন: \"$cleanMessage\" — এখনই Send বাটনে চাপ দেওয়া হচ্ছে..."
                )
                handler.postDelayed({
                    var sentClicked = false
                    for (viewId in macro.targetViewIds) {
                        if (viewId.contains("send", ignoreCase = true)) {
                            if (findAndClickByViewIdAcrossWindows(viewId)) {
                                sentClicked = true
                                break
                            }
                        }
                    }
                    if (!sentClicked) {
                        for (lbl in listOf("Send", "পাঠান", "Send message", "সেন্ড")) {
                            if (findAndClickByTextAcrossWindows(lbl, exactOrButtonPreferred = true)) {
                                sentClicked = true
                                break
                            }
                        }
                    }
                    AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন দেখে নিখুঁতভাবে মেসেজ পাঠানো হয়েছে: \"$cleanMessage\"")
                }, 320L)
            }
        }
    }

    private fun findBottomChatMessageInputNode(
        root: AccessibilityNodeInfo,
        preferredViewIds: List<String>
    ): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        // Check explicit chat message input viewIds first
        for (vid in preferredViewIds) {
            if (vid.contains("entry", ignoreCase = true) ||
                vid.contains("chat_input", ignoreCase = true) ||
                vid.contains("msg", ignoreCase = true) ||
                vid.contains("composer", ignoreCase = true)
            ) {
                val nodes = try { root.findAccessibilityNodeInfosByViewId(vid) } catch (_: Exception) { null }
                val match = nodes?.firstOrNull { it.isEditable && it.isVisibleToUser }
                if (match != null) return match
            }
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var fallbackEditable: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isEditable && cur.isVisibleToUser) {
                val vid = cur.viewIdResourceName?.lowercase().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cur.hintText?.toString()?.lowercase().orEmpty()
                } else ""
                val txt = cur.text?.toString()?.lowercase().orEmpty()
                val rect = Rect()
                cur.getBoundsInScreen(rect)
                val isSearchField = vid.contains("search") ||
                    hint.contains("search") ||
                    hint.contains("খুঁজুন") ||
                    txt.contains("search...")
                val isChatComposer = vid.contains("entry") ||
                    vid.contains("chat_input") ||
                    vid.contains("composer") ||
                    vid.contains("message") ||
                    hint.contains("message") ||
                    hint.contains("মেসেজ") ||
                    rect.centerY() > (screenH * 0.48f)
                if (!isSearchField && isChatComposer) {
                    return cur
                }
                if (!isSearchField) {
                    fallbackEditable = cur
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return fallbackEditable
    }

    private fun findTopSearchEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(1280)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isEditable && cur.isVisibleToUser) {
                val vid = cur.viewIdResourceName?.lowercase().orEmpty()
                val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cur.hintText?.toString()?.lowercase().orEmpty()
                } else ""
                val rect = Rect()
                cur.getBoundsInScreen(rect)
                if (vid.contains("search") ||
                    hint.contains("search") ||
                    hint.contains("খুঁজুন") ||
                    rect.centerY() < (screenH * 0.35f)
                ) {
                    return cur
                }
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /**
     * Opens Android Recent Apps overview (`GLOBAL_ACTION_RECENTS`) and removes the target app
     * (by swiping the app card upward off the screen) or clears all recent apps ("Clear all" / "Close all").
     */
    private fun executeClearRecentAppsMacro(macro: PendingUiMacro) {
        val targetApp = macro.clickTargets.firstOrNull().orEmpty().trim()
        val clearAllRequested = targetApp.isBlank() ||
            targetApp.equals("ALL", ignoreCase = true) ||
            targetApp.contains("সব") ||
            targetApp.contains("all", ignoreCase = true)

        // Step 1: Open Recents screen
        performGlobalAction(GLOBAL_ACTION_RECENTS)
        AccessibilityAutomationBus.updatePendingMacro(
            macro = macro.copy(globalAction = ""),
            statusMessage = "রিসেন্ট অ্যাপস ওপেন করে অ্যাপ সরানো হচ্ছে..."
        )

        handler.postDelayed({
            var clearedViaButton = false
            if (clearAllRequested) {
                val clearAllLabels = listOf(
                    "Clear all", "Close all", "সব মুছুন", "সবগুলো বন্ধ করুন", "সব ক্লিয়ার করুন", "Clear"
                )
                for (lbl in clearAllLabels) {
                    if (findAndClickByTextAcrossWindows(lbl, exactOrButtonPreferred = true)) {
                        clearedViaButton = true
                        break
                    }
                }
            }

            if (!clearedViaButton) {
                // Swipe upward on the Recent App card to fling/dismiss it from Recents
                dispatchRecentsCardDismissSwipe()
                if (clearAllRequested) {
                    handler.postDelayed({
                        val clearAllLabels = listOf("Clear all", "Close all", "সব মুছুন", "Clear")
                        var clickedAfterSwipe = false
                        for (lbl in clearAllLabels) {
                            if (findAndClickByTextAcrossWindows(lbl, exactOrButtonPreferred = true)) {
                                clickedAfterSwipe = true
                                break
                            }
                        }
                        if (!clickedAfterSwipe) {
                            dispatchRecentsCardDismissSwipe()
                        }
                    }, 220L)
                }
            }

            AccessibilityAutomationBus.recordMacroSuccess(
                if (clearAllRequested) {
                    "রিসেন্ট অ্যাপস থেকে অ্যাপগুলো সোয়াইপ করে ক্লিয়ার করা হয়েছে"
                } else {
                    "রিসেন্ট অ্যাপস থেকে '$targetApp' সোয়াইপ করে সরিয়ে দেওয়া হয়েছে"
                }
            )
        }, 280L)
    }

    private fun dispatchRecentsCardDismissSwipe(): Boolean {
        return try {
            val dm = resources.displayMetrics
            val cx = (dm.widthPixels.coerceAtLeast(720)) * 0.5f
            val h = (dm.heightPixels.coerceAtLeast(1280)).toFloat()
            val startY = h * 0.68f
            val endY = h * 0.10f
            TouchEffectOverlay.swipe(cx, startY, cx, endY)
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 140L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Pulls down the Notification Bar / Quick Settings shade (`GLOBAL_ACTION_QUICK_SETTINGS`),
     * locates the requested Quick Settings tile (Wi-Fi, Mobile Data, Bluetooth, Airplane Mode,
     * Hotspot, Location, Torch, Auto-Rotate, DND, etc.), clicks it, and closes the shade.
     */
    private fun executeQuickSettingsTileToggle(macro: PendingUiMacro) {
        if (quickSettingsShadeOpenedForMacroId != macro.id) {
            quickSettingsShadeOpenedForMacroId = macro.id
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            AccessibilityAutomationBus.updatePendingMacro(
                macro = macro,
                statusMessage = "নোটিফিকেশন বার নামিয়ে '${macro.quickSettingTileTargets.firstOrNull()}' টগল খোঁজা হচ্ছে..."
            )
            handler.postDelayed({
                tryClickQuickSettingsTileNow(macro, attempt = 1)
            }, SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(600L))
        } else {
            tryClickQuickSettingsTileNow(macro, attempt = 2)
        }
    }

    private fun tryClickQuickSettingsTileNow(macro: PendingUiMacro, attempt: Int) {
        var clickedTileLabel: String? = null

        // Pass 1: Search all interactive windows (SystemUI Quick Settings shade + Settings Panels)
        for (tileName in macro.quickSettingTileTargets) {
            if (findAndClickByTextAcrossWindows(tileName, exactOrButtonPreferred = false)) {
                clickedTileLabel = tileName
                break
            }
        }

        // Pass 2: If a Settings Panel popup is open with a Switch/ToggleButton, click the first Switch
        if (clickedTileLabel == null) {
            if (clickFirstSwitchOrToggleAcrossWindows()) {
                clickedTileLabel = macro.quickSettingTileTargets.firstOrNull() ?: "Switch"
            }
        }

        if (clickedTileLabel != null) {
            AccessibilityAutomationBus.recordMacroSuccess("নোটিফিকেশন বার টগল সম্পন্ন: '$clickedTileLabel'")
            if (macro.closeShadeAfterClick) {
                handler.postDelayed({
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    } else {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(500L))
            }
        } else if (attempt < 2) {
            // Expand Quick Settings a second time to reveal all tiles in the full grid
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({
                tryClickQuickSettingsTileNow(macro, attempt = 2)
            }, SmartMemoryAndSelfUpdateEngine.uiStepDelayMs(550L))
        } else {
            AccessibilityAutomationBus.recordMacroSuccess(
                "কুইক সেটিংস প্যানেল খোলা হয়েছে: ${macro.quickSettingTileTargets.firstOrNull().orEmpty()}"
            )
        }
    }

    /**
     * Executes real screen touch & swipe gestures using Android's `dispatchGesture` API at snappy human speed.
     */
    private fun executeScreenTouchGesture(macro: PendingUiMacro) {
        val type = macro.gestureType.uppercase()
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.coerceAtLeast(720).toFloat()
        val screenH = dm.heightPixels.coerceAtLeast(1280).toFloat()

        val x = if (macro.gestureX > 1f) macro.gestureX.coerceIn(20f, screenW - 20f) else screenW * 0.5f
        val y = if (macro.gestureY > 1f) macro.gestureY.coerceIn(40f, screenH - 40f) else screenH * 0.5f

        when (type) {
            "TAP_XY", "TAP_CENTER", "TAP", "DOUBLE_TAP", "LONG_PRESS" -> TouchEffectOverlay.tap(x, y)
            "SWIPE_LEFT", "SCROLL_RIGHT" -> TouchEffectOverlay.swipe(screenW * 0.84f, screenH * 0.5f, screenW * 0.16f, screenH * 0.5f)
            "SWIPE_RIGHT", "SCROLL_LEFT" -> TouchEffectOverlay.swipe(screenW * 0.16f, screenH * 0.5f, screenW * 0.84f, screenH * 0.5f)
            else -> {}
        }
        when (type) {
            "TAP_XY", "TAP_CENTER", "TAP" -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 48L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন টাচ সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "DOUBLE_TAP" -> {
                val path1 = Path().apply { moveTo(x, y) }
                val path2 = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path1, 0L, 42L))
                    .addStroke(GestureDescription.StrokeDescription(path2, 85L, 42L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "ডাবল ট্যাপ সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "LONG_PRESS" -> {
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 620L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "লং প্রেস সম্পন্ন (X: ${x.toInt()}, Y: ${y.toInt()})"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "SWIPE_UP", "SCROLL_DOWN" -> {
                dispatchSwipeGesture(isScrollDown = true)
                AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন সোয়াইপ ডাউন সম্পন্ন")
            }
            "SWIPE_DOWN", "SCROLL_UP" -> {
                dispatchSwipeGesture(isScrollDown = false)
                AccessibilityAutomationBus.recordMacroSuccess("স্ক্রিন সোয়াইপ আপ সম্পন্ন")
            }
            "SWIPE_LEFT", "SCROLL_RIGHT" -> {
                val path = Path().apply {
                    moveTo(screenW * 0.84f, screenH * 0.5f)
                    lineTo(screenW * 0.16f, screenH * 0.5f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 155L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন বামে সোয়াইপ সম্পন্ন"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            "SWIPE_RIGHT", "SCROLL_LEFT" -> {
                val path = Path().apply {
                    moveTo(screenW * 0.16f, screenH * 0.5f)
                    lineTo(screenW * 0.84f, screenH * 0.5f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 155L))
                    .build()
                dispatchGesture(gesture, null, null)
                val summary = "স্ক্রিন ডানে সোয়াইপ সম্পন্ন"
                AccessibilityAutomationBus.updateTouchTelemetry(summary)
                AccessibilityAutomationBus.recordMacroSuccess(summary)
            }
            else -> {
                AccessibilityAutomationBus.recordMacroSuccess("টাচ কমান্ড সম্পন্ন")
            }
        }
    }

    private fun dispatchSwipeGesture(isScrollDown: Boolean): Boolean {
        return try {
            val dm = resources.displayMetrics
            val cx = (dm.widthPixels.coerceAtLeast(720)) * 0.5f
            val h = (dm.heightPixels.coerceAtLeast(1280)).toFloat()
            val startY = if (isScrollDown) h * 0.75f else h * 0.28f
            val endY = if (isScrollDown) h * 0.25f else h * 0.78f
            TouchEffectOverlay.swipe(cx, startY, cx, endY)
            val path = Path().apply {
                moveTo(cx, startY)
                lineTo(cx, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 165L))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun getAllWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (win in windows) {
                win.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        rootInActiveWindow?.let { activeRoot ->
            if (roots.none { it == activeRoot }) {
                roots.add(0, activeRoot)
            }
        }
        return roots
    }

    private fun findAndClickByViewIdAcrossWindows(viewId: String): Boolean {
        for (root in getAllWindowRoots()) {
            if (findAndClickByViewId(root, viewId)) return true
        }
        return false
    }

    private fun findAndClickByTextAcrossWindows(
        targetText: String,
        exactOrButtonPreferred: Boolean = false,
        excludeTopSearchHeader: Boolean = false,
        exactOnly: Boolean = false
    ): Boolean {
        for (root in getAllWindowRoots()) {
            if (findAndClickByText(root, targetText, exactOrButtonPreferred, excludeTopSearchHeader, exactOnly)) return true
        }
        return false
    }

    private fun clickFirstSwitchOrToggleAcrossWindows(): Boolean {
        for (root in getAllWindowRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                val cls = cur.className?.toString()?.lowercase().orEmpty()
                if (cls.contains("switch") || cls.contains("togglebutton") || cur.isCheckable) {
                    if (clickNodeOrParent(cur)) return true
                }
                for (i in 0 until cur.childCount) {
                    cur.getChild(i)?.let { queue.add(it) }
                }
            }
        }
        return false
    }

    private fun collectVisibleTextAndButtons(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        outButtons: MutableList<String>,
        maxItems: Int
    ) {
        if (node == null || outList.size >= maxItems) return
        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val label = txt.ifBlank { desc }
        if (label.length in 2..140 && !outList.contains(label)) {
            outList.add(label)
            if ((node.isClickable || node.parent?.isClickable == true) && outButtons.size < 20) {
                outButtons.add(label)
            }
        }
        for (i in 0 until node.childCount) {
            if (outList.size >= maxItems) break
            collectVisibleTextAndButtons(node.getChild(i), outList, outButtons, maxItems)
        }
    }

    private fun submitImeEnterIfSearchField(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val viewId = node.viewIdResourceName?.lowercase().orEmpty()
            val pkg = node.packageName?.toString()?.lowercase().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.lowercase().orEmpty()
            } else ""
            val isSearchInput = viewId.contains("search") ||
                viewId.contains("url_bar") ||
                hint.contains("search") ||
                hint.contains("সার্চ") ||
                pkg.contains("youtube") ||
                pkg.contains("vending") ||
                pkg.contains("chrome")
            if (isSearchInput) {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
        }
    }

    private fun isVoiceSearchOrMicNode(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 3) {
            val txt = cur.text?.toString()?.lowercase().orEmpty()
            val desc = cur.contentDescription?.toString()?.lowercase().orEmpty()
            val vid = cur.viewIdResourceName?.lowercase().orEmpty()
            if (txt.contains("voice search") ||
                txt.contains("search with your voice") ||
                txt.contains("ভয়েস সার্চ") ||
                desc.contains("voice search") ||
                desc.contains("search with your voice") ||
                desc.contains("ভয়েস সার্চ") ||
                vid.contains("voice_search") ||
                vid.contains("voice_btn") ||
                vid.contains("mic_button")
            ) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun findAndTypeText(
        root: AccessibilityNodeInfo,
        text: String,
        preferredViewIds: List<String> = emptyList()
    ): Boolean {
        val cleanText = text.trim()
        // Never type raw macro command keywords or coordinates into any input field
        if (cleanText.isBlank() ||
            cleanText.equals("CLICK", ignoreCase = true) ||
            cleanText.equals("TAP", ignoreCase = true) ||
            cleanText.equals("TYPE", ignoreCase = true) ||
            cleanText.equals("SCROLL_DOWN", ignoreCase = true) ||
            cleanText.equals("SCROLL_UP", ignoreCase = true) ||
            Regex("^\\d+\\s*[,xX]\\s*\\d+$").matches(cleanText)
        ) {
            return false
        }

        for (viewId in preferredViewIds) {
            val idNodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }
            if (!idNodes.isNullOrEmpty()) {
                for (node in idNodes) {
                    if (node.isEditable) {
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                cleanText
                            )
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            submitImeEnterIfSearchField(node)
                            return true
                        }
                    }
                }
            }
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current.isEditable) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        cleanText
                    )
                }
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = current.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (ok) {
                    submitImeEnterIfSearchField(current)
                    return true
                }
            }
            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun findAndClickByViewId(root: AccessibilityNodeInfo, viewId: String): Boolean {
        if (viewId.isBlank()) return false
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(viewId)
        } catch (_: Exception) {
            null
        }
        if (!nodes.isNullOrEmpty()) {
            for (node in nodes) {
                if (!node.isEditable && !isVoiceSearchOrMicNode(node) && clickNodeOrParent(node)) {
                    return true
                }
            }
        }
        return false
    }

    private fun isSearchBoxOrEditableNode(node: AccessibilityNodeInfo?): Boolean {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            if (cur.isEditable) return true
            val cls = cur.className?.toString()?.lowercase().orEmpty()
            val viewId = cur.viewIdResourceName?.lowercase().orEmpty()
            if (cls.contains("edittext") ||
                viewId.contains("search_box") ||
                viewId.contains("search_src_text") ||
                viewId.contains("search_edit")
            ) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    private fun isNodeInTopSearchHeader(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val screenH = resources.displayMetrics.heightPixels.coerceAtLeast(800)
        return rect.centerY() < (screenH * 0.19f).toInt()
    }

    private fun isConflictingInstallText(lowerTarget: String, candidateText: String): Boolean {
        val asksInstall = lowerTarget == "install" || lowerTarget == "ইনস্টল করুন" || lowerTarget == "ইনস্টল"
        if (!asksInstall) return false
        return candidateText.contains("uninstall") ||
            candidateText.contains("installed") ||
            candidateText.contains("আনইনস্টল")
    }

    private data class ScoredClickCandidate(
        val node: AccessibilityNodeInfo,
        val clickableAncestor: AccessibilityNodeInfo?,
        val score: Int,
        val tapX: Int,
        val tapY: Int,
        val matchedLabel: String
    )

    private fun resolveTightClickableAncestor(
        node: AccessibilityNodeInfo,
        screenW: Int,
        screenH: Int
    ): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        val maxAllowedArea = (screenW * screenH * 0.58f).toInt()
        while (cur != null && depth <= 4) {
            if (cur.isClickable && !cur.isEditable) {
                val r = Rect()
                cur.getBoundsInScreen(r)
                val area = r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0)
                if (area in 64..maxAllowedArea) {
                    return cur
                }
            }
            cur = cur.parent
            depth++
        }
        return null
    }

    /**
     * Screen-First Smart Visual Node Scorer:
     * Inspects all visible nodes in `root`, scores each candidate by label match quality,
     * button/clickable status, and screen position, and clicks ONLY the single highest-scoring target.
     */
    private fun findAndClickByText(
        root: AccessibilityNodeInfo,
        targetText: String,
        exactOrButtonPreferred: Boolean = false,
        excludeTopSearchHeader: Boolean = false,
        exactOnly: Boolean = false
    ): Boolean {
        if (targetText.isBlank()) return false
        val lowerTarget = targetText.trim().lowercase()
        val allowVoiceMic = lowerTarget.contains("voice") ||
            lowerTarget.contains("ভয়েস") ||
            lowerTarget.contains("mic")

        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(720)
        val screenH = metrics.heightPixels.coerceAtLeast(1280)
        val maxContainerArea = (screenW * screenH * 0.60f).toInt()

        var bestCandidate: ScoredClickCandidate? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 320) {
            val cur = queue.removeFirst()
            visited++
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }

            if (!cur.isVisibleToUser) continue
            if (isSearchBoxOrEditableNode(cur)) continue
            if (excludeTopSearchHeader && isNodeInTopSearchHeader(cur)) continue
            if (!allowVoiceMic && isVoiceSearchOrMicNode(cur)) continue

            val txt = cur.text?.toString()?.trim().orEmpty()
            val desc = cur.contentDescription?.toString()?.trim().orEmpty()
            if (txt.isEmpty() && desc.isEmpty()) continue

            val lowerTxt = txt.lowercase()
            val lowerDesc = desc.lowercase()
            if (isConflictingInstallText(lowerTarget, "$lowerTxt $lowerDesc")) continue

            val isExactTxt = lowerTxt == lowerTarget
            val isExactDesc = lowerDesc == lowerTarget
            val isExact = isExactTxt || isExactDesc

            if ((exactOnly || lowerTarget.length <= 2) && !isExact) continue

            val isStartsWith = !isExact && (
                lowerTxt.startsWith("$lowerTarget ") ||
                    lowerDesc.startsWith("$lowerTarget ") ||
                    lowerTxt.startsWith(lowerTarget) ||
                    lowerDesc.startsWith(lowerTarget)
                )
            val isContains = !isExact && !isStartsWith && (
                lowerTxt.contains(lowerTarget) || lowerDesc.contains(lowerTarget)
                )

            if (!isExact && !isStartsWith && !isContains) continue

            val matchedLen = when {
                isExactTxt || lowerTxt.contains(lowerTarget) -> lowerTxt.length
                else -> lowerDesc.length
            }
            if (exactOrButtonPreferred && !isExact && matchedLen > lowerTarget.length + 24) {
                continue
            }

            val rect = Rect()
            cur.getBoundsInScreen(rect)
            if (rect.width() < 8 || rect.height() < 8) continue
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= screenW || rect.top >= screenH) continue

            val nodeArea = rect.width() * rect.height()
            if (nodeArea > maxContainerArea) continue

            val clickableTarget = resolveTightClickableAncestor(cur, screenW, screenH)
            val clsName = cur.className?.toString()?.lowercase().orEmpty()
            val isButtonClass = clsName.contains("button") || clsName.contains("chip") || clsName.contains("tab")

            var score = when {
                isExact -> 100
                isStartsWith -> 78
                else -> 58 - ((matchedLen - lowerTarget.length).coerceIn(0, 40) / 3)
            }

            if (cur.isClickable) score += 26
            else if (clickableTarget != null) score += 18
            else score -= 15

            if (isButtonClass) score += 16
            if (isNodeInTopSearchHeader(cur)) score -= 24

            val bestNow = bestCandidate
            if (bestNow == null || score > bestNow.score) {
                bestCandidate = ScoredClickCandidate(
                    node = cur,
                    clickableAncestor = clickableTarget,
                    score = score,
                    tapX = rect.centerX(),
                    tapY = rect.centerY(),
                    matchedLabel = txt.ifBlank { desc }.take(40)
                )
            }
        }

        val winner = bestCandidate ?: return false
        return executeVerifiedSingleClick(winner)
    }

    private fun executeVerifiedSingleClick(candidate: ScoredClickCandidate): Boolean {
        val nowUptime = android.os.SystemClock.uptimeMillis()
        val sig = "${candidate.matchedLabel.lowercase()}@${candidate.tapX / 24},${candidate.tapY / 24}"
        if (sig == lastClickSignature && nowUptime - lastClickAtUptimeMs < 1450L) {
            // Prevent duplicate/extra click on the exact same UI target within 1.45s!
            return true
        }
        if (nowUptime - lastClickAtUptimeMs < 480L) {
            return false
        }

        lastClickSignature = sig
        lastClickAtUptimeMs = nowUptime

        val cx = candidate.tapX.toFloat()
        val cy = candidate.tapY.toFloat()
        AccessibilityAutomationBus.updateTouchTelemetry(
            "স্ক্রিন ভেরিফাইড ক্লিক: '${candidate.matchedLabel}' (${candidate.tapX}, ${candidate.tapY})"
        )
        TouchEffectOverlay.tap(cx, cy)

        val clickNode = candidate.clickableAncestor ?: candidate.node.takeIf { it.isClickable && !it.isEditable }
        if (clickNode != null) {
            if (clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }

        val path = Path().apply { moveTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 75L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val metrics = resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(720)
        val screenH = metrics.heightPixels.coerceAtLeast(1280)
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() <= 4 || rect.height() <= 4) return false

        val label = node.text?.toString()?.trim().orEmpty()
            .ifBlank { node.contentDescription?.toString()?.trim().orEmpty() }
            .ifBlank { node.viewIdResourceName?.substringAfterLast('/').orEmpty() }
            .ifBlank { "target" }

        val candidate = ScoredClickCandidate(
            node = node,
            clickableAncestor = resolveTightClickableAncestor(node, screenW, screenH),
            score = 100,
            tapX = rect.centerX(),
            tapY = rect.centerY(),
            matchedLabel = label.take(40)
        )
        return executeVerifiedSingleClick(candidate)
    }

    private fun performScrollOnNode(root: AccessibilityNodeInfo, forward: Boolean): Boolean {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur.isScrollable) {
                if (cur.performAction(action)) return true
            }
            for (i in 0 until cur.childCount) {
                cur.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        AccessibilityAutomationBus.setServiceConnected(false, null)
        super.onDestroy()
    }
}
