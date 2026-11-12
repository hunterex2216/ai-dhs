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
    val action: String = "NONE",     // TAP_NODE, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP, SCROLL_LEFT, SCROLL_RIGHT, OPEN_APP, BACK, HOME, WAIT, NONE
    val nodeIndex: Int = -1,
    val x: Int = -1,
    val y: Int = -1,
    val text: String = "",
    val submitEnter: Boolean = false,
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
 * Real Closed-Loop Autonomous Screen Agent (Observe -> Reason with Vision + UI Tree -> Act -> Verify).
 *
 * Works across ANY app (WhatsApp, WhatsApp Business, Messenger, Telegram, Instagram, Facebook, SMS,
 * YouTube, Play Store, Chrome, etc.) without resetting the current screen, resolves deictic pronouns
 * ("একে", "ওকে", "এই আইডিতে", "this person") from the open screen header/profile, scrolls down or uses
 * search when a contact/ID is not immediately visible, and verifies every step after acting.
 */
object AutonomousScreenAgent {

    private val FAST_VISION_MODELS = listOf(
        "models/gemini-2.5-flash",
        "models/gemini-3-flash-preview",
        "models/gemini-2.5-pro",
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
        maxSteps: Int = 14,
        onProgress: (String) -> Unit = {}
    ): AutonomousAgentResult {
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
                summaryBn = "স্ক্রিন দেখে কাজ করার জন্য Accessibility সার্ভিস অন করা প্রয়োজন — সেটিংস পেজ খুলে দিয়েছি স্যার, অন করে দিন।"
            )
        }

        if (apiKey.isBlank()) {
            return AutonomousAgentResult(
                success = false,
                summaryBn = "অটোনোমাস স্ক্রিন এজেন্ট চালানোর জন্য Gemini API Key প্রয়োজন।"
            )
        }

        val cleanGoal = goal.trim()
        val rawRec = recipientHint.trim()
        val isDeictic = isDeicticReference(rawRec) || (rawRec.isBlank() && isDeicticReferenceInGoal(cleanGoal))
        val effectiveRecipient = if (isDeictic) "" else rawRec
        val cleanMsg = messageHint.trim()

        // Step 0: Inspect current screen FIRST before deciding whether to switch apps!
        val initialObs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
        val currentPkg = initialObs?.packageName.orEmpty()
        val ownPkg = context.packageName

        val shouldLaunchTargetApp = when {
            appHint.isBlank() || appHint.equals("CURRENT_SCREEN", ignoreCase = true) -> {
                // Only leave our own app if user named a messaging app inside goal
                currentPkg == ownPkg && inferAppFromGoal(cleanGoal).isNotBlank()
            }
            appPackageMatchesHint(currentPkg, appHint) -> false // Already in target app! Never reset chat/scroll state!
            else -> true
        }

        if (shouldLaunchTargetApp) {
            val targetAppToOpen = appHint.ifBlank { inferAppFromGoal(cleanGoal) }
            if (targetAppToOpen.isNotBlank()) {
                onProgress("স্ক্রিন এজেন্ট: $targetAppToOpen ওপেন করা হচ্ছে...")
                val launched = withContext(Dispatchers.Main) {
                    launchAppForAgent(context, targetAppToOpen)
                }
                if (launched) {
                    delay(950L)
                }
            }
        }

        val stepsTaken = mutableListOf<String>()
        var lastSignature = ""
        var sameStateRepeatCount = 0
        var lastDecisionKey = ""

        for (stepIdx in 1..maxSteps) {
            val obs = withContext(Dispatchers.Main) { svc.captureRichScreenObservation() }
            if (obs == null) {
                delay(350L)
                continue
            }

            val sig = obs.signature()
            val screenChanged = sig != lastSignature
            val bitmap = withContext(Dispatchers.Main) { svc.captureRealScreenBitmapForAgent() }

            val stuckWarning = if (!screenChanged && stepIdx > 1) {
                sameStateRepeatCount++
                "WARNING: Screen did NOT change after the previous action ($lastDecisionKey)! " +
                    "Do NOT repeat the exact same tap. Choose a different strategy: use Search icon/bar, SCROLL_DOWN to reveal hidden items, tap the correct clickable row, or press BACK if stuck."
            } else {
                sameStateRepeatCount = 0
                ""
            }
            lastSignature = sig

            if (sameStateRepeatCount >= 4) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "স্যার, আমি '$cleanGoal' কাজটি স্ক্রিনে ৪ বার চেষ্টা করেও এগোতে পারিনি (${obs.packageName})। স্ক্রিনে টার্গেট বাটন বা আইডিটি খুঁজে পাওয়া যায়নি।",
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
                history = stepsTaken,
                stuckWarning = stuckWarning,
                installedAppsHint = InstalledAppsIndex.summary(context, 60)
            )

            val rawJson = withContext(Dispatchers.IO) {
                callVisionAgentBrain(apiKey, prompt, bitmap)
            }
            if (rawJson.isNullOrBlank()) {
                return AutonomousAgentResult(
                    success = false,
                    summaryBn = "স্ক্রিন বিশ্লেষণ ব্রেইন থেকে সাড়া পাওয়া যায়নি—ইন্টারনেট বা Gemini API Key চেক করুন।",
                    stepsTaken = stepsTaken,
                    finalPackage = obs.packageName
                )
            }

            val decision = parseDecisionJson(rawJson)
            val actionUpper = decision.action.uppercase().trim()
            val statusUpper = decision.status.uppercase().trim()

            onProgress(decision.thought.ifBlank { "ধাপ $stepIdx: $actionUpper" })

            // Terminal statuses before/without action
            when (statusUpper) {
                "DONE" -> {
                    val reply = decision.replyBn.ifBlank {
                        "জি স্যার, স্ক্রিন দেখে কাজটি সম্পূর্ণ করা হয়েছে।"
                    }
                    return AutonomousAgentResult(
                        success = true,
                        summaryBn = reply,
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "NEED_USER_CHOICE" -> {
                    return AutonomousAgentResult(
                        success = false,
                        needsUserChoice = true,
                        summaryBn = decision.replyBn.ifBlank {
                            "স্যার, স্ক্রিনে একাধিক অপশন দেখছি—কোনটিতে যাব একটু বলুন।"
                        },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "FAILED" -> {
                    return AutonomousAgentResult(
                        success = false,
                        summaryBn = decision.replyBn.ifBlank {
                            "স্যার, স্ক্রিন চেক করে টার্গেট আইডি বা অপশনটি খুঁজে পাওয়া যায়নি।"
                        },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
                "READY_FOR_CONFIRMATION" -> {
                    val targetNode = obs.nodes.getOrNull(decision.nodeIndex)
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
                            "বস, '$lbl' বাটন লক করে দিয়েছি এবং মেসেজ টাইপ করা আছে—এখন কি পাঠাব?"
                        },
                        stepsTaken = stepsTaken,
                        finalPackage = obs.packageName
                    )
                }
            }

            // Execute chosen UI action
            val targetNode = obs.nodes.getOrNull(decision.nodeIndex)
            val cx = targetNode?.cx ?: decision.x
            val cy = targetNode?.cy ?: decision.y
            val nodeLabel = targetNode?.bestLabel.orEmpty()
            lastDecisionKey = "$actionUpper#${decision.nodeIndex}@$cx,$cy:${decision.text.take(20)}"

            val execOk = when (actionUpper) {
                "TAP_NODE", "TAP", "CLICK" -> {
                    if (cx > 0 && cy > 0) {
                        withContext(Dispatchers.Main) {
                            svc.performAgentTap(cx, cy, nodeLabel)
                        }
                    } else false
                }
                "TYPE_TEXT", "TYPE" -> {
                    val tx = if (cx > 0) cx else (obs.screenWidth / 2)
                    val ty = if (cy > 0) cy else (obs.screenHeight * 0.85f).toInt()
                    withContext(Dispatchers.Main) {
                        svc.performAgentTypeText(
                            cx = tx,
                            cy = ty,
                            text = decision.text,
                            labelHint = nodeLabel,
                            submitIme = decision.submitEnter
                        )
                    }
                }
                "SCROLL_DOWN" -> withContext(Dispatchers.Main) { svc.performAgentScroll("DOWN") }
                "SCROLL_UP" -> withContext(Dispatchers.Main) { svc.performAgentScroll("UP") }
                "SCROLL_LEFT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("LEFT") }
                "SCROLL_RIGHT" -> withContext(Dispatchers.Main) { svc.performAgentScroll("RIGHT") }
                "OPEN_APP" -> {
                    val appToOpen = decision.appName.ifBlank { decision.text }
                    withContext(Dispatchers.Main) { launchAppForAgent(context, appToOpen) }
                }
                "BACK" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("BACK") }
                "HOME" -> withContext(Dispatchers.Main) { svc.performAgentGlobalAction("HOME") }
                "WAIT" -> true
                else -> false
            }

            stepsTaken.add(
                "Step $stepIdx [$actionUpper${if (nodeLabel.isNotBlank()) " '$nodeLabel'" else ""}${if (decision.text.isNotBlank()) " text='${decision.text}'" else ""}] -> ${if (execOk) "ok" else "failed"} (${decision.thought.take(70)})"
            )

            // Wait for UI animation / network search / message delivery to settle before re-observing
            val settleMs = when (actionUpper) {
                "OPEN_APP" -> 950L
                "SCROLL_DOWN", "SCROLL_UP", "SCROLL_LEFT", "SCROLL_RIGHT" -> 580L
                "TYPE_TEXT", "TYPE" -> 420L
                "WAIT" -> 900L
                else -> 520L
            }
            delay(settleMs)
        }

        val finalPkg = withContext(Dispatchers.Main) { svc.captureRichScreenObservation()?.packageName.orEmpty() }
        return AutonomousAgentResult(
            success = false,
            summaryBn = "স্যার, সর্বোচ্চ ধাপ ($maxSteps) চেষ্টা করার পরও কাজটি সম্পূর্ণ শেষ হয়নি। অনুগ্রহ করে স্ক্রিন দেখে আবার বলুন।",
            stepsTaken = stepsTaken,
            finalPackage = finalPkg
        )
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
        history: List<String>,
        stuckWarning: String,
        installedAppsHint: String
    ): String = buildString {
        appendLine("USER GOAL: $goal")
        if (appHint.isNotBlank()) appendLine("TARGET_APP_HINT: $appHint")
        if (isDeictic) {
            appendLine("RECIPIENT_CONTEXT: User referred deictically ('একে/ওকে/this person/here') -> Look at the CURRENTLY OPEN chat header / profile / active conversation on screen! Do NOT search for the word 'একে' or 'ওকে'.")
        } else if (recipientHint.isNotBlank()) {
            appendLine("TARGET_RECIPIENT_OR_ID: $recipientHint")
        }
        if (messageHint.isNotBlank()) appendLine("MESSAGE_TO_SEND: $messageHint")
        appendLine("FINAL_SEND_MODE: ${if (stopBeforeFinalSend) "STOP_AND_LOCK_ON_SEND_BUTTON (return status='READY_FOR_CONFIRMATION' with node_index of the Send button once message is verified in composer)" else "AUTO_COMPLETE (tap Send button and verify message sent before returning status='DONE')"}")
        appendLine("STEP: $stepIdx / $maxSteps")
        if (installedAppsHint.isNotBlank()) {
            appendLine("INSTALLED_APPS_SAMPLE: $installedAppsHint")
        }
        appendLine()
        appendLine("=== CURRENT LIVE SCREEN UI TREE ===")
        appendLine(obs.toPromptText())
        if (history.isNotEmpty()) {
            appendLine("=== PREVIOUS STEPS TAKEN ===")
            history.takeLast(8).forEach { appendLine("- $it") }
        }
        if (stuckWarning.isNotBlank()) {
            appendLine()
            appendLine(stuckWarning)
        }
    }

    private fun callVisionAgentBrain(apiKey: String, prompt: String, bitmap: Bitmap?): String? {
        val resolved = GeminiModelResolver.cached?.brain.orEmpty()
        // Prefer fast multimodal Flash models first for snappy multi-step mobile control, then Pro fallback
        val ordered = (resolved.filter { it.contains("flash", ignoreCase = true) } +
            FAST_VISION_MODELS +
            resolved).distinct()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        if (bitmap != null && !bitmap.isRecycled) {
            val bos = ByteArrayOutputStream()
            val maxDim = 960
            val w = bitmap.width.coerceAtLeast(1)
            val h = bitmap.height.coerceAtLeast(1)
            val scaled = if (w > maxDim || h > maxDim) {
                val ratio = maxDim.toFloat() / maxOf(w, h).toFloat()
                Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt().coerceAtLeast(1), (h * ratio).toInt().coerceAtLeast(1), true)
            } else bitmap
            scaled.compress(Bitmap.CompressFormat.JPEG, 76, bos)
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                )
            )
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
                    .put("temperature", 0.15)
                    .put("maxOutputTokens", 900)
                    .put("responseMimeType", "application/json")
            )

        for (model in ordered.take(5)) {
            try {
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$apiKey")
                    .openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 6000
                conn.readTimeout = 28000
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
                x = obj.optInt("x", -1),
                y = obj.optInt("y", -1),
                text = obj.optString("text"),
                submitEnter = obj.optBoolean("submit_enter", false),
                appName = obj.optString("app_name").trim(),
                replyBn = obj.optString("reply_bn").trim()
            )
        } catch (_: Exception) {
            AutonomousAgentDecision(
                thought = "JSON parse fallback",
                status = "FAILED",
                action = "NONE",
                replyBn = "স্ক্রিন সিদ্ধান্ত পার্স করতে সমস্যা হয়েছে—আবার চেষ্টা করুন।"
            )
        }
    }

    private const val SYSTEM_PROMPT = """
You are an elite, real-time Autonomous Android UI Agent (Vision + Accessibility UI Tree).
You see the live screenshot AND the exact structured UI tree (`[HEADER]`, `[BODY]`, `[BOTTOM]` with `#index`, `text`, `desc`, `hint`, `id`, `@(x,y)`, and flags `[clickable,editable,scrollable]`).

Your job: inspect the current screen and decide the SINGLE best next step to achieve the USER GOAL accurately and safely.

CRITICAL DECISION RULES:
1. UNDERSTAND WHERE YOU ARE FIRST:
   - Check `FOREGROUND_APP` and `[HEADER]` nodes to see what app and what person/chat/profile is currently open.
   - If the user said a deictic pronoun ("একে", "ওকে", "তাকে", "এই আইডিতে", "him", "her", "this person") and a chat or profile is ALREADY open on screen:
     * If a `[BOTTOM]` message composer (`[editable]`, e.g., id=`entry`, `message`, hint=`Message`/`মেসেজ`) is visible -> YOU ARE ALREADY IN THEIR CHAT! Type the clean message right there!
     * If you are on a user's PROFILE screen (e.g. Messenger/Instagram/Facebook/WhatsApp profile) -> tap the "Message" / "মেসেজ" button to enter their chat!
     * If you are on the chat list and NO specific person is open or identifiable -> return `status="NEED_USER_CHOICE"` asking politely in Bengali whose ID/chat to message.
2. FINDING A SPECIFIC PERSON / ID / ITEM IN ANY APP:
   - Look in `[BODY]` first: if the target name/ID is visible on screen, `TAP_NODE` on that row! (Do NOT tap a top search bar text that matches the query—tap the result row in `[BODY]`).
   - If NOT visible on the current screen:
     * Decide whether to **SCROLL_DOWN** (if browsing a list or user asked to look below / "নিচে যেতে হবে") OR tap the **Search** icon/bar (`desc="Search"`, `id=*search*`) and `TYPE_TEXT` the person's name/ID with `submit_enter=true`.
     * For long contact/chat lists (WhatsApp, Messenger, Telegram, Instagram), tapping Search and typing the name is usually fastest; if Search doesn't show results or we're already inside a list/profile/feed, `SCROLL_DOWN` (up to 2-3 times) or tap "New chat" / "Contacts" tab!
   - If the wrong chat or an unwanted popup/dialog opened -> use `BACK` (`action="BACK"`).
   - If search returned NO matching contact after checking, return `status="FAILED"` with an honest Bengali `reply_bn`.
   - If multiple distinct people with the exact same name appear and it's genuinely ambiguous, return `status="NEED_USER_CHOICE"` listing their names in `reply_bn`.
3. COMPOSER vs SEARCH BAR DISTINCTION (NEVER CONFUSE THEM):
   - A Search bar is in `[HEADER]` or has `search` in `id`/`hint` -> ONLY type contact names/search queries there.
   - A Chat Message composer is in `[BOTTOM]` or has `entry`/`composer`/`message`/`chat_input` in `id`/`hint` -> ONLY type the clean message body there (NEVER command words like "পাঠাও", "মেসেজ দাও", or recipient name).
4. VERIFYING BEFORE & AFTER SENDING:
   - Before tapping Send, check `[BOTTOM]` composer's current `text`:
     * If the composer does NOT contain the target message yet -> `TYPE_TEXT` into the composer node (`submit_enter=false`).
     * Once the composer ALREADY contains the target message -> check `FINAL_SEND_MODE`:
       - If `STOP_AND_LOCK_ON_SEND_BUTTON`: return `status="READY_FOR_CONFIRMATION"` with `node_index` pointing to the Send button!
       - If `AUTO_COMPLETE`: `TAP_NODE` on the Send button (`id=*send*` or `desc="Send"` / `"পাঠান"`).
   - After tapping Send in the previous step: look at the new screen! If the composer is now empty/cleared OR the sent message appears in the `[BODY]` chat history -> return `status="DONE"` with a clear Bengali `reply_bn`!

OUTPUT STRICTLY ONE JSON OBJECT:
{
  "thought": "Brief reasoning about what is on screen and why this next step is needed",
  "status": "CONTINUE" | "DONE" | "READY_FOR_CONFIRMATION" | "NEED_USER_CHOICE" | "FAILED",
  "action": "TAP_NODE" | "TYPE_TEXT" | "SCROLL_DOWN" | "SCROLL_UP" | "SCROLL_LEFT" | "SCROLL_RIGHT" | "OPEN_APP" | "BACK" | "HOME" | "WAIT" | "NONE",
  "node_index": 0,
  "x": -1,
  "y": -1,
  "text": "",
  "submit_enter": false,
  "app_name": "",
  "reply_bn": "Natural Bengali message for user when DONE / READY_FOR_CONFIRMATION / NEED_USER_CHOICE / FAILED"
}
"""
}
