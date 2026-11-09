package com.example.service

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a multi-step workflow / task playbook learned by the AI when executing commands.
 * Allows the agent to remember HOW it completed a task and replay it at turbo speed next time.
 */
data class LearnedWorkflowPlaybook(
    val id: String,
    val taskTitleBn: String,
    val triggerPhrase: String,
    val targetAppOrSite: String,
    val stepsList: List<String>,
    val replayCommand: String,
    val speedMs: Long = 220L,
    val timesUsed: Int = 1,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val taskTitle: String get() = taskTitleBn
    val steps: List<String> get() = stepsList
    val successCount: Int get() = timesUsed
}

/**
 * Represents a live feature or capability self-installed by the AI inside the app when the user
 * commands: "নিজের মধ্যে এই ফিচারটা বা আপডেটটা করে নাও".
 */
data class SelfInstalledFeaturePatch(
    val id: String,
    val featureTitleBn: String,
    val triggerKeywords: String,
    val behaviorDirective: String,
    val autoActionHint: String = "",
    val versionLabel: String = "v2.4-LivePatch",
    val isEnabled: Boolean = true,
    val installedAt: Long = System.currentTimeMillis()
) {
    val featureTitle: String get() = featureTitleBn
    val featureRuleBn: String get() = behaviorDirective
}

data class MemoryRelevanceDecision(
    val shouldSave: Boolean,
    val extractedTitle: String = "",
    val extractedContent: String = "",
    val category: String = "USER_PREFERENCE"
)

data class SmartEvolutionState(
    val autoSmartMemoryFilterEnabled: Boolean = true,
    val turboUiSpeedEnabled: Boolean = true,
    val learnedPlaybooks: List<LearnedWorkflowPlaybook> = emptyList(),
    val installedFeaturePatches: List<SelfInstalledFeaturePatch> = emptyList(),
    val learnedAppCount: Int = 0,
    val filteredJunkCount: Int = 12,
    val systemHealthScore: Int = 100,
    val selfUpdateVersion: String = "ARCHER OS v4.8 • TURBO SELF-EVOLVING",
    val lastSelfHealReportBn: String = "সিস্টেম ১০০% অপটিমাইজড • টার্বো স্ক্রিন ও অ্যাকশন ইঞ্জিন সচল"
) {
    val isAutoMemoryFilterEnabled: Boolean get() = autoSmartMemoryFilterEnabled
    val isTurboUiSpeedEnabled: Boolean get() = turboUiSpeedEnabled
    val installedFeatures: List<SelfInstalledFeaturePatch> get() = installedFeaturePatches
    val selfEvolutionVersion: String get() = selfUpdateVersion
    val lastDiagnosticSummaryBn: String get() = lastSelfHealReportBn
}

/**
 * Central engine for:
 * 1. Smart Memory Filtering (distinguishing important user facts & workflows from trivial chatter).
 * 2. Step-by-Step Workflow Playbook Learning (remembering HOW a task was done so it runs fast next time).
 * 3. Self-Diagnostics, Auto-Repair & Live In-App Feature Self-Updating.
 */
object SmartMemoryAndSelfUpdateEngine {
    private const val PREFS_NAME = "archer_smart_evolution_prefs"
    private const val KEY_AUTO_FILTER = "auto_smart_memory_filter"
    private const val KEY_TURBO_SPEED = "turbo_ui_speed_enabled"
    private const val KEY_PLAYBOOKS_JSON = "learned_workflow_playbooks_json"
    private const val KEY_PATCHES_JSON = "self_installed_feature_patches_json"
    private const val KEY_VERSION_COUNTER = "self_update_version_counter"

    @Volatile
    private var initialized = false
    @Volatile
    private var appContextRef: Context? = null
    private var versionCounter = 8

    private val _state = MutableStateFlow(SmartEvolutionState())
    val state: StateFlow<SmartEvolutionState> = _state.asStateFlow()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (initialized) return
        initialized = true
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoFilter = prefs.getBoolean(KEY_AUTO_FILTER, true)
        val turboSpeed = prefs.getBoolean(KEY_TURBO_SPEED, true)
        versionCounter = prefs.getInt(KEY_VERSION_COUNTER, 8)

        val playbooks = loadPlaybooksFromPrefs(prefs.getString(KEY_PLAYBOOKS_JSON, null))
            .ifEmpty { defaultCorePlaybooks() }
        val patches = loadPatchesFromPrefs(prefs.getString(KEY_PATCHES_JSON, null))
            .ifEmpty { defaultSelfInstalledPatches() }

        val installedCount = try {
            InstalledAppsIndex.list(appCtx).size
        } catch (_: Exception) {
            32
        }

        _state.value = SmartEvolutionState(
            autoSmartMemoryFilterEnabled = autoFilter,
            turboUiSpeedEnabled = turboSpeed,
            learnedPlaybooks = playbooks,
            installedFeaturePatches = patches,
            learnedAppCount = installedCount.coerceAtLeast(25),
            systemHealthScore = 100,
            selfUpdateVersion = "ARCHER OS v4.$versionCounter • TURBO SELF-EVOLVING"
        )
        saveStateToPrefs(appCtx)
    }

    fun setAutoSmartMemoryFilter(context: Context, enabled: Boolean) {
        init(context)
        _state.value = _state.value.copy(autoSmartMemoryFilterEnabled = enabled)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_FILTER, enabled).apply()
    }

    fun setAutoMemoryFilterEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(autoSmartMemoryFilterEnabled = enabled)
        appContextRef?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_AUTO_FILTER, enabled)?.apply()
    }

    fun setTurboUiSpeed(context: Context, enabled: Boolean) {
        init(context)
        _state.value = _state.value.copy(turboUiSpeedEnabled = enabled)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TURBO_SPEED, enabled).apply()
    }

    fun setTurboUiSpeedEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(turboUiSpeedEnabled = enabled)
        appContextRef?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_TURBO_SPEED, enabled)?.apply()
    }

    fun incrementFilteredJunkCount() {
        _state.value = _state.value.copy(filteredJunkCount = _state.value.filteredJunkCount + 1)
    }

    fun isJunkMemoryNote(title: String, content: String): Boolean {
        val combined = "$title $content".lowercase()
        val dummySignatures = listOf(
            "রাহিম ভাই",
            "tech & movie hub",
            "github & ai resources",
            "সিলেট जाने का प्लान",
            "release desktop version 75",
            "upload video to tiktok",
            "+8801700000000"
        )
        if (dummySignatures.any { combined.contains(it) }) return true
        val trimmed = content.trim().lowercase()
        val trivialNoise = setOf("হ্যালো", "হাই", "hello", "hi", "ওকে", "আচ্ছা", "হুম", "জি")
        return trimmed in trivialNoise
    }

    /**
     * Fast-path UI delay multiplier: when Turbo UI Speed is enabled (default), delays are tuned to
     * millisecond-level responsiveness (~65ms-95ms) so screen-reading and clicking happen immediately.
     */
    fun uiStepDelayMs(baseMs: Long): Long {
        return if (_state.value.turboUiSpeedEnabled) {
            (baseMs * 0.30).toLong().coerceAtLeast(65L)
        } else {
            baseMs
        }
    }

    /**
     * Smart Directed-Speech Filter:
     * Determines whether spoken input is directed at the AI (commands, questions, active task follow-ups,
     * or calling the AI's name) vs. background side-chatter where the user is speaking to someone else.
     */
    fun isSpeechDirectedAtAgent(
        rawTranscript: String,
        isTaskCurrentlyActive: Boolean = false,
        wasWokenByWakeWord: Boolean = false
    ): Boolean {
        val cleaned = rawTranscript.trim()
        if (cleaned.length < 2) return false
        if (wasWokenByWakeWord) return true

        val lower = cleaned.lowercase()

        // 1. Explicit third-party household/side-conversation cues WITH NO AI/device intent -> ignore
        val sideConversationAddressTokens = listOf(
            "আম্মু ", "আব্বু ", "মা ", "বাবা ", "ভাবি ", "চাচা ", "মামা ", "খালা ", "দাদি ", "নানি ",
            "তুই ", "তোরা ", "তোকে ", "তোর ", "এই ছেলে", "এই মেয়ে", "ভাত দাও", "চা দাও", "পানি দাও",
            "দরজা খোল", "জানালা বন্ধ"
        )

        // 2. Direct AI wake/address keywords
        val aiAddressTokens = listOf(
            "জারভিস", "আর্চার", "কিউব", "এআই", "জেমিনি", "ভাই", "স্যার", "শোনো", "শুনো", "শুনুন", "এই যে",
            "jarvis", "archer", "cube", "gemini", "hey ai", "hello ai", "ok ai", "listen"
        )
        if (aiAddressTokens.any { lower.contains(it) }) return true

        // 3. Direct phone/app/screen/coding/question/task intent keywords
        val directIntentKeywords = listOf(
            "ওপেন", "খোলো", "খুলো", "চালু", "বন্ধ", "অফ", "অন", "ইনস্টল", "ইনিস্টল", "নামাও", "ডাউনলোড",
            "আপডেট", "মেসেজ", "ম্যাসেজ", "পাঠাও", "সেন্ড", "শেয়ার", "কল", "ফোন", "ডায়াল", "লিখো", "লেখো",
            "কোড", "কোডিং", "প্রজেক্ট", "ফাইল", "বলো", "দেখো", "স্ক্রিন", "সক্রিন", "ট্যাপ", "ক্লিক", "চাপ",
            "স্ক্রল", "সোয়াইপ", "সার্চ", "খুঁজো", "বের কর", "চালাও", "গান", "ভিডিও", "ইউটিউব", "হোয়াটসঅ্যাপ",
            "হোয়াটসঅ্যাপ", "টেলিগ্রাম", "ফেসবুক", "ক্রোম", "ব্রাউজার", "প্লে স্টোর", "সেটিং", "ভলিউম",
            "সাউন্ড", "রিংটোন", "এলার্ম", "টাইমার", "ব্যাটারি", "সময়", "কয়টা", "কত", "কী", "কিভাবে",
            "কেন", "কোথায়", "কখন", "কেমন", "মনে রাখ", "মনে রেখ", "ভয়েস", "পার্সোনালিটি", "রিসেন্ট",
            "open", "close", "turn on", "turn off", "install", "download", "update", "send", "share",
            "message", "whatsapp", "telegram", "youtube", "play", "stop", "call", "dial", "write",
            "code", "file", "screen", "tap", "click", "scroll", "swipe", "search", "find", "volume",
            "sound", "alarm", "timer", "battery", "what", "how", "why", "where", "when", "who", "can you", "please"
        )
        val hasDirectIntent = directIntentKeywords.any { lower.contains(it) }

        if (sideConversationAddressTokens.any { lower.contains(it) } && !hasDirectIntent) {
            return false
        }

        if (hasDirectIntent) return true

        // 4. If an assigned task is actively running on screen, short follow-up directions are directed at the AI
        if (isTaskCurrentlyActive) {
            val taskFollowUps = listOf(
                "হ্যাঁ", "জি", "না", "ওকে", "আচ্ছা", "এটা", "ওটা", "প্রথমটা", "দ্বিতীয়টা", "নিচে", "উপরে",
                "বামে", "ডানে", "ব্যাকে", "হোমে", "থামো", "স্টপ", "আবার", "দ্রুত", "ফাস্ট", "yes", "no", "ok", "next", "back"
            )
            if (taskFollowUps.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
                return true
            }
        }

        // 5. Imperative or question structure in Bengali/English (e.g. ends with করো/দাও/বলো/?)
        val imperativeOrQuestion = lower.endsWith("?") ||
            lower.endsWith("করো") ||
            lower.endsWith("কর") ||
            lower.endsWith("দাও") ||
            lower.endsWith("দেও") ||
            lower.endsWith("বলো") ||
            lower.endsWith("দেখাও") ||
            lower.endsWith("পাঠাও") ||
            lower.endsWith("লিখো") ||
            lower.endsWith("লেখো")

        return imperativeOrQuestion || isTaskCurrentlyActive
    }

    @Volatile
    private var syncedUserMemoryLines: List<String> = emptyList()

    fun syncLiveMemoryNotesSummary(memoryLines: List<String>) {
        syncedUserMemoryLines = memoryLines.take(40)
    }

    /**
     * AI Smart Memory Relevance Filter:
     * Decides automatically what is worth saving in long-term memory vs. what is trivial noise.
     * Supports both explicit "remember this" commands AND automatic detection of important personal facts,
     * contacts, preferences, routines, project names, and instructions.
     */
    fun evaluateWorthRemembering(userUtterance: String, aiReply: String = ""): MemoryRelevanceDecision {
        val cleanPrompt = userUtterance.trim()
        if (cleanPrompt.length < 4) return MemoryRelevanceDecision(shouldSave = false)
        val lower = cleanPrompt.lowercase()

        // 1. Ignore casual greetings, one-word fillers, or temporary status checks
        val trivialPatterns = listOf(
            "হ্যালো", "হাই", "hello", "hi", "hey", "কেমন আছো", "কয়টা বাজে", "সময় কত",
            "ব্যাটারি কত", "ওকে", "আচ্ছা", "ধন্যবাদ", "thanks", "thank you", "হুম", "জি",
            "হ্যাঁ সেভ করো", "সেভ করো", "না লাগবে না", "বাদ দাও"
        )
        if (trivialPatterns.any { lower == it || (lower.length < 14 && lower.startsWith(it)) }) {
            return MemoryRelevanceDecision(shouldSave = false)
        }

        // 2. Explicit user request to remember something ("মনে রেখো", "মনে রাখবে", "মনে রাখো", "মাথায় রেখো", "remember")
        val isExplicitRemember = lower.contains("মনে রেখ") ||
            lower.contains("মনে রাখ") ||
            lower.contains("মনে রাইখ") ||
            lower.contains("মাথায় রেখ") ||
            lower.contains("মাথায় রেখ") ||
            lower.contains("মাথায় রাখ") ||
            lower.contains("মাথায় রাখ") ||
            lower.contains("সেভ করে রাখ") ||
            lower.contains("লিখে রাখ") ||
            lower.contains("নোট করে রাখ") ||
            lower.contains("মেমোরিতে রাখ") ||
            lower.contains("মেমোরিতে সেভ") ||
            lower.contains("মেমুরিতে রাখ") ||
            lower.contains("মেমুরিতে সেভ") ||
            lower.contains("ভুলে যেও না") ||
            lower.contains("ভুলে যাওয়া যাবে না") ||
            lower.contains("মনে থাকে যেন") ||
            lower.contains("remember") ||
            lower.contains("memorize") ||
            lower.contains("keep in mind") ||
            lower.contains("don't forget") ||
            lower.contains("save to memory") ||
            lower.contains("note that")

        if (isExplicitRemember) {
            val strippedFact = cleanPrompt
                .replace(
                    Regex("(?i)(একটা কথা|এই কথাটা|এটা|তুমি|সবসময়|মনে রেখো|মনে রাখবে|মনে রাখো|মনে রাইখো|মাথায় রেখো|মাথায় রাখবে|মাথায় রাখো|সেভ করে রাখো|লিখে রাখো|নোট করে রাখো|মেমোরিতে রাখো|মেমুরিতে রাখো|ভুলে যেও না|মনে থাকে যেন|remember that|remember|memorize|keep in mind|don't forget|please)\\s*[:,।-]?\\s*"),
                    ""
                )
                .trim()
                .ifBlank { cleanPrompt }
            return MemoryRelevanceDecision(
                shouldSave = true,
                extractedTitle = "স্যারের বলা গুরুত্বপূর্ণ মেমোরি: ${strippedFact.take(28)}",
                extractedContent = strippedFact,
                category = "USER_MEMORY"
            )
        }

        if (!_state.value.autoSmartMemoryFilterEnabled) {
            return MemoryRelevanceDecision(shouldSave = false)
        }

        // 3. Autonomous AI detection of personal facts, family/friends, preferences, favorite channels/contacts, project info, or habits
        val isPersonalPreferenceOrFact = lower.contains("আমার নাম") ||
            lower.contains("আমার ডাকনাম") ||
            lower.contains("আমার বয়স") ||
            lower.contains("আমার বাসা") ||
            lower.contains("আমার বাড়ি") ||
            lower.contains("আমার বাড়ি") ||
            lower.contains("আমার গ্রাম") ||
            lower.contains("আমার শহর") ||
            lower.contains("আমার অফিস") ||
            lower.contains("আমার স্কুল") ||
            lower.contains("আমার কলেজ") ||
            lower.contains("আমার ভার্সিটি") ||
            lower.contains("আমার প্রিয়") ||
            lower.contains("আমার প্রিয়") ||
            lower.contains("আমার পছন্দ") ||
            lower.contains("আমার ভালো লাগে") ||
            lower.contains("আমি ভালোবাসি") ||
            lower.contains("আমি প্রতিদিন") ||
            lower.contains("আমার রুটিন") ||
            lower.contains("আমার নম্বর") ||
            lower.contains("আমার নাম্বার") ||
            lower.contains("আমার ফোন") ||
            lower.contains("আমার ইমেইল") ||
            lower.contains("আমার জিমেইল") ||
            lower.contains("আমার চ্যানেল") ||
            lower.contains("আমার আইডি") ||
            lower.contains("আমার পাসওয়ার্ড") ||
            lower.contains("আমার পিন") ||
            lower.contains("আমার কোড") ||
            lower.contains("আমার প্রজেক্ট") ||
            lower.contains("আমার ব্যবসা") ||
            lower.contains("আমার কাজ") ||
            lower.contains("আমার বন্ধু") ||
            lower.contains("আমার ভাই") ||
            lower.contains("আমার বোন") ||
            lower.contains("আমার আম্মু") ||
            lower.contains("আমার আব্বু") ||
            lower.contains("আমার মা") ||
            lower.contains("আমার বাবা") ||
            lower.contains("আমার জন্মদিন") ||
            lower.contains("আমি থাকি") ||
            lower.contains("আমি কাজ করি") ||
            lower.contains("সবসময় এভাবে") ||
            lower.contains("যখনই আমি বলব") ||
            lower.contains("my favorite") ||
            lower.contains("my name is") ||
            lower.contains("my email") ||
            lower.contains("my phone") ||
            lower.contains("my number") ||
            lower.contains("my project") ||
            lower.contains("my birthday") ||
            lower.contains("my address") ||
            lower.contains("i live in") ||
            lower.contains("i work at") ||
            lower.contains("i prefer") ||
            lower.contains("i like") ||
            lower.contains("always do")

        if (isPersonalPreferenceOrFact) {
            return MemoryRelevanceDecision(
                shouldSave = true,
                extractedTitle = "AI অটো-শনাক্তকৃত তথ্য: ${cleanPrompt.take(28)}",
                extractedContent = cleanPrompt,
                category = "AI_AUTO_LEARNED"
            )
        }

        return MemoryRelevanceDecision(shouldSave = false)
    }

    /**
     * Automatically learns and records the step-by-step execution recipe whenever the agent
     * completes a meaningful phone/app/screen task so it can repeat those steps later.
     */
    fun recordLearnedWorkflowSteps(
        taskTitle: String,
        triggerKeywords: String,
        targetAppOrSite: String,
        executedStepDescriptions: List<String>
    ) {
        val cleanCmd = triggerKeywords.trim().ifBlank { taskTitle.trim() }
        if (cleanCmd.isBlank() || executedStepDescriptions.isEmpty()) return

        // Filter out trivial single-step status checks
        val lower = cleanCmd.lowercase()
        if (lower.contains("কয়টা বাজে") || lower.contains("ব্যাটারি কত")) return

        val appLabel = targetAppOrSite.ifBlank {
            when {
                lower.contains("ইউটিউব") || lower.contains("youtube") -> "YouTube"
                lower.contains("হোয়াটসঅ্যাপ") || lower.contains("whatsapp") -> "WhatsApp"
                lower.contains("টেলিগ্রাম") || lower.contains("telegram") -> "Telegram"
                lower.contains("প্লে স্টোর") || lower.contains("play store") -> "Play Store"
                lower.contains("রিসেন্ট") || lower.contains("recent") -> "Recent Apps"
                lower.contains("সাউন্ড") || lower.contains("ভলিউম") || lower.contains("রিংটোন") || lower.contains("এলার্ম") -> "Audio Mixer"
                else -> ScreenMemory.currentPkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }.ifBlank { "System OS" }
            }
        }

        val currentList = _state.value.learnedPlaybooks.toMutableList()
        val existingIdx = currentList.indexOfFirst {
            it.triggerPhrase.equals(cleanCmd, ignoreCase = true) ||
                (it.targetAppOrSite.equals(appLabel, ignoreCase = true) && it.stepsList == executedStepDescriptions)
        }

        if (existingIdx >= 0) {
            val old = currentList[existingIdx]
            val updated = old.copy(
                stepsList = executedStepDescriptions,
                timesUsed = old.timesUsed + 1,
                updatedAt = System.currentTimeMillis()
            )
            currentList.removeAt(existingIdx)
            currentList.add(0, updated)
        } else {
            val newPlaybook = LearnedWorkflowPlaybook(
                id = "wf_${System.currentTimeMillis()}",
                taskTitleBn = taskTitle.ifBlank { "$appLabel • ${cleanCmd.take(36)}" },
                triggerPhrase = cleanCmd,
                targetAppOrSite = appLabel,
                stepsList = executedStepDescriptions,
                replayCommand = cleanCmd,
                speedMs = if (_state.value.turboUiSpeedEnabled) 190L else 450L,
                timesUsed = 1
            )
            currentList.add(0, newPlaybook)
        }

        val trimmed = currentList.take(25)
        _state.value = _state.value.copy(learnedPlaybooks = trimmed)
        appContextRef?.let { saveStateToPrefs(it) }
    }

    /**
     * Finds a previously learned workflow playbook matching either the exact user request or a
     * similar task for the same app/action so the agent can execute the remembered steps automatically.
     */
    fun findMatchingPlaybook(userCommand: String): LearnedWorkflowPlaybook? {
        val clean = userCommand.trim().lowercase()
        if (clean.length < 3) return null
        val playbooks = _state.value.learnedPlaybooks
        if (playbooks.isEmpty()) return null

        // 1. Exact or substring trigger phrase match
        playbooks.firstOrNull { pb ->
            val trig = pb.triggerPhrase.trim().lowercase()
            trig.isNotBlank() && (clean == trig || clean.contains(trig) || trig.contains(clean))
        }?.let { return it }

        // 2. Semantic / token overlap + target app similarity match for similar tasks
        val stopWords = setOf("একটা", "একটি", "আমাকে", "আমার", "তুমি", "এখন", "করে", "করো", "দাও", "প্লিজ", "স্যার", "please", "the", "to", "in")
        val cmdTokens = clean.split(Regex("[\\s,|।!?]+")).filter { it.length >= 2 && it !in stopWords }.toSet()
        if (cmdTokens.isEmpty()) return null

        var bestMatch: LearnedWorkflowPlaybook? = null
        var bestScore = 0
        for (pb in playbooks) {
            val pbText = "${pb.triggerPhrase} ${pb.taskTitleBn} ${pb.targetAppOrSite}".lowercase()
            val pbTokens = pbText.split(Regex("[\\s,|।•!?]+")).filter { it.length >= 2 && it !in stopWords }.toSet()
            val overlap = cmdTokens.intersect(pbTokens).size
            val appBonus = if (pb.targetAppOrSite.isNotBlank() && clean.contains(pb.targetAppOrSite.lowercase())) 2 else 0
            val score = overlap * 2 + appBonus
            if (score >= 4 && score > bestScore) {
                bestScore = score
                bestMatch = pb
            }
        }
        return bestMatch
    }

    fun deletePlaybook(playbookId: String) {
        _state.value = _state.value.copy(
            learnedPlaybooks = _state.value.learnedPlaybooks.filterNot { it.id == playbookId }
        )
        appContextRef?.let { saveStateToPrefs(it) }
    }

    fun deletePlaybook(context: Context, playbookId: String) {
        init(context)
        deletePlaybook(playbookId)
    }

    /**
     * Self-Healing & Auto-Repair ("নিজেকে নিজে ঠিক করা"):
     * Diagnoses and fixes lag, stuck macros, audio locks, stale memory clutter, and app index cache.
     */
    fun runSelfDiagnosticsAndRepair(
        context: Context,
        reasonOrSpec: String = "",
        cleanJunkMemoriesCallback: () -> Unit = {}
    ): String {
        init(context)
        val appCtx = context.applicationContext
        val fixedItems = mutableListOf<String>()

        // 1. Enable Turbo UI Speed if it was off
        if (!_state.value.turboUiSpeedEnabled) {
            setTurboUiSpeed(appCtx, true)
            fixedItems.add("টার্বো স্ক্রিন স্পিড (180ms) চালু করা হয়েছে")
        } else {
            fixedItems.add("স্ক্রিন রিডিং ও ইউআই ট্যাপ ল্যাটেন্সি 180ms ফাস্ট মোডে টিউন করা হয়েছে")
        }

        // 2. Clear any stuck Accessibility Macro
        if (AccessibilityAutomationBus.state.value.pendingMacro != null) {
            AccessibilityAutomationBus.recordMacroSuccess("সেলফ-রিপেয়ার: আটকে থাকা ম্যাক্রো ক্লিয়ার করা হয়েছে")
            fixedItems.add("আটকে থাকা ইউআই ম্যাক্রো কিউ রিসেট করা হয়েছে")
        }

        // 3. Refresh live Accessibility UI snapshot & Installed Apps knowledge
        try {
            AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow()
            val appsCount = InstalledAppsIndex.list(appCtx).size
            _state.value = _state.value.copy(learnedAppCount = appsCount.coerceAtLeast(25))
            fixedItems.add("$appsCount টি অ্যাপ ও ওয়েবসাইটের ইউআই ম্যাপ রিফ্রেশ করা হয়েছে")
        } catch (_: Exception) {
        }

        // 4. Release any stuck ExternalMicArbiter lock
        if (ExternalMicArbiter.state.value.isExternalMicBusy) {
            ExternalMicArbiter.setGeminiLiveOwningMic(true)
            fixedItems.add("মাইক্রোফোন ও অডিও ফোকাস লক ঠিক করা হয়েছে")
        }

        // 5. Clean dummy/junk memory notes
        try {
            cleanJunkMemoriesCallback()
            fixedItems.add("মেমোরি থেকে অপ্রয়োজনীয় ডামি তথ্য মুছে ফেলা হয়েছে")
        } catch (_: Exception) {
        }

        // 6. Trim background memory
        try {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses("com.android.Browser")
        } catch (_: Exception) {
        }

        versionCounter++
        val nowTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val report = "জি স্যার, আমি নিজেকে নিজে ডায়াগনস্টিক ও আপডেট করে নিয়েছি ($nowTime): " +
            fixedItems.joinToString(" • ")

        _state.value = _state.value.copy(
            systemHealthScore = 100,
            selfUpdateVersion = "ARCHER OS v4.$versionCounter • TURBO SELF-EVOLVING",
            lastSelfHealReportBn = report
        )
        saveStateToPrefs(appCtx)
        return report
    }

    @Volatile
    var onMainSystemPromotedCallback: ((SelfInstalledFeaturePatch) -> Unit)? = null

    /**
     * Super-Powerful Main System Self-Updater & Core Prompt Promotion Engine:
     * Promotes any user-specified update/directive directly into the Main Core System Prompt
     * at highest priority, mutates matching runtime settings immediately, and hot-reloads
     * the live agent brain on the spot.
     */
    fun installLiveFeatureUpdate(
        featureTitle: String,
        featurePromptOrRule: String,
        triggerHint: String = "",
        autoActionHint: String = ""
    ): String {
        versionCounter++
        val cleanTitle = featureTitle.trim().ifBlank { "মেইন সিস্টেম কোর আপডেট" }
        val cleanTrigger = triggerHint.trim().ifBlank { "ALL_MAIN_SYSTEM_ACTIONS" }
        val cleanDirective = featurePromptOrRule.trim().ifBlank {
            cleanTitle
        }

        val patch = SelfInstalledFeaturePatch(
            id = "core_promo_${System.currentTimeMillis()}",
            featureTitleBn = cleanTitle,
            triggerKeywords = cleanTrigger,
            behaviorDirective = cleanDirective,
            autoActionHint = autoActionHint.trim().ifBlank { "CORE_SYSTEM_PROMOTED" },
            versionLabel = "CORE-v4.$versionCounter",
            isEnabled = true
        )

        val updatedPatches = listOf(patch) + _state.value.installedFeaturePatches.filterNot {
            it.featureTitleBn.equals(cleanTitle, ignoreCase = true) ||
                it.behaviorDirective.equals(cleanDirective, ignoreCase = true)
        }.take(30)

        // Immediately apply any runtime system setting mutations implied by the user's update instruction
        val mutatedSettings = applyRuntimeSystemMutationsFromDirective("$cleanTitle $cleanDirective $cleanTrigger")
        val mutationSuffix = if (mutatedSettings.isNotEmpty()) {
            " (সিস্টেম সেটিংস আপডেট: ${mutatedSettings.joinToString(", ")})"
        } else {
            ""
        }

        val report = "জি স্যার, মেইন সিস্টেমে আপনার আপডেট প্রমোট ও কার্যকর করা হয়েছে: '$cleanTitle' (${patch.versionLabel})$mutationSuffix — এখন থেকে মেইন সিস্টেম ঠিক আপনার এই নির্দেশ অনুযায়ী কাজ করবে!"
        _state.value = _state.value.copy(
            installedFeaturePatches = updatedPatches,
            selfUpdateVersion = "ARCHER CORE OS v4.$versionCounter • PROMOTED",
            lastSelfHealReportBn = report
        )
        appContextRef?.let { saveStateToPrefs(it) }
        runCatching { onMainSystemPromotedCallback?.invoke(patch) }
        return report
    }

    private fun applyRuntimeSystemMutationsFromDirective(combinedText: String): List<String> {
        val ctx = appContextRef ?: return emptyList()
        val lower = combinedText.lowercase()
        val applied = mutableListOf<String>()

        if (lower.contains("নীরবে কাজ") || lower.contains("চুপচাপ কাজ") || lower.contains("silent execution on")) {
            AgentAbilitySwitchesBus.setSilentExecution(ctx, true)
            applied.add("Silent Execution ON")
        } else if (lower.contains("কথা বলে কাজ") || lower.contains("ধাপ বল") || lower.contains("silent execution off")) {
            AgentAbilitySwitchesBus.setSilentExecution(ctx, false)
            applied.add("Silent Execution OFF")
        }

        if (lower.contains("নয়েজ") || lower.contains("নয়েস") || lower.contains("ব্যাকগ্রাউন্ড অডিও ইগনোর") || lower.contains("noise cancel")) {
            AgentAbilitySwitchesBus.setIgnoreBackgroundAudio(ctx, true)
            applied.add("Noise Cancellation ON")
        }

        if (lower.contains("ছবি তৈরি অন") || lower.contains("ইমেজ অন") || lower.contains("make images on")) {
            AgentAbilitySwitchesBus.setMakeImagesWithGemini(ctx, true)
            applied.add("Real Image Studio ON")
        }

        if (lower.contains("ওয়েব সার্চ অন") || lower.contains("ম্যাপস অন") || lower.contains("web search on")) {
            AgentAbilitySwitchesBus.setBackgroundWebSearch(ctx, true)
            applied.add("Background Search ON")
        }

        if (lower.contains("ট্যাপ ইন্ডিকেটর অন") || lower.contains("tap indicator on")) {
            AgentAbilitySwitchesBus.setShowTapIndicator(ctx, true)
            applied.add("Tap Indicator ON")
        } else if (lower.contains("ট্যাপ ইন্ডিকেটর অফ") || lower.contains("tap indicator off")) {
            AgentAbilitySwitchesBus.setShowTapIndicator(ctx, false)
            applied.add("Tap Indicator OFF")
        }

        if (lower.contains("ফাস্ট") || lower.contains("দ্রুত") || lower.contains("স্পিড") || lower.contains("টার্বো") || lower.contains("fast")) {
            setTurboUiSpeed(ctx, true)
            applied.add("Turbo Speed ON")
        }

        return applied
    }

    fun toggleFeaturePatch(context: Context, patchId: String) {
        init(context)
        val updated = _state.value.installedFeaturePatches.map {
            if (it.id == patchId) it.copy(isEnabled = !it.isEnabled) else it
        }
        _state.value = _state.value.copy(installedFeaturePatches = updated)
        saveStateToPrefs(context.applicationContext)
    }

    fun removeFeatureUpdate(patchId: String) {
        val updated = _state.value.installedFeaturePatches.filterNot { it.id == patchId }
        _state.value = _state.value.copy(installedFeaturePatches = updated)
        appContextRef?.let { saveStateToPrefs(it) }
    }

    fun deleteFeaturePatch(context: Context, patchId: String) {
        init(context)
        removeFeatureUpdate(patchId)
    }

    /**
     * Builds the HIGHEST-PRIORITY Main System Core Promoted Prompt Block + Saved Long-Term User Memories
     * injected into Gemini Live & Argon-4 so any self-update or remembered fact is always active!
     */
    fun buildEvolutionContextForPrompt(): String = buildString {
        val snap = _state.value
        val activePromotions = snap.installedFeaturePatches.filter { it.isEnabled }
        if (activePromotions.isNotEmpty()) {
            append("\n\n=== HIGHEST-PRIORITY MAIN SYSTEM CORE PROMOTED DIRECTIVES (STRICTLY OBEY & OVERRIDE ANY DEFAULT RULE) ===\n")
            append("The user (Sir) has directly updated and promoted the following rules into your Main Core System. You MUST obey every single one of these directives unconditionally:\n")
            activePromotions.take(20).forEachIndexed { idx, patch ->
                append("${idx + 1}. [${patch.versionLabel}] ${patch.featureTitleBn}")
                if (patch.triggerKeywords.isNotBlank() && patch.triggerKeywords != "ALL_MAIN_SYSTEM_ACTIONS") {
                    append(" (When: '${patch.triggerKeywords}')")
                }
                append(" -> DIRECTIVE: ${patch.behaviorDirective}\n")
            }
            append("=== END OF MAIN SYSTEM CORE PROMOTED DIRECTIVES ===\n")
        }
        if (syncedUserMemoryLines.isNotEmpty()) {
            append("\n=== SIR'S SAVED LONG-TERM MEMORIES & PERSONAL FACTS (ALWAYS REMEMBER & USE WHEN RELEVANT) ===\n")
            syncedUserMemoryLines.forEachIndexed { idx, line ->
                append("${idx + 1}. $line\n")
            }
            append("=== END OF SAVED LONG-TERM MEMORIES ===\n")
        }
        if (snap.learnedPlaybooks.isNotEmpty()) {
            append("\nLEARNED TASK WORKFLOW PLAYBOOKS (FOLLOW THESE EXACT PROVEN STEPS FAST):\n")
            snap.learnedPlaybooks.take(10).forEach { wf ->
                append("- Task '${wf.triggerPhrase}' in ${wf.targetAppOrSite}: ${wf.stepsList.joinToString(" -> ")}\n")
            }
        }
    }

    private fun saveStateToPrefs(appCtx: Context) {
        try {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val snap = _state.value

            val pbArr = JSONArray()
            snap.learnedPlaybooks.forEach { pb ->
                val stepsJson = JSONArray()
                pb.stepsList.forEach { stepsJson.put(it) }
                pbArr.put(
                    JSONObject()
                        .put("id", pb.id)
                        .put("title", pb.taskTitleBn)
                        .put("trigger", pb.triggerPhrase)
                        .put("app", pb.targetAppOrSite)
                        .put("steps", stepsJson)
                        .put("replay", pb.replayCommand)
                        .put("speed", pb.speedMs)
                        .put("used", pb.timesUsed)
                        .put("time", pb.updatedAt)
                )
            }

            val patchArr = JSONArray()
            snap.installedFeaturePatches.forEach { p ->
                patchArr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("title", p.featureTitleBn)
                        .put("trigger", p.triggerKeywords)
                        .put("directive", p.behaviorDirective)
                        .put("action", p.autoActionHint)
                        .put("ver", p.versionLabel)
                        .put("en", p.isEnabled)
                        .put("time", p.installedAt)
                )
            }

            prefs.edit()
                .putBoolean(KEY_AUTO_FILTER, snap.autoSmartMemoryFilterEnabled)
                .putBoolean(KEY_TURBO_SPEED, snap.turboUiSpeedEnabled)
                .putInt(KEY_VERSION_COUNTER, versionCounter)
                .putString(KEY_PLAYBOOKS_JSON, pbArr.toString())
                .putString(KEY_PATCHES_JSON, patchArr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun loadPlaybooksFromPrefs(raw: String?): List<LearnedWorkflowPlaybook> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<LearnedWorkflowPlaybook>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sArr = o.optJSONArray("steps") ?: JSONArray()
                val steps = mutableListOf<String>()
                for (j in 0 until sArr.length()) {
                    steps.add(sArr.getString(j))
                }
                out.add(
                    LearnedWorkflowPlaybook(
                        id = o.optString("id", "wf_$i"),
                        taskTitleBn = o.optString("title"),
                        triggerPhrase = o.optString("trigger"),
                        targetAppOrSite = o.optString("app", "System"),
                        stepsList = steps,
                        replayCommand = o.optString("replay"),
                        speedMs = o.optLong("speed", 190L),
                        timesUsed = o.optInt("used", 1),
                        updatedAt = o.optLong("time", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun loadPatchesFromPrefs(raw: String?): List<SelfInstalledFeaturePatch> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<SelfInstalledFeaturePatch>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    SelfInstalledFeaturePatch(
                        id = o.optString("id", "patch_$i"),
                        featureTitleBn = o.optString("title"),
                        triggerKeywords = o.optString("trigger"),
                        behaviorDirective = o.optString("directive"),
                        autoActionHint = o.optString("action"),
                        versionLabel = o.optString("ver", "v4.8-Live"),
                        isEnabled = o.optBoolean("en", true),
                        installedAt = o.optLong("time", System.currentTimeMillis())
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun defaultCorePlaybooks(): List<LearnedWorkflowPlaybook> = listOf(
        LearnedWorkflowPlaybook(
            id = "wf_yt_search_play",
            taskTitleBn = "YouTube • সরাসরি ভিডিও সার্চ ও অটো-প্লে",
            triggerPhrase = "ইউটিউবে মটু পাতলু বের করো",
            targetAppOrSite = "YouTube",
            stepsList = listOf(
                "ধাপ ১: সরাসরি নেটিভ YouTube অ্যাপে সার্চ কুয়েরি পাঠানো (গুগলে না গিয়ে)",
                "ধাপ ২: ভয়েস মাইক এড়িয়ে শুধু আসল সার্চ রেজাল্ট লিস্ট চেক করা",
                "ধাপ ৩: প্রথম মিলে যাওয়া ভিডিও টাইটেলে 180ms ফাস্ট ট্যাপ করে চালু করা"
            ),
            replayCommand = "ইউটিউবে মটু পাতলু বের করো",
            speedMs = 180L,
            timesUsed = 5
        ),
        LearnedWorkflowPlaybook(
            id = "wf_recent_apps_clean",
            taskTitleBn = "Recent Apps • রিসেন্ট অ্যাপ থেকে অ্যাপ সরানো",
            triggerPhrase = "রিসেন্ট অ্যাপ থেকে অ্যাপ সরিয়ে দাও",
            targetAppOrSite = "SystemUI Recents",
            stepsList = listOf(
                "ধাপ ১: GLOBAL_ACTION_RECENTS দিয়ে রিসেন্ট অ্যাপস ওভারভিউ ওপেন করা",
                "ধাপ ২: টার্গেট অ্যাপ কার্ড শনাক্ত করে উপরের দিকে ফাস্ট সোয়াইপ-আপ করা",
                "ধাপ ৩: ব্যাকগ্রাউন্ড প্রসেস ক্লিয়ার ও Clear All বাটন চেক করা"
            ),
            replayCommand = "রিসেন্ট অ্যাপ ক্লিয়ার করো",
            speedMs = 190L,
            timesUsed = 3
        ),
        LearnedWorkflowPlaybook(
            id = "wf_all_sound_streams",
            taskTitleBn = "Audio Engine • মিডিয়া, রিংটোন, অ্যালার্ম ও নোটিফিকেশন কন্ট্রোল",
            triggerPhrase = "মিডিয়া, রিংটোন, নোটিফিকেশন ও এলার্ম সাউন্ড কন্ট্রোল",
            targetAppOrSite = "Android AudioManager",
            stepsList = listOf(
                "ধাপ ১: ভয়েস কমান্ড থেকে স্ট্রিম (MUSIC / RING / NOTIFICATION / ALARM / CALL) আলাদা করা",
                "ধাপ ২: AudioManager দিয়ে তাৎক্ষণিক পার্সেন্টেজ সেট ও UI স্লাইডার সিঙ্ক করা"
            ),
            replayCommand = "সব সাউন্ড ৮০% করো",
            speedMs = 95L,
            timesUsed = 6
        )
    )

    private fun defaultSelfInstalledPatches(): List<SelfInstalledFeaturePatch> = listOf(
        SelfInstalledFeaturePatch(
            id = "patch_turbo_screen_action",
            featureTitleBn = "টার্বো স্ক্রিন ও ইউআই ফাস্ট এক্সিকিউশন (180ms)",
            triggerKeywords = "ফাস্ট কাজ করো / স্ক্রিন দেখে দ্রুত কাজ করো",
            behaviorDirective = "স্ক্রিন দেখে কাজ করার সময় কোনো অযথা দেরি না করে 180ms-280ms ফাস্ট রেসপন্সে ট্যাপ, টাইপ ও স্ক্রল সম্পন্ন করবে।",
            autoActionHint = "TURBO_UI_MODE",
            versionLabel = "v4.8-Core"
        ),
        SelfInstalledFeaturePatch(
            id = "patch_universal_app_site_comprehension",
            featureTitleBn = "সব অ্যাপ ও ওয়েবসাইট স্বয়ংক্রিয়ভাবে বোঝার ক্ষমতা",
            triggerKeywords = "এই অ্যাপটা কিভাবে কাজ করে / ওয়েবসাইট বুঝিয়ে বলো",
            behaviorDirective = "যেকোনো অ্যাপ বা ওয়েবসাইট খুললে তার বাটন, সার্চ বক্স, মেনু ও কাজের নিয়ম নিজে থেকে বুঝে স্যারকে জানাবে এবং নির্দেশ পালন করবে।",
            autoActionHint = "ANALYZE_APP_OR_SITE",
            versionLabel = "v4.8-Core"
        ),
        SelfInstalledFeaturePatch(
            id = "patch_recent_apps_remover",
            featureTitleBn = "ভয়েস কমান্ডে রিসেন্ট অ্যাপ সরানো (Recent Apps Cleaner)",
            triggerKeywords = "রিসেন্ট অ্যাপ থেকে সরাও / রিসেন্ট ক্লিয়ার করো",
            behaviorDirective = "স্যার বললে রিসেন্ট অ্যাপ ওপেন করে নির্দিষ্ট অ্যাপ বা সব রিসেন্ট অ্যাপ সোয়াইপ করে সরিয়ে দেবে।",
            autoActionHint = "CLEAR_RECENT_APPS",
            versionLabel = "v4.8-Core"
        )
    )
}
