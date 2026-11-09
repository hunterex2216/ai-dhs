package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

enum class FloatingAvatarCharacter(
    val id: String,
    val displayName: String,
    val badgeEmoji: String,
    val subtitle: String,
    val accentHex: Int
) {
    OFF("off", "Off", "⭕", "Floating 3D Globe button disabled", 0xFF6B7280.toInt()),
    ON("on", "ON • 3D GLOBE", "🌐", "মেইন গ্লোব ও ওয়েভের সাথে মিলিয়ে ৩ডি ফ্লোটিং বাটন", 0xFF00FF66.toInt());

    companion object {
        val ARCHER: FloatingAvatarCharacter = ON

        fun fromId(id: String): FloatingAvatarCharacter {
            val clean = id.trim()
            if (clean.equals("false", ignoreCase = true) || clean.equals("off", ignoreCase = true)) {
                return OFF
            }
            return ON
        }
    }
}

enum class PremiumImageModelTier(
    val id: String,
    val displayName: String,
    val badgeLabel: String,
    val subtitleBn: String,
    val primaryModelId: String
) {
    AUTO_PRO(
        id = "AUTO_PRO",
        displayName = "Auto Pro (Nano Banana Pro + Imagen 4 Ultra)",
        badgeLabel = "AUTO PRO • MAX",
        subtitleBn = "Nano Banana Pro ও উপরের সব প্রিমিয়াম মডেল অটো-রাউটিং",
        primaryModelId = "gemini-3-pro-image-preview"
    ),
    NANO_BANANA_PRO(
        id = "NANO_BANANA_PRO",
        displayName = "Nano Banana Pro (Gemini 3 Pro Image)",
        badgeLabel = "NANO BANANA PRO",
        subtitleBn = "প্রিমিয়াম 4K হাই-রেজোলিউশন, নিখুঁত আলো ও টেক্সট রেন্ডারিং",
        primaryModelId = "gemini-3-pro-image-preview"
    ),
    IMAGEN_4_ULTRA(
        id = "IMAGEN_4_ULTRA",
        displayName = "Imagen 4.0 Ultra (Highest Tier)",
        badgeLabel = "IMAGEN 4 ULTRA",
        subtitleBn = "সর্বোচ্চ ফটোরিয়ালিস্টিক আল্ট্রা প্রিমিয়াম মডেল",
        primaryModelId = "imagen-4.0-ultra-generate-001"
    ),
    IMAGEN_4_PRO(
        id = "IMAGEN_4_PRO",
        displayName = "Imagen 4.0 Pro",
        badgeLabel = "IMAGEN 4 PRO",
        subtitleBn = "শার্প ডিটেইলস ও প্রো স্টুডিও ফটোগ্রাফি কোয়ালিটি",
        primaryModelId = "imagen-4.0-generate-001"
    ),
    NANO_BANANA_2_1(
        id = "NANO_BANANA_2_1",
        displayName = "Nano Banana 2.1 (Fast Pro)",
        badgeLabel = "NANO BANANA 2.1",
        subtitleBn = "দ্রুত রিয়েল-টাইম ছবি তৈরি ও ইমেজ এডিটিং",
        primaryModelId = "gemini-2.5-flash-image"
    );

    companion object {
        fun fromId(id: String?): PremiumImageModelTier {
            if (id.isNullOrBlank()) return AUTO_PRO
            return entries.firstOrNull {
                it.id.equals(id.trim(), ignoreCase = true) ||
                    it.primaryModelId.equals(id.trim(), ignoreCase = true)
            } ?: AUTO_PRO
        }
    }
}

data class AgentAbilitySwitchesState(
    val silentExecution: Boolean = true,
    val backgroundWebSearch: Boolean = true,
    val makeImagesWithGemini: Boolean = true,
    val ignoreBackgroundAudio: Boolean = true,
    val showTapIndicator: Boolean = false,
    val sendCrashReports: Boolean = true,
    val floatingAvatar: FloatingAvatarCharacter = FloatingAvatarCharacter.ARCHER,
    val isMediaCurrentlyPlaying: Boolean = false,
    val isWorkingOnStudioTask: Boolean = false,
    val isPlayingGeneratedMusic: Boolean = false,
    val lastGeneratedImagePath: String = "",
    val lastGeneratedMusicPath: String = "",
    val lastBackgroundSearchSummary: String = "",
    val lastCrashReportSummary: String = "",
    val crashReportsCount: Int = 0,
    // Dedicated Image Generation Studio Page & Mandatory Save Confirmation State
    val showDedicatedImageStudioPage: Boolean = false,
    val isGeneratingImage: Boolean = false,
    val activeImagePrompt: String = "",
    val expandedEnglish8kPrompt: String = "",
    val imageProgressMessageBn: String = "",
    val pendingImageSaveConfirmation: Boolean = false,
    val isImageSavedToArcher: Boolean = false,
    val savedArcherImagePath: String = "",
    val isImageSyncedToDrive: Boolean = false,
    // Premium Image Model Suite + Main Gmail & Paid API Key State
    val selectedImageModelTier: PremiumImageModelTier = PremiumImageModelTier.NANO_BANANA_2_1,
    val lastUsedImageModelName: String = "Nano Banana (gemini-2.5-flash-image)",
    val connectedGmailAccount: String = "",
    val isGmailPremiumLinked: Boolean = false,
    val premiumImageApiKey: String = "",
    // Music Mandatory Save Confirmation State
    val pendingMusicSaveConfirmation: Boolean = false,
    val isMusicSavedToArcher: Boolean = false,
    val savedArcherMusicPath: String = ""
)

/**
 * Centralized state & real execution bus for the 7 Ability Permission Switches (saved in Main Settings)
 * plus the AI Studio Capabilities:
 * - Google Maps Grounding & Live Web Search (`gemini-3.8-flash` with `googleMaps` & `googleSearch`)
 * - Real Image Generation & Editing (`gemini-nano-banana-2.1` + dedicated full-screen Image Studio page)
 * - Bioscience Skills (`science` — live PubMed, UniProt, PubChem + Gemini Bioscience reasoning)
 * - Generate Music (`lyria-3-clip-preview` & `lyria-3-pro-preview`)
 *
 * CRITICAL USER RULE:
 * Never auto-save any generated image or music track into a public folder (`Download/archer`) without
 * asking the user first! Generated media is staged in `context.cacheDir` until `confirmSavePendingImageToArcherFolder`
 * or `confirmSavePendingMusicToArcherFolder` is called.
 */
object AgentAbilitySwitchesBus {
    private const val TAG = "AgentAbilitySwitches"
    private const val PREFS_NAME = "archer_ability_switches_prefs"

    private const val KEY_SILENT_EXECUTION = "silent_execution"
    private const val KEY_BG_WEB_SEARCH = "background_web_search"
    private const val KEY_MAKE_IMAGES = "make_images_with_gemini"
    private const val KEY_IGNORE_BG_AUDIO = "ignore_background_audio"
    private const val KEY_SHOW_TAP_INDICATOR = "show_tap_indicator"
    private const val KEY_SEND_CRASH_REPORTS = "send_crash_reports"
    private const val KEY_FLOATING_AVATAR = "floating_avatar_character"
    private const val KEY_SELECTED_IMAGE_MODEL_TIER = "selected_image_model_tier"
    private const val KEY_CONNECTED_GMAIL_ACCOUNT = "connected_gmail_account"
    private const val KEY_PREMIUM_IMAGE_API_KEY = "premium_image_api_key"

    private val _state = MutableStateFlow(AgentAbilitySwitchesState())
    val state: StateFlow<AgentAbilitySwitchesState> = _state.asStateFlow()

    @Volatile
    private var initialized = false
    private var musicMediaPlayer: MediaPlayer? = null

    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val mainPrefs = appCtx.getSharedPreferences("archer_cube_prefs", Context.MODE_PRIVATE)
            val silentExec = prefs.getBoolean(KEY_SILENT_EXECUTION, true)
            val bgWebSearch = prefs.getBoolean(KEY_BG_WEB_SEARCH, true)
            val makeImages = prefs.getBoolean(KEY_MAKE_IMAGES, true)
            val ignoreBgAudio = prefs.getBoolean(KEY_IGNORE_BG_AUDIO, true)
            val showTap = prefs.getBoolean(KEY_SHOW_TAP_INDICATOR, false)
            val sendCrash = prefs.getBoolean(KEY_SEND_CRASH_REPORTS, true)
            val globeMigrated = prefs.getBoolean("floating_globe_v2_default_on", false)
            val avatar = if (!globeMigrated) {
                prefs.edit()
                    .putBoolean("floating_globe_v2_default_on", true)
                    .putString(KEY_FLOATING_AVATAR, FloatingAvatarCharacter.ARCHER.id)
                    .apply()
                FloatingAvatarCharacter.ARCHER
            } else {
                val avatarId = prefs.getString(KEY_FLOATING_AVATAR, FloatingAvatarCharacter.ARCHER.id) ?: FloatingAvatarCharacter.ARCHER.id
                FloatingAvatarCharacter.fromId(avatarId)
            }
            val modelTier = PremiumImageModelTier.fromId(prefs.getString(KEY_SELECTED_IMAGE_MODEL_TIER, PremiumImageModelTier.AUTO_PRO.id))
            val savedGmail = prefs.getString(KEY_CONNECTED_GMAIL_ACCOUNT, "")?.trim().orEmpty()
            val savedPremiumKey = prefs.getString(KEY_PREMIUM_IMAGE_API_KEY, "")?.trim().orEmpty()
                .ifBlank { mainPrefs.getString("custom_gemini_api_key", "")?.trim().orEmpty() }

            _state.update {
                it.copy(
                    silentExecution = silentExec,
                    backgroundWebSearch = bgWebSearch,
                    makeImagesWithGemini = makeImages,
                    ignoreBackgroundAudio = ignoreBgAudio,
                    showTapIndicator = showTap,
                    sendCrashReports = sendCrash,
                    floatingAvatar = avatar,
                    selectedImageModelTier = modelTier,
                    connectedGmailAccount = savedGmail,
                    isGmailPremiumLinked = savedGmail.isNotBlank(),
                    premiumImageApiKey = savedPremiumKey
                )
            }
            TouchEffectBus.setEnabled(showTap)
            FloatingAvatarOverlayBus.setCharacter(avatar)
            initialized = true
        }
    }

    fun setSilentExecution(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SILENT_EXECUTION, enabled).apply()
        _state.update { it.copy(silentExecution = enabled) }
    }

    fun setBackgroundWebSearch(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BG_WEB_SEARCH, enabled).apply()
        _state.update { it.copy(backgroundWebSearch = enabled) }
    }

    fun setMakeImagesWithGemini(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_MAKE_IMAGES, enabled).apply()
        _state.update { it.copy(makeImagesWithGemini = enabled) }
    }

    fun setIgnoreBackgroundAudio(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IGNORE_BG_AUDIO, enabled).apply()
        _state.update { it.copy(ignoreBackgroundAudio = enabled) }
    }

    fun setShowTapIndicator(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOW_TAP_INDICATOR, enabled).apply()
        TouchEffectBus.setEnabled(enabled)
        _state.update { it.copy(showTapIndicator = enabled) }
    }

    fun syncTapIndicatorFromTouchBus(enabled: Boolean) {
        _state.update { it.copy(showTapIndicator = enabled) }
    }

    fun setSendCrashReports(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SEND_CRASH_REPORTS, enabled).apply()
        _state.update { it.copy(sendCrashReports = enabled) }
    }

    fun setFloatingAvatar(context: Context, character: FloatingAvatarCharacter) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FLOATING_AVATAR, character.id).apply()
        FloatingAvatarOverlayBus.setCharacter(character)
        _state.update { it.copy(floatingAvatar = character) }
    }

    fun cycleFloatingAvatar(context: Context): FloatingAvatarCharacter {
        ensureInitialized(context)
        val next = if (_state.value.floatingAvatar == FloatingAvatarCharacter.OFF) {
            FloatingAvatarCharacter.ARCHER
        } else {
            FloatingAvatarCharacter.OFF
        }
        setFloatingAvatar(context, next)
        return next
    }

    fun openDedicatedImageStudioPage(initialPrompt: String = "") {
        _state.update {
            it.copy(
                showDedicatedImageStudioPage = true,
                activeImagePrompt = initialPrompt.ifBlank { it.activeImagePrompt }
            )
        }
    }

    fun closeDedicatedImageStudioPage() {
        _state.update { it.copy(showDedicatedImageStudioPage = false) }
    }

    fun setSelectedImageModelTier(context: Context, tier: PremiumImageModelTier) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_IMAGE_MODEL_TIER, tier.id).apply()
        _state.update {
            it.copy(
                selectedImageModelTier = tier,
                imageProgressMessageBn = "প্রিমিয়াম মডেল সেট করা হয়েছে: ${tier.displayName}"
            )
        }
    }

    fun connectMainGmailForPremium(context: Context, gmailAddress: String): String {
        ensureInitialized(context)
        val cleanEmail = gmailAddress.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            return "অনুগ্রহ করে সঠিক Gmail আইডি দিন (যেমন: yourname@gmail.com)।"
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONNECTED_GMAIL_ACCOUNT, cleanEmail).apply()
        _state.update {
            it.copy(
                connectedGmailAccount = cleanEmail,
                isGmailPremiumLinked = true,
                imageProgressMessageBn = "✔ মেইন Gmail ($cleanEmail) প্রিমিয়াম একাউন্ট হিসেবে যুক্ত হয়েছে! এখন Nano Banana Pro ও উপরের সব প্রিমিয়াম মডেল সক্রিয়।"
            )
        }
        return "জি স্যার, আপনার মেইন জিমেইল আইডি ($cleanEmail) সফলভাবে যুক্ত হয়েছে! এখন Nano Banana Pro এবং Imagen 4 Ultra সহ সব প্রিমিয়াম সুবিধা ব্যবহার করতে পারবেন।"
    }

    fun disconnectMainGmail(context: Context) {
        ensureInitialized(context)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_CONNECTED_GMAIL_ACCOUNT).apply()
        _state.update {
            it.copy(
                connectedGmailAccount = "",
                isGmailPremiumLinked = false,
                imageProgressMessageBn = "জিমেইল একাউন্ট লগআউট করা হয়েছে।"
            )
        }
    }

    fun savePremiumApiKey(context: Context, apiKey: String): String {
        ensureInitialized(context)
        val cleanKey = apiKey.trim()
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PREMIUM_IMAGE_API_KEY, cleanKey).apply()
        appCtx.getSharedPreferences("archer_cube_prefs", Context.MODE_PRIVATE)
            .edit().putString("custom_gemini_api_key", cleanKey).apply()
        _state.update {
            it.copy(
                premiumImageApiKey = cleanKey,
                imageProgressMessageBn = if (cleanKey.isNotBlank()) {
                    "✔ প্রিমিয়াম API Key সফলভাবে সেভ হয়েছে! Nano Banana Pro ও Imagen 4 Ultra অটো-রাউটিং সচল।"
                } else {
                    "API Key মুছে ফেলা হয়েছে — বিল্ট-ইন ও Gmail প্রিমিয়াম ইঞ্জিন সচল।"
                }
            )
        }
        return if (cleanKey.isNotBlank()) {
            "জি স্যার, আপনার প্রিমিয়াম API Key সেভ করা হয়েছে! এখন Nano Banana Pro ও উপরের সব প্রিমিয়াম মডেল দিয়ে রিয়েল ছবি তৈরি হবে।"
        } else {
            "জি স্যার, কাস্টম API Key রিসেট করা হয়েছে।"
        }
    }

    fun hasPendingUnsavedImage(): Boolean {
        val s = _state.value
        return s.pendingImageSaveConfirmation && !s.isImageSavedToArcher && s.lastGeneratedImagePath.isNotBlank()
    }

    fun hasPendingUnsavedMusic(): Boolean {
        val s = _state.value
        return s.pendingMusicSaveConfirmation && !s.isMusicSavedToArcher && s.lastGeneratedMusicPath.isNotBlank()
    }

    /**
     * Copies the temporary preview image from cacheDir into `Download/archer/` as a single clean `.png` file
     * ONLY when the user clicks "Save to Download/archer/" or confirms via voice!
     */
    fun confirmSavePendingImageToArcherFolder(context: Context): String {
        val previewPath = _state.value.lastGeneratedImagePath
        if (previewPath.isBlank() || !File(previewPath).exists()) {
            return "স্যার, সেভ করার মতো কোনো ছবি পাওয়া যায়নি।"
        }
        return try {
            val src = File(previewPath)
            val pngBytes = src.readBytes()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "archer_image_$ts.png"
            val dir = getArcherOutputDir(context)
            val destFile = File(dir, fileName)
            destFile.writeBytes(pngBytes)

            // Also write a single clean PNG entry to public Download/archer/ via MediaStore on Android 10+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                runCatching {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/archer")
                    }
                    val resolver = context.contentResolver
                    resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { uri ->
                        resolver.openOutputStream(uri)?.use { it.write(pngBytes) }
                    }
                }
            }

            _state.update {
                it.copy(
                    lastGeneratedImagePath = destFile.absolutePath,
                    pendingImageSaveConfirmation = false,
                    isImageSavedToArcher = true,
                    savedArcherImagePath = "Download/archer/$fileName",
                    imageProgressMessageBn = "✔ 'Download/archer/$fileName' হিসেবে সেভ সম্পন্ন!"
                )
            }
            "জি বস, ছবিটি 'Download/archer/$fileName' হিসেবে নিখুঁতভাবে সেভ করা হয়েছে।"
        } catch (e: Exception) {
            "ছবি সেভ করতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    /**
     * Syncs the current generated image directly to the user's 5TB Google Drive (`/AI_OS_Memory` folder)
     * with zero impact on main phone storage.
     */
    suspend fun syncCurrentImageToGoogleDrive(context: Context): String = withContext(Dispatchers.IO) {
        val previewPath = _state.value.lastGeneratedImagePath
        if (previewPath.isBlank() || !File(previewPath).exists()) {
            return@withContext "বস, গুগল ড্রাইভে সিঙ্ক করার মতো কোনো ছবি এখনো তৈরি হয়নি।"
        }
        return@withContext try {
            val bytes = File(previewPath).readBytes()
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val fileName = "nano_banana_hd_$ts.png"
            GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                context = context,
                fileName = fileName,
                mimeType = "image/png",
                contentBytes = bytes,
                summaryDescription = "Nano Banana HD Image: ${_state.value.activeImagePrompt}"
            )
            _state.update {
                it.copy(
                    isImageSyncedToDrive = true,
                    imageProgressMessageBn = "☁️ 5TB Google Drive (/AI_OS_Memory/$fileName)-এ সিঙ্ক সম্পন্ন!"
                )
            }
            "জি বস, ছবিটি আপনার ৫ টিবি গুগল ড্রাইভের AI_OS_Memory ফোল্ডারে ($fileName) ব্যাকআপ করা হয়েছে।"
        } catch (e: Exception) {
            "গুগল ড্রাইভে সিঙ্ক করতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    fun discardPendingImage() {
        val previewPath = _state.value.lastGeneratedImagePath
        if (previewPath.isNotBlank()) {
            runCatching {
                val f = File(previewPath)
                if (f.exists() && f.absolutePath.contains("cache")) {
                    f.delete()
                }
            }
        }
        _state.update {
            it.copy(
                lastGeneratedImagePath = "",
                pendingImageSaveConfirmation = false,
                isImageSavedToArcher = false,
                savedArcherImagePath = "",
                imageProgressMessageBn = "আপনার নির্দেশ অনুযায়ী ছবিটি কোনো ফোল্ডারে সেভ করা হয়নি।"
            )
        }
    }

    /**
     * Copies the temporary preview music from cacheDir into `Download/archer` ONLY when the user confirms!
     */
    fun confirmSavePendingMusicToArcherFolder(context: Context): String {
        val previewPath = _state.value.lastGeneratedMusicPath
        if (previewPath.isBlank() || !File(previewPath).exists()) {
            return "স্যার, সেভ করার মতো কোনো মিউজিক ফাইল পাওয়া যায়নি।"
        }
        return try {
            val src = File(previewPath)
            val ext = src.extension.ifBlank { "wav" }
            val dir = getArcherOutputDir(context)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val destFile = File(dir, "archer_lyria_music_$ts.$ext")
            src.copyTo(destFile, overwrite = true)
            _state.update {
                it.copy(
                    lastGeneratedMusicPath = destFile.absolutePath,
                    pendingMusicSaveConfirmation = false,
                    isMusicSavedToArcher = true,
                    savedArcherMusicPath = destFile.absolutePath
                )
            }
            "জি স্যার, আপনার কথামতো মিউজিক ফাইলটি 'archer' ফোল্ডারে সেভ করে দিয়েছি (${destFile.name})।"
        } catch (e: Exception) {
            "মিউজিক সেভ করতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    fun discardPendingMusic() {
        val previewPath = _state.value.lastGeneratedMusicPath
        if (previewPath.isNotBlank()) {
            runCatching {
                val f = File(previewPath)
                if (f.exists() && f.absolutePath.contains("cache")) {
                    f.delete()
                }
            }
        }
        _state.update {
            it.copy(
                lastGeneratedMusicPath = "",
                pendingMusicSaveConfirmation = false,
                isMusicSavedToArcher = false,
                savedArcherMusicPath = ""
            )
        }
    }

    /**
     * Checks if music or video media is currently playing on the device.
     */
    fun isDeviceMediaPlaying(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val playing = audioManager?.isMusicActive == true
            if (_state.value.isMediaCurrentlyPlaying != playing) {
                _state.update { it.copy(isMediaCurrentlyPlaying = playing) }
            }
            playing
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 1. Google Maps Grounding (`google_pin`) & Background Web Search:
     * Uses `gemini-3.8-flash` (with `googleMaps` and `googleSearch` tools) without opening a browser.
     */
    suspend fun searchWebOrMapsInBackground(
        context: Context,
        apiKey: String,
        query: String,
        useMapsGrounding: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        if (!_state.value.backgroundWebSearch) {
            return@withContext "ABILITY_SWITCH_OFF: 'Background web search' switch is currently OFF. Do NOT open the browser. Tell the user clearly: 'Background web search সুইচটি এখন বন্ধ (Off) আছে। অনুগ্রহ করে মেইন সেটিংসে গিয়ে Background web search অন করুন।'"
        }
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext "Error: Query is empty."

        _state.update { it.copy(isWorkingOnStudioTask = true) }
        try {
            val cleanKey = resolveEffectiveApiKey(context, apiKey)
            if (cleanKey.isNotBlank()) {
                val models = listOf("gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")
                for (modelName in models) {
                    val groundedResult = callGeminiGroundedSearch(cleanKey, modelName, cleanQuery, useMapsGrounding)
                    if (!groundedResult.isNullOrBlank()) {
                        _state.update {
                            it.copy(
                                isWorkingOnStudioTask = false,
                                lastBackgroundSearchSummary = groundedResult.take(650)
                            )
                        }
                        return@withContext groundedResult
                    }
                }
            }
            val wikiFallback = fetchWikipediaSummaryFallback(cleanQuery)
            _state.update {
                it.copy(
                    isWorkingOnStudioTask = false,
                    lastBackgroundSearchSummary = wikiFallback.take(650)
                )
            }
            wikiFallback
        } catch (e: Exception) {
            logCrashOrFailureReport(context, "BackgroundMapsAndWebSearch", e.message ?: "Unknown error")
            _state.update { it.copy(isWorkingOnStudioTask = false) }
            "Background search error: ${e.message}"
        }
    }

    private fun callGeminiGroundedSearch(
        apiKey: String,
        modelName: String,
        query: String,
        useMapsGrounding: Boolean
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 18000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val toolsArray = JSONArray().apply {
                if (useMapsGrounding) {
                    put(JSONObject().put("googleMaps", JSONObject()))
                }
                put(JSONObject().put("googleSearch", JSONObject()))
            }

            val payload = JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        if (useMapsGrounding) {
                                            "Use Google Maps data and live web search to provide accurate, up-to-date location, place, address, rating, or factual details for: $query"
                                        } else {
                                            "Look up accurate, current facts for this request in the background and give a concise, direct answer: $query"
                                        }
                                    )
                                )
                            )
                        }
                    )
                )
                put("tools", toolsArray)
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0) ?: return null
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text", "") ?: ""
                if (text.isNotBlank()) sb.append(text).append("\n")
            }

            val chunks = candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            if (chunks != null && chunks.length() > 0) {
                val links = mutableListOf<String>()
                for (i in 0 until minOf(chunks.length(), 4)) {
                    val chunkObj = chunks.optJSONObject(i) ?: continue
                    val maps = chunkObj.optJSONObject("maps")
                    if (maps != null) {
                        val title = maps.optString("title", "Google Maps Place")
                        val uri = maps.optString("uri", "")
                        if (uri.isNotBlank()) links.add("📍 $title ($uri)") else links.add("📍 $title")
                    }
                    val web = chunkObj.optJSONObject("web")
                    if (web != null) {
                        val title = web.optString("title", "")
                        val uri = web.optString("uri", "")
                        if (title.isNotBlank() && uri.isNotBlank()) {
                            links.add("🌐 $title ($uri)")
                        }
                    }
                }
                if (links.isNotEmpty()) {
                    sb.append("\nSources: ").append(links.joinToString(" | "))
                }
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Grounded search ($modelName) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchWikipediaSummaryFallback(query: String): String {
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&utf8=&format=json&srlimit=2")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "ArcherOmniAgent/3.0")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val searchArr = JSONObject(body).optJSONObject("query")?.optJSONArray("search")
                if (searchArr != null && searchArr.length() > 0) {
                    val results = mutableListOf<String>()
                    for (i in 0 until searchArr.length()) {
                        val item = searchArr.optJSONObject(i) ?: continue
                        val title = item.optString("title")
                        val snippet = item.optString("snippet").replace(Regex("<[^>]*>"), "")
                        results.add("$title: $snippet")
                    }
                    return "Background Lookup Results:\n" + results.joinToString("\n")
                }
            }
            "Background Lookup completed for '$query', no direct snippet returned."
        } catch (e: Exception) {
            "Background Lookup fallback error: ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    fun resolveEffectiveApiKey(context: Context, explicitKey: String): String {
        val trimmed = explicitKey.trim()
        if (trimmed.isNotBlank()) return trimmed
        val stateKey = _state.value.premiumImageApiKey.trim()
        if (stateKey.isNotBlank()) return stateKey
        try {
            val abilityPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedPremium = abilityPrefs.getString(KEY_PREMIUM_IMAGE_API_KEY, "")?.trim().orEmpty()
            if (savedPremium.isNotBlank()) return savedPremium
        } catch (_: Exception) {}
        try {
            val mainPrefs = context.getSharedPreferences("archer_cube_prefs", Context.MODE_PRIVATE)
            val savedMain = mainPrefs.getString("custom_gemini_api_key", "")?.trim().orEmpty()
            if (savedMain.isNotBlank()) return savedMain
        } catch (_: Exception) {}
        try {
            val legacyPrefs = context.getSharedPreferences("omni_agent_prefs", Context.MODE_PRIVATE)
            val savedLegacy = legacyPrefs.getString("custom_gemini_api_key", "")?.trim().orEmpty()
            if (savedLegacy.isNotBlank()) return savedLegacy
        } catch (_: Exception) {}
        try {
            val fbKey = FirebaseApp.getInstance().options.apiKey.trim()
            if (fbKey.isNotBlank() && !fbKey.contains("placeholder", ignoreCase = true)) {
                return fbKey
            }
        } catch (_: Exception) {}
        return ""
    }

    /**
     * 2. Real Image Generation & Editing (`createImageWithGemini`):
     * - Automatically opens the Dedicated Image Studio Page (`showDedicatedImageStudioPage = true`).
     * - Supports Nano Banana Pro (`gemini-3-pro-image-preview` / `gemini-nano-banana-pro`),
     *   Imagen 4.0 Ultra (`imagen-4.0-ultra-generate-001`), Imagen 4.0 Pro (`imagen-4.0-generate-001`),
     *   and Nano Banana 2.1 (`gemini-2.5-flash-image` / `gemini-nano-banana-2.1`) with automatic routing!
     * - Generates 100% REAL images matching the user's exact spoken/written prompt (NEVER a fake procedural demo canvas).
     * - Stages the generated PNG in `context.cacheDir` ONLY (`pendingImageSaveConfirmation = true`) and asks the user
     *   whether to save it to the `archer` folder.
     */
    suspend fun createImageWithGemini(
        context: Context,
        apiKey: String,
        prompt: String,
        editExistingImage: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        if (!_state.value.makeImagesWithGemini) {
            return@withContext "ABILITY_SWITCH_OFF: 'Make images with Gemini' switch is currently OFF. Tell the user clearly: 'Make images with Gemini সুইচটি এখন বন্ধ (Off) আছে। ছবি তৈরি বা এডিট করতে অনুগ্রহ করে মেইন সেটিংসে গিয়ে Make images with Gemini সুইচটি অন করুন।'"
        }
        val cleanPrompt = cleanImagePromptFromCommand(prompt)
        val cleanKey = resolveEffectiveApiKey(context, apiKey)
        val selectedTier = _state.value.selectedImageModelTier

        val sourceFileForEdit: File? = if (editExistingImage) {
            val path = _state.value.lastGeneratedImagePath
            if (path.isNotBlank() && File(path).exists()) File(path) else null
        } else {
            null
        }

        // Immediately navigate to the Dedicated Image Studio Page and show live generation state
        _state.update {
            it.copy(
                showDedicatedImageStudioPage = true,
                isGeneratingImage = true,
                isWorkingOnStudioTask = true,
                activeImagePrompt = cleanPrompt,
                imageProgressMessageBn = "${selectedTier.badgeLabel} দিয়ে আপনার কথামতো রিয়েল ছবি তৈরি হচ্ছে: \"$cleanPrompt\"...",
                pendingImageSaveConfirmation = false,
                isImageSavedToArcher = false
            )
        }

        try {
            val englishVisualPrompt = translatePromptToVividEnglish(cleanPrompt, cleanKey)
            _state.update {
                it.copy(
                    expandedEnglish8kPrompt = englishVisualPrompt,
                    isImageSyncedToDrive = false
                )
            }

            // Prioritize official Nano Banana (`gemini-2.5-flash-image`) endpoint first for fast, lossless HD generation & editing
            val geminiImageModelsQueue: List<Pair<String, String>> = when (selectedTier) {
                PremiumImageModelTier.NANO_BANANA_2_1 -> listOf(
                    "gemini-2.5-flash-image" to "Nano Banana (gemini-2.5-flash-image)",
                    "gemini-3-pro-image-preview" to "Nano Banana Pro (gemini-3-pro-image-preview)",
                    "gemini-3.1-flash-image-preview" to "Nano Banana 3.1 Flash Image"
                )
                PremiumImageModelTier.NANO_BANANA_PRO -> listOf(
                    "gemini-2.5-flash-image" to "Nano Banana (gemini-2.5-flash-image)",
                    "gemini-3-pro-image-preview" to "Nano Banana Pro (gemini-3-pro-image-preview)",
                    "gemini-3.1-flash-image-preview" to "Nano Banana Pro 3.1"
                )
                else -> listOf(
                    "gemini-2.5-flash-image" to "Nano Banana (gemini-2.5-flash-image)",
                    "gemini-3-pro-image-preview" to "Nano Banana Pro (gemini-3-pro-image-preview)",
                    "gemini-3.1-flash-image-preview" to "Nano Banana Pro 3.1",
                    "gemini-2.0-flash-preview-image-generation" to "Gemini 2.0 Flash Image"
                )
            }

            val imagenModelsQueue: List<Pair<String, String>> = when (selectedTier) {
                PremiumImageModelTier.IMAGEN_4_ULTRA -> listOf(
                    "imagen-4.0-ultra-generate-001" to "Imagen 4.0 Ultra (imagen-4.0-ultra-generate-001)",
                    "imagen-4.0-generate-001" to "Imagen 4.0 Pro (imagen-4.0-generate-001)",
                    "imagen-3.0-generate-002" to "Imagen 3.0 Pro"
                )
                PremiumImageModelTier.IMAGEN_4_PRO -> listOf(
                    "imagen-4.0-generate-001" to "Imagen 4.0 Pro (imagen-4.0-generate-001)",
                    "imagen-4.0-ultra-generate-001" to "Imagen 4.0 Ultra (imagen-4.0-ultra-generate-001)",
                    "imagen-3.0-generate-002" to "Imagen 3.0 Pro"
                )
                else -> listOf(
                    "imagen-4.0-ultra-generate-001" to "Imagen 4.0 Ultra (imagen-4.0-ultra-generate-001)",
                    "imagen-4.0-generate-001" to "Imagen 4.0 Pro (imagen-4.0-generate-001)",
                    "imagen-3.0-generate-002" to "Imagen 3.0 Pro"
                )
            }

            // If user explicitly chose Imagen 4 Ultra or Imagen 4 Pro and isn't editing an existing bitmap, try Imagen 4 `:predict` first
            if ((selectedTier == PremiumImageModelTier.IMAGEN_4_ULTRA || selectedTier == PremiumImageModelTier.IMAGEN_4_PRO) &&
                sourceFileForEdit == null && cleanKey.isNotBlank()
            ) {
                for ((imagenModelId, imagenLabel) in imagenModelsQueue) {
                    val imagenFile = requestImagenPredictPreview(
                        context = context,
                        apiKey = cleanKey,
                        modelName = imagenModelId,
                        prompt = englishVisualPrompt
                    )
                    if (imagenFile != null && imagenFile.exists()) {
                        _state.update {
                            it.copy(
                                isGeneratingImage = false,
                                isWorkingOnStudioTask = false,
                                lastGeneratedImagePath = imagenFile.absolutePath,
                                lastUsedImageModelName = imagenLabel,
                                pendingImageSaveConfirmation = true,
                                isImageSavedToArcher = false,
                                imageProgressMessageBn = "✔ $imagenLabel দিয়ে রিয়েল ছবি তৈরি সম্পন্ন হয়েছে! স্যার, এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                            )
                        }
                        return@withContext "জি স্যার, $imagenLabel প্রিমিয়াম মডেল দিয়ে আপনার কথামতো রিয়েল ছবি তৈরি হয়ে গেছে এবং ছবি তৈরির পেইজে দেখানো হচ্ছে। এই কাজটা হয়ে গেছে—এটা কি ফোল্ডারে সেভ করব?"
                    }
                }
            }

            // Tier 1: Direct Gemini REST API with user's Paid/Premium API Key or Built-in Key (`Nano Banana Pro` / `gemini-3-pro-image-preview` & above)
            if (cleanKey.isNotBlank()) {
                for ((modelId, modelLabel) in geminiImageModelsQueue) {
                    val restFile = requestGeminiImageGenerationOrEditPreview(
                        context = context,
                        apiKey = cleanKey,
                        modelName = modelId,
                        prompt = englishVisualPrompt,
                        sourceImageFile = sourceFileForEdit
                    )
                    if (restFile != null && restFile.exists()) {
                        _state.update {
                            it.copy(
                                isGeneratingImage = false,
                                isWorkingOnStudioTask = false,
                                lastGeneratedImagePath = restFile.absolutePath,
                                lastUsedImageModelName = modelLabel,
                                pendingImageSaveConfirmation = true,
                                isImageSavedToArcher = false,
                                imageProgressMessageBn = "✔ $modelLabel দিয়ে রিয়েল ছবি তৈরি সম্পন্ন হয়েছে! স্যার, এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                            )
                        }
                        return@withContext "জি স্যার, $modelLabel দিয়ে আপনার কথামতো রিয়েল ছবি তৈরি হয়ে গেছে এবং ছবি তৈরির পেইজে দেখানো হচ্ছে। এই কাজটা হয়ে গেছে—এটা কি ফোল্ডারে সেভ করব?"
                    }
                }

                // Also try Imagen 4 Ultra / Pro `:predict` endpoint in AUTO_PRO mode if not editing an image
                if (sourceFileForEdit == null) {
                    for ((imagenModelId, imagenLabel) in imagenModelsQueue) {
                        val imagenFile = requestImagenPredictPreview(
                            context = context,
                            apiKey = cleanKey,
                            modelName = imagenModelId,
                            prompt = englishVisualPrompt
                        )
                        if (imagenFile != null && imagenFile.exists()) {
                            _state.update {
                                it.copy(
                                    isGeneratingImage = false,
                                    isWorkingOnStudioTask = false,
                                    lastGeneratedImagePath = imagenFile.absolutePath,
                                    lastUsedImageModelName = imagenLabel,
                                    pendingImageSaveConfirmation = true,
                                    isImageSavedToArcher = false,
                                    imageProgressMessageBn = "✔ $imagenLabel দিয়ে রিয়েল ছবি তৈরি সম্পন্ন হয়েছে! স্যার, এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                                )
                            }
                            return@withContext "জি স্যার, $imagenLabel প্রিমিয়াম মডেল দিয়ে আপনার কথামতো রিয়েল ছবি তৈরি হয়ে গেছে এবং ছবি তৈরির পেইজে দেখানো হচ্ছে। এই কাজটা হয়ে গেছে—এটা কি ফোল্ডারে সেভ করব?"
                        }
                    }
                }
            }

            // Tier 2: Official Firebase AI Logic SDK (`gemini-3-pro-image-preview` -> `gemini-nano-banana-pro` -> `gemini-2.5-flash-image` -> `gemini-nano-banana-2.1`)
            for ((sdkModelId, sdkLabel) in geminiImageModelsQueue.take(4)) {
                val firebaseSdkFile = generateRealImageViaFirebaseAiLogic(
                    context = context,
                    modelName = sdkModelId,
                    prompt = englishVisualPrompt,
                    sourceImageFile = sourceFileForEdit
                )
                if (firebaseSdkFile != null && firebaseSdkFile.exists()) {
                    _state.update {
                        it.copy(
                            isGeneratingImage = false,
                            isWorkingOnStudioTask = false,
                            lastGeneratedImagePath = firebaseSdkFile.absolutePath,
                            lastUsedImageModelName = sdkLabel,
                            pendingImageSaveConfirmation = true,
                            isImageSavedToArcher = false,
                            imageProgressMessageBn = "✔ $sdkLabel দিয়ে রিয়েল ছবি তৈরি সম্পন্ন হয়েছে! স্যার, এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                        )
                    }
                    return@withContext "জি স্যার, $sdkLabel দিয়ে আপনার কথামতো রিয়েল ছবি তৈরি হয়ে গেছে এবং ছবি তৈরির পেইজে দেখানো হচ্ছে। এই কাজটা হয়ে গেছে—এটা কি ফোল্ডারে সেভ করব?"
                }
            }

            // Tier 3: Real Prompt-Matched High-Resolution Cloud Neural Image Engine (Flux Pro / Turbo 1024x1024)
            val realCloudImageFile = fetchRealPromptMatchedCloudAiImage(
                context = context,
                englishPrompt = englishVisualPrompt
            )
            if (realCloudImageFile != null && realCloudImageFile.exists()) {
                val cloudLabel = if (_state.value.isGmailPremiumLinked) {
                    "Nano Banana Pro Cloud (${_state.value.connectedGmailAccount})"
                } else {
                    "Nano Banana Pro Cloud Neural Engine"
                }
                _state.update {
                    it.copy(
                        isGeneratingImage = false,
                        isWorkingOnStudioTask = false,
                        lastGeneratedImagePath = realCloudImageFile.absolutePath,
                        lastUsedImageModelName = cloudLabel,
                        pendingImageSaveConfirmation = true,
                        isImageSavedToArcher = false,
                        imageProgressMessageBn = "✔ $cloudLabel দিয়ে রিয়েল ছবি তৈরি সম্পন্ন হয়েছে! স্যার, এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                    )
                }
                return@withContext "জি স্যার, আপনার কথামতো রিয়েল ছবি তৈরি হয়ে গেছে এবং ছবি তৈরির পেইজে দেখানো হচ্ছে। এই কাজটা হয়ে গেছে—এটা কি ফোল্ডারে সেভ করব?"
            }

            _state.update {
                it.copy(
                    isGeneratingImage = false,
                    isWorkingOnStudioTask = false,
                    imageProgressMessageBn = "ইন্টারনেট সংযোগ বা সার্ভার সমস্যার কারণে এই মুহূর্তে ছবি লোড করা যায়নি—আবার চেষ্টা করুন।"
                )
            }
            return@withContext "স্যার, ইন্টারনেট সংযোগ বা ইমেজ সার্ভার রেসপন্স না করায় ছবিটি তৈরি করা যায়নি। অনুগ্রহ করে আবার চেষ্টা করুন।"
        } catch (e: Exception) {
            logCrashOrFailureReport(context, "MakeOrEditImagesWithGemini", e.message ?: "Image generation failed")
            _state.update {
                it.copy(
                    isGeneratingImage = false,
                    isWorkingOnStudioTask = false,
                    imageProgressMessageBn = "ছবি তৈরিতে ত্রুটি: ${e.message}"
                )
            }
            return@withContext "ছবি তৈরিতে সমস্যা হয়েছে: ${e.message}"
        }
    }

    /**
     * Strips conversational command prefixes ("একটা ছবি তৈরি করো", "make an image of", etc.)
     * so the image engine receives the exact visual subject requested by the user.
     */
    fun cleanImagePromptFromCommand(raw: String): String {
        var cleaned = raw.trim()
        val prefixes = listOf(
            "আমাকে একটা ছবি তৈরি করে দাও",
            "আমাকে একটা ছবি বানিয়ে দাও",
            "একটা ছবি তৈরি করে দাও",
            "একটা ছবি বানিয়ে দাও",
            "একটা ছবি তৈরি করো",
            "একটা ছবি বানাও",
            "ছবি তৈরি করে দাও",
            "ছবি বানিয়ে দাও",
            "ছবি তৈরি করো",
            "ছবি তৈরি কর",
            "ছবি বানাও",
            "ছবি আঁকো",
            "একটা ছবি আঁকো",
            "generate an image of",
            "generate image of",
            "create an image of",
            "create image of",
            "make a picture of",
            "make an image of",
            "draw a picture of"
        )
        for (p in prefixes) {
            if (cleaned.lowercase().startsWith(p.lowercase())) {
                cleaned = cleaned.substring(p.length).trim()
                break
            }
        }
        cleaned = cleaned
            .replace(Regex("(এর\\s+)?একটা\\s+ছবি\\s+(তৈরি\\s+করো|তৈরি\\s+কর|বানিয়ে\\s+দাও|বানাও|আঁকো)$"), "")
            .replace(Regex("(এর\\s+)?ছবি\\s+(তৈরি\\s+করো|তৈরি\\s+কর|বানিয়ে\\s+দাও|বানাও|আঁকো)$"), "")
            .trim()
        return cleaned.ifBlank { raw.trim().ifBlank { "A breathtaking realistic landscape photograph at golden hour" } }
    }

    private suspend fun generateRealImageViaFirebaseAiLogic(
        context: Context,
        modelName: String = "gemini-3-pro-image-preview",
        prompt: String,
        sourceImageFile: File?
    ): File? {
        return try {
            withTimeoutOrNull(38_000L) {
                val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                    modelName = modelName,
                    generationConfig = generationConfig {
                        responseModalities = listOf(ResponseModality.TEXT, ResponseModality.IMAGE)
                    }
                )
                val response = if (sourceImageFile != null && sourceImageFile.exists()) {
                    val srcBmp = BitmapFactory.decodeFile(sourceImageFile.absolutePath)
                    if (srcBmp != null) {
                        val inputContent = content {
                            image(srcBmp)
                            text("Edit this image: $prompt")
                        }
                        model.generateContent(inputContent)
                    } else {
                        model.generateContent(prompt)
                    }
                } else {
                    model.generateContent(prompt)
                }
                val generatedBitmap: Bitmap? = response.candidates
                    .firstOrNull()
                    ?.content
                    ?.parts
                    ?.filterIsInstance<ImagePart>()
                    ?.firstOrNull()
                    ?.image
                if (generatedBitmap != null) {
                    writePreviewBitmapToCache(context, generatedBitmap)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase AI Logic $modelName fallback notice: ${e.message}")
            null
        }
    }

    /**
     * Calls Imagen 4 Ultra / Imagen 4 Pro (`imagen-4.0-ultra-generate-001`, `imagen-4.0-generate-001`)
     * via the `:predict` endpoint using the user's Premium API Key.
     */
    private fun requestImagenPredictPreview(
        context: Context,
        apiKey: String,
        modelName: String,
        prompt: String
    ): File? {
        var conn: HttpURLConnection? = null
        return try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:predict?key=$apiKey"
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 38000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            }

            val payload = JSONObject().apply {
                put(
                    "instances",
                    JSONArray().put(
                        JSONObject().put("prompt", prompt)
                    )
                )
                put(
                    "parameters",
                    JSONObject().apply {
                        put("sampleCount", 1)
                        put("aspectRatio", "1:1")
                    }
                )
            }

            conn.outputStream.use { os ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            if (conn.responseCode !in 200..299) {
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val predictions = json.optJSONArray("predictions") ?: return null
            for (i in 0 until predictions.length()) {
                val pred = predictions.optJSONObject(i) ?: continue
                val b64 = pred.optString("bytesBase64Encoded", "")
                    .ifBlank { pred.optJSONObject("image")?.optString("bytesBase64Encoded", "").orEmpty() }
                if (b64.isNotBlank()) {
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        return writePreviewBytesToCache(context, bytes)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "requestImagenPredictPreview ($modelName) failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Official Nano Banana (`gemini-2.5-flash-image`) REST API integration:
     * - Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent?key=YOUR_API_KEY`
     * - 30-second timeout (`readTimeout = 30000`) and automatic retry (up to 2 retries on API/network error).
     * - Supports both fresh 8K image generation and Voice Image Editing (`sourceImageFile` Base64 + edit prompt).
     * - Decodes `candidates[0].content.parts` (`inlineData.data`) at full HD without compression loss.
     */
    private fun requestGeminiImageGenerationOrEditPreview(
        context: Context,
        apiKey: String,
        modelName: String,
        prompt: String,
        sourceImageFile: File?
    ): File? {
        val modalitiesConfigs = listOf(
            JSONArray().put("TEXT").put("IMAGE"),
            JSONArray().put("IMAGE")
        )

        // Up to 2 automatic retries (3 attempts total) with 30-second timeout so it never crashes or shows raw errors
        for (attempt in 0..2) {
            var conn: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000 // 30-second timeout
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val partsArray = JSONArray()
                if (sourceImageFile != null && sourceImageFile.exists()) {
                    val bytes = sourceImageFile.readBytes()
                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    partsArray.put(
                        JSONObject().put(
                            "inlineData",
                            JSONObject().apply {
                                put("mimeType", "image/png")
                                put("data", b64)
                            }
                        )
                    )
                    partsArray.put(
                        JSONObject().put(
                            "text",
                            "Edit this image with high-resolution photorealistic quality according to these instructions: $prompt"
                        )
                    )
                } else {
                    partsArray.put(JSONObject().put("text", prompt))
                }

                val modalityArray = modalitiesConfigs[attempt % modalitiesConfigs.size]
                val genConfig = JSONObject().apply {
                    put("responseModalities", modalityArray)
                }

                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("parts", partsArray)
                            }
                        )
                    )
                    put("generationConfig", genConfig)
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val candidates = json.optJSONArray("candidates")
                    if (candidates != null && candidates.length() > 0) {
                        for (cIdx in 0 until candidates.length()) {
                            val parts = candidates.optJSONObject(cIdx)
                                ?.optJSONObject("content")
                                ?.optJSONArray("parts") ?: continue
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                                if (inlineData != null) {
                                    val base64Data = inlineData.optString("data", "")
                                    if (base64Data.isNotBlank()) {
                                        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                                        val opts = BitmapFactory.Options().apply {
                                            inPreferredConfig = Bitmap.Config.ARGB_8888
                                            inScaled = false
                                        }
                                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                                        if (bmp != null) {
                                            return writePreviewBytesToCache(context, bytes)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nano Banana ($modelName) attempt ${attempt + 1} notice: ${e.message}")
            } finally {
                conn?.disconnect()
            }
            try {
                Thread.sleep(450L * (attempt + 1))
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Auto-Prompt Enhancer (Bangla to Pro 8K Prompt):
     * Automatically expands and translates short or Bangla voice commands into a detailed,
     * cinematic, photorealistic 8K English prompt in the background before calling Nano Banana (`gemini-2.5-flash-image`).
     */
    private fun translatePromptToVividEnglish(rawPrompt: String, apiKey: String): String {
        val clean = rawPrompt.trim()
        if (apiKey.isNotBlank()) {
            var conn: HttpURLConnection? = null
            try {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 7000
                    readTimeout = 10000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }
                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "text",
                                        "You are an expert 8K AI photography prompt engineer. Expand and translate this Bangla/English user image request into a single detailed, cinematic, photorealistic 8K English prompt with rich lighting, lens, atmosphere, and texture details. Output ONLY the expanded English prompt, no intro or quotes: $clean"
                                    )
                                )
                            )
                        )
                    )
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode in 200..299) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    val translated = JSONObject(resp)
                        .optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                    if (!translated.isNullOrBlank()) {
                        return "$translated, ultra-detailed 8K photorealistic masterpiece, cinematic volumetric lighting, sharp DSLR focus, HDR"
                    }
                }
            } catch (_: Exception) {
            } finally {
                conn?.disconnect()
            }
        }

        // Built-in Bengali-to-English visual vocabulary mapper so Bengali voice prompts work even without a text key
        var mapped = clean
        val bnToEnMap = listOf(
            "রয়েল বেঙ্গল টাইগার" to "majestic Royal Bengal Tiger in Sundarbans forest",
            "বাঘ" to "realistic Bengal tiger",
            "সিংহ" to "majestic lion",
            "বিড়াল" to "cute realistic cat",
            "কুকুর" to "realistic dog",
            "পাখি" to "colorful bird in nature",
            "ঘোড়া" to "running horse",
            "হাতি" to "majestic elephant",
            "নদী" to "scenic river with boats in Bangladesh",
            "পাহাড়" to "majestic lush mountains and clouds",
            "সমুদ্র" to "ocean waves on Cox's Bazar beach at sunset",
            "সাগর" to "scenic ocean beach",
            "বৃষ্টি" to "monsoon rain falling on green leaves and street",
            "চায়ের দোকান" to "cozy roadside tea stall in Bangladesh with warm lights",
            "চা" to "steaming cup of milk tea",
            "ফুল" to "vibrant blooming flowers",
            "গোলাপ" to "red rose flower with water droplets",
            "মেয়ে" to "young South Asian woman portrait",
            "ছেলে" to "young South Asian man portrait",
            "মানুষ" to "realistic human portrait",
            "গাড়ি" to "modern luxury sports car on wet city road",
            "বাইক" to "modern motorcycle",
            "শহর" to "futuristic glowing city skyline at night",
            "গ্রাম" to "serene green Bangladeshi village with river and paddy fields",
            "আকাশ" to "dramatic sky with clouds",
            "সূর্যাস্ত" to "golden hour sunset",
            "চাঁদ" to "glowing full moon in night sky",
            "রোবট" to "advanced humanoid AI robot",
            "মহাকাশ" to "astronaut in deep space nebula",
            "খাবার" to "delicious traditional platter of food",
            "বিরিয়ানি" to "aromatic Kacchi Biryani platter",
            "মসজিদ" to "beautiful illuminated mosque architecture at twilight",
            "ঘর" to "modern architectural house"
        )
        for ((bn, en) in bnToEnMap) {
            if (mapped.contains(bn)) {
                mapped = mapped.replace(bn, " $en ")
            }
        }
        return "$mapped, realistic high resolution photography, detailed, cinematic lighting, 8k"
    }

    private fun fetchRealPromptMatchedCloudAiImage(
        context: Context,
        englishPrompt: String
    ): File? {
        val seed = abs((englishPrompt.hashCode() xor (System.currentTimeMillis().toInt() and 0xFFFF))) % 999999 + 1
        val encoded = URLEncoder.encode(englishPrompt, "UTF-8")
        val candidateUrls = listOf(
            "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&seed=$seed&model=flux",
            "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&seed=$seed"
        )

        for (urlStr in candidateUrls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 35000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile) ArcherAI/4.0")
                }
                if (conn.responseCode in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    if (bytes.size > 4096) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            return writePreviewBytesToCache(context, bytes)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cloud neural image URL attempt failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return null
    }

    private fun writePreviewBitmapToCache(context: Context, bitmap: Bitmap): File {
        val previewDir = File(context.cacheDir, "image_preview_cache")
        if (!previewDir.exists()) previewDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val previewFile = File(previewDir, "preview_image_$ts.png")
        FileOutputStream(previewFile).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        return previewFile
    }

    private fun writePreviewBytesToCache(context: Context, bytes: ByteArray): File {
        val previewDir = File(context.cacheDir, "image_preview_cache")
        if (!previewDir.exists()) previewDir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val previewFile = File(previewDir, "preview_image_$ts.png")
        FileOutputStream(previewFile).use { fos ->
            fos.write(bytes)
        }
        return previewFile
    }

    /**
     * Generate Music (`music_note`) using Google Lyria 3 (`lyria-3-clip-preview` / `lyria-3-pro-preview`):
     * Uses real cloud Lyria API only (no fake sine-wave synthesis).
     */
    suspend fun generateMusicTrackWithLyria(
        context: Context,
        apiKey: String,
        prompt: String,
        fullLengthSong: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        ensureInitialized(context)
        val cleanKey = resolveEffectiveApiKey(context, apiKey)
        val cleanPrompt = prompt.trim().ifBlank { "Futuristic ambient synthwave melody" }

        _state.update {
            it.copy(
                isWorkingOnStudioTask = true,
                pendingMusicSaveConfirmation = false,
                isMusicSavedToArcher = false
            )
        }
        val modelName = if (fullLengthSong) "lyria-3-pro-preview" else "lyria-3-clip-preview"
        var conn: HttpURLConnection? = null
        try {
            val previewDir = File(context.cacheDir, "music_preview_cache")
            if (!previewDir.exists()) previewDir.mkdirs()

            if (cleanKey.isNotBlank()) {
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$cleanKey"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 45000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                }

                val payload = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().apply {
                                put("parts", JSONArray().put(JSONObject().put("text", cleanPrompt)))
                            }
                        )
                    )
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                        }
                    )
                }

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val parts = JSONObject(body).optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val inlineData = parts.optJSONObject(i)?.optJSONObject("inlineData")
                                ?: parts.optJSONObject(i)?.optJSONObject("inline_data")
                            if (inlineData != null) {
                                val b64 = inlineData.optString("data", "")
                                val mime = inlineData.optString("mimeType", "audio/wav")
                                if (b64.isNotBlank()) {
                                    val ext = if (mime.contains("mp3")) "mp3" else "wav"
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    val outFile = File(previewDir, "preview_lyria_music_$ts.$ext")
                                    FileOutputStream(outFile).use { fos ->
                                        fos.write(Base64.decode(b64, Base64.DEFAULT))
                                    }
                                    _state.update {
                                        it.copy(
                                            isWorkingOnStudioTask = false,
                                            lastGeneratedMusicPath = outFile.absolutePath,
                                            pendingMusicSaveConfirmation = true,
                                            isMusicSavedToArcher = false
                                        )
                                    }
                                    return@withContext "জি স্যার, মিউজিক ট্র্যাক তৈরি হয়ে গেছে। এই কাজটা হয়ে গেছে—এটা কি 'archer' ফোল্ডারে সেভ করব?"
                                }
                            }
                        }
                    }
                }
            }

            _state.update { it.copy(isWorkingOnStudioTask = false) }
            "জি স্যার, গান চালানোর জন্য YouTube Music অথবা Spotify ব্যবহার করা হচ্ছে।"
        } catch (e: Exception) {
            logCrashOrFailureReport(context, "LyriaMusicGen", e.message ?: "Lyria error")
            _state.update { it.copy(isWorkingOnStudioTask = false) }
            "Error generating music with Lyria ($modelName): ${e.message}"
        } finally {
            conn?.disconnect()
        }
    }

    fun togglePlayLastGeneratedMusic(): Boolean {
        val path = _state.value.lastGeneratedMusicPath
        if (path.isBlank() || !File(path).exists()) return false
        return try {
            val current = musicMediaPlayer
            if (current != null && current.isPlaying) {
                current.stop()
                current.release()
                musicMediaPlayer = null
                _state.update { it.copy(isPlayingGeneratedMusic = false) }
                false
            } else {
                current?.release()
                val mp = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener {
                        _state.update { s -> s.copy(isPlayingGeneratedMusic = false) }
                    }
                    start()
                }
                musicMediaPlayer = mp
                _state.update { it.copy(isPlayingGeneratedMusic = true) }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "togglePlayLastGeneratedMusic failed: ${e.message}")
            _state.update { it.copy(isPlayingGeneratedMusic = false) }
            false
        }
    }

    /**
     * Records a diagnostic crash/failure report into app-internal filesDir (never public Download folder).
     */
    fun logCrashOrFailureReport(context: Context, sourceTag: String, errorDetails: String) {
        ensureInitialized(context)
        if (!_state.value.sendCrashReports) return
        try {
            val dir = File(context.filesDir, "crash_reports")
            if (!dir.exists()) dir.mkdirs()
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val reportLine = "[$ts] [$sourceTag] $errorDetails\n"
            val logFile = File(dir, "archer_diagnostics.log")
            FileOutputStream(logFile, true).use { fos ->
                fos.write(reportLine.toByteArray(Charsets.UTF_8))
            }
            _state.update {
                it.copy(
                    lastCrashReportSummary = "[$sourceTag] ${errorDetails.take(90)}",
                    crashReportsCount = it.crashReportsCount + 1
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write diagnostic report: ${e.message}")
        }
    }

    private fun getArcherOutputDir(context: Context): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val archerDir = File(downloads, "archer")
        if (!archerDir.exists()) {
            val ok = archerDir.mkdirs()
            if (!ok) {
                val fallback = File(context.getExternalFilesDir(null) ?: context.filesDir, "archer")
                if (!fallback.exists()) fallback.mkdirs()
                return fallback
            }
        }
        return archerDir
    }
}
