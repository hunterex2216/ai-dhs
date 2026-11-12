package com.example.service

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class UiElem(
    val label: String,
    val cx: Int,
    val cy: Int,
    val clickable: Boolean,
    val editable: Boolean,
    val seen: Long = System.currentTimeMillis()
)

/**
 * Remembers where every button / field was seen, per app (saved on the phone).
 * The agent uses it to go straight to the right place the next time instead of searching again.
 */
object ScreenMemory {
    private const val PREFS = "omni_screen_memory"
    private val map = ConcurrentHashMap<String, LinkedHashMap<String, UiElem>>()
    @Volatile private var lastSave = 0L
    @Volatile private var loaded = false
    @Volatile var currentPkg: String = ""
    @Volatile var currentElems: List<UiElem> = emptyList()

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        AppKnowledgeRegistry.init(ctx)
        try {
            val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("m", null) ?: return
            val root = JSONObject(raw)
            root.keys().forEach { pkg ->
                val arr = root.getJSONArray(pkg)
                val m = LinkedHashMap<String, UiElem>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    m[o.getString("l")] = UiElem(
                        o.getString("l"),
                        o.getInt("x"),
                        o.getInt("y"),
                        o.optBoolean("c"),
                        o.optBoolean("e"),
                        o.optLong("t")
                    )
                }
                map[pkg] = m
            }
        } catch (_: Exception) {}
    }

    fun record(ctx: Context?, pkg: String, elems: List<UiElem>) {
        currentPkg = pkg
        currentElems = elems
        if (ctx != null) AppKnowledgeRegistry.init(ctx)
        val m = map.getOrPut(pkg) { LinkedHashMap() }
        synchronized(m) {
            for (e in elems) {
                m.remove(e.label)
                m[e.label] = e
            }
            while (m.size > 140) m.remove(m.keys.first())
        }
        AppKnowledgeRegistry.learnLiveAppStructure(pkg, elems, ctx)
        val now = System.currentTimeMillis()
        if (ctx != null && now - lastSave > 6000L) {
            lastSave = now
            save(ctx)
        }
    }

    fun save(ctx: Context) {
        try {
            val root = JSONObject()
            map.forEach { (pkg, m) ->
                val arr = JSONArray()
                synchronized(m) {
                    m.values.forEach { e ->
                        arr.put(
                            JSONObject()
                                .put("l", e.label)
                                .put("x", e.cx)
                                .put("y", e.cy)
                                .put("c", e.clickable)
                                .put("e", e.editable)
                                .put("t", e.seen)
                        )
                    }
                }
                root.put(pkg, arr)
            }
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("m", root.toString()).apply()
        } catch (_: Exception) {}
    }

    /** Search remembered elements. pkgHint may be a package name, an app label fragment, or blank (= all apps). */
    fun lookup(ctx: Context, query: String, pkgHint: String): List<Triple<String, String, UiElem>> {
        val q = query.trim().lowercase()
        val hint = pkgHint.trim().lowercase()
        val out = mutableListOf<Triple<String, String, UiElem>>()
        val wantsVoice = q.contains("voice") || q.contains("ভয়েস") || q.contains("mic")
        map.forEach { (pkg, m) ->
            if (hint.isNotBlank() && !pkg.lowercase().contains(hint) &&
                InstalledAppsIndex.list(ctx).none { it.pkg == pkg && it.label.lowercase().contains(hint) }
            ) return@forEach
            synchronized(m) {
                m.values.filter { elem ->
                    val lbl = elem.label.lowercase()
                    val isVoiceBtn = lbl.contains("voice search") || lbl.contains("search with your voice") || lbl.contains("ভয়েস সার্চ")
                    if (!wantsVoice && isVoiceBtn) false
                    else q.isBlank() || lbl.contains(q)
                }.forEach { out.add(Triple(pkg, it.label, it)) }
            }
        }
        return out.sortedByDescending { it.third.seen }.take(15)
    }

    fun describeCurrent(limit: Int = 30): String {
        val e = currentElems
        val useful = e.filter { it.clickable || it.editable }.ifEmpty { e }
        return useful.take(limit).joinToString("; ") {
            "${it.label.take(40)}@${it.cx},${it.cy}${if (it.editable) "[input]" else ""}"
        }
    }
}

/**
 * Universal App & Website Intelligence Engine:
 * - Pre-loaded deep operational blueprints for 30+ major Android apps & website categories.
 * - Real-time autonomous structure profiler (`learnLiveAppStructure` & `explainHowAppOrSiteWorksBn`)
 *   that inspects ANY opened app or website on the user's phone, understands its search bars,
 *   input fields, navigation tabs, and buttons, and explains/executes commands accordingly.
 */
object AppKnowledgeRegistry {
    private const val PREFS_KNOWLEDGE = "archer_app_knowledge_prefs"
    private const val KEY_GUIDES = "learned_guides_json"
    private const val KEY_ROLES = "learned_roles_json"

    private val dynamicLearnedGuides = ConcurrentHashMap<String, String>()
    private val learnedControlRoles = ConcurrentHashMap<String, MutableMap<String, String>>()
    @Volatile private var initialized = false
    @Volatile private var appCtxRef: Context? = null
    @Volatile private var lastPersistAtMs = 0L

    fun init(ctx: Context) {
        appCtxRef = ctx.applicationContext
        if (initialized) return
        initialized = true
        try {
            val prefs = ctx.applicationContext.getSharedPreferences(PREFS_KNOWLEDGE, Context.MODE_PRIVATE)
            prefs.getString(KEY_GUIDES, null)?.let { raw ->
                val obj = JSONObject(raw)
                obj.keys().forEach { k ->
                    val v = obj.optString(k).trim()
                    if (v.isNotBlank()) dynamicLearnedGuides[k.lowercase()] = v
                }
            }
            prefs.getString(KEY_ROLES, null)?.let { raw ->
                val obj = JSONObject(raw)
                obj.keys().forEach { pkg ->
                    val inner = obj.optJSONObject(pkg) ?: return@forEach
                    val m = ConcurrentHashMap<String, String>()
                    inner.keys().forEach { role -> m[role] = inner.optString(role) }
                    learnedControlRoles[pkg.lowercase()] = m
                }
            }
        } catch (_: Exception) {}
    }

    private fun persistAsyncOrThrottled(force: Boolean = false) {
        val ctx = appCtxRef ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastPersistAtMs < 5000L) return
        lastPersistAtMs = now
        try {
            val gJson = JSONObject()
            dynamicLearnedGuides.forEach { (k, v) -> gJson.put(k, v) }
            val rJson = JSONObject()
            learnedControlRoles.forEach { (pkg, roles) ->
                val inner = JSONObject()
                roles.forEach { (role, spec) -> inner.put(role, spec) }
                rJson.put(pkg, inner)
            }
            ctx.getSharedPreferences(PREFS_KNOWLEDGE, Context.MODE_PRIVATE).edit()
                .putString(KEY_GUIDES, gJson.toString())
                .putString(KEY_ROLES, rJson.toString())
                .apply()
        } catch (_: Exception) {}
    }

    fun hasDeepKnowledge(pkg: String): Boolean {
        val low = pkg.lowercase().trim()
        if (low.isBlank()) return false
        if (blueprints.containsKey(low) || dynamicLearnedGuides.containsKey(low)) return true
        return low.contains("whatsapp") || low.contains("telegram") ||
            low.contains("facebook.orca") || low.contains("instagram") ||
            low.contains("youtube") || low.contains("chrome") || low.contains("vending")
    }

    fun saveLearnedAppBlueprint(ctx: Context?, pkg: String, summary: String) {
        if (ctx != null) init(ctx)
        val low = pkg.lowercase().trim()
        val clean = summary.trim().take(480)
        if (low.isBlank() || clean.isBlank()) return
        dynamicLearnedGuides[low] = clean
        persistAsyncOrThrottled(force = true)
    }

    fun recordLearnedRole(
        ctx: Context?,
        pkg: String,
        role: String,
        viewId: String,
        label: String,
        cx: Int,
        cy: Int
    ) {
        if (ctx != null) init(ctx)
        val low = pkg.lowercase().trim()
        val r = role.uppercase().trim()
        if (low.isBlank() || r.isBlank() || cx <= 0 || cy <= 0) return
        val mapForPkg = learnedControlRoles.getOrPut(low) { ConcurrentHashMap() }
        val encoded = "${viewId.trim()}|${label.trim().take(36)}|$cx,$cy"
        mapForPkg[r] = encoded
        persistAsyncOrThrottled(force = false)
    }

    fun getRoleSummary(pkg: String): String {
        val low = pkg.lowercase().trim()
        val m = learnedControlRoles[low] ?: return ""
        if (m.isEmpty()) return ""
        return m.entries.joinToString("; ") { (role, spec) -> "$role=[$spec]" }
    }

    private val blueprints = mapOf(
        "com.google.android.youtube" to "YouTube (ইউটিউব ভিডিও ও শর্টস অ্যাপ): (1) ভিডিও সার্চ ও প্লে করতে সরাসরি `OPEN_APP` target='YouTube' extra='<ভিডিওর নাম>' ব্যবহার করুন—এটি গুগলে না গিয়ে সরাসরি ইউটিউবে সার্চ করে প্রথম ভিডিওতে ফাস্ট ট্যাপ করবে। (2) উপরে ডানদিকে Search আইকন (ভয়েস মাইক এড়িয়ে চলুন), নিচে Home, Shorts, Subscriptions ও You ট্যাব থাকে।",
        "com.whatsapp" to "WhatsApp (হোয়াটসঅ্যাপ মেসেজিং ও কলিং): চ্যাট লিস্টের উপরে Search আইকন/বার দিয়ে কন্টাক্ট খোঁজা যায়; চ্যাটের ভেতর নিচে 'Message' (`entry`) বক্সে টেক্সট লিখে ডানপাশের 'Send' (`send`) বাটনে ট্যাপ করলে সাথে সাথে মেসেজ যায়; উপরে চ্যাট হেডারে কন্টাক্টের নাম ও কল বাটন থাকে।",
        "com.whatsapp.w4b" to "WhatsApp Business (হোয়াটসঅ্যাপ বিজনেস): সাধারণ হোয়াটসঅ্যাপের মতোই—উপরে `menuitem_search` / Search দিয়ে কন্টাক্ট খুঁজুন, চ্যাট খুলে নিচে `entry` বক্সে লিখে `send` বাটনে ট্যাপ করুন।",
        "org.telegram.messenger" to "Telegram (টেলিগ্রাম চ্যানেল, মেসেজ ও ফাইল ডাউনলোডার): উপরে ডানদিকে Search আইকন দিয়ে চ্যানেল/ইউজার বা ফাইল সার্চ করা যায়; চ্যাটের নিচে মেসেজ কম্পোজারে লিখে Send তীরে ট্যাপ করলে মেসেজ যায়।",
        "com.android.chrome" to "Google Chrome ও যেকোনো ওয়েবসাইট (Web Browser): উপরে অ্যাড্রেস/সার্চ বার (`url_bar`) থাকে যেখানে ওয়েবসাইটের নাম বা সার্চ লিখে Enter দেওয়া যায়। ওয়েবসাইটের ভেতরে `[input]` ফর্ম ফিল্ডে টাইপ করা, বাটনে ট্যাপ করা এবং নিচে স্ক্রল করে ডাউনলোড বা লিংক ওপেন করা যায়।",
        "com.android.vending" to "Google Play Store (অ্যাপ ইনস্টল ও আপডেট স্টোর): যেকোনো অ্যাপ বা গেম ইনস্টল/আপডেট করতে `INSTALL_APP_PLAYSTORE` অ্যাকশন ব্যবহার করুন; অ্যাপ পেজে সবুজ 'Install', 'Update' বা 'Open' বাটন থাকে।",
        "com.facebook.katana" to "Facebook (ফেসবুক সোশ্যাল ফিড ও রিলস): উপরে Search বার ও মেসেঞ্জার আইকন; প্রোফাইলে থাকলে 'Message' বাটনে ট্যাপ করে চ্যাটে যাওয়া যায়; ফিড নিচে স্ক্রল করে পোস্ট দেখা যায়।",
        "com.facebook.orca" to "Messenger (ফেসবুক মেসেঞ্জার): চ্যাট লিস্টের উপরে Search বারে নাম লিখে খুঁজুন অথবা চ্যাটের নামের ওপর ট্যাপ করে নিচে মেসেজ বক্সে টাইপ ও Send করুন। প্রোফাইলে থাকলে 'Message' বাটনে ট্যাপ করুন।",
        "com.instagram.android" to "Instagram (ইনস্টাগ্রাম রিলস, পোস্ট ও ডিএম): নিচে Home, Search, Reels, Profile ট্যাব এবং উপরে ডানদিকে মেসেজ (DM) আইকন থাকে; কারও প্রোফাইলে থাকলে 'Message' বাটনে ট্যাপ করে সরাসরি ডিএম খোলা যায়।",
        "com.google.android.apps.messaging" to "Messages (SMS অ্যাপ): উপরে Search bar দিয়ে কন্টাক্ট/নম্বর খুঁজুন অথবা Start chat এ ট্যাপ করুন; চ্যাটের নিচে SMS ইনপুট বক্সে লিখে Send চাপুন।",
        "com.zhiliaoapp.musically" to "TikTok (টিকটক ভিডিও ফিড): ফুল-স্ক্রিন শর্ট ভিডিও অ্যাপ। পরবর্তী ভিডিও দেখতে `SWIPE_UP`, আগের ভিডিও দেখতে `SWIPE_DOWN`, লাইক দিতে `DOUBLE_TAP` এবং উপরে ডানপাশে Search আইকন কাজ করে।",
        "com.spotify.music" to "Spotify (মিউজিক স্ট্রিমিং): নিচে Search ট্যাবে গিয়ে গান বা শিল্পীর নাম লিখে প্লে করা যায় অথবা সরাসরি `PLAY_MUSIC` কমান্ডে গান বাজে।",
        "com.bKash.customerapp" to "bKash (বিকাশ মোবাইল ব্যাংকিং): হোম স্ক্রিনে Send Money, Mobile Recharge, Cash Out, Make Payment অপশন থাকে; নিরাপত্তার জন্য পিন ব্যবহারকারী নিজে দেবেন।",
        "com.konasl.nagad" to "Nagad (নগদ মোবাইল ফিন্যান্স): সেন্ড মানি, ক্যাশ আউট, মোবাইল রিচার্জ ও বিল পে অপশনগুলো গ্রিড আকারে হোম স্ক্রিনে থাকে।",
        "com.daraz.android" to "Daraz (দারাজ অনলাইন শপিং): উপরে সার্চ বারে পণ্যের নাম লিখে সার্চ, প্রোডাক্ট কার্ডে ট্যাপ এবং নিচে Add to Cart / Buy Now বাটন থাকে।",
        "com.global.foodpanda.android" to "foodpanda (ফুডপান্ডা খাবার অর্ডার): রেস্টুরেন্ট বা খাবারের নাম সার্চ করে কার্টে যোগ ও অর্ডার ট্র্যাক করা যায়।",
        "com.pathao.user" to "Pathao (পাঠাও রাইড ও ডেলিভারি): Bike, Car, Food অপশন সিলেক্ট করে গন্তব্য (Destination) লিখে রাইড ডাকা যায়।",
        "com.google.android.gm" to "Gmail (ইমেইল অ্যাপ): নিচে ডানপাশে 'Compose' বাটনে ট্যাপ করে প্রাপক (To), বিষয় (Subject) ও ইমেইল বডি লিখে উপরে Send আইকনে ট্যাপ করলে মেইল চলে যায়।",
        "com.google.android.apps.maps" to "Google Maps (ম্যাপ ও লোকেশন নেভিগেশন): উপরে সার্চ বক্সে জায়গার নাম লিখে Directions ও Start বাটনে ট্যাপ করলে রাস্তা দেখায়।",
        "com.dts.freefireth" to "Free Fire / Game: ল্যান্ডস্কেপ গেমিং ইন্টারফেস; ডানদিকে নিচে Start বাটন এবং বাম/ডান পাশে লবি মেনু থাকে।",
        "com.android.settings" to "Android System Settings (ফোন সেটিংস): উপরে Search settings বার এবং নিচে Network & internet, Connected devices, Apps, Sound & vibration, Display, Battery, Accessibility অপশন থাকে।"
    )

    fun learnLiveAppStructure(pkg: String, elems: List<UiElem>, ctx: Context? = null) {
        if (pkg.isBlank() || elems.isEmpty()) return
        if (ctx != null) init(ctx)
        val low = pkg.lowercase().trim()
        // Never overwrite a rich AI-learned blueprint with a generic list if we already have one; only fill or append controls
        val existing = dynamicLearnedGuides[low].orEmpty()
        if (existing.contains("AI-Learned")) return
        val inputs = elems.filter { it.editable }.map { "${it.label}@${it.cx},${it.cy}" }.distinct().take(4)
        val clickables = elems.filter { it.clickable && !it.editable }.map { it.label }.distinct().take(10)
        val summary = buildString {
            append("লাইভ শেখা ইউআই গঠন ($pkg): ")
            if (inputs.isNotEmpty()) {
                append("ইনপুট/সার্চ বক্স: [${inputs.joinToString(", ")}]; ")
            }
            if (clickables.isNotEmpty()) {
                append("প্রধান বাটন ও অপশন: [${clickables.joinToString(", ")}]")
            }
        }
        if (summary.length > 30) {
            dynamicLearnedGuides[low] = summary
            persistAsyncOrThrottled(force = false)
        }
    }

    fun guideForPackage(pkg: String): String {
        val low = pkg.lowercase().trim()
        val staticGuide = blueprints[low] ?: when {
            low.contains("youtube") -> blueprints["com.google.android.youtube"]
            low.contains("whatsapp.w4b") -> blueprints["com.whatsapp.w4b"]
            low.contains("whatsapp") -> blueprints["com.whatsapp"]
            low.contains("telegram") -> blueprints["org.telegram.messenger"]
            low.contains("chrome") || low.contains("browser") || low.contains("firefox") || low.contains("opera") -> blueprints["com.android.chrome"]
            low.contains("vending") -> blueprints["com.android.vending"]
            low.contains("facebook.orca") || low.contains("messenger") -> blueprints["com.facebook.orca"]
            low.contains("facebook") -> blueprints["com.facebook.katana"]
            low.contains("instagram") -> blueprints["com.instagram.android"]
            low.contains("messaging") || low.contains("mms") || low.contains("sms") -> blueprints["com.google.android.apps.messaging"]
            low.contains("tiktok") || low.contains("musically") -> blueprints["com.zhiliaoapp.musically"]
            low.contains("spotify") -> blueprints["com.spotify.music"]
            low.contains("bkash") -> blueprints["com.bKash.customerapp"]
            low.contains("nagad") -> blueprints["com.konasl.nagad"]
            low.contains("daraz") -> blueprints["com.daraz.android"]
            low.contains("foodpanda") -> blueprints["com.global.foodpanda.android"]
            low.contains("pathao") -> blueprints["com.pathao.user"]
            low.contains("gm") || low.contains("mail") -> blueprints["com.google.android.gm"]
            low.contains("maps") -> blueprints["com.google.android.apps.maps"]
            low.contains("freefire") || low.contains("pubg") -> blueprints["com.dts.freefireth"]
            low.contains("settings") -> blueprints["com.android.settings"]
            else -> null
        }
        val dynamic = dynamicLearnedGuides[low].orEmpty()
        val roleSummary = getRoleSummary(low)
        return buildString {
            if (staticGuide != null) append(staticGuide)
            if (dynamic.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(dynamic)
            }
            if (roleSummary.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append("Known Controls: $roleSummary")
            }
            if (isEmpty()) {
                append("Live App Layout ($pkg): Inspect screen first, map Search / Menu / Input / Action controls cleanly before acting.")
            }
        }
    }

    /**
     * Generates a rich, human-friendly Bengali explanation of how the currently open app/website
     * (or any queried app/website) works and what actions the agent can perform inside it right now.
     */
    fun explainHowAppOrSiteWorksBn(context: Context, targetAppOrUrlHint: String = ""): String {
        init(context)
        val hint = targetAppOrUrlHint.trim()
        val activePkg = ScreenMemory.currentPkg
        val liveElems = ScreenMemory.currentElems
        val visibleText = AccessibilityAutomationBus.state.value.visibleScreenText

        // If user asked about a specific app/website by name
        if (hint.isNotBlank() && !hint.equals("CURRENT", ignoreCase = true) && !hint.contains("এই অ্যাপ")) {
            val matchedApp = InstalledAppsIndex.search(context, hint).firstOrNull()
            if (matchedApp != null) {
                val guide = guideForPackage(matchedApp.pkg)
                return "'${matchedApp.label}' (${matchedApp.pkg}) অ্যাপটি ফোনে আছে। এটি যেভাবে কাজ করে: $guide"
            }
            val lowerHint = hint.lowercase()
            val matchedBlueprint = blueprints.entries.firstOrNull { (k, v) ->
                k.contains(lowerHint) || v.lowercase().contains(lowerHint)
            }?.value
            if (matchedBlueprint != null) {
                return matchedBlueprint
            }
        }

        val targetPkg = activePkg.ifBlank { "বর্তমান স্ক্রিন" }
        val baseGuide = guideForPackage(targetPkg)
        val inputBoxes = liveElems.filter { it.editable }.map { it.label }.distinct().take(4)
        val topButtons = liveElems.filter { it.clickable && !it.editable }.map { it.label }.distinct().take(8)

        return buildString {
            append("বর্তমান অ্যাপ/স্ক্রিন ($targetPkg) আমি ভালোভাবে পড়ে বুঝে নিয়েছি। ")
            append(baseGuide)
            if (inputBoxes.isNotEmpty()) {
                append(" • এখানে টাইপ/সার্চের জায়গা আছে: ${inputBoxes.joinToString(", ")}। ")
            }
            if (topButtons.isNotEmpty()) {
                append("• এখন সামনে যেসব বাটন/অপশন দেখা যাচ্ছে: ${topButtons.joinToString(", ")}। ")
            } else if (visibleText.isNotBlank()) {
                append("• স্ক্রিনে এখন দেখা যাচ্ছে: ${visibleText.take(160)}। ")
            }
        }
    }

    fun listAllKnownAppGuides(context: Context): List<Pair<String, String>> {
        init(context)
        val installed = InstalledAppsIndex.list(context)
        val result = mutableListOf<Pair<String, String>>()
        for (app in installed.take(35)) {
            result.add("${app.label} (${app.pkg})" to guideForPackage(app.pkg))
        }
        if (result.isEmpty()) {
            blueprints.forEach { (pkg, guide) ->
                result.add(pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } to guide)
            }
        }
        return result
    }

    fun compactSummaryForSystemPrompt(): String = """
        UNIVERSAL APP COMPREHENSION, HONEST SCREEN VISION & HUMAN-SPEED EXECUTION:
        - NEVER GUESS OR HALLUCINATE THE SCREEN: Only describe what you genuinely read from the live screen frames or `read_screen_now()`. Before answering questions about what is on screen ("কী লেখা আছে?", "কে মেসেজ দিয়েছে?", "কোন অ্যাপ এটা?"), call `read_screen_now` if you are not 100% certain.
        - LEARN UNFAMILIAR APPS FIRST: When an unfamiliar app opens or user asks how it works, call `understand_app_or_website(target="CURRENT")` or let `autonomous_screen_agent` inspect and map its buttons/controls first before acting.
        - FAST AUTONOMOUS MESSAGING: Use `autonomous_screen_agent` for messaging in ANY app (WhatsApp, WhatsApp Business, Messenger, Telegram, Instagram, SMS) or deictic commands ("একে/ওকে মেসেজ পাঠাও").
        - FULL LIVE INTERNET ACCESS: Whenever user asks about live news, weather, prices, scores, facts, coding docs, places, or any webpage/URL, ALWAYS call `background_web_search` immediately instead of guessing!
        - NATURAL HUMAN CONVERSATION: Talk like a sharp, warm, natural human companion—short, clear, intelligent, zero robotic filler.
        - YOUTUBE: When user says "ইউটিউবে ... চালাও", call `phone_action(action="OPEN_APP", target="YouTube", extra="<video name>")` and tap the matching video!
        - RECENT APPS & SOUND: Use `CLEAR_RECENT_APPS` and 6-channel `SET_VOLUME` as needed.
    """.trimIndent()
}

object InstalledAppsIndex {
    data class AppInfo(val label: String, val pkg: String)
    @Volatile private var cache: List<AppInfo> = emptyList()
    @Volatile private var at = 0L

    fun list(ctx: Context): List<AppInfo> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - at < 10 * 60_000L) return cache
        return try {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            cache = pm.queryIntentActivities(intent, 0)
                .map { AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
            at = now
            cache
        } catch (_: Exception) {
            cache
        }
    }

    fun search(ctx: Context, q: String): List<AppInfo> {
        val s = q.trim().lowercase()
        return list(ctx).filter { it.label.lowercase().contains(s) || it.pkg.lowercase().contains(s) }.take(10)
    }

    fun summary(ctx: Context, limit: Int = 150): String =
        list(ctx).take(limit).joinToString(", ") { it.label }
}
