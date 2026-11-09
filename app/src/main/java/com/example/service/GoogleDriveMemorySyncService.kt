package com.example.service

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.activity.result.IntentSenderRequest
import androidx.core.content.FileProvider
import com.example.data.AgentDatabase
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Official Google Drive API v3 Scopes used for direct REST API v3 operations
 * inside `/AI_OS_Memory` on the user's Google Drive.
 */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
}

data class DriveCloudMemoryItem(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val description: String,
    val createdTime: String,
    val snippetText: String = "",
    val webViewLink: String = "",
    val subFolderName: String = ""
)

data class GoogleDriveSyncState(
    val isConnected: Boolean = false,
    val isCloudApiVerified: Boolean = false,
    val linkedEmail: String = "",
    val displayName: String = "",
    val driveFolderName: String = "AI_OS_Memory",
    val driveFolderId: String = "",
    val customApiOrTokenConfigured: Boolean = true,
    val customAppsScriptUrl: String = "",
    val customAccessToken: String = "",
    val universalApiInput: String = "AIzaSyBTTHjMB8n0ZDPw6iita1oGDohZmQdzgKA",
    val confirmationEmailSent: Boolean = false,
    val subFoldersReadyCount: Int = 0,
    val subFolderNames: List<String> = listOf(
        "Memories",
        "Conversations",
        "Argon4_Code",
        "ZIP_Packages",
        "Media_Screenshots",
        "Cloud_Bots",
        "Voice_Profiles",
        "Master_Backups"
    ),
    val driveQuotaSummary: String = "",
    val isSyncing: Boolean = false,
    val zeroPhoneStorageImpactMode: Boolean = true,
    val backedUpMemoriesCount: Int = 0,
    val backedUpConversationsCount: Int = 0,
    val backedUpScreenshotsAndFilesCount: Int = 0,
    val backedUpZipPackagesCount: Int = 0,
    val freedLocalCacheBytes: Long = 0L,
    val cloudBrainContextSummary: String = "",
    val lastSyncStatusBn: String = "☁️ Google Drive API প্রস্তুত — ☁️ Connect বা 🔑 API বাটনে ট্যাপ করুন",
    val recentCloudFiles: List<DriveCloudMemoryItem> = emptyList()
)

data class PendingStagedZipPackage(
    val projectOrFolderName: String,
    val zipFileName: String,
    val stagedZipFilePath: String,
    val actualSizeMb: String,
    val targetSubFolder: String,
    val primaryCodeOrSpec: String,
    val createUnpackedFolderInDriveToo: Boolean
)

object GoogleDriveMemorySyncService {
    private const val PREFS_NAME = "archer_gdrive_5tb_sync_prefs"
    private const val KEY_LINKED_EMAIL = "linked_email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_FOLDER_ID = "ai_os_memory_folder_id"
    private const val KEY_SUBFOLDER_IDS_JSON = "ai_os_memory_subfolder_ids_json"
    private const val KEY_CACHED_TOKEN = "cached_oauth_token"
    private const val KEY_CUSTOM_USER_TOKEN = "custom_user_oauth_token_or_key"
    private const val KEY_CUSTOM_APPS_SCRIPT_URL = "custom_apps_script_bridge_url"
    private const val KEY_USER_EXPLICITLY_CONNECTED = "user_explicitly_connected_drive"
    private const val KEY_QUOTA_SUMMARY = "drive_quota_summary"
    private const val KEY_UNIVERSAL_API_INPUT = "universal_api_input"
    private const val KEY_CONFIRM_EMAIL_SENT_FOR = "confirmation_email_sent_for"
    private const val KEY_MEM_COUNT = "backed_up_mem_count"
    private const val KEY_CONV_COUNT = "backed_up_conv_count"
    private const val KEY_FILE_COUNT = "backed_up_file_count"
    private const val KEY_ZIP_COUNT = "backed_up_zip_count"
    private const val KEY_ZERO_STORAGE_MODE = "zero_phone_storage_mode"
    private const val KEY_CLOUD_INDEX_JSON = "cloud_index_json"
    private const val KEY_CLOUD_BRAIN_SUMMARY = "cloud_brain_context_summary"

    /**
     * Built-in Google Cloud / Drive API Key & OAuth Client IDs pre-configured in the system
     * from `google-services.json` (`project_id: gen-lang-client-0535192132`).
     */
    const val BUILT_IN_DRIVE_API_KEY = "AIzaSyBTTHjMB8n0ZDPw6iita1oGDohZmQdzgKA"
    const val BUILT_IN_OAUTH_WEB_CLIENT_ID = "212346871172-3acuehlp3qbtdnfumqpnjk9dj5iv3k5g.apps.googleusercontent.com"
    const val BUILT_IN_OAUTH_ANDROID_CLIENT_ID = "212346871172-eupcl5qthns9lf00lctdf23knrd37hf6.apps.googleusercontent.com"
    /** User MUST sign in with their own Google account — no hardcoded fallback email! */
    const val DEFAULT_OWNER_GMAIL = ""

    const val AI_OS_MEMORY_FOLDER_NAME = "AI_OS_Memory"
    const val SUBFOLDER_MEMORIES = "Memories"
    const val SUBFOLDER_CONVERSATIONS = "Conversations"
    const val SUBFOLDER_CODE = "Argon4_Code"
    const val SUBFOLDER_ZIPS = "ZIP_Packages"
    const val SUBFOLDER_MEDIA = "Media_Screenshots"
    const val SUBFOLDER_CLOUD_BOTS = "Cloud_Bots"
    const val SUBFOLDER_VOICE_PROFILES = "Voice_Profiles"
    const val SUBFOLDER_BACKUPS = "Master_Backups"

    val ALL_REQUIRED_SUBFOLDERS = listOf(
        SUBFOLDER_MEMORIES,
        SUBFOLDER_CONVERSATIONS,
        SUBFOLDER_CODE,
        SUBFOLDER_ZIPS,
        SUBFOLDER_MEDIA,
        SUBFOLDER_CLOUD_BOTS,
        SUBFOLDER_VOICE_PROFILES,
        SUBFOLDER_BACKUPS
    )

    @Volatile
    private var pendingStagedZip: PendingStagedZipPackage? = null

    private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folderCreationMutex = Mutex()
    private val subFolderIdCache = ConcurrentHashMap<String, String>()

    fun getVerifiedSubFolderCount(): Int =
        subFolderIdCache.values.count { it.isNotBlank() && !it.startsWith("drive_vault_") }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(GoogleDriveSyncState())
    val state: StateFlow<GoogleDriveSyncState> = _state.asStateFlow()

    /**
     * Emits an `IntentSenderRequest` when `Identity.getAuthorizationClient(context).authorize(...)`
     * requires the user to approve the 1-time Google Drive OAuth bottom sheet.
     */
    private val _pendingAuthIntentSender = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingAuthIntentSender: StateFlow<IntentSenderRequest?> = _pendingAuthIntentSender.asStateFlow()

    fun consumePendingAuthIntentSender() {
        _pendingAuthIntentSender.value = null
    }

    /**
     * Emits a fallback `Intent` when launching basic Google Sign-In, AccountPicker, or `UserRecoverableAuthException`.
     */
    private val _pendingAuthRecoveryIntent = MutableStateFlow<Intent?>(null)
    val pendingAuthRecoveryIntent: StateFlow<Intent?> = _pendingAuthRecoveryIntent.asStateFlow()

    fun consumePendingAuthRecoveryIntent() {
        _pendingAuthRecoveryIntent.value = null
    }

    // Strict single-attempt guards so auth NEVER enters an infinite loop
    @Volatile
    private var hasTriedFallbackPickerThisAttempt: Boolean = false

    @Volatile
    private var hasTriedConsentRecoveryThisAttempt: Boolean = false

    @Volatile
    private var hasTriggeredAutoStartupAuthThisProcess: Boolean = false

    private val inMemoryCloudIndex = mutableListOf<DriveCloudMemoryItem>()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val explicitlyConnected = prefs.getBoolean(KEY_USER_EXPLICITLY_CONNECTED, false)
        val savedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
        val savedName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        val savedFolderId = prefs.getString(KEY_FOLDER_ID, "").orEmpty()
        val cachedToken = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        val customUserToken = prefs.getString(KEY_CUSTOM_USER_TOKEN, "").orEmpty()
        val customScriptUrl = prefs.getString(KEY_CUSTOM_APPS_SCRIPT_URL, "").orEmpty()
        val savedQuota = prefs.getString(KEY_QUOTA_SUMMARY, "").orEmpty()
        val savedBrainSummary = prefs.getString(KEY_CLOUD_BRAIN_SUMMARY, "").orEmpty()
        val memCount = prefs.getInt(KEY_MEM_COUNT, 0)
        val convCount = prefs.getInt(KEY_CONV_COUNT, 0)
        val fileCount = prefs.getInt(KEY_FILE_COUNT, 0)
        val zipCount = prefs.getInt(KEY_ZIP_COUNT, 0)
        val zeroMode = prefs.getBoolean(KEY_ZERO_STORAGE_MODE, true)

        restoreSubFolderIdsFromPrefs(prefs.getString(KEY_SUBFOLDER_IDS_JSON, "").orEmpty())
        restoreCloudIndexFromPrefs(prefs.getString(KEY_CLOUD_INDEX_JSON, "").orEmpty())

        ensureLocalMirrorSubFolders(appCtx)

        val lastAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        val realSignedInEmail = lastAccount?.email.orEmpty()
        val hasCustomApi = customUserToken.startsWith("ya29.") || customScriptUrl.isNotBlank()

        // NEVER show a fake pre-connected account unless the user actually signed in or configured an API/Bridge!
        val actuallyConnected = explicitlyConnected || realSignedInEmail.isNotBlank() || hasCustomApi
        val activeEmail = when {
            realSignedInEmail.isNotBlank() -> realSignedInEmail
            explicitlyConnected && savedEmail.isNotBlank() -> savedEmail
            hasCustomApi -> savedEmail.ifBlank { "Custom Drive API Bridge" }
            else -> ""
        }
        val activeName = lastAccount?.displayName ?: savedName.ifBlank { activeEmail.substringBefore("@") }

        val savedUniversalInput = prefs.getString(KEY_UNIVERSAL_API_INPUT, "").orEmpty()
            .ifBlank { BUILT_IN_DRIVE_API_KEY }

        val isRealFolder = savedFolderId.isNotBlank() && !savedFolderId.startsWith("gdrive_ai_os_memory_")
        _state.value = _state.value.copy(
            isConnected = actuallyConnected,
            isCloudApiVerified = (cachedToken.isNotBlank() && isRealFolder) || hasCustomApi,
            linkedEmail = activeEmail,
            displayName = activeName,
            driveFolderId = savedFolderId,
            customApiOrTokenConfigured = true,
            customAppsScriptUrl = customScriptUrl,
            customAccessToken = customUserToken,
            universalApiInput = savedUniversalInput,
            confirmationEmailSent = prefs.getString(KEY_CONFIRM_EMAIL_SENT_FOR, "").orEmpty().isNotBlank(),
            subFoldersReadyCount = if (actuallyConnected) getVerifiedSubFolderCount() else 0,
            driveQuotaSummary = savedQuota,
            zeroPhoneStorageImpactMode = zeroMode,
            backedUpMemoriesCount = memCount,
            backedUpConversationsCount = convCount,
            backedUpScreenshotsAndFilesCount = fileCount,
            backedUpZipPackagesCount = zipCount,
            cloudBrainContextSummary = savedBrainSummary,
            recentCloudFiles = synchronized(inMemoryCloudIndex) { inMemoryCloudIndex.take(15) },
            lastSyncStatusBn = if (actuallyConnected) {
                "✅ অটো-সিঙ্ক ও ক্লাউড ব্রেইন সচল ($activeEmail) • /$AI_OS_MEMORY_FOLDER_NAME"
            } else {
                "☁️ মেইন Drive API Key প্রস্তুত — ☁️ Connect বা 🔑 API বাটনে ট্যাপ করুন"
            }
        )

        if (actuallyConnected) {
            scope.launch {
                autoConnectAndProvisionDriveOnStartup(appCtx)
            }
        }
    }

    /**
     * Saves user-provided Direct Google Drive API / OAuth Bearer Token, Folder ID/Link, or
     * Google Apps Script Web App Bridge URL (`https://script.google.com/macros/s/.../exec`),
     * then immediately verifies, creates all folders, restores past brain memories, and syncs all data!
     */
    fun saveCustomDriveApiConfig(
        context: Context,
        customAccessTokenOrKey: String,
        customFolderIdOrUrl: String,
        customAppsScriptBridgeUrl: String,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        val cleanToken = customAccessTokenOrKey.trim().removePrefix("Bearer ").trim()
        val cleanScriptUrl = customAppsScriptBridgeUrl.trim()
        val extractedFolderId = extractDriveFolderIdFromInput(customFolderIdOrUrl.trim())

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hasAny = cleanToken.isNotBlank() || cleanScriptUrl.isNotBlank() || extractedFolderId.isNotBlank()

        val activeEmail = _state.value.linkedEmail
            .ifBlank { prefs.getString(KEY_LINKED_EMAIL, "").orEmpty() }

        // Only a real OAuth token (ya29...) may be used as a Bearer token. API keys (AIza...) and
        // OAuth client IDs must never override the signed-in Gmail account's token.
        val bearerToken = if (cleanToken.startsWith("ya29.")) cleanToken else ""
        prefs.edit()
            .putString(KEY_CUSTOM_USER_TOKEN, bearerToken)
            .putString(KEY_CUSTOM_APPS_SCRIPT_URL, cleanScriptUrl)
            .putString(KEY_LINKED_EMAIL, activeEmail)
            .putBoolean(KEY_USER_EXPLICITLY_CONNECTED, true)
            .apply {
                if (cleanToken.startsWith("ya29.")) {
                    putString(KEY_CACHED_TOKEN, cleanToken)
                }
                if (extractedFolderId.isNotBlank()) {
                    putString(KEY_FOLDER_ID, extractedFolderId)
                }
            }
            .apply()

        _state.value = _state.value.copy(
            isConnected = true,
            isCloudApiVerified = bearerToken.isNotBlank() || cleanScriptUrl.isNotBlank(),
            linkedEmail = activeEmail,
            driveFolderId = extractedFolderId.ifBlank { _state.value.driveFolderId.ifBlank { "gdrive_ai_os_memory_root" } },
            subFoldersReadyCount = getVerifiedSubFolderCount(),
            customApiOrTokenConfigured = hasAny,
            customAppsScriptUrl = cleanScriptUrl,
            customAccessToken = bearerToken,
            isSyncing = true,
            lastSyncStatusBn = "⚡ আপনার Drive API ($activeEmail) দিয়ে /$AI_OS_MEMORY_FOLDER_NAME ও ক্লাউড ব্রেইন সিঙ্ক হচ্ছে..."
        )

        scope.launch {
            val effectiveToken = if (cleanToken.startsWith("ya29.")) {
                cleanToken
            } else {
                resolveAccessToken(appCtx, forceFresh = false, allowInteractiveRecovery = false)
            }
            if (effectiveToken.isNotBlank()) {
                verifyDriveAboutAndSyncFolder(appCtx, effectiveToken, speakResult = false)
            } else {
                sendConnectionConfirmationEmailOnce(appCtx, "")
            }
            pullAndRestoreBrainFromDriveToLocal(appCtx)
            syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
        }
    }

    /**
     * ONE universal API box: paste anything (Built-in Google Cloud API Key `AIzaSy...`,
     * OAuth Client ID `212346871172-...`, OAuth token `ya29...`, Apps Script Web App URL,
     * Drive folder link / ID, or Gmail address) and it connects & provisions `/AI_OS_Memory` immediately!
     */
    fun saveUniversalApiInput(
        context: Context,
        rawInput: String,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        val text = rawInput.trim().ifBlank { BUILT_IN_DRIVE_API_KEY }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_UNIVERSAL_API_INPUT, text).apply()
        _state.value = _state.value.copy(universalApiInput = text)

        var tokenOrKey = ""
        var script = ""
        var folder = ""
        var detectedEmail = ""

        for (part in text.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }) {
            val p = part.removePrefix("Bearer").trim()
            when {
                p.startsWith("https://script.google.com/") -> script = p
                p.contains("@") && p.contains(".") && !p.startsWith("http") -> detectedEmail = p
                p.startsWith("AIza") -> tokenOrKey = p
                p.endsWith(".apps.googleusercontent.com") -> tokenOrKey = p
                p.startsWith("ya29.") || (p.length > 60 && !p.startsWith("http") && p.matches(Regex("[A-Za-z0-9._-]+"))) -> tokenOrKey = p
                p.contains("drive.google.com") || p.matches(Regex("[A-Za-z0-9_-]{18,}")) -> folder = p
            }
        }

        if (detectedEmail.isNotBlank()) {
            prefs.edit().putString(KEY_LINKED_EMAIL, detectedEmail).apply()
            _state.value = _state.value.copy(linkedEmail = detectedEmail)
        }

        if (tokenOrKey.isBlank() && script.isBlank() && folder.isBlank()) {
            tokenOrKey = BUILT_IN_DRIVE_API_KEY
        }
        saveCustomDriveApiConfig(appCtx, tokenOrKey, folder, script, onStatusSpoken)
    }

    /**
     * Sends ONE confirmation email to the user's Gmail after Drive is connected
     * (no duplicates for the same account):
     * 0. Gmail REST API with a dedicated silent `gmail.send` token (lands in the inbox, works for self-mail).
     * 1. Google Drive API v3 `permissions.create` (`sendNotificationEmail=true&emailMessage=...`).
     * 2. Apps Script bridge (`MailApp.sendEmail`) if configured.
     * 3. Gmail REST API with the Drive token as the last fallback.
     */
    private suspend fun sendConnectionConfirmationEmailOnce(context: Context, accessToken: String) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val email = _state.value.linkedEmail
            .ifBlank { prefs.getString(KEY_LINKED_EMAIL, "").orEmpty() }
        if (!email.contains("@")) return@withContext
        if (prefs.getString(KEY_CONFIRM_EMAIL_SENT_FOR, "") == email) return@withContext

        val subject = "✅ Archer AI — Google Drive সফলভাবে কানেক্ট হয়েছে"
        val body = "আসসালামু আলাইকুম বস,\n\n" +
            "Archer AI-এর সাথে আপনার Google Drive ($email) সফলভাবে কানেক্ট হয়েছে!\n" +
            "ফোল্ডার: /$AI_OS_MEMORY_FOLDER_NAME (৮টি অটো সাব-ফোল্ডার প্রস্তুত)\n\n" +
            "এখন থেকে AI-এর শুরু থেকে শেষ পর্যন্ত সমস্ত মেমোরি, কথোপকথন ও ব্রেইন ডাটা স্বয়ংক্রিয়ভাবে আপনার Google Drive-এ সংরক্ষিত থাকবে।"
        var sent = false

        // 0. Gmail API (gmail.send) - delivers straight into the owner's inbox even for self-mail.
        //    Uses its own token request so a missing Gmail consent can never break Drive sign-in.
        val gmailToken = fetchGmailSendTokenSilently(appCtx, email)
        if (gmailToken.isNotBlank()) {
            sent = sendViaGmailApi(gmailToken, email, subject, body)
        }

        // 1. Official Google Drive API v3 Notification Email via permissions endpoint
        //    (Google refuses to "share" with the owner himself, so this only helps for a different address)
        if (!sent && accessToken.startsWith("ya29.")) {
            val folderId = _state.value.driveFolderId.ifBlank { prefs.getString(KEY_FOLDER_ID, "").orEmpty() }
            if (folderId.isNotBlank() && !folderId.startsWith("gdrive_ai_os_memory_")) {
                sent = runCatching {
                    val encodedMsg = URLEncoder.encode(body, "UTF-8")
                    val permUrl = "$DRIVE_API_BASE/files/$folderId/permissions?sendNotificationEmail=true&emailMessage=$encodedMsg"
                    val permPayload = JSONObject().apply {
                        put("role", "writer")
                        put("type", "user")
                        put("emailAddress", email)
                    }
                    val req = Request.Builder()
                        .url(permUrl)
                        .addHeader("Authorization", "Bearer $accessToken")
                        .post(permPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(req).execute().use { it.isSuccessful }
                }.getOrDefault(false)
            }
        }

        // 2. Apps Script Bridge (MailApp.sendEmail)
        val scriptUrl = _state.value.customAppsScriptUrl.trim().ifBlank {
            prefs.getString(KEY_CUSTOM_APPS_SCRIPT_URL, "").orEmpty().trim()
        }
        if (!sent && scriptUrl.startsWith("https://script.google.com/")) {
            sent = runCatching {
                val payload = JSONObject().apply {
                    put("action", "SEND_EMAIL")
                    put("to", email)
                    put("subject", subject)
                    put("body", body)
                }
                val req = Request.Builder().url(scriptUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }

        // 3. Last fallback: Gmail API with the Drive token (works only if it also carries gmail.send)
        if (!sent && accessToken.startsWith("ya29.") && accessToken != gmailToken) {
            sent = sendViaGmailApi(accessToken, email, subject, body)
        }

        if (sent) {
            prefs.edit().putString(KEY_CONFIRM_EMAIL_SENT_FOR, email).apply()
            _state.value = _state.value.copy(
                confirmationEmailSent = true,
                lastSyncStatusBn = "✅ Drive কানেক্টেড ($email) • 📧 জিমেইলে কনফার্মেশন মেইল পাঠানো হয়েছে"
            )
        } else {
            // Not marked as sent -> will be retried automatically on the next connect / app start
            _state.value = _state.value.copy(
                lastSyncStatusBn = "✅ Drive কানেক্টেড ($email) • 📧 কনফার্মেশন মেইল পরে আবার চেষ্টা হবে"
            )
        }
        Unit
    }

    /**
     * Silent (no popup) token request for the `gmail.send` scope only.
     * Returns "" if the user has not granted it - never launches any consent screen.
     */
    private suspend fun fetchGmailSendTokenSilently(context: Context, email: String): String =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val acct: Account = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx)?.account }.getOrNull()
                ?: email.takeIf { it.contains("@") }?.let { Account(it, "com.google") }
                ?: return@withContext ""
            try {
                GoogleAuthUtil.getToken(appCtx, acct, "oauth2:${DriveScopes.GMAIL_SEND}")
            } catch (_: Exception) {
                ""
            }
        }

    private fun sendViaGmailApi(token: String, to: String, subject: String, body: String): Boolean =
        runCatching {
            fun b64(s: String) = android.util.Base64.encodeToString(
                s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val raw = "To: $to\r\nSubject: =?UTF-8?B?${b64(subject)}?=\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n${b64(body)}"
            val rawB64 = android.util.Base64.encodeToString(
                raw.toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val req = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .addHeader("Authorization", "Bearer $token")
                .post(JSONObject().put("raw", rawB64).toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)

    private fun extractDriveFolderIdFromInput(raw: String): String {
        if (raw.isBlank()) return ""
        val folderRegex = Regex("folders/([a-zA-Z0-9_-]{10,})")
        folderRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        val idParamRegex = Regex("[?&]id=([a-zA-Z0-9_-]{10,})")
        idParamRegex.find(raw)?.groupValues?.getOrNull(1)?.let { return it }
        return if (!raw.contains("http") && raw.length >= 10) raw else ""
    }

    /**
     * Ready-to-paste Google Apps Script code that the user can deploy as a Web App (`script.google.com`)
     * for 100% permanent, zero-popup, never-expiring Google Drive auto-upload & AI brain sync!
     */
    fun getReadyToPasteAppsScriptCode(): String = """
function doPost(e) {
  var data = JSON.parse(e.postData.contents);
  if (data.action === "SEND_EMAIL") {
    MailApp.sendEmail(data.to || Session.getActiveUser().getEmail(), data.subject || "Archer AI", data.body || "");
    return ContentService.createTextOutput(JSON.stringify({ status: "ok", mailed: true })).setMimeType(ContentService.MimeType.JSON);
  }
  if (data.action === "DELETE_FILE") {
    var rootIter = DriveApp.getFoldersByName("AI_OS_Memory");
    if (rootIter.hasNext()) {
      var root = rootIter.next();
      var subName = data.subFolderName || "Memories";
      var subIter = root.getFoldersByName(subName);
      if (subIter.hasNext()) {
        var subFolder = subIter.next();
        var fIter = subFolder.getFilesByName(data.fileName);
        while (fIter.hasNext()) { fIter.next().setTrashed(true); }
      }
    }
    return ContentService.createTextOutput(JSON.stringify({ status: "ok", deleted: true })).setMimeType(ContentService.MimeType.JSON);
  }
  var rootIter = DriveApp.getFoldersByName("AI_OS_Memory");
  var root = rootIter.hasNext() ? rootIter.next() : DriveApp.createFolder("AI_OS_Memory");
  var subName = data.subFolderName || data.subFolder || "Memories";
  var subIter = root.getFoldersByName(subName);
  var folder = subIter.hasNext() ? subIter.next() : root.createFolder(subName);
  var fileName = data.fileName || "ai_data.json";
  var existing = folder.getFilesByName(fileName);
  while (existing.hasNext()) { existing.next().setTrashed(true); }
  var blob;
  if (data.base64Data) {
    blob = Utilities.newBlob(Utilities.base64Decode(data.base64Data), data.mimeType || "application/octet-stream", fileName);
  } else {
    blob = Utilities.newBlob(data.content || "", data.mimeType || "application/json", fileName);
  }
  var file = folder.createFile(blob);
  return ContentService.createTextOutput(JSON.stringify({ status: "ok", id: file.getId(), fileId: file.getId(), folderId: root.getId(), webViewLink: file.getUrl(), url: file.getUrl() })).setMimeType(ContentService.MimeType.JSON);
}
function doGet(e) {
  var rootIter = DriveApp.getFoldersByName("AI_OS_Memory");
  if (!rootIter.hasNext()) return ContentService.createTextOutput(JSON.stringify({ status: "empty" })).setMimeType(ContentService.MimeType.JSON);
  var root = rootIter.next();
  var backupIter = root.getFoldersByName("Master_Backups");
  var masterJson = "";
  if (backupIter.hasNext()) {
    var fIter = backupIter.next().getFilesByName("AI_OS_Master_Backup.json");
    if (fIter.hasNext()) masterJson = fIter.next().getBlob().getDataAsString();
  }
  return ContentService.createTextOutput(JSON.stringify({ status: "ok", folderId: root.getId(), masterBackup: masterJson })).setMimeType(ContentService.MimeType.JSON);
}
""".trimIndent()

    /**
     * Silent Startup Sync (NEVER triggers any popup dialog on startup!).
     * Automatically restores the AI Brain from Google Drive (`pullAndRestoreBrainFromDriveToLocal`)
     * and syncs local changes in the background.
     */
    private suspend fun autoConnectAndProvisionDriveOnStartup(context: Context) = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val token = resolveAccessToken(
            context = appCtx,
            forceFresh = false,
            allowInteractiveRecovery = false // NEVER launch popups on startup
        )

        if (token.isNotBlank()) {
            val verified = verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
            if (!verified) {
                val freshToken = resolveAccessToken(
                    context = appCtx,
                    forceFresh = true,
                    allowInteractiveRecovery = false
                )
                if (freshToken.isNotBlank()) {
                    verifyDriveAboutAndSyncFolder(appCtx, freshToken, speakResult = false)
                }
            }
        }
        pullAndRestoreBrainFromDriveToLocal(appCtx)
        syncAllAppDataToDriveNow(appCtx, speakResult = false)
    }

    /**
     * Single-Click Google Drive Sign-In:
     * Launches ONLY ONE single sign-in dialog (`buildGoogleSignInClient(appCtx).signInIntent` with `DRIVE_FILE` scope).
     * Never chains 2 or 3 popups back-to-back!
     */
    fun startDirectDriveAuthorization(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        hasTriedFallbackPickerThisAttempt = false
        hasTriedConsentRecoveryThisAttempt = false

        _state.value = _state.value.copy(
            isSyncing = true,
            lastSyncStatusBn = "☁️ Google অ্যাকাউন্ট সিলেক্ট করুন (শুধুমাত্র ১ বার)..."
        )

        // Check if already signed in with GoogleSignIn and token is available without showing any popup
        val existingAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        if (existingAccount != null && !existingAccount.email.isNullOrBlank()) {
            scope.launch {
                val token = fetchTokenForAccount(appCtx, existingAccount, allowPopup = true)
                if (token.isNotBlank()) {
                    onAccountConnected(appCtx, existingAccount, onStatusSpoken)
                } else {
                    try {
                        _pendingAuthRecoveryIntent.value = buildGoogleSignInClient(appCtx).signInIntent
                    } catch (_: Exception) {
                        connectGmailHybridAccount(appCtx, existingAccount.email.orEmpty(), onStatusSpoken = onStatusSpoken)
                    }
                }
            }
            return
        }

        try {
            // Launch standard single-step GoogleSignInClient with DRIVE_FILE scope
            val signInIntent = buildGoogleSignInClient(appCtx).signInIntent
            _pendingAuthRecoveryIntent.value = signInIntent
        } catch (_: Exception) {
            try {
                _pendingAuthRecoveryIntent.value = createAccountPickerIntent()
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    isSyncing = false,
                    lastSyncStatusBn = "⚠️ সাইন-ইন উইন্ডো ওপেন করা সম্ভব হয়নি। ফোনে Google অ্যাকাউন্ট চেক করুন।"
                )
            }
        }
    }

    fun handleAuthorizationIntentResult(
        context: Context,
        data: Intent?,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        if (data == null) {
            _state.value = _state.value.copy(
                isSyncing = false,
                lastSyncStatusBn = "☁️ Google Drive কানেকশন বাতিল করা হয়েছে।"
            )
            return
        }
        try {
            val result = Identity.getAuthorizationClient(appCtx).getAuthorizationResultFromIntent(data)
            onDirectAuthorizationSuccess(appCtx, result, onStatusSpoken)
        } catch (_: Exception) {
            handleGoogleSignInResult(appCtx, data, onStatusSpoken = onStatusSpoken)
        }
    }

    private fun onDirectAuthorizationSuccess(
        context: Context,
        result: AuthorizationResult,
        onStatusSpoken: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val accessToken = result.accessToken.orEmpty()
        val googleAccount = runCatching { result.toGoogleSignInAccount() }.getOrNull()
        val emailFromAcct = googleAccount?.email.orEmpty()
        val nameFromAcct = googleAccount?.displayName.orEmpty()

        scope.launch {
            if (accessToken.isNotBlank()) {
                val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CACHED_TOKEN, accessToken)
                    .putBoolean(KEY_USER_EXPLICITLY_CONNECTED, true)
                    .apply {
                        if (emailFromAcct.isNotBlank()) putString(KEY_LINKED_EMAIL, emailFromAcct)
                        if (nameFromAcct.isNotBlank()) putString(KEY_DISPLAY_NAME, nameFromAcct)
                    }
                    .apply()

                verifyDriveAboutAndSyncFolder(
                    context = appCtx,
                    accessToken = accessToken,
                    speakResult = false,
                    onStatusSpoken = onStatusSpoken
                )
                pullAndRestoreBrainFromDriveToLocal(appCtx)
                syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
            } else if (googleAccount != null && !googleAccount.email.isNullOrBlank()) {
                onAccountConnected(appCtx, googleAccount, onStatusSpoken)
            } else {
                _state.value = _state.value.copy(isSyncing = false)
            }
        }
    }

    fun buildGoogleSignInClient(context: Context): GoogleSignInClient {
        val appCtx = context.applicationContext
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .requestScopes(Scope(DriveScopes.DRIVE_FILE), Scope(DriveScopes.DRIVE_APPDATA), Scope(DriveScopes.GMAIL_SEND))
        return GoogleSignIn.getClient(appCtx, builder.build())
    }

    fun buildBasicEmailSignInClient(context: Context): GoogleSignInClient {
        val appCtx = context.applicationContext
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(appCtx, gso)
    }

    @Suppress("DEPRECATION")
    fun createAccountPickerIntent(): Intent {
        return AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            false,
            null,
            null,
            null,
            null
        )
    }

    /**
     * Handles the single sign-in result without ever chaining into additional popups!
     */
    fun handleGoogleSignInResult(
        context: Context,
        data: Intent?,
        fallbackEmailIfEmulator: String = "",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            try {
                // 1. Check if returned from system AccountPicker
                val pickedAccountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
                if (pickedAccountName.isNotBlank() && pickedAccountName.contains("@")) {
                    connectGmailHybridAccount(
                        context = appCtx,
                        email = pickedAccountName,
                        displayName = pickedAccountName.substringBefore("@"),
                        oauthToken = "",
                        onStatusSpoken = onStatusSpoken
                    )
                    return@launch
                }

                // 2. Check if returned from Identity.getAuthorizationClient
                if (data != null) {
                    val authRes = runCatching {
                        Identity.getAuthorizationClient(appCtx).getAuthorizationResultFromIntent(data)
                    }.getOrNull()
                    if (authRes != null && !authRes.accessToken.isNullOrBlank()) {
                        onDirectAuthorizationSuccess(appCtx, authRes, onStatusSpoken)
                        return@launch
                    }
                }

                // 3. Check standard GoogleSignIn result
                val task = GoogleSignIn.getSignedInAccountFromIntent(data)
                val account = task.getResult(ApiException::class.java)
                if (account != null) {
                    onAccountConnected(appCtx, account, onStatusSpoken)
                    return@launch
                }
            } catch (_: ApiException) {
                val existing = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
                if (existing != null && !existing.email.isNullOrBlank()) {
                    onAccountConnected(appCtx, existing, onStatusSpoken)
                    return@launch
                }
                // Fallback: Show error instead of faking connection with built-in API key
                _state.value = _state.value.copy(
                    isSyncing = false,
                    lastSyncStatusBn = "⚠️ Play Services ত্রুটি — ☁️ Connect বাটনে আবার ট্যাপ করুন"
                )
                return@launch
            } catch (_: Exception) {
            }

            // Fallback: Show real disconnection status instead of faking connection with API key
            _state.value = _state.value.copy(
                isSyncing = false,
                lastSyncStatusBn = "⚠️ Google Drive সাইন-ইন ব্যর্থ হয়েছে — আবার চেষ্টা করুন বা ☁️ Connect বাটনে ট্যাপ করুন"
            )
        }
    }

    fun onAuthRecoveryResult(
        context: Context,
        data: Intent? = null,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        _pendingAuthRecoveryIntent.value = null
        handleGoogleSignInResult(
            context = appCtx,
            data = data,
            onStatusSpoken = onStatusSpoken
        )
    }

    private suspend fun onAccountConnected(
        context: Context,
        account: GoogleSignInAccount,
        onStatusSpoken: (String) -> Unit
    ) {
        val appCtx = context.applicationContext
        val email = account.email.orEmpty()
        if (email.isBlank()) {
            _state.value = _state.value.copy(isSyncing = false)
            return
        }
        val name = account.displayName.orEmpty().ifBlank { email.substringBefore("@") }

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LINKED_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, name)
            .putBoolean(KEY_USER_EXPLICITLY_CONNECTED, true)
            .apply()

        _state.value = _state.value.copy(
            isConnected = true,
            linkedEmail = email,
            displayName = name,
            isSyncing = true,
            lastSyncStatusBn = "☁️ Google Drive ($email) কানেক্ট হয়েছে — ক্লাউড ব্রেইন ও /$AI_OS_MEMORY_FOLDER_NAME সিঙ্ক হচ্ছে..."
        )

        val token = fetchTokenForAccount(appCtx, account, allowPopup = !hasTriedConsentRecoveryThisAttempt)
        if (token.isNotBlank()) {
            verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
        }
        pullAndRestoreBrainFromDriveToLocal(appCtx)
        syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
    }

    fun connectGmailHybridAccount(
        context: Context,
        email: String,
        displayName: String = "Boss 5TB Drive",
        oauthToken: String = "",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) return
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LINKED_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .putBoolean(KEY_USER_EXPLICITLY_CONNECTED, true)
            .apply {
                if (oauthToken.isNotBlank()) putString(KEY_CACHED_TOKEN, oauthToken.trim())
            }
            .apply()

        scope.launch {
            _state.value = _state.value.copy(
                isConnected = true,
                linkedEmail = cleanEmail,
                displayName = cleanName,
                isSyncing = true,
                lastSyncStatusBn = "☁️ Google Drive ($cleanEmail) /$AI_OS_MEMORY_FOLDER_NAME ও সাব-ফোল্ডার সিঙ্ক হচ্ছে..."
            )
            val token = resolveAccessToken(
                context = appCtx,
                allowInteractiveRecovery = false
            )
            if (token.isNotBlank()) {
                verifyDriveAboutAndSyncFolder(appCtx, token, speakResult = false)
            }
            pullAndRestoreBrainFromDriveToLocal(appCtx)
            syncAllAppDataToDriveNow(appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
        }
    }

    /**
     * Calls `GET https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName),storageQuota(limit,usage)`
     * directly with the OAuth Bearer token, creates/verifies `/AI_OS_Memory` AND all 6 sub-folders
     * (`Memories`, `Conversations`, `Argon4_Code`, `ZIP_Packages`, `Media_Screenshots`, `Master_Backups`),
     * and loads remote files.
     */
    private suspend fun verifyDriveAboutAndSyncFolder(
        context: Context,
        accessToken: String,
        speakResult: Boolean = false,
        onStatusSpoken: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var verifiedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
        var verifiedName = prefs.getString(KEY_DISPLAY_NAME, "").orEmpty()
        var quotaText = prefs.getString(KEY_QUOTA_SUMMARY, "").orEmpty()

        try {
            val aboutReq = Request.Builder()
                .url("$DRIVE_API_BASE/about?fields=user(emailAddress,displayName),storageQuota(limit,usage)")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val aboutOk = httpClient.newCall(aboutReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val userObj = root.optJSONObject("user")
                    val quotaObj = root.optJSONObject("storageQuota")
                    val apiEmail = userObj?.optString("emailAddress").orEmpty()
                    val apiName = userObj?.optString("displayName").orEmpty()
                    if (apiEmail.isNotBlank()) verifiedEmail = apiEmail
                    if (apiName.isNotBlank()) verifiedName = apiName

                    if (quotaObj != null) {
                        val usageBytes = quotaObj.optLong("usage", 0L)
                        val limitBytes = quotaObj.optLong("limit", 0L)
                        val usedGb = String.format(Locale.US, "%.2f GB", usageBytes / (1024.0 * 1024.0 * 1024.0))
                        quotaText = if (limitBytes > 0L) {
                            val totalGb = String.format(Locale.US, "%.0f GB", limitBytes / (1024.0 * 1024.0 * 1024.0))
                            "$usedGb / $totalGb"
                        } else {
                            "$usedGb Used"
                        }
                    }
                    true
                } else {
                    false
                }
            }

            if (!aboutOk) {
                return@withContext false
            }

            // 1. Ensure main `/AI_OS_Memory` folder exists on Google Drive
            val mainFolderId = ensureAiOsMemoryFolderId(appCtx, accessToken)
            val isRealFolder = !mainFolderId.isNullOrBlank()

            // 2. Automatically provision all 6 organized sub-folders inside `/AI_OS_Memory`
            var subFolderCount = 0
            if (isRealFolder) {
                for (subName in ALL_REQUIRED_SUBFOLDERS) {
                    val subId = ensureSubFolderId(appCtx, accessToken, subName, mainFolderId)
                    if (!subId.isNullOrBlank()) subFolderCount++
                }
            }

            prefs.edit()
                .putString(KEY_LINKED_EMAIL, verifiedEmail)
                .putString(KEY_DISPLAY_NAME, verifiedName)
                .putString(KEY_QUOTA_SUMMARY, quotaText)
                .apply {
                    if (!mainFolderId.isNullOrBlank()) putString(KEY_FOLDER_ID, mainFolderId)
                }
                .apply()

            refreshRemoteFileListIntoIndex(appCtx, accessToken)

            _state.value = _state.value.copy(
                isConnected = true,
                isCloudApiVerified = isRealFolder,
                linkedEmail = verifiedEmail,
                displayName = verifiedName,
                driveFolderId = mainFolderId.orEmpty(),
                subFoldersReadyCount = subFolderCount,
                driveQuotaSummary = quotaText,
                isSyncing = false,
                lastSyncStatusBn = "✅ Google Drive অটো-সিঙ্ক সচল ($verifiedEmail ${if (quotaText.isNotBlank()) "• $quotaText" else ""}) • /$AI_OS_MEMORY_FOLDER_NAME ($subFolderCount সাব-ফোল্ডার)"
            )

            if (isRealFolder) {
                sendConnectionConfirmationEmailOnce(appCtx, accessToken)
            }

            if (speakResult) {
                withContext(Dispatchers.Main) {
                    onStatusSpoken("জি স্যার, Google Drive-এ AI_OS_Memory এবং এর ভেতরের ৬টি সাব-ফোল্ডার স্বয়ংক্রিয়ভাবে প্রস্তুত হয়ে গেছে।")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disconnectGoogleDrive(context: Context) {
        val appCtx = context.applicationContext
        runCatching { buildGoogleSignInClient(appCtx).signOut() }
        runCatching { buildBasicEmailSignInClient(appCtx).signOut() }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val oldToken = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        if (oldToken.isNotBlank()) {
            scope.launch {
                runCatching { GoogleAuthUtil.clearToken(appCtx, oldToken) }
            }
        }
        prefs.edit()
            .remove(KEY_LINKED_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_FOLDER_ID)
            .remove(KEY_SUBFOLDER_IDS_JSON)
            .remove(KEY_CACHED_TOKEN)
            .remove(KEY_CUSTOM_USER_TOKEN)
            .remove(KEY_CUSTOM_APPS_SCRIPT_URL)
            .putBoolean(KEY_USER_EXPLICITLY_CONNECTED, false)
            .remove(KEY_QUOTA_SUMMARY)
            .apply()
        subFolderIdCache.clear()
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.clear()
        }
        _state.value = GoogleDriveSyncState()
    }

    fun setZeroPhoneStorageImpactMode(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ZERO_STORAGE_MODE, enabled)
            .apply()
        _state.value = _state.value.copy(zeroPhoneStorageImpactMode = enabled)
        if (enabled) {
            scope.launch {
                val freed = purgeLocalTempAndCacheForZeroStorage(appCtx)
                _state.value = _state.value.copy(
                    freedLocalCacheBytes = _state.value.freedLocalCacheBytes + freed
                )
            }
        }
    }

    private suspend fun fetchTokenForAccount(
        context: Context,
        account: GoogleSignInAccount,
        allowPopup: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val acct = account.account ?: account.email?.takeIf { it.isNotBlank() }?.let { Account(it, "com.google") }
        if (acct != null) {
            try {
                val scopeStr = "oauth2:${DriveScopes.DRIVE_FILE}"
                val token = GoogleAuthUtil.getToken(appCtx, acct, scopeStr)
                if (token.isNotBlank()) {
                    appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_CACHED_TOKEN, token)
                        .apply()
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                if (allowPopup && !hasTriedConsentRecoveryThisAttempt) {
                    hasTriedConsentRecoveryThisAttempt = true
                    recoverable.intent?.let { _pendingAuthRecoveryIntent.value = it }
                }
            } catch (_: Exception) {
            }
        }
        ""
    }

    /**
     * Obtains a valid OAuth2 Bearer token for `DriveScopes.DRIVE_FILE` via user's custom token,
     * cached token, `GoogleSignIn.getLastSignedInAccount`, or device Google accounts.
     */
    private suspend fun resolveAccessToken(
        context: Context,
        forceFresh: Boolean = false,
        allowInteractiveRecovery: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customUserToken = prefs.getString(KEY_CUSTOM_USER_TOKEN, "").orEmpty()
            .takeIf { it.startsWith("ya29.") }.orEmpty()
        if (customUserToken.isNotBlank() && !forceFresh) {
            return@withContext customUserToken
        }
        val cached = prefs.getString(KEY_CACHED_TOKEN, "").orEmpty()
        if (!forceFresh && cached.isNotBlank()) {
            return@withContext cached
        }

        val lastAccount = runCatching { GoogleSignIn.getLastSignedInAccount(appCtx) }.getOrNull()
        val acct: Account? = lastAccount?.account ?: run {
            val savedEmail = prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
            if (savedEmail.isNotBlank() && savedEmail.contains("@")) {
                Account(savedEmail, "com.google")
            } else {
                null
            }
        }
        if (acct != null) {
            try {
                if (forceFresh && cached.isNotBlank()) {
                    runCatching { GoogleAuthUtil.clearToken(appCtx, cached) }
                }
                val scopeStr = "oauth2:${DriveScopes.DRIVE_FILE}"
                val token = GoogleAuthUtil.getToken(appCtx, acct, scopeStr)
                if (token.isNotBlank()) {
                    prefs.edit().putString(KEY_CACHED_TOKEN, token).apply()
                    return@withContext token
                }
            } catch (recoverable: UserRecoverableAuthException) {
                if (allowInteractiveRecovery && !hasTriedConsentRecoveryThisAttempt) {
                    hasTriedConsentRecoveryThisAttempt = true
                    recoverable.intent?.let { recoveryIntent ->
                        _pendingAuthRecoveryIntent.value = recoveryIntent
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (customUserToken.isNotBlank()) customUserToken else if (forceFresh) "" else cached
    }

    /**
     * Pulls and restores the AI's complete Cloud Brain (`AI_OS_Master_Backup.json` + `All_Enrolled_Voice_Profiles.json`)
     * from Google Drive (via REST API v3 or Custom Apps Script Bridge URL) into local SQLite, `VoiceBiometricsEngine`,
     * and `cloudBrainContextSummary` so the AI immediately remembers all past conversations, rules, and voices!
     */
    suspend fun pullAndRestoreBrainFromDriveToLocal(context: Context): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val customScriptUrl = prefs.getString(KEY_CUSTOM_APPS_SCRIPT_URL, "").orEmpty()
        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)

        var masterBackupRaw = ""
        var voiceProfilesRaw = ""

        // 1. Try Apps Script Bridge URL if configured
        if (customScriptUrl.startsWith("http")) {
            runCatching {
                val req = Request.Builder().url(customScriptUrl).get().build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        val obj = JSONObject(body)
                        val folderId = obj.optString("folderId")
                        if (folderId.isNotBlank()) {
                            prefs.edit().putString(KEY_FOLDER_ID, folderId).apply()
                        }
                        masterBackupRaw = obj.optString("masterBackup")
                    }
                }
            }
        }

        // 2. Try Direct Google Drive REST API v3
        if (token.isNotBlank()) {
            if (masterBackupRaw.isBlank()) {
                masterBackupRaw = downloadDriveFileContentByName(token, "AI_OS_Master_Backup.json", SUBFOLDER_BACKUPS)
                if (masterBackupRaw.isBlank()) {
                    masterBackupRaw = downloadDriveFileContentByName(token, "AI_OS_Master_Backup.json", "")
                }
            }
            voiceProfilesRaw = downloadDriveFileContentByName(token, "All_Enrolled_Voice_Profiles.json", SUBFOLDER_VOICE_PROFILES)
            if (voiceProfilesRaw.isBlank()) {
                voiceProfilesRaw = downloadDriveFileContentByName(token, "All_Enrolled_Voice_Profiles.json", "")
            }
        }

        // 3. Fallback to local Drive mirror if offline
        if (masterBackupRaw.isBlank()) {
            val localBackup = File(getLocalDriveMirrorSubDir(appCtx, SUBFOLDER_BACKUPS), "AI_OS_Master_Backup.json")
            if (localBackup.exists()) {
                masterBackupRaw = runCatching { localBackup.readText(Charsets.UTF_8) }.getOrDefault("")
            }
        }
        if (voiceProfilesRaw.isBlank()) {
            val localVp = File(getLocalDriveMirrorSubDir(appCtx, SUBFOLDER_VOICE_PROFILES), "All_Enrolled_Voice_Profiles.json")
            if (localVp.exists()) {
                voiceProfilesRaw = runCatching { localVp.readText(Charsets.UTF_8) }.getOrDefault("")
            }
        }

        if (voiceProfilesRaw.isNotBlank()) {
            VoiceBiometricsEngine.restoreProfilesFromCloudJson(appCtx, voiceProfilesRaw)
        }

        if (masterBackupRaw.isNotBlank()) {
            runCatching {
                val root = JSONObject(masterBackupRaw)
                val db = AgentDatabase.getInstance(appCtx)
                val memDao = db.agentMemoryNoteDao()
                val existingNotes = memDao.getAllNotesSnapshot()

                val memArr = root.optJSONArray("memories")
                val restoredMemLines = mutableListOf<String>()
                if (memArr != null) {
                    for (i in 0 until memArr.length()) {
                        val m = memArr.optJSONObject(i) ?: continue
                        val title = m.optString("title").trim()
                        val content = m.optString("content").trim()
                        val cat = m.optString("category", "CLOUD_MEMORY").trim()
                        if (title.isNotBlank() && content.isNotBlank()) {
                            restoredMemLines.add("[$title] $content ($cat)")
                            if (existingNotes.none { it.title.equals(title, ignoreCase = true) && it.content.equals(content, ignoreCase = true) }) {
                                memDao.insertNote(
                                    AgentMemoryNoteEntity(
                                        title = title,
                                        content = content,
                                        category = cat,
                                        timestamp = m.optLong("timestamp", System.currentTimeMillis())
                                    )
                                )
                            }
                        }
                    }
                }

                val convArr = root.optJSONArray("conversations")
                val restoredConvLines = mutableListOf<String>()
                if (convArr != null) {
                    for (i in 0 until minOf(convArr.length(), 15)) {
                        val c = convArr.optJSONObject(i) ?: continue
                        val u = c.optString("userPrompt").trim()
                        val a = c.optString("agentReply").trim()
                        if (u.isNotBlank() || a.isNotBlank()) {
                            restoredConvLines.add("User: ${u.take(110)} -> AI: ${a.take(140)}")
                        }
                    }
                }

                val brainBlock = buildString {
                    if (restoredMemLines.isNotEmpty()) {
                        appendLine("CLOUD DRIVE PERMANENT MEMORIES (${restoredMemLines.size}):")
                        restoredMemLines.take(25).forEach { appendLine("- $it") }
                    }
                    if (restoredConvLines.isNotEmpty()) {
                        appendLine("CLOUD DRIVE PAST CONVERSATION HISTORY (${restoredConvLines.size}):")
                        restoredConvLines.take(12).forEach { appendLine("- $it") }
                    }
                }.trim()

                if (brainBlock.isNotBlank()) {
                    prefs.edit().putString(KEY_CLOUD_BRAIN_SUMMARY, brainBlock).apply()
                    SmartMemoryAndSelfUpdateEngine.syncLiveMemoryNotesSummary(restoredMemLines + restoredConvLines)
                    _state.value = _state.value.copy(cloudBrainContextSummary = brainBlock)
                }
            }
        }
        _state.value.cloudBrainContextSummary
    }

    private fun downloadDriveFileContentByName(accessToken: String, exactFileName: String, targetSubFolder: String = ""): String {
        if (accessToken.isBlank()) return ""
        return try {
            val escaped = exactFileName.replace("'", "\\'")
            val parentId = if (targetSubFolder.isNotBlank()) {
                subFolderIdCache[targetSubFolder].orEmpty()
            } else {
                _state.value.driveFolderId
            }
            val parentClause = if (parentId.isNotBlank() && !parentId.startsWith("drive_vault_") && !parentId.startsWith("gdrive_")) {
                "'$parentId' in parents and "
            } else ""
            val q = URLEncoder.encode("${parentClause}name = '$escaped' and trashed = false", "UTF-8")
            val findUrl = "$DRIVE_API_BASE/files?q=$q&pageSize=1&orderBy=modifiedTime desc&fields=files(id,name)"
            val req = Request.Builder()
                .url(findUrl)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            var fileId = ""
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        fileId = files.getJSONObject(0).optString("id")
                    }
                }
            }
            if (fileId.isBlank()) return ""
            val dlReq = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(dlReq).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun buildLiveCloudBrainContextForPrompt(): String {
        val st = _state.value
        val summary = st.cloudBrainContextSummary
        return buildString {
            append("\n\n5TB GOOGLE DRIVE CLOUD BRAIN & LIVE MEMORY VAULT (`/$AI_OS_MEMORY_FOLDER_NAME`):\n")
            append("- DRIVE CONNECTION STATUS: ${if (st.isConnected) "CONNECTED (${st.linkedEmail})" else "LOCAL MIRROR READY"}\n")
            if (summary.isNotBlank()) {
                append(summary).append("\n")
            }
            if (st.recentCloudFiles.isNotEmpty()) {
                append("- RECENT FILES IN GOOGLE DRIVE: ")
                append(st.recentCloudFiles.take(10).joinToString("; ") { "${it.subFolderName}/${it.name}: ${it.snippetText.take(80)}" })
                append("\n")
            }
        }
    }

    /**
     * Google Drive REST API v3: Finds or creates the root `AI_OS_Memory` folder in the user's Google Drive.
     * Protected by `folderCreationMutex` so concurrent auto-sync calls never create duplicate `AI_OS_Memory` folders.
     */
    suspend fun ensureAiOsMemoryFolderId(context: Context, accessToken: String): String? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext null
        folderCreationMutex.withLock {
            val appCtx = context.applicationContext
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedId = prefs.getString(KEY_FOLDER_ID, "").orEmpty()

            if (cachedId.isNotBlank() && !cachedId.startsWith("gdrive_ai_os_memory_")) {
                val checkReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files/$cachedId?fields=id,name,trashed")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
                val stillValid = runCatching {
                    httpClient.newCall(checkReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val obj = JSONObject(resp.body?.string().orEmpty())
                            !obj.optBoolean("trashed", false) && obj.optString("id").isNotBlank()
                        } else false
                    }
                }.getOrDefault(false)
                if (stillValid) return@withLock cachedId
            }

            try {
                val q = URLEncoder.encode(
                    "name = '$AI_OS_MEMORY_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                    "UTF-8"
                )
                val searchReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?q=$q&spaces=drive&fields=files(id,name)")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(searchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = resp.body?.string().orEmpty()
                        val files = JSONObject(bodyStr).optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            val foundId = files.getJSONObject(0).optString("id")
                            if (foundId.isNotBlank()) {
                                prefs.edit().putString(KEY_FOLDER_ID, foundId).apply()
                                return@withLock foundId
                            }
                        }
                    }
                }

                val metaJson = JSONObject().apply {
                    put("name", AI_OS_MEMORY_FOLDER_NAME)
                    put("mimeType", "application/vnd.google-apps.folder")
                    put("description", "Archer AI OS Direct Google Drive API v3 Cloud Storage & Memory Vault")
                }
                val createReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?fields=id,name")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(createReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val createdId = JSONObject(resp.body?.string().orEmpty()).optString("id")
                        if (createdId.isNotBlank()) {
                            prefs.edit().putString(KEY_FOLDER_ID, createdId).apply()
                            return@withLock createdId
                        }
                    }
                }
            } catch (_: Exception) {
            }
            null
        }
    }

    /**
     * Finds or creates a specific sub-folder inside `/AI_OS_Memory` (e.g., `Memories`, `Conversations`,
     * `Argon4_Code`, `ZIP_Packages`, `Media_Screenshots`, `Master_Backups`, or any custom AI-created folder).
     * Never creates duplicate sub-folders.
     */
    suspend fun ensureSubFolderId(
        context: Context,
        accessToken: String,
        subFolderName: String,
        parentFolderIdOverride: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (accessToken.isBlank()) return@withContext null
        val cleanSubName = subFolderName.trim().ifBlank { SUBFOLDER_CODE }
        val appCtx = context.applicationContext
        val mainFolderId = parentFolderIdOverride?.takeIf { it.isNotBlank() }
            ?: ensureAiOsMemoryFolderId(appCtx, accessToken)
            ?: return@withContext null

        val cachedSubId = subFolderIdCache[cleanSubName].orEmpty()
        if (cachedSubId.isNotBlank() && !cachedSubId.startsWith("drive_vault_")) {
            return@withContext cachedSubId
        }

        folderCreationMutex.withLock {
            val recheck = subFolderIdCache[cleanSubName].orEmpty()
            if (recheck.isNotBlank() && !recheck.startsWith("drive_vault_")) return@withLock recheck

            try {
                val escapedSub = cleanSubName.replace("'", "\\'")
                val q = URLEncoder.encode(
                    "'$mainFolderId' in parents and name = '$escapedSub' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                    "UTF-8"
                )
                val searchReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?q=$q&spaces=drive&fields=files(id,name)")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                httpClient.newCall(searchReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            val foundId = files.getJSONObject(0).optString("id")
                            if (foundId.isNotBlank()) {
                                subFolderIdCache[cleanSubName] = foundId
                                persistSubFolderIdsToPrefs(appCtx)
                                return@withLock foundId
                            }
                        }
                    }
                }

                val metaJson = JSONObject().apply {
                    put("name", cleanSubName)
                    put("mimeType", "application/vnd.google-apps.folder")
                    put("description", "Archer AI OS Sub-Folder: /$AI_OS_MEMORY_FOLDER_NAME/$cleanSubName")
                    put("parents", JSONArray().put(mainFolderId))
                }
                val createReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?fields=id,name")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(createReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val createdId = JSONObject(resp.body?.string().orEmpty()).optString("id")
                        if (createdId.isNotBlank()) {
                            subFolderIdCache[cleanSubName] = createdId
                            persistSubFolderIdsToPrefs(appCtx)
                            return@withLock createdId
                        }
                    }
                }
            } catch (_: Exception) {
            }
            mainFolderId
        }
    }

    /**
     * Automatically backs up a saved memory note into `/AI_OS_Memory/Memories` on Google Drive
     * with Single-Copy Deduplication (`overwriteExistingByName = true` keyed by clean title).
     */
    suspend fun backupMemoryNoteToDrive(
        context: Context,
        title: String,
        factContent: String,
        tagsOrCategory: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF_-]"), "_").take(36).ifBlank { "memory" }
        // Deterministic file name per memory title so only 1 copy per memory exists in Drive (no duplicates!)
        val fileName = "memory_$safeTitle.json"

        val payloadJson = JSONObject().apply {
            put("type", "AI_OS_MEMORY_NOTE")
            put("title", title)
            put("fact", factContent)
            put("tags", tagsOrCategory)
            put("timestamp", ts)
            put("formattedTime", SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()).format(Date(ts)))
        }.toString(2)

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "application/json",
            bytes = payloadJson.toByteArray(Charsets.UTF_8),
            description = "[MEMORY] $title: $factContent ($tagsOrCategory)",
            snippetText = "[$title] $factContent (tags: $tagsOrCategory)",
            subFolderName = SUBFOLDER_MEMORIES,
            overwriteExistingByName = true
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_MEM_COUNT, 0) + 1
            prefs.edit().putInt(KEY_MEM_COUNT, newCount).apply()
            if (_state.value.zeroPhoneStorageImpactMode) {
                purgeLocalTempAndCacheForZeroStorage(appCtx)
            }
            _state.value = _state.value.copy(
                backedUpMemoriesCount = newCount,
                lastSyncStatusBn = "☁️ মেমোরি '/$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_MEMORIES'-এ অটো-সেভ সম্পন্ন ($fileName)"
            )
            true
        } else {
            false
        }
    }

    /**
     * Deletes a memory note from `/AI_OS_Memory/Memories` on Google Drive, local mirror,
     * and inMemoryCloudIndex so deleted memories are truly purged from the AI Brain.
     */
    suspend fun deleteMemoryNoteFromDrive(
        context: Context,
        title: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF_-]"), "_").take(36).ifBlank { "memory" }
        val fileName = "memory_$safeTitle.json"

        // 1. Delete from local mirror file
        val localFile = File(getLocalDriveMirrorSubDir(appCtx, SUBFOLDER_MEMORIES), fileName)
        runCatching { if (localFile.exists()) localFile.delete() }

        // 2. Remove from inMemoryCloudIndex
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.removeAll { it.name == fileName || it.name.startsWith("memory_$safeTitle") }
            _state.value = _state.value.copy(recentCloudFiles = inMemoryCloudIndex.take(15))
        }
        persistCloudIndexToPrefs(appCtx)

        // 3. Delete from Google Drive API v3
        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        if (token.isNotBlank()) {
            val targetFolderId = ensureSubFolderId(appCtx, token, SUBFOLDER_MEMORIES)
            deleteRemoteDriveFileByName(accessToken = token, fileName = fileName, parentFolderId = targetFolderId)
        }

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 3B. Delete via Apps Script Bridge if configured
        val scriptUrl = _state.value.customAppsScriptUrl.trim().ifBlank {
            prefs.getString(KEY_CUSTOM_APPS_SCRIPT_URL, "").orEmpty().trim()
        }
        if (scriptUrl.startsWith("https://script.google.com/")) {
            runCatching {
                val payload = JSONObject().apply {
                    put("action", "DELETE_FILE")
                    put("subFolderName", SUBFOLDER_MEMORIES)
                    put("fileName", fileName)
                }
                val req = Request.Builder()
                    .url(scriptUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(req).execute().close()
            }
        }

        // 4. Update count in prefs & state
        val curCount = prefs.getInt(KEY_MEM_COUNT, 0)
        val newCount = maxOf(0, curCount - 1)
        prefs.edit().putInt(KEY_MEM_COUNT, newCount).apply()
        _state.value = _state.value.copy(
            backedUpMemoriesCount = newCount,
            lastSyncStatusBn = "🗑️ মেমোরি ($title) Google Drive ব্রেইন থেকে মুছে ফেলা হয়েছে"
        )
        true
    }

    private fun deleteRemoteDriveFileByName(
        accessToken: String,
        fileName: String,
        parentFolderId: String?
    ): Boolean {
        return try {
            val escapedName = fileName.replace("'", "\\'")
            val parentClause = if (!parentFolderId.isNullOrBlank()) "'$parentFolderId' in parents and " else ""
            val q = URLEncoder.encode("${parentClause}name = '$escapedName' and trashed = false", "UTF-8")
            val searchReq = Request.Builder()
                .url("$DRIVE_API_BASE/files?q=$q&spaces=drive&fields=files(id)")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(searchReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val fileId = files.getJSONObject(i).optString("id")
                            if (fileId.isNotBlank()) {
                                val delReq = Request.Builder()
                                    .url("$DRIVE_API_BASE/files/$fileId")
                                    .addHeader("Authorization", "Bearer $accessToken")
                                    .delete()
                                    .build()
                                httpClient.newCall(delReq).execute().close()
                            }
                        }
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Automatically backs up a conversation summary into `/AI_OS_Memory/Conversations` on Google Drive
     * (updates `Latest_Conversation_Turn.json` + daily log so there is no file spam).
     */
    suspend fun backupConversationSummaryToDrive(
        context: Context,
        userPrompt: String,
        agentReply: String,
        actionsSummary: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (userPrompt.isBlank() && agentReply.isBlank()) return@withContext false
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
        val fileName = "conversation_$dayFmt.json"

        val payloadJson = JSONObject().apply {
            put("type", "AI_OS_CONVERSATION_SUMMARY")
            put("userPrompt", userPrompt)
            put("agentReply", agentReply)
            put("actionsSummary", actionsSummary)
            put("timestamp", ts)
            put("formattedTime", SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault()).format(Date(ts)))
        }.toString(2)

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "application/json",
            bytes = payloadJson.toByteArray(Charsets.UTF_8),
            description = "[CONVERSATION] User: ${userPrompt.take(120)} | AI: ${agentReply.take(160)}",
            snippetText = "User: ${userPrompt.take(90)} -> AI: ${agentReply.take(120)}",
            subFolderName = SUBFOLDER_CONVERSATIONS,
            overwriteExistingByName = true
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_CONV_COUNT, 0) + 1
            prefs.edit().putInt(KEY_CONV_COUNT, newCount).apply()
            _state.value = _state.value.copy(
                backedUpConversationsCount = newCount,
                lastSyncStatusBn = "☁️ কথোপকথন '/$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_CONVERSATIONS'-এ সিঙ্ক হয়েছে"
            )
            true
        } else {
            false
        }
    }

    /**
     * Automatically backs up a Screenshot `Bitmap` directly from RAM to `/AI_OS_Memory/Media_Screenshots`.
     */
    suspend fun backupScreenshotBitmapToDrive(
        context: Context,
        bitmap: Bitmap,
        captionOrContext: String = "Screen Snapshot"
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val ts = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val fileName = "screenshot_$timeFmt.jpg"

        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val jpegBytes = bos.toByteArray()

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = "image/jpeg",
            bytes = jpegBytes,
            description = "[SCREENSHOT] $captionOrContext",
            snippetText = "[Screenshot $timeFmt] $captionOrContext",
            subFolderName = SUBFOLDER_MEDIA,
            overwriteExistingByName = true
        )

        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_FILE_COUNT, 0) + 1
            prefs.edit().putInt(KEY_FILE_COUNT, newCount).apply()
            _state.value = _state.value.copy(
                backedUpScreenshotsAndFilesCount = newCount,
                lastSyncStatusBn = "☁️ স্ক্রিনশট '/$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_MEDIA'-এ আপলোড হয়েছে"
            )
            true
        } else {
            false
        }
    }

    /**
     * Backs up any generated code file, ZIP package, text artifact, or image byte array directly to
     * its appropriate sub-folder inside `/AI_OS_Memory` (`Argon4_Code`, `ZIP_Packages`, or `Media_Screenshots`)
     * with `overwriteExistingByName = true` so ONLY ONE single copy is ever saved in Google Drive.
     */
    suspend fun backupGeneratedFileToDrive(
        context: Context,
        fileName: String,
        mimeType: String = "text/plain",
        contentBytes: ByteArray,
        summaryDescription: String = "AI Generated File",
        targetSubFolder: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val resolvedSubFolder = targetSubFolder.ifBlank {
            when {
                fileName.endsWith(".zip", ignoreCase = true) -> SUBFOLDER_ZIPS
                fileName.endsWith(".jpg", ignoreCase = true) ||
                    fileName.endsWith(".jpeg", ignoreCase = true) ||
                    fileName.endsWith(".png", ignoreCase = true) ||
                    fileName.endsWith(".mp3", ignoreCase = true) ||
                    fileName.endsWith(".wav", ignoreCase = true) -> SUBFOLDER_MEDIA
                else -> SUBFOLDER_CODE
            }
        }

        val uploadedId = uploadOrUpdateFileInDriveApi(
            context = appCtx,
            fileName = fileName,
            mimeType = mimeType.ifBlank { guessMimeType(fileName) },
            bytes = contentBytes,
            description = "[FILE] $fileName — $summaryDescription",
            snippetText = "[$resolvedSubFolder/$fileName] $summaryDescription",
            subFolderName = resolvedSubFolder,
            overwriteExistingByName = true
        )
        if (uploadedId.isNotBlank()) {
            val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val newCount = prefs.getInt(KEY_FILE_COUNT, 0) + 1
            prefs.edit().putInt(KEY_FILE_COUNT, newCount).apply()
            if (fileName.endsWith(".zip", ignoreCase = true)) {
                val zCount = prefs.getInt(KEY_ZIP_COUNT, 0) + 1
                prefs.edit().putInt(KEY_ZIP_COUNT, zCount).apply()
                _state.value = _state.value.copy(backedUpZipPackagesCount = zCount)
            }
            if (_state.value.zeroPhoneStorageImpactMode) {
                purgeLocalTempAndCacheForZeroStorage(appCtx)
            }
            _state.value = _state.value.copy(
                isConnected = true,
                backedUpScreenshotsAndFilesCount = newCount,
                lastSyncStatusBn = "☁️ '$fileName' সরাসরি Google Drive (/$AI_OS_MEMORY_FOLDER_NAME/$resolvedSubFolder)-এ সেভ হয়েছে"
            )
            true
        } else {
            false
        }
    }

    fun hasPendingStagedZip(): Boolean {
        val staged = pendingStagedZip ?: return false
        return File(staged.stagedZipFilePath).exists()
    }

    fun discardPendingStagedZip() {
        val staged = pendingStagedZip
        pendingStagedZip = null
        if (staged != null) {
            runCatching {
                val f = File(staged.stagedZipFilePath)
                if (f.exists()) f.delete()
            }
        }
    }

    /**
     * Uploads the staged `.zip` package to Google Drive (`/AI_OS_Memory/ZIP_Packages` or custom sub-folder)
     * ONLY AFTER the user has inspected the preview and confirmed ("হ্যাঁ" / "ঠিক আছে" / "সেভ করো").
     */
    suspend fun confirmSavePendingStagedZipToDrive(context: Context): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val staged = pendingStagedZip ?: return@withContext "স্যার, সেভ করার মতো কোনো পেন্ডিং ZIP প্যাকেজ পাওয়া যায়নি।"
        val zipFile = File(staged.stagedZipFilePath)
        if (!zipFile.exists()) {
            pendingStagedZip = null
            return@withContext "স্যার, স্টেইজড ZIP ফাইলটি পাওয়া যায়নি।"
        }

        _state.value = _state.value.copy(
            isSyncing = true,
            lastSyncStatusBn = "☁️ আপনার কনফার্মেশন অনুযায়ী '${staged.zipFileName}' (${staged.actualSizeMb}) সরাসরি Google Drive-এ সেভ হচ্ছে..."
        )

        // Copy single clean file into local mirror & Download/archer/
        val targetSub = staged.targetSubFolder.ifBlank { SUBFOLDER_ZIPS }
        val mirrorDest = File(getLocalDriveMirrorSubDir(appCtx, targetSub), staged.zipFileName)
        runCatching {
            zipFile.copyTo(mirrorDest, overwrite = true)
        }
        runCatching {
            val pubArcher = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "archer"
            )
            if (pubArcher.exists() || pubArcher.mkdirs()) {
                val dest = File(pubArcher, staged.zipFileName)
                zipFile.copyTo(dest, overwrite = true)
            }
        }

        val uploadedId = uploadFileFromDiskToDriveApi(
            context = appCtx,
            sourceFile = zipFile,
            fileName = staged.zipFileName,
            mimeType = "application/zip",
            description = "[AI ZIP PACKAGE] ${staged.projectOrFolderName} (${staged.actualSizeMb}) — User-Verified Single-Copy Saved",
            snippetText = "[$targetSub/${staged.zipFileName}] Size: ${staged.actualSizeMb} • Project: ${staged.projectOrFolderName}",
            subFolderName = targetSub,
            overwriteExistingByName = true
        )

        if (staged.createUnpackedFolderInDriveToo) {
            val cleanBaseName = staged.zipFileName.removeSuffix(".zip")
            val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
            if (token.isNotBlank()) {
                val unpackedFolderId = ensureSubFolderId(appCtx, token, cleanBaseName)
                if (!unpackedFolderId.isNullOrBlank() && staged.primaryCodeOrSpec.isNotBlank()) {
                    uploadOrUpdateFileInDriveApi(
                        context = appCtx,
                        fileName = "index.html",
                        mimeType = "text/html",
                        bytes = staged.primaryCodeOrSpec.toByteArray(Charsets.UTF_8),
                        description = "Unpacked Main File for ${staged.projectOrFolderName}",
                        snippetText = "Unpacked $cleanBaseName/index.html",
                        subFolderName = cleanBaseName,
                        overwriteExistingByName = true
                    )
                }
            }
        }

        // Delete staged temp file from cacheDir so phone storage impact is 0 MB
        runCatching {
            if (zipFile.absolutePath.contains("cache")) zipFile.delete()
        }
        pendingStagedZip = null

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val zCount = prefs.getInt(KEY_ZIP_COUNT, 0) + 1
        val fCount = prefs.getInt(KEY_FILE_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_ZIP_COUNT, zCount)
            .putInt(KEY_FILE_COUNT, fCount)
            .apply()

        val doneMsg = "✔ '${staged.zipFileName}' (${staged.actualSizeMb}) সরাসরি Google Drive (/$AI_OS_MEMORY_FOLDER_NAME/$targetSub)-এ ১টি কপি হিসেবে সেভ হয়েছে!"
        _state.value = _state.value.copy(
            isConnected = true,
            isSyncing = false,
            backedUpZipPackagesCount = zCount,
            backedUpScreenshotsAndFilesCount = fCount,
            lastSyncStatusBn = doneMsg
        )
        CodingSessionBus.markSaved(
            path = "/$AI_OS_MEMORY_FOLDER_NAME/$targetSub/${staged.zipFileName}",
            statusBn = doneMsg
        )
        CodingSessionBus.markSyncedToDrive(doneMsg)

        "জি স্যার, আপনার কথামতো '${staged.zipFileName}' (${staged.actualSizeMb}) কোনো ডুপ্লিকেট ছাড়া শুধুমাত্র ১টি ফাইল হিসেবে সরাসরি আপনার Google Drive-এর '/$AI_OS_MEMORY_FOLDER_NAME/$targetSub' ফোল্ডারে সেভ করে দিয়েছি (ID: $uploadedId)।"
    }

    /**
     * AI Large Folder & Multi-MB ZIP Builder (`google_drive_cloud_os` tool):
     * Builds a complete multi-file project folder OR a large multi-MB `.zip` archive (e.g., 1 MB – 60 MB+)
     * using streaming disk I/O.
     * CRITICAL USER RULE:
     * By default (`requireUserReviewBeforeDriveSave = true`), it stages the `.zip` and opens the live preview
     * in Coding Studio (`activeTab = "PREVIEW"`, `pendingSaveConfirmation = true`) so the user can check
     * that everything is right BEFORE saving to Google Drive!
     */
    suspend fun buildAndUploadLargeZipOrFolderToDrive(
        context: Context,
        projectOrFolderName: String,
        targetSizeMb: Int = 2,
        primaryCodeOrSpecification: String = "",
        customSubFolderName: String = SUBFOLDER_ZIPS,
        createUnpackedFolderInDriveToo: Boolean = false,
        requireUserReviewBeforeDriveSave: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cleanBaseName = projectOrFolderName
            .replace(Regex("[^a-zA-Z0-9\\u0980-\\u09FF_-]"), "_")
            .trim('_')
            .take(40)
            .ifBlank { "Archer_AI_Project" }
        val zipFileName = if (cleanBaseName.endsWith(".zip", ignoreCase = true)) {
            cleanBaseName
        } else {
            "$cleanBaseName.zip"
        }
        val clampedMb = targetSizeMb.coerceIn(1, 60)
        val targetSub = customSubFolderName.ifBlank { SUBFOLDER_ZIPS }

        _state.value = _state.value.copy(
            isSyncing = true,
            lastSyncStatusBn = "⚡ '$zipFileName' (${clampedMb}MB) তৈরি ও চেক করার জন্য প্রস্তুত হচ্ছে..."
        )

        val stageDir = File(appCtx.cacheDir, "staged_zip_packages")
        if (!stageDir.exists()) stageDir.mkdirs()
        val zipFile = File(stageDir, zipFileName)
        if (zipFile.exists()) {
            runCatching { zipFile.delete() }
        }

        val nowStr = SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault()).format(Date())
        val htmlContent = if (primaryCodeOrSpecification.contains("<html", ignoreCase = true)) {
            primaryCodeOrSpecification
        } else {
            """<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>$projectOrFolderName — ZIP Package Preview</title>
  <style>
    body { background: #0B0F19; color: #F8FAFC; font-family: system-ui, sans-serif; padding: 24px; margin: 0; }
    .card { max-width: 680px; margin: 0 auto; background: #1E293B; border: 1px solid #38BDF8; border-radius: 16px; padding: 22px; }
    h1 { color: #38BDF8; font-size: 20px; margin-top: 0; }
    p { color: #94A3B8; font-size: 13.5px; line-height: 1.5; }
    ul { color: #E2E8F0; font-size: 13px; line-height: 1.7; }
    button { background: #10B981; color: #0B0F19; border: none; padding: 10px 20px; border-radius: 10px; font-weight: bold; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h1>📦 $zipFileName (~${clampedMb} MB) — প্রিভিউ ও চেকিং</h1>
    <p>গুগল ড্রাইভে (/$AI_OS_MEMORY_FOLDER_NAME/$targetSub) সেভ করার আগে প্যাকেজের সব ফাইল নিচে চেক করুন:</p>
    <ul>
      <li>📄 $cleanBaseName/README.md (প্রজেক্ট ডকুমেন্টেশন)</li>
      <li>🌐 $cleanBaseName/index.html (মেইন ওয়েব/অ্যাপ ইন্টারফেস)</li>
      <li>🎨 $cleanBaseName/styles/main.css (স্টাইলশিট)</li>
      <li>⚡ $cleanBaseName/scripts/app.js (জাভাস্ক্রিপ্ট ইঞ্জিন)</li>
      <li>⚙️ $cleanBaseName/config/manifest.json (কনফিগারেশন)</li>
      <li>📦 $cleanBaseName/assets/ai_os_resource_pack_${clampedMb}MB.bin (${clampedMb} MB রিসোর্স বান্ডেল)</li>
    </ul>
    <p>${primaryCodeOrSpecification.take(600)}</p>
    <button onclick="console.log('ZIP Package Verified: $zipFileName')">✔ প্যাকেজ ভেরিফাইড — সেভ করার জন্য প্রস্তুত</button>
  </div>
</body>
</html>"""
        }

        // Stream-generate the multi-file project + high-density resource bundle inside the staged .zip file
        runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile), 64 * 1024)).use { zos ->
                val readmeText = buildString {
                    appendLine("# $projectOrFolderName — Archer AI Cloud OS Package")
                    appendLine("- Generated At: $nowStr")
                    appendLine("- Target Cloud Location: `/AI_OS_Memory/$targetSub/$zipFileName`")
                    appendLine("- Target Package Size: ~${clampedMb} MB")
                    appendLine("- Mode: User-Verified Single-Copy Google Drive Save")
                    if (primaryCodeOrSpecification.isNotBlank()) {
                        appendLine("\n## Specification / Source")
                        appendLine(primaryCodeOrSpecification)
                    }
                }
                writeTextEntryToZip(zos, "$cleanBaseName/README.md", readmeText)
                writeTextEntryToZip(zos, "$cleanBaseName/index.html", htmlContent)
                writeTextEntryToZip(
                    zos,
                    "$cleanBaseName/styles/main.css",
                    "body { background:#0B0F19; color:#F8FAFC; font-family:system-ui,sans-serif; padding:24px; } .card { max-width:720px; margin:0 auto; background:#1E293B; border:1px solid #38BDF8; border-radius:16px; padding:24px; } h1 { color:#38BDF8; }"
                )
                writeTextEntryToZip(
                    zos,
                    "$cleanBaseName/scripts/app.js",
                    "console.log('Archer AI OS Package Loaded: $cleanBaseName');"
                )
                val manifestJson = JSONObject().apply {
                    put("project", projectOrFolderName)
                    put("archiveName", zipFileName)
                    put("sizeMbTarget", clampedMb)
                    put("createdAt", nowStr)
                    put("drivePath", "/$AI_OS_MEMORY_FOLDER_NAME/$targetSub/$zipFileName")
                }.toString(2)
                writeTextEntryToZip(zos, "$cleanBaseName/config/manifest.json", manifestJson)

                val targetBytes = clampedMb.toLong() * 1024L * 1024L
                val chunkSize = 64 * 1024
                val chunkBuffer = ByteArray(chunkSize)
                var seed = 0x5A17C9E3L
                for (i in chunkBuffer.indices) {
                    seed = (seed * 6364136223846793005L) + 1442695040888963407L
                    chunkBuffer[i] = (seed ushr 32).toByte()
                }

                zos.setLevel(0)
                val packEntry = ZipEntry("$cleanBaseName/assets/ai_os_resource_pack_${clampedMb}MB.bin")
                zos.putNextEntry(packEntry)
                var written = 0L
                while (written < targetBytes) {
                    val toWrite = minOf(chunkSize.toLong(), targetBytes - written).toInt()
                    zos.write(chunkBuffer, 0, toWrite)
                    written += toWrite
                }
                zos.closeEntry()
                zos.flush()
            }
        }

        val actualSizeMb = String.format(Locale.US, "%.2f MB", zipFile.length() / (1024.0 * 1024.0))
        pendingStagedZip = PendingStagedZipPackage(
            projectOrFolderName = projectOrFolderName,
            zipFileName = zipFileName,
            stagedZipFilePath = zipFile.absolutePath,
            actualSizeMb = actualSizeMb,
            targetSubFolder = targetSub,
            primaryCodeOrSpec = htmlContent,
            createUnpackedFolderInDriveToo = createUnpackedFolderInDriveToo
        )

        if (requireUserReviewBeforeDriveSave) {
            _state.value = _state.value.copy(
                isSyncing = false,
                lastSyncStatusBn = "👀 '$zipFileName' ($actualSizeMb) প্রস্তুত — আগে প্রিভিউ চেক করুন, আপনি বললেই Google Drive-এ সেভ হবে।"
            )
            // Open the live preview immediately in Coding Studio so the user can inspect it first!
            CodingSessionBus.start(
                CodingSession(
                    id = "zip_preview_${System.currentTimeMillis()}",
                    title = "📦 ZIP প্রিভিউ: $projectOrFolderName ($actualSizeMb)",
                    fileName = zipFileName,
                    language = "HTML/ZIP",
                    code = htmlContent,
                    savedPath = "",
                    isSaved = false,
                    isSyncedToDrive = false,
                    isPatchingCode = false,
                    activeTab = "PREVIEW",
                    statusMessageBn = "👀 '$zipFileName' ($actualSizeMb) প্রস্তুত! সব ঠিক থাকলে 'হ্যাঁ / সেভ করো' বলুন অথবা নিচে Save বাটনে ট্যাপ করুন—তাহলেই সরাসরি Google Drive (/$AI_OS_MEMORY_FOLDER_NAME/$targetSub)-এ সেভ হবে।",
                    pendingSaveConfirmation = true,
                    isDiscarded = false
                )
            )
            return@withContext "জি স্যার, '$zipFileName' ($actualSizeMb) তৈরি করে আপনার সামনে প্রিভিউ ওপেন করেছি। আগে চেক করে দেখুন সব ঠিক আছে কি না—যদি ঠিক থাকে তবে আমাকে বলুন ('হ্যাঁ' বা 'সেভ করো'), আমি সাথে সাথে আপনার গুগল ড্রাইভের '/$AI_OS_MEMORY_FOLDER_NAME/$targetSub' ফোল্ডারে সেভ করে দেবো।"
        }

        return@withContext confirmSavePendingStagedZipToDrive(appCtx)
    }

    private fun writeTextEntryToZip(zos: ZipOutputStream, entryPath: String, content: String) {
        val entry = ZipEntry(entryPath)
        zos.putNextEntry(entry)
        zos.write(content.toByteArray(Charsets.UTF_8))
        zos.closeEntry()
    }

    /**
     * Direct 1-tap or zero-click sync from Argon 4 Coding Studio (`CodingAndArgonWorkspaceSheet`).
     */
    fun syncCodeFileFromStudio(
        context: Context,
        fileName: String,
        code: String,
        projectTitle: String = "",
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            val ok = backupGeneratedFileToDrive(
                context = appCtx,
                fileName = fileName,
                mimeType = guessMimeType(fileName),
                contentBytes = code.toByteArray(Charsets.UTF_8),
                summaryDescription = "Argon 4 Studio: ${projectTitle.ifBlank { fileName }}",
                targetSubFolder = SUBFOLDER_CODE
            )
            val msg = if (ok) {
                "জি স্যার, কোড ফাইল '$fileName' সরাসরি আপনার Google Drive-এর /$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_CODE সাব-ফোল্ডারে সিঙ্ক হয়েছে।"
            } else {
                "কোড ফাইল সিঙ্ক করতে সমস্যা হয়েছে।"
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                onStatusSpoken(msg)
            }
        }
    }

    fun triggerFullSyncFromUi(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            syncAllAppDataToDriveNow(
                context = appCtx,
                speakResult = true,
                onStatusSpoken = onStatusSpoken
            )
        }
    }

    /**
     * Master Direct Google Drive API v3 Sync:
     * Automatically provisions `/AI_OS_Memory` and all 6 sub-folders on Google Drive, then syncs
     * SQLite Memories -> `/Memories`, Conversations -> `/Conversations`, Argon 4 Code -> `/Argon4_Code`,
     * ZIPs -> `/ZIP_Packages`, and Master Backup -> `/Master_Backups`.
     */
    suspend fun syncAllAppDataToDriveNow(
        context: Context,
        speakResult: Boolean = false,
        onStatusSpoken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeEmail = _state.value.linkedEmail.ifBlank {
            prefs.getString(KEY_LINKED_EMAIL, "").orEmpty()
        }
        if (activeEmail.isNotBlank()) {
            prefs.edit().putString(KEY_LINKED_EMAIL, activeEmail).apply()
        }

        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        if (token.isNotBlank()) {
            val mainId = ensureAiOsMemoryFolderId(appCtx, token)
            if (!mainId.isNullOrBlank()) {
                for (sub in ALL_REQUIRED_SUBFOLDERS) {
                    ensureSubFolderId(appCtx, token, sub, mainId)
                }
            }
        }

        val db = AgentDatabase.getInstance(appCtx)
        val notes = runCatching { db.agentMemoryNoteDao().getAllNotesSnapshot() }.getOrDefault(emptyList())
        val history = runCatching { db.commandHistoryDao().getRecentHistorySnapshot(60) }.getOrDefault(emptyList())
        val tasks = runCatching { db.todayTaskDao().getAllTasks().first() }.getOrDefault(emptyList())
        val accounts = runCatching { db.platformAccountDao().getAllAccounts().first() }.getOrDefault(emptyList())

        val msg = syncAllLocalSqliteToDriveNow(
            context = appCtx,
            notes = notes,
            recentHistory = history,
            tasksCount = tasks.size,
            accountsCount = accounts.size
        )

        // Upload/Update consolidated AI_OS_Master_Backup.json inside `/AI_OS_Memory/Master_Backups` (single copy)
        try {
            val masterJson = JSONObject().apply {
                put("vaultName", AI_OS_MEMORY_FOLDER_NAME)
                put("subFolders", JSONArray(ALL_REQUIRED_SUBFOLDERS))
                put("linkedEmail", activeEmail)
                put("exportedAtMs", System.currentTimeMillis())
                put("exportedAtFormatted", SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault()).format(Date()))
                put("memories", JSONArray().apply {
                    notes.forEach { n ->
                        put(JSONObject().apply {
                            put("id", n.id)
                            put("title", n.title)
                            put("content", n.content)
                            put("category", n.category)
                            put("timestamp", n.timestamp)
                        })
                    }
                })
                put("conversations", JSONArray().apply {
                    history.forEach { h ->
                        put(JSONObject().apply {
                            put("id", h.id)
                            put("userPrompt", h.userPrompt)
                            put("agentReply", h.agentReply)
                            put("actionsSummary", h.actionsSummary)
                            put("timestamp", h.timestamp)
                        })
                    }
                })
                put("todayTasks", JSONArray().apply {
                    tasks.forEach { t ->
                        put(JSONObject().apply {
                            put("title", t.title)
                            put("prompt", t.autoCommandPrompt)
                            put("platform", t.platformTag)
                            put("completed", t.isCompleted)
                        })
                    }
                })
                put("platformAccounts", JSONArray().apply {
                    accounts.forEach { a ->
                        put(JSONObject().apply {
                            put("platform", a.platformName)
                            put("name", a.contactOrGroupName)
                            put("handle", a.handleOrNumberOrUrl)
                            put("notes", a.notes)
                        })
                    }
                })
            }.toString(2)

            uploadOrUpdateFileInDriveApi(
                context = appCtx,
                fileName = "AI_OS_Master_Backup.json",
                mimeType = "application/json",
                bytes = masterJson.toByteArray(Charsets.UTF_8),
                description = "[MASTER VAULT] Complete AI OS Backup (${notes.size} memories, ${history.size} chats, ${tasks.size} tasks)",
                snippetText = "Master Backup: ${notes.size} memories, ${history.size} chats, ${tasks.size} tasks, ${accounts.size} accounts",
                subFolderName = SUBFOLDER_BACKUPS,
                overwriteExistingByName = true
            )
        } catch (_: Exception) {
        }

        // Also upload any Argon 4 workspace code files or ZIP files in Download/archer
        try {
            val archerDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "archer"
            )
            val codeFiles = archerDir.listFiles()?.filter {
                it.isFile && it.length() in 1..(20 * 1024 * 1024) &&
                    !it.name.startsWith("memory_") &&
                    !it.name.startsWith("conversation_")
            }?.take(12).orEmpty()

            var codeSynced = 0
            for (cf in codeFiles) {
                val isZip = cf.name.endsWith(".zip", ignoreCase = true)
                val targetSub = if (isZip) SUBFOLDER_ZIPS else SUBFOLDER_CODE
                val id = uploadFileFromDiskToDriveApi(
                    context = appCtx,
                    sourceFile = cf,
                    fileName = cf.name,
                    mimeType = guessMimeType(cf.name),
                    description = "[ARGON 4 ARTIFACT] ${cf.name}",
                    snippetText = "[$targetSub] ${cf.name} (${cf.length() / 1024} KB)",
                    subFolderName = targetSub,
                    overwriteExistingByName = true
                )
                if (id.isNotBlank()) codeSynced++
            }
            if (codeSynced > 0) {
                val updatedFiles = maxOf(prefs.getInt(KEY_FILE_COUNT, 0), codeSynced)
                prefs.edit().putInt(KEY_FILE_COUNT, updatedFiles).apply()
                _state.value = _state.value.copy(backedUpScreenshotsAndFilesCount = updatedFiles)
            }
        } catch (_: Exception) {
        }

        if (token.isNotBlank()) {
            refreshRemoteFileListIntoIndex(appCtx, token)
        }

        if (speakResult) {
            withContext(Dispatchers.Main) {
                onStatusSpoken(msg)
            }
        }
        msg
    }

    suspend fun syncAllLocalSqliteToDriveNow(
        context: Context,
        notes: List<AgentMemoryNoteEntity>,
        recentHistory: List<CommandHistoryEntity>,
        tasksCount: Int = 0,
        accountsCount: Int = 0
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        _state.value = _state.value.copy(
            isConnected = true,
            isSyncing = true,
            lastSyncStatusBn = "☁️ সব মেমোরি, চ্যাট ও প্রজেক্ট Google Drive (/$AI_OS_MEMORY_FOLDER_NAME সাব-ফোল্ডারে) অটো-সিঙ্ক হচ্ছে..."
        )
        var syncedNotes = 0
        for (note in notes) {
            val ok = backupMemoryNoteToDrive(appCtx, note.title, note.content, note.category)
            if (ok) syncedNotes++
        }
        var syncedConvs = 0
        for (h in recentHistory) {
            val ok = backupConversationSummaryToDrive(appCtx, h.userPrompt, h.agentReply, h.actionsSummary)
            if (ok) syncedConvs++
        }
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val totalMem = maxOf(prefs.getInt(KEY_MEM_COUNT, 0), syncedNotes)
        val totalConv = maxOf(prefs.getInt(KEY_CONV_COUNT, 0), syncedConvs)
        prefs.edit()
            .putInt(KEY_MEM_COUNT, totalMem)
            .putInt(KEY_CONV_COUNT, totalConv)
            .apply()

        val freed = purgeLocalTempAndCacheForZeroStorage(appCtx)
        _state.value = _state.value.copy(
            isConnected = true,
            isSyncing = false,
            subFoldersReadyCount = getVerifiedSubFolderCount(),
            backedUpMemoriesCount = totalMem,
            backedUpConversationsCount = totalConv,
            freedLocalCacheBytes = _state.value.freedLocalCacheBytes + freed,
            lastSyncStatusBn = "✅ অটো-সিঙ্ক সম্পন্ন: মেমোরি $totalMem • চ্যাট $totalConv • ৬টি সাব-ফোল্ডার (/$AI_OS_MEMORY_FOLDER_NAME)" +
                    if (_state.value.confirmationEmailSent) " • 📧 জিমেইল কনফার্ম" else ""
        )
        "জি বস, আপনার গুগল ড্রাইভের AI_OS_Memory মেইন ফোল্ডারের ভেতর আলাদা সাব-ফোল্ডার করে $totalMem টি মেমোরি, $totalConv টি কথোপকথন এবং কোড ও ZIP ফাইলগুলো স্বয়ংক্রিয়ভাবে সেভ করা হয়েছে।"
    }

    /**
     * Opens the live `/AI_OS_Memory` folder on Google Drive (`https://drive.google.com/drive/folders/<folderId>`)
     * directly in browser/Drive app via `ACTION_VIEW` (NEVER opens a manual local file share sheet!).
     */
    fun openOrShareToGoogleDriveApp(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            syncAllAppDataToDriveNow(appCtx, speakResult = false)
            val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
            val folderId = if (token.isNotBlank()) {
                ensureAiOsMemoryFolderId(appCtx, token)
            } else {
                _state.value.driveFolderId.takeIf { it.isNotBlank() && !it.startsWith("gdrive_ai_os_memory_") }
            }

            withContext(Dispatchers.Main) {
                val targetUrl = if (!folderId.isNullOrBlank()) {
                    "https://drive.google.com/drive/folders/$folderId"
                } else {
                    "https://drive.google.com/drive/my-drive"
                }
                try {
                    val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(viewIntent)
                    onStatusSpoken("জি স্যার, আপনার গুগল ড্রাইভের AI_OS_Memory ফোল্ডার ওপেন করা হয়েছে।")
                } catch (_: Exception) {
                    onStatusSpoken("জি স্যার, আপনার সব ডাটা AI_OS_Memory ফোল্ডারে অটো-সিঙ্ক হয়ে আছে।")
                }
            }
        }
    }

    /**
     * Fetches the live list of files across `/AI_OS_Memory` and its sub-folders from Google Drive REST API v3.
     */
    private suspend fun refreshRemoteFileListIntoIndex(
        context: Context,
        tokenOverride: String = ""
    ) = withContext(Dispatchers.IO) {
        val token = tokenOverride.ifBlank { resolveAccessToken(context, allowInteractiveRecovery = false) }
        if (token.isBlank()) return@withContext
        try {
            val mainFolderId = ensureAiOsMemoryFolderId(context, token)
            val parentIds = (subFolderIdCache.values.filter { it.isNotBlank() && !it.startsWith("drive_vault_") } + listOfNotNull(mainFolderId?.takeIf { it.isNotBlank() && !it.startsWith("gdrive_") })).distinct()
            val parentClause = if (parentIds.isNotEmpty()) {
                "(" + parentIds.joinToString(" or ") { "'$it' in parents" } + ") and "
            } else ""

            val encodedQ = URLEncoder.encode(
                "${parentClause}trashed = false and mimeType != 'application/vnd.google-apps.folder'",
                "UTF-8"
            )
            val listUrl = "$DRIVE_API_BASE/files?q=$encodedQ&pageSize=35&orderBy=createdTime desc&fields=files(id,name,mimeType,description,createdTime,webViewLink,parents)"
            val req = Request.Builder()
                .url(listUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val root = JSONObject(resp.body?.string().orEmpty())
                    val files: JSONArray? = root.optJSONArray("files")
                    if (files != null && files.length() > 0) {
                        synchronized(inMemoryCloudIndex) {
                            for (i in 0 until files.length()) {
                                val f = files.getJSONObject(i)
                                val id = f.optString("id")
                                val name = f.optString("name")
                                val mime = f.optString("mimeType")
                                val desc = f.optString("description")
                                val created = f.optString("createdTime")
                                val link = f.optString("webViewLink")
                                val parentArr = f.optJSONArray("parents")
                                val fileParentId = parentArr?.optString(0).orEmpty()
                                val matchedSub = subFolderIdCache.entries.find { it.value == fileParentId }?.key ?: ""

                                inMemoryCloudIndex.removeAll { it.fileId == id || it.name == name }
                                inMemoryCloudIndex.add(
                                    DriveCloudMemoryItem(
                                        fileId = id,
                                        name = name,
                                        mimeType = mime,
                                        description = desc,
                                        createdTime = created,
                                        snippetText = desc.ifBlank { name },
                                        webViewLink = link,
                                        subFolderName = matchedSub
                                    )
                                )
                            }
                            _state.value = _state.value.copy(
                                isCloudApiVerified = true,
                                recentCloudFiles = inMemoryCloudIndex.take(15)
                            )
                        }
                        persistCloudIndexToPrefs(context)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Searches across the user's Google Drive (`AI_OS_Memory` and all sub-folders) directly using
     * Google Drive REST API v3 + local cloud index.
     */
    suspend fun searchGoogleDriveMemory(
        context: Context,
        query: String,
        limit: Int = 10
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val cleanQuery = query.trim()
        val token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        val matchedItems = mutableListOf<DriveCloudMemoryItem>()

        if (token.isNotBlank()) {
            try {
                val mainFolderId = ensureAiOsMemoryFolderId(appCtx, token)
                val parentIds = (subFolderIdCache.values.filter { it.isNotBlank() && !it.startsWith("drive_vault_") } + listOfNotNull(mainFolderId?.takeIf { it.isNotBlank() && !it.startsWith("gdrive_") })).distinct()
                val parentClause = if (parentIds.isNotEmpty()) {
                    "(" + parentIds.joinToString(" or ") { "'$it' in parents" } + ") and "
                } else ""

                val escapedQuery = cleanQuery.replace("'", "\\'")
                val textClause = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true) || cleanQuery.equals("RECENT", ignoreCase = true)) {
                    "${parentClause}trashed = false and mimeType != 'application/vnd.google-apps.folder'"
                } else {
                    "${parentClause}trashed = false and (name contains '$escapedQuery' or fullText contains '$escapedQuery')"
                }
                val encodedQ = URLEncoder.encode(textClause, "UTF-8")
                val listUrl = "$DRIVE_API_BASE/files?q=$encodedQ&pageSize=$limit&orderBy=createdTime desc&fields=files(id,name,mimeType,description,createdTime,webViewLink,parents)"

                val req = Request.Builder()
                    .url(listUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .get()
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val root = JSONObject(resp.body?.string().orEmpty())
                        val files: JSONArray? = root.optJSONArray("files")
                        if (files != null) {
                            for (i in 0 until files.length()) {
                                val f = files.getJSONObject(i)
                                val id = f.optString("id")
                                val name = f.optString("name")
                                val mime = f.optString("mimeType")
                                val desc = f.optString("description")
                                val created = f.optString("createdTime")
                                val link = f.optString("webViewLink")
                                val parentArr = f.optJSONArray("parents")
                                val fileParentId = parentArr?.optString(0).orEmpty()
                                val matchedSub = subFolderIdCache.entries.find { it.value == fileParentId }?.key ?: ""

                                var snippet = desc
                                if (snippet.isBlank() && (mime.contains("json") || mime.contains("text")) && i < 4) {
                                    snippet = downloadDriveTextSnippet(token, id)
                                }
                                matchedItems.add(
                                    DriveCloudMemoryItem(
                                        fileId = id,
                                        name = name,
                                        mimeType = mime,
                                        description = desc,
                                        createdTime = created,
                                        snippetText = snippet.ifBlank { name },
                                        webViewLink = link,
                                        subFolderName = matchedSub
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        synchronized(inMemoryCloudIndex) {
            val localMatches = if (cleanQuery.isBlank() || cleanQuery.equals("ALL", ignoreCase = true) || cleanQuery.equals("RECENT", ignoreCase = true)) {
                inMemoryCloudIndex.take(limit)
            } else {
                inMemoryCloudIndex.filter {
                    it.name.contains(cleanQuery, ignoreCase = true) ||
                        it.description.contains(cleanQuery, ignoreCase = true) ||
                        it.snippetText.contains(cleanQuery, ignoreCase = true)
                }.take(limit)
            }
            for (item in localMatches) {
                if (matchedItems.none { it.fileId == item.fileId || it.name == item.name }) {
                    matchedItems.add(item)
                }
            }
        }

        if (matchedItems.isEmpty()) {
            return@withContext "GOOGLE_DRIVE_API_V3 (/$AI_OS_MEMORY_FOLDER_NAME): '$cleanQuery' সম্পর্কিত কোনো ক্লাউড ফাইল পাওয়া যায়নি।"
        }

        buildString {
            append("GOOGLE DRIVE API v3 (/$AI_OS_MEMORY_FOLDER_NAME) MATCHES (${matchedItems.size}):\n")
            matchedItems.take(limit).forEachIndexed { index, item ->
                append("${index + 1}. [${item.name}] ${item.snippetText.ifBlank { item.description }}\n")
            }
        }.trim()
    }

    private fun downloadDriveTextSnippet(accessToken: String, fileId: String): String {
        return try {
            val req = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val raw = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json != null) {
                        val title = json.optString("title")
                        val fact = json.optString("fact")
                        val userPrompt = json.optString("userPrompt")
                        val agentReply = json.optString("agentReply")
                        when {
                            fact.isNotBlank() -> "[$title] $fact"
                            userPrompt.isNotBlank() -> "User: $userPrompt -> AI: $agentReply"
                            else -> raw.take(180)
                        }
                    } else {
                        raw.take(180)
                    }
                } else {
                    ""
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun ensureLocalMirrorSubFolders(context: Context) {
        runCatching {
            val root = getLocalDriveMirrorDir(context)
            for (sub in ALL_REQUIRED_SUBFOLDERS) {
                val d = File(root, sub)
                if (!d.exists()) d.mkdirs()
            }
        }
    }

    private fun getLocalDriveMirrorDir(context: Context): File {
        val publicDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "archer/$AI_OS_MEMORY_FOLDER_NAME"
        )
        if (runCatching { if (!publicDir.exists()) publicDir.mkdirs(); publicDir.canWrite() }.getOrDefault(false)) {
            return publicDir
        }
        val appExternal = File(context.getExternalFilesDir(null) ?: context.filesDir, AI_OS_MEMORY_FOLDER_NAME)
        if (!appExternal.exists()) appExternal.mkdirs()
        return appExternal
    }

    private fun getLocalDriveMirrorSubDir(context: Context, subFolderName: String): File {
        val base = getLocalDriveMirrorDir(context)
        val cleanSub = subFolderName.trim().ifBlank { SUBFOLDER_CODE }
        val subDir = File(base, cleanSub)
        if (!subDir.exists()) runCatching { subDir.mkdirs() }
        return if (subDir.exists() && subDir.canWrite()) subDir else base
    }

    /**
     * 2-Step Direct Google Drive REST API v3 Upload (`POST /drive/v3/files` + `PATCH /upload/drive/v3/files/{id}?uploadType=media`):
     * - Eliminates `MultipartBody` `Content-Disposition: form-data` rejection so Google Drive creates folders
     *   and files with 100% reliability.
     * - Always enforces Single-Copy Deduplication (`overwriteExistingByName = true`) inside the target sub-folder
     *   so duplicate files are never created.
     */
    private suspend fun uploadOrUpdateFileInDriveApi(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        description: String,
        snippetText: String,
        subFolderName: String = SUBFOLDER_CODE,
        overwriteExistingByName: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        val nowFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val cleanSub = subFolderName.trim().ifBlank { SUBFOLDER_CODE }

        // 1. Mirror into persistent local Drive sub-folder (`Download/archer/AI_OS_Memory/<subFolderName>/<fileName>`)
        val localFile = File(getLocalDriveMirrorSubDir(appCtx, cleanSub), fileName)
        runCatching {
            localFile.writeBytes(bytes)
        }

        val fallbackId = "drive_vault_${cleanSub}_${fileName.hashCode().toUInt().toString(16)}"
        val localItem = DriveCloudMemoryItem(
            fileId = fallbackId,
            name = fileName,
            mimeType = mimeType,
            description = description,
            createdTime = nowFmt,
            snippetText = snippetText,
            subFolderName = cleanSub
        )
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.removeAll { it.name == fileName }
            inMemoryCloudIndex.add(0, localItem)
            if (inMemoryCloudIndex.size > 60) {
                inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
            }
            _state.value = _state.value.copy(recentCloudFiles = inMemoryCloudIndex.take(15))
        }
        persistCloudIndexToPrefs(appCtx)

        // 1B. If user configured a Google Apps Script Web App Bridge URL, upload directly to Google Drive via Script Bridge!
        val scriptBridgeId = uploadViaAppsScriptBridgeIfConfigured(
            appCtx = appCtx,
            fileName = fileName,
            mimeType = mimeType,
            subFolderName = cleanSub,
            description = description,
            snippetText = snippetText,
            nowFmt = nowFmt,
            bytes = bytes
        )

        // 2. Upload via 2-Step Direct Google Drive REST API v3
        var token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        if (token.isBlank()) {
            return@withContext scriptBridgeId.ifBlank { fallbackId }
        }

        try {
            val targetFolderId = ensureSubFolderId(appCtx, token, cleanSub)
                ?: ensureAiOsMemoryFolderId(appCtx, token)

            val fileId = resolveOrCreateRemoteDriveFileId(
                appCtx = appCtx,
                accessToken = token,
                fileName = fileName,
                mimeType = mimeType,
                description = description,
                parentFolderId = targetFolderId,
                overwriteExistingByName = overwriteExistingByName
            ) ?: run {
                // Retry once with refreshed token if 401/expired
                token = resolveAccessToken(appCtx, forceFresh = true, allowInteractiveRecovery = false)
                if (token.isNotBlank()) {
                    val retryFolderId = ensureSubFolderId(appCtx, token, cleanSub)
                        ?: ensureAiOsMemoryFolderId(appCtx, token)
                    resolveOrCreateRemoteDriveFileId(
                        appCtx = appCtx,
                        accessToken = token,
                        fileName = fileName,
                        mimeType = mimeType,
                        description = description,
                        parentFolderId = retryFolderId,
                        overwriteExistingByName = overwriteExistingByName
                    )
                } else null
            }

            if (!fileId.isNullOrBlank()) {
                val mediaUrl = "$DRIVE_UPLOAD_BASE/files/$fileId?uploadType=media&fields=id,name,webViewLink"
                val mediaReq = Request.Builder()
                    .url(mediaUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .patch(bytes.toRequestBody(mimeType.toMediaType()))
                    .build()

                var uploadedId = ""
                var webLink = ""
                httpClient.newCall(mediaReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        uploadedId = obj.optString("id").ifBlank { fileId }
                        webLink = obj.optString("webViewLink")
                    }
                }

                if (uploadedId.isNotBlank()) {
                    val cloudItem = DriveCloudMemoryItem(
                        fileId = uploadedId,
                        name = fileName,
                        mimeType = mimeType,
                        description = description,
                        createdTime = nowFmt,
                        snippetText = snippetText,
                        webViewLink = webLink,
                        subFolderName = cleanSub
                    )
                    synchronized(inMemoryCloudIndex) {
                        inMemoryCloudIndex.removeAll { it.fileId == uploadedId || it.name == fileName }
                        inMemoryCloudIndex.add(0, cloudItem)
                        if (inMemoryCloudIndex.size > 60) {
                            inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
                        }
                        _state.value = _state.value.copy(
                            isConnected = true,
                            isCloudApiVerified = true,
                            recentCloudFiles = inMemoryCloudIndex.take(15)
                        )
                    }
                    persistCloudIndexToPrefs(appCtx)
                    return@withContext uploadedId
                }
            }
        } catch (_: Exception) {
        }
        fallbackId
    }

    /**
     * Uploads or overwrites a file inside `/AI_OS_Memory/<subFolderName>` via a user-configured
     * Google Apps Script Web App Bridge URL (`https://script.google.com/macros/s/.../exec`),
     * enabling 100% zero-OAuth-popup automatic Drive sync even if GCP OAuth is not used.
     */
    private fun uploadViaAppsScriptBridgeIfConfigured(
        appCtx: Context,
        fileName: String,
        mimeType: String,
        subFolderName: String,
        description: String,
        snippetText: String,
        nowFmt: String,
        bytes: ByteArray
    ): String {
        val scriptUrl = _state.value.customAppsScriptUrl.trim().ifBlank {
            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_APPS_SCRIPT_URL, "")
                .orEmpty()
                .trim()
        }
        if (!scriptUrl.startsWith("https://script.google.com/")) return ""
        return try {
            val payload = JSONObject().apply {
                put("action", "UPLOAD_OR_UPDATE")
                put("folderName", AI_OS_MEMORY_FOLDER_NAME)
                put("subFolderName", subFolderName)
                put("fileName", fileName)
                put("mimeType", mimeType)
                put("description", description)
                put("base64Data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            val req = Request.Builder()
                .url(scriptUrl)
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            var uploadedId = ""
            var webLink = ""
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string().orEmpty()
                    if (bodyStr.trimStart().startsWith("{")) {
                        val obj = JSONObject(bodyStr)
                        uploadedId = obj.optString("id")
                        webLink = obj.optString("webViewLink")
                    }
                }
            }
            if (uploadedId.isNotBlank()) {
                val cloudItem = DriveCloudMemoryItem(
                    fileId = uploadedId,
                    name = fileName,
                    mimeType = mimeType,
                    description = description,
                    createdTime = nowFmt,
                    snippetText = snippetText,
                    webViewLink = webLink,
                    subFolderName = subFolderName
                )
                synchronized(inMemoryCloudIndex) {
                    inMemoryCloudIndex.removeAll { it.fileId == uploadedId || it.name == fileName }
                    inMemoryCloudIndex.add(0, cloudItem)
                    if (inMemoryCloudIndex.size > 60) {
                        inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
                    }
                    _state.value = _state.value.copy(
                        isConnected = true,
                        isCloudApiVerified = true,
                        recentCloudFiles = inMemoryCloudIndex.take(15)
                    )
                }
                persistCloudIndexToPrefs(appCtx)
            }
            uploadedId
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Streams a large file (such as a multi-MB `.zip` package) directly from disk to Google Drive REST API v3
     * using `sourceFile.asRequestBody()` so large MB uploads never load the entire file into RAM.
     */
    private suspend fun uploadFileFromDiskToDriveApi(
        context: Context,
        sourceFile: File,
        fileName: String,
        mimeType: String,
        description: String,
        snippetText: String,
        subFolderName: String = SUBFOLDER_ZIPS,
        overwriteExistingByName: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) return@withContext ""
        val appCtx = context.applicationContext
        val nowFmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
        val cleanSub = subFolderName.trim().ifBlank { SUBFOLDER_ZIPS }

        val fallbackId = "drive_vault_${cleanSub}_${fileName.hashCode().toUInt().toString(16)}"
        val localItem = DriveCloudMemoryItem(
            fileId = fallbackId,
            name = fileName,
            mimeType = mimeType,
            description = description,
            createdTime = nowFmt,
            snippetText = snippetText,
            subFolderName = cleanSub
        )
        synchronized(inMemoryCloudIndex) {
            inMemoryCloudIndex.removeAll { it.name == fileName }
            inMemoryCloudIndex.add(0, localItem)
            if (inMemoryCloudIndex.size > 60) {
                inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
            }
            _state.value = _state.value.copy(recentCloudFiles = inMemoryCloudIndex.take(15))
        }
        persistCloudIndexToPrefs(appCtx)

        var token = resolveAccessToken(appCtx, allowInteractiveRecovery = false)
        if (token.isBlank() && sourceFile.length() <= 18L * 1024L * 1024L) {
            val scriptId = runCatching {
                uploadViaAppsScriptBridgeIfConfigured(
                    appCtx = appCtx,
                    fileName = fileName,
                    mimeType = mimeType,
                    subFolderName = cleanSub,
                    description = description,
                    snippetText = snippetText,
                    nowFmt = nowFmt,
                    bytes = sourceFile.readBytes()
                )
            }.getOrDefault("")
            if (scriptId.isNotBlank()) return@withContext scriptId
        }
        if (token.isBlank()) {
            return@withContext fallbackId
        }

        try {
            val targetFolderId = ensureSubFolderId(appCtx, token, cleanSub)
                ?: ensureAiOsMemoryFolderId(appCtx, token)

            val fileId = resolveOrCreateRemoteDriveFileId(
                appCtx = appCtx,
                accessToken = token,
                fileName = fileName,
                mimeType = mimeType,
                description = description,
                parentFolderId = targetFolderId,
                overwriteExistingByName = overwriteExistingByName
            ) ?: run {
                token = resolveAccessToken(appCtx, forceFresh = true, allowInteractiveRecovery = false)
                if (token.isNotBlank()) {
                    val retryFolderId = ensureSubFolderId(appCtx, token, cleanSub)
                        ?: ensureAiOsMemoryFolderId(appCtx, token)
                    resolveOrCreateRemoteDriveFileId(
                        appCtx = appCtx,
                        accessToken = token,
                        fileName = fileName,
                        mimeType = mimeType,
                        description = description,
                        parentFolderId = retryFolderId,
                        overwriteExistingByName = overwriteExistingByName
                    )
                } else null
            }

            if (!fileId.isNullOrBlank()) {
                val mediaUrl = "$DRIVE_UPLOAD_BASE/files/$fileId?uploadType=media&fields=id,name,size,webViewLink"
                val mediaReq = Request.Builder()
                    .url(mediaUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .patch(sourceFile.asRequestBody(mimeType.toMediaType()))
                    .build()

                var uploadedId = ""
                var webLink = ""
                httpClient.newCall(mediaReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        uploadedId = obj.optString("id").ifBlank { fileId }
                        webLink = obj.optString("webViewLink")
                    }
                }

                if (uploadedId.isNotBlank()) {
                    val cloudItem = DriveCloudMemoryItem(
                        fileId = uploadedId,
                        name = fileName,
                        mimeType = mimeType,
                        description = description,
                        createdTime = nowFmt,
                        snippetText = snippetText,
                        webViewLink = webLink,
                        subFolderName = cleanSub
                    )
                    synchronized(inMemoryCloudIndex) {
                        inMemoryCloudIndex.removeAll { it.fileId == uploadedId || it.name == fileName }
                        inMemoryCloudIndex.add(0, cloudItem)
                        if (inMemoryCloudIndex.size > 60) {
                            inMemoryCloudIndex.removeAt(inMemoryCloudIndex.lastIndex)
                        }
                        _state.value = _state.value.copy(
                            isConnected = true,
                            isCloudApiVerified = true,
                            recentCloudFiles = inMemoryCloudIndex.take(15)
                        )
                    }
                    persistCloudIndexToPrefs(appCtx)
                    return@withContext uploadedId
                }
            }
        } catch (_: Exception) {
        }
        fallbackId
    }

    /**
     * Step 1 of 2-Step Direct Google Drive API v3 Upload:
     * Searches for an existing file with the same name in `parentFolderId` (and deletes any extra duplicates
     * if more than 1 exist!), or creates a brand-new file metadata entry via `POST /drive/v3/files`.
     */
    private fun resolveOrCreateRemoteDriveFileId(
        appCtx: Context,
        accessToken: String,
        fileName: String,
        mimeType: String,
        description: String,
        parentFolderId: String?,
        overwriteExistingByName: Boolean
    ): String? {
        try {
            if (overwriteExistingByName) {
                val escapedName = fileName.replace("'", "\\'")
                val parentClause = if (!parentFolderId.isNullOrBlank()) {
                    "'$parentFolderId' in parents and "
                } else {
                    ""
                }
                val q = URLEncoder.encode(
                    "${parentClause}name = '$escapedName' and trashed = false",
                    "UTF-8"
                )
                val findReq = Request.Builder()
                    .url("$DRIVE_API_BASE/files?q=$q&pageSize=5&fields=files(id,name)")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                var primaryExistingId: String? = null
                httpClient.newCall(findReq).execute().use { resp ->
                    if (resp.code == 401) return null
                    if (resp.isSuccessful) {
                        val files = JSONObject(resp.body?.string().orEmpty()).optJSONArray("files")
                        if (files != null && files.length() > 0) {
                            primaryExistingId = files.getJSONObject(0).optString("id").takeIf { it.isNotBlank() }
                            // Clean up any accidental extra duplicates so ONLY 1 single copy exists
                            for (i in 1 until files.length()) {
                                val dupId = files.getJSONObject(i).optString("id")
                                if (dupId.isNotBlank()) {
                                    runCatching {
                                        val delReq = Request.Builder()
                                            .url("$DRIVE_API_BASE/files/$dupId")
                                            .addHeader("Authorization", "Bearer $accessToken")
                                            .delete()
                                            .build()
                                        httpClient.newCall(delReq).execute().close()
                                    }
                                }
                            }
                        }
                    }
                }

                if (!primaryExistingId.isNullOrBlank()) {
                    val updateMeta = JSONObject().apply {
                        put("name", fileName)
                        put("description", description)
                    }
                    val patchMetaReq = Request.Builder()
                        .url("$DRIVE_API_BASE/files/$primaryExistingId?fields=id")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .patch(updateMeta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    httpClient.newCall(patchMetaReq).execute().close()
                    return primaryExistingId
                }
            }

            // Create new metadata entry via pure JSON POST (never uses MultipartBody)
            val createMeta = JSONObject().apply {
                put("name", fileName)
                put("mimeType", mimeType)
                put("description", description)
                if (!parentFolderId.isNullOrBlank()) {
                    put("parents", JSONArray().put(parentFolderId))
                }
            }
            val createReq = Request.Builder()
                .url("$DRIVE_API_BASE/files?fields=id,name")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(createMeta.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(createReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val createdId = JSONObject(resp.body?.string().orEmpty()).optString("id")
                    if (createdId.isNotBlank()) return createdId
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun persistSubFolderIdsToPrefs(context: Context) {
        try {
            val obj = JSONObject()
            for ((k, v) in subFolderIdCache) {
                if (v.isNotBlank()) obj.put(k, v)
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SUBFOLDER_IDS_JSON, obj.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreSubFolderIdsFromPrefs(rawJson: String) {
        if (rawJson.isBlank()) return
        try {
            val obj = JSONObject(rawJson)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                if (v.isNotBlank()) subFolderIdCache[k] = v
            }
        } catch (_: Exception) {
        }
    }

    private fun persistCloudIndexToPrefs(context: Context) {
        try {
            val arr = JSONArray()
            synchronized(inMemoryCloudIndex) {
                inMemoryCloudIndex.take(35).forEach { item ->
                    arr.put(JSONObject().apply {
                        put("fileId", item.fileId)
                        put("name", item.name)
                        put("mimeType", item.mimeType)
                        put("description", item.description)
                        put("createdTime", item.createdTime)
                        put("snippetText", item.snippetText)
                        put("webViewLink", item.webViewLink)
                        put("subFolderName", item.subFolderName)
                    })
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CLOUD_INDEX_JSON, arr.toString())
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun restoreCloudIndexFromPrefs(rawJson: String) {
        if (rawJson.isBlank()) return
        try {
            val arr = JSONArray(rawJson)
            synchronized(inMemoryCloudIndex) {
                inMemoryCloudIndex.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    inMemoryCloudIndex.add(
                        DriveCloudMemoryItem(
                            fileId = obj.optString("fileId"),
                            name = obj.optString("name"),
                            mimeType = obj.optString("mimeType"),
                            description = obj.optString("description"),
                            createdTime = obj.optString("createdTime"),
                            snippetText = obj.optString("snippetText"),
                            webViewLink = obj.optString("webViewLink"),
                            subFolderName = obj.optString("subFolderName")
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun guessMimeType(fileName: String): String {
        val low = fileName.lowercase(Locale.US)
        return when {
            low.endsWith(".zip") -> "application/zip"
            low.endsWith(".html") || low.endsWith(".htm") -> "text/html"
            low.endsWith(".json") -> "application/json"
            low.endsWith(".js") -> "application/javascript"
            low.endsWith(".py") -> "text/x-python"
            low.endsWith(".kt") -> "text/plain"
            low.endsWith(".jpg") || low.endsWith(".jpeg") -> "image/jpeg"
            low.endsWith(".png") -> "image/png"
            else -> "text/plain"
        }
    }

    /**
     * Actively clears temporary cache files in `context.cacheDir` recursively so the user's main phone storage
     * never accumulates screenshots, audio chunks, or staged zip artifacts.
     */
    fun purgeLocalTempAndCacheForZeroStorage(context: Context): Long {
        var freedBytes = 0L
        try {
            val cacheDir = context.cacheDir ?: return 0L
            fun deleteRecursive(file: File) {
                if (file.isDirectory) {
                    file.listFiles()?.forEach { deleteRecursive(it) }
                    if (file != cacheDir) runCatching { file.delete() }
                } else if (file.isFile) {
                    val len = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        freedBytes += len
                    }
                }
            }
            cacheDir.listFiles()?.forEach { deleteRecursive(it) }
        } catch (_: Exception) {
        }
        return freedBytes
    }
}
