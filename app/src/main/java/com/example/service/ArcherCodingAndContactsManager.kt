package com.example.service

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DeviceContactEntry(
    val displayName: String,
    val phoneNumber: String
)

data class ContactOrScreenResolution(
    val queryName: String,
    val matchedContact: DeviceContactEntry?,
    val foundOnVisibleScreen: Boolean,
    val hasContactsPermission: Boolean,
    val phoneticCandidates: List<String>
)

data class SavedArcherArtifact(
    val id: String,
    val projectTitle: String,
    val primaryFileName: String,
    val language: String,
    val codeContent: String,
    val folderPath: String,
    val singleFilePath: String,
    val zipFilePath: String,
    val createdAtLabel: String
)

data class CodingWorkspaceState(
    val activeModelId: String = "models/gemini-2.5-flash",
    val isAutoModelRouting: Boolean = true,
    val currentProjectTitle: String = "Archer AI Coding Studio",
    val currentFileName: String = "index.html",
    val currentLanguage: String = "HTML/JS",
    val currentCode: String = DEFAULT_STARTER_CODE,
    val archerMainFolderPath: String = "/storage/emulated/0/Download/archer",
    val lastSaveSummary: String = "কোডিং বা ফাইল তৈরি শেষ হলে শুধুমাত্র একটি সঠিক ফরম্যাটে (ডুপ্লিকেট ছাড়া) Download/archer/ ফোল্ডারে সেভ হবে।",
    val savedArtifacts: List<SavedArcherArtifact> = emptyList(),
    val hasPendingUnsavedCode: Boolean = false
)

data class PendingCodeStageRequest(
    val projectTitle: String,
    val preferredFileName: String,
    val language: String,
    val cleanCode: String
)

private const val DEFAULT_STARTER_CODE = """<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ARCHER AI — Coding Studio</title>
  <style>
    body { background: #0B0F19; color: #F8FAFC; font-family: system-ui, sans-serif; text-align: center; padding: 32px; margin: 0; }
    .card { border: 1px solid #334155; border-radius: 16px; padding: 24px; max-width: 460px; margin: 0 auto; background: #1E293B; box-shadow: 0 10px 30px rgba(0,0,0,0.4); }
    h1 { color: #38BDF8; font-size: 22px; margin-bottom: 8px; }
    p { color: #94A3B8; font-size: 14px; line-height: 1.5; }
    button { background: #38BDF8; color: #0B0F19; border: none; padding: 12px 24px; border-radius: 10px; font-weight: 800; cursor: pointer; margin-top: 12px; }
  </style>
</head>
<body>
  <div class="card">
    <h1>ARCHER AI • CODING STUDIO</h1>
    <p>লাইভ কোড এডিটর এবং স্যান্ডবক্স ওয়েবভিউ প্রিভিউ প্রস্তুত। ভয়েস দিয়ে যেকোনো পরিবর্তন বলুন।</p>
    <button onclick="this.textContent='✔ প্রিভিউ সচল!'">Test Live Action</button>
  </div>
</body>
</html>"""

class ArcherCodingAndContactsManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _workspaceState = MutableStateFlow(CodingWorkspaceState())
    val workspaceState: StateFlow<CodingWorkspaceState> = _workspaceState.asStateFlow()

    @Volatile
    private var pendingStagedCode: PendingCodeStageRequest? = null

    init {
        ensureArcherDirectoriesAndLoadExisting()
        CodingSessionBus.onConfirmSaveRequested = { extOverride ->
            confirmSavePendingCodeToArcherFolder(extOverride)
        }
        CodingSessionBus.onSyncToDriveRequested = {
            managerScope.launch {
                syncCurrentCodeToGoogleDrive()
            }
        }
        CodingSessionBus.onDiscardRequested = {
            discardPendingCode()
        }
    }

    fun hasPendingCodeConfirmation(): Boolean =
        pendingStagedCode != null ||
            CodingSessionBus.hasPendingUnsavedCode() ||
            GoogleDriveMemorySyncService.hasPendingStagedZip()

    /**
     * Stages generated code or text in memory and opens the Dedicated Coding Studio Page immediately:
     * - Automatically sets `activeTab = "PREVIEW"` whenever the generated code is HTML/CSS/JS or a web app/site
     *   so the live rendered website opens immediately on screen without manual tab switching!
     * - Keeps `pendingSaveConfirmation = true` so the user can inspect the preview first before saving a single
     *   clean copy to Google Drive (`/AI_OS_Memory/Argon4_Code`) and `Download/archer/`.
     */
    fun stageGeneratedCodePendingSave(
        projectTitle: String,
        preferredFileName: String,
        language: String,
        rawCodeOrResponse: String
    ): PendingCodeStageRequest {
        val extractedCode = extractCleanCodeBlock(rawCodeOrResponse)
        val cleanLang = detectSmartLanguage(language, extractedCode, preferredFileName)
        val finalFileName = normalizeSingleExactFileName(
            preferredFileName = preferredFileName,
            projectTitle = projectTitle,
            language = cleanLang,
            code = extractedCode,
            overrideExt = null
        )
        val cleanTitle = projectTitle.ifBlank { finalFileName.substringBeforeLast('.') }
        val isWebPreviewable = cleanLang.contains("HTML", ignoreCase = true) ||
            finalFileName.endsWith(".html", ignoreCase = true) ||
            finalFileName.endsWith(".htm", ignoreCase = true) ||
            extractedCode.contains("<!doctype html", ignoreCase = true) ||
            extractedCode.contains("<html", ignoreCase = true) ||
            (extractedCode.contains("<div", ignoreCase = true) && extractedCode.contains("<button", ignoreCase = true))

        val staged = PendingCodeStageRequest(
            projectTitle = cleanTitle,
            preferredFileName = finalFileName,
            language = cleanLang,
            cleanCode = extractedCode
        )
        pendingStagedCode = staged
        _workspaceState.value = _workspaceState.value.copy(
            currentProjectTitle = cleanTitle,
            currentFileName = finalFileName,
            currentLanguage = cleanLang,
            currentCode = extractedCode,
            hasPendingUnsavedCode = true,
            lastSaveSummary = "👀 '$finalFileName' প্রস্তুত — প্রিভিউ চেক করে বলুন, সব ঠিক থাকলে সাথে সাথে Google Drive ও Download/archer/-এ সেভ হবে।"
        )
        CodingSessionBus.start(
            CodingSession(
                id = "code_${System.currentTimeMillis()}",
                title = cleanTitle,
                fileName = finalFileName,
                language = cleanLang,
                code = extractedCode,
                savedPath = "",
                isSaved = false,
                isSyncedToDrive = false,
                isPatchingCode = false,
                activeTab = if (isWebPreviewable) "PREVIEW" else "EDITOR",
                statusMessageBn = "👀 লাইভ প্রিভিউ প্রস্তুত ($finalFileName)! চেক করে দেখুন সব ঠিক আছে কি না—আপনি বললেই Google Drive-এ সেভ করে দেবো।",
                pendingSaveConfirmation = true,
                isDiscarded = false
            )
        )
        return staged
    }

    /**
     * Saves ONLY ONE single clean file with the exact right extension into `Download/archer/`
     * and uploads 1 single copy to Google Drive (`/AI_OS_Memory/Argon4_Code` or `/ZIP_Packages`)
     * when the user confirms after checking the preview!
     */
    fun confirmSavePendingCodeToArcherFolder(overrideExtension: String? = null): SavedArcherArtifact? {
        if (GoogleDriveMemorySyncService.hasPendingStagedZip()) {
            managerScope.launch {
                GoogleDriveMemorySyncService.confirmSavePendingStagedZipToDrive(appContext)
            }
            _workspaceState.value = _workspaceState.value.copy(hasPendingUnsavedCode = false)
            return null
        }

        val staged = pendingStagedCode ?: CodingSessionBus.session.value?.let { s ->
            PendingCodeStageRequest(
                projectTitle = s.title,
                preferredFileName = s.fileName,
                language = s.language,
                cleanCode = s.code
            )
        } ?: return null

        pendingStagedCode = null
        val targetFileName = if (!overrideExtension.isNullOrBlank()) {
            normalizeSingleExactFileName(
                preferredFileName = staged.preferredFileName,
                projectTitle = staged.projectTitle,
                language = staged.language,
                code = staged.cleanCode,
                overrideExt = overrideExtension
            )
        } else {
            staged.preferredFileName
        }

        val artifact = saveGeneratedCodeToArcherFolder(
            projectTitle = staged.projectTitle,
            preferredFileName = targetFileName,
            language = staged.language,
            rawCodeOrResponse = staged.cleanCode
        )
        _workspaceState.value = _workspaceState.value.copy(hasPendingUnsavedCode = false)
        CodingSessionBus.markSaved(
            path = artifact.singleFilePath,
            statusBn = "✔ '${artifact.primaryFileName}' সিঙ্গেল ফাইল হিসেবে Download/archer/ ও Google Drive-এ সেভ হয়েছে!"
        )
        CodingSessionBus.markSyncedToDrive(
            "✔ '${artifact.primaryFileName}' সরাসরি Google Drive (/AI_OS_Memory/Argon4_Code)-এ ১টি কপি হিসেবে সেভ হয়েছে!"
        )
        return artifact
    }

    /**
     * Syncs the active code file or staged ZIP package in Coding Studio directly to the `AI_OS_Memory` folder in 5TB Google Drive.
     */
    suspend fun syncCurrentCodeToGoogleDrive(): String = withContext(Dispatchers.IO) {
        if (GoogleDriveMemorySyncService.hasPendingStagedZip()) {
            return@withContext GoogleDriveMemorySyncService.confirmSavePendingStagedZipToDrive(appContext)
        }
        val s = CodingSessionBus.session.value
        val fileName = s?.fileName ?: _workspaceState.value.currentFileName
        val code = s?.code ?: _workspaceState.value.currentCode
        val mime = mimeTypeForFileName(fileName)

        val ok = GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
            context = appContext,
            fileName = fileName,
            mimeType = mime,
            contentBytes = code.toByteArray(Charsets.UTF_8),
            summaryDescription = "Coding Studio File: ${s?.title ?: fileName}",
            targetSubFolder = GoogleDriveMemorySyncService.SUBFOLDER_CODE
        )
        val msg = if (ok) {
            "✔ আপনার 5TB Google Drive-এর AI_OS_Memory/Argon4_Code ফোল্ডারে '$fileName' সিঙ্ক হয়েছে!"
        } else {
            "Google Drive-এ সিঙ্ক করতে সমস্যা হয়েছে, আবার চেষ্টা করুন।"
        }
        if (ok) {
            CodingSessionBus.markSyncedToDrive(msg)
        } else {
            CodingSessionBus.updatePatchingState(false, msg)
        }
        msg
    }

    /**
     * Voice-Driven Code Patching: Modifies/fixes the currently open code in Coding Studio based on the user's
     * spoken or typed instruction (e.g., "ডিজাইনটা আরও সুন্দর করো" or "এই বাটনটা কাজ করছে না, ঠিক করো")
     * and automatically refreshes the Live Preview.
     */
    suspend fun patchCurrentCodeWithAi(
        apiKey: String,
        userInstruction: String
    ): String = withContext(Dispatchers.IO) {
        val currentSession = CodingSessionBus.session.value
        val currentCode = currentSession?.code ?: _workspaceState.value.currentCode
        val currentFileName = currentSession?.fileName ?: _workspaceState.value.currentFileName
        val currentLang = currentSession?.language ?: _workspaceState.value.currentLanguage
        val currentTitle = currentSession?.title ?: _workspaceState.value.currentProjectTitle

        if (apiKey.isBlank()) {
            val err = "বস, কোড আপডেট করার জন্য Gemini API Key প্রয়োজন।"
            CodingSessionBus.updatePatchingState(false, err)
            return@withContext err
        }

        CodingSessionBus.updatePatchingState(
            isPatching = true,
            statusBn = "⚡ ভয়েস নির্দেশনা অনুযায়ী '$currentFileName' আপডেট ও ফিক্স করা হচ্ছে..."
        )

        val prompt = buildString {
            appendLine("You are an elite software engineer inside the ARCHER AI Coding Studio.")
            appendLine("The user wants to modify/patch the following existing code file (`$currentFileName`, language: `$currentLang`).")
            appendLine("USER INSTRUCTION: $userInstruction")
            appendLine("EXISTING CODE:")
            appendLine("```")
            appendLine(currentCode)
            appendLine("```")
            appendLine("Return ONLY the complete, updated, bug-free code without any markdown explanation outside the code block.")
        }

        val models = listOf("gemini-2.5-flash", "gemini-2.5-pro")
        for (model in models) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 20000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().put("role", "user").put(
                            "parts", JSONArray().put(JSONObject().put("text", prompt))
                        )
                    ))
                    put("generationConfig", JSONObject().apply {
                        put("temperature", 0.25)
                    })
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
                if (conn.responseCode in 200..299) {
                    val raw = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
                    val root = JSONObject(raw)
                    val text = root.optJSONArray("candidates")
                        ?.optJSONObject(0)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts")
                        ?.optJSONObject(0)
                        ?.optString("text")
                        .orEmpty()
                    val patchedCode = extractCleanCodeBlock(text)
                    if (patchedCode.isNotBlank()) {
                        val shouldSwitchPreview = currentFileName.endsWith(".html", ignoreCase = true) ||
                            patchedCode.contains("<html", ignoreCase = true)
                        saveGeneratedCodeToArcherFolder(
                            projectTitle = currentTitle,
                            preferredFileName = currentFileName,
                            language = currentLang,
                            rawCodeOrResponse = patchedCode
                        )
                        pendingStagedCode = null
                        _workspaceState.value = _workspaceState.value.copy(
                            currentCode = patchedCode,
                            hasPendingUnsavedCode = false,
                            lastSaveSummary = "ভয়েস নির্দেশনা অনুযায়ী '$currentFileName' আপডেট ও Google Drive-এ অটো-সেভ হয়েছে!"
                        )
                        val msg = "জি বস, আপনার নির্দেশ অনুযায়ী '$currentFileName'-এর কোড আপডেট ও ফিক্স করে সরাসরি Google Drive-এ অটো-সেভ করেছি।"
                        CodingSessionBus.updatePatchedCode(
                            newCode = patchedCode,
                            newFileName = currentFileName,
                            newLanguage = currentLang,
                            autoSwitchToPreview = shouldSwitchPreview,
                            statusBn = msg
                        )
                        return@withContext msg
                    }
                }
            } catch (_: Exception) {
            }
        }

        val failMsg = "বস, নেটওয়ার্ক সমস্যার কারণে কোডটি এই মুহূর্তে আপডেট করা যায়নি। আবার বলুন।"
        CodingSessionBus.updatePatchingState(false, failMsg)
        failMsg
    }

    /**
     * Discards the staged code/text without writing anything to any folder.
     */
    fun discardPendingCode() {
        pendingStagedCode = null
        GoogleDriveMemorySyncService.discardPendingStagedZip()
        _workspaceState.value = _workspaceState.value.copy(
            hasPendingUnsavedCode = false,
            lastSaveSummary = "আপনার নির্দেশ অনুযায়ী কোড/ZIP প্যাকেজটি সেভ করা হয়নি।"
        )
        CodingSessionBus.markDiscarded()
    }

    fun setActiveModel(modelId: String, autoRoute: Boolean = _workspaceState.value.isAutoModelRouting) {
        _workspaceState.value = _workspaceState.value.copy(
            activeModelId = modelId,
            isAutoModelRouting = autoRoute
        )
    }

    fun toggleAutoModelRouting(): Boolean {
        val next = !_workspaceState.value.isAutoModelRouting
        _workspaceState.value = _workspaceState.value.copy(isAutoModelRouting = next)
        return next
    }

    fun selectBestModelForPrompt(prompt: String): String {
        val state = _workspaceState.value
        if (!state.isAutoModelRouting) return state.activeModelId

        val lower = prompt.lowercase()
        val needsCodingModel = lower.contains("কোড") ||
            lower.contains("কোডিং") ||
            lower.contains("code") ||
            lower.contains("coding") ||
            lower.contains("html") ||
            lower.contains("python") ||
            lower.contains("javascript") ||
            lower.contains("kotlin") ||
            lower.contains("script") ||
            lower.contains("ওয়েবসাইট বান") ||
            lower.contains("অ্যাপ বান") ||
            lower.contains("প্রোগ্রাম")

        val chosen = if (needsCodingModel) "models/gemini-2.5-pro" else state.activeModelId
        if (chosen != state.activeModelId) {
            _workspaceState.value = state.copy(activeModelId = chosen)
        }
        return chosen
    }

    /**
     * FIXED SINGLE-FILE SAVER:
     * Saves ONLY ONE clean file with the exact detected extension and proper MIME type into `Download/archer/<fileName>`.
     * NEVER creates duplicate `.zip` archives, duplicate subfolder copies, or unwanted `.txt` suffixes!
     */
    fun saveGeneratedCodeToArcherFolder(
        projectTitle: String,
        preferredFileName: String,
        language: String,
        rawCodeOrResponse: String
    ): SavedArcherArtifact {
        val extractedCode = extractCleanCodeBlock(rawCodeOrResponse)
        val cleanLang = detectSmartLanguage(language, extractedCode, preferredFileName)
        val finalFileName = normalizeSingleExactFileName(
            preferredFileName = preferredFileName,
            projectTitle = projectTitle,
            language = cleanLang,
            code = extractedCode,
            overrideExt = null
        )

        val baseArcherDir = resolveWritableArcherRootDirectory()
        if (!baseArcherDir.exists()) baseArcherDir.mkdirs()

        // Keep 1 local copy in app/writable archer dir for fast FileProvider sharing if needed
        val singleFile = File(baseArcherDir, finalFileName)
        runCatching {
            singleFile.writeText(extractedCode, Charsets.UTF_8)
        }

        // Write ONLY ONE single file to public Download/archer/<finalFileName> with exact MIME type
        val publicFilePath = saveSingleFileToPublicDownloadArcher(
            fileName = finalFileName,
            codeBytes = extractedCode.toByteArray(Charsets.UTF_8)
        )

        val timeLabel = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date())
        val artifact = SavedArcherArtifact(
            id = "art_${System.currentTimeMillis()}",
            projectTitle = projectTitle.ifBlank { finalFileName.substringBeforeLast('.') },
            primaryFileName = finalFileName,
            language = cleanLang,
            codeContent = extractedCode,
            folderPath = "/storage/emulated/0/Download/archer",
            singleFilePath = publicFilePath,
            zipFilePath = publicFilePath, // Points to the single clean file (no duplicate .zip created)
            createdAtLabel = timeLabel
        )

        val updatedList = listOf(artifact) + _workspaceState.value.savedArtifacts.filterNot {
            it.primaryFileName.equals(artifact.primaryFileName, ignoreCase = true)
        }

        _workspaceState.value = _workspaceState.value.copy(
            currentProjectTitle = artifact.projectTitle,
            currentFileName = finalFileName,
            currentLanguage = cleanLang,
            currentCode = extractedCode,
            archerMainFolderPath = "/storage/emulated/0/Download/archer",
            lastSaveSummary = "✔ সিঙ্গেল ফাইল সেভ ও Google Drive-এ অটো-সিঙ্ক হয়েছে: $publicFilePath",
            savedArtifacts = updatedList.take(15)
        )

        // Zero-Click Auto-Sync: Immediately upload/update the single file in Google Drive (/AI_OS_Memory/Argon4_Code)
        managerScope.launch {
            GoogleDriveMemorySyncService.backupGeneratedFileToDrive(
                context = appContext,
                fileName = finalFileName,
                mimeType = mimeTypeForFileName(finalFileName),
                contentBytes = extractedCode.toByteArray(Charsets.UTF_8),
                summaryDescription = "Argon 4 Code Project: ${artifact.projectTitle}",
                targetSubFolder = GoogleDriveMemorySyncService.SUBFOLDER_CODE
            )
        }

        return artifact
    }

    private fun ensureArcherDirectoriesAndLoadExisting() {
        runCatching {
            val baseDir = resolveWritableArcherRootDirectory()
            if (!baseDir.exists()) baseDir.mkdirs()
            // Load existing single files if any, without creating any unwanted startup files
            val existingFiles = baseDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".zip", ignoreCase = true) && it.name != "README.md" }
                ?.sortedByDescending { it.lastModified() }
                ?.take(10)
                .orEmpty()

            if (existingFiles.isNotEmpty()) {
                val loaded = existingFiles.map { file ->
                    val content = runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("")
                    val lang = detectSmartLanguage("", content, file.name)
                    SavedArcherArtifact(
                        id = "art_${file.lastModified()}",
                        projectTitle = file.nameWithoutExtension,
                        primaryFileName = file.name,
                        language = lang,
                        codeContent = content,
                        folderPath = "/storage/emulated/0/Download/archer",
                        singleFilePath = "/storage/emulated/0/Download/archer/${file.name}",
                        zipFilePath = "/storage/emulated/0/Download/archer/${file.name}",
                        createdAtLabel = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault()).format(Date(file.lastModified()))
                    )
                }
                _workspaceState.value = _workspaceState.value.copy(savedArtifacts = loaded)
            }
        }
    }

    private fun resolveWritableArcherRootDirectory(): File {
        try {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val archerDl = File(pubDownloads, "archer")
            if (archerDl.exists() || archerDl.mkdirs()) {
                if (archerDl.canWrite()) return archerDl
            }
        } catch (_: Exception) {
        }

        val extDir = appContext.getExternalFilesDir(null)
        if (extDir != null) {
            val archerExt = File(extDir, "archer")
            if (archerExt.exists() || archerExt.mkdirs()) return archerExt
        }

        val internalArcher = File(appContext.filesDir, "archer")
        if (!internalArcher.exists()) internalArcher.mkdirs()
        return internalArcher
    }

    /**
     * Writes ONLY ONE file into `Download/archer/<fileName>` with the exact MIME type for its extension
     * so Android MediaStore NEVER appends `.txt` or creates duplicate `.zip` files.
     */
    private fun saveSingleFileToPublicDownloadArcher(
        fileName: String,
        codeBytes: ByteArray
    ): String {
        val exactMime = mimeTypeForFileName(fileName)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = appContext.contentResolver
                // Delete any existing entry with the same display name in Download/archer/ to prevent "(1)" duplicates
                runCatching {
                    resolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf(fileName, "%Download/archer%")
                    )
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, exactMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/archer")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(codeBytes)
                        out.flush()
                    }
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    return "/storage/emulated/0/Download/archer/$fileName"
                }
            }
        } catch (_: Exception) {
        }
        return File(resolveWritableArcherRootDirectory(), fileName).absolutePath
    }

    fun shareCodeOrProject(artifact: SavedArcherArtifact) {
        shareGeneratedArtifactToContactOrApp(
            targetContactOrApp = "",
            preferredFileName = artifact.primaryFileName,
            sendAsZip = false,
            captionText = artifact.projectTitle
        )
    }

    fun shareGeneratedArtifactToContactOrApp(
        targetContactOrApp: String,
        preferredFileName: String = "",
        sendAsZip: Boolean = false,
        captionText: String = "",
        launchExternal: Boolean = true
    ): String {
        val state = _workspaceState.value
        val matchedArtifact = state.savedArtifacts.firstOrNull { art ->
            preferredFileName.isNotBlank() && (
                art.primaryFileName.equals(preferredFileName.trim(), ignoreCase = true) ||
                    art.projectTitle.contains(preferredFileName.trim(), ignoreCase = true)
                )
        } ?: state.savedArtifacts.firstOrNull() ?: saveGeneratedCodeToArcherFolder(
            projectTitle = state.currentProjectTitle,
            preferredFileName = state.currentFileName,
            language = state.currentLanguage,
            rawCodeOrResponse = state.currentCode
        )

        val baseDir = resolveWritableArcherRootDirectory()
        val targetDiskFile = File(baseDir, matchedArtifact.primaryFileName)
        if (!targetDiskFile.exists()) {
            runCatching {
                targetDiskFile.writeText(matchedArtifact.codeContent, Charsets.UTF_8)
            }
        }

        val lowerTarget = targetContactOrApp.lowercase()
        val cleanRecipient = extractCleanPersonName(targetContactOrApp)
        val targetPkg = when {
            lowerTarget.contains("telegram") || lowerTarget.contains("টেলিগ্রাম") -> "org.telegram.messenger"
            lowerTarget.contains("whatsapp") || lowerTarget.contains("হোয়াটসঅ্যাপ") || lowerTarget.contains("হোয়াটসঅ্যাপ") -> "com.whatsapp"
            cleanRecipient.isNotBlank() -> "com.whatsapp"
            else -> ""
        }

        if (cleanRecipient.isNotBlank()) {
            val candidates = buildPhoneticNameCandidates(cleanRecipient)
            AccessibilityAutomationBus.queueMacro(
                PendingUiMacro(
                    targetPackageHint = targetPkg,
                    contactRecipientToOpen = cleanRecipient,
                    textToType = captionText,
                    isDirectChatMessage = true,
                    maxAutoScrollRetries = 3,
                    clickTargets = buildList {
                        addAll(candidates)
                        add("Send")
                        add("পাঠান")
                    }
                )
            )
        }

        if (launchExternal) {
            runCatching {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    targetDiskFile
                )
                val mime = mimeTypeForFileName(targetDiskFile.name)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "${matchedArtifact.projectTitle} (${targetDiskFile.name})")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        captionText.ifBlank { "${matchedArtifact.projectTitle} — ${targetDiskFile.name}" }
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (targetPkg.isNotBlank()) {
                        setPackage(targetPkg)
                    }
                }
                try {
                    appContext.startActivity(shareIntent)
                } catch (_: Exception) {
                    shareIntent.setPackage(null)
                    appContext.startActivity(
                        Intent.createChooser(shareIntent, "কোড ফাইলটি পাঠান").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }.onFailure {
                val textFallback = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "${matchedArtifact.projectTitle} (${matchedArtifact.primaryFileName})")
                    putExtra(Intent.EXTRA_TEXT, matchedArtifact.codeContent)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    appContext.startActivity(
                        Intent.createChooser(textFallback, "কোড বা প্রজেক্ট শেয়ার করুন").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
        }

        val recipientDesc = cleanRecipient.ifBlank { targetContactOrApp.ifBlank { "শেয়ার মেনু" } }
        return "জি বস, 'Download/archer/' ফোল্ডার থেকে '${targetDiskFile.name}' ফাইলটি '$recipientDesc'-কে পাঠানোর জন্য প্রস্তুত করা হয়েছে।"
    }

    private fun extractCleanCodeBlock(raw: String): String {
        val fenced = Regex("```(?:[a-zA-Z0-9_+-]+)?\\s*([\\s\\S]*?)```").find(raw)
        if (fenced != null) {
            val body = fenced.groupValues[1].trim()
            if (body.isNotBlank()) return body
        }
        return raw
            .replace(Regex("\\[\\[ACTION:.*?]]", RegexOption.DOT_MATCHES_ALL), "")
            .trim()
    }

    /**
     * Intelligently detects the language from explicit parameter, file name, or code structure.
     */
    private fun detectSmartLanguage(explicitLang: String, code: String, fileName: String): String {
        val lowerName = fileName.lowercase().trim()
        val lowerCode = code.lowercase().trim()
        val lowerExplicit = explicitLang.lowercase().trim()

        return when {
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                lowerCode.contains("<!doctype html") || lowerCode.contains("<html") ||
                (lowerCode.contains("<div") && lowerCode.contains("<style")) ||
                lowerExplicit.contains("html") || lowerExplicit.contains("web") -> "HTML/JS"

            lowerName.endsWith(".py") || lowerExplicit.contains("python") ||
                lowerCode.contains("def ") || (lowerCode.contains("import ") && lowerCode.contains("print(")) -> "Python"

            lowerName.endsWith(".js") || lowerExplicit == "js" || lowerExplicit.contains("javascript") ||
                lowerCode.contains("console.log(") || lowerCode.contains("document.queryselector") -> "JavaScript"

            lowerName.endsWith(".kt") || lowerExplicit.contains("kotlin") ||
                lowerCode.contains("fun main") || lowerCode.contains("@composable") || lowerCode.contains("package com.") -> "Kotlin"

            lowerName.endsWith(".json") || lowerExplicit.contains("json") ||
                (lowerCode.startsWith("{") && lowerCode.endsWith("}") && lowerCode.contains("\":")) -> "JSON"

            lowerName.endsWith(".css") || lowerExplicit.contains("css") -> "CSS"
            lowerName.endsWith(".java") || lowerExplicit.contains("java") || lowerCode.contains("public class ") -> "Java"
            lowerName.endsWith(".cpp") || lowerExplicit.contains("c++") || lowerCode.contains("#include") -> "C++"
            lowerName.endsWith(".md") || lowerExplicit.contains("markdown") -> "Markdown"
            else -> explicitLang.ifBlank { "Text" }
        }
    }

    /**
     * Guarantees a single clean file name with the exact right extension (`.html`, `.py`, `.js`, `.kt`, `.json`, `.txt`, etc.)
     * and strips any accidental double extensions like `.html.txt` or `.py.txt`.
     */
    private fun normalizeSingleExactFileName(
        preferredFileName: String,
        projectTitle: String,
        language: String,
        code: String,
        overrideExt: String?
    ): String {
        val detectedExt = when {
            !overrideExt.isNullOrBlank() -> overrideExt.lowercase().trim().removePrefix(".")
            else -> extensionForLanguageAndCode(language, preferredFileName, code)
        }

        val rawBase = if (preferredFileName.isNotBlank()) {
            preferredFileName
                .replace(Regex("\\.(txt|zip|html|htm|py|js|kt|json|css|java|cpp|md)$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\.(txt|zip|html|htm|py|js|kt|json|css|java|cpp|md)$", RegexOption.IGNORE_CASE), "")
        } else {
            projectTitle
        }

        val cleanSlug = rawBase
            .lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "_")
            .trim('_')
            .ifBlank { "archer_app_${System.currentTimeMillis() % 10000}" }

        return "$cleanSlug.$detectedExt"
    }

    private fun extensionForLanguageAndCode(lang: String, fileName: String, code: String): String {
        val lowerCode = code.lowercase().trim()
        val lowerFile = fileName.lowercase().trim()

        // First check if code is clearly HTML/Web even if fileName was generic "main.txt"
        if (lowerCode.contains("<!doctype html") || lowerCode.contains("<html") ||
            (lowerCode.contains("<body") && lowerCode.contains("</body>"))
        ) {
            return "html"
        }
        if (lowerCode.startsWith("{") && lowerCode.endsWith("}") && lowerCode.contains("\":")) {
            return "json"
        }

        // If the user/AI gave a specific non-txt extension, honor it
        if (lowerFile.contains(".")) {
            val givenExt = lowerFile.substringAfterLast('.').trim()
            if (givenExt in setOf("html", "htm", "py", "js", "kt", "json", "css", "java", "cpp", "md", "xml", "sh")) {
                return if (givenExt == "htm") "html" else givenExt
            }
        }

        return when (lang.uppercase().trim()) {
            "HTML", "HTML/JS", "WEB", "HTML5" -> "html"
            "PYTHON", "PY" -> "py"
            "JAVASCRIPT", "JS", "NODE" -> "js"
            "KOTLIN", "KT" -> "kt"
            "JSON" -> "json"
            "CSS" -> "css"
            "JAVA" -> "java"
            "C++", "CPP" -> "cpp"
            "MARKDOWN", "MD" -> "md"
            else -> "txt"
        }
    }

    /**
     * Maps each extension to its exact MIME type so Android MediaStore NEVER appends `.txt` to code files!
     */
    private fun mimeTypeForFileName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase().trim()
        return when (ext) {
            "html", "htm" -> "text/html"
            "py" -> "text/x-python"
            "js" -> "application/javascript"
            "kt" -> "text/x-kotlin"
            "json" -> "application/json"
            "css" -> "text/css"
            "java" -> "text/x-java-source"
            "cpp", "c" -> "text/x-c++src"
            "md" -> "text/markdown"
            "xml" -> "application/xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "text/plain"
        }
    }

    // =========================================================================
    // Contacts List + Screen-Wide Name Scanner
    // =========================================================================

    fun hasReadContactsPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasCallPhonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun findContactOrScreenTarget(
        rawSpokenNameOrCommand: String,
        savedPlatformContacts: List<Pair<String, String>> = emptyList()
    ): ContactOrScreenResolution {
        val cleanName = extractCleanPersonName(rawSpokenNameOrCommand)
        val candidates = buildPhoneticNameCandidates(cleanName)
        val hasPerm = hasReadContactsPermission()

        var matchedContact: DeviceContactEntry? = null

        if (hasPerm && cleanName.isNotBlank()) {
            val allContacts = queryDeviceContacts()
            matchedContact = allContacts.firstOrNull { entry ->
                val contactLower = entry.displayName.lowercase()
                candidates.any { cand ->
                    cand.isNotBlank() && (contactLower.contains(cand.lowercase()) || cand.lowercase().contains(contactLower))
                }
            }
        }

        if (matchedContact == null && cleanName.isNotBlank()) {
            val savedMatch = savedPlatformContacts.firstOrNull { (name, _) ->
                val nLower = name.lowercase()
                candidates.any { cand ->
                    cand.isNotBlank() && (nLower.contains(cand.lowercase()) || cand.lowercase().contains(nLower))
                }
            }
            if (savedMatch != null) {
                matchedContact = DeviceContactEntry(savedMatch.first, savedMatch.second)
            }
        }

        val screenTextLower = AccessibilityAutomationBus.state.value.visibleScreenText.lowercase()
        val foundOnScreen = candidates.any { cand ->
            cand.length >= 2 && screenTextLower.contains(cand.lowercase())
        }

        return ContactOrScreenResolution(
            queryName = cleanName.ifBlank { rawSpokenNameOrCommand.trim() },
            matchedContact = matchedContact,
            foundOnVisibleScreen = foundOnScreen,
            hasContactsPermission = hasPerm,
            phoneticCandidates = candidates
        )
    }

    fun queryDeviceContacts(limit: Int = 250): List<DeviceContactEntry> {
        if (!hasReadContactsPermission()) return emptyList()
        val list = mutableListOf<DeviceContactEntry>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        runCatching {
            appContext.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (cursor.moveToNext() && list.size < limit) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim().orEmpty() else ""
                    val number = if (numIdx >= 0) cursor.getString(numIdx)?.trim().orEmpty() else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        list.add(DeviceContactEntry(name, number))
                    }
                }
            }
        }
        return list
    }

    private fun extractCleanPersonName(rawInput: String): String {
        return rawInput
            .replace(
                Regex(
                    "(?i)(কন্টাক্ট|কন্টেক্ট|ফোনবুক|নাম্বার|নম্বর|স্ক্রিনে|সক্রিনে|সারা স্ক্রিনে|খুঁজে|বিচারাইয়া|বিচারিয়ে|" +
                        "কল দাও|কল করো|কল কর|ফোন দাও|ফোন করো|ডায়াল করো|মেসেজ দাও|মেসেজ করো|হোয়াটসঅ্যাপে|ইমোতে|মেসেঞ্জারে|" +
                        "নামের ওপর|নামে|চাপ দাও|ক্লিক করো|ট্যাপ করো|কে|রে|এর|ভাইকে|আপুকে|স্যার|বস|" +
                        "call|dial|contact|phone|message|whatsapp|tap|click|on screen|search|find|please|sir|boss)"
                ),
                " "
            )
            .replace(Regex("[|,:;!?\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun buildPhoneticNameCandidates(cleanName: String): List<String> {
        if (cleanName.isBlank()) return emptyList()
        val set = linkedSetOf(cleanName)
        val lower = cleanName.lowercase()

        val dictionary = mapOf(
            "রহিম" to listOf("Rahim", "Rohim", "রাহিম"),
            "রাহিম" to listOf("Rahim", "Rohim", "রহিম"),
            "করিম" to listOf("Karim", "Korim"),
            "তানভীর" to listOf("Tanvir", "Tanveer"),
            "সাকিব" to listOf("Sakib", "Shakib", "শাকিব"),
            "শাকিব" to listOf("Shakib", "Sakib", "সাকিব"),
            "হাসান" to listOf("Hasan", "Hassan"),
            "হোসেন" to listOf("Hossain", "Husain"),
            "মামুন" to listOf("Mamun"),
            "সুমন" to listOf("Sumon", "Suman"),
            "রিয়ান" to listOf("Riyan", "Ryan", "Rian"),
            "রিয়ান" to listOf("Riyan", "Ryan", "Rian"),
            "আরিফ" to listOf("Arif"),
            "জাহিদ" to listOf("Jahid", "Zahid"),
            "নাহিদ" to listOf("Nahid"),
            "সজীব" to listOf("Sajib", "Sojib"),
            "ইমরান" to listOf("Imran"),
            "রাকিব" to listOf("Rakib"),
            "নাবিল" to listOf("Nabil"),
            "ফাহিম" to listOf("Fahim"),
            "মা" to listOf("Maa", "Mom", "Amma", "Ammu", "আম্মু", "আম্মা"),
            "আম্মু" to listOf("Ammu", "Mom", "Maa", "মা"),
            "বাবা" to listOf("Baba", "Abba", "Abbu", "আব্বু", "আব্বা", "Dad"),
            "আব্বু" to listOf("Abbu", "Baba", "Dad", "বাবা")
        )

        for ((key, equivalents) in dictionary) {
            if (lower.contains(key.lowercase()) || equivalents.any { lower.contains(it.lowercase()) }) {
                set.add(key)
                set.addAll(equivalents)
            }
        }

        if (cleanName.any { it.code in 0x0980..0x09FF }) {
            val roman = transliterateBengaliNameToEnglish(cleanName)
            if (roman.length >= 2) {
                set.add(roman)
                set.add(roman.replaceFirstChar { it.uppercase() })
            }
        }

        return set.filter { it.isNotBlank() }
    }

    private fun transliterateBengaliNameToEnglish(bn: String): String {
        val sb = StringBuilder()
        for (ch in bn) {
            val m = when (ch) {
                'অ' -> "o"
                'আ', 'া' -> "a"
                'ই', 'ঈ', 'ি', 'ী' -> "i"
                'উ', 'ঊ', 'ু', 'ূ' -> "u"
                'এ', 'ে' -> "e"
                'ও', 'ো' -> "o"
                'ক' -> "k"
                'খ' -> "kh"
                'গ' -> "g"
                'ঘ' -> "gh"
                'চ' -> "ch"
                'ছ' -> "chh"
                'জ', 'য' -> "j"
                'ট', 'ত' -> "t"
                'ঠ', 'থ' -> "th"
                'ড', 'দ' -> "d"
                'ঢ', 'ধ' -> "dh"
                'ণ', 'ন' -> "n"
                'প' -> "p"
                'ফ' -> "f"
                'ব' -> "b"
                'ভ' -> "bh"
                'ম' -> "m"
                'র', 'ড়' -> "r"
                'ল' -> "l"
                'শ', 'ষ', 'স' -> "s"
                'হ' -> "h"
                'য়' -> "y"
                'ং' -> "ng"
                '্', 'ঁ' -> ""
                else -> if (ch.isLetterOrDigit() || ch == ' ') ch.toString() else ""
            }
            sb.append(m)
        }
        return sb.toString().trim()
    }
}
