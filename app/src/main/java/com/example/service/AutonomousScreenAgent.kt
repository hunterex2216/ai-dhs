package com.example.service

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AutonomousAgentDecision(
    val thought: String = "",
    val status: String = "CONTINUE", // CONTINUE, DONE, READY_FOR_CONFIRMATION, NEED_USER_CHOICE, FAILED
    val action: String = "NONE",     // TAP_NODE, TYPE_TEXT, TYPE_AND_SEND, SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT, OPEN_APP, BACK, HOME, SEARCH_INTERNET, WAIT, NONE
    val nodeIndex: Int = -1,
    val sendNodeIndex: Int = -1,
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val submitEnter: Boolean = false,
    val nextAction: String = "",     // Optional chained follow-up in same turn (e.g. TYPE_TEXT after TAP_NODE on Search)
    val nextNodeIndex: Int = -1,
    val nextText: String = "",
    val nextSubmitEnter: Boolean = false,
    val appName: String = "",
    val replyBn: String = ""
)

data class AutonomousAgentResult(
    val success: Boolean,
    val needsUserConfirmation: Boolean = false,
    val needsUserChoice: Boolean = false,
    val summaryBn: String,
    val stepsTaken: List<String> = emptyList(),
    val finalPackage: String = ""
)

/**
 * High-Speed, Self-Learning, Internet-Aware Autonomous Screen Agent
 * (Self-Learn App -> Observe -> Zero-LLM Fast-Path / Vision+Tree Brain -> Batched Act -> Verify).
 *
 * - Learns unfamiliar apps automatically on first sight and persists control maps in AppKnowledgeRegistry.
 * - Executes open-chat deictic sends ("একে", "ওকে") and unambiguous visible chats in ~1s without redundant LLM hops.
 * - Supports batched multi-step actions (TAP search + TYPE name, TYPE message + TAP Send) in a single turn.
 * - Can fetch live internet information mid-task when a goal needs current facts, prices, weather, or URLs.
 */
object AutonomousScreenAgent {

    private val FAST_VISION_MODELS = listOf(
        "models/gemini-2.5-flash",
        "models/gemini-2.5-pro",
        "models/gemini-3-flash-preview",
        "models/gemini-3.1-pro-preview"
    )

    private val DEICTIC_TERMS = setOf(
        "একে", "ওকে", "তাকে", "এনাকে", "উনাকে", "ইনাকে", "এইজনকে", "এই জনকে",
        "এই আইডি", "এই আইডিতে", "এই চ্যাট", "এই চ্যাটে", "এখানে", "এটাতে", "এটায়",
        "him", "her", "them", "this person", "this user", "this id", "this chat",
        "current chat", "current user", "here", "same person"
    )

    fun isDeicticReference(raw: String): Boolean {
        val clean = raw.trim().lowercase()
            .replace(Regex("^(to|for|@)\\s+"), "")
            .trim()
        if (clean.isBlank()) return false
        if (clean in DEICTIC_TERMS) return true
        return DEICTIC_TERMS.any { term ->
            clean == "${term}কে" || clean == "${term}র" || clean == "${term}তে"
        }
    }

    fun appPackageMatchesHint(currentPkg: String, appHint: String): Boolean {
        val pkg = currentPkg.lowercase().trim()
        val hint = appHint.lowercase().trim()
        if (pkg.isBlank() || hint.isBlank() || hint == "current_screen" || hint == "current") return false
        return when {
            hint.contains("whatsapp") || hint.contains("হোয়াটসঅ্যাপ") || hint.contains("হোয়াটসঅ্যাপ") ->
                pkg.contains("whatsapp")
            hint.contains("messenger") || hint.contains("মেসেঞ্জার") ->
                pkg == "com.facebook.orca" || pkg.contains("orca")
            hint.contains("facebook") || hint.contains("ফেসবুক") ->
                pkg.contains("facebook")
            hint.contains("telegram") || hint.contains("টেলিগ্রাম") ->
                pkg.contains("telegram")
            hint.contains("instagram") || hint.contains("ইনস্টাগ্রাম") ->
                pkg.contains("instagram")
            hint.contains("sms") || hint.contains("message") || hint.contains("মেসেজ") ->
                pkg.contains("messaging") || pkg.contains("mms") || pkg.contains("sms")
            hint.contains("youtube") || hint.contains("ইউটিউব") ->
                pkg.contains("youtube")
            hint.contains("play") || hint.contains("প্লে স্টোর") ->
                pkg == "com.android.vending"
            hint.contains("chrome") || hint.contains("ক্রোম") ->
                pkg.contains("chrome")
            else -> pkg.contains(hint)
        }
    }

    suspend fun runTask(
        context: Context,
        apiKey: String,
        goal: String,
        appHint: String = "",
        recipientHint: String = "",
        messageHint: String = "",
        stopBeforeFinalSend: Boolean = false,
        maxSteps: Int = 12,
        onProgress: (String) -> Unit = {}
    ): AutonomousAgentResult {
        AppKnowledgeRegistry.init(context)
        val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)
        val svc = AccessibilityAutomationBus.serviceInstance
        if (!isAccEnabled || svc == null) {
            withContext(Dispatchers.Main) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
            return AutonomousAgentResult(
                success = false,
                summaryBn = "স্ক্রিন দেখে কাজ করার জন্য Accessibility সার্ভিসটা চালু করা দরকার — সেটিংস খুলে দিয়েছি, অন করে দিলেই কাজ শুরু করব।"
            )
        }

        if (apiKey.isBlank()) {
            return AutonomousAgentResult(
                success = false,
                summaryBn = "কাজটা করতে Gemini API Key প্রয়োজন—দয়া করে চাবি আইকনে কী-টি সেট করে দিন।"
            )
        }

        val cleanGoal = goal.trim()
        val rawRec = recipientHint.trim()
        val isDeictic = isDeicticReference(rawRec) || (rawRec.isBlank() && isDeicticReferenceInGoal(cleanGoal))
        val effectiveRecipient = if (isDeictic) "" else rawRec
        var cleanMsg = messageHint.trim()

        // Step 0: Inspect current screen FIRST before deciding whether to switch apps!
        val initialObs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
        val currentPkg = initialObs?.packageName.orEmpty()
        val ownPkg = context.packageName

        val shouldLaunchTargetApp = when {
            appHint.isBlank() || appHint.equals("CURRENT_SCREEN", ignoreCase = true) -> {
                currentPkg == ownPkg && inferAppFromGoal(cleanGoal).isNotBlank()
            }
            appPackageMatchesHint(currentPkg, appHint) -> false // Already in target app! Keep current chat/scroll intact!
            else -> true
        }

        if (shouldLaunchTargetApp) {
            val targetAppToOpen = appHint.ifBlank { inferAppFromGoal(cleanGoal) }
            if (targetAppToOpen.isNotBlank()) {
                onProgress("$targetAppToOpen খুলছি...")
                val launched = withContext(Dispatchers.Main) {
                    launchAppForAgent(context, targetAppToOpen)
                }
                if (launched) {
                    delay(550L)
                }
            }
        }

        // Warm up model list if not cached yet (non-fatal)
        if (GeminiModelResolver.cached == null) {
            withContext(Dispatchers.IO) { GeminiModelResolver.resolve(apiKey) }
        }

        // Pre-fetch live internet info if goal explicitly asks to send/use web info (e.g. weather, score, price, URL)
        val preFetchedWebContext = maybeResolveWebContextForGoal(context, apiKey, cleanGoal, cleanMsg)

        val stepsTaken = mutableListOf<String>()
        var extraWebContext = preFetchedWebContext
        var lastSignature = ""
        var sameStateRepeatCount = 0
        var lastDecisionKey = ""
        val inspectedUnknownPkgs = HashSet<String>()
        var triedFastVisibleContactTap = false

        for (stepIdx in 1..maxSteps) {
            val obs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
            if (obs == null) {
                delay(220L)
                continue
            }

            // Self-learning phase: if this app is unfamiliar, study how it works once and persist blueprint
            if (obs.packageName.isNotBlank() &&
                obs.packageName != ownPkg &&
                !AppKnowledgeRegistry.hasDeepKnowledge(obs.packageName) &&
                inspectedUnknownPkgs.add(obs.packageName)
            ) {
                onProgress("নতুন অ্যাপটির ডিজাইন বুঝে নিচ্ছি...")
                val previewBmp = withContext(Dispatchers.Main) { svc.captureRealScreenBitmapForAgent() }
                val learned = withContext(Dispatchers.IO) {
                    discoverUnknownAppBlueprint(apiKey, obs, previewBmp)
                }
                if (!learned.isNullOrBlank()) {
                    AppKnowledgeRegistry.saveLearnedAppBlueprint(context, obs.packageName, "AI-Learned: $learned")
                }
            }

            // ZERO-LLM FAST PATH #1: Already inside the right chat with bottom composer visible!
            if (cleanMsg.isNotBlank() && preFetchedWebContext.isBlank()) {
                val fastOpenRes = tryInstantOpenChatSend(
                    context = context,
                    svc = svc,
                    obs = obs,
                    cleanMsg = cleanMsg,
                    effectiveRecipient = effectiveRecipient,
                    isDeictic = isDeictic,
                    stopBeforeFinalSend = stopBeforeFinalSend,
                    stepsTaken = stepsTaken
                )
                if (fastOpenRes != null) return fastOpenRes
            }

            // ZERO-LLM FAST PATH #2: Contact row is unambiguously visible right on screen in [BODY]!
            if (!triedFastVisibleContactTap &&
                !isDeictic &&
                effectiveRecipient.isNotBlank() &&
                findComposerNode(obs) == null
            ) {
                val matchingRow = findSingleMatchingContactRowInBody(obs, effectiveRecipient)
                if (matchingRow != null) {
                    triedFastVisibleContactTap = true
                    onProgress("${matchingRow.bestLabel.ifBlank { effectiveRecipient }} দেখা যাচ্ছে, চ্যাটে ঢুকছি...")
                    val tapped = withContext(Dispatchers.Main) {
                        svc.performAgentTap(matchingRow.cx, matchingRow.cy, matchingRow.bestLabel)
                    }
                    if (tapped) {
                        stepsTaken.add("FastPath: Tapped visible contact '${matchingRow.bestLabel}' @(${matchingRow.cx},${matchingRow.cy})")
                        delay(320L)
                        continue
                    }
                }
            }

            val sig = obs.signature()
            val screenChanged = sig != lastSignature
            val bitmap = withContext(Dispatchers.Main) { svc.captureRealScreenBitmapForAgent() }

            val stuckWarning = if (!screenChanged && stepIdx > 1) {
                sameStateRepeatCount++
                "WARNING: Screen did NOT change after '$lastDecisionKey'! " +
                    "Switch strategy immediately: tap Search icon + type name, SCROLL_DOWN to find hidden contacts/buttons, tap an exact row in [BODY], or press BACK."
            } else {
                sameStateRepeatCount = 0
                ""
            }
            lastSignature = sig

            if (sameStateRepeatCount >= 4) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "স্ক্রিনে কয়েকবার চেষ্টা করেও '$cleanGoal' এর জন্য সঠিক বাটন বা আইডি খুঁজে পাইনি (${obs.packageName})। একটু স্ক্রিনটা ঠিক জায়গায় এনে আবার বলুন তো।",
                    stepsTaken = stepsTaken,
                    finalPackage = obs.packageName
                )
            }

            val prompt = buildBrainPrompt(
                goal = cleanGoal,
                appHint = appHint,
                recipientHint = effectiveRecipient,
                isDeictic = isDeictic,
                messageHint = cleanMsg,
                stopBeforeFinalSend = stopBeforeFinalSend,
                stepIdx = stepIdx,
                maxSteps = maxSteps,
                obs = obs,
                appKnowledge = AppKnowledgeRegistry.guideForPackage(obs.packageName),
                webContext = extraWebContext,
                history = stepsTaken,
                stuckWarning = stuckWarning,
                installedAppsHint = InstalledAppsIndex.summary(context, 45)
            )

            val rawJson = withContext(Dispatchers.IO) {
                callVisionAgentBrain(apiKey, prompt, bitmap)
            }
            if (rawJson.isNullOrBlank()) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "এখন AI ব্রেইন থেকে রেসপন্স আসেনি—নেট কানেকশন বা Gemini API Key টা একটু দেখে নিন।",
                    stepsTaken = stepsTaken,
                    finalPackage = obs.packageName
                )
            }

            val decision = parseDecisionJson(rawJson)
            val actionUpper = decision.action.uppercase().trim()
            val statusUpper = decision.status.uppercase().trim()

            onProgress(decision.replyBn.ifBlank { decision.thought }.ifBlank { "কাজ করছি..." })

            when (statusUpper) {
                "DONE" -> {
                    return AutonomousAgentResult(
                        success = true,
                        summaryBn = decision.replyBn.ifBlank { "কাজটি হয়ে গেছে!" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "NEED_USER_CHOICE" -> {
                    return AutonomousAgentResult(
                        success = false,
                        needsUserChoice = true,
                        summaryBn = decision.replyBn.ifBlank { "এখানে কয়েকজনকে দেখা যাচ্ছে—ঠিক কাকে পাঠাব একটু বলে দিন?" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "FAILED" -> {
                    return AutonomousAgentResult(
                        success = false,
                        summaryBn = decision.replyBn.ifBlank { "এই স্ক্রিনে খুঁজে দেখলাম, কিন্তু টার্গেট আইডি বা অপশনটা পেলাম না।" },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "READY_FOR_CONFIRMATION" -> {
                    val targetNode = obs.nodes.getOrNull(decision.nodeIndex) ?: findSendNode(obs)
                    val tx = (targetNode?.cx ?: decision.x).takeIf { it > 0 } ?: (obs.screenWidth * 0.88f).toInt()
                    val ty = (targetNode?.cy ?: decision.y).takeIf { it > 0 } ?: (obs.screenHeight * 0.90f).toInt()
                    val lbl = targetNode?.bestLabel?.ifBlank { "Send" } ?: "Send"
                    val bounds = targetNode?.bounds ?: Rect(tx - 60, ty - 45, tx + 60, ty + 45)
                    ConfirmationGateBus.lockTargetAndHighlight(
                        ctx = context,
                        buttonLabel = lbl,
                        x = tx.toFloat(),
                        y = ty.toFloat(),
                        bounds = bounds,
                        appPackageOrName = obs.packageName,
                        typedTextPreview = cleanMsg
                    )
                    return AutonomousAgentResult(
                        success = true,
                        needsUserConfirmation = true,
                        summaryBn = decision.replyBn.ifBlank {
                            "সব রেডি! '$lbl' বাটনে লক করে দিয়েছি—এখন কি পাঠিয়ে দেব?"
                        },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
            }

            // Mid-task live internet search tool if Brain requested web facts!
            if (actionUpper == "SEARCH_INTERNET") {
                val q = decision.text.ifBlank { cleanGoal }
                onProgress("ইন্টারনেট থেকে তথ্য খুঁজে নিচ্ছি...")
                val webRes = AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
                    context = context,
                    apiKey = apiKey,
                    query = q,
                    useMapsGrounding = false
                )
                extraWebContext = webRes.take(1400)
                stepsTaken.add("Step $stepIdx [SEARCH_INTERNET '$q'] -> fetched ${extraWebContext.length} chars")
                continue
            }

            // Batched TYPE_AND_SEND: Type into composer + immediately tap Send in ONE Brain roundtrip!
            if (actionUpper == "TYPE_AND_SEND") {
                val composerNode = obs.nodes.getOrNull(decision.nodeIndex) ?: findComposerNode(obs)
                val msgToType = decision.text.ifBlank { cleanMsg }
                val cx = composerNode?.cx ?: decision.x.takeIf { it > 0 } ?: (obs.screenWidth / 2)
                val cy = composerNode?.cy ?: decision.y.takeIf { it > 0 } ?: (obs.screenHeight * 0.88f).toInt()
                if (composerNode != null) {
                    AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", composerNode.viewId, composerNode.bestLabel, cx, cy)
                }
                val typedOk = withContext(Dispatchers.Main) {
                    svc.performAgentTypeText(
                        cx = cx,
                        cy = cy,
                        text = msgToType,
                        labelHint = composerNode?.bestLabel.orEmpty(),
                        submitIme = false
                    )
                }
                stepsTaken.add("Step $stepIdx [TYPE '$msgToType'] -> $typedOk")
                delay(160L)

                val obsAfterType = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() } ?: obs
                val sendNode = obsAfterType.nodes.getOrNull(decision.sendNodeIndex)?.takeIf {
                    isSendLikeNode(it, obsAfterType.screenWidth, obsAfterType.screenHeight)
                } ?: findSendNode(obsAfterType)

                if (stopBeforeFinalSend) {
                    val sx = (sendNode?.cx ?: (obsAfterType.screenWidth * 0.90f).toInt())
                    val sy = (sendNode?.cy ?: cy)
                    val sLbl = sendNode?.bestLabel?.ifBlank { "Send" } ?: "Send"
                    ConfirmationGateBus.lockTargetAndHighlight(
                        ctx = context,
                        buttonLabel = sLbl,
                        x = sx.toFloat(),
                        y = sy.toFloat(),
                        bounds = sendNode?.bounds ?: Rect(sx - 55, sy - 45, sx + 55, sy + 45),
                        appPackageOrName = obsAfterType.packageName,
                        typedTextPreview = msgToType
                    )
                    return AutonomousAgentResult(
                        success = true,
                        needsUserConfirmation = true,
                        summaryBn = "মেসেজ লেখা হয়ে গেছে ও Send বাটন লক করে দিয়েছি—এখন কি পাঠাব?",
                        stepsTaken = stepsTaken,
                        finalPackage = obsAfterType.packageName
                    )
                }

                if (sendNode != null) {
                    AppKnowledgeRegistry.recordLearnedRole(context, obsAfterType.packageName, "SEND", sendNode.viewId, sendNode.bestLabel, sendNode.cx, sendNode.cy)
                    val sentOk = withContext(Dispatchers.Main) {
                        svc.performAgentTap(sendNode.cx, sendNode.cy, sendNode.bestLabel)
                    }
                    stepsTaken.add("Step $stepIdx [TAP SEND '${sendNode.bestLabel}'] -> $sentOk")
                    delay(240L)
                    val obsAfterSend = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
                    val composerAfter = obsAfterSend?.let { findComposerNode(it) }
                    val cleared = composerAfter == null ||
                        composerAfter.text.isBlank() ||
                        composerAfter.text.equals(composerAfter.hintText, ignoreCase = true) ||
                        !composerAfter.text.contains(msgToType.take(12), ignoreCase = true)
                    if (sentOk && cleared) {
                        return AutonomousAgentResult(
                            success = true,
                            summaryBn = decision.replyBn.ifBlank { "হয়ে গেছে! \"$msgToType\" মেসেজটা পাঠিয়ে দিয়েছি।" },
                            stepsTaken = stepsTaken,
                            finalPackage = obsAfterSend?.packageName ?: obsAfterType.packageName
                        )
                    }
                }
                continue
            }

            // Standard single or 2-step chained UI action
            val targetNode = obs.nodes.getOrNull(decision.nodeIndex)
            val cx = targetNode?.cx ?: decision.x
            val cy = targetNode?.cy ?: decision.y
            val nodeLabel = targetNode?.bestLabel.orEmpty()
            lastDecisionKey = "$actionUpper#${decision.nodeIndex}@$cx,$cy:${decision.text.take(20)}"

            if (targetNode != null) {
                val idLow = targetNode.viewId.lowercase()
                val lblLow = nodeLabel.lowercase()
                when {
                    idLow.contains("search") || lblLow.contains("search") || lblLow.contains("সার্চ") ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "SEARCH", targetNode.viewId, nodeLabel, cx, cy)
                    targetNode.editable ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", targetNode.viewId, nodeLabel, cx, cy)
                    isSendLikeNode(targetNode, obs.screenWidth, obs.screenHeight) ->
                        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "SEND", targetNode.viewId, nodeLabel, cx, cy)
                }
            }

            val execOk = executeAtomicUiAction(context, svc, obs, actionUpper, targetNode, cx, cy, decision.text, nodeLabel, decision.submitEnter)
            stepsTaken.add(
                "Step $stepIdx [$actionUpper${if (nodeLabel.isNotBlank()) " '$nodeLabel'" else ""}${if (decision.text.isNotBlank()) " text='${decision.text}'" else ""}] -> ${if (execOk) "ok" else "failed"}"
            )

            // Execute optional chained next_action in the SAME Brain roundtrip (e.g. TAP Search + TYPE contact name!)
            val nextUpper = decision.nextAction.uppercase().trim()
            if (execOk && (nextUpper == "TYPE_TEXT" || nextUpper == "TYPE" || nextUpper == "TAP_NODE" || nextUpper == "TAP")) {
                delay(280L)
                val midObs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
                if (midObs != null) {
                    if (nextUpper == "TYPE_TEXT" || nextUpper == "TYPE") {
                        val editTarget = midObs.nodes.getOrNull(decision.nextNodeIndex)?.takeIf { it.editable }
                            ?: midObs.nodes.firstOrNull { it.editable && it.focused }
                            ?: midObs.nodes.firstOrNull { it.editable }
                        if (editTarget != null) {
                            val typeOk2 = withContext(Dispatchers.Main) {
                                svc.performAgentTypeText(
                                    cx = editTarget.cx,
                                    cy = editTarget.cy,
                                    text = decision.nextText.ifBlank { effectiveRecipient },
                                    labelHint = editTarget.bestLabel,
                                    submitIme = decision.nextSubmitEnter
                                )
                            }
                            stepsTaken.add("Step ${stepIdx}b [CHAINED TYPE '${decision.nextText}'] -> $typeOk2")
                            delay(if (decision.nextSubmitEnter) 460L else 260L)
                            continue
                        }
                    } else if (nextUpper == "TAP_NODE" || nextUpper == "TAP") {
                        val tapTarget = midObs.nodes.getOrNull(decision.nextNodeIndex)
                        if (tapTarget != null) {
                            withContext(Dispatchers.Main) {
                                svc.performAgentTap(tapTarget.cx, tapTarget.cy, tapTarget.bestLabel)
                            }
                            stepsTaken.add("Step ${stepIdx}b [CHAINED TAP '${tapTarget.bestLabel}']")
                            delay(240L)
                            continue
                        }
                    }
                }
            }

            // Snappy settle wait before next observe
            val settleMs = when (actionUpper) {
                "OPEN_APP" -> 520L
                "SCROLL_DOWN", "SCROLL_UP", "SCROLL_LEFT", "SCROLL_RIGHT" -> 320L
                "TYPE_TEXT", "TYPE" -> if (decision.submitEnter) 440L else 200L
                "WAIT" -> 650L
                else -> 240L
            }
            delay(settleMs)
        }

        val finalPkg = withContext(Dispatchers.Main) { svc.captureRichScreenObservation()?.packageName.orEmpty() }
        return AutonomousAgentResult(
            success = false,
            summaryBn = "বেশ কয়েকবার চেষ্টা করলাম, কিন্তু কাজটা পুরো শেষ করতে পারিনি। স্ক্রিনে আরেকটু সামনে আনলে এখনই করে দিচ্ছি।",
            stepsTaken = stepsTaken,
            finalPackage = finalPkg
        )
    }

    private suspend fun executeAtomicUiAction(
        context: Context,
        svc: CubeAccessibilityService,
        obs: RichScreenObservation,
        actionUpper: String,
        targetNode: RichUiNode?,
        cx: Int,
        cy: Int,
        text: String,
        nodeLabel: String,
        submitEnter: Boolean
    ): Boolean = when (actionUpper) {
        "TAP_NODE", "TAP", "CLICK" -> {
            if (cx > 0 && cy > 0) {
                withContext(Dispatchers.Main) { svc.performAgentTap(cx, cy, nodeLabel) }
            } else false
        }
        "TYPE_TEXT", "TYPE" -> {
            val tx = if (cx > 0) cx else (obs.screenWidth / 2)
            val ty = if (cy > 0) cy else (obs.screenHeight * 0.85f).toInt()
            withContext(Dispatchers.Main) {
                svc.performAgentTypeText(
                    cx = tx,
                    cy = ty,
                    text = text,
                    labelHint = targetNode?.bestLabel ?: nodeLabel,
                    submitIme = submitEnter
                )
            }
        }
        "SCROLL_DOWN" -> withContext(Dispatchers.Main) { svc.performAgentScroll("DOWN") }
        "SCROLL_UP" -> withContext(Dispatchers.Main) { svc.performAgentScroll("UP") }
        "SCROLL_LEFT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("LEFT") }
        "SCROLL_RIGHT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("RIGHT") }
        "OPEN_APP" -> withContext(Dispatchers.Main) { launchAppForAgent(context, text) }
        "BACK" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("BACK") }
        "HOME" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("HOME") }
        "WAIT" -> true
        else -> false
    }

    /**
     * Zero-LLM Instant Open-Chat Send Fast-Path:
     * Executes in ~0.8-1.5s whenever the target chat composer is ALREADY open on screen!
     */
    private suspend fun tryInstantOpenChatSend(
        context: Context,
        svc: CubeAccessibilityService,
        obs: RichScreenObservation,
        cleanMsg: String,
        effectiveRecipient: String,
        isDeictic: Boolean,
        stopBeforeFinalSend: Boolean,
        stepsTaken: MutableList<String>
    ): AutonomousAgentResult? {
        val composer = findComposerNode(obs) ?: return null
        val isOpenChatMatch = isDeictic ||
            effectiveRecipient.isBlank() ||
            headerMatchesRecipient(obs, effectiveRecipient)
        if (!isOpenChatMatch) return null

        AppKnowledgeRegistry.recordLearnedRole(context, obs.packageName, "COMPOSER", composer.viewId, composer.bestLabel, composer.cx, composer.cy)

        val currentText = composer.text.trim()
        val alreadyTyped = currentText.isNotBlank() &&
            !currentText.equals(composer.hintText.trim(), ignoreCase = true) &&
            currentText.contains(cleanMsg, ignoreCase = true)

        if (!alreadyTyped) {
            val typedOk = withContext(Dispatchers.Main) {
                svc.performAgentTypeText(
                    cx = composer.cx,
                    cy = composer.cy,
                    text = cleanMsg,
                    labelHint = composer.bestLabel,
                    submitIme = false
                )
            }
            if (!typedOk) return null
            stepsTaken.add("FastPath: Typed message directly into composer '${composer.bestLabel}'")
            delay(160L)
        }

        val obsAfterType = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() } ?: obs
        val updatedComposer = findComposerNode(obsAfterType)
        val verifiedTyped = updatedComposer?.text.orEmpty().contains(cleanMsg.take(12), ignoreCase = true)
        if (!verifiedTyped) return null // Let Gemini Vision inspect if custom UI rejected text

        val sendNode = findSendNode(obsAfterType) ?: return null
        AppKnowledgeRegistry.recordLearnedRole(context, obsAfterType.packageName, "SEND", sendNode.viewId, sendNode.bestLabel, sendNode.cx, sendNode.cy)

        if (stopBeforeFinalSend) {
            ConfirmationGateBus.lockTargetAndHighlight(
                ctx = context,
                buttonLabel = sendNode.bestLabel.ifBlank { "Send" },
                x = sendNode.cx.toFloat(),
                y = sendNode.cy.toFloat(),
                bounds = sendNode.bounds,
                appPackageOrName = obsAfterType.packageName,
                typedTextPreview = cleanMsg
            )
            return AutonomousAgentResult(
                success = true,
                needsUserConfirmation = true,
                summaryBn = "সব রেডি! মেসেজ লিখে Send বাটনে লক করে দিয়েছি—পাঠিয়ে দেব?",
                stepsTaken = stepsTaken,
                finalPackage = obsAfterType.packageName
            )
        }

        val tappedSend = withContext(Dispatchers.Main) {
            svc.performAgentTap(sendNode.cx, sendNode.cy, sendNode.bestLabel)
        }
        if (!tappedSend) return null
        stepsTaken.add("FastPath: Tapped Send '${sendNode.bestLabel}' @(${sendNode.cx},${sendNode.cy})")
        delay(220L)

        val obsAfterSend = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
        val composerAfter = obsAfterSend?.let { findComposerNode(it) }
        val cleared = composerAfter == null ||
            composerAfter.text.isBlank() ||
            composerAfter.text.equals(composerAfter.hintText, ignoreCase = true) ||
            !composerAfter.text.contains(cleanMsg.take(12), ignoreCase = true)
        if (!cleared) return null

        val targetWho = headerContactName(obs).ifBlank { effectiveRecipient }.ifBlank { "উনাকে" }
        return AutonomousAgentResult(
            success = true,
            summaryBn = "হয়ে গেছে! $targetWho কে \"$cleanMsg\" পাঠিয়ে দিয়েছি।",
            stepsTaken = stepsTaken,
            finalPackage = obsAfterSend?.packageName ?: obsAfterType.packageName
        )
    }

    private fun findComposerNode(obs: RichScreenObservation): RichUiNode? {
        val minComposerY = (obs.screenHeight * 0.40f).toInt()
        return obs.nodes
            .filter { n ->
                n.editable && n.cy >= minComposerY &&
                    !n.viewId.lowercase().contains("search") &&
                    !n.hintText.lowercase().contains("search") &&
                    !n.contentDesc.lowercase().contains("search")
            }
            .sortedByDescending { n ->
                var score = n.cy
                val meta = "${n.viewId} ${n.hintText} ${n.contentDesc}".lowercase()
                if (meta.contains("entry") || meta.contains("composer") || meta.contains("message") || meta.contains("chat_input")) {
                    score += 1000
                }
                score
            }
            .firstOrNull()
    }

    private fun isSendLikeNode(n: RichUiNode, screenWidth: Int, screenHeight: Int): Boolean {
        if (!n.clickable) return false
        val minSendY = (screenHeight * 0.40f).toInt()
        if (n.cy < minSendY) return false
        val idLow = n.viewId.lowercase()
        val lblLow = "${n.text} ${n.contentDesc}".lowercase().trim()
        if (idLow.contains("voice") || idLow.contains("record") || lblLow.contains("voice note") || lblLow.contains("record audio")) {
            return false
        }
        return idLow.contains("send") ||
            lblLow == "send" ||
            lblLow == "পাঠান" ||
            lblLow.startsWith("send ") ||
            (n.cx >= (screenWidth * 0.78f).toInt() && lblLow.contains("send"))
    }

    private fun findSendNode(obs: RichScreenObservation): RichUiNode? =
        obs.nodes.firstOrNull { isSendLikeNode(it, obs.screenWidth, obs.screenHeight) }

    private fun headerMatchesRecipient(obs: RichScreenObservation, recipient: String): Boolean {
        val clean = recipient.trim().lowercase()
        if (clean.length < 2) return false
        return obs.nodes.any { n ->
            n.zone == "HEADER" && !n.editable &&
                !n.viewId.lowercase().contains("search") &&
                (n.text.lowercase().contains(clean) || n.contentDesc.lowercase().contains(clean))
        }
    }

    private fun headerContactName(obs: RichScreenObservation): String {
        val candidate = obs.nodes.firstOrNull { n ->
            n.zone == "HEADER" && !n.editable && !n.viewId.lowercase().contains("search") &&
                n.text.trim().length in 2..36 &&
                !n.text.equals("WhatsApp", ignoreCase = true) &&
                !n.text.equals("Messenger", ignoreCase = true) &&
                !n.text.equals("Telegram", ignoreCase = true) &&
                !n.text.equals("Instagram", ignoreCase = true)
        }
        return candidate?.text?.trim().orEmpty()
    }

    private fun findSingleMatchingContactRowInBody(obs: RichScreenObservation, recipient: String): RichUiNode? {
        val q = recipient.trim().lowercase()
        if (q.length < 2) return null
        val matches = obs.nodes.filter { n ->
            n.zone == "BODY" &&
                n.clickable &&
                !n.editable &&
                (n.text.lowercase().contains(q) || n.contentDesc.lowercase().contains(q))
        }
        // Only fast-tap when there is ONE clear distinct row (avoid guessing when 2+ similar names exist!)
        if (matches.size == 1) return matches.first()
        val exactMatches = matches.filter {
            it.text.trim().equals(q, ignoreCase = true) || it.contentDesc.trim().equals(q, ignoreCase = true)
        }
        return if (exactMatches.size == 1) exactMatches.first() else null
    }

    private suspend fun maybeResolveWebContextForGoal(
        context: Context,
        apiKey: String,
        goal: String,
        messageHint: String
    ): String {
        if (!AgentAbilitySwitchesBus.state.value.backgroundWebSearch) return ""
        val combined = "$goal $messageHint".lowercase()
        val hasUrl = Regex("https?://\\S+").containsMatchIn(combined)
        val asksLiveInfo = hasUrl || listOf(
            "আজকের", "আবহাওয়া", "আবহাওয়া", "খবর", "দাম", "কত টাকা", "ম্যাচ", "স্কোর",
            "weather", "news", "price", "score", "rate", "search online", "ইন্টারনেট"
        ).any { combined.contains(it) }
        if (!asksLiveInfo) return ""
        return AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
            context = context,
            apiKey = apiKey,
            query = goal,
            useMapsGrounding = false
        ).take(1200)
    }

    private fun discoverUnknownAppBlueprint(
        apiKey: String,
        obs: RichScreenObservation,
        bitmap: Bitmap?
    ): String? {
        val discoveryPrompt = buildString {
            appendLine("Analyze this unfamiliar Android app screen concisely in 2-3 sentences:")
            appendLine("1. What app is this and what is its main purpose?")
            appendLine("2. Where are the main Search, Menu/Tabs, Input/Composer, and Primary Action controls?")
            appendLine()
            appendLine(obs.toPromptText().take(2400))
        }
        val resolved = GeminiModelResolver.cached?.brain.orEmpty()
        val ordered = (resolved.filter { it.contains("flash", ignoreCase = true) } +
            listOf("models/gemini-2.5-flash") +
            resolved).distinct()
        val parts = JSONArray().put(JSONObject().put("text", discoveryPrompt))
        if (bitmap != null && !bitmap.isRecycled) {
            appendScaledBitmapPart(parts, bitmap, maxDim = 640, quality = 62)
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.1).put("maxOutputTokens", 220))
        for (model in ordered.take(2)) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 4500
                conn.readTimeout = 10000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                    val text = resp.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        ?.trim()
                    if (!text.isNullOrBlank()) return text
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private fun isDeicticReferenceInGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        return DEICTIC_TERMS.any { lower.contains(it) }
    }

    private fun inferAppFromGoal(goal: String): String {
        val lower = goal.lowercase()
        return when {
            lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") -> "WhatsApp"
            lower.contains("messenger") || lower.contains("মেসেঞ্জার") -> "Messenger"
            lower.contains("telegram") || lower.contains("টেলিগ্রাম") -> "Telegram"
            lower.contains("instagram") || lower.contains("ইনস্টাগ্রাম") -> "Instagram"
            lower.contains("facebook") || lower.contains("ফেসবুক") -> "Facebook"
            lower.contains("youtube") || lower.contains("ইউটিউব") -> "YouTube"
            lower.contains("play store") || lower.contains("প্লে স্টোর") -> "Play Store"
            else -> ""
        }
    }

    private fun launchAppForAgent(context: Context, appName: String): Boolean {
        val lower = appName.trim().lowercase()
        if (lower.isBlank()) return false
        val pm = context.packageManager

        val priorityPkgs = when {
            lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") || lower.contains("হোয়াটসঅ্যাপ") ->
                listOf("com.whatsapp", "com.whatsapp.w4b")
            lower.contains("messenger") || lower.contains("মেসেঞ্জার") ->
                listOf("com.facebook.orca")
            lower.contains("telegram") || lower.contains("টেলিগ্রাম") ->
                listOf("org.telegram.messenger", "org.telegram.messenger.web")
            lower.contains("instagram") || lower.contains("ইনস্টাগ্রাম") ->
                listOf("com.instagram.android")
            lower.contains("facebook") || lower.contains("ফেসবুক") ->
                listOf("com.facebook.katana")
            lower.contains("youtube") || lower.contains("ইউটিউব") ->
                listOf("com.google.android.youtube")
            lower.contains("play") || lower.contains("প্লে স্টোর") ->
                listOf("com.android.vending")
            lower.contains("sms") || lower.contains("messages") ->
                listOf("com.google.android.apps.messaging")
            else -> emptyList()
        }

        for (pkg in priorityPkgs) {
            try {
                val launch = pm.getLaunchIntentForPackage(pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return true
                }
            } catch (_: Exception) {}
        }

        val installed = InstalledAppsIndex.search(context, appName)
        for (app in installed) {
            try {
                val launch = pm.getLaunchIntentForPackage(app.pkg)
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launch)
                    return true
                }
            } catch (_: Exception) {}
        }

        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(appName)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            } catch (_: Exception) { false }
        }
        return false
    }

    private fun buildBrainPrompt(
        goal: String,
        appHint: String,
        recipientHint: String,
        isDeictic: Boolean,
        messageHint: String,
        stopBeforeFinalSend: Boolean,
        stepIdx: Int,
        maxSteps: Int,
        obs: RichScreenObservation,
        appKnowledge: String,
        webContext: String,
        history: List<String>,
        stuckWarning: String,
        installedAppsHint: String
    ): String = buildString {
        appendLine("USER GOAL: $goal")
        if (appHint.isNotBlank()) appendLine("TARGET_APP_HINT: $appHint")
        if (isDeictic) {
            appendLine("RECIPIENT_CONTEXT: Deictic ('একে/ওকে/this person') -> Read the open screen header/profile! Do NOT search 'একে' or 'ওকে'.")
        } else if (recipientHint.isNotBlank()) {
            appendLine("TARGET_RECIPIENT_OR_ID: $recipientHint")
        }
        if (messageHint.isNotBlank()) appendLine("MESSAGE_TO_SEND: $messageHint")
        appendLine("FINAL_SEND_MODE: ${if (stopBeforeFinalSend) "STOP_AND_LOCK_ON_SEND_BUTTON" else "AUTO_COMPLETE (use TYPE_AND_SEND when composer+send or open composer is ready)"}")
        appendLine("STEP: $stepIdx / $maxSteps")
        if (appKnowledge.isNotBlank()) {
            appendLine("APP_KNOWLEDGE_BLUEPRINT: $appKnowledge")
        }
        if (webContext.isNotBlank()) {
            appendLine("LIVE_INTERNET_FACTS: $webContext")
        }
        if (installedAppsHint.isNotBlank()) {
            appendLine("INSTALLED_APPS: $installedAppsHint")
        }
        appendLine()
        appendLine("=== CURRENT LIVE SCREEN UI TREE ===")
        appendLine(obs.toPromptText())
        if (history.isNotEmpty()) {
            appendLine("=== PREVIOUS STEPS TAKEN ===")
            history.takeLast(6).forEach { appendLine("- $it") }
        }
        if (stuckWarning.isNotBlank()) {
            appendLine()
            appendLine(stuckWarning)
        }
    }

    private fun appendScaledBitmapPart(parts: JSONArray, bitmap: Bitmap, maxDim: Int, quality: Int) {
        val bos = ByteArrayOutputStream()
        val w = bitmap.width.coerceAtLeast(1)
        val h = bitmap.height.coerceAtLeast(1)
        val scaled = if (w > maxDim || h > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
        } else bitmap
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        parts.put(
            JSONObject().put(
                "inlineData",
                JSONObject()
                    .put("mimeType", "image/jpeg")
                    .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
            )
        )
    }

    private fun callVisionAgentBrain(apiKey: String, prompt: String, bitmap: Bitmap?): String? {
        val resolved = GeminiModelResolver.cached?.brain.orEmpty()
        // Prioritize fast, confirmed Flash models first to eliminate 404 roundtrip delays
        val ordered = (
            listOf("models/gemini-2.5-flash") +
                resolved.filter { it.contains("flash", ignoreCase = true) } +
                FAST_VISION_MODELS +
                resolved
            ).distinct()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (bitmap != null && !bitmap.isRecycled) {
            appendScaledBitmapPart(parts, bitmap, maxDim = 720, quality = 68)
        }

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT))
                )
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.12)
                    .put("maxOutputTokens", 700)
                    .put("responseMimeType", "application/json")
            )

        for (model in ordered.take(4)) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 20000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode == 200) {
                    val resp = JSONObject(conn.inputStream.bufferedReader().readText())
                    val respParts = resp.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?: continue
                    val sb = StringBuilder()
                    for (i in 0 until respParts.length()) {
                        sb.append(respParts.optJSONObject(i)?.optString("text").orEmpty())
                    }
                    val txt = sb.toString().trim()
                    if (txt.isNotBlank()) return txt
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun parseDecisionJson(raw: String): AutonomousAgentDecision {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonStr = Regex("\\{[\\s\\S]*}").find(cleaned)?.value ?: cleaned
        return try {
            val obj = JSONObject(jsonStr)
            AutonomousAgentDecision(
                thought = obj.optString("thought").trim(),
                status = obj.optString("status", "CONTINUE").trim(),
                action = obj.optString("action", "NONE").trim(),
                nodeIndex = obj.optInt("node_index", -1),
                sendNodeIndex = obj.optInt("send_node_index", -1),
                x = obj.optInt("x", -1),
                y = obj.optInt("y", -1),
                text = obj.optString("text"),
                submitEnter = obj.optBoolean("submit_enter", false),
                nextAction = obj.optString("next_action").trim(),
                nextNodeIndex = obj.optInt("next_node_index", -1),
                nextText = obj.optString("next_text"),
                nextSubmitEnter = obj.optBoolean("next_submit_enter", false),
                appName = obj.optString("app_name").trim(),
                replyBn = obj.optString("reply_bn").trim()
            )
        } catch (_: Exception) {
            AutonomousAgentDecision(
                thought = "JSON parse fallback",
                status = "FAILED",
                action = "NONE",
                replyBn = "স্ক্রিন বিশ্লেষণ করতে একটু সমস্যা হয়েছিল—আরেকবার বলুন তো।"
            )
        }
    }

    private const val SYSTEM_PROMPT = """
You are an ultra-fast, perceptive Autonomous Android UI Agent with a natural human personality.
You inspect BOTH the live screenshot AND structured Accessibility nodes (`[HEADER]`, `[BODY]`, `[BOTTOM]` with `#index`, `text`, `desc`, `hint`, `id`, `@(x,y)`) PLUS `APP_KNOWLEDGE_BLUEPRINT` (what you already learned about how this app works).

ACT FAST USING MINIMAL ROUNDTRIPS:
1. UNDERSTAND APP & SCREEN FIRST:
   - Read `APP_KNOWLEDGE_BLUEPRINT`, `FOREGROUND_APP`, and `[HEADER]` first.
   - Deictic pronoun ("একে", "ওকে", "তাকে", "এই আইডিতে"): if a chat composer in `[BOTTOM]` is open -> use `TYPE_AND_SEND` immediately! If on a user's PROFILE -> tap "Message" (`TYPE` not needed yet). If on a list with no open person -> `status="NEED_USER_CHOICE"`.
2. FAST BATCHED NAVIGATION TO FIND RECIPIENT/ID:
   - If the target ID/contact row is visible in `[BODY]` -> `TAP_NODE` on that row! (Never tap a Search bar label when tapping a contact row).
   - If NOT visible -> combine opening Search AND typing in ONE turn: `action="TAP_NODE"`, `node_index=<search_icon>`, `next_action="TYPE_TEXT"`, `next_text="<recipient name>"`, `next_submit_enter=true`! OR if a search input box is already visible/focused, `TYPE_TEXT` directly with `submit_enter=true`.
   - When user implies scrolling or search doesn't fit -> `SCROLL_DOWN`. If wrong popup/chat opened -> `BACK`.
   - Need live internet info to type or answer? Use `action="SEARCH_INTERNET"` with `text="<search query or URL>"`.
3. COMPOSER vs SEARCH BAR (NEVER CONFUSE):
   - `[HEADER]` / `*search*` input = ONLY for contact names / search queries.
   - `[BOTTOM]` / `entry` / `composer` / `message` editable = ONLY for the clean message text!
4. SINGLE-TURN `TYPE_AND_SEND` (FASTEST SEND):
   - When the `[BOTTOM]` message composer is visible AND `FINAL_SEND_MODE == AUTO_COMPLETE`: use `action="TYPE_AND_SEND"`, `node_index=<composer_index>`, `send_node_index=<send_btn_if_visible_else_-1>`, `text="<clean message>"`, and provide a warm, natural human `reply_bn`!
   - When `FINAL_SEND_MODE == STOP_AND_LOCK_ON_SEND_BUTTON`: if composer does NOT have the message yet -> `TYPE_AND_SEND` (it automatically stops and locks the Neon Ring on Send!); if composer ALREADY has it -> `status="READY_FOR_CONFIRMATION"`, `node_index=<send_btn>`.
   - If message already appears in chat history / composer cleared after Send -> `status="DONE"`.

OUTPUT STRICTLY ONE JSON OBJECT:
{
  "thought": "Short natural note of what is on screen",
  "status": "CONTINUE" | "DONE" | "READY_FOR_CONFIRMATION" | "NEED_USER_CHOICE" | "FAILED",
  "action": "TAP_NODE" | "TYPE_TEXT" | "TYPE_AND_SEND" | "SCROLL_DOWN" | "SCROLL_UP" | "SCROLL_LEFT" | "SCROLL_RIGHT" | "OPEN_APP" | "BACK" | "HOME" | "SEARCH_INTERNET" | "WAIT" | "NONE",
  "node_index": 0,
  "send_node_index": -1,
  "x": -1,
  "y": -1,
  "text": "",
  "submit_enter": false,
  "next_action": "",
  "next_node_index": -1,
  "next_text": "",
  "next_submit_enter": false,
  "app_name": "",
  "reply_bn": "Warm, natural, human-like Bengali response (no robotic 'জি স্যার অটোনোমাস এজেন্ট...' phrasing)"
}
"""
}
