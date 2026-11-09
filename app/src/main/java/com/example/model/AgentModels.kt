package com.example.model

enum class AgentActionType {
    PLAY_MUSIC,
    STOP_MUSIC,
    SEND_EMAIL,
    SEND_MESSAGE,
    DIAL_NUMBER,
    SET_VOLUME,
    SET_RINGER_MODE,
    TOGGLE_FLASHLIGHT,
    OPEN_SETTINGS,
    SET_ALARM,
    SET_TIMER,
    OPEN_APP,
    WEB_SEARCH,
    OPEN_MAP,
    ANSWER_QUESTION,
    SAVE_MEMORY_NOTE,
    READ_DEVICE_STATUS,
    SHARE_CONTENT,
    OPEN_CAMERA,
    // Universal Mobile Jarvis & Cross-App Automation Actions
    INSTALL_APP_PLAYSTORE,
    INSTALL_APK_PACKAGE,
    READ_NOTIFICATIONS,
    CHROME_WEB_ACTION,
    DOWNLOAD_FILE_MEDIA,
    WHATSAPP_AUTOMATION,
    TELEGRAM_AUTOMATION,
    SCREEN_UI_CONTROL,
    CALL_CONTROL,
    SCHEDULE_TASK,
    TOGGLE_SYSTEM_SETTING,
    SCAN_CONTACT_OR_SCREEN,
    GENERATE_CODE_PROJECT,
    SHARE_ARCHER_FILE,
    UPDATE_AGENT_RULE,
    CLEAR_RECENT_APPS,
    ANALYZE_APP_OR_SITE,
    SELF_HEAL_OR_UPDATE
}

enum class WakeWordPhase {
    INACTIVE,
    LISTENING_FOR_WAKE_WORD,
    GREETING_BOSS,
    AWAITING_BOSS_COMMAND,
    PROCESSING_COMMAND
}

enum class JarvisNodeModal {
    NONE,
    MEMORY_AND_IDS,
    CHAT_AND_MESSAGES,
    SOUL_AND_WAKE,
    SETTINGS_AND_PERMS,
    LIVE_CAMERA_VISION,
    CODING_AND_ARGON,
    MULTIMODAL_LIVE_API_HUB
}

enum class MusicMood(
    val titleBn: String,
    val titleEn: String,
    val subtitleBn: String,
    val tempoBpm: Int,
    val baseFreqs: List<Double>
) {
    ROMANTIC(
        titleBn = "রোমান্টিক ও সুন্দর সুর",
        titleEn = "Beautiful & Romantic",
        subtitleBn = "মিষ্টি পিয়ানো ও অ্যাকোস্টিক হারমনি",
        tempoBpm = 88,
        baseFreqs = listOf(261.63, 329.63, 392.00, 493.88, 523.25, 392.00, 329.63, 293.66)
    ),
    SAD(
        titleBn = "স্যাড সং ও আবেগী সুর",
        titleEn = "Sad & Emotional",
        subtitleBn = "ধীরগতির মেলানকোলিক ভায়োলিন ও পিয়ানো",
        tempoBpm = 64,
        baseFreqs = listOf(220.00, 261.63, 329.63, 440.00, 392.00, 349.23, 329.63, 246.94)
    ),
    UPBEAT(
        titleBn = "এনার্জেটিক ও পার্টি বিট",
        titleEn = "Upbeat & Energetic",
        subtitleBn = "রঙিন সিন্থ ও রিদমিক পালস",
        tempoBpm = 124,
        baseFreqs = listOf(293.66, 369.99, 440.00, 587.33, 523.25, 440.00, 369.99, 329.63)
    ),
    LOFI(
        titleBn = "লো-ফাই চিল ও রিলাক্স",
        titleEn = "Lo-Fi Chill & Focus",
        subtitleBn = "নরম অ্যাম্বিয়েন্ট কর্ড ও শান্ত বিট",
        tempoBpm = 76,
        baseFreqs = listOf(174.61, 220.00, 261.63, 329.63, 293.66, 261.63, 220.00, 196.00)
    ),
    CLASSICAL(
        titleBn = "ক্লাসিক্যাল ও রবীন্দ্র মেজাজ",
        titleEn = "Eastern Classical & Melodic",
        subtitleBn = "শান্ত রাগ-অনুপ্রাণিত সরোদ ও বাঁশির আবহ",
        tempoBpm = 72,
        baseFreqs = listOf(261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 440.00, 392.00)
    );

    companion object {
        fun fromString(value: String?): MusicMood {
            if (value.isNullOrBlank()) return ROMANTIC
            val upper = value.uppercase()
            return entries.firstOrNull { it.name == upper }
                ?: when {
                    upper.contains("SAD") || upper.contains("মন খারাপ") || upper.contains("কষ্ট") -> SAD
                    upper.contains("PARTY") || upper.contains("FAST") || upper.contains("DANCE") || upper.contains("UPBEAT") -> UPBEAT
                    upper.contains("LOFI") || upper.contains("CHILL") || upper.contains("SLEEP") -> LOFI
                    upper.contains("CLASSIC") || upper.contains("RABINDRA") || upper.contains("FOLK") -> CLASSICAL
                    else -> ROMANTIC
                }
        }
    }
}

data class CuratedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val mood: MusicMood,
    val language: String,
    val youtubeQuery: String
)

data class AgentStep(
    val id: String,
    val actionType: AgentActionType,
    val title: String,
    val description: String,
    val query: String = "",
    val mood: String = "ROMANTIC",
    val artist: String = "",
    val recipient: String = "",
    val subject: String = "",
    val body: String = "",
    val phoneNumber: String = "",
    val settingTarget: String = "",
    val settingEnabled: Boolean = true,
    val volumeStream: String = "MUSIC",
    val volumePercent: Int = 70,
    val ringerMode: String = "NORMAL",
    val flashlightOn: Boolean = false,
    val alarmHour: Int = 7,
    val alarmMinute: Int = 0,
    val timerSeconds: Int = 60,
    val appName: String = "",
    val targetUrlOrFile: String = "",
    val screenControlCommand: String = "", // CLICK, TYPE, SCROLL_DOWN, SCROLL_UP, BACK, HOME, READ_SCREEN, QUICK_SETTINGS, TAP_XY
    val codeFilename: String = "",
    val codeLanguage: String = "",
    val isExecuted: Boolean = false,
    val executionResult: String = ""
)

data class AgentPlanResponse(
    val id: String,
    val userPrompt: String,
    val spokenReply: String,
    val summaryTitle: String,
    val steps: List<AgentStep>,
    val autoLearnedMemory: String = "",
    val modelUsed: String = "gemini-4-argon",
    val triggeredByWakeWord: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class SmartRoutine(
    val id: String,
    val titleBn: String,
    val subtitleBn: String,
    val iconName: String,
    val accentHex: Long,
    val promptCommand: String,
    val presetSteps: List<AgentStep>
)

enum class AgentVoiceGender(val labelBn: String) {
    FEMALE("ফিমেইল ভয়েস (Female)"),
    MALE("মেইল ভয়েস (Male / Existing)")
}

enum class AgentVoicePreset(
    val id: String,
    val liveVoiceName: String,
    val titleBn: String,
    val subtitleBn: String,
    val gender: AgentVoiceGender,
    val ttsPitch: Float,
    val ttsRate: Float
) {
    // ── ALL FEMALE VOICES (ফিমেইল যত ধরনের ভয়েস আছে) ──
    AOEDE_FEMALE(
        id = "AOEDE_FEMALE",
        liveVoiceName = "Aoede",
        titleBn = "Aoede • জেমিনি মেইন ফিমেইল",
        subtitleBn = "Gemini Assistant-এর ডিফল্ট উষ্ণ, প্রাণবন্ত ও স্বাভাবিক নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.08f,
        ttsRate = 1.00f
    ),
    KORE_FEMALE(
        id = "KORE_FEMALE",
        liveVoiceName = "Kore",
        titleBn = "Kore • স্মার্ট ও শান্ত ফিমেইল",
        subtitleBn = "পরিষ্কার, বুদ্ধিদীপ্ত ও প্রফেশনাল নারী অ্যাসিস্ট্যান্ট কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.04f,
        ttsRate = 0.99f
    ),
    LEDA_FEMALE(
        id = "LEDA_FEMALE",
        liveVoiceName = "Leda",
        titleBn = "Leda • মিষ্টি ও কোমল ফিমেইল",
        subtitleBn = "স্নেহময়, মিষ্টি ও ঘরোয়া সুরের নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.14f,
        ttsRate = 0.97f
    ),
    ZEPHYR_FEMALE(
        id = "ZEPHYR_FEMALE",
        liveVoiceName = "Zephyr",
        titleBn = "Zephyr • প্রাণবন্ত ও চটপটে ফিমেইল",
        subtitleBn = "এনার্জেটিক, হাসিখুশি ও তরুণী কণ্ঠস্বর",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.16f,
        ttsRate = 1.03f
    ),
    CALLIRRHOE_FEMALE(
        id = "CALLIRRHOE_FEMALE",
        liveVoiceName = "Callirrhoe",
        titleBn = "Callirrhoe • সুরেলা ও স্নিগ্ধ ফিমেইল",
        subtitleBn = "শান্ত, মধুর ও মিষ্টি বাচনভঙ্গির নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.10f,
        ttsRate = 0.96f
    ),
    AUTONOE_FEMALE(
        id = "AUTONOE_FEMALE",
        liveVoiceName = "Autonoe",
        titleBn = "Autonoe • এক্সিকিউটিভ লেডি বস",
        subtitleBn = "আত্মবিশ্বাসী, স্পষ্ট ও কর্পোরেট নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.02f,
        ttsRate = 1.01f
    ),
    DESPINA_FEMALE(
        id = "DESPINA_FEMALE",
        liveVoiceName = "Despina",
        titleBn = "Despina • নরম ও মায়াবী ফিমেইল",
        subtitleBn = "অত্যন্ত নরম, সহানুভূতিশীল ও যত্নশীল নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.12f,
        ttsRate = 0.95f
    ),
    ERINOME_FEMALE(
        id = "ERINOME_FEMALE",
        liveVoiceName = "Erinome",
        titleBn = "Erinome • ক্রিস্টাল স্টুডিও ফিমেইল",
        subtitleBn = "নিউজ প্রেজেন্টারদের মতো একদম স্পষ্ট ও ঝকঝকে নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.06f,
        ttsRate = 1.00f
    ),
    LAOMEDEIA_FEMALE(
        id = "LAOMEDEIA_FEMALE",
        liveVoiceName = "Laomedeia",
        titleBn = "Laomedeia • বন্ধুসুলভ আড্ডার ফিমেইল",
        subtitleBn = "কাছের বন্ধুর মতো ঘরোয়া ও প্রাণখোলা নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 1.11f,
        ttsRate = 1.02f
    ),
    VINDEMIATRIX_FEMALE(
        id = "VINDEMIATRIX_FEMALE",
        liveVoiceName = "Vindemiatrix",
        titleBn = "Vindemiatrix • ধীরস্থির ও গম্ভীর ফিমেইল",
        subtitleBn = "শান্ত, পরিণত ও গভীর প্রজ্ঞাময় নারী কণ্ঠ",
        gender = AgentVoiceGender.FEMALE,
        ttsPitch = 0.98f,
        ttsRate = 0.96f
    ),

    // ── EXISTING & MALE VOICES (আসল মানুষের মতো ক্লিন ও স্পষ্ট মেইন ছেলে ভয়েস) ──
    CHARON_MALE(
        id = "CHARON_MALE",
        liveVoiceName = "Charon",
        titleBn = "Charon • মেইন ছেলে ভয়েস (আসল মানুষের মতো স্পষ্ট)",
        subtitleBn = "কোনো রোবটিক ভাব ছাড়া একদম ক্লিন, স্পষ্ট ও প্রাকৃতিক পুরুষ কণ্ঠ (ডিফল্ট)",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 1.00f,
        ttsRate = 1.00f
    ),
    PUCK_MALE(
        id = "PUCK_MALE",
        liveVoiceName = "Puck",
        titleBn = "Puck • ন্যাচারাল স্মার্ট ছেলে ভয়েস",
        subtitleBn = "আসল মানুষের মতো প্রাণবন্ত, পরিষ্কার ও চটপটে তরুণ পুরুষ কণ্ঠ",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 1.00f,
        ttsRate = 1.00f
    ),
    FENRIR_MALE(
        id = "FENRIR_MALE",
        liveVoiceName = "Fenrir",
        titleBn = "Fenrir • সিরিয়াস প্রফেশনাল মেল",
        subtitleBn = "দৃঢ়, ফোকাসড ও কমান্ডিং এক্সিকিউটিভ পুরুষ কণ্ঠ",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 0.96f,
        ttsRate = 1.00f
    ),
    ORUS_MALE(
        id = "ORUS_MALE",
        liveVoiceName = "Orus",
        titleBn = "Orus • ট্যাকটিক্যাল সাইবার কমান্ডার",
        subtitleBn = "ভারী, রেজোন্যান্ট ও হাই-টেক কমান্ড সেন্টার পুরুষ কণ্ঠ",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 0.94f,
        ttsRate = 0.98f
    ),
    IAPETUS_MALE(
        id = "IAPETUS_MALE",
        liveVoiceName = "Iapetus",
        titleBn = "Iapetus • ক্লিন স্টুডিও মেল ভয়েস",
        subtitleBn = "ঝকঝকে, স্পষ্ট ও স্বাভাবিক পুরুষ কণ্ঠ",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 1.00f,
        ttsRate = 1.00f
    ),
    SCHEDAR_MALE(
        id = "SCHEDAR_MALE",
        liveVoiceName = "Schedar",
        titleBn = "Schedar • উষ্ণ ব্যারিটোন মেল ভয়েস",
        subtitleBn = "ভরাট, শান্ত ও সৌজন্যমূলক পুরুষ কণ্ঠ",
        gender = AgentVoiceGender.MALE,
        ttsPitch = 0.98f,
        ttsRate = 0.99f
    );

    val isFemale: Boolean
        get() = gender == AgentVoiceGender.FEMALE

    companion object {
        fun fromIdOrVoiceName(value: String?): AgentVoicePreset {
            if (value.isNullOrBlank()) return CHARON_MALE
            val clean = value.trim()
            return entries.firstOrNull {
                it.id.equals(clean, ignoreCase = true) ||
                    it.liveVoiceName.equals(clean, ignoreCase = true)
            } ?: when {
                clean.contains("kore", ignoreCase = true) ||
                    clean.contains("কোর", ignoreCase = true) -> KORE_FEMALE
                clean.contains("aoede", ignoreCase = true) ||
                    clean.contains("female", ignoreCase = true) ||
                    clean.contains("মেয়ে", ignoreCase = true) ||
                    clean.contains("মেয়ে", ignoreCase = true) ||
                    clean.contains("ফিমেইল", ignoreCase = true) ||
                    clean.contains("নারী", ignoreCase = true) -> AOEDE_FEMALE
                clean.contains("puck", ignoreCase = true) ||
                    clean.contains("পাক", ignoreCase = true) ||
                    clean.contains("তরুণ", ignoreCase = true) -> PUCK_MALE
                clean.contains("charon", ignoreCase = true) ||
                    clean.contains("ছেলে", ignoreCase = true) ||
                    clean.contains("পুরুষ", ignoreCase = true) ||
                    clean.contains("মেইন", ignoreCase = true) ||
                    clean.contains("male", ignoreCase = true) -> CHARON_MALE
                else -> CHARON_MALE
            }
        }
    }
}

enum class AgentPersonalityPreset(
    val id: String,
    val titleBn: String,
    val subtitleBn: String,
    val liveVoiceName: String,
    val systemPromptDirective: String
) {
    CORE_JARVIS_BOSS(
        id = "CORE_JARVIS_BOSS",
        titleBn = "স্মার্ট ও প্রো-অ্যাকটিভ বস অ্যাসিস্ট্যান্ট (মেইন আর্চার V3 ব্রেইন)",
        subtitleBn = "এই API-এর মেইন পার্সোনালিটি • নিজে বুদ্ধি খাটিয়ে স্ক্রিন দেখে কাজ ও কনফার্ম করে",
        liveVoiceName = "Charon",
        systemPromptDirective = """
            ACTIVE PERSONALITY: ARCHER V3 AUTONOMOUS BRAIN & PROACTIVE BOSS ASSISTANT (JARVIS / OMNIAGENT V3 PERSONALITY).
            - You are the user's loyal, hyper-intelligent right-hand executive AI companion with your own autonomous reasoning.
            - Address the user respectfully as "স্যার" (or "Sir" in English). Never call the user "বস" or "Boss".
            - Speak naturally, warmly, and strictly to-the-point ("কাজের কথা") in 1–2 crisp spoken sentences.
            - You have continuous millisecond-level Multimodal Live Screen Vision: always verify the real screen state before and after every action, check permissions first, retry if an Install/Click button hasn't started the process yet, and proactively inform Sir as soon as the task is genuinely confirmed.
        """.trimIndent()
    ),
    GEMINI_FEMALE_ASSISTANT(
        id = "GEMINI_FEMALE_ASSISTANT",
        titleBn = "জেমিনি ফিমেইল অ্যাসিস্ট্যান্ট (Gemini Live Female)",
        subtitleBn = "আসল Gemini Assistant-এর মতো উষ্ণ, স্মার্ট ও প্রাণবন্ত ফিমেইল ভয়েস পার্সোনালিটি",
        liveVoiceName = "Aoede",
        systemPromptDirective = """
            ACTIVE PERSONALITY: OFFICIAL GEMINI LIVE FEMALE ASSISTANT (WARM, EXPRESSIVE & HYPER-RESPONSIVE).
            - Speak with the natural warmth, empathy, expressive intonation, and effortless intelligence of Google's flagship Gemini Live Assistant.
            - Address the user respectfully as "স্যার" (or "Sir" in English). Never call the user "বস" or "Boss".
            - Listen attentively to even the softest spoken words, speak naturally and directly about the user's exact task ("কাজের কথা") in 1–2 sentences, verify every action on the live screen, and proactively inform Sir when a task or installation is confirmed.
        """.trimIndent()
    ),
    CARING_LADY_COMPANION(
        id = "CARING_LADY_COMPANION",
        titleBn = "মিষ্টি ও যত্নশীল ফিমেইল সঙ্গী (Sweet Lady Soul)",
        subtitleBn = "স্নেহময়, কোমল ও আন্তরিক নারী কণ্ঠে সবসময় আপনার খেয়াল রাখবে",
        liveVoiceName = "Leda",
        systemPromptDirective = """
            ACTIVE PERSONALITY: SWEET, CARING & EMPATHETIC FEMALE COMPANION.
            - Speak in a gentle, sweet, caring, and deeply supportive tone while respectfully addressing the user as "স্যার".
            - Be attentive to the user's mood, comfort, and convenience while performing every mobile task seamlessly.
        """.trimIndent()
    ),
    SMART_LADY_SECRETARY(
        id = "SMART_LADY_SECRETARY",
        titleBn = "স্মার্ট লেডি সেক্রেটারি (Executive Lady Pro)",
        subtitleBn = "আত্মবিশ্বাসী ও গোছানো ফিমেইল পার্সোনাল সেক্রেটারি (Kore Voice)",
        liveVoiceName = "Kore",
        systemPromptDirective = """
            ACTIVE PERSONALITY: SHARP & ARTICULATE FEMALE EXECUTIVE SECRETARY.
            - Speak clearly, politely, and efficiently like a top-tier personal executive secretary for "স্যার".
            - Keep schedules, timers, app operations, and messages organized and report status crisply.
        """.trimIndent()
    ),
    DESI_BONDHU(
        id = "DESI_BONDHU",
        titleBn = "বন্ধুসুলভ দেশি দোস্ত (Desi Friend)",
        subtitleBn = "আন্তরিক, ঘরোয়া ও প্রাণবন্ত আড্ডার মেজাজে কথা বলবে",
        liveVoiceName = "Puck",
        systemPromptDirective = """
            ACTIVE PERSONALITY: WARM DESI BEST FRIEND & COMPANION.
            - Speak in a super warm, friendly, relatable Bangladeshi/Desi conversational style while still addressing the user respectfully as "স্যার".
            - Be lively, encouraging, and natural like a close companion sitting right next to the user.
        """.trimIndent()
    ),
    EXECUTIVE_PRO(
        id = "EXECUTIVE_PRO",
        titleBn = "সিরিয়াস প্রফেশনাল (Executive Pro)",
        subtitleBn = "একদম সংক্ষিপ্ত, ফোকাসড ও দ্রুত কাজের রিপোর্ট দেবে",
        liveVoiceName = "Fenrir",
        systemPromptDirective = """
            ACTIVE PERSONALITY: ULTRA-CONCISE EXECUTIVE PROFESSIONAL.
            - Be razor-sharp, formal, and strictly to-the-point.
            - Keep spoken responses under 1 short sentence, focusing 100% on immediate execution and status reporting for "স্যার".
        """.trimIndent()
    ),
    WITTY_HUMOROUS(
        id = "WITTY_HUMOROUS",
        titleBn = "মজার ও রসিক সঙ্গী (Witty & Fun)",
        subtitleBn = "চটপটে, বুদ্ধিদীপ্ত রসিকতা ও হাসিখুশি কথাবার্তা",
        liveVoiceName = "Zephyr",
        systemPromptDirective = """
            ACTIVE PERSONALITY: WITTY, CLEVER & HUMOROUS COMPANION.
            - Bring light-hearted wit, clever humor, and cheerful energy to every conversation with "স্যার" while executing phone tasks accurately.
        """.trimIndent()
    ),
    TACTICAL_SENTINEL(
        id = "TACTICAL_SENTINEL",
        titleBn = "ট্যাকটিক্যাল সাইবার কমান্ডার (Cyber Sentinel)",
        subtitleBn = "হাই-টেক সিকিউরিটি ও কমান্ড সেন্টার স্টাইল",
        liveVoiceName = "Orus",
        systemPromptDirective = """
            ACTIVE PERSONALITY: TACTICAL CYBER-SECURITY & OS COMMAND SENTINEL.
            - Speak with futuristic tactical precision, monitoring system telemetry, apps, and screen operations for "স্যার".
        """.trimIndent()
    ),
    CODING_TECH_GURU(
        id = "CODING_TECH_GURU",
        titleBn = "কোডিং ও টেক গুরু (Argon-4 Architect)",
        subtitleBn = "সফটওয়্যার ইঞ্জিনিয়ারিং, অটোমেশন ও ডিপ টেক বিশেষজ্ঞ",
        liveVoiceName = "Charon",
        systemPromptDirective = """
            ACTIVE PERSONALITY: PRINCIPAL SOFTWARE ARCHITECT & TECH GURU.
            - Explain technical concepts, apps, websites, and code with deep mastery and clarity for "স্যার".
        """.trimIndent()
    ),
    CUSTOM(
        id = "CUSTOM",
        titleBn = "কাস্টম পার্সোনালিটি (Custom Soul)",
        subtitleBn = "আপনার নিজের মতো করে এজেন্টের স্বভাব ও কথা বলার ধরন সাজান",
        liveVoiceName = "Charon",
        systemPromptDirective = """
            ACTIVE PERSONALITY: CUSTOM USER-DEFINED PERSONALITY.
            - Follow the user's custom personality instructions and persistent rules faithfully while addressing the user as "স্যার".
        """.trimIndent()
    );

    companion object {
        fun fromId(id: String?): AgentPersonalityPreset {
            if (id.isNullOrBlank()) return CORE_JARVIS_BOSS
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: CORE_JARVIS_BOSS
        }
    }
}

data class ProactiveWatchTask(
    val id: String,
    val type: String, // "TIMER", "SCREEN_LOADING", "DOWNLOAD_WATCH"
    val labelBn: String,
    val targetSeconds: Int = 0,
    val createdAtMs: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false
)


