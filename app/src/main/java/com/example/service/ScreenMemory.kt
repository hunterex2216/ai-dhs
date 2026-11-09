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
        val m = map.getOrPut(pkg) { LinkedHashMap() }
        synchronized(m) {
            for (e in elems) {
                m.remove(e.label)
                m[e.label] = e
            }
            while (m.size > 140) m.remove(m.keys.first())
        }
        AppKnowledgeRegistry.learnLiveAppStructure(pkg, elems)
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
    private val dynamicLearnedGuides = ConcurrentHashMap<String, String>()

    private val blueprints = mapOf(
        "com.google.android.youtube" to "YouTube (ইউটিউব ভিডিও ও শর্টস অ্যাপ): (1) ভিডিও সার্চ ও প্লে করতে সরাসরি `OPEN_APP` target='YouTube' extra='<ভিডিওর নাম>' ব্যবহার করুন—এটি গুগলে না গিয়ে সরাসরি ইউটিউবে সার্চ করে প্রথম ভিডিওতে ফাস্ট ট্যাপ করবে। (2) উপরে ডানদিকে Search আইকন (ভয়েস মাইক এড়িয়ে চলুন), নিচে Home, Shorts, Subscriptions ও You ট্যাব থাকে।",
        "com.whatsapp" to "WhatsApp (হোয়াটসঅ্যাপ মেসেজিং ও কলিং): উপরে Search আইকন দিয়ে নাম খোঁজা যায়; চ্যাটে ঢুকে নিচে 'Type a message' [input] বক্সে টেক্সট লিখে ডানপাশের 'Send' বাটনে ট্যাপ করলে মেসেজ যায়; উপরে ডানপাশে অডিও ও ভিডিও কল বাটন থাকে।",
        "org.telegram.messenger" to "Telegram (টেলিগ্রাম চ্যানেল, মেসেজ ও ফাইল ডাউনলোডার): উপরে ডানদিকে Search আইকন দিয়ে চ্যানেল বা মুভি/পিডিএফ ফাইল সার্চ করা যায়; ফাইলের ডানপাশে ৩-ডট বা ডাউনলোড তীরে ট্যাপ করে Save to Downloads দিলে ফাইল সেভ হয়।",
        "com.android.chrome" to "Google Chrome ও যেকোনো ওয়েবসাইট (Web Browser): উপরে অ্যাড্রেস/সার্চ বার (url_bar) থাকে যেখানে ওয়েবসাইটের নাম বা সার্চ লিখে Enter দেওয়া যায়। ওয়েবসাইটের ভেতরে `[input]` ফর্ম ফিল্ডে টাইপ করা, বাটনে ট্যাপ করা এবং নিচে স্ক্রল করে ডাউনলোড বা লিংক ওপেন করা যায়।",
        "com.android.vending" to "Google Play Store (অ্যাপ ইনস্টল ও আপডেট স্টোর): যেকোনো অ্যাপ বা গেম ইনস্টল/আপডেট করতে `INSTALL_APP_PLAYSTORE` অ্যাকশন ব্যবহার করুন; অ্যাপ পেজে সবুজ 'Install', 'Update' বা 'Open' বাটন থাকে।",
        "com.facebook.katana" to "Facebook (ফেসবুক সোশ্যাল ফিড ও রিলস): উপরে Search বার ও মেসেঞ্জার আইকন; ফিড নিচে স্ক্রল (SCROLL_DOWN) করে পোস্ট দেখা যায় এবং Like, Comment, Share বাটনে ট্যাপ করা যায়।",
        "com.facebook.orca" to "Messenger (ফেসবুক মেসেঞ্জার): চ্যাট লিস্ট থেকে নামের ওপর ট্যাপ করে নিচে মেসেজ বক্সে টাইপ ও Send করা যায় কিংবা উপরে অডিও/ভিডিও কল দেওয়া যায়।",
        "com.instagram.android" to "Instagram (ইনস্টাগ্রাম রিলস, পোস্ট ও ডিএম): নিচে Home, Search, Reels, Profile ট্যাব এবং উপরে ডানদিকে মেসেজ (DM) আইকন থাকে।",
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

    fun learnLiveAppStructure(pkg: String, elems: List<UiElem>) {
        if (pkg.isBlank() || elems.isEmpty()) return
        val inputs = elems.filter { it.editable }.map { it.label }.distinct().take(4)
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
            dynamicLearnedGuides[pkg.lowercase()] = summary
        }
    }

    fun guideForPackage(pkg: String): String {
        val low = pkg.lowercase().trim()
        val staticGuide = blueprints[low] ?: when {
            low.contains("youtube") -> blueprints["com.google.android.youtube"]
            low.contains("whatsapp") -> blueprints["com.whatsapp"]
            low.contains("telegram") -> blueprints["org.telegram.messenger"]
            low.contains("chrome") || low.contains("browser") || low.contains("firefox") || low.contains("opera") -> blueprints["com.android.chrome"]
            low.contains("vending") -> blueprints["com.android.vending"]
            low.contains("facebook.orca") || low.contains("messenger") -> blueprints["com.facebook.orca"]
            low.contains("facebook") -> blueprints["com.facebook.katana"]
            low.contains("instagram") -> blueprints["com.instagram.android"]
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
        return when {
            staticGuide != null && dynamic.isNotBlank() -> "$staticGuide | $dynamic"
            staticGuide != null -> staticGuide
            dynamic.isNotBlank() -> dynamic
            else -> "Live App Layout ($pkg): Inspect ELEMENTS(label@x,y) first. Tap [input] field or use extra='TYPE' with clean text to type and submit; tap exact button label or coordinates via TAP_XY."
        }
    }

    /**
     * Generates a rich, human-friendly Bengali explanation of how the currently open app/website
     * (or any queried app/website) works and what actions the agent can perform inside it right now.
     */
    fun explainHowAppOrSiteWorksBn(context: Context, targetAppOrUrlHint: String = ""): String {
        val hint = targetAppOrUrlHint.trim()
        val activePkg = ScreenMemory.currentPkg
        val liveElems = ScreenMemory.currentElems
        val visibleText = AccessibilityAutomationBus.state.value.visibleScreenText

        // If user asked about a specific app/website by name
        if (hint.isNotBlank() && !hint.equals("CURRENT", ignoreCase = true) && !hint.contains("এই অ্যাপ")) {
            val matchedApp = InstalledAppsIndex.search(context, hint).firstOrNull()
            if (matchedApp != null) {
                val guide = guideForPackage(matchedApp.pkg)
                return "জি স্যার, '${matchedApp.label}' (${matchedApp.pkg}) অ্যাপটি আপনার ফোনে ইনস্টল করা আছে। এটি যেভাবে কাজ করে: $guide"
            }
            val lowerHint = hint.lowercase()
            val matchedBlueprint = blueprints.entries.firstOrNull { (k, v) ->
                k.contains(lowerHint) || v.lowercase().contains(lowerHint)
            }?.value
            if (matchedBlueprint != null) {
                return "জি স্যার, $matchedBlueprint"
            }
        }

        val targetPkg = activePkg.ifBlank { "বর্তমান স্ক্রিন" }
        val baseGuide = guideForPackage(targetPkg)
        val inputBoxes = liveElems.filter { it.editable }.map { it.label }.distinct().take(4)
        val topButtons = liveElems.filter { it.clickable && !it.editable }.map { it.label }.distinct().take(8)

        return buildString {
            append("জি স্যার, আমি বর্তমান অ্যাপ/সাইট ($targetPkg) সম্পূর্ণ বিশ্লেষণ করে বুঝে নিয়েছি। ")
            append(baseGuide)
            if (inputBoxes.isNotEmpty()) {
                append(" • এখানে লেখার/সার্চ করার বক্স রয়েছে: ${inputBoxes.joinToString(", ")}। ")
            }
            if (topButtons.isNotEmpty()) {
                append("• এখন স্ক্রিনে যেসব বাটনে কাজ করা যাবে: ${topButtons.joinToString(", ")}। ")
            } else if (visibleText.isNotBlank()) {
                append("• স্ক্রিনে দৃশ্যমান তথ্য: ${visibleText.take(140)}। ")
            }
            append("আপনি আমাকে যেকোনো অপশনে ক্লিক করতে, সার্চ করতে বা কাজ করতে বললেই আমি সাথে সাথে করে দিচ্ছি।")
        }
    }

    fun listAllKnownAppGuides(context: Context): List<Pair<String, String>> {
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
        UNIVERSAL APP & WEBSITE COMPREHENSION & FAST OPERATION RULES (FOLLOW STRICTLY):
        - SPEED & ACCURACY: Act immediately and snappily without unnecessary delays. Whenever an app or website opens, you immediately understand its layout from `APP GUIDE`, `NOW ON SCREEN`, and `ELEMENTS(label@x,y)`.
        - EXPLAINING ANY APP OR WEBSITE: If Sir asks how an app or website works ("এই অ্যাপটা কিভাবে কাজ করে", "ওয়েবসাইটটা বুঝিয়ে বলো") or opens a new app/site, call `understand_app_or_website(target="<app or website name or CURRENT>")` and explain its features and available buttons clearly in Bengali.
        - YOUTUBE: When the user says "ইউটিউবে মটু পাতলু বের করো / চালাও" or any YouTube search, call `phone_action(action="OPEN_APP", target="YouTube", extra="মটু পাতলু")` (ONLY the clean video name in `extra`). Once YouTube search results appear, immediately tap the matching video title!
        - RECENT APPS REMOVAL: When the user says "রিসেন্ট অ্যাপ থেকে এটা সরিয়ে দাও", "রিসেন্ট অ্যাপ ক্লিয়ার করো", or "ব্যাকগ্রাউন্ড থেকে অ্যাপটা সরাও", call `phone_action(action="CLEAR_RECENT_APPS", target="<app name or ALL>", extra="CLEAR")`.
        - FULL 6-CHANNEL SOUND CONTROL: When the user asks to control Media (`MUSIC`), Ringtone (`RING`), Notification (`NOTIFICATION`), Alarm (`ALARM`), Call (`VOICE_CALL`), System (`SYSTEM`), or All (`ALL`) sound, call `phone_action(action="SET_VOLUME", target="<MUSIC|RING|NOTIFICATION|ALARM|VOICE_CALL|SYSTEM|ALL>", extra="<0..100|UP|DOWN|MAX|MUTE|UNMUTE>")`.
        - SELF-HEALING & SELF-UPDATING: When the user asks you to fix/repair yourself, speed yourself up, or add/update a feature inside yourself ("নিজেকে নিজে ঠিক করো", "নিজেকে আপডেট করো", "নিজের মধ্যে এই ফিচারটা যোগ করো"), call `self_heal_or_update(mode="SELF_UPDATE" or "SELF_HEAL", feature_title="<title>", trigger_phrase="<when to trigger>", behavior_rule="<how the new feature works>")`.
        - NEVER JUMP TO GOOGLE SEARCH (`WEB_SEARCH`) when inside an app (YouTube, Play Store, WhatsApp, Facebook, TikTok). Never tap voice search mic by mistake, and never type command words ("CLICK", "TAP", coordinates) into input boxes.
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
