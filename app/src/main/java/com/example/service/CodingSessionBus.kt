package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CodingSession(
    val id: String,
    val title: String,
    val fileName: String,
    val language: String,
    val code: String,
    val savedPath: String = "",
    val isSaved: Boolean = false,
    val isSyncedToDrive: Boolean = false,
    val isPatchingCode: Boolean = false,
    val activeTab: String = "EDITOR", // "EDITOR" or "PREVIEW"
    val statusMessageBn: String = "",
    val pendingSaveConfirmation: Boolean = true,
    val isDiscarded: Boolean = false
)

/**
 * Drives the Dedicated Coding Studio Page (Mini AI IDE) with multi-tab Code Editor + Sandboxed WebView Live Preview,
 * Voice-Driven Code Patching, and Single-File Clean Export to `Download/archer/` and 5TB Google Drive.
 */
object CodingSessionBus {
    private val _session = MutableStateFlow<CodingSession?>(null)
    val session: StateFlow<CodingSession?> = _session.asStateFlow()

    @Volatile
    var onConfirmSaveRequested: ((String?) -> Unit)? = null

    @Volatile
    var onSyncToDriveRequested: (() -> Unit)? = null

    @Volatile
    var onPatchCodeByVoiceRequested: ((String) -> Unit)? = null

    @Volatile
    var onDiscardRequested: (() -> Unit)? = null

    fun start(s: CodingSession) {
        _session.value = s
    }

    fun selectTab(tab: String) {
        _session.value = _session.value?.copy(activeTab = tab)
    }

    fun updatePatchingState(isPatching: Boolean, statusBn: String = "") {
        _session.value = _session.value?.copy(
            isPatchingCode = isPatching,
            statusMessageBn = statusBn
        )
    }

    fun updatePatchedCode(
        newCode: String,
        newFileName: String? = null,
        newLanguage: String? = null,
        autoSwitchToPreview: Boolean = false,
        statusBn: String = ""
    ) {
        val cur = _session.value ?: return
        _session.value = cur.copy(
            id = "code_${System.currentTimeMillis()}",
            code = newCode,
            fileName = newFileName ?: cur.fileName,
            language = newLanguage ?: cur.language,
            isPatchingCode = false,
            isSaved = false,
            pendingSaveConfirmation = true,
            activeTab = if (autoSwitchToPreview) "PREVIEW" else cur.activeTab,
            statusMessageBn = statusBn
        )
    }

    fun hasPendingUnsavedCode(): Boolean {
        val cur = _session.value ?: return false
        return cur.pendingSaveConfirmation && !cur.isSaved && !cur.isDiscarded
    }

    fun isCodingStudioVisible(): Boolean = _session.value != null

    fun markSaved(path: String, statusBn: String = "") {
        _session.value = _session.value?.copy(
            savedPath = path,
            isSaved = true,
            pendingSaveConfirmation = false,
            isDiscarded = false,
            statusMessageBn = statusBn.ifBlank { "✔ Download/archer/ ফোল্ডারে সেভ হয়েছে: $path" }
        )
    }

    fun markSyncedToDrive(statusBn: String) {
        _session.value = _session.value?.copy(
            isSyncedToDrive = true,
            statusMessageBn = statusBn
        )
    }

    fun markDiscarded() {
        _session.value = _session.value?.copy(
            isSaved = false,
            pendingSaveConfirmation = false,
            isDiscarded = true
        )
    }

    fun requestConfirmSave(preferredExtensionOverride: String? = null) {
        onConfirmSaveRequested?.invoke(preferredExtensionOverride)
    }

    fun requestSyncToDrive() {
        onSyncToDriveRequested?.invoke()
    }

    fun requestPatchCodeByVoice(instruction: String) {
        val clean = instruction.trim()
        if (clean.isNotBlank()) {
            onPatchCodeByVoiceRequested?.invoke(clean)
        }
    }

    fun requestDiscard() {
        onDiscardRequested?.invoke()
        _session.value = null
    }

    fun dismiss() {
        _session.value = null
    }
}
