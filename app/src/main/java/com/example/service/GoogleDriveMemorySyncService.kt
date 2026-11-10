package com.example.service

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.result.IntentSenderRequest
import com.example.data.AgentDatabase
import com.example.data.AgentMemoryNoteEntity
import com.example.data.CommandHistoryEntity
import com.example.data.PlatformAccountEntity
import com.example.data.TodayTaskEntity
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Only ONE Drive scope is requested: `drive.file` (files/folders created by this app).
 * Gmail / appdata scopes were removed on purpose — extra restricted scopes were the reason the
 * Google consent screen kept failing on unverified builds.
 */
object DriveScopes {
    const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
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
    /** TRUE only after a real Google account was authorised AND Drive `about` call succeeded. */
    val isConnected: Boolean = false,
    val isCloudApiVerified: Boolean = false,
    /** Connected before, but the token can no longer be refreshed silently -> user must tap Connect. */
    val needsReconnect: Boolean = false,
    val linkedEmail: String = "",
    val displayName: String = "",
    val driveFolderName: String = "AI_OS_Memory",
    val driveFolderId: String = "",
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
    /** Items that are safe on the phone but not uploaded yet (offline / token problem). */
    val pendingUploadsCount: Int = 0,
    val freedLocalCacheBytes: Long = 0L,
    val cloudBrainContextSummary: String = "",
    val lastSyncStatusBn: String = "☁️ Google Drive কানেক্ট করা নেই — ☁️ Connect বাটনে ট্যাপ করুন",
    /** Human readable reason of the last failure (empty when everything is fine). */
    val lastErrorBn: String = "",
    val appSha1: String = "",
    val appPackageName: String = "",
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

/**
 * ARCHER AI — GOOGLE DRIVE = THE BRAIN.
 *
 * Design:
 *  - Google Drive (`/AI_OS_Memory/...`) is the permanent source of truth for memories, conversations,
 *    code, ZIPs, screenshots, voice prints, cloud bots and a master backup.
 *  - The phone only keeps a small working cache (Room DB + `files/brain/`), so nothing is ever lost
 *    when Drive/Internet is down: every item is written locally FIRST and is uploaded automatically
 *    as soon as Drive is reachable (startup, every change, every 2 minutes).
 *  - Everything is automatic: save, restore on a fresh install, de-duplication and deletion
 *    (the AI can `forget_memory`; deleted items go to the Drive Trash for 30 days).
 *  - No fake/demo account: "connected" is shown only after Google really authorised the account.
 */
object GoogleDriveMemorySyncService {
    private const val PREFS_NAME = "archer_gdrive_brain_v2"
    private const val LEGACY_PREFS_NAME = "archer_gdrive_5tb_sync_prefs"

    /** SHA-1 of the signing key that is registered for the Android OAuth client in google-services.json. */
    private const val EXPECTED_CERT_SHA1 = "a0ce22dd11e0a9f201b6158be7204fd75bcb93a1"

    private const val KEY_LINKED_EMAIL = "linked_email"
    private const val KEY_DISPLAY_NAME = "display_name"
    private const val KEY_CONNECTED = "connected"
    private const val KEY_FOLDER_ID = "root_folder_id"
    private const val KEY_SUBFOLDER_IDS_JSON = "subfolder_ids_json"
    private const val KEY_FILE_IDS_JSON = "file_ids_json"
    private const val KEY_QUOTA_SUMMARY = "quota_summary"
    private const val KEY_FILE_COUNT = "backed_up_file_count"
    private const val KEY_ZIP_COUNT = "backed_up_zip_count"
    private const val KEY_ZERO_STORAGE_MODE = "zero_phone_storage_mode"
    private const val KEY_UPLOADED_MEM_KEYS = "uploaded_mem_keys"
    private const val KEY_TOMBSTONES = "tombstones"
    private const val KEY_DIRTY_CONV_DAYS = "dirty_conv_days"
    private const val KEY_UPLOADED_FINGERPRINTS = "uploaded_fingerprints"
    private const val KEY_LAST_PULL_ISO = "last_pull_iso"
    private const val KEY_LAST_MASTER_BACKUP_MS = "last_master_backup_ms"
    private const val KEY_CLOUD_BRAIN_SUMMARY = "cloud_brain_context_summary"

    const val AI_OS_MEMORY_FOLDER_NAME = "AI_OS_Memory"
    const val SUBFOLDER_MEMORIES = "Memories"
    const val SUBFOLDER_CONVERSATIONS = "Conversations"
    const val SUBFOLDER_CODE = "Argon4_Code"
    const val SUBFOLDER_ZIPS = "ZIP_Packages"
    const val SUBFOLDER_MEDIA = "Media_Screenshots"
    const val SUBFOLDER_CLOUD_BOTS = "Cloud_Bots"
    const val SUBFOLDER_VOICE_PROFILES = "Voice_Profiles"
    const val SUBFOLDER_BACKUPS = "Master_Backups"

    private const val MASTER_BACKUP_FILE = "AI_OS_Master_Backup.json"
    private const val VOICE_PROFILES_FILE = "All_Enrolled_Voice_Profiles.json"

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

    private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
    private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
    private const val SIMPLE_UPLOAD_MAX_BYTES = 4L * 1024L * 1024L
    private const val TOKEN_LIFETIME_MS = 45L * 60L * 1000L

    @Volatile
    private var pendingStagedZip: PendingStagedZipPackage? = null

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var initialized = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val folderMutex = Mutex()
    private val tokenMutex = Mutex()
    private val syncMutex = Mutex()
    private val subFolderIdCache = ConcurrentHashMap<String, String>()
    private val fileIdCache = ConcurrentHashMap<String, String>()
    private val setLock = Any()
    private val convLock = Any()
    private val convLastUploadMs = ConcurrentHashMap<String, Long>()

    @Volatile
    private var cachedToken: String = ""

    @Volatile
    private var cachedTokenExpiryMs: Long = 0L

    @Volatile
    private var debounceJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(GoogleDriveSyncState())
    val state: StateFlow<GoogleDriveSyncState> = _state.asStateFlow()

    /** Emits an `IntentSenderRequest` when Google needs the user to tap "Allow" once. */
    private val _pendingAuthIntentSender = MutableStateFlow<IntentSenderRequest?>(null)
    val pendingAuthIntentSender: StateFlow<IntentSenderRequest?> = _pendingAuthIntentSender.asStateFlow()

    fun consumePendingAuthIntentSender() {
        _pendingAuthIntentSender.value = null
    }

    /** Emits the Google account chooser (only used by [changeGoogleAccount]). */
    private val _pendingAuthRecoveryIntent = MutableStateFlow<Intent?>(null)
    val pendingAuthRecoveryIntent: StateFlow<Intent?> = _pendingAuthRecoveryIntent.asStateFlow()

    fun consumePendingAuthRecoveryIntent() {
        _pendingAuthRecoveryIntent.value = null
    }

    private class HttpRes(val code: Int, val body: String, val location: String = "")

    private sealed class AuthOutcome {
        class Token(val token: String) : AuthOutcome()
        class NeedsConsent(val pendingIntent: PendingIntent) : AuthOutcome()
        class Failed(val code: Int, val message: String) : AuthOutcome()
    }

    // ---------------------------------------------------------------------------------------------
    //  INIT
    // ---------------------------------------------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Safe to call many times (UI calls it too). Loads saved state and starts the auto-sync once. */
    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val p = prefs(appCtx)

        // Old builds stored a FAKE "connected" demo state (owner e-mail + API key). Drop it for good.
        runCatching {
            if (appCtx.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE).all.isNotEmpty()) {
                appCtx.deleteSharedPreferences(LEGACY_PREFS_NAME)
            }
        }

        val firstTime: Boolean = synchronized(this) {
            val was = initialized
            initialized = true
            !was
        }
        restoreMapFromPrefs(p.getString(KEY_SUBFOLDER_IDS_JSON, "").orEmpty(), subFolderIdCache)
        restoreMapFromPrefs(p.getString(KEY_FILE_IDS_JSON, "").orEmpty(), fileIdCache)

        val connected = p.getBoolean(KEY_CONNECTED, false)
        val email = p.getString(KEY_LINKED_EMAIL, "").orEmpty()
        val counts = computeCounts(appCtx)
        _state.update {
            it.copy(
                isConnected = connected,
                isCloudApiVerified = connected && p.getString(KEY_FOLDER_ID, "").orEmpty().isNotBlank(),
                linkedEmail = email,
                displayName = p.getString(KEY_DISPLAY_NAME, "").orEmpty(),
                driveFolderId = p.getString(KEY_FOLDER_ID, "").orEmpty(),
                driveQuotaSummary = p.getString(KEY_QUOTA_SUMMARY, "").orEmpty(),
                zeroPhoneStorageImpactMode = p.getBoolean(KEY_ZERO_STORAGE_MODE, true),
                cloudBrainContextSummary = p.getString(KEY_CLOUD_BRAIN_SUMMARY, "").orEmpty(),
                subFoldersReadyCount = if (connected) ALL_REQUIRED_SUBFOLDERS.size else 0,
                backedUpMemoriesCount = counts.mem,
                backedUpConversationsCount = counts.conv,
                backedUpScreenshotsAndFilesCount = p.getInt(KEY_FILE_COUNT, 0),
                backedUpZipPackagesCount = p.getInt(KEY_ZIP_COUNT, 0),
                pendingUploadsCount = counts.pending,
                appSha1 = it.appSha1.ifBlank { computeSigningSha1(appCtx) },
                appPackageName = appCtx.packageName,
                lastSyncStatusBn = if (!firstTime) {
                    it.lastSyncStatusBn
                } else if (connected) {
                    "☁️ Drive ($email) — ব্রেইন সিঙ্ক চালু হচ্ছে..."
                } else {
                    "☁️ Google Drive কানেক্ট করা নেই — ☁️ Connect বাটনে ট্যাপ করুন"
                }
            )
        }

        if (firstTime) {
            startPeriodicLoop()
            if (connected) {
                scope.launch {
                    delay(1_500L)
                    syncAllAppDataToDriveNow(appCtx, speakResult = false)
                }
            }
        }
    }

    private fun startPeriodicLoop() {
        scope.launch {
            while (true) {
                delay(2L * 60L * 1000L)
                val ctx = appContextRef ?: continue
                if (prefs(ctx).getBoolean(KEY_CONNECTED, false) && !_state.value.needsReconnect) {
                    runCatching { syncAllAppDataToDriveNow(ctx, speakResult = false) }
                }
            }
        }
    }

    /** Debounced background sync — call it after any data change; many calls collapse into one. */
    fun requestBackgroundSync(context: Context, delayMs: Long = 4_000L) {
        val appCtx = context.applicationContext
        if (!prefs(appCtx).getBoolean(KEY_CONNECTED, false)) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(delayMs)
            syncAllAppDataToDriveNow(appCtx, speakResult = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  AUTH  (Google Identity Authorization API — no deprecated GoogleSignIn, no fake fallbacks)
    // ---------------------------------------------------------------------------------------------

    private fun savedAccount(ctx: Context): Account? {
        val email = prefs(ctx).getString(KEY_LINKED_EMAIL, "").orEmpty()
        return if (email.contains("@")) Account(email, "com.google") else null
    }

    private suspend fun authorize(ctx: Context): AuthOutcome = suspendCancellableCoroutine { cont ->
        try {
            val builder = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DriveScopes.DRIVE_FILE)))
            savedAccount(ctx)?.let { builder.setAccount(it) }
            Identity.getAuthorizationClient(ctx).authorize(builder.build())
                .addOnSuccessListener { res ->
                    val tok = res.accessToken
                    val pi = res.pendingIntent
                    when {
                        res.hasResolution() && pi != null -> cont.resume(AuthOutcome.NeedsConsent(pi))
                        !tok.isNullOrBlank() -> cont.resume(AuthOutcome.Token(tok))
                        else -> cont.resume(AuthOutcome.Failed(-2, "Google খালি টোকেন দিয়েছে"))
                    }
                }
                .addOnFailureListener { e ->
                    val code = (e as? ApiException)?.statusCode ?: -3
                    cont.resume(AuthOutcome.Failed(code, e.message.orEmpty()))
                }
        } catch (e: Exception) {
            cont.resume(AuthOutcome.Failed(-4, e.message.orEmpty()))
        }
    }

    private fun cacheToken(token: String) {
        cachedToken = token
        cachedTokenExpiryMs = System.currentTimeMillis() + TOKEN_LIFETIME_MS
    }

    /**
     * ALWAYS returns a fresh-enough OAuth token (cached for 45 min, silently renewed afterwards).
     * The old code cached one token forever, so after ~1 hour every Drive call silently failed.
     */
    private suspend fun getAccessToken(ctx: Context, forceRefresh: Boolean = false): String? =
        tokenMutex.withLock {
            val now = System.currentTimeMillis()
            if (!forceRefresh && cachedToken.isNotBlank() && now < cachedTokenExpiryMs) {
                return@withLock cachedToken
            }
            if (forceRefresh && cachedToken.isNotBlank()) {
                val old = cachedToken
                cachedToken = ""
                withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(ctx, old) } }
            }
            if (!prefs(ctx).getBoolean(KEY_CONNECTED, false) && cachedToken.isBlank() && !authInProgress) {
                return@withLock null
            }
            when (val out = authorize(ctx)) {
                is AuthOutcome.Token -> {
                    cacheToken(out.token)
                    out.token
                }
                is AuthOutcome.NeedsConsent -> {
                    markNeedsReconnect("Google Drive-এর অনুমতি নবায়ন করতে হবে।")
                    null
                }
                is AuthOutcome.Failed -> {
                    markNeedsReconnect(describeAuthFailure(ctx, out.code, out.message))
                    null
                }
            }
        }

    @Volatile
    private var authInProgress = false

    private fun markNeedsReconnect(reasonBn: String) {
        _state.update {
            it.copy(
                needsReconnect = true,
                isSyncing = false,
                lastErrorBn = reasonBn,
                lastSyncStatusBn = "🔁 Drive-এর অনুমতি নবায়ন দরকার — ☁️ Connect চাপুন (ডাটা ফোনে সুরক্ষিত আছে)"
            )
        }
    }

    private fun setError(reasonBn: String) {
        _state.update { it.copy(lastErrorBn = reasonBn) }
    }

    /** Connect button. When Google needs consent the sheet is shown through MainActivity's launcher. */
    fun startDirectDriveAuthorization(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        init(appCtx)
        val st = _state.value
        if (st.isConnected && !st.needsReconnect) {
            // Already healthy: the button simply means "sync now".
            triggerFullSyncFromUi(appCtx, onStatusSpoken)
            return
        }
        authInProgress = true
        _state.update {
            it.copy(isSyncing = true, lastErrorBn = "", lastSyncStatusBn = "☁️ Google অ্যাকাউন্ট যাচাই হচ্ছে...")
        }
        scope.launch {
            when (val out = authorize(appCtx)) {
                is AuthOutcome.Token -> completeConnection(appCtx, out.token, onStatusSpoken)
                is AuthOutcome.NeedsConsent -> {
                    _state.update {
                        it.copy(lastSyncStatusBn = "☁️ Google-এর স্ক্রিনে Drive অনুমতিতে 'Allow' দিন...")
                    }
                    _pendingAuthIntentSender.value = IntentSenderRequest.Builder(out.pendingIntent).build()
                }
                is AuthOutcome.Failed -> failConnection(appCtx, out.code, out.message)
            }
        }
    }

    /** Result of the consent sheet launched via [pendingAuthIntentSender]. */
    fun handleAuthorizationIntentResult(
        context: Context,
        data: Intent?,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        if (data == null) {
            authInProgress = false
            _state.update {
                it.copy(isSyncing = false, lastSyncStatusBn = "☁️ Google Drive কানেকশন বাতিল করা হয়েছে।")
            }
            return
        }
        scope.launch {
            try {
                val res = Identity.getAuthorizationClient(appCtx).getAuthorizationResultFromIntent(data)
                val tok = res.accessToken
                if (!tok.isNullOrBlank()) {
                    completeConnection(appCtx, tok, onStatusSpoken)
                } else {
                    failConnection(appCtx, -2, "Google খালি টোকেন দিয়েছে")
                }
            } catch (e: ApiException) {
                failConnection(appCtx, e.statusCode, e.message.orEmpty())
            } catch (e: Exception) {
                failConnection(appCtx, -3, e.message.orEmpty())
            }
        }
    }

    /** Lets the user pick a different Google account (Settings button). */
    fun changeGoogleAccount(context: Context) {
        init(context)
        _pendingAuthRecoveryIntent.value = createAccountPickerIntent()
    }

    /** Result of the account chooser started by [changeGoogleAccount]. */
    fun onAuthRecoveryResult(
        context: Context,
        data: Intent? = null,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        _pendingAuthRecoveryIntent.value = null
        val picked = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME).orEmpty()
        if (!picked.contains("@")) return
        val oldEmail = prefs(appCtx).getString(KEY_LINKED_EMAIL, "").orEmpty()
        if (!picked.equals(oldEmail, ignoreCase = true)) {
            resetLocalConnectionState(appCtx)
            _state.update { it.copy(isConnected = false, isCloudApiVerified = false, needsReconnect = false, driveFolderId = "") }
        }
        prefs(appCtx).edit().putString(KEY_LINKED_EMAIL, picked).apply()
        authInProgress = true
        startDirectDriveAuthorization(appCtx, onStatusSpoken)
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

    private suspend fun completeConnection(
        ctx: Context,
        token: String,
        onStatusSpoken: (String) -> Unit
    ) {
        cacheToken(token)
        authInProgress = true
        val ok = verifyAboutAndStoreIdentity(ctx)
        if (!ok) {
            authInProgress = false
            _state.update { it.copy(isSyncing = false, isConnected = false) }
            return
        }
        prefs(ctx).edit().putBoolean(KEY_CONNECTED, true).apply()
        authInProgress = false
        _state.update {
            it.copy(
                isConnected = true,
                needsReconnect = false,
                lastErrorBn = "",
                lastSyncStatusBn = "✅ Drive কানেক্টেড (${it.linkedEmail}) — ব্রেইন রিস্টোর ও সিঙ্ক হচ্ছে..."
            )
        }
        syncAllAppDataToDriveNow(ctx, speakResult = true, onStatusSpoken = onStatusSpoken)
    }

    private fun failConnection(ctx: Context, code: Int, message: String) {
        authInProgress = false
        val reason = describeAuthFailure(ctx, code, message)
        _state.update {
            it.copy(
                isSyncing = false,
                lastErrorBn = reason,
                lastSyncStatusBn = "❌ Drive কানেক্ট হয়নি — ℹ️ বাটনে সমস্যার কারণ ও সমাধান দেখুন"
            )
        }
    }

    fun disconnectGoogleDrive(context: Context) {
        val appCtx = context.applicationContext
        val old = cachedToken
        if (old.isNotBlank()) {
            scope.launch { runCatching { GoogleAuthUtil.clearToken(appCtx, old) } }
        }
        resetLocalConnectionState(appCtx)
        _state.value = GoogleDriveSyncState(
            appSha1 = computeSigningSha1(appCtx),
            appPackageName = appCtx.packageName,
            zeroPhoneStorageImpactMode = prefs(appCtx).getBoolean(KEY_ZERO_STORAGE_MODE, true)
        )
    }

    /** Clears only the account/session part. Local brain data (memories, chats, pending files) is kept. */
    private fun resetLocalConnectionState(ctx: Context) {
        cachedToken = ""
        cachedTokenExpiryMs = 0L
        subFolderIdCache.clear()
        fileIdCache.clear()
        prefs(ctx).edit()
            .remove(KEY_LINKED_EMAIL)
            .remove(KEY_DISPLAY_NAME)
            .remove(KEY_FOLDER_ID)
            .remove(KEY_SUBFOLDER_IDS_JSON)
            .remove(KEY_FILE_IDS_JSON)
            .remove(KEY_QUOTA_SUMMARY)
            .remove(KEY_UPLOADED_MEM_KEYS)
            .remove(KEY_UPLOADED_FINGERPRINTS)
            .remove(KEY_LAST_PULL_ISO)
            .remove(KEY_LAST_MASTER_BACKUP_MS)
            .putBoolean(KEY_CONNECTED, false)
            .apply()
        // Everything is re-checked against the (possibly different) Drive on the next connect.
        synchronized(convLock) {
            val dir = convDir(ctx)
            val days = dir.listFiles()?.map { it.name.removePrefix("conversation_").removeSuffix(".jsonl") }.orEmpty()
            prefs(ctx).edit().putStringSet(KEY_DIRTY_CONV_DAYS, HashSet<String>(days)).apply()
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  DIAGNOSTICS  (why Google refused — the real cause of the old "demo account" problem)
    // ---------------------------------------------------------------------------------------------

    private fun computeSigningSha1(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val bytes: ByteArray? = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        }
        if (bytes == null) {
            ""
        } else {
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString(":") { b -> "%02X".format(b) }
        }
    }.getOrDefault("")

    private fun sha1Matches(sha1: String): Boolean =
        sha1.replace(":", "").lowercase(Locale.US) == EXPECTED_CERT_SHA1

    private fun describeAuthFailure(ctx: Context, code: Int, msg: String): String {
        val sha1 = _state.value.appSha1.ifBlank { computeSigningSha1(ctx) }
        return when (code) {
            10, 12500 -> {
                val mismatch = if (sha1.isNotBlank() && !sha1Matches(sha1)) {
                    " এই APK-র SHA-1 ($sha1) google-services.json-এ রেজিস্টার করা SHA-1-এর সাথে মেলেনি।"
                } else {
                    ""
                }
                "Google এই অ্যাপকে অনুমতি দেয়নি (DEVELOPER_ERROR $code).$mismatch " +
                    "কারণ: Google Cloud-এ APK-র SHA-1 রেজিস্টার নেই, অথবা OAuth consent screen-এ আপনার Gmail 'Test user' হিসেবে যোগ করা নেই।"
            }
            16, 12501 -> "আপনি অনুমতি বাতিল করেছেন।"
            7, 12502 -> "ইন্টারনেট নেই বা খুব দুর্বল ($code)।"
            17 -> "এই ফোনে Google Play Services আপডেট/চালু নেই ($code)।"
            -1, -2, -3, -4 -> "Google অনুমতি প্রক্রিয়া ব্যর্থ ($code): ${msg.take(140)}"
            else -> "Google ত্রুটি কোড $code: ${msg.take(140)}"
        }
    }

    /** Full Bangla checklist shown by the ℹ️ button. */
    fun buildSetupDiagnosticBn(context: Context): String {
        val appCtx = context.applicationContext
        val sha1 = _state.value.appSha1.ifBlank { computeSigningSha1(appCtx) }
        val matches = sha1.isNotBlank() && sha1Matches(sha1)
        return buildString {
            appendLine("📦 প্যাকেজ: ${appCtx.packageName}")
            appendLine("🔑 এই APK-র SHA-1: ${sha1.ifBlank { "পড়া যায়নি" }}")
            appendLine("✅ Cloud-এ রেজিস্টার করা SHA-1: ${EXPECTED_CERT_SHA1.chunked(2).joinToString(":").uppercase(Locale.US)}")
            appendLine(if (matches) "✔ দুটো মিলেছে।" else "❌ দুটো মেলেনি — Google লগইন এ কারণেই ব্যর্থ হয়।")
            val err = _state.value.lastErrorBn
            if (err.isNotBlank()) {
                appendLine()
                appendLine("সর্বশেষ ত্রুটি: $err")
            }
            appendLine()
            appendLine("সমাধানের ধাপ (একবারই করতে হবে):")
            appendLine("১) console.cloud.google.com → আপনার প্রজেক্ট → APIs & Services → Library → 'Google Drive API' → Enable।")
            appendLine("২) Credentials → Android OAuth client (package ${appCtx.packageName}) → উপরের 'এই APK-র SHA-1' যোগ করুন।")
            appendLine("৩) OAuth consent screen যদি Testing মোডে থাকে → Test users-এ আপনার Gmail যোগ করুন।")
            appendLine("৪) GitHub Actions দিয়ে APK বানালে একই keystore ব্যবহার করুন (repo-তে debug.keystore.base64) — নইলে প্রতি বিল্ডে SHA-1 বদলে যায়।")
            append("৫) এরপর অ্যাপে ☁️ Connect চাপুন।")
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  HTTP CORE
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes one Drive request with a fresh token. Retries on network errors, 429 and 5xx with back-off
     * and renews the token once on 401. Returns null only when nothing could be done (no token / offline).
     */
    private suspend fun driveRequest(ctx: Context, build: (String) -> Request): HttpRes? {
        var forceRefresh = false
        var attempt = 0
        while (attempt < 4) {
            val token = getAccessToken(ctx, forceRefresh) ?: return null
            forceRefresh = false
            var retry = false
            var result: HttpRes? = null
            try {
                httpClient.newCall(build(token)).execute().use { r ->
                    val code = r.code
                    if (code == 401) {
                        forceRefresh = true
                        retry = true
                    } else if (code == 429 || code >= 500) {
                        retry = true
                    } else {
                        result = HttpRes(code, r.body?.string().orEmpty(), r.header("Location").orEmpty())
                    }
                }
            } catch (_: IOException) {
                retry = true
            }
            if (!retry) return result
            attempt++
            delay(500L * (1 shl attempt))
        }
        return null
    }

    private fun describeHttpError(res: HttpRes): String {
        val parsed = runCatching {
            val err = JSONObject(res.body).optJSONObject("error")
            val first = err?.optJSONArray("errors")?.optJSONObject(0)
            Pair(first?.optString("reason").orEmpty(), err?.optString("message").orEmpty())
        }.getOrDefault(Pair("", ""))
        val reason = parsed.first
        val message = parsed.second
        return when {
            reason == "accessNotConfigured" || message.contains("has not been used", true) ||
                message.contains("is disabled", true) ->
                "Google Drive API এই Google Cloud প্রজেক্টে চালু (Enable) করা নেই — Cloud Console → APIs & Services → Library → 'Google Drive API' → Enable।"
            reason.contains("storageQuota", true) -> "Google Drive-এ জায়গা শেষ।"
            res.code == 403 -> "Drive অনুমতি পাওয়া যায়নি (403) — ☁️ Connect চেপে আবার অনুমতি দিন। ${message.take(100)}"
            res.code == 401 -> "Drive টোকেন বাতিল (401) — ☁️ Connect চেপে আবার অনুমতি দিন।"
            else -> "Drive ত্রুটি ${res.code}: ${message.take(120)}"
        }
    }

    private fun qEsc(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun mediaTypeOf(mime: String) =
        runCatching { mime.toMediaType() }.getOrNull() ?: "application/octet-stream".toMediaType()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private suspend fun listFiles(
        ctx: Context,
        q: String,
        orderBy: String = "",
        pageSize: Int = 100,
        maxPages: Int = 50
    ): List<JSONObject>? {
        val out = ArrayList<JSONObject>()
        var pageToken = ""
        for (page in 0 until maxPages) {
            val url = buildString {
                append("$DRIVE_API_BASE/files?q=").append(enc(q))
                append("&pageSize=").append(pageSize)
                append("&spaces=drive&fields=")
                append(enc("nextPageToken,files(id,name,mimeType,description,createdTime,modifiedTime,webViewLink)"))
                if (orderBy.isNotBlank()) append("&orderBy=").append(enc(orderBy))
                if (pageToken.isNotBlank()) append("&pageToken=").append(enc(pageToken))
            }
            val res = driveRequest(ctx) { t ->
                Request.Builder().url(url).addHeader("Authorization", "Bearer $t").get().build()
            } ?: return null
            if (res.code !in 200..299) {
                setError(describeHttpError(res))
                return null
            }
            val obj = runCatching { JSONObject(res.body) }.getOrNull() ?: return null
            val files = obj.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    files.optJSONObject(i)?.let { out.add(it) }
                }
            }
            pageToken = obj.optString("nextPageToken")
            if (pageToken.isBlank()) break
        }
        return out
    }

    private suspend fun downloadText(ctx: Context, fileId: String): String? {
        val res = driveRequest(ctx) { t ->
            Request.Builder().url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $t").get().build()
        } ?: return null
        return if (res.code in 200..299) res.body else null
    }

    private suspend fun trashFile(ctx: Context, fileId: String): Boolean {
        val res = driveRequest(ctx) { t ->
            Request.Builder().url("$DRIVE_API_BASE/files/$fileId?fields=id")
                .addHeader("Authorization", "Bearer $t")
                .patch(JSONObject().put("trashed", true).toString().toRequestBody(jsonMedia))
                .build()
        } ?: return false
        return res.code in 200..299 || res.code == 404
    }

    // ---------------------------------------------------------------------------------------------
    //  FOLDERS
    // ---------------------------------------------------------------------------------------------

    private suspend fun verifyAboutAndStoreIdentity(ctx: Context): Boolean {
        val res = driveRequest(ctx) { t ->
            Request.Builder()
                .url("$DRIVE_API_BASE/about?fields=user(emailAddress,displayName),storageQuota(limit,usage)")
                .addHeader("Authorization", "Bearer $t").get().build()
        }
        if (res == null) {
            setError("Drive-এর সাথে যোগাযোগ করা যায়নি (ইন্টারনেট/টোকেন সমস্যা)।")
            _state.update { it.copy(lastSyncStatusBn = "❌ Drive-এর সাথে যোগাযোগ হয়নি — ℹ️ বাটনে কারণ দেখুন") }
            return false
        }
        if (res.code !in 200..299) {
            val why = describeHttpError(res)
            setError(why)
            _state.update { it.copy(lastSyncStatusBn = "❌ Drive কানেক্ট হয়নি — ℹ️ বাটনে সমস্যার কারণ ও সমাধান দেখুন") }
            return false
        }
        val root = runCatching { JSONObject(res.body) }.getOrNull() ?: return false
        val user = root.optJSONObject("user")
        val email = user?.optString("emailAddress").orEmpty()
        val name = user?.optString("displayName").orEmpty()
        val quota = root.optJSONObject("storageQuota")
        var quotaText = ""
        if (quota != null) {
            val usage = quota.optString("usage", "0").toLongOrNull() ?: 0L
            val limit = quota.optString("limit", "0").toLongOrNull() ?: 0L
            val usedGb = String.format(Locale.US, "%.2f GB", usage / (1024.0 * 1024.0 * 1024.0))
            quotaText = if (limit > 0L) {
                "$usedGb / " + String.format(Locale.US, "%.0f GB", limit / (1024.0 * 1024.0 * 1024.0))
            } else {
                "$usedGb Used"
            }
        }
        if (email.isBlank()) {
            setError("Drive আপনার ইমেইল ফেরত দেয়নি।")
            return false
        }
        prefs(ctx).edit()
            .putString(KEY_LINKED_EMAIL, email)
            .putString(KEY_DISPLAY_NAME, name.ifBlank { email.substringBefore("@") })
            .putString(KEY_QUOTA_SUMMARY, quotaText)
            .apply()
        _state.update {
            it.copy(
                linkedEmail = email,
                displayName = name.ifBlank { email.substringBefore("@") },
                driveQuotaSummary = quotaText
            )
        }
        return true
    }

    private suspend fun ensureRootFolderId(ctx: Context): String? = folderMutex.withLock {
        val p = prefs(ctx)
        val cachedId = p.getString(KEY_FOLDER_ID, "").orEmpty()
        if (cachedId.isNotBlank()) {
            val check = driveRequest(ctx) { t ->
                Request.Builder().url("$DRIVE_API_BASE/files/$cachedId?fields=id,trashed")
                    .addHeader("Authorization", "Bearer $t").get().build()
            }
            if (check != null && check.code in 200..299) {
                val obj = runCatching { JSONObject(check.body) }.getOrNull()
                if (obj != null && !obj.optBoolean("trashed", false)) return@withLock cachedId
            } else if (check == null) {
                return@withLock cachedId // offline: keep trusting the saved id
            }
            p.edit().remove(KEY_FOLDER_ID).apply()
            subFolderIdCache.clear()
            fileIdCache.clear()
        }
        val found = listFiles(
            ctx,
            "name = '$AI_OS_MEMORY_FOLDER_NAME' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
            orderBy = "createdTime",
            pageSize = 10,
            maxPages = 1
        )
        val existing = found?.firstOrNull()?.optString("id").orEmpty()
        if (existing.isNotBlank()) {
            p.edit().putString(KEY_FOLDER_ID, existing).apply()
            return@withLock existing
        }
        if (found == null) return@withLock null // listing failed: never create a duplicate blindly
        val meta = JSONObject().apply {
            put("name", AI_OS_MEMORY_FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
            put("description", "Archer AI — Google Drive = AI Brain")
        }
        val res = driveRequest(ctx) { t ->
            Request.Builder().url("$DRIVE_API_BASE/files?fields=id")
                .addHeader("Authorization", "Bearer $t")
                .post(meta.toString().toRequestBody(jsonMedia)).build()
        }
        if (res != null && res.code in 200..299) {
            val id = runCatching { JSONObject(res.body).optString("id") }.getOrDefault("")
            if (id.isNotBlank()) {
                p.edit().putString(KEY_FOLDER_ID, id).apply()
                return@withLock id
            }
        } else if (res != null) {
            setError(describeHttpError(res))
        }
        null
    }

    /** Finds or creates `/AI_OS_Memory/<name>`. Returns null when it really could not (never a wrong folder). */
    suspend fun ensureSubFolderId(context: Context, subFolderName: String): String? {
        val appCtx = context.applicationContext
        val cleanSub = subFolderName.trim().ifBlank { SUBFOLDER_CODE }
        subFolderIdCache[cleanSub]?.let { if (it.isNotBlank()) return it }
        val rootId = ensureRootFolderId(appCtx) ?: return null
        return folderMutex.withLock {
            subFolderIdCache[cleanSub]?.let { if (it.isNotBlank()) return@withLock it }
            val found = listFiles(
                appCtx,
                "'$rootId' in parents and name = '${qEsc(cleanSub)}' and mimeType = 'application/vnd.google-apps.folder' and trashed = false",
                orderBy = "createdTime",
                pageSize = 10,
                maxPages = 1
            ) ?: return@withLock null
            val existing = found.firstOrNull()?.optString("id").orEmpty()
            if (existing.isNotBlank()) {
                subFolderIdCache[cleanSub] = existing
                persistMap(appCtx, KEY_SUBFOLDER_IDS_JSON, subFolderIdCache)
                return@withLock existing
            }
            val meta = JSONObject().apply {
                put("name", cleanSub)
                put("mimeType", "application/vnd.google-apps.folder")
                put("parents", JSONArray().put(rootId))
            }
            val res = driveRequest(appCtx) { t ->
                Request.Builder().url("$DRIVE_API_BASE/files?fields=id")
                    .addHeader("Authorization", "Bearer $t")
                    .post(meta.toString().toRequestBody(jsonMedia)).build()
            }
            val id = if (res != null && res.code in 200..299) {
                runCatching { JSONObject(res.body).optString("id") }.getOrDefault("")
            } else {
                ""
            }
            if (id.isNotBlank()) {
                subFolderIdCache[cleanSub] = id
                persistMap(appCtx, KEY_SUBFOLDER_IDS_JSON, subFolderIdCache)
                id
            } else {
                null
            }
        }
    }

    private suspend fun provisionFolders(ctx: Context): Boolean {
        val rootId = ensureRootFolderId(ctx)
        if (rootId.isNullOrBlank()) return false
        var ready = 0
        for (sub in ALL_REQUIRED_SUBFOLDERS) {
            if (!ensureSubFolderId(ctx, sub).isNullOrBlank()) ready++
        }
        _state.update {
            it.copy(
                driveFolderId = rootId,
                isCloudApiVerified = true,
                subFoldersReadyCount = ready
            )
        }
        return ready > 0
    }

    private fun persistMap(ctx: Context, key: String, map: Map<String, String>) {
        runCatching {
            val obj = JSONObject()
            for ((k, v) in map) if (v.isNotBlank()) obj.put(k, v)
            prefs(ctx).edit().putString(key, obj.toString()).apply()
        }
    }

    private fun restoreMapFromPrefs(raw: String, into: MutableMap<String, String>) {
        if (raw.isBlank()) return
        runCatching {
            val obj = JSONObject(raw)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = obj.optString(k)
                if (v.isNotBlank()) into[k] = v
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  LOCAL BRAIN STORE  (write-ahead: nothing is ever lost while Drive is offline)
    // ---------------------------------------------------------------------------------------------

    private fun ensureInit(ctx: Context) {
        if (appContextRef == null) init(ctx)
    }

    private fun isDriveConnected(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CONNECTED, false)

    private fun brainDir(ctx: Context): File =
        File(ctx.applicationContext.filesDir, "brain").apply { if (!exists()) mkdirs() }

    private fun convDir(ctx: Context): File =
        File(brainDir(ctx), "conversations").apply { if (!exists()) mkdirs() }

    private fun pendingDir(ctx: Context): File =
        File(brainDir(ctx), "pending_files").apply { if (!exists()) mkdirs() }

    private fun convFile(ctx: Context, day: String): File = File(convDir(ctx), "conversation_$day.jsonl")

    private fun safeName(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "file" }

    private fun dayOf(ts: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))

    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { b -> "%02x".format(b) }

    /** Stable, collision-free id of a memory (the old code used the title only -> same title overwrote others). */
    private fun memoryKey(title: String, content: String): String =
        sha1Hex(title.trim().lowercase(Locale.ROOT) + "\u0000" + content.trim().lowercase(Locale.ROOT)).take(20)

    private fun memoryFileName(key: String): String = "mem_$key.json"

    private fun convKey(ts: Long, userPrompt: String): String = "$ts|${userPrompt.trim().take(120)}"

    private fun readStringSet(ctx: Context, key: String): Set<String> = synchronized(setLock) {
        HashSet<String>(prefs(ctx).getStringSet(key, null) ?: emptySet<String>())
    }

    private fun updateStringSet(ctx: Context, key: String, mutate: (MutableSet<String>) -> Unit): Set<String> =
        synchronized(setLock) {
            val p = prefs(ctx)
            val s = HashSet<String>(p.getStringSet(key, null) ?: emptySet<String>())
            mutate(s)
            p.edit().putStringSet(key, s).apply()
            s
        }

    private fun bumpCount(ctx: Context, key: String, by: Int = 1): Int = synchronized(setLock) {
        val p = prefs(ctx)
        val n = p.getInt(key, 0) + by
        p.edit().putInt(key, n).apply()
        n
    }

    private class Counts(val mem: Int, val conv: Int, val pending: Int)

    private fun computeCounts(ctx: Context): Counts {
        val mem = readStringSet(ctx, KEY_UPLOADED_MEM_KEYS).size
        val dirty = readStringSet(ctx, KEY_DIRTY_CONV_DAYS).size
        val tomb = readStringSet(ctx, KEY_TOMBSTONES).size
        val days = convDir(ctx).listFiles()?.size ?: 0
        val pendingFiles = pendingDir(ctx).walkTopDown().count { it.isFile }
        return Counts(mem, (days - dirty).coerceAtLeast(0), dirty + tomb + pendingFiles)
    }

    private fun refreshCountsIntoState(ctx: Context) {
        val c = computeCounts(ctx)
        val p = prefs(ctx)
        _state.update {
            it.copy(
                backedUpMemoriesCount = c.mem,
                backedUpConversationsCount = c.conv,
                pendingUploadsCount = c.pending,
                backedUpScreenshotsAndFilesCount = p.getInt(KEY_FILE_COUNT, 0),
                backedUpZipPackagesCount = p.getInt(KEY_ZIP_COUNT, 0)
            )
        }
    }

    private fun addTombstone(ctx: Context, sub: String, fileName: String) {
        updateStringSet(ctx, KEY_TOMBSTONES) { it.add("$sub|$fileName") }
        if (sub == SUBFOLDER_MEMORIES && fileName.startsWith("mem_") && fileName.endsWith(".json")) {
            val key = fileName.removePrefix("mem_").removeSuffix(".json")
            updateStringSet(ctx, KEY_UPLOADED_MEM_KEYS) { it.remove(key) }
        }
    }

    private fun removeTombstone(ctx: Context, sub: String, fileName: String) {
        val entry = "$sub|$fileName"
        if (readStringSet(ctx, KEY_TOMBSTONES).contains(entry)) {
            updateStringSet(ctx, KEY_TOMBSTONES) { it.remove(entry) }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  UPLOAD CORE  (one copy per name, resumable for big files, id cache, no fake ids)
    // ---------------------------------------------------------------------------------------------

    private val selfModified = ConcurrentHashMap<String, String>()

    private suspend fun uploadBytes(
        ctx: Context,
        sub: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
        description: String
    ): String? {
        val mt = mediaTypeOf(mime)
        return uploadCore(ctx, sub, fileName, mime, bytes.size.toLong(), { bytes.toRequestBody(mt) }, description)
    }

    private suspend fun uploadFile(
        ctx: Context,
        sub: String,
        file: File,
        fileName: String,
        mime: String,
        description: String
    ): String? {
        if (!file.exists()) return null
        val mt = mediaTypeOf(mime)
        return uploadCore(ctx, sub, fileName, mime, file.length(), { file.asRequestBody(mt) }, description)
    }

    private suspend fun uploadCore(
        ctx: Context,
        sub: String,
        fileName: String,
        mime: String,
        size: Long,
        bodyFactory: () -> RequestBody,
        description: String
    ): String? {
        val appCtx = ctx.applicationContext
        if (!isDriveConnected(appCtx)) return null
        val parentId = ensureSubFolderId(appCtx, sub) ?: return null
        val cacheKey = "$sub/$fileName"
        var fileId = fileIdCache[cacheKey].orEmpty()
        for (attempt in 0..1) {
            if (fileId.isBlank()) {
                fileId = findOrCreateFileId(appCtx, parentId, fileName, mime, description) ?: return null
                fileIdCache[cacheKey] = fileId
                persistMap(appCtx, KEY_FILE_IDS_JSON, fileIdCache)
            }
            val res = putMedia(appCtx, fileId, mime, size, bodyFactory) ?: return null
            if (res.code in 200..299) {
                runCatching { JSONObject(res.body).optString("modifiedTime") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { selfModified[fileId] = it }
                return fileId
            }
            if (res.code == 404 && attempt == 0) {
                fileIdCache.remove(cacheKey)
                fileId = ""
                continue
            }
            setError(describeHttpError(res))
            return null
        }
        return null
    }

    private suspend fun putMedia(
        ctx: Context,
        fileId: String,
        mime: String,
        size: Long,
        bodyFactory: () -> RequestBody
    ): HttpRes? {
        if (size <= SIMPLE_UPLOAD_MAX_BYTES) {
            return driveRequest(ctx) { t ->
                Request.Builder()
                    .url("$DRIVE_UPLOAD_BASE/files/$fileId?uploadType=media&fields=id,modifiedTime")
                    .addHeader("Authorization", "Bearer $t")
                    .patch(bodyFactory())
                    .build()
            }
        }
        // Big files (ZIP packages, screenshots...) go through a resumable session in one streamed PUT.
        val session = driveRequest(ctx) { t ->
            Request.Builder()
                .url("$DRIVE_UPLOAD_BASE/files/$fileId?uploadType=resumable&fields=id,modifiedTime")
                .addHeader("Authorization", "Bearer $t")
                .addHeader("X-Upload-Content-Type", mime)
                .addHeader("X-Upload-Content-Length", size.toString())
                .patch("{}".toRequestBody(jsonMedia))
                .build()
        } ?: return null
        if (session.code !in 200..299 || session.location.isBlank()) return session
        return driveRequest(ctx) { t ->
            Request.Builder()
                .url(session.location)
                .addHeader("Authorization", "Bearer $t")
                .put(bodyFactory())
                .build()
        }
    }

    private suspend fun findOrCreateFileId(
        ctx: Context,
        parentId: String,
        fileName: String,
        mime: String,
        description: String
    ): String? {
        val found = listFiles(
            ctx,
            "'$parentId' in parents and name = '${qEsc(fileName)}' and trashed = false",
            orderBy = "createdTime",
            pageSize = 10,
            maxPages = 1
        ) ?: return null
        if (found.isNotEmpty()) {
            val first = found[0].optString("id")
            // keep exactly ONE copy of a name (older builds sometimes left duplicates)
            for (i in 1 until found.size) trashFile(ctx, found[i].optString("id"))
            if (first.isNotBlank()) return first
        }
        val meta = JSONObject().apply {
            put("name", fileName)
            put("mimeType", mime)
            put("description", description.take(1000))
            put("parents", JSONArray().put(parentId))
        }
        val res = driveRequest(ctx) { t ->
            Request.Builder().url("$DRIVE_API_BASE/files?fields=id")
                .addHeader("Authorization", "Bearer $t")
                .post(meta.toString().toRequestBody(jsonMedia)).build()
        } ?: return null
        if (res.code in 200..299) {
            return runCatching { JSONObject(res.body).optString("id") }.getOrNull()?.takeIf { it.isNotBlank() }
        }
        setError(describeHttpError(res))
        return null
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
            low.endsWith(".mp3") -> "audio/mpeg"
            low.endsWith(".wav") -> "audio/wav"
            else -> "text/plain"
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  MEMORIES
    // ---------------------------------------------------------------------------------------------

    private suspend fun uploadMemoryNote(
        ctx: Context,
        key: String,
        title: String,
        content: String,
        category: String,
        timestamp: Long
    ): Boolean {
        val json = JSONObject().apply {
            put("type", "AI_OS_MEMORY_NOTE")
            put("key", key)
            put("title", title)
            put("content", content)
            put("fact", content)
            put("category", category)
            put("tags", category)
            put("timestamp", timestamp)
            put("formattedTime", SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()).format(Date(timestamp)))
        }.toString(2)
        val id = uploadBytes(
            ctx = ctx,
            sub = SUBFOLDER_MEMORIES,
            fileName = memoryFileName(key),
            mime = "application/json",
            bytes = json.toByteArray(Charsets.UTF_8),
            description = "[MEMORY] $title: ${content.take(180)} ($category)"
        )
        if (id.isNullOrBlank()) return false
        updateStringSet(ctx, KEY_UPLOADED_MEM_KEYS) { it.add(key) }
        return true
    }

    /**
     * Saves one memory to `/AI_OS_Memory/Memories` (one file per memory, id = hash of title+content).
     * The phone database already holds it, so a failed upload is simply retried automatically later.
     */
    suspend fun backupMemoryNoteToDrive(
        context: Context,
        title: String,
        factContent: String,
        tagsOrCategory: String
    ): Boolean = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        val content = factContent.trim()
        if (content.isBlank()) return@withContext false
        val cleanTitle = title.ifBlank { "কিউব নিউরাল মেমোরি" }
        val key = memoryKey(cleanTitle, content)
        // the user saved something he/she had forgotten before -> cancel the pending deletion
        removeTombstone(appCtx, SUBFOLDER_MEMORIES, memoryFileName(key))
        if (!isDriveConnected(appCtx)) {
            refreshCountsIntoState(appCtx)
            return@withContext false
        }
        val ok = uploadMemoryNote(appCtx, key, cleanTitle, content, tagsOrCategory, System.currentTimeMillis())
        refreshCountsIntoState(appCtx)
        if (ok) {
            _state.update {
                it.copy(lastSyncStatusBn = "☁️ মেমোরি '/$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_MEMORIES'-এ অটো-সেভ সম্পন্ন")
            }
        }
        ok
    }

    // ---------------------------------------------------------------------------------------------
    //  CONVERSATIONS  (append-only day log; the old code overwrote ONE file per day -> lost every turn)
    // ---------------------------------------------------------------------------------------------

    private class ConvEntry(val ts: Long, val user: String, val reply: String, val actions: String)

    private fun entryJson(e: ConvEntry): JSONObject = JSONObject().apply {
        put("timestamp", e.ts)
        put("userPrompt", e.user)
        put("agentReply", e.reply)
        put("actionsSummary", e.actions)
    }

    private fun parseConvEntry(o: JSONObject): ConvEntry? {
        val u = o.optString("userPrompt").trim()
        val a = o.optString("agentReply").trim()
        if (u.isBlank() && a.isBlank()) return null
        return ConvEntry(o.optLong("timestamp", 0L), u, a, o.optString("actionsSummary"))
    }

    /** Appends to the local day log. Returns the day, or "" when it was a duplicate. */
    private fun appendConversationLocal(ctx: Context, ts: Long, user: String, reply: String, actions: String): String =
        synchronized(convLock) {
            val day = dayOf(ts)
            val f = convFile(ctx, day)
            val lines = if (f.exists()) f.readLines(Charsets.UTF_8) else emptyList()
            val duplicate = lines.takeLast(8).any { line ->
                runCatching {
                    val o = JSONObject(line)
                    o.optString("userPrompt") == user &&
                        o.optString("agentReply") == reply &&
                        abs(o.optLong("timestamp") - ts) < 90_000L
                }.getOrDefault(false)
            }
            if (duplicate) {
                ""
            } else {
                f.appendText(entryJson(ConvEntry(ts, user, reply, actions)).toString() + "\n", Charsets.UTF_8)
                day
            }
        }

    private suspend fun uploadConversationDay(ctx: Context, day: String): Boolean {
        val f = convFile(ctx, day)
        if (!f.exists() || f.length() == 0L) {
            updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.remove(day) }
            return true
        }
        val id = uploadFile(ctx, SUBFOLDER_CONVERSATIONS, f, f.name, "text/plain", "[CONVERSATIONS] $day")
        if (id.isNullOrBlank()) return false
        convLastUploadMs[day] = System.currentTimeMillis()
        updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.remove(day) }
        return true
    }

    /** Every chat turn: saved on the phone first, then pushed to `/AI_OS_Memory/Conversations/`. */
    suspend fun backupConversationSummaryToDrive(
        context: Context,
        userPrompt: String,
        agentReply: String,
        actionsSummary: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (userPrompt.isBlank() && agentReply.isBlank()) return@withContext false
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        val day = appendConversationLocal(appCtx, System.currentTimeMillis(), userPrompt, agentReply, actionsSummary)
        if (day.isNotBlank()) {
            updateStringSet(appCtx, KEY_DIRTY_CONV_DAYS) { it.add(day) }
        }
        if (!isDriveConnected(appCtx)) {
            refreshCountsIntoState(appCtx)
            return@withContext false
        }
        val today = day.ifBlank { dayOf(System.currentTimeMillis()) }
        val last = convLastUploadMs[today] ?: 0L
        if (System.currentTimeMillis() - last < 15_000L) {
            return@withContext false // next turn / the 2-minute loop uploads the whole day log
        }
        val ok = uploadConversationDay(appCtx, today)
        refreshCountsIntoState(appCtx)
        ok
    }

    /** Fire-and-forget hook used by the Room repository for every logged command. */
    fun archiveConversationTurn(userPrompt: String, agentReply: String, actionsSummary: String) {
        val ctx = appContextRef ?: return
        scope.launch { backupConversationSummaryToDrive(ctx, userPrompt, agentReply, actionsSummary) }
    }

    private fun readDayEntries(ctx: Context, f: File): List<ConvEntry> {
        if (!f.exists()) return emptyList()
        return f.readLines(Charsets.UTF_8).mapNotNull { line ->
            runCatching { parseConvEntry(JSONObject(line)) }.getOrNull()
        }
    }

    /** Merges entries downloaded from Drive into the local day log. Returns (added entries, local had extra lines). */
    private fun mergeConversationDayLocal(
        ctx: Context,
        day: String,
        remote: List<ConvEntry>
    ): Pair<List<ConvEntry>, Boolean> = synchronized(convLock) {
        val f = convFile(ctx, day)
        val localEntries = readDayEntries(ctx, f)
        val localKeys = HashSet<String>()
        localEntries.forEach { localKeys.add(convKey(it.ts, it.user)) }
        val remoteKeys = HashSet<String>()
        remote.forEach { remoteKeys.add(convKey(it.ts, it.user)) }
        val hadLocalOnly = localKeys.any { it !in remoteKeys }
        val added = ArrayList<ConvEntry>()
        val sb = StringBuilder()
        for (e in remote) {
            val k = convKey(e.ts, e.user)
            if (k !in localKeys) {
                localKeys.add(k)
                sb.append(entryJson(e).toString()).append('\n')
                added.add(e)
            }
        }
        if (sb.isNotEmpty()) f.appendText(sb.toString(), Charsets.UTF_8)
        Pair(added, hadLocalOnly)
    }

    /** Room history rows that never reached the archive (older builds) are copied into it once. */
    private suspend fun backfillConversationArchiveFromRoom(ctx: Context) {
        val history = runCatching {
            AgentDatabase.getInstance(ctx).commandHistoryDao().getRecentHistorySnapshot(100_000)
        }.getOrDefault(emptyList())
        if (history.isEmpty()) return
        val byDay = history.groupBy { dayOf(it.timestamp) }
        for ((day, rows) in byDay) {
            val entries = rows.map { ConvEntry(it.timestamp, it.userPrompt, it.agentReply, it.actionsSummary) }
            if (appendMissingEntries(ctx, day, entries) > 0) {
                updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.add(day) }
            }
        }
    }

    private fun appendMissingEntries(ctx: Context, day: String, entries: List<ConvEntry>): Int =
        synchronized(convLock) {
            val f = convFile(ctx, day)
            val keys = HashSet<String>()
            readDayEntries(ctx, f).forEach { keys.add(convKey(it.ts, it.user)) }
            val sb = StringBuilder()
            var n = 0
            for (e in entries) {
                val k = convKey(e.ts, e.user)
                if (k !in keys) {
                    keys.add(k)
                    sb.append(entryJson(e).toString()).append('\n')
                    n++
                }
            }
            if (sb.isNotEmpty()) f.appendText(sb.toString(), Charsets.UTF_8)
            n
        }

    private fun searchConversationArchive(ctx: Context, query: String, limit: Int): List<String> {
        val q = query.trim()
        val files = convDir(ctx).listFiles()?.sortedByDescending { it.name }.orEmpty()
        val out = ArrayList<String>()
        val fmt = SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault())
        for (f in files) {
            val entries = readDayEntries(ctx, f).sortedByDescending { it.ts }
            for (e in entries) {
                val all = q.isBlank() || q.equals("ALL", true) || q.equals("RECENT", true)
                if (all || e.user.contains(q, true) || e.reply.contains(q, true) || e.actions.contains(q, true)) {
                    out.add("[${fmt.format(Date(e.ts))}] User: ${e.user.take(110)} -> AI: ${e.reply.take(150)}")
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------------
    //  GENERATED FILES  (code, ZIP, screenshots, voice prints, cloud bots)
    // ---------------------------------------------------------------------------------------------

    /**
     * Stores the file on the phone first (`files/brain/pending_files/<folder>/`), uploads it right away
     * and deletes the private copy only after Drive confirmed it. If Drive is unreachable the upload is
     * retried automatically — the caller gets `false` so no code claims a fake success.
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
        ensureInit(appCtx)
        val name = safeName(fileName)
        val sub = safeName(
            targetSubFolder.ifBlank {
                when {
                    name.endsWith(".zip", ignoreCase = true) -> SUBFOLDER_ZIPS
                    name.endsWith(".jpg", ignoreCase = true) ||
                        name.endsWith(".jpeg", ignoreCase = true) ||
                        name.endsWith(".png", ignoreCase = true) ||
                        name.endsWith(".mp3", ignoreCase = true) ||
                        name.endsWith(".wav", ignoreCase = true) -> SUBFOLDER_MEDIA
                    else -> SUBFOLDER_CODE
                }
            }
        )
        val pending = File(File(pendingDir(appCtx), sub), name)
        runCatching {
            pending.parentFile?.mkdirs()
            pending.writeBytes(contentBytes)
        }
        if (!isDriveConnected(appCtx)) {
            refreshCountsIntoState(appCtx)
            return@withContext false
        }
        val id = uploadBytes(
            appCtx, sub, name, mimeType.ifBlank { guessMimeType(name) }, contentBytes,
            "[FILE] $name — $summaryDescription"
        )
        if (id.isNullOrBlank()) {
            refreshCountsIntoState(appCtx)
            return@withContext false
        }
        runCatching { pending.delete() }
        bumpCount(appCtx, KEY_FILE_COUNT)
        if (name.endsWith(".zip", ignoreCase = true)) bumpCount(appCtx, KEY_ZIP_COUNT)
        refreshCountsIntoState(appCtx)
        _state.update {
            it.copy(lastSyncStatusBn = "☁️ '$name' সরাসরি Google Drive (/$AI_OS_MEMORY_FOLDER_NAME/$sub)-এ সেভ হয়েছে")
        }
        true
    }

    suspend fun backupScreenshotBitmapToDrive(
        context: Context,
        bitmap: Bitmap,
        captionOrContext: String = "Screen Snapshot"
    ): Boolean = withContext(Dispatchers.IO) {
        val ts = System.currentTimeMillis()
        val timeFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(ts))
        val bos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        backupGeneratedFileToDrive(
            context = context,
            fileName = "screenshot_$timeFmt.jpg",
            mimeType = "image/jpeg",
            contentBytes = bos.toByteArray(),
            summaryDescription = "[SCREENSHOT] $captionOrContext",
            targetSubFolder = SUBFOLDER_MEDIA
        )
    }

    private suspend fun pushPendingFiles(ctx: Context): Int {
        var failures = 0
        val subDirs = pendingDir(ctx).listFiles()?.filter { it.isDirectory }.orEmpty()
        for (dir in subDirs) {
            val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
            for (f in files) {
                val id = uploadFile(ctx, dir.name, f, f.name, guessMimeType(f.name), "[FILE] ${f.name} (auto-retry)")
                if (!id.isNullOrBlank()) {
                    runCatching { f.delete() }
                    bumpCount(ctx, KEY_FILE_COUNT)
                    if (f.name.endsWith(".zip", ignoreCase = true)) bumpCount(ctx, KEY_ZIP_COUNT)
                } else {
                    failures++
                }
            }
        }
        return failures
    }

    /** Files the user saved by hand into `Download/archer` are uploaded once each (fingerprint = name+size+time). */
    private suspend fun pushArcherFolderArtifacts(ctx: Context) {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "archer")
        val files = runCatching {
            dir.listFiles()?.filter {
                it.isFile && it.length() in 1L..(20L * 1024L * 1024L) &&
                    !it.name.startsWith("memory_") && !it.name.startsWith("conversation_")
            }
        }.getOrNull().orEmpty()
        if (files.isEmpty()) return
        val done = readStringSet(ctx, KEY_UPLOADED_FINGERPRINTS)
        var uploaded = 0
        for (f in files.take(20)) {
            val fp = "${f.name}|${f.length()}|${f.lastModified()}"
            if (fp in done) continue
            val sub = if (f.name.endsWith(".zip", ignoreCase = true)) SUBFOLDER_ZIPS else SUBFOLDER_CODE
            val id = uploadFile(ctx, sub, f, f.name, guessMimeType(f.name), "[ARGON 4 ARTIFACT] ${f.name}")
            if (!id.isNullOrBlank()) {
                updateStringSet(ctx, KEY_UPLOADED_FINGERPRINTS) { it.add(fp) }
                uploaded++
            }
        }
        if (uploaded > 0) bumpCount(ctx, KEY_FILE_COUNT, uploaded)
    }

    // ---------------------------------------------------------------------------------------------
    //  DELETION  (the AI can forget things; Drive Trash keeps them 30 days as a safety net)
    // ---------------------------------------------------------------------------------------------

    private suspend fun trashByName(ctx: Context, sub: String, name: String): Boolean {
        val key = "$sub/$name"
        val cachedId = fileIdCache[key].orEmpty()
        if (cachedId.isNotBlank()) {
            val ok = trashFile(ctx, cachedId)
            if (ok) {
                fileIdCache.remove(key)
                persistMap(ctx, KEY_FILE_IDS_JSON, fileIdCache)
            }
            return ok
        }
        val folderId = ensureSubFolderId(ctx, sub) ?: return false
        val found = listFiles(
            ctx,
            "'$folderId' in parents and name = '${qEsc(name)}' and trashed = false",
            pageSize = 10,
            maxPages = 1
        ) ?: return false
        var all = true
        for (f in found) {
            if (!trashFile(ctx, f.optString("id"))) all = false
        }
        return all
    }

    private suspend fun processTombstones(ctx: Context) {
        for (entry in readStringSet(ctx, KEY_TOMBSTONES)) {
            val idx = entry.indexOf('|')
            if (idx <= 0) {
                updateStringSet(ctx, KEY_TOMBSTONES) { it.remove(entry) }
                continue
            }
            val sub = entry.substring(0, idx)
            val name = entry.substring(idx + 1)
            if (trashByName(ctx, sub, name)) {
                updateStringSet(ctx, KEY_TOMBSTONES) { it.remove(entry) }
            }
        }
    }

    private fun markMemoryDeletedInCloud(ctx: Context, title: String, content: String) {
        val key = memoryKey(title.ifBlank { "কিউব নিউরাল মেমোরি" }, content)
        addTombstone(ctx, SUBFOLDER_MEMORIES, memoryFileName(key))
    }

    /** Called by the repository when ONE memory is deleted (UI or AI). */
    fun onMemoryNoteDeleted(title: String, content: String) {
        val ctx = appContextRef ?: return
        markMemoryDeletedInCloud(ctx, title, content)
        refreshCountsIntoState(ctx)
        requestBackgroundSync(ctx, 2_000L)
    }

    /** Called by the repository when ALL memories are wiped. */
    fun onAllMemoriesCleared() {
        val ctx = appContextRef ?: return
        scope.launch { wipeCloudFolder(ctx, SUBFOLDER_MEMORIES) }
    }

    private suspend fun wipeCloudFolder(ctx: Context, sub: String) {
        if (sub == SUBFOLDER_MEMORIES) {
            updateStringSet(ctx, KEY_UPLOADED_MEM_KEYS) { it.clear() }
        }
        if (isDriveConnected(ctx)) {
            val folderId = ensureSubFolderId(ctx, sub)
            if (!folderId.isNullOrBlank()) {
                val files = listFiles(ctx, "'$folderId' in parents and trashed = false") ?: emptyList()
                for (f in files) {
                    val name = f.optString("name")
                    if (name.isNotBlank()) addTombstone(ctx, sub, name)
                }
            }
        }
        refreshCountsIntoState(ctx)
        requestBackgroundSync(ctx, 1_500L)
    }

    fun onConversationTurnDeleted(timestamp: Long, userPrompt: String) {
        val ctx = appContextRef ?: return
        scope.launch {
            val day = dayOf(timestamp)
            val changed = synchronized(convLock) {
                val f = convFile(ctx, day)
                if (!f.exists()) {
                    false
                } else {
                    val key = convKey(timestamp, userPrompt)
                    val all = f.readLines(Charsets.UTF_8)
                    val kept = all.filter { line ->
                        val e = runCatching { parseConvEntry(JSONObject(line)) }.getOrNull()
                        e == null || convKey(e.ts, e.user) != key
                    }
                    if (kept.size != all.size) {
                        if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
                        true
                    } else {
                        false
                    }
                }
            }
            if (changed) {
                if (!convFile(ctx, day).exists()) {
                    addTombstone(ctx, SUBFOLDER_CONVERSATIONS, "conversation_$day.jsonl")
                    updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.remove(day) }
                } else {
                    updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.add(day) }
                }
                requestBackgroundSync(ctx, 2_000L)
            }
        }
    }

    fun onAllConversationsCleared() {
        val ctx = appContextRef ?: return
        scope.launch {
            removeConversations(ctx, "", true)
            requestBackgroundSync(ctx, 1_500L)
        }
    }

    /** Removes archived conversation lines (all, or those containing [query]). Returns removed count. */
    private suspend fun removeConversations(ctx: Context, query: String, all: Boolean): Int {
        var removed = 0
        val files = convDir(ctx).listFiles()?.toList().orEmpty()
        for (f in files) {
            val day = f.name.removePrefix("conversation_").removeSuffix(".jsonl")
            val result: Pair<Int, Boolean> = synchronized(convLock) {
                val lines = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                val kept = if (all) emptyList() else lines.filter { !it.contains(query, ignoreCase = true) }
                val gone = lines.size - kept.size
                if (gone > 0) {
                    if (kept.isEmpty()) f.delete() else f.writeText(kept.joinToString("\n") + "\n", Charsets.UTF_8)
                }
                Pair(gone, kept.isEmpty())
            }
            if (result.first > 0) {
                removed += result.first
                if (result.second) {
                    addTombstone(ctx, SUBFOLDER_CONVERSATIONS, f.name)
                    updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.remove(day) }
                } else {
                    updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.add(day) }
                }
            }
        }
        val db = AgentDatabase.getInstance(ctx)
        if (all) {
            runCatching { db.commandHistoryDao().clearAll() }
            wipeCloudFolder(ctx, SUBFOLDER_CONVERSATIONS)
        } else {
            runCatching {
                db.commandHistoryDao().searchHistory(query, 500).forEach { db.commandHistoryDao().deleteById(it.id) }
            }
        }
        return removed
    }

    /**
     * AI tool `forget_memory`: removes memories and/or archived conversations from the phone AND from
     * the Google Drive brain. Wiping everything needs `confirm = "YES"` (the AI must ask the boss first).
     */
    suspend fun forgetFromBrain(
        context: Context,
        query: String,
        scopeName: String = "MEMORY",
        confirm: String = ""
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        val q = query.trim()
        val scopeU = scopeName.trim().uppercase(Locale.ROOT).ifBlank { "MEMORY" }
        val wantsAll = q.isBlank() || q.equals("ALL", true) || q.equals("EVERYTHING", true) || q == "সব"
        if (wantsAll && !confirm.trim().equals("YES", true)) {
            return@withContext "কিছু মোছা হয়নি। সব মুছে ফেলার আগে বসকে জিজ্ঞেস করুন ('সব মেমোরি মুছে দেবো?'), তিনি হ্যাঁ বললে confirm=\"YES\" দিয়ে আবার কল করুন।"
        }
        val db = AgentDatabase.getInstance(appCtx)
        var removedMem = 0
        var removedConv = 0
        val titles = ArrayList<String>()
        if (scopeU == "MEMORY" || scopeU == "ALL") {
            val dao = db.agentMemoryNoteDao()
            val targets = if (wantsAll) dao.getRecentNotesSnapshot(1_000_000) else dao.searchNotes(q, 500)
            for (n in targets) {
                dao.deleteNoteById(n.id)
                markMemoryDeletedInCloud(appCtx, n.title, n.content)
                removedMem++
                if (titles.size < 5) titles.add(n.title)
            }
            if (wantsAll) wipeCloudFolder(appCtx, SUBFOLDER_MEMORIES)
        }
        if (scopeU == "CONVERSATION" || scopeU == "ALL") {
            removedConv = removeConversations(appCtx, q, wantsAll)
        }
        refreshCountsIntoState(appCtx)
        requestBackgroundSync(appCtx, 1_500L)
        if (removedMem == 0 && removedConv == 0) {
            "'$q' সম্পর্কিত কোনো মেমোরি বা কথোপকথন পাওয়া যায়নি, তাই কিছু মোছা হয়নি।"
        } else {
            buildString {
                append("মোছা হয়েছে: মেমোরি ${removedMem}টি, কথোপকথন ${removedConv}টি (ফোন + Google Drive ব্রেইন থেকে; Drive Trash-এ ৩০ দিন থাকবে)।")
                if (titles.isNotEmpty()) append(" যেমন: ").append(titles.joinToString(", "))
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  PULL  (restore the whole brain from Drive — incremental, cheap when nothing changed)
    // ---------------------------------------------------------------------------------------------

    private class PullResult(val ok: Boolean, val notes: Int, val turns: Int)

    private suspend fun pullBrain(ctx: Context): PullResult {
        val p = prefs(ctx)
        val lastPull = p.getString(KEY_LAST_PULL_ISO, "").orEmpty()
        var maxModified = lastPull
        var ok = true
        var notesImported = 0
        var turnsImported = 0
        val tombs = readStringSet(ctx, KEY_TOMBSTONES)
        val db = AgentDatabase.getInstance(ctx)
        val sinceClause = if (lastPull.isNotBlank()) " and modifiedTime > '$lastPull'" else ""

        // ---- memories ----
        val memFolder = ensureSubFolderId(ctx, SUBFOLDER_MEMORIES)
        if (memFolder == null) {
            ok = false
        } else {
            val files = listFiles(ctx, "'$memFolder' in parents and trashed = false$sinceClause", orderBy = "modifiedTime")
            if (files == null) {
                ok = false
            } else {
                val noteDao = db.agentMemoryNoteDao()
                val existingKeys = HashSet<String>()
                noteDao.getRecentNotesSnapshot(1_000_000).forEach { existingKeys.add(memoryKey(it.title, it.content)) }
                for (f in files) {
                    val id = f.optString("id")
                    val name = f.optString("name")
                    val mod = f.optString("modifiedTime")
                    if (mod > maxModified) maxModified = mod
                    if (id.isBlank() || !name.endsWith(".json")) continue
                    fileIdCache["$SUBFOLDER_MEMORIES/$name"] = id
                    if (selfModified[id] == mod) continue
                    if ("$SUBFOLDER_MEMORIES|$name" in tombs) continue
                    val raw = downloadText(ctx, id)
                    if (raw == null) {
                        ok = false
                        continue
                    }
                    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                    val content = obj.optString("content").ifBlank { obj.optString("fact") }.trim()
                    if (content.isBlank()) continue
                    val title = obj.optString("title").trim().ifBlank { "কিউব নিউরাল মেমোরি" }
                    val category = obj.optString("category").ifBlank { obj.optString("tags") }.ifBlank { "CLOUD_MEMORY" }
                    val ts = obj.optLong("timestamp", System.currentTimeMillis())
                    val key = memoryKey(title, content)
                    if ("$SUBFOLDER_MEMORIES|${memoryFileName(key)}" in tombs) continue
                    if (key !in existingKeys && !SmartMemoryAndSelfUpdateEngine.isJunkMemoryNote(title, content)) {
                        noteDao.insertNote(
                            AgentMemoryNoteEntity(title = title, content = content, category = category, timestamp = ts)
                        )
                        existingKeys.add(key)
                        notesImported++
                    }
                    if (name == memoryFileName(key)) {
                        updateStringSet(ctx, KEY_UPLOADED_MEM_KEYS) { it.add(key) }
                    } else if (uploadMemoryNote(ctx, key, title, content, category, ts)) {
                        // legacy `memory_<title>.json` migrated to the collision-free format -> retire the old one
                        addTombstone(ctx, SUBFOLDER_MEMORIES, name)
                    }
                }
            }
        }

        // ---- conversations ----
        val convFolder = ensureSubFolderId(ctx, SUBFOLDER_CONVERSATIONS)
        if (convFolder == null) {
            ok = false
        } else {
            val files = listFiles(ctx, "'$convFolder' in parents and trashed = false$sinceClause", orderBy = "modifiedTime")
            if (files == null) {
                ok = false
            } else {
                val addedAll = ArrayList<ConvEntry>()
                for (f in files) {
                    val id = f.optString("id")
                    val name = f.optString("name")
                    val mod = f.optString("modifiedTime")
                    if (mod > maxModified) maxModified = mod
                    if (id.isBlank() || !name.startsWith("conversation_")) continue
                    fileIdCache["$SUBFOLDER_CONVERSATIONS/$name"] = id
                    if (selfModified[id] == mod) continue
                    if ("$SUBFOLDER_CONVERSATIONS|$name" in tombs) continue
                    val raw = downloadText(ctx, id)
                    if (raw == null) {
                        ok = false
                        continue
                    }
                    val entries = ArrayList<ConvEntry>()
                    if (name.endsWith(".jsonl")) {
                        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                            runCatching { parseConvEntry(JSONObject(line)) }.getOrNull()?.let { entries.add(it) }
                        }
                    } else {
                        runCatching { parseConvEntry(JSONObject(raw)) }.getOrNull()?.let { entries.add(it) }
                    }
                    for ((day, group) in entries.groupBy { dayOf(it.ts) }) {
                        val (added, hadLocalOnly) = mergeConversationDayLocal(ctx, day, group)
                        addedAll.addAll(added)
                        if (hadLocalOnly || !name.endsWith(".jsonl")) {
                            // the phone has lines Drive lacks (or Drive only had the old single-turn format)
                            updateStringSet(ctx, KEY_DIRTY_CONV_DAYS) { it.add(day) }
                        }
                    }
                }
                if (addedAll.isNotEmpty()) {
                    turnsImported = addedAll.size
                    val historyDao = db.commandHistoryDao()
                    val have = HashSet<String>()
                    historyDao.getRecentHistorySnapshot(100_000).forEach { have.add(convKey(it.timestamp, it.userPrompt)) }
                    addedAll.sortedByDescending { it.ts }.take(60).forEach { e ->
                        if (convKey(e.ts, e.user) !in have) {
                            historyDao.insertHistory(
                                CommandHistoryEntity(
                                    userPrompt = e.user,
                                    agentReply = e.reply,
                                    actionsSummary = e.actions,
                                    primaryActionType = "CLOUD_RESTORE",
                                    timestamp = e.ts
                                )
                            )
                        }
                    }
                }
            }
        }

        // ---- voice prints ----
        val vpFolder = ensureSubFolderId(ctx, SUBFOLDER_VOICE_PROFILES)
        if (vpFolder != null) {
            val vp = listFiles(
                ctx,
                "'$vpFolder' in parents and name = '$VOICE_PROFILES_FILE' and trashed = false$sinceClause",
                pageSize = 5,
                maxPages = 1
            )
            val id = vp?.firstOrNull()?.optString("id").orEmpty()
            if (id.isNotBlank() && selfModified[id] != vp?.firstOrNull()?.optString("modifiedTime")) {
                val raw = downloadText(ctx, id)
                if (!raw.isNullOrBlank()) {
                    runCatching { VoiceBiometricsEngine.restoreProfilesFromCloudJson(ctx, raw) }
                }
            }
        }

        // ---- tasks & platform accounts (only the first time on this install / after re-connect) ----
        if (lastPull.isBlank()) {
            restoreTasksAndAccountsFromMaster(ctx)
        }

        if (ok && maxModified.isNotBlank()) {
            p.edit().putString(KEY_LAST_PULL_ISO, maxModified).apply()
        }
        return PullResult(ok, notesImported, turnsImported)
    }

    private suspend fun restoreTasksAndAccountsFromMaster(ctx: Context) {
        val folder = ensureSubFolderId(ctx, SUBFOLDER_BACKUPS) ?: return
        val found = listFiles(
            ctx,
            "'$folder' in parents and name = '$MASTER_BACKUP_FILE' and trashed = false",
            pageSize = 5,
            maxPages = 1
        )
        val id = found?.firstOrNull()?.optString("id").orEmpty()
        if (id.isBlank()) return
        val raw = downloadText(ctx, id) ?: return
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val db = AgentDatabase.getInstance(ctx)

        val tasksArr = root.optJSONArray("todayTasks")
        if (tasksArr != null) {
            val have = HashSet<String>()
            runCatching { db.todayTaskDao().getAllTasks().first() }.getOrDefault(emptyList())
                .forEach { have.add(it.title.trim().lowercase(Locale.ROOT)) }
            for (i in 0 until tasksArr.length()) {
                val t = tasksArr.optJSONObject(i) ?: continue
                val title = t.optString("title").trim()
                if (title.isBlank() || title.lowercase(Locale.ROOT) in have) continue
                have.add(title.lowercase(Locale.ROOT))
                db.todayTaskDao().insertTask(
                    TodayTaskEntity(
                        title = title,
                        autoCommandPrompt = t.optString("prompt").ifBlank { title },
                        platformTag = t.optString("platform").ifBlank { "SYSTEM" },
                        isCompleted = t.optBoolean("completed", false)
                    )
                )
            }
        }
        val accArr = root.optJSONArray("platformAccounts")
        if (accArr != null) {
            val have = HashSet<String>()
            runCatching { db.platformAccountDao().getAllAccounts().first() }.getOrDefault(emptyList())
                .forEach { have.add("${it.platformName}|${it.handleOrNumberOrUrl}".lowercase(Locale.ROOT)) }
            for (i in 0 until accArr.length()) {
                val a = accArr.optJSONObject(i) ?: continue
                val platform = a.optString("platform").trim()
                val handle = a.optString("handle").trim()
                if (platform.isBlank() || handle.isBlank()) continue
                val k = "$platform|$handle".lowercase(Locale.ROOT)
                if (k in have) continue
                have.add(k)
                db.platformAccountDao().insertAccount(
                    PlatformAccountEntity(
                        platformName = platform,
                        contactOrGroupName = a.optString("name"),
                        handleOrNumberOrUrl = handle,
                        notes = a.optString("notes")
                    )
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  PUSH + MASTER BACKUP
    // ---------------------------------------------------------------------------------------------

    private suspend fun uploadMasterBackup(ctx: Context, force: Boolean): Boolean {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong(KEY_LAST_MASTER_BACKUP_MS, 0L) < 90_000L) return true
        val db = AgentDatabase.getInstance(ctx)
        val notes = runCatching { db.agentMemoryNoteDao().getRecentNotesSnapshot(1_000_000) }.getOrDefault(emptyList())
        val history = runCatching { db.commandHistoryDao().getRecentHistorySnapshot(200) }.getOrDefault(emptyList())
        val tasks = runCatching { db.todayTaskDao().getAllTasks().first() }.getOrDefault(emptyList())
        val accounts = runCatching { db.platformAccountDao().getAllAccounts().first() }.getOrDefault(emptyList())
        val master = JSONObject().apply {
            put("vaultName", AI_OS_MEMORY_FOLDER_NAME)
            put("subFolders", JSONArray(ALL_REQUIRED_SUBFOLDERS))
            put("linkedEmail", _state.value.linkedEmail)
            put("exportedAtMs", now)
            put("exportedAtFormatted", SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault()).format(Date(now)))
            put("memories", JSONArray().apply {
                notes.forEach { n ->
                    put(JSONObject().apply {
                        put("id", n.id); put("title", n.title); put("content", n.content)
                        put("category", n.category); put("timestamp", n.timestamp)
                    })
                }
            })
            put("conversations", JSONArray().apply {
                history.forEach { h -> put(entryJson(ConvEntry(h.timestamp, h.userPrompt, h.agentReply, h.actionsSummary))) }
            })
            put("todayTasks", JSONArray().apply {
                tasks.forEach { t ->
                    put(JSONObject().apply {
                        put("title", t.title); put("prompt", t.autoCommandPrompt)
                        put("platform", t.platformTag); put("completed", t.isCompleted)
                    })
                }
            })
            put("platformAccounts", JSONArray().apply {
                accounts.forEach { a ->
                    put(JSONObject().apply {
                        put("platform", a.platformName); put("name", a.contactOrGroupName)
                        put("handle", a.handleOrNumberOrUrl); put("notes", a.notes)
                    })
                }
            })
        }.toString(2)
        val id = uploadBytes(
            ctx, SUBFOLDER_BACKUPS, MASTER_BACKUP_FILE, "application/json", master.toByteArray(Charsets.UTF_8),
            "[MASTER VAULT] ${notes.size} memories, ${history.size} chats, ${tasks.size} tasks, ${accounts.size} accounts"
        )
        if (id.isNullOrBlank()) return false
        p.edit().putLong(KEY_LAST_MASTER_BACKUP_MS, now).apply()
        return true
    }

    /** Uploads everything that is not on Drive yet. Returns the number of failed items. */
    private suspend fun pushBrain(ctx: Context): Int {
        var failures = 0
        backfillConversationArchiveFromRoom(ctx)

        // memories
        val notes = runCatching {
            AgentDatabase.getInstance(ctx).agentMemoryNoteDao().getRecentNotesSnapshot(1_000_000)
        }.getOrDefault(emptyList())
        val uploaded = readStringSet(ctx, KEY_UPLOADED_MEM_KEYS)
        val tombs = readStringSet(ctx, KEY_TOMBSTONES)
        var consecutive = 0
        for (n in notes) {
            val title = n.title.ifBlank { "কিউব নিউরাল মেমোরি" }
            val key = memoryKey(title, n.content)
            if (key in uploaded) continue
            if ("$SUBFOLDER_MEMORIES|${memoryFileName(key)}" in tombs) continue
            if (uploadMemoryNote(ctx, key, title, n.content.trim(), n.category, n.timestamp)) {
                consecutive = 0
            } else {
                failures++
                consecutive++
                if (consecutive >= 3) break
            }
        }

        // conversations
        for (day in readStringSet(ctx, KEY_DIRTY_CONV_DAYS).sorted()) {
            if (!uploadConversationDay(ctx, day)) failures++
        }

        // generated files that were waiting
        failures += pushPendingFiles(ctx)
        pushArcherFolderArtifacts(ctx)

        // master backup (tasks, accounts + a full safety copy)
        if (!uploadMasterBackup(ctx, force = false)) failures++
        return failures
    }

    // ---------------------------------------------------------------------------------------------
    //  PUBLIC SYNC API
    // ---------------------------------------------------------------------------------------------

    fun triggerFullSyncFromUi(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            syncAllAppDataToDriveNow(context = appCtx, speakResult = true, onStatusSpoken = onStatusSpoken)
        }
    }

    /**
     * Full two-way brain sync: folders -> pending deletions -> restore what is missing on this phone ->
     * upload what is missing on Drive -> master backup. Safe to call any time (a second call while one
     * is running returns immediately).
     */
    suspend fun syncAllAppDataToDriveNow(
        context: Context,
        speakResult: Boolean = false,
        onStatusSpoken: (String) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)

        suspend fun finish(msg: String): String {
            if (speakResult) withContext(Dispatchers.Main) { onStatusSpoken(msg) }
            return msg
        }

        if (!isDriveConnected(appCtx)) {
            return@withContext finish(
                "স্যার, Google Drive এখনো কানেক্ট করা নেই। ☁️ Connect চাপলেই সব ডাটা নিজে নিজে ড্রাইভে সেভ হবে — ততক্ষণ আপনার ডাটা ফোনে সুরক্ষিত আছে।"
            )
        }
        if (!syncMutex.tryLock()) {
            return@withContext "সিঙ্ক ইতিমধ্যে চলছে, একটু অপেক্ষা করুন।"
        }
        try {
            _state.update { it.copy(isSyncing = true) }
            if (getAccessToken(appCtx) == null) {
                _state.update { it.copy(isSyncing = false) }
                return@withContext finish(
                    "স্যার, Google Drive-এর অনুমতি নবায়ন করতে হবে। ☁️ Connect চাপুন — আপনার ডাটা ফোনে সুরক্ষিত আছে, কানেক্ট হলে নিজে থেকে আপলোড হবে।"
                )
            }
            if (!provisionFolders(appCtx)) {
                _state.update {
                    it.copy(isSyncing = false, lastSyncStatusBn = "❌ Drive ফোল্ডার তৈরি/খোঁজা যায়নি — ℹ️ বাটনে কারণ দেখুন")
                }
                return@withContext finish("স্যার, ড্রাইভে AI_OS_Memory ফোল্ডার পাওয়া/তৈরি করা যায়নি। কারণটা ℹ️ বাটনে লেখা আছে।")
            }
            processTombstones(appCtx)
            val pull = pullBrain(appCtx)
            val failures = pushBrain(appCtx)
            refreshBrainSummary(appCtx)
            val freed = if (_state.value.zeroPhoneStorageImpactMode) purgeLocalTempAndCacheForZeroStorage(appCtx) else 0L
            refreshCountsIntoState(appCtx)
            val st = _state.value
            val restoredNote = if (pull.notes > 0 || pull.turns > 0) {
                " • ♻️ ড্রাইভ থেকে ফিরেছে: মেমোরি ${pull.notes}, চ্যাট ${pull.turns}"
            } else {
                ""
            }
            val pendingNote = if (st.pendingUploadsCount > 0 || failures > 0) {
                " • ⏳ ${st.pendingUploadsCount}টি অপেক্ষমাণ (নিজে থেকে যাবে)"
            } else {
                ""
            }
            val status = "✅ ব্রেইন সিঙ্ক সম্পন্ন (${st.linkedEmail}${if (st.driveQuotaSummary.isNotBlank()) " • ${st.driveQuotaSummary}" else ""}): " +
                "মেমোরি ${st.backedUpMemoriesCount} • চ্যাট-দিন ${st.backedUpConversationsCount} • ফাইল ${st.backedUpScreenshotsAndFilesCount}" +
                restoredNote + pendingNote
            _state.update {
                it.copy(
                    isSyncing = false,
                    isConnected = true,
                    needsReconnect = false,
                    lastErrorBn = if (failures > 0) it.lastErrorBn else "",
                    freedLocalCacheBytes = it.freedLocalCacheBytes + freed,
                    lastSyncStatusBn = status
                )
            }
            val spoken = "জি বস, আপনার গুগল ড্রাইভের AI_OS_Memory ব্রেইন আপ-টু-ডেট: ${st.backedUpMemoriesCount}টি মেমোরি ও চ্যাট ইতিহাস নিরাপদে সেভ আছে" +
                (if (pull.notes > 0) ", ড্রাইভ থেকে ${pull.notes}টি পুরনো মেমোরি ফিরিয়ে এনেছি" else "") +
                (if (st.pendingUploadsCount > 0) ", ${st.pendingUploadsCount}টি আইটেম নেট ফিরলে নিজে থেকে আপলোড হবে" else "") + "।"
            finish(spoken)
        } catch (e: Exception) {
            _state.update {
                it.copy(isSyncing = false, lastErrorBn = "সিঙ্ক ত্রুটি: ${e.message.orEmpty().take(120)}")
            }
            "সিঙ্কে সমস্যা হয়েছে: ${e.message.orEmpty().take(100)} (ডাটা ফোনে সুরক্ষিত, পরে আবার চেষ্টা হবে)"
        } finally {
            syncMutex.unlock()
        }
    }

    /** Compatibility wrapper: restore only (the normal sync already does this). */
    suspend fun pullAndRestoreBrainFromDriveToLocal(context: Context): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        if (isDriveConnected(appCtx) && syncMutex.tryLock()) {
            try {
                if (getAccessToken(appCtx) != null && provisionFolders(appCtx)) {
                    pullBrain(appCtx)
                    refreshBrainSummary(appCtx)
                }
            } finally {
                syncMutex.unlock()
            }
        }
        _state.value.cloudBrainContextSummary
    }

    private suspend fun refreshBrainSummary(ctx: Context) {
        val db = AgentDatabase.getInstance(ctx)
        val notes = runCatching { db.agentMemoryNoteDao().getRecentNotesSnapshot(25) }.getOrDefault(emptyList())
        val history = runCatching { db.commandHistoryDao().getRecentHistorySnapshot(12) }.getOrDefault(emptyList())
        val block = buildString {
            if (notes.isNotEmpty()) {
                appendLine("CLOUD DRIVE PERMANENT MEMORIES (${notes.size} newest):")
                notes.forEach { appendLine("- [${it.title}] ${it.content.take(220)} (${it.category})") }
            }
            if (history.isNotEmpty()) {
                appendLine("CLOUD DRIVE PAST CONVERSATION HISTORY (${history.size} newest):")
                history.forEach { appendLine("- User: ${it.userPrompt.take(110)} -> AI: ${it.agentReply.take(140)}") }
            }
        }.trim()
        prefs(ctx).edit().putString(KEY_CLOUD_BRAIN_SUMMARY, block).apply()
        _state.update { it.copy(cloudBrainContextSummary = block) }
    }

    fun buildLiveCloudBrainContextForPrompt(): String {
        val st = _state.value
        return buildString {
            append("\n\nGOOGLE DRIVE CLOUD BRAIN & LIVE MEMORY VAULT (`/$AI_OS_MEMORY_FOLDER_NAME`):\n")
            append(
                "- DRIVE CONNECTION STATUS: " + when {
                    st.isConnected && !st.needsReconnect -> "CONNECTED (${st.linkedEmail})"
                    st.isConnected -> "NEEDS RE-CONNECT (data is safe on the phone)"
                    else -> "NOT CONNECTED (data is kept on the phone and uploads automatically after connecting)"
                } + "\n"
            )
            if (st.cloudBrainContextSummary.isNotBlank()) append(st.cloudBrainContextSummary).append("\n")
            if (st.recentCloudFiles.isNotEmpty()) {
                append("- RECENT FILES IN GOOGLE DRIVE: ")
                append(st.recentCloudFiles.take(10).joinToString("; ") { "${it.subFolderName}/${it.name}: ${it.snippetText.take(80)}" })
                append("\n")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  SEARCH
    // ---------------------------------------------------------------------------------------------

    suspend fun searchGoogleDriveMemory(
        context: Context,
        query: String,
        limit: Int = 10
    ): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        val q = query.trim()
        val lines = ArrayList<String>()
        searchConversationArchive(appCtx, q, limit).forEach { lines.add("CHAT ARCHIVE: $it") }

        if (isDriveConnected(appCtx)) {
            val all = q.isBlank() || q.equals("ALL", true) || q.equals("RECENT", true)
            val clause = if (all) {
                "trashed = false and mimeType != 'application/vnd.google-apps.folder'"
            } else {
                "trashed = false and mimeType != 'application/vnd.google-apps.folder' and (name contains '${qEsc(q)}' or fullText contains '${qEsc(q)}')"
            }
            val found = listFiles(appCtx, clause, pageSize = limit, maxPages = 1)
            val sorted = found?.sortedByDescending { it.optString("createdTime") }.orEmpty()
            val items = ArrayList<DriveCloudMemoryItem>()
            for ((i, f) in sorted.withIndex()) {
                val name = f.optString("name")
                var snippet = f.optString("description")
                if (snippet.isBlank() && i < 3) {
                    snippet = downloadText(appCtx, f.optString("id"))?.take(200).orEmpty()
                }
                lines.add("DRIVE: [$name] ${snippet.ifBlank { name }.replace('\n', ' ').take(220)}")
                items.add(
                    DriveCloudMemoryItem(
                        fileId = f.optString("id"),
                        name = name,
                        mimeType = f.optString("mimeType"),
                        description = f.optString("description"),
                        createdTime = f.optString("createdTime"),
                        snippetText = snippet.ifBlank { name }.take(120),
                        webViewLink = f.optString("webViewLink")
                    )
                )
            }
            if (items.isNotEmpty()) _state.update { it.copy(recentCloudFiles = items.take(15)) }
        }

        if (lines.isEmpty()) {
            "GOOGLE_DRIVE_BRAIN (/$AI_OS_MEMORY_FOLDER_NAME): '$q' সম্পর্কিত কোনো ক্লাউড ফাইল বা চ্যাট পাওয়া যায়নি।"
        } else {
            "GOOGLE DRIVE BRAIN (/$AI_OS_MEMORY_FOLDER_NAME) MATCHES (${lines.size}):\n" +
                lines.take(limit * 2).mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  MISC UI-FACING HELPERS
    // ---------------------------------------------------------------------------------------------

    fun setZeroPhoneStorageImpactMode(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        prefs(appCtx).edit().putBoolean(KEY_ZERO_STORAGE_MODE, enabled).apply()
        _state.update { it.copy(zeroPhoneStorageImpactMode = enabled) }
        if (enabled) {
            scope.launch {
                val freed = purgeLocalTempAndCacheForZeroStorage(appCtx)
                _state.update { it.copy(freedLocalCacheBytes = it.freedLocalCacheBytes + freed) }
            }
        }
    }

    /** Deletes only OLD temp files (>15 min) at the top level of the cache dir — never files other features still use. */
    fun purgeLocalTempAndCacheForZeroStorage(context: Context): Long {
        var freed = 0L
        runCatching {
            val limit = System.currentTimeMillis() - 15L * 60L * 1000L
            context.cacheDir?.listFiles()?.forEach { file ->
                if (file.isFile && file.lastModified() < limit) {
                    val len = file.length()
                    if (runCatching { file.delete() }.getOrDefault(false)) freed += len
                }
            }
        }
        return freed
    }

    fun openOrShareToGoogleDriveApp(
        context: Context,
        onStatusSpoken: (String) -> Unit = {}
    ) {
        val appCtx = context.applicationContext
        scope.launch {
            if (!isDriveConnected(appCtx)) {
                withContext(Dispatchers.Main) {
                    onStatusSpoken("স্যার, আগে Google Drive কানেক্ট করুন, তারপর ফোল্ডার খুলে দেব।")
                }
                return@launch
            }
            syncAllAppDataToDriveNow(appCtx, speakResult = false)
            val folderId = prefs(appCtx).getString(KEY_FOLDER_ID, "").orEmpty()
            withContext(Dispatchers.Main) {
                val targetUrl = if (folderId.isNotBlank()) {
                    "https://drive.google.com/drive/folders/$folderId"
                } else {
                    "https://drive.google.com/drive/my-drive"
                }
                try {
                    val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(viewIntent)
                    onStatusSpoken("জি স্যার, আপনার গুগল ড্রাইভের AI_OS_Memory ফোল্ডার ওপেন করা হয়েছে।")
                } catch (_: Exception) {
                    onStatusSpoken("জি স্যার, আপনার সব ডাটা AI_OS_Memory ফোল্ডারে অটো-সিঙ্ক হয়ে আছে।")
                }
            }
        }
    }

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
                "জি স্যার, কোড ফাইল '$fileName' সরাসরি আপনার Google Drive-এর /$AI_OS_MEMORY_FOLDER_NAME/$SUBFOLDER_CODE সাব-ফোল্ডারে সিঙ্ক হয়েছে।"
            } else {
                "কোড ফাইল '$fileName' ফোনে সুরক্ষিত আছে; Drive কানেক্ট/নেট ফিরলে নিজে থেকে আপলোড হবে।"
            }
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(appCtx, msg, android.widget.Toast.LENGTH_SHORT).show()
                onStatusSpoken(msg)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    //  ZIP PACKAGES  (preview first, upload only after the boss confirms)
    // ---------------------------------------------------------------------------------------------

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
     * Uploads the staged `.zip` ONLY AFTER the boss checked the preview and confirmed.
     * A private copy is kept until Drive confirms, so the answer below is always truthful.
     */
    suspend fun confirmSavePendingStagedZipToDrive(context: Context): String = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureInit(appCtx)
        val staged = pendingStagedZip ?: return@withContext "স্যার, সেভ করার মতো কোনো পেন্ডিং ZIP প্যাকেজ পাওয়া যায়নি।"
        val zipFile = File(staged.stagedZipFilePath)
        if (!zipFile.exists()) {
            pendingStagedZip = null
            return@withContext "স্যার, স্টেইজড ZIP ফাইলটি পাওয়া যায়নি।"
        }
        _state.update {
            it.copy(isSyncing = true, lastSyncStatusBn = "☁️ '${staged.zipFileName}' (${staged.actualSizeMb}) Google Drive-এ সেভ হচ্ছে...")
        }
        val targetSub = safeName(staged.targetSubFolder.ifBlank { SUBFOLDER_ZIPS })

        runCatching {
            val pubArcher = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "archer")
            if (pubArcher.exists() || pubArcher.mkdirs()) {
                zipFile.copyTo(File(pubArcher, staged.zipFileName), overwrite = true)
            }
        }
        val pendingCopy = File(File(pendingDir(appCtx), targetSub), staged.zipFileName)
        runCatching {
            pendingCopy.parentFile?.mkdirs()
            zipFile.copyTo(pendingCopy, overwrite = true)
        }

        val uploadedId = if (isDriveConnected(appCtx)) {
            uploadFile(
                appCtx, targetSub, zipFile, staged.zipFileName, "application/zip",
                "[AI ZIP PACKAGE] ${staged.projectOrFolderName} (${staged.actualSizeMb})"
            )
        } else {
            null
        }
        val uploaded = !uploadedId.isNullOrBlank()
        if (uploaded) {
            runCatching { pendingCopy.delete() }
            if (staged.createUnpackedFolderInDriveToo && staged.primaryCodeOrSpec.isNotBlank()) {
                val folder = safeName(staged.zipFileName.removeSuffix(".zip"))
                uploadBytes(
                    appCtx, folder, "index.html", "text/html",
                    staged.primaryCodeOrSpec.toByteArray(Charsets.UTF_8),
                    "Unpacked Main File for ${staged.projectOrFolderName}"
                )
            }
            bumpCount(appCtx, KEY_ZIP_COUNT)
            bumpCount(appCtx, KEY_FILE_COUNT)
        }
        runCatching { if (zipFile.absolutePath.contains("cache")) zipFile.delete() }
        pendingStagedZip = null
        refreshCountsIntoState(appCtx)

        val doneMsg = if (uploaded) {
            "✔ '${staged.zipFileName}' (${staged.actualSizeMb}) সরাসরি Google Drive (/$AI_OS_MEMORY_FOLDER_NAME/$targetSub)-এ ১টি কপি হিসেবে সেভ হয়েছে!"
        } else {
            "📥 '${staged.zipFileName}' (${staged.actualSizeMb}) ফোনে সুরক্ষিত আছে; Drive কানেক্ট/নেট ফিরলে নিজে থেকে আপলোড হবে।"
        }
        _state.update { it.copy(isSyncing = false, lastSyncStatusBn = doneMsg) }
        CodingSessionBus.markSaved(path = "/$AI_OS_MEMORY_FOLDER_NAME/$targetSub/${staged.zipFileName}", statusBn = doneMsg)
        if (uploaded) CodingSessionBus.markSyncedToDrive(doneMsg)

        if (uploaded) {
            "জি স্যার, '${staged.zipFileName}' (${staged.actualSizeMb}) কোনো ডুপ্লিকেট ছাড়া ১টি ফাইল হিসেবে আপনার Google Drive-এর '/$AI_OS_MEMORY_FOLDER_NAME/$targetSub' ফোল্ডারে সেভ করে দিয়েছি।"
        } else {
            "স্যার, '${staged.zipFileName}' ফোনে সেভ করেছি, কিন্তু Google Drive এই মুহূর্তে কানেক্ট নেই বা আপলোড হয়নি। কানেক্ট হলে নিজে থেকেই আপলোড হবে।"
        }
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
}
