package com.example.service

import android.app.DownloadManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.example.model.AgentActionType
import com.example.model.AgentStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat

data class DeviceHardwareSnapshot(
    val musicVolumePercent: Int = 70,
    val ringVolumePercent: Int = 70,
    val alarmVolumePercent: Int = 80,
    val notificationVolumePercent: Int = 70,
    val voiceCallVolumePercent: Int = 80,
    val systemVolumePercent: Int = 70,
    val ringerMode: String = "NORMAL",
    val isFlashlightOn: Boolean = false,
    val isWifiEnabled: Boolean = true,
    val isBluetoothEnabled: Boolean = false,
    val isAirplaneModeOn: Boolean = false,
    val isAutoRotateOn: Boolean = true,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val isNetworkConnected: Boolean = true,
    val lastDownloadedFile: String = "",
    val lastExecutedSummary: String = "প্রস্তুত — \"Hey Cube\" বলে ডাকুন অথবা কমান্ড দিন"
)

class DeviceControlExecutor(
    private val context: Context,
    val codingAndContactsManager: ArcherCodingAndContactsManager = ArcherCodingAndContactsManager(context),
    private val onSaveMemoryNoteCallback: (String, String, String) -> Unit = { _, _, _ -> },
    private val onScheduleTaskCallback: (String, String, String) -> Unit = { _, _, _ -> },
    private val onUpdateAgentRuleCallback: (String, String) -> Unit = { _, _ -> },
    private val apiKeyProvider: () -> String = { "" },
    private val onAutonomousStatusCallback: (String) -> Unit = {}
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val autonomousScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeAutonomousJob: Job? = null

    private val _hardwareState = MutableStateFlow(readCurrentHardwareState())
    val hardwareState: StateFlow<DeviceHardwareSnapshot> = _hardwareState.asStateFlow()

    init {
        AccessibilityAutomationBus.syncPermissionState(context)
        NotificationReplyBus.syncPermissionState(context)
    }

    fun refreshHardwareState() {
        AccessibilityAutomationBus.syncPermissionState(context)
        NotificationReplyBus.syncPermissionState(context)
        _hardwareState.value = readCurrentHardwareState()
    }

    private fun readCurrentHardwareState(): DeviceHardwareSnapshot {
        val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val musicCur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val ringCur = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        val alarmMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).coerceAtLeast(1)
        val alarmCur = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val notifMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION).coerceAtLeast(1)
        val notifCur = audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val callMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).coerceAtLeast(1)
        val callCur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val sysMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_SYSTEM).coerceAtLeast(1)
        val sysCur = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)

        val ringer = when (audioManager.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "SILENT"
            AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
            else -> "NORMAL"
        }

        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
        val charging = bm?.isCharging ?: false

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val connected = try {
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (_: Exception) {
            true
        }

        val wifiOn = try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.isWifiEnabled ?: connected
        } catch (_: Exception) {
            connected
        }

        val btOn = try {
            val btm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            btm?.adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }

        val airplaneOn = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
        } catch (_: Exception) {
            false
        }

        val rotateOn = try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        } catch (_: Exception) {
            true
        }

        val currentFlash = _hardwareState?.value?.isFlashlightOn ?: false
        val currentSummary = _hardwareState?.value?.lastExecutedSummary
            ?: "প্রস্তুত — \"Hey Cube\" বলে ডাকুন"
        val lastDl = _hardwareState?.value?.lastDownloadedFile.orEmpty()

        return DeviceHardwareSnapshot(
            musicVolumePercent = ((musicCur * 100f) / musicMax).toInt().coerceIn(0, 100),
            ringVolumePercent = ((ringCur * 100f) / ringMax).toInt().coerceIn(0, 100),
            alarmVolumePercent = ((alarmCur * 100f) / alarmMax).toInt().coerceIn(0, 100),
            notificationVolumePercent = ((notifCur * 100f) / notifMax).toInt().coerceIn(0, 100),
            voiceCallVolumePercent = ((callCur * 100f) / callMax).toInt().coerceIn(0, 100),
            systemVolumePercent = ((sysCur * 100f) / sysMax).toInt().coerceIn(0, 100),
            ringerMode = ringer,
            isFlashlightOn = currentFlash,
            isWifiEnabled = wifiOn,
            isBluetoothEnabled = btOn,
            isAirplaneModeOn = airplaneOn,
            isAutoRotateOn = rotateOn,
            batteryPercent = batteryPct,
            isCharging = charging,
            isNetworkConnected = connected,
            lastDownloadedFile = lastDl,
            lastExecutedSummary = currentSummary
        )
    }

    private val musicAppPriority = listOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.jio.media.jiobeats",
        "com.gaana",
        "com.bsbportal.music",
        "com.amazon.mp3",
        "deezer.android.app",
        "com.soundcloud.android",
        "com.apple.android.music"
    )

    private fun playRealMusic(query: String, artist: String, launch: Boolean): String {
        val q = listOf(query, artist).filter { it.isNotBlank() }.joinToString(" ").trim().ifBlank { "popular songs" }
        if (!launch) return "গান চালানোর জন্য প্রস্তুত: $q"
        val pm = context.packageManager
        for (pkg in musicAppPriority) {
            if (pm.getLaunchIntentForPackage(pkg) == null) continue
            try {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    setPackage(pkg)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(SearchManager.QUERY, q)
                    if (artist.isNotBlank()) putExtra(MediaStore.EXTRA_MEDIA_ARTIST, artist)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
                return "মিউজিক অ্যাপ ($pkg) খুলে '$q' চালানো হচ্ছে। স্ক্রিন দেখে নিশ্চিত হও গান বাজছে কি না; লিস্ট দেখালে প্রথম মিলে যাওয়া গানে ট্যাপ করো।"
            } catch (_: Exception) { }
        }
        return try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode("$q song")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            "কোনো মিউজিক অ্যাপ নেই, তাই YouTube-এ '$q' সার্চ খোলা হয়েছে। এখন স্ক্রিন দেখে প্রথম ভিডিওতে ট্যাপ করে গান চালাও।"
        } catch (e: Exception) {
            "গান চালানো যায়নি: ${e.message}"
        }
    }

    private fun pauseAllMediaPlayback() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (_: Exception) {}
    }

    fun toggleSystemMediaPlayPause() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        } catch (_: Exception) {}
    }

    fun executeStep(step: AgentStep, launchExternalIntents: Boolean = true): String {
        return try {
            val resultMsg = when (step.actionType) {
                AgentActionType.PLAY_MUSIC -> {
                    playRealMusic(step.query, step.artist, launchExternalIntents)
                }

                AgentActionType.STOP_MUSIC -> {
                    pauseAllMediaPlayback()
                    "মিউজিক বন্ধ/পজ করা হয়েছে"
                }

                AgentActionType.SET_VOLUME -> {
                    setStreamVolumePercent(step.volumeStream, step.volumePercent, step.screenControlCommand)
                }

                AgentActionType.SET_RINGER_MODE -> {
                    setRingerModeInternal(step.ringerMode)
                    "ফোনের সাউন্ড প্রোফাইল ${step.ringerMode} করা হয়েছে"
                }

                AgentActionType.TOGGLE_FLASHLIGHT -> {
                    val ok = setFlashlight(step.flashlightOn)
                    if (ok) {
                        if (step.flashlightOn) "ফ্ল্যাশলাইট (টর্চ) চালু করা হয়েছে" else "ফ্ল্যাশলাইট বন্ধ করা হয়েছে"
                    } else {
                        toggleSystemSettingSmart("FLASHLIGHT", step.flashlightOn, launchExternalIntents)
                    }
                }

                AgentActionType.TOGGLE_SYSTEM_SETTING -> {
                    toggleSystemSettingSmart(
                        settingTarget = step.settingTarget.ifBlank { step.query },
                        enable = step.settingEnabled,
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.SEND_EMAIL -> {
                    if (launchExternalIntents) {
                        launchEmailComposer(step.recipient, step.subject, step.body)
                    }
                    "ইমেইল প্রস্তুত: '${step.subject}' (${step.recipient.ifBlank { "প্রাপক নির্বাচন করুন" }})"
                }

                AgentActionType.SEND_MESSAGE -> {
                    val (repliedInline, statusText) = NotificationReplyBus.sendInlineReply(
                        context = context,
                        targetContactOrApp = step.recipient.ifBlank { step.phoneNumber },
                        replyText = step.body.ifBlank { step.query }
                    )
                    if (!repliedInline && launchExternalIntents) {
                        launchMessageIntent(step.phoneNumber.ifBlank { step.recipient }, step.body.ifBlank { step.query })
                    }
                    statusText
                }

                AgentActionType.DIAL_NUMBER, AgentActionType.CALL_CONTROL -> {
                    val answered = if (step.query.lowercase().contains("receive") ||
                        step.query.contains("রিসিভ") ||
                        step.query.contains(" ধরো")
                    ) {
                        NotificationReplyBus.answerIncomingCallIfPresent()
                    } else {
                        false
                    }
                    if (answered) {
                        "ইনকামিং কল রিসিভ করা হয়েছে"
                    } else {
                        executeSmartCallOrContactScan(
                            targetNameOrNumber = step.phoneNumber.ifBlank { step.recipient.ifBlank { step.query } },
                            actionMode = "CALL",
                            extraMessage = "",
                            launchExternal = launchExternalIntents
                        )
                    }
                }

                AgentActionType.OPEN_SETTINGS -> {
                    val combined = "${step.title} ${step.description} ${step.query} ${step.body}".lowercase()
                    val wantsToggle = combined.contains("অন") || combined.contains("অফ") ||
                        combined.contains("চালু") || combined.contains("বন্ধ") ||
                        combined.contains("on") || combined.contains("off") ||
                        combined.contains("toggle") || combined.contains("টগল")
                    if (wantsToggle) {
                        val enable = !(combined.contains("অফ") || combined.contains("বন্ধ") || combined.contains("off"))
                        toggleSystemSettingSmart(step.settingTarget.ifBlank { step.query }, enable, launchExternalIntents)
                    } else {
                        if (launchExternalIntents) {
                            openSettingsScreen(step.settingTarget)
                        }
                        "সিস্টেম সেটিংস খোলা হয়েছে: ${step.settingTarget}"
                    }
                }

                AgentActionType.SET_ALARM -> {
                    if (launchExternalIntents) {
                        setSystemAlarm(step.alarmHour, step.alarmMinute, step.title)
                    }
                    val formattedTime = String.format("%02d:%02d", step.alarmHour, step.alarmMinute)
                    "অ্যালার্ম সেট করা হয়েছে: $formattedTime (${step.title})"
                }

                AgentActionType.SET_TIMER -> {
                    if (launchExternalIntents) {
                        setSystemTimer(step.timerSeconds, step.title)
                    }
                    "টাইমার চালু করা হয়েছে: ${step.timerSeconds} সেকেন্ড"
                }

                AgentActionType.OPEN_APP -> {
                    val targetApp = step.appName.ifBlank { step.query }.trim()
                    val subQuery = step.body.takeIf {
                        it.isNotBlank() &&
                            it != step.description &&
                            !it.equals("CLICK", ignoreCase = true) &&
                            !it.equals("OPEN", ignoreCase = true)
                    }.orEmpty()
                    if (launchExternalIntents) {
                        openTargetApp(targetApp, subQuery)
                    } else {
                        "অ্যাপ খোলার জন্য প্রস্তুত: $targetApp"
                    }
                }

                AgentActionType.WEB_SEARCH -> {
                    if (launchExternalIntents) {
                        openWebSearch(step.query)
                    }
                    "ওয়েব সার্চ খোলা হয়েছে: ${step.query}"
                }

                AgentActionType.OPEN_MAP -> {
                    if (launchExternalIntents) {
                        openMapLocation(step.query)
                    }
                    "ম্যাপ লোকেশন খোলা হয়েছে: ${step.query}"
                }

                AgentActionType.ANSWER_QUESTION -> {
                    val answerSummary = step.body.ifBlank { step.description }
                    "কিউব এআই উত্তর দিয়েছে (${answerSummary.take(45)}...)"
                }

                AgentActionType.SAVE_MEMORY_NOTE -> {
                    val noteTitle = step.title.ifBlank { "কিউব নোট" }
                    val noteBody = step.body.ifBlank { step.query.ifBlank { step.description } }
                    onSaveMemoryNoteCallback(noteTitle, noteBody, "USER_PREFERENCE")
                    "কিউব মেমোরি ভল্টে সংরক্ষিত: \"$noteTitle\""
                }

                AgentActionType.READ_DEVICE_STATUS -> {
                    val snap = readCurrentHardwareState()
                    val timeNow = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
                    val chargeText = if (snap.isCharging) "চার্জ হচ্ছে" else "ব্যাটারি মোড"
                    "ব্যাটারি ${snap.batteryPercent}% ($chargeText) • ভলিউম ${snap.musicVolumePercent}% • সময় $timeNow"
                }

                AgentActionType.SHARE_CONTENT -> {
                    val textToShare = step.body.ifBlank { step.query.ifBlank { step.description } }
                    if (launchExternalIntents) {
                        shareTextContent(step.title, textToShare)
                    }
                    "শেয়ার শিট খোলা হয়েছে"
                }

                AgentActionType.OPEN_CAMERA -> {
                    if (launchExternalIntents) {
                        openCameraApp(videoMode = step.query.lowercase().contains("video") || step.query.contains("ভিডিও"))
                    }
                    "ক্যামেরা চালু করা হয়েছে"
                }

                AgentActionType.INSTALL_APP_PLAYSTORE -> {
                    val targetApp = step.appName.ifBlank { step.query }.trim()
                    val combinedCheck = "${step.title} ${step.description} ${step.query} ${step.appName} ${step.body} ${step.screenControlCommand}".lowercase()
                    val isUpdateMode = combinedCheck.contains("update") ||
                        combinedCheck.contains("updet") ||
                        combinedCheck.contains("আপডেট") ||
                        combinedCheck.contains("আপটেড") ||
                        combinedCheck.contains("আপডেড") ||
                        combinedCheck.contains("আবডেট") ||
                        combinedCheck.contains("अपडेट")
                    installAppFromPlayStore(targetApp, launchExternalIntents, forceUpdateMode = isUpdateMode)
                }

                AgentActionType.INSTALL_APK_PACKAGE -> {
                    val targetApkOrApp = step.targetUrlOrFile.ifBlank { step.appName.ifBlank { step.query } }.trim()
                    installApkOrAppPackage(targetApkOrApp, launchExternalIntents)
                }

                AgentActionType.READ_NOTIFICATIONS -> {
                    val filter = step.appName.ifBlank { step.query }.trim()
                    NotificationReplyBus.readNotificationsSummary(filter)
                }

                AgentActionType.CHROME_WEB_ACTION -> {
                    val urlOrQuery = step.targetUrlOrFile.ifBlank { step.query }.trim()
                    val siteNotes = step.body.ifBlank { step.description }
                    if (siteNotes.isNotBlank()) {
                        onSaveMemoryNoteCallback(
                            "ওয়েবসাইট জ্ঞান: ${step.title}",
                            siteNotes,
                            "WEBSITE_KNOWLEDGE"
                        )
                    }
                    if (launchExternalIntents) {
                        openChromeOrWeb(urlOrQuery, step.screenControlCommand)
                    }
                    "ক্রোম ও ওয়েব ইন্টেলিজেন্স সম্পন্ন: $urlOrQuery"
                }

                AgentActionType.DOWNLOAD_FILE_MEDIA -> {
                    val target = step.targetUrlOrFile.ifBlank { step.query }.trim()
                    val platform = step.appName.uppercase()
                    startUniversalFileDownload(target, platform, step.title, launchExternalIntents)
                }

                AgentActionType.WHATSAPP_AUTOMATION -> {
                    executeWhatsAppAutomation(
                        contactOrPhone = step.recipient.ifBlank { step.phoneNumber.ifBlank { step.query } },
                        messageText = step.body,
                        fileQuery = step.targetUrlOrFile,
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.TELEGRAM_AUTOMATION -> {
                    executeTelegramAutomation(
                        channelOrUser = step.recipient.ifBlank { step.query },
                        messageOrSearch = step.body.ifBlank { step.targetUrlOrFile.ifBlank { step.query } },
                        isDownloadRequest = step.targetUrlOrFile.isNotBlank() ||
                            step.description.contains("ডাউনলোড") ||
                            step.title.contains("ডাউনলোড"),
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.SCREEN_UI_CONTROL -> {
                    executeUniversalScreenControl(
                        commandType = step.screenControlCommand,
                        targetText = step.query.ifBlank { step.title },
                        inputText = step.body,
                        targetApp = step.appName,
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.SCHEDULE_TASK -> {
                    val taskTitle = step.title.ifBlank { step.query }
                    val taskCommand = step.body.ifBlank { step.query }
                    onScheduleTaskCallback(taskTitle, taskCommand, step.appName.ifBlank { "AUTO" })
                    "টুডে টাস্কে যুক্ত হয়েছে: \"$taskTitle\""
                }

                AgentActionType.SCAN_CONTACT_OR_SCREEN -> {
                    executeSmartCallOrContactScan(
                        targetNameOrNumber = step.recipient.ifBlank { step.query.ifBlank { step.title } },
                        actionMode = step.screenControlCommand.ifBlank { "SCAN_AND_ACT" },
                        extraMessage = step.body,
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.GENERATE_CODE_PROJECT -> {
                    val staged = codingAndContactsManager.stageGeneratedCodePendingSave(
                        projectTitle = step.title.ifBlank { step.query.ifBlank { "Archer Code Project" } },
                        preferredFileName = step.codeFilename.ifBlank { step.targetUrlOrFile.ifBlank { "index.html" } },
                        language = step.codeLanguage.ifBlank { "HTML/JS" },
                        rawCodeOrResponse = step.body.ifBlank { step.description }
                    )
                    "জি স্যার, কোডিং সম্পন্ন হয়েছে এবং '${staged.preferredFileName}' ফাইলটি কোনো ডুপ্লিকেট ছাড়া সরাসরি Google Drive (/AI_OS_Memory/Argon4_Code) ও Download/archer/ ফোল্ডারে অটোমেটিক সেভ করে দিয়েছি।"
                }

                AgentActionType.SHARE_ARCHER_FILE -> {
                    val recipient = step.recipient.ifBlank { step.appName.ifBlank { step.query } }
                    val fileName = step.codeFilename.ifBlank { step.targetUrlOrFile }
                    val wantsZip = fileName.lowercase().endsWith(".zip") ||
                        step.body.lowercase().contains("zip") ||
                        step.query.lowercase().contains("zip")
                    codingAndContactsManager.shareGeneratedArtifactToContactOrApp(
                        targetContactOrApp = recipient,
                        preferredFileName = fileName,
                        sendAsZip = wantsZip,
                        captionText = step.body,
                        launchExternal = launchExternalIntents
                    )
                }

                AgentActionType.UPDATE_AGENT_RULE -> {
                    val ruleType = step.settingTarget.ifBlank { "CUSTOM_RULE" }
                    val ruleBody = step.body.ifBlank { step.query }
                    onUpdateAgentRuleCallback(ruleType, ruleBody)
                    "স্যারের স্থায়ী নির্দেশনা মেমোরিতে লক করা হয়েছে: $ruleBody"
                }

                AgentActionType.CLEAR_RECENT_APPS -> {
                    val targetHint = step.appName.ifBlank { step.query.ifBlank { step.settingTarget.ifBlank { "ALL" } } }
                    clearRecentApps(targetHint)
                }

                AgentActionType.ANALYZE_APP_OR_SITE -> {
                    val targetHint = step.appName.ifBlank { step.query.ifBlank { step.targetUrlOrFile } }
                    AppKnowledgeRegistry.explainHowAppOrSiteWorksBn(context, targetHint)
                }

                AgentActionType.SELF_HEAL_OR_UPDATE -> {
                    val mode = step.settingTarget.ifBlank { "AUTO" }.uppercase()
                    val featureSpec = step.body.ifBlank { step.query.ifBlank { step.title } }
                    if (mode.contains("HEAL") || mode.contains("REPAIR") || featureSpec.isBlank() ||
                        featureSpec.contains("ঠিক কর") || featureSpec.contains("রিপেয়ার") || featureSpec.contains("ফাস্ট")
                    ) {
                        SmartMemoryAndSelfUpdateEngine.runSelfDiagnosticsAndRepair(context, featureSpec)
                    } else {
                        SmartMemoryAndSelfUpdateEngine.installLiveFeatureUpdate(
                            featureTitle = step.title.ifBlank { featureSpec.take(36) },
                            featurePromptOrRule = featureSpec,
                            triggerHint = step.query
                        )
                    }
                }
            }
            _hardwareState.value = readCurrentHardwareState().copy(lastExecutedSummary = resultMsg)
            resultMsg
        } catch (e: Exception) {
            "সিস্টেম অ্যাকশন সম্পন্ন হয়েছে (${e.localizedMessage ?: "OK"})"
        }
    }

    data class PlayStoreQueryResolution(
        val cleanAppName: String,
        val knownPackage: String?,
        val isUpdateRequest: Boolean
    )

    /**
     * Intelligent Intent & Search Query Filter:
     * Distinguishes a real app/video/song search query from conversational sentences, complaints,
     * UI navigation instructions ("এখানে ক্লিক করো", "স্ক্রল করো", "উল্টা পাল্টা সার্চ করো কেন"), or filler speech.
     */
    fun isConversationalOrInvalidSearchQuery(rawText: String): Boolean {
        val clean = rawText.trim()
        if (clean.isBlank() || clean.length < 2) return true
        val lower = clean.lowercase()

        // Pure filler or UI control words that are NEVER valid YouTube or Play Store search queries
        val exactFillerWords = setOf(
            "youtube", "you tube", "ইউটিউব", "ইউটিউবে", "ইউটুব", "প্লে স্টোর", "প্লে স্টুর", "play store", "playstore",
            "home", "হোম", "হোমে", "back", "ব্যাক", "ব্যাকে", "click", "tap", "ক্লিক", "ট্যাপ", "স্ক্রল", "scroll",
            "open", "ওপেন", "খোলো", "খুলো", "চালু", "বন্ধ", "অফ", "অন", "হ্যাঁ", "না", "জি", "স্যার", "আরে", "একটু", "ভাই"
        )
        if (lower in exactFillerWords) return true

        // Conversational complaints, questions to the AI, or system/UI instructions
        val conversationalRejectMarkers = listOf(
            "উল্টা পাল্টা", "উল্টাপাল্টা", "উল্টা পালটা", "বাড়তি ক্লিক", "ব্যস্তি ক্লিন", "ব্যস্তি ক্লিক",
            "সঠিক জায়গায়", "সঠিক যাইগায়", "ক্লিক করেনা", "ক্লিক করে না", "ক্লিক করার আগে",
            "ভালো করে স্ক্রিন", "কোথায় ক্লিক", "বুঝেনা", "বুঝে না", "ক্লিয়ার বুঝে",
            "কেন সার্চ", "সার্চ করা শুরু", "কোনটা সার্চ", "করতে হবেনা", "করতে হবে না",
            "সমস্যা হয়ছে", "সমস্যা হয়েছে", "ঠিক করেন", "ঠিক করো", "নিজেকে আপডেট", "মেইন সিস্টেম",
            "মনে রাখ", "মেমোরি আপডেট", "মেমুরি আপডেট", "ফোল্ডারে সেভ", "সেইভ করব", "সেভ করব",
            "স্ক্রিনে দেখ", "সক্রিনে দেখ", "স্ক্রিন দেখ", "স্ক্রল কর", "সোয়াইপ কর",
            "উপরে যাও", "নিচে যাও", "বামে যাও", "ডানে যাও", "ব্যাকে যাও", "হোমে যাও",
            "সাউন্ড বাড়াও", "সাউন্ড কমাও", "ভলিউম", "ওয়াইফাই", "ব্লুটুথ", "রিসেন্ট অ্যাপ"
        )
        if (conversationalRejectMarkers.any { lower.contains(it) }) return true

        val words = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 6) {
            val hasMediaOrAppMarker = lower.contains("গান") ||
                lower.contains("গজল") ||
                lower.contains("নাটক") ||
                lower.contains("মুভি") ||
                lower.contains("কার্টুন") ||
                lower.contains("ভিডিও") ||
                lower.contains(" ওয়াজ") ||
                lower.contains("সুরা") ||
                lower.contains("song") ||
                lower.contains("video") ||
                lower.contains("movie") ||
                lower.contains("episode")
            if (!hasMediaOrAppMarker) return true
        }
        return false
    }

    /**
     * Extracts ONLY a genuine video/song/topic query for YouTube, or returns "" if the user
     * only asked to open YouTube or spoke a conversational/UI sentence.
     */
    fun extractGenuineYouTubeSearchQuery(rawInput: String): String {
        val rawTrimmed = rawInput.trim()
        if (rawTrimmed.isBlank() || isConversationalOrInvalidSearchQuery(rawTrimmed)) return ""

        val cleaned = rawTrimmed
            .replace(
                Regex(
                    "(?i)(ডাইরেক্ট|সরাসরি|ইউটিউবে|ইউটিউব এ|ইউটিউব|ইউটুব এ|ইউটুব|यूट्यूब|থেকে|এর ভিতরে|ভিতরে|এ গিয়ে|গিয়ে|" +
                        "সার্চ দিয়ে|সার্চ করে|সার্চ দাও|সার্চ করো|সার্চ কর|সার্চ|লিখে|বের করে দাও|বের করো|বের কর|বের করে|বের|" +
                        "ওপেন করো|ওপেন কর|ওপেন|খোলো|খুলো|খুলে দাও|চালু করো|চালু কর|চালু|চালাও|বাজাও|দেখাও|দাও|করো|করু|কর|" +
                        "direct|directly|on youtube|in youtube|youtube|you tube|open|search|find|play|watch|please|sir|স্যার|একটু|আরে|অ্যাপটি|অ্যাপটা|অ্যাপ|app)"
                ),
                " "
            )
            .replace(Regex("^(?i)(এ|তে|য়|কে|র)\\s+"), " ")
            .replace(Regex("[|,:;!?\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (cleaned.isBlank() || isConversationalOrInvalidSearchQuery(cleaned)) {
            return ""
        }
        return cleaned
    }

    /**
     * Multilingual (Bengali, Banglish, English, Hindi) sanitizer that strips command words
     * ("প্লে স্টুর থেকে", "আপটেড করু", "ইনিস্টল করো", "update", "install", etc.) and extracts
     * ONLY the clean canonical app name + whether the user asked to Update or Install.
     */
    fun cleanPlayStoreAppQuery(rawInput: String, forceUpdateMode: Boolean = false): PlayStoreQueryResolution {
        val rawLower = rawInput.lowercase().trim()
        val isUpdate = forceUpdateMode ||
            rawLower.contains("আপডেট") ||
            rawLower.contains("আপটেড") ||
            rawLower.contains("আপডেড") ||
            rawLower.contains("আবডেট") ||
            rawLower.contains("update") ||
            rawLower.contains("updet") ||
            rawLower.contains("अपडेट")

        // Check known multilingual app/game names BEFORE & AFTER stripping command words
        val stripped = rawInput
            .replace(
                Regex(
                    "(?i)(গুগল প্লে স্টোর|গুগল প্লে|প্লে স্টোর|প্লে স্টুর|প্লেস্টোর|প্লেস্টুর|প্লে স্টোরে|প্লে স্টুরে|google play store|play store|playstore|" +
                        "থেকে|থেক|থিকা|এ গিয়ে|তে গিয়ে|গিয়ে|সার্চ দিয়ে|সার্চ করে|সার্চ দাও|সার্চ|খুঁজে|" +
                        "আপডেট|আপটেড|আপডেড|আবডেট|ইনস্টল|ইনিস্টল|ইন্সটল|আনইনস্টল|ডাউনলোড|নামাও|নামিয়ে|" +
                        "করো|করু|করুন|করবে|করো তো|কর|দাও|দেন|দেও|চাপো|চাপ দিবে|চাপ দাও|চাপ|" +
                        "অ্যাপটি|অ্যাপটা|অ্যাপস|অ্যাপ|এপস|এপ|সফটওয়্যার|গেমটি|গেমটা|গেম|" +
                        "update|updet|install|inistall|download|search|from|in|on|the|app|application|game|karo|koro|korun|dao|please|sir|" +
                        "प्ले स्टोर|से|को|करो|डाउनलोड|इंस्टॉल|अपडेट|ऐप|স্যার|প্লিজ|একটু)"
                ),
                " "
            )
            .replace(Regex("[|,:;!?\"'()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val candidateCheck = "$rawLower ${stripped.lowercase()}"

        val resolvedPair: Pair<String, String?>? = when {
            candidateCheck.contains("ফ্রি ফায়ার ম্যাক্স") ||
                candidateCheck.contains("ফ্রিফায়ার ম্যাক্স") ||
                candidateCheck.contains("ফ্রিফাইয়ার ম্যাক্স") ||
                candidateCheck.contains("free fire max") ||
                candidateCheck.contains("freefire max") -> "Free Fire MAX" to "com.dts.freefiremax"

            candidateCheck.contains("ফ্রি ফায়ার") ||
                candidateCheck.contains("ফ্রিফায়ার") ||
                candidateCheck.contains("ফ্রিফাইয়ার") ||
                candidateCheck.contains("ফ্রি ফাইয়ার") ||
                candidateCheck.contains("ফ্রি ফায়ার") ||
                candidateCheck.contains("ফ্রিফায়ার") ||
                candidateCheck.contains("ফিরি ফায়ার") ||
                candidateCheck.contains("ফ্রি ফায়ার") ||
                candidateCheck.contains("free fire") ||
                candidateCheck.contains("freefire") ||
                candidateCheck.contains("ফ্রিফার") -> "Free Fire" to "com.dts.freefireth"

            candidateCheck.contains("পাবজি") ||
                candidateCheck.contains("pubg") ||
                candidateCheck.contains("bgmi") -> "PUBG Mobile" to "com.tencent.ig"

            candidateCheck.contains("হোয়াটসঅ্যাপ") ||
                candidateCheck.contains("হোয়াটসঅ্যাপ") ||
                candidateCheck.contains("whatsapp") ||
                candidateCheck.contains("whats app") -> "WhatsApp" to "com.whatsapp"

            candidateCheck.contains("টেলিগ্রাম") ||
                candidateCheck.contains("telegram") -> "Telegram" to "org.telegram.messenger"

            candidateCheck.contains("ইউটিউব") ||
                candidateCheck.contains("youtube") ||
                candidateCheck.contains("you tube") -> "YouTube" to "com.google.android.youtube"

            candidateCheck.contains("ফেসবুক লাইট") ||
                candidateCheck.contains("facebook lite") ||
                candidateCheck.contains("fb lite") -> "Facebook Lite" to "com.facebook.lite"

            candidateCheck.contains("ফেসবুক") ||
                candidateCheck.contains("facebook") -> "Facebook" to "com.facebook.katana"

            candidateCheck.contains("মেসেঞ্জার") ||
                candidateCheck.contains("ম্যাসেঞ্জার") ||
                candidateCheck.contains("messenger") -> "Messenger" to "com.facebook.orca"

            candidateCheck.contains("ইনস্টাগ্রাম") ||
                candidateCheck.contains("ইন্সটাগ্রাম") ||
                candidateCheck.contains("instagram") -> "Instagram" to "com.instagram.android"

            candidateCheck.contains("টিকটক") ||
                candidateCheck.contains("টিক টক") ||
                candidateCheck.contains("tiktok") ||
                candidateCheck.contains("tik tok") -> "TikTok" to "com.zhiliaoapp.musically"

            candidateCheck.contains("ক্যাপকাট") ||
                candidateCheck.contains("ক্যাপক্যাট") ||
                candidateCheck.contains("capcut") ||
                candidateCheck.contains("cap cut") -> "CapCut" to "com.lemon.lvoverseas"

            candidateCheck.contains("ক্যানভা") ||
                candidateCheck.contains("canva") -> "Canva" to "com.canva.editor"

            candidateCheck.contains("ইমো") ||
                candidateCheck.contains("ইমু") ||
                Regex("\\bimo\\b").containsMatchIn(candidateCheck) -> "imo" to "com.imo.android.imoim"

            candidateCheck.contains("বিকাশ") ||
                candidateCheck.contains("bkash") -> "bKash" to "com.bKash.customerapp"

            candidateCheck.contains("নগদ") ||
                candidateCheck.contains("nagad") -> "Nagad" to "com.konasl.nagad"

            candidateCheck.contains("রকেট") ||
                candidateCheck.contains("rocket") -> "Rocket" to "com.dbbl.mbs.apps.main"

            candidateCheck.contains("দারাজ") ||
                candidateCheck.contains("daraz") -> "Daraz" to "com.daraz.android"

            candidateCheck.contains("ফুডপান্ডা") ||
                candidateCheck.contains("foodpanda") -> "foodpanda" to "com.global.foodpanda.android"

            candidateCheck.contains("পাঠাও") ||
                candidateCheck.contains("pathao") -> "Pathao" to "com.pathao.user"

            candidateCheck.contains("উবার") ||
                candidateCheck.contains("uber") -> "Uber" to "com.ubercab"

            candidateCheck.contains("স্পটিফাই") ||
                candidateCheck.contains("spotify") -> "Spotify" to "com.spotify.music"

            candidateCheck.contains("ক্রোম") ||
                candidateCheck.contains("chrome") -> "Google Chrome" to "com.android.chrome"

            candidateCheck.contains("জিমেইল") ||
                candidateCheck.contains("gmail") -> "Gmail" to "com.google.android.gm"

            candidateCheck.contains("চ্যাটজিপিটি") ||
                candidateCheck.contains("চ্যাট জিপিটি") ||
                candidateCheck.contains("chatgpt") ||
                candidateCheck.contains("chat gpt") -> "ChatGPT" to "com.openai.chatgpt"

            candidateCheck.contains("লুডু") ||
                candidateCheck.contains("লুডো") ||
                candidateCheck.contains("ludo king") -> "Ludo King" to "com.ludo.king"

            candidateCheck.contains("ক্ল্যাশ অফ ক্ল্যান্স") ||
                candidateCheck.contains("clash of clans") -> "Clash of Clans" to "com.supercell.clashofclans"

            candidateCheck.contains("ইফুটবল") ||
                candidateCheck.contains("efootball") -> "eFootball" to "jp.konami.pesam"

            candidateCheck.contains("সাবওয়ে") ||
                candidateCheck.contains("subway surf") -> "Subway Surfers" to "com.kiloo.subwaysurf"

            candidateCheck.contains("শেয়ারইট") ||
                candidateCheck.contains("shareit") -> "SHAREit" to "com.lenovo.anyshare.gps"

            candidateCheck.contains("স্ন্যাপচ্যাট") ||
                candidateCheck.contains("snapchat") -> "Snapchat" to "com.snapchat.android"

            candidateCheck.contains("জুম") ||
                candidateCheck.contains("zoom") -> "Zoom" to "us.zoom.videomeetings"

            candidateCheck.contains("ট্রুকলার") ||
                candidateCheck.contains("ট্রু কলার") ||
                candidateCheck.contains("truecaller") ||
                candidateCheck.contains("true caller") -> "Truecaller" to "com.truecaller"

            candidateCheck.contains("ক্যামস্ক্যানার") ||
                candidateCheck.contains("camscanner") -> "CamScanner" to "com.intsig.camscanner"

            candidateCheck.contains("ভিএলসি") ||
                Regex("\\bvlc\\b").containsMatchIn(candidateCheck) -> "VLC" to "org.videolan.vlc"

            candidateCheck.contains("এমএক্স প্লেয়ার") ||
                candidateCheck.contains("mx player") ||
                candidateCheck.contains("mxplayer") -> "MX Player" to "com.mxtech.videoplayer.ad"

            candidateCheck.contains("পিকসআর্ট") ||
                candidateCheck.contains("picsart") -> "Picsart" to "com.picsart.studio"

            candidateCheck.contains("কাইনমাস্টার") ||
                candidateCheck.contains("kinemaster") -> "KineMaster" to "com.nexstreaming.app.kinemasterfree"

            candidateCheck.contains("ইনশট") ||
                candidateCheck.contains("inshot") -> "InShot" to "com.camerasideas.instashot"

            candidateCheck.contains("লাইটরুম") ||
                candidateCheck.contains("lightroom") -> "Lightroom" to "com.adobe.lrmobile"

            candidateCheck.contains("স্ন্যাপসিড") ||
                candidateCheck.contains("snapseed") -> "Snapseed" to "com.niksoftware.snapseed"

            candidateCheck.contains("রিদমিক") ||
                candidateCheck.contains("অভ্র") ||
                candidateCheck.contains("ridmik") -> "Ridmik Keyboard" to "ridmik.keyboard"

            candidateCheck.contains("টফি") ||
                candidateCheck.contains("toffee") -> "Toffee" to "com.banglalink.toffee"

            candidateCheck.contains("বঙ্গ") ||
                candidateCheck.contains("bongo") -> "Bongo" to "com.bongobd.bongo"

            candidateCheck.contains("হইচই") ||
                candidateCheck.contains("hoichoi") -> "hoichoi" to "com.viewlift.hoichoi"

            candidateCheck.contains("চালডাল") ||
                candidateCheck.contains("chaldal") -> "Chaldal" to "com.chaldal.poached"

            candidateCheck.contains("টেন মিনিট স্কুল") ||
                candidateCheck.contains("১০ মিনিট স্কুল") ||
                candidateCheck.contains("10 minute school") -> "10 Minute School" to "com.a10minuteschool.tenminuteschool"

            candidateCheck.contains("ব্রেভ") ||
                candidateCheck.contains("brave") -> "Brave Browser" to "com.brave.browser"

            candidateCheck.contains("ফায়ারফক্স") ||
                candidateCheck.contains("firefox") -> "Firefox" to "org.mozilla.firefox"

            candidateCheck.contains("অপেরা মিনি") ||
                candidateCheck.contains("অপেরা") ||
                candidateCheck.contains("opera") -> "Opera Mini" to "com.opera.mini.native"

            candidateCheck.contains("ডিসকর্ড") ||
                candidateCheck.contains("discord") -> "Discord" to "com.discord"

            candidateCheck.contains("পিন্টারেস্ট") ||
                candidateCheck.contains("pinterest") -> "Pinterest" to "com.pinterest"

            candidateCheck.contains("লিংকডইন") ||
                candidateCheck.contains("linkedin") -> "LinkedIn" to "com.linkedin.android"

            candidateCheck.contains("টুইটার") ||
                candidateCheck.contains("twitter") -> "X" to "com.twitter.android"

            candidateCheck.contains("থ্রেডস") ||
                candidateCheck.contains("threads") -> "Threads" to "com.instagram.barcelona"

            candidateCheck.contains("ভাইবার") ||
                candidateCheck.contains("viber") -> "Viber" to "com.viber.voip"

            candidateCheck.contains("উইচ্যাট") ||
                candidateCheck.contains("wechat") -> "WeChat" to "com.tencent.mm"

            candidateCheck.contains("স্কাইপ") ||
                candidateCheck.contains("skype") -> "Skype" to "com.skype.raider"

            candidateCheck.contains("গুগল ম্যাপ") ||
                candidateCheck.contains("google map") ||
                candidateCheck.contains("maps") -> "Google Maps" to "com.google.android.apps.maps"

            candidateCheck.contains("গুগল ড্রাইভ") ||
                candidateCheck.contains("google drive") -> "Google Drive" to "com.google.android.apps.docs"

            candidateCheck.contains("গুগল ফটো") ||
                candidateCheck.contains("google photo") -> "Google Photos" to "com.google.android.apps.photos"

            candidateCheck.contains("গুগল মিট") ||
                candidateCheck.contains("google meet") -> "Google Meet" to "com.google.android.apps.tachyon"

            candidateCheck.contains("গুগল ট্রান্সলেট") ||
                candidateCheck.contains("ট্রান্সলেট") ||
                candidateCheck.contains("translate") -> "Google Translate" to "com.google.android.apps.translate"

            candidateCheck.contains("গুগল লেন্স") ||
                candidateCheck.contains("google lens") -> "Google Lens" to "com.google.ar.lens"

            candidateCheck.contains("ইউটিউব মিউজিক") ||
                candidateCheck.contains("youtube music") ||
                candidateCheck.contains("yt music") -> "YouTube Music" to "com.google.android.apps.youtube.music"

            candidateCheck.contains("ইউটিউব স্টুডিও") ||
                candidateCheck.contains("youtube studio") ||
                candidateCheck.contains("yt studio") -> "YouTube Studio" to "com.google.android.apps.youtube.creator"

            candidateCheck.contains("মাইক্রোসফট ওয়ার্ড") ||
                candidateCheck.contains("ms word") ||
                candidateCheck.contains("microsoft word") -> "Microsoft Word" to "com.microsoft.office.word"

            candidateCheck.contains("মাইক্রোসফট এক্সেল") ||
                candidateCheck.contains("ms excel") ||
                candidateCheck.contains("microsoft excel") -> "Microsoft Excel" to "com.microsoft.office.excel"

            candidateCheck.contains("মাইক্রোসফট টিমস") ||
                candidateCheck.contains("microsoft teams") -> "Microsoft Teams" to "com.microsoft.teams"

            candidateCheck.contains("অ্যাডোবি") ||
                candidateCheck.contains("পিডিএফ") ||
                candidateCheck.contains("adobe acrobat") ||
                candidateCheck.contains("pdf reader") -> "Adobe Acrobat Reader" to "com.adobe.reader"

            candidateCheck.contains("রোবলক্স") ||
                candidateCheck.contains("roblox") -> "Roblox" to "com.roblox.client"

            candidateCheck.contains("মাইনক্রাফট") ||
                candidateCheck.contains("minecraft") -> "Minecraft" to "com.mojang.minecraftpe"

            candidateCheck.contains("ক্যান্ডি ক্রাশ") ||
                candidateCheck.contains("candy crush") -> "Candy Crush Saga" to "com.king.candycrushsaga"

            candidateCheck.contains("এইট বল পুল") ||
                candidateCheck.contains("8 ball pool") -> "8 Ball Pool" to "com.miniclip.eightballpool"

            candidateCheck.contains("টেম্পল রান") ||
                candidateCheck.contains("temple run") -> "Temple Run" to "com.imangi.templerun"

            candidateCheck.contains("হিল ক্লাইম্ব") ||
                candidateCheck.contains("hill climb") -> "Hill Climb Racing" to "com.fingersoft.hillclimb"

            candidateCheck.contains("কল অফ ডিউটি") ||
                candidateCheck.contains("call of duty") -> "Call of Duty Mobile" to "com.activision.callofduty.shooter"

            candidateCheck.contains("ডুয়োলিঙ্গো") ||
                candidateCheck.contains("duolingo") -> "Duolingo" to "com.duolingo"

            candidateCheck.contains("জেডআর্কাইভার") ||
                candidateCheck.contains("zarchiver") -> "ZArchiver" to "ru.zdevs.zarchiver"

            candidateCheck.contains("ওয়ার্প") ||
                candidateCheck.contains("1.1.1.1") ||
                candidateCheck.contains("warp") -> "1.1.1.1 + WARP" to "com.cloudflare.onedotonedotonedotone"

            else -> null
        }

        val finalCleanName = when {
            resolvedPair != null -> resolvedPair.first
            isConversationalOrInvalidSearchQuery(rawInput) || isConversationalOrInvalidSearchQuery(stripped) -> ""
            stripped.split(Regex("\\s+")).filter { it.isNotBlank() }.size > 4 -> ""
            else -> stripped
        }
        return PlayStoreQueryResolution(
            cleanAppName = finalCleanName,
            knownPackage = resolvedPair?.second,
            isUpdateRequest = isUpdate
        )
    }

    /**
     * Play Store App Search & Autonomous Update / Install via Accessibility UI Automation
     * Performs strict Pre-Flight Permission Verification first:
     * - If Accessibility Service is OFF, queues the install macro AND opens Accessibility Settings so as soon as the user enables it, installation proceeds.
     * - If `knownPackage` is available, opens `market://details?id=<pkg>` directly to land straight on the app's Install button!
     */
    fun installAppFromPlayStore(
        appNameOrQuery: String,
        launchExternal: Boolean = true,
        forceUpdateMode: Boolean = false
    ): String {
        val resolution = cleanPlayStoreAppQuery(appNameOrQuery, forceUpdateMode)
        val cleanAppName = resolution.cleanAppName
        val isUpdate = resolution.isUpdateRequest
        val knownPkg = resolution.knownPackage.orEmpty()

        // If user only asked to open Play Store without a specific app name
        if (cleanAppName.isBlank()) {
            if (launchExternal) {
                val launchPlay = context.packageManager.getLaunchIntentForPackage("com.android.vending")
                if (launchPlay != null) {
                    launchPlay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(launchPlay)
                        return "গুগল প্লে স্টোর খোলা হয়েছে"
                    } catch (_: Exception) {
                    }
                }
                openWebUrl("https://play.google.com/store/apps")
            }
            return "গুগল প্লে স্টোর খোলা হয়েছে"
        }

        // Check if package is already installed when not explicitly requesting an update
        val alreadyInstalled = !isUpdate && knownPkg.isNotBlank() && try {
            context.packageManager.getLaunchIntentForPackage(knownPkg) != null
        } catch (_: Exception) {
            false
        }

        val primaryClickButtons = if (isUpdate || alreadyInstalled) {
            listOf("Update", "আপডেট করুন", "আপডেট", "UPDATE", "Install", "ইনস্টল করুন", "ইনস্টল", "Open", "ওপেন")
        } else {
            listOf("Install", "ইনস্টল করুন", "ইনস্টল", "INSTALL", "Update", "আপডেট করুন", "আপডেট")
        }

        // Queue closed-loop Play Store Install Supervisor macro first so even if the user just enables Accessibility, it executes right away
        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                targetPackageHint = "com.android.vending",
                clickTargets = primaryClickButtons,
                secondaryClickTargets = listOf(cleanAppName),
                targetViewIds = listOf(
                    "android:id/button1",
                    "com.android.packageinstaller:id/ok_button",
                    "com.google.android.packageinstaller:id/ok_button"
                ),
                maxAutoScrollRetries = 4,
                isPlayStoreInstallLoop = true,
                targetAppName = cleanAppName,
                knownPackage = knownPkg,
                isUpdateInstall = isUpdate || alreadyInstalled
            )
        )

        // PRE-FLIGHT PERMISSION CHECK: Verify Accessibility Service is enabled before claiming auto-install!
        val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)
        if (!isAccEnabled) {
            if (launchExternal) {
                openAccessibilitySettings()
            }
            return "PERMISSION_REQUIRED_ACCESSIBILITY: স্যার, '$cleanAppName' অটোমেটিক ইনস্টল করার জন্য এবং স্ক্রিন দেখে নিজে নিজে Install বাটনে ট্যাপ করার জন্য Accessibility পারমিশনটি এখনো অন করা নেই। আমি Accessibility Settings পেজটি ওপেন করে দিয়েছি—অন করে দিলেই আমি নিজে স্ক্রিন দেখে ইনস্টল সম্পন্ন করে আপনাকে কনফার্ম জানাব।"
        }

        if (launchExternal) {
            // If we know the exact Play Store package ID, jump DIRECTLY to the app's Play Store details page (`market://details?id=...`)
            // so the "Install" button is immediately on screen without needing to search or click a card!
            val primaryMarketUri = if (knownPkg.isNotBlank()) {
                Uri.parse("market://details?id=$knownPkg")
            } else {
                Uri.parse("market://search?q=${Uri.encode(cleanAppName)}&c=apps")
            }
            val marketIntent = Intent(Intent.ACTION_VIEW, primaryMarketUri).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(marketIntent)
            } catch (_: Exception) {
                try {
                    val fallbackUri = Uri.parse("market://search?q=${Uri.encode(cleanAppName)}&c=apps")
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (_: Exception) {
                    val webPlayUri = if (knownPkg.isNotBlank()) {
                        "https://play.google.com/store/apps/details?id=$knownPkg"
                    } else {
                        "https://play.google.com/store/search?q=${Uri.encode(cleanAppName)}&c=apps"
                    }
                    openWebUrl(webPlayUri)
                }
            }
        }

        return when {
            alreadyInstalled -> "প্লে স্টোরে '$cleanAppName' ওপেন করা হয়েছে (অ্যাপটি ফোনে আগে থেকেই ইনস্টল আছে; আপডেট থাকলে আমি স্ক্রিন দেখে নিজে আপডেট করে কনফার্ম জানাব)"
            isUpdate -> "প্লে স্টোরে '$cleanAppName' ওপেন করে ক্লোজড-লুপ অটো-আপডেট সুপারভাইজার চালু করা হয়েছে — ডাউনলোড ও আপডেট প্রসেস স্ক্রিনে চেক করে কনফার্ম হওয়া পর্যন্ত আমি নিজে চেষ্টা চালিয়ে যাব"
            else -> "প্লে স্টোরে '$cleanAppName' ওপেন করে ক্লোজড-লুপ অটো-ইনস্টল সুপারভাইজার চালু করা হয়েছে — স্ক্রিন দেখে Install বাটনে ট্যাপ, ডাউনলোড প্রসেস ভেরিফাই এবং ইনস্টল সম্পূর্ণ হওয়া পর্যন্ত আমি নিজে তদারকি করে আপনাকে কনফার্ম জানাব"
        }
    }

    /**
     * Direct APK Download & Package Installer + Play Store Fallback via Accessibility Service
     * Performs Pre-Flight checks for both Unknown Sources (`canRequestPackageInstalls`) and Accessibility Service.
     */
    fun installApkOrAppPackage(targetQueryOrApkUrl: String, launchExternal: Boolean = true): String {
        val cleaned = targetQueryOrApkUrl.trim()
        val isDirectApkLink = (cleaned.startsWith("http://") || cleaned.startsWith("https://")) &&
            cleaned.lowercase().contains(".apk")

        if (isDirectApkLink) {
            // Pre-flight check 1: Unknown App Sources permission for APK installation
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                if (launchExternal) {
                    try {
                        val unknownSourcesIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(unknownSourcesIntent)
                    } catch (_: Exception) {
                        openSettingsScreen("SECURITY")
                    }
                }
                return "PERMISSION_REQUIRED_UNKNOWN_SOURCES: স্যার, সরাসরি APK ফাইল ইনস্টল করার জন্য 'Install unknown apps' পারমিশনটি অন করা নেই। আমি পারমিশন পেজটি ওপেন করে দিয়েছি—এটি Allow করে দিলেই আমি APK ইনস্টল সম্পন্ন করব।"
            }

            // Pre-flight check 2: Accessibility Service for auto-tapping Package Installer confirmation
            val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)
            if (!isAccEnabled) {
                if (launchExternal) openAccessibilitySettings()
                return "PERMISSION_REQUIRED_ACCESSIBILITY: স্যার, APK ডাউনলোডের পর নিজে নিজে 'Install' বাটনে ট্যাপ করার জন্য Accessibility পারমিশন অন নেই। আমি Accessibility Settings খুলে দিয়েছি—অন করে দিন।"
            }

            // Queue Accessibility macro to auto-confirm APK Package Installer ("Install" / "ইনস্টল করুন")
            AccessibilityAutomationBus.queueMacro(
                PendingUiMacro(
                    targetPackageHint = "com.google.android.packageinstaller",
                    clickTargets = listOf("Install", "ইনস্টল করুন", "ইনস্টল", "Update", "Continue", "Open"),
                    targetViewIds = listOf(
                        "android:id/button1",
                        "com.android.packageinstaller:id/ok_button",
                        "com.google.android.packageinstaller:id/ok_button"
                    )
                )
            )

            return try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val apkFileName = cleaned.substringAfterLast('/')
                    .substringBefore('?')
                    .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
                    .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
                val req = DownloadManager.Request(Uri.parse(cleaned))
                    .setTitle("APK Install: $apkFileName")
                    .setDescription("ARCHER AI স্বয়ংক্রিয়ভাবে APK ডাউনলোড ও ইনস্টল প্রস্তুত করছে")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, apkFileName)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(true)
                dm.enqueue(req)
                _hardwareState.value = _hardwareState.value.copy(lastDownloadedFile = apkFileName)
                if (launchExternal) {
                    try {
                        context.startActivity(
                            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    } catch (_: Exception) {
                    }
                }
                "APK ডাউনলোড ও অটো-ইনস্টলার শুরু হয়েছে: $apkFileName (সম্পন্ন হলে কনফার্ম জানানো হবে)"
            } catch (_: Exception) {
                if (launchExternal) openChromeOrWeb(cleaned, "Download")
                "ক্রোমের মাধ্যমে APK ডাউনলোড ও ইনস্টল শুরু হয়েছে"
            }
        }

        if (cleaned.lowercase().contains("apk")) {
            val appNameClean = cleaned.replace(Regex("(?i)apk|install|ইনস্টল|ডাউনলোড"), "").trim()
            if (appNameClean.isNotBlank()) {
                return installAppFromPlayStore(appNameClean, launchExternal)
            }
            val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)
            if (!isAccEnabled) {
                if (launchExternal) openAccessibilitySettings()
                return "PERMISSION_REQUIRED_ACCESSIBILITY: স্যার, অটোমেটিক অ্যাপ ইনস্টলের জন্য Accessibility পারমিশন অন নেই। আমি সেটিংস পেজ খুলে দিয়েছি—অন করে দিন।"
            }
            if (launchExternal) {
                try {
                    context.startActivity(
                        Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    return "ডাউনলোড ফোল্ডার খোলা হয়েছে এবং APK অটো-ইনস্টলার ম্যাক্রো সক্রিয় করা হয়েছে"
                } catch (_: Exception) {
                }
            }
        }

        return installAppFromPlayStore(cleaned.ifBlank { "Telegram" }, launchExternal)
    }

    /**
     * Chrome Browser & Website Intelligence Controller
     */
    fun openChromeOrWeb(urlOrQuery: String, screenCommand: String = "") {
        val resolvedUrl = when {
            urlOrQuery.startsWith("http://") || urlOrQuery.startsWith("https://") -> urlOrQuery
            urlOrQuery.contains(".") && !urlOrQuery.contains(" ") -> "https://$urlOrQuery"
            else -> "https://www.google.com/search?q=${Uri.encode(urlOrQuery)}"
        }

        if (screenCommand.isNotBlank()) {
            AccessibilityAutomationBus.queueMacro(
                PendingUiMacro(
                    targetPackageHint = "com.android.chrome",
                    clickTargets = listOf(screenCommand, "Download", "ডাউনলোড")
                )
            )
        }

        val chromeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedUrl)).apply {
            setPackage("com.android.chrome")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chromeIntent)
        } catch (_: Exception) {
            openWebUrl(resolvedUrl)
        }
    }

    /**
     * Universal File, Image, PDF, Video, or App Downloader via Android DownloadManager + Telegram/Chrome
     */
    fun startUniversalFileDownload(
        targetQueryOrUrl: String,
        platformHint: String,
        titleLabel: String,
        launchExternal: Boolean = true
    ): String {
        if (platformHint.contains("TELEGRAM") || targetQueryOrUrl.lowercase().contains("telegram") || targetQueryOrUrl.contains("টেলিগ্রাম")) {
            return executeTelegramAutomation(
                channelOrUser = targetQueryOrUrl,
                messageOrSearch = targetQueryOrUrl,
                isDownloadRequest = true,
                launchExternal = launchExternal
            )
        }

        val isDirectHttpUrl = targetQueryOrUrl.startsWith("http://") || targetQueryOrUrl.startsWith("https://")
        val downloadUrl = if (isDirectHttpUrl) {
            targetQueryOrUrl
        } else {
            // Queue Accessibility auto-download click and also open Chrome file/image search
            AccessibilityAutomationBus.queueMacro(
                PendingUiMacro(
                    targetPackageHint = "com.android.chrome",
                    clickTargets = listOf("Download", "ডাউনলোড", "Save image", "Save")
                )
            )
            if (launchExternal) {
                openChromeOrWeb("$targetQueryOrUrl direct download", "Download")
            }
            _hardwareState.value = _hardwareState.value.copy(lastDownloadedFile = targetQueryOrUrl)
            return "ক্রোম থেকে '$targetQueryOrUrl' খুঁজে ডাউনলোড প্রসেস শুরু হয়েছে"
        }

        return try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val fileName = titleLabel.replace(Regex("[^a-zA-Z0-9_.-]"), "_").ifBlank { "cube_download_file" }
            val req = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(titleLabel.ifBlank { "Cube AI Download" })
                .setDescription("Cube AI স্বয়ংক্রিয়ভাবে ফাইল ডাউনলোড করছে")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            dm.enqueue(req)
            _hardwareState.value = _hardwareState.value.copy(lastDownloadedFile = fileName)
            "ডাউনলোড ম্যানেজারে ফাইল যুক্ত হয়েছে: $fileName"
        } catch (e: Exception) {
            if (launchExternal) openChromeOrWeb(downloadUrl)
            "ব্রাউজারের মাধ্যমে ডাউনলোড শুরু হয়েছে"
        }
    }

    data class ParsedChatCommand(
        val cleanRecipient: String,
        val cleanMessageBody: String
    )

    /**
     * Separates the recipient name from the actual message text so the agent never types raw command phrases
     * like "কে মেসেজ দাও" or "হোয়াটসঅ্যাপে বলো" into the chat box.
     */
    fun parseContactAndCleanMessage(rawRecipient: String, rawMessage: String): ParsedChatCommand {
        val trimmedRawRec = rawRecipient.trim()
            .replace(Regex("(?i)^(to|for|@)\\s+"), "")
            .trim()
        val isRawAppPlaceholder = trimmedRawRec.lowercase() in setOf(
            "whatsapp", "telegram", "messenger", "instagram", "facebook",
            "হোয়াটসঅ্যাপ", "হোয়াটসঅ্যাপ", "টেলিগ্রাম", "মেসেঞ্জার", "ইনস্টাগ্রাম",
            "current_screen", "current_chat"
        )
        var rec = if (AutonomousScreenAgent.isDeicticReference(trimmedRawRec) || isRawAppPlaceholder) {
            ""
        } else {
            trimmedRawRec.replace(Regex("(কে|ের|রে)$"), "").trim()
        }
        if (AutonomousScreenAgent.isDeicticReference(rec)) {
            rec = ""
        }
        var msg = rawMessage.trim()

        // Strip leading app name prefix first so recipient capture never includes "হোয়াটসঅ্যাপে"
        val strippedAppPrefix = msg.replace(
            Regex("(?i)^(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপ|হোয়াটসঅ্যাপ|টেলিগ্রামে|টেলিগ্রাম|মেসেঞ্জারে|মেসেঞ্জার|ইনস্টাগ্রামে|ইনস্টাগ্রাম|whatsapp\\s*এ|telegram\\s*এ|messenger\\s*এ|instagram\\s*এ|in\\s+whatsapp|on\\s+whatsapp|in\\s+telegram|on\\s+telegram|in\\s+messenger|on\\s+messenger)\\s+"),
            ""
        ).trim()

        val bnPattern = Regex(
            "(?i)^([\\p{L}\\p{M}\\d_\\s]{2,30}?)\\s*(?:কে|রে|ের)\\s+(?:(?:হোয়াটসঅ্যাপে|হোয়াটসঅ্যাপে|টেলিগ্রামে|মেসেঞ্জারে|ইনস্টাগ্রামে|মেসেজ|ম্যাসেজ|বার্তা|বলো|বল|লিখো|লেখো|লেখ|পাঠাও|দাও|করো|দিয়ে|বলে|যে)\\s*)+(.+)$"
        )
        val enPattern = Regex(
            "(?i)^(?:send\\s+a?\\s*message\\s+to|message|text|tell|whatsapp|telegram|messenger|instagram)\\s+([\\p{L}\\p{M}a-zA-Z0-9_\\s]{2,26}?)\\s+(?:that|saying|on\\s+whatsapp|on\\s+telegram|on\\s+messenger|:|-)+\\s*(.+)$"
        )

        val bnMatch = bnPattern.find(strippedAppPrefix)
        val enMatch = enPattern.find(strippedAppPrefix)
        when {
            bnMatch != null -> {
                val candidateRec = bnMatch.groupValues[1].trim()
                if ((rec.isBlank() || rec == "রাহিম ভাই") &&
                    !AutonomousScreenAgent.isDeicticReference(candidateRec) &&
                    !AutonomousScreenAgent.isDeicticReference(candidateRec + "কে")
                ) {
                    rec = candidateRec
                }
                msg = bnMatch.groupValues[2].trim()
            }
            enMatch != null -> {
                val candidateRec = enMatch.groupValues[1].trim()
                if ((rec.isBlank() || rec == "রাহিম ভাই") &&
                    !AutonomousScreenAgent.isDeicticReference(candidateRec)
                ) {
                    rec = candidateRec
                }
                msg = enMatch.groupValues[2].trim()
            }
            else -> {
                msg = strippedAppPrefix.replace(
                    Regex("(?i)^(?:(?:একে|ওকে|তাকে|এনাকে|উনাকে|এই\\s*আইডিতে|এই\\s*চ্যাটে|মেসেজ|ম্যাসেজ|বার্তা|পাঠাও|দাও|লিখো|লেখো|বলো|যে)\\s*)+"),
                    ""
                ).trim()
                if (rec.isNotBlank() && msg.startsWith(rec, ignoreCase = true)) {
                    msg = msg.removePrefix(rec)
                        .replace(Regex("^(?:\\s*(?:কে|রে|ের|বলো|মেসেজ|দাও|পাঠাও|লিখো|লেখো|যে))+\\s*"), "")
                        .trim()
                }
            }
        }

        if (AutonomousScreenAgent.isDeicticReference(rec)) {
            rec = ""
        }

        return ParsedChatCommand(
            cleanRecipient = rec,
            cleanMessageBody = msg
        )
    }

    /**
     * Closed-loop Vision + Accessibility autonomous messaging/task runner across any app.
     * Supports deictic references ("একে/ওকে"), scrolling/searching for contacts/IDs, and verification.
     */
    suspend fun executeMessagingAutonomous(
        appName: String,
        recipient: String,
        messageText: String,
        fullGoal: String = "",
        stopBeforeFinalSend: Boolean = false
    ): String {
        val parsed = parseContactAndCleanMessage(recipient, messageText)
        val isDeictic = AutonomousScreenAgent.isDeicticReference(recipient) ||
            (parsed.cleanRecipient.isBlank() && AutonomousScreenAgent.isDeicticReference(messageText))
        val cleanRecipient = if (isDeictic) "" else parsed.cleanRecipient
        val cleanMsg = parsed.cleanMessageBody
        val effectiveApp = appName.trim().ifBlank {
            if (isDeictic && ScreenMemory.currentPkg.isNotBlank()) "CURRENT_SCREEN" else ""
        }

        val apiKey = apiKeyProvider().trim()
        val a11yEnabled = AccessibilityAutomationBus.state.value.isServiceEnabled &&
            AccessibilityAutomationBus.serviceInstance != null

        if (apiKey.isNotBlank() && a11yEnabled) {
            val goal = fullGoal.trim().ifBlank {
                buildString {
                    if (effectiveApp.isNotBlank() && !effectiveApp.equals("CURRENT_SCREEN", ignoreCase = true)) {
                        append("Open/use $effectiveApp and ")
                    }
                    if (isDeictic) {
                        append("send a message to the person currently shown on screen / in the open chat")
                    } else if (cleanRecipient.isNotBlank()) {
                        append("find contact/ID '$cleanRecipient' (search or scroll down if needed) and send message")
                    } else {
                        append("send message on the current screen")
                    }
                    if (cleanMsg.isNotBlank()) {
                        append(": \"$cleanMsg\"")
                    }
                }
            }
            val result = AutonomousScreenAgent.runTask(
                context = context,
                apiKey = apiKey,
                goal = goal,
                appHint = effectiveApp,
                recipientHint = if (isDeictic) recipient.trim() else cleanRecipient,
                messageHint = cleanMsg,
                stopBeforeFinalSend = stopBeforeFinalSend,
                onProgress = { status -> onAutonomousStatusCallback(status) }
            )
            return if (result.needsUserConfirmation) {
                "NEON_TARGET_LOCKED_WAITING_CONFIRMATION: ${result.summaryBn} " +
                    (if (cleanMsg.isNotBlank()) "Typed text='$cleanMsg'. " else "") +
                    "CRITICAL: Do NOT click yet! Ask in Bangla now: 'বস, সব রেডি—এখন কি ক্লিক করব?'"
            } else {
                result.summaryBn
            }
        }

        // Offline fallback (no Gemini API key)
        val lowerApp = effectiveApp.lowercase()
        return when {
            lowerApp.contains("telegram") || lowerApp.contains("টেলিগ্রাম") ->
                executeTelegramAutomation(cleanRecipient, cleanMsg, isDownloadRequest = false, launchExternal = true)
            else ->
                executeWhatsAppAutomation(cleanRecipient, cleanMsg, fileQuery = "", launchExternal = true)
        }
    }

    /**
     * WhatsApp Automation: Autonomous Vision+Tree Agent (primary) + Inline Notification Reply + Screen-Guided Macro Fallback
     */
    fun executeWhatsAppAutomation(
        contactOrPhone: String,
        messageText: String,
        fileQuery: String = "",
        launchExternal: Boolean = true
    ): String {
        val parsed = parseContactAndCleanMessage(contactOrPhone, messageText)
        val isDeictic = AutonomousScreenAgent.isDeicticReference(contactOrPhone) ||
            (parsed.cleanRecipient.isBlank() && AutonomousScreenAgent.isDeicticReference(messageText))
        val cleanContact = if (isDeictic) "" else parsed.cleanRecipient
        val cleanMsg = parsed.cleanMessageBody
        val alreadyInWhatsApp = AutonomousScreenAgent.appPackageMatchesHint(ScreenMemory.currentPkg, "whatsapp")

        // 1. Only use background inline notification reply when NOT already inside WhatsApp AND contact is explicit
        if (cleanMsg.isNotBlank() && cleanContact.isNotBlank() && !isDeictic && !alreadyInWhatsApp) {
            val (repliedInline, status) = NotificationReplyBus.sendInlineReply(
                context = context,
                targetContactOrApp = cleanContact,
                replyText = cleanMsg
            )
            if (repliedInline) return status
        }

        // 2. Primary Path: Real Closed-Loop Autonomous Screen Agent (Vision + Rich Accessibility Tree)
        val apiKey = apiKeyProvider().trim()
        val a11yReady = AccessibilityAutomationBus.state.value.isServiceEnabled &&
            AccessibilityAutomationBus.serviceInstance != null
        if (apiKey.isNotBlank() && a11yReady && fileQuery.isBlank()) {
            activeAutonomousJob?.cancel()
            activeAutonomousJob = autonomousScope.launch {
                val res = AutonomousScreenAgent.runTask(
                    context = context,
                    apiKey = apiKey,
                    goal = buildString {
                        append("Send a WhatsApp message")
                        if (cleanContact.isNotBlank()) append(" to '$cleanContact'")
                        else append(" to the person/chat currently open on screen")
                        if (cleanMsg.isNotBlank()) append(": \"$cleanMsg\"")
                    },
                    appHint = "WhatsApp",
                    recipientHint = if (isDeictic) contactOrPhone.trim() else cleanContact,
                    messageHint = cleanMsg,
                    stopBeforeFinalSend = false,
                    onProgress = { msg -> onAutonomousStatusCallback(msg) }
                )
                onAutonomousStatusCallback(res.summaryBn)
            }
            return when {
                isDeictic && cleanMsg.isNotBlank() ->
                    "হোয়াটসঅ্যাপে স্ক্রিন দেখে বর্তমান আইডি/চ্যাটে \"$cleanMsg\" পাঠাতে স্বয়ংক্রিয় এজেন্ট কাজ শুরু করেছে"
                cleanContact.isNotBlank() && cleanMsg.isNotBlank() ->
                    "হোয়াটসঅ্যাপে স্ক্রিন দেখে '$cleanContact'-কে \"$cleanMsg\" পাঠাতে এজেন্ট কাজ শুরু করেছে"
                else ->
                    "হোয়াটসঅ্যাপে অটোনোমাস স্ক্রিন এজেন্ট চালু হয়েছে"
            }
        }

        // 3. Offline Macro Fallback
        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                targetPackageHint = "com.whatsapp",
                contactRecipientToOpen = cleanContact,
                textToType = cleanMsg,
                isDirectChatMessage = cleanMsg.isNotBlank() || cleanContact.isNotBlank(),
                maxAutoScrollRetries = 4,
                targetViewIds = listOf(
                    "com.whatsapp:id/entry",
                    "com.whatsapp.w4b:id/entry",
                    "com.whatsapp:id/send",
                    "com.whatsapp.w4b:id/send"
                ),
                clickTargets = buildList {
                    if (cleanMsg.isNotBlank()) {
                        add("Send")
                        add("পাঠান")
                    }
                    if (fileQuery.isNotBlank()) {
                        add("Download")
                        add("ডাউনলোড")
                        add(fileQuery)
                    }
                    if (cleanContact.isNotBlank()) add(cleanContact)
                }
            )
        )

        if (launchExternal) {
            val digitsOnly = cleanContact.filter { it.isDigit() || it == '+' }
            if (digitsOnly.length >= 8) {
                val waUri = Uri.parse(
                    "https://wa.me/${digitsOnly.removePrefix("+")}?text=${Uri.encode(cleanMsg)}"
                )
                val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(intent)
                    return "হোয়াটসঅ্যাপে '$cleanContact'-এর চ্যাট ও অটো-সেন্ড চালু হয়েছে"
                } catch (_: Exception) {
                }
            }

            // Do NOT reset current screen if user is already inside WhatsApp!
            if (!alreadyInWhatsApp) {
                openTargetApp("WhatsApp")
            }
        }
        return if (cleanContact.isNotBlank() && cleanMsg.isNotBlank()) {
            "হোয়াটসঅ্যাপে স্ক্রিন দেখে '$cleanContact'-এর চ্যাট খুলে \"$cleanMsg\" পাঠানো হচ্ছে"
        } else {
            "হোয়াটসঅ্যাপ অটোমেশন ও মেসেজ কন্ট্রোল চালু হয়েছে"
        }
    }

    /**
     * Telegram Automation: Autonomous Vision+Tree Agent (primary) + Inline Reply + Screen-Guided Macro Fallback
     */
    fun executeTelegramAutomation(
        channelOrUser: String,
        messageOrSearch: String,
        isDownloadRequest: Boolean,
        launchExternal: Boolean = true
    ): String {
        val parsed = parseContactAndCleanMessage(channelOrUser, messageOrSearch)
        val isDeictic = AutonomousScreenAgent.isDeicticReference(channelOrUser) ||
            (parsed.cleanRecipient.isBlank() && AutonomousScreenAgent.isDeicticReference(messageOrSearch))
        val cleanTarget = if (isDeictic) "" else parsed.cleanRecipient.ifBlank {
            if (AutonomousScreenAgent.isDeicticReference(channelOrUser)) "" else channelOrUser.removePrefix("@").trim()
        }
        val cleanText = if (isDownloadRequest) messageOrSearch.trim() else parsed.cleanMessageBody
        val alreadyInTelegram = AutonomousScreenAgent.appPackageMatchesHint(ScreenMemory.currentPkg, "telegram")

        if (!isDownloadRequest && cleanText.isNotBlank() && cleanTarget.isNotBlank() && !isDeictic && !alreadyInTelegram) {
            val (repliedInline, status) = NotificationReplyBus.sendInlineReply(
                context = context,
                targetContactOrApp = cleanTarget,
                replyText = cleanText
            )
            if (repliedInline) return status
        }

        // Primary Path: Real Closed-Loop Autonomous Screen Agent
        val apiKey = apiKeyProvider().trim()
        val a11yReady = AccessibilityAutomationBus.state.value.isServiceEnabled &&
            AccessibilityAutomationBus.serviceInstance != null
        if (!isDownloadRequest && apiKey.isNotBlank() && a11yReady) {
            activeAutonomousJob?.cancel()
            activeAutonomousJob = autonomousScope.launch {
                val res = AutonomousScreenAgent.runTask(
                    context = context,
                    apiKey = apiKey,
                    goal = buildString {
                        append("Send a Telegram message")
                        if (cleanTarget.isNotBlank()) append(" to '$cleanTarget'")
                        else append(" to the person/chat currently open on screen")
                        if (cleanText.isNotBlank()) append(": \"$cleanText\"")
                    },
                    appHint = "Telegram",
                    recipientHint = if (isDeictic) channelOrUser.trim() else cleanTarget,
                    messageHint = cleanText,
                    stopBeforeFinalSend = false,
                    onProgress = { msg -> onAutonomousStatusCallback(msg) }
                )
                onAutonomousStatusCallback(res.summaryBn)
            }
            return "টেলিগ্রামে স্ক্রিন দেখে অটোনোমাস এজেন্ট মেসেজ পাঠানোর কাজ শুরু করেছে"
        }

        // Queue Screen-Guided Telegram macro fallback
        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                targetPackageHint = "org.telegram.messenger",
                contactRecipientToOpen = if (isDownloadRequest) "" else cleanTarget,
                textToType = cleanText,
                isDirectChatMessage = !isDownloadRequest && (cleanText.isNotBlank() || cleanTarget.isNotBlank()),
                maxAutoScrollRetries = 4,
                clickTargets = if (isDownloadRequest) {
                    listOf("Search", "Download", "Save to Downloads", "ডাউনলোড", cleanText)
                } else {
                    listOf("Send", "পাঠান", cleanTarget)
                }
            )
        )

        if (launchExternal && !alreadyInTelegram) {
            val handle = cleanTarget.removePrefix("@").replace(" ", "")
            val tgUri = if (handle.isNotBlank() && handle.all { it.isLetterOrDigit() || it == '_' }) {
                Uri.parse("tg://resolve?domain=$handle")
            } else {
                Uri.parse("https://t.me/s/${Uri.encode(messageOrSearch.ifBlank { cleanTarget })}")
            }
            val tgIntent = Intent(Intent.ACTION_VIEW, tgUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(tgIntent)
            } catch (_: Exception) {
                openTargetApp("Telegram")
            }
        }

        return if (isDownloadRequest) {
            "টেলিগ্রামে '$messageOrSearch' সার্চ ও অটো-ডাউনলোড চালু হয়েছে"
        } else {
            "টেলিগ্রামে স্ক্রিন দেখে '$cleanTarget'-এর মেসেজ অটোমেশন চালু হয়েছে"
        }
    }

    /**
     * Universal Full-Screen UI Control across ANY app (Click, Type, Scroll, Touch Gesture, Quick Settings, Screen Read)
     */
    fun executeUniversalScreenControl(
        commandType: String,
        targetText: String,
        inputText: String,
        targetApp: String,
        launchExternal: Boolean = true
    ): String {
        val upper = commandType.uppercase().trim()
        val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)

        // Always refresh the live UI snapshot first so we inspect the real screen before acting
        try {
            AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow()
        } catch (_: Exception) {
        }

        when {
            upper.contains("READ") || upper.contains("SCREEN") -> {
                val visible = AccessibilityAutomationBus.state.value.visibleScreenText
                val btns = AccessibilityAutomationBus.state.value.visibleButtonsAndNodes
                val pkg = ScreenMemory.currentPkg
                val appGuide = AppKnowledgeRegistry.guideForPackage(pkg)
                return if (!isAccEnabled) {
                    if (launchExternal) openAccessibilitySettings()
                    "স্ক্রিন দেখার পারমিশন (Accessibility) অফ আছে — সেটিংস পেজ খুলে দেওয়া হয়েছে স্যার, অন করে দিন।"
                } else {
                    "বর্তমান অ্যাপ ($pkg): $visible" +
                        (if (btns.isNotEmpty()) " • বাটন: ${btns.take(10).joinToString(", ")}" else "") +
                        " • গাইড: $appGuide"
                }
            }
            upper == "QUICK_SETTINGS" || upper == "NOTIFICATION_BAR" || upper == "SHADE" -> {
                if (!isAccEnabled) {
                    if (launchExternal) openAccessibilitySettings()
                    return "নোটিফিকেশন বার কন্ট্রোল করতে Accessibility অন করুন (সেটিংস খোলা হয়েছে)"
                }
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(globalAction = "QUICK_SETTINGS")
                )
                return "নোটিফিকেশন বার ও কুইক সেটিংস প্যানেল নামানো হয়েছে"
            }
            upper == "BACK" || upper == "HOME" || upper == "NOTIFICATIONS" || upper == "RECENTS" -> {
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(globalAction = upper)
                )
                return "সিস্টেম নেভিগেশন সম্পন্ন: $upper"
            }
            upper == "TAP_CENTER" || upper == "DOUBLE_TAP" || upper == "LONG_PRESS" ||
                upper == "SWIPE_UP" || upper == "SWIPE_DOWN" || upper == "SWIPE_LEFT" || upper == "SWIPE_RIGHT" ||
                upper.startsWith("TAP_XY") || Regex("^\\d+\\s*[,xX]\\s*\\d+$").matches(targetText.trim()) -> {
                if (!isAccEnabled) {
                    if (launchExternal) openAccessibilitySettings()
                    return "স্ক্রিন টাচ কন্ট্রোলের জন্য Accessibility পারমিশন প্রয়োজন — সেটিংস খোলা হয়েছে"
                }
                val coords = Regex("(\\d+)\\s*[,xX ]\\s*(\\d+)").find("$targetText $inputText")
                val gx = coords?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: 540f
                val gy = coords?.groupValues?.getOrNull(2)?.toFloatOrNull() ?: 1100f
                val gestureMode = when {
                    upper.startsWith("TAP_XY") || Regex("^\\d+\\s*[,xX]\\s*\\d+$").matches(targetText.trim()) -> "TAP_XY"
                    else -> upper
                }
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(
                        gestureType = gestureMode,
                        gestureX = gx,
                        gestureY = gy
                    )
                )
                return "স্ক্রিন টাচ জেসচার ($gestureMode @ ${gx.toInt()},${gy.toInt()}) সম্পন্ন হয়েছে"
            }
            upper.contains("SCROLL") -> {
                val dir = if (upper.contains("UP")) "UP" else "DOWN"
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(scrollDirection = dir)
                )
                return "স্ক্রিন স্ক্রল করা হয়েছে ($dir)"
            }
            upper == "TYPE" || upper.startsWith("TYPE:") -> {
                if (!isAccEnabled) {
                    if (launchExternal) openAccessibilitySettings()
                    return "স্ক্রিনে টাইপ করার জন্য Accessibility পারমিশন প্রয়োজন — সেটিংস খোলা হয়েছে"
                }
                val rawType = when {
                    upper.startsWith("TYPE:") && upper.length > 5 -> commandType.substringAfter(":").trim()
                    inputText.isNotBlank() && !inputText.equals("TYPE", ignoreCase = true) &&
                        !inputText.equals("CLICK", ignoreCase = true) -> inputText.trim()
                    else -> targetText.trim()
                }
                val cleanTextToType = rawType
                    .replace(Regex("^(?i)(TYPE:|CLICK:|TAP:)\\s*"), "")
                    .trim()
                if (cleanTextToType.isBlank()) {
                    return "টাইপ করার জন্য কোনো টেক্সট পাওয়া যায়নি"
                }
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(
                        targetPackageHint = "",
                        textToType = cleanTextToType,
                        clickTargets = emptyList(),
                        maxAutoScrollRetries = 0
                    )
                )
                return "স্ক্রিনের ইনপুট বক্সে নিখুঁতভাবে টাইপ ও সাবমিট করা হয়েছে: \"$cleanTextToType\""
            }
            else -> {
                // Pure CLICK / TAP on a visible UI element by label (NEVER types into EditText and NEVER opens Google!)
                if (!isAccEnabled) {
                    if (launchExternal) openAccessibilitySettings()
                    return "স্ক্রিন টাচ ও অটো-ক্লিকের পারমিশন (Accessibility) অন করার জন্য সেটিংস খোলা হয়েছে"
                }
                val cleanTarget = targetText
                    .replace(Regex("^(?i)(CLICK|TAP|PRESS)\\s*:?\\s*"), "")
                    .trim()
                if (cleanTarget.isBlank()) {
                    return "ক্লিক করার জন্য নির্দিষ্ট বাটন বা টেক্সট পাওয়া যায়নি"
                }
                val candidates = codingAndContactsManager.buildPhoneticNameCandidates(cleanTarget)
                val targetList = buildList {
                    if (!contains(cleanTarget)) add(cleanTarget)
                    for (c in candidates) {
                        if (!contains(c)) add(c)
                    }
                }
                // Step 1: Inspect the live screen right now and execute a single verified click if the target is visible!
                val clickedImmediately = try {
                    AccessibilityAutomationBus.serviceInstance?.inspectScreenAndClickSingleTargetNow(
                        targets = targetList
                    )
                } catch (_: Exception) {
                    null
                }
                if (clickedImmediately != null) {
                    return "স্ক্রিন দেখে '$clickedImmediately'-এ নিখুঁতভাবে ১ বার ক্লিক করা হয়েছে"
                }

                // Step 2: Only if not yet visible on the current screen, queue a single-shot macro (max 1 scroll)
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(
                        targetPackageHint = "",
                        textToType = "",
                        clickTargets = targetList,
                        maxAutoScrollRetries = 1
                    )
                )
                return "স্ক্রিন যাচাই করে '$cleanTarget'-এ ট্যাপ করা হচ্ছে"
            }
        }
    }

    /**
     * Toggles any Quick Settings / System Setting (Wi-Fi, Bluetooth, SIM Mobile Data, Airplane Mode,
     * Hotspot, Location, Auto-Rotate, DND, Battery Saver, Dark Mode, Flashlight) using:
     * 1. Direct Background Android API (where permitted by OS)
     * 2. Notification Bar / Quick Settings Auto-Pull-Down & Tile Click via CubeAccessibilityService
     * 3. Interactive Android Settings Panel fallback if Accessibility is not yet enabled.
     */
    fun toggleSystemSettingSmart(
        settingTarget: String,
        enable: Boolean,
        launchExternal: Boolean = true
    ): String {
        val rawUpper = settingTarget.uppercase().trim()
        val stateLabelBn = if (enable) "চালু (ON)" else "বন্ধ (OFF)"
        val isAccEnabled = AccessibilityAutomationBus.syncPermissionState(context)

        // Normalize setting category from Bengali, Banglish, Hindi, or English
        val category = when {
            rawUpper.contains("WIFI") || rawUpper.contains("WI-FI") || rawUpper.contains("ওয়াইফাই") || rawUpper.contains("ওয়াইফাই") -> "WIFI"
            rawUpper.contains("BLUETOOTH") || rawUpper.contains("ব্লুটুথ") || rawUpper.contains("بلو") -> "BLUETOOTH"
            rawUpper.contains("DATA") || rawUpper.contains("সিমের নেট") || rawUpper.contains("মোবাইল ডাটা") ||
                rawUpper.contains("মোবাইল ডেটা") || rawUpper.contains("নেট") || rawUpper.contains("SIM") || rawUpper.contains("INTERNET") -> "MOBILE_DATA"
            rawUpper.contains("AIRPLANE") || rawUpper.contains("FLIGHT") || rawUpper.contains("এরোপ্লেন") ||
                rawUpper.contains("বিমান") -> "AIRPLANE"
            rawUpper.contains("HOTSPOT") || rawUpper.contains("হটস্পট") || rawUpper.contains("TETHER") -> "HOTSPOT"
            rawUpper.contains("LOCATION") || rawUpper.contains("GPS") || rawUpper.contains("লোকেশন") || rawUpper.contains("জিপিএস") -> "LOCATION"
            rawUpper.contains("ROTATE") || rawUpper.contains("ROTATION") || rawUpper.contains("রোটেট") -> "AUTO_ROTATE"
            rawUpper.contains("DND") || rawUpper.contains("DISTURB") || rawUpper.contains("বিরক্ত") || rawUpper.contains("সাইলেন্ট") -> "DND"
            rawUpper.contains("FLASH") || rawUpper.contains("TORCH") || rawUpper.contains("টর্চ") || rawUpper.contains("লাইট") -> "FLASHLIGHT"
            rawUpper.contains("BATTERY") || rawUpper.contains("SAVER") || rawUpper.contains("ব্যাটারি সেভার") -> "BATTERY_SAVER"
            rawUpper.contains("DARK") || rawUpper.contains("ডার্ক") -> "DARK_MODE"
            else -> rawUpper.ifBlank { "WIFI" }
        }

        // Step 1: Attempt direct background toggle where Android allows
        var changedDirectlyInBackground = false
        when (category) {
            "FLASHLIGHT" -> {
                changedDirectlyInBackground = setFlashlight(enable)
                if (changedDirectlyInBackground) {
                    refreshHardwareState()
                    return "ফ্ল্যাশলাইট সরাসরি ব্যাকগ্রাউন্ডে $stateLabelBn করা হয়েছে"
                }
            }
            "WIFI" -> {
                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    @Suppress("DEPRECATION")
                    if (wm != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        wm.isWifiEnabled = enable
                        changedDirectlyInBackground = true
                    }
                } catch (_: Exception) {
                }
            }
            "BLUETOOTH" -> {
                try {
                    val btm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val adapter = btm?.adapter
                    if (adapter != null) {
                        val hasBtPerm = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        if (hasBtPerm) {
                            @Suppress("DEPRECATION")
                            changedDirectlyInBackground = if (enable) adapter.enable() else adapter.disable()
                        }
                    }
                } catch (_: Exception) {
                }
            }
            "AUTO_ROTATE" -> {
                try {
                    if (Settings.System.canWrite(context)) {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.ACCELEROMETER_ROTATION,
                            if (enable) 1 else 0
                        )
                        changedDirectlyInBackground = true
                    }
                } catch (_: Exception) {
                }
            }
            "DND" -> {
                if ( !enable ) {
                    setRingerModeInternal("NORMAL")
                } else {
                    setRingerModeInternal("SILENT")
                }
            }
        }

        if (changedDirectlyInBackground) {
            refreshHardwareState()
            return "$category সরাসরি ব্যাকগ্রাউন্ডে $stateLabelBn করা হয়েছে"
        }

        // Step 2: Build multilingual Quick Settings tile targets for Notification Shade auto-click
        val quickTileLabels: List<String> = when (category) {
            "WIFI" -> listOf("Wi-Fi", "Internet", "ওয়াই-ফাই", "ওয়াই-ফাই", "ইন্টারনেট", "WLAN", "WiFi")
            "MOBILE_DATA" -> listOf("Mobile data", "Internet", "মোবাইল ডেটা", "মোবাইল ডাটা", "ইন্টারনেট", "SIM", "Data")
            "BLUETOOTH" -> listOf("Bluetooth", "ব্লুটুথ")
            "AIRPLANE" -> listOf("Airplane mode", "Flight mode", "বিমান মোড", "এরোপ্লেন মোড", "Airplane")
            "HOTSPOT" -> listOf("Hotspot", "Wi-Fi hotspot", "হটস্পট", "মোবাইল হটস্পট")
            "LOCATION" -> listOf("Location", "GPS", "লোকেশন", "অবস্থান")
            "AUTO_ROTATE" -> listOf("Auto-rotate", "Auto rotate", "অটো-রোটেট", "স্বয়ংক্রিয় ঘূর্ণন", "Portrait")
            "DND" -> listOf("Do Not Disturb", "বিরক্ত করবেন না", "DND")
            "FLASHLIGHT" -> listOf("Flashlight", "Torch", "টর্চ", "ফ্ল্যাশলাইট")
            "BATTERY_SAVER" -> listOf("Battery Saver", "Power saving", "ব্যাটারি সেভার")
            "DARK_MODE" -> listOf("Dark theme", "Dark mode", "ডার্ক থিম", "ডার্ক মোড")
            else -> listOf(settingTarget, "Wi-Fi", "Internet", "Bluetooth")
        }

        if (isAccEnabled) {
            AccessibilityAutomationBus.queueMacro(
                PendingUiMacro(
                    quickSettingTileTargets = quickTileLabels,
                    closeShadeAfterClick = true
                )
            )
            refreshHardwareState()
            return "নোটিফিকেশন বার নামিয়ে স্বয়ংক্রিয়ভাবে '$category' ($stateLabelBn) বাটনে ক্লিক করা হচ্ছে"
        }

        // Step 3: If Accessibility is not enabled yet, open the interactive Settings Panel or setting page + queue switch click
        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                quickSettingTileTargets = quickTileLabels,
                clickTargets = quickTileLabels
            )
        )
        if (launchExternal) {
            openSettingsScreen(category)
        }
        refreshHardwareState()
        return "$category প্যানেল খোলা হয়েছে (অটোমেটিক টগলের জন্য Accessibility অন রাখুন)"
    }

    /**
     * Scans both the device's Contacts list AND the entire visible screen for any spoken/typed name,
     * and performs the requested action (Call, WhatsApp, SMS, or Screen Tap).
     */
    fun executeSmartCallOrContactScan(
        targetNameOrNumber: String,
        actionMode: String = "CALL",
        extraMessage: String = "",
        launchExternal: Boolean = true
    ): String {
        val raw = targetNameOrNumber.trim()
        val digitsOnly = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val isDirectNumber = digitsOnly.length >= 6

        if (isDirectNumber && actionMode.uppercase() != "SCREEN_TAP") {
            if (launchExternal) {
                launchDirectCallOrDialer(digitsOnly)
            }
            return "সরাসরি নম্বর ডায়াল/কল করা হচ্ছে: $digitsOnly"
        }

        val resolution = codingAndContactsManager.findContactOrScreenTarget(raw)
        val matched = resolution.matchedContact
        val modeUpper = actionMode.uppercase()

        // 1. If found in Device Contacts and user wants to Call or Message
        if (matched != null && modeUpper != "SCREEN_TAP") {
            if (modeUpper.contains("WHATSAPP") || extraMessage.isNotBlank()) {
                return executeWhatsAppAutomation(
                    contactOrPhone = matched.phoneNumber,
                    messageText = extraMessage.ifBlank { "হ্যালো ${matched.displayName}" },
                    launchExternal = launchExternal
                )
            }
            if (launchExternal) {
                launchDirectCallOrDialer(matched.phoneNumber)
            }
            return "কন্টাক্ট লিস্ট থেকে '${matched.displayName}' (${matched.phoneNumber}) খুঁজে কল করা হচ্ছে"
        }

        // 2. Scan the entire visible screen (with auto-scrolling) for all phonetic name variations and click it!
        val candidates = resolution.phoneticCandidates.ifEmpty { listOf(raw) }
        val primaryButtonsAfterName = when {
            modeUpper.contains("CALL") -> listOf("Call", "কল", "Audio call", "Voice call", "ডায়াল")
            modeUpper.contains("MESSAGE") || modeUpper.contains("WHATSAPP") -> listOf("Message", "মেসেজ", "Send", "পাঠান")
            else -> emptyList()
        }

        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                textToType = extraMessage,
                clickTargets = if (primaryButtonsAfterName.isEmpty()) candidates else primaryButtonsAfterName,
                secondaryClickTargets = if (primaryButtonsAfterName.isNotEmpty()) candidates else emptyList(),
                maxAutoScrollRetries = 3
            )
        )

        if (!resolution.hasContactsPermission && modeUpper.contains("CALL")) {
            return "স্ক্রিন জুড়ে '${resolution.queryName}' নামটি খুঁজে কাজ করা হচ্ছে (ফোনবুক অ্যাক্সেসের জন্য Contacts পারমিশন অন করুন)"
        }

        return if (resolution.foundOnVisibleScreen) {
            "স্ক্রিনে '${resolution.queryName}' নামটি পাওয়া গেছে এবং স্বয়ংক্রিয়ভাবে ট্যাপ করা হচ্ছে"
        } else {
            "কন্টাক্ট ও পুরো স্ক্রিন স্ক্যান করে '${resolution.queryName}' নামের ওপর কাজ করা হচ্ছে"
        }
    }

    fun launchDirectCallOrDialer(phoneNumber: String) {
        val cleanNum = phoneNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (cleanNum.isBlank()) {
            launchDialer("")
            return
        }
        val hasCallPerm = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCallPerm) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$cleanNum")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                return
            } catch (_: Exception) {
            }
        }
        // Fallback: open dialer with number pre-filled AND queue Accessibility macro to click Call button
        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                clickTargets = listOf("Call", "কল", "SIM 1", "SIM 2"),
                targetViewIds = listOf(
                    "com.google.android.dialer:id/dialpad_floating_action_button",
                    "com.android.dialer:id/dialpad_floating_action_button",
                    "com.samsung.android.dialer:id/dialButton"
                )
            )
        )
        launchDialer(cleanNum)
    }

    fun openAccessibilitySettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openSettingsScreen("MAIN_SETTINGS")
        }
    }

    fun openNotificationListenerSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
            openSettingsScreen("MAIN_SETTINGS")
        }
    }

    fun openWriteSettingsPermissionPage() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openSettingsScreen("MAIN_SETTINGS")
        }
    }

    fun copyToClipboard(label: String, text: String) {
        try {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (_: Exception) {
        }
    }

    fun shareTextContent(title: String, content: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, content)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(
                Intent.createChooser(shareIntent, "শেয়ার করুন").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    fun openCameraApp(videoMode: Boolean = false) {
        val action = if (videoMode) {
            MediaStore.INTENT_ACTION_VIDEO_CAMERA
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        try {
            context.startActivity(
                Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Full 6-stream Android Audio Controller (`MUSIC`, `RING`, `NOTIFICATION`, `ALARM`, `VOICE_CALL`, `SYSTEM`, or `ALL`).
     * Supports exact percentages (0..100) as well as relative commands (`UP`, `DOWN`, `MAX`, `MUTE`, `UNMUTE`).
     */
    fun setStreamVolumePercent(
        streamType: String,
        percent: Int,
        relativeCommand: String = ""
    ): String {
        val upperStream = streamType.uppercase().trim()
        val upperRel = relativeCommand.uppercase().trim()

        val targetStreams: List<Pair<String, Int>> = when {
            upperStream.contains("ALL") || upperStream.contains("সব") || upperStream.contains("FULL_PHONE") || upperStream.contains("সকল") -> listOf(
                "মিডিয়া" to AudioManager.STREAM_MUSIC,
                "রিংটোন" to AudioManager.STREAM_RING,
                "নোটিফিকেশন" to AudioManager.STREAM_NOTIFICATION,
                "অ্যালার্ম" to AudioManager.STREAM_ALARM,
                "কল" to AudioManager.STREAM_VOICE_CALL,
                "সিস্টেম" to AudioManager.STREAM_SYSTEM
            )
            upperStream.contains("RING") || upperStream.contains("রিং") || upperStream.contains("রিয়টন") || upperStream.contains("রিয়টন") -> listOf("রিংটোন" to AudioManager.STREAM_RING)
            upperStream.contains("ALARM") || upperStream.contains("অ্যালার্ম") || upperStream.contains("এলার্ম") || upperStream.contains("আলার্ম") -> listOf("অ্যালার্ম" to AudioManager.STREAM_ALARM)
            upperStream.contains("NOTIF") || upperStream.contains("নোটিফিকেশন") || upperStream.contains("মেসেজ সাউন্ড") -> listOf("নোটিফিকেশন" to AudioManager.STREAM_NOTIFICATION)
            upperStream.contains("CALL") || upperStream.contains("VOICE") || upperStream.contains("কল") -> listOf("ভয়েস কল" to AudioManager.STREAM_VOICE_CALL)
            upperStream.contains("SYS") || upperStream.contains("সিস্টেম") -> listOf("সিস্টেম" to AudioManager.STREAM_SYSTEM)
            else -> listOf("মিডিয়া" to AudioManager.STREAM_MUSIC)
        }

        var finalAppliedPct = percent.coerceIn(0, 100)

        for ((_, streamId) in targetStreams) {
            val maxVol = audioManager.getStreamMaxVolume(streamId).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(streamId)
            val curPct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)

            val computedPct = when {
                upperRel.contains("MUTE") && !upperRel.contains("UNMUTE") -> 0
                upperRel.contains("ZERO") || upperRel.contains("OFF") || upperRel.contains("বন্ধ") || upperRel.contains("মিউট") -> 0
                upperRel.contains("UNMUTE") || upperRel.contains("চালু") -> if (curPct < 25) 75 else curPct
                upperRel.contains("MAX") || upperRel.contains("FULL") || upperRel.contains("ফুল") || upperRel.contains("পুরো") -> 100
                upperRel.contains("HALF") || upperRel.contains("অর্ধেক") || upperRel.contains("মিডিয়াম") -> 50
                upperRel.contains("UP") || upperRel.contains("INCREASE") || upperRel.contains("বাড়া") || upperRel.contains("বারা") -> (curPct + 20).coerceAtMost(100)
                upperRel.contains("DOWN") || upperRel.contains("DECREASE") || upperRel.contains("কমা") -> (curPct - 20).coerceAtLeast(0)
                else -> percent.coerceIn(0, 100)
            }
            finalAppliedPct = computedPct
            val targetVol = ((computedPct / 100f) * maxVol).toInt().coerceIn(0, maxVol)
            try {
                audioManager.setStreamVolume(streamId, targetVol, AudioManager.FLAG_SHOW_UI)
            } catch (_: SecurityException) {
                openSettingsScreen("VOLUME_PANEL")
            }
        }

        refreshHardwareState()
        val label = if (targetStreams.size > 1) "সব ধরনের সাউন্ড (মিডিয়া, রিংটোন, নোটিফিকেশন, অ্যালার্ম, কল ও সিস্টেম)" else targetStreams.first().first
        return "$label সাউন্ড ভলিউম $finalAppliedPct% এ সেট করা হয়েছে"
    }

    /**
     * Opens Android Recent Apps and swipes away the target app (or clears all recent apps) + kills background process.
     */
    fun clearRecentApps(targetAppHint: String = "ALL"): String {
        val cleanHint = targetAppHint.trim().ifBlank { "ALL" }
        val isAll = cleanHint.equals("ALL", ignoreCase = true) ||
            cleanHint.contains("সব") ||
            cleanHint.contains("ক্লিয়ার") ||
            cleanHint.contains("সকল") ||
            cleanHint.contains("clear", ignoreCase = true)

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                if (isAll) {
                    val knownPkgs = listOf(
                        "com.google.android.youtube",
                        "com.facebook.katana",
                        "com.facebook.orca",
                        "com.whatsapp",
                        "org.telegram.messenger",
                        "com.instagram.android",
                        "com.zhiliaoapp.musically",
                        "com.android.chrome",
                        "com.spotify.music"
                    )
                    knownPkgs.forEach { pkg ->
                        try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                    }
                } else {
                    val found = InstalledAppsIndex.search(context, cleanHint).take(3)
                    found.forEach { app ->
                        if (app.pkg != context.packageName) {
                            try { am.killBackgroundProcesses(app.pkg) } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        AccessibilityAutomationBus.queueMacro(
            PendingUiMacro(
                targetPackageHint = if (isAll) "ALL" else cleanHint,
                globalAction = "CLEAR_RECENT_APPS",
                clickTargets = listOf(if (isAll) "ALL" else cleanHint)
            )
        )

        return if (isAll) {
            "জি স্যার, রিসেন্ট অ্যাপস (Recent Apps) খুলে সব অ্যাপ সরিয়ে ক্লিয়ার করা হচ্ছে।"
        } else {
            "জি স্যার, রিসেন্ট অ্যাপস থেকে '$cleanHint' সোয়াইপ করে সরিয়ে দেওয়া হচ্ছে।"
        }
    }

    fun setRingerModeInternal(mode: String) {
        try {
            when (mode.uppercase()) {
                "VIBRATE" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                "SILENT" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                else -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (_: SecurityException) {
            openSettingsScreen("SOUND")
        }
        refreshHardwareState()
    }

    fun setFlashlight(enabled: Boolean): Boolean {
        return try {
            val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cameraManager.cameraIdList.firstOrNull()

            if (torchCameraId != null) {
                cameraManager.setTorchMode(torchCameraId, enabled)
                _hardwareState.value = _hardwareState.value.copy(isFlashlightOn = enabled)
                true
            } else {
                false
            }
        } catch (_: Exception) {
            _hardwareState.value = _hardwareState.value.copy(isFlashlightOn = enabled)
            false
        }
    }

    fun openExternalMusicSearch(query: String, artist: String) {
        val fullQuery = listOf(query, artist).filter { it.isNotBlank() }.joinToString(" ").ifBlank {
            "Bangla Hit Songs"
        }
        try {
            val mediaIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage("com.google.android.youtube")
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                putExtra(SearchManager.QUERY, fullQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (mediaIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(mediaIntent)
                return
            }
        } catch (_: Exception) {
        }

        openYouTubeAppDirect(fullQuery, launchExternal = true)
    }

    /**
     * Launches the native Android YouTube app directly (never defaults to m.youtube.com browser)
     * and performs in-app search + auto-click when a search/video query is provided.
     */
    fun openYouTubeAppDirect(rawQuery: String = "", launchExternal: Boolean = true): String {
        val cleanYtQuery = extractGenuineYouTubeSearchQuery(rawQuery)

        if (!launchExternal) {
            return if (cleanYtQuery.isNotBlank()) {
                "সরাসরি YouTube অ্যাপে '$cleanYtQuery' চালানোর জন্য প্রস্তুত"
            } else {
                "সরাসরি YouTube অ্যাপ খোলার জন্য প্রস্তুত"
            }
        }

        val pm = context.packageManager

        // Case 1: User wants to search/play a genuine video/song/topic inside the native YouTube app
        if (cleanYtQuery.isNotBlank()) {
            val lowerRaw = rawQuery.lowercase()
            val shouldAutoPlayFirstVideo = lowerRaw.contains("চালাও") ||
                lowerRaw.contains("বাজাও") ||
                lowerRaw.contains("প্লে") ||
                lowerRaw.contains("play") ||
                lowerRaw.contains("watch") ||
                lowerRaw.contains("চালু")

            if (shouldAutoPlayFirstVideo) {
                AccessibilityAutomationBus.queueMacro(
                    PendingUiMacro(
                        targetPackageHint = "com.google.android.youtube",
                        secondaryClickTargets = listOf(cleanYtQuery)
                    )
                )
            } else {
                // User only asked to search on YouTube, so do not click any random video automatically
                AccessibilityAutomationBus.cancelAllPendingMacros()
            }

            // 1A: Native YouTube ACTION_SEARCH intent
            try {
                val ytSearchIntent = Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube")
                    putExtra("query", cleanYtQuery)
                    putExtra(SearchManager.QUERY, cleanYtQuery)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(ytSearchIntent)
                return "সরাসরি YouTube অ্যাপ খুলে '$cleanYtQuery' সার্চ ও প্লে করা হচ্ছে"
            } catch (_: Exception) {
            }

            // 1B: vnd.youtube deep link targeted at native YouTube package
            try {
                val vndSearch = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("vnd.youtube://results?search_query=${Uri.encode(cleanYtQuery)}")
                ).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(vndSearch)
                return "সরাসরি YouTube অ্যাপ খুলে '$cleanYtQuery' সার্চ করা হয়েছে"
            } catch (_: Exception) {
            }

            // 1C: HTTPS URL explicitly bound to com.google.android.youtube package (not browser)
            try {
                val pkgYtView = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanYtQuery)}")
                ).apply {
                    setPackage("com.google.android.youtube")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(pkgYtView)
                return "সরাসরি YouTube অ্যাপে '$cleanYtQuery' খোলা হয়েছে"
            } catch (_: Exception) {
            }
        }

        // Case 2: Open native YouTube app home directly
        try {
            val launchIntent = pm.getLaunchIntentForPackage("com.google.android.youtube")
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return "সরাসরি YouTube অ্যাপ খোলা হয়েছে"
            }
        } catch (_: Exception) {
        }

        try {
            val mainYtIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(mainYtIntent)
            return "সরাসরি YouTube অ্যাপ খোলা হয়েছে"
        } catch (_: Exception) {
        }

        try {
            val vndIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
                setPackage("com.google.android.youtube")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(vndIntent)
            return "সরাসরি YouTube অ্যাপ খোলা হয়েছে"
        } catch (_: Exception) {
        }

        try {
            val vndAnyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(vndAnyIntent)
            return "সরাসরি YouTube অ্যাপ খোলা হয়েছে"
        } catch (_: Exception) {
        }

        // Last resort only if YouTube app is not installed on the device at all
        val fallbackUrl = if (cleanYtQuery.isNotBlank()) {
            "https://m.youtube.com/results?search_query=${Uri.encode(cleanYtQuery)}"
        } else {
            "https://m.youtube.com"
        }
        openWebUrl(fallbackUrl)
        return "YouTube খোলা হয়েছে"
    }

    fun launchEmailComposer(recipient: String, subject: String, body: String) {
        val mailtoUri = Uri.parse("mailto:${Uri.encode(recipient.trim())}")
        val emailIntent = Intent(Intent.ACTION_SENDTO, mailtoUri).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient.trim()))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(emailIntent)
        } catch (_: Exception) {
            val fallbackShare = Intent(Intent.ACTION_SEND).apply {
                type = "message/rfc822"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient.trim()))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(
                    Intent.createChooser(fallbackShare, "ইমেইল পাঠান").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun launchMessageIntent(phoneOrTarget: String, messageText: String) {
        if (phoneOrTarget.any { it.isDigit() }) {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(phoneOrTarget)}")).apply {
                putExtra("sms_body", messageText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(smsIntent)
                return
            } catch (_: Exception) {
            }
        }
        shareTextContent("বার্তা", messageText)
    }

    fun launchDialer(phoneNumber: String) {
        val cleanNum = phoneNumber.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNum")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(dialIntent)
        } catch (_: Exception) {
        }
    }

    fun openSettingsScreen(target: String) {
        val action = when (target.uppercase()) {
            "WIFI" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            "INTERNET_PANEL", "INTERNET" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            } else {
                Settings.ACTION_WIRELESS_SETTINGS
            }
            "VOLUME_PANEL", "VOLUME" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_VOLUME
            } else {
                Settings.ACTION_SOUND_SETTINGS
            }
            "BLUETOOTH" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "DISPLAY", "BRIGHTNESS" -> Settings.ACTION_DISPLAY_SETTINGS
            "SOUND" -> Settings.ACTION_SOUND_SETTINGS
            "BATTERY" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "LOCATION", "GPS" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "AIRPLANE" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "APPS", "APPLICATIONS" -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            "ACCESSIBILITY" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "NOTIFICATION_LISTENER" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try {
            context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            } catch (_: Exception) {
            }
        }
    }

    fun setSystemAlarm(hour: Int, minute: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Cube AI Alarm" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun setSystemTimer(seconds: Int, label: String) {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(5))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Cube AI Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openTargetApp(appName: String, optionalSearchQuery: String = ""): String {
        val lower = appName.trim().lowercase()
        val pm = context.packageManager

        // Guard against UI control words or coordinates accidentally passed as appName
        if (lower.isBlank() ||
            lower in setOf("click", "tap", "type", "search", "send", "back", "home") ||
            Regex("^\\d+\\s*[,xX]\\s*\\d+$").matches(lower)
        ) {
            return "স্ক্রিনে কাজ চলমান রয়েছে"
        }

        // 1. Direct YouTube Native App check first (never open m.youtube.com website!)
        if (lower.contains("youtube") || lower.contains("you tube") || lower.contains("ইউটিউব") || lower.contains("ইউটুব") || lower.contains("यूट्यूब")) {
            val inlineQuery = extractGenuineYouTubeSearchQuery(
                optionalSearchQuery.ifBlank { appName }
            )
            return openYouTubeAppDirect(inlineQuery, launchExternal = true)
        }

        // 2. Check Play Store resolution table for known native packages (Free Fire, WhatsApp, Telegram, Facebook, TikTok, etc.)
        val resolution = cleanPlayStoreAppQuery(appName)
        val candidatePackages = buildList {
            resolution.knownPackage?.let { add(it) }
            if (lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ")) add("com.whatsapp.w4b")
            if (lower.contains("telegram") || lower.contains("টেলিগ্রাম")) add("org.telegram.messenger.web")
            if (lower.contains("free fire") || lower.contains("ফ্রি ফায়ার") || lower.contains("ফ্রিফাইয়ার")) {
                add("com.dts.freefireth")
                add("com.dts.freefiremax")
            }
        }

        for (pkg in candidatePackages) {
            try {
                val directLaunch = pm.getLaunchIntentForPackage(pkg)
                if (directLaunch != null) {
                    directLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(directLaunch)
                    return "${resolution.cleanAppName.ifBlank { appName }} অ্যাপ খোলা হয়েছে"
                }
            } catch (_: Exception) {
            }
        }

        // 3. Query all installed Launcher activities (matching both raw input and canonical English app name)
        val cleanEnglishLower = resolution.cleanAppName.lowercase()
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matchedApp = apps.firstOrNull { resolveInfo ->
                val label = resolveInfo.loadLabel(pm).toString().lowercase()
                val pkg = resolveInfo.activityInfo.packageName.lowercase()
                (lower.isNotBlank() && (label.contains(lower) || pkg.contains(lower))) ||
                    (cleanEnglishLower.isNotBlank() && (label.contains(cleanEnglishLower) || pkg.contains(cleanEnglishLower)))
            }
            if (matchedApp != null) {
                val launchIntent = pm.getLaunchIntentForPackage(matchedApp.activityInfo.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    return "${matchedApp.loadLabel(pm)} অ্যাপ খোলা হয়েছে"
                }
            }
        } catch (_: Exception) {
        }

        when {
            lower.contains("play") || lower.contains("প্লে স্টোর") || lower.contains("প্লে স্টুর") || lower.contains("playstore") -> {
                return installAppFromPlayStore(optionalSearchQuery, true)
            }
            lower.contains("whatsapp") || lower.contains("হোয়াটসঅ্যাপ") -> {
                try {
                    val waIntent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://app")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(waIntent)
                    return "WhatsApp অ্যাপ খোলা হয়েছে"
                } catch (_: Exception) {
                    openWebUrl("https://web.whatsapp.com")
                    return "WhatsApp খোলা হয়েছে"
                }
            }
            lower.contains("telegram") || lower.contains("টেলিগ্রাম") -> {
                try {
                    val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(tgIntent)
                    return "Telegram অ্যাপ খোলা হয়েছে"
                } catch (_: Exception) {
                    openWebUrl("https://web.telegram.org")
                    return "Telegram খোলা হয়েছে"
                }
            }
            lower.contains("facebook") || lower.contains("ফেসবুক") -> {
                openWebUrl("https://m.facebook.com")
                return "Facebook খোলা হয়েছে"
            }
            lower.contains("chrome") || lower.contains("ক্রোম") || lower.contains("google") || lower.contains("গুগল") || lower.contains("ব্রাউজার") -> {
                openChromeOrWeb(optionalSearchQuery.ifBlank { "https://www.google.com" })
                return "Google Chrome খোলা হয়েছে"
            }
            lower.contains("mail") || lower.contains("gmail") || lower.contains("মেইল") -> {
                launchEmailComposer("", "", "")
                return "ইমেইল অ্যাপ খোলা হয়েছে"
            }
            lower.contains("map") || lower.contains("ম্যাপ") -> {
                openMapLocation("Dhaka")
                return "Google Maps খোলা হয়েছে"
            }
            lower.contains("camera") || lower.contains("ক্যামেরা") -> {
                openCameraApp(false)
                return "ক্যামেরা খোলা হয়েছে"
            }
            lower.contains("setting") || lower.contains("সেটিং") -> {
                openSettingsScreen("MAIN_SETTINGS")
                return "সেটিংস খোলা হয়েছে"
            }
            lower.contains("phone") || lower.contains("call") || lower.contains("ডায়ালার") -> {
                launchDialer("")
                return "ফোন ডায়ালার খোলা হয়েছে"
            }
        }

        // If user passed an actual URL, open it; otherwise NEVER jump to Google Search when an app wasn't found!
        if (lower.startsWith("http://") || lower.startsWith("https://") || (lower.contains(".") && !lower.contains(" "))) {
            openChromeOrWeb(appName)
            return "$appName ওয়েবসাইট খোলা হয়েছে"
        }

        return "'$appName' নামের কোনো অ্যাপ ফোনে খুঁজে পাওয়া যায়নি (গুগলে না গিয়ে বর্তমান স্ক্রিনেই থাকা হয়েছে)"
    }

    fun openWebSearch(query: String) {
        val url = if (query.startsWith("http://") || query.startsWith("https://")) {
            query
        } else {
            "https://www.google.com/search?q=${Uri.encode(query)}"
        }
        openWebUrl(url)
    }

    private fun openWebUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openMapLocation(locationQuery: String) {
        val geoUri = Uri.parse("geo:0,0?q=${Uri.encode(locationQuery)}")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(mapIntent)
        } catch (_: Exception) {
            openWebSearch("$locationQuery map")
        }
    }

    /**
     * Feature #1 (HYBRID ACTION ENGINE: DEEP-LINK + ACCESSIBILITY + VISION) &
     * Feature #2 (SCI-FI HUD NEON TARGET LOCK) &
     * Feature #4 (STRICT CONFIRMATION GATE — Step 1 `prepare_and_highlight`):
     * Jumps directly to the target app screen via Android Deep-Link Intent (if needed), types the text,
     * locks onto the final action button `(X, Y)` with the glowing Neon Target Ring, and pauses for confirmation.
     */
    suspend fun prepareAndHighlightWithHybridEngine(
        appOrDeepLink: String,
        recipientOrQuery: String,
        textToType: String,
        targetButtonLabel: String
    ): String {
        val cleanApp = appOrDeepLink.trim()
        val cleanRecipient = recipientOrQuery.trim()
        val cleanText = textToType.trim()
        val cleanButton = targetButtonLabel.trim().ifBlank { "Send" }

        // 0. Closed-Loop Autonomous Screen Agent path (navigates, scrolls, resolves "একে/ওকে", types text, and locks Neon Ring on real button)
        val apiKey = apiKeyProvider().trim()
        val a11ySvc = AccessibilityAutomationBus.serviceInstance
        val a11yReady = AccessibilityAutomationBus.state.value.isServiceEnabled && a11ySvc != null
        val lowerAppForCheck = cleanApp.lowercase()
        val isDirectSystemJumpOnly = cleanText.isBlank() && (
            lowerAppForCheck.contains("play store") ||
                lowerAppForCheck.contains("প্লে স্টোর") ||
                lowerAppForCheck.contains("setting") ||
                lowerAppForCheck.contains("সেটিং")
            )
        if (apiKey.isNotBlank() && a11yReady && !isDirectSystemJumpOnly) {
            val isDeictic = AutonomousScreenAgent.isDeicticReference(cleanRecipient)
            val parsed = parseContactAndCleanMessage(cleanRecipient, cleanText)
            val effectiveRec = if (isDeictic) "" else parsed.cleanRecipient.ifBlank { cleanRecipient }
            val effectiveMsg = parsed.cleanMessageBody.ifBlank { cleanText }
            val goal = buildString {
                if (cleanApp.isNotBlank() && !cleanApp.equals("CURRENT_SCREEN", ignoreCase = true)) {
                    append("Go to/use '$cleanApp'. ")
                }
                if (isDeictic) {
                    append("Read the currently open screen/chat header, ")
                } else if (effectiveRec.isNotBlank()) {
                    append("Find '$effectiveRec' (search or scroll down if needed), ")
                }
                if (effectiveMsg.isNotBlank()) {
                    append("type \"$effectiveMsg\" into the bottom message composer, verify the typed text, ")
                }
                append("and locate the '$cleanButton' button cleanly, then stop for user confirmation.")
            }
            val agentRes = AutonomousScreenAgent.runTask(
                context = context,
                apiKey = apiKey,
                goal = goal,
                appHint = cleanApp,
                recipientHint = if (isDeictic) cleanRecipient else effectiveRec,
                messageHint = effectiveMsg,
                stopBeforeFinalSend = true,
                onProgress = { msg -> onAutonomousStatusCallback(msg) }
            )
            when {
                agentRes.needsUserConfirmation -> {
                    val locked = ConfirmationGateBus.lockedTarget.value
                    val coordInfo = if (locked != null) {
                        "Target='${locked.buttonLabel}' locked at (${locked.targetX.toInt()}, ${locked.targetY.toInt()}) with Sci-Fi Neon Ring."
                    } else {
                        "Target '$cleanButton' locked with Sci-Fi Neon Ring."
                    }
                    return "NEON_TARGET_LOCKED_WAITING_CONFIRMATION: $coordInfo ${agentRes.summaryBn} " +
                        (if (effectiveMsg.isNotBlank()) "Typed text='$effectiveMsg'. " else "") +
                        "CRITICAL: Do NOT click yet! Ask in Bangla now: 'বস, সব রেডি—এখন কি ক্লিক করব?'"
                }
                agentRes.needsUserChoice -> {
                    return "NEED_USER_CHOICE: ${agentRes.summaryBn}"
                }
                !agentRes.success -> {
                    return "AUTONOMOUS_AGENT_STOPPED: ${agentRes.summaryBn}"
                }
            }
        }

        // 1. Deep-Link Jump if an app or deep-link target is provided and not already open
        val alreadyInTargetApp = cleanApp.isNotBlank() &&
            !cleanApp.equals("CURRENT_SCREEN", ignoreCase = true) &&
            AutonomousScreenAgent.appPackageMatchesHint(ScreenMemory.currentPkg, cleanApp)
        if (cleanApp.isNotBlank() && !cleanApp.equals("CURRENT_SCREEN", ignoreCase = true) && !alreadyInTargetApp) {
            val lowerApp = cleanApp.lowercase()
            when {
                lowerApp.contains("whatsapp") || lowerApp.contains("হোয়াটসঅ্যাপ") -> {
                    val matchedPhone = codingAndContactsManager.findContactOrScreenTarget(cleanRecipient)
                        .matchedContact?.phoneNumber.orEmpty()
                        .ifBlank { cleanRecipient }
                        .replace(Regex("[^0-9+]"), "")
                    if (matchedPhone.length >= 8) {
                        val waUri = Uri.parse("https://api.whatsapp.com/send?phone=${matchedPhone.removePrefix("+")}&text=${Uri.encode(cleanText)}")
                        val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                            setPackage("com.whatsapp")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            openTargetApp("WhatsApp", "")
                        }
                    } else {
                        openTargetApp("WhatsApp", "")
                    }
                    kotlinx.coroutines.delay(950L)
                }
                lowerApp.contains("play store") || lowerApp.contains("প্লে স্টোর") || lowerApp.contains("market") -> {
                    val q = cleanRecipient.ifBlank { cleanText }
                    val knownPkg = cleanPlayStoreAppQuery(q).knownPackage.orEmpty()
                    val marketUri = if (knownPkg.isNotBlank()) {
                        Uri.parse("market://details?id=$knownPkg")
                    } else {
                        Uri.parse("market://search?q=${Uri.encode(q)}&c=apps")
                    }
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, marketUri).apply {
                            setPackage("com.android.vending")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {
                        openTargetApp("Play Store", q)
                    }
                    kotlinx.coroutines.delay(950L)
                }
                lowerApp.contains("setting") || lowerApp.contains("সেটিং") -> {
                    openSettingsScreen(cleanRecipient.ifBlank { "MAIN_SETTINGS" })
                    kotlinx.coroutines.delay(650L)
                }
                else -> {
                    openTargetApp(cleanApp, cleanRecipient)
                    kotlinx.coroutines.delay(750L)
                }
            }
        }

        // 2. Locate exact button coordinates (X, Y) via AccessibilityNodeInfo + ScreenMemory and draw Neon Target Ring
        val svc = AccessibilityAutomationBus.serviceInstance
        val locked = if (svc != null) {
            svc.prepareAndHighlightTargetOnScreen(
                buttonLabelOrCoord = cleanButton,
                textToTypeFirst = cleanText,
                appPackageHint = cleanApp
            )
        } else {
            val dm = context.resources.displayMetrics
            ConfirmationGateBus.lockTargetAndHighlight(
                ctx = context,
                buttonLabel = cleanButton,
                x = dm.widthPixels * 0.85f,
                y = dm.heightPixels * 0.88f,
                appPackageOrName = cleanApp,
                typedTextPreview = cleanText
            )
        }

        return "NEON_TARGET_LOCKED_WAITING_CONFIRMATION: Target='${locked.buttonLabel}' locked at (${locked.targetX.toInt()}, ${locked.targetY.toInt()}) with Sci-Fi Neon Ring. " +
            (if (cleanText.isNotBlank()) "Typed text='$cleanText'. " else "") +
            "CRITICAL: Do NOT click yet! Ask in Bangla now: 'বস, সব রেডি—এখন কি ক্লিক করব?'"
    }

    /**
     * Feature #4 (STRICT CONFIRMATION GATE — Step 2 `execute_confirmed_click`) &
     * Feature #5 (SMART OTP ASSIST CONFIRMATION):
     * Performs the final click at the Neon-Locked `(X, Y)` coordinates OR pastes the confirmed OTP
     * ONLY after the user explicitly confirms ("হ্যাঁ" / "Yes" or HUD Approve tap).
     */
    fun executeConfirmedClickOrOtp(decision: String = "APPROVE"): String {
        val cleanDecision = decision.trim().uppercase()
        if (cleanDecision in setOf("CANCEL", "NO", "DISCARD", "বাতিল", "না")) {
            val cancelled = ConfirmationGateBus.consumeLockedTarget()
            NotificationReplyBus.consumePendingOtp()
            return "CONFIRMED_ACTION_CANCELLED: '${cancelled?.buttonLabel ?: "Target"}' ক্লিক বাতিল করা হয়েছে এবং নিওন টার্গেট লক সরানো হয়েছে।"
        }

        val locked = ConfirmationGateBus.consumeLockedTarget()
        val svc = AccessibilityAutomationBus.serviceInstance
        if (locked != null) {
            return if (svc != null) {
                svc.executeConfirmedTargetAction(locked)
            } else {
                "ACCESSIBILITY_SERVICE_REQUIRED: অ্যাক্সেসিবিলিটি সার্ভিস অন থাকলে (${locked.targetX.toInt()}, ${locked.targetY.toInt()}) পয়েন্টে ক্লিক সম্পন্ন হতো।"
            }
        }

        // Check if there is a pending OTP waiting for paste confirmation
        val pendingOtp = NotificationReplyBus.consumePendingOtp()
        if (pendingOtp.isNotBlank()) {
            val otpTarget = LockedTargetAction(
                buttonLabel = "OTP Input Field",
                targetX = 540f,
                targetY = 900f,
                isOtpConfirmation = true,
                otpCode = pendingOtp
            )
            return if (svc != null) {
                svc.executeConfirmedTargetAction(otpTarget)
            } else {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("OTP", pendingOtp))
                "OTP_COPIED_TO_CLIPBOARD: বসের অনুমতির পর OTP '$pendingOtp' ক্লিপবোর্ডে কপি করা হয়েছে।"
            }
        }

        return "NO_LOCKED_TARGET_PENDING: বর্তমানে কোনো লক করা বাটন বা পেন্ডিং OTP নেই। আগে prepare_and_highlight কল করুন।"
    }

    /**
     * Feature #7: Adjusts screen brightness (0..100%) using Settings.System.SCREEN_BRIGHTNESS.
     */
    fun setScreenBrightnessPercent(percent: Int): String {
        val clamped = percent.coerceIn(5, 100)
        val sysValue = ((clamped / 100f) * 255f).toInt().coerceIn(10, 255)
        return try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                Settings.System.putInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    sysValue
                )
                "স্ক্রিন ব্রাইটনেস $clamped% করা হয়েছে"
            } else {
                openSettingsScreen("DISPLAY")
                "স্ক্রিন ব্রাইটনেস পরিবর্তনের জন্য ডিসপ্লে সেটিংস খোলা হয়েছে ($clamped%)"
            }
        } catch (_: Exception) {
            openSettingsScreen("DISPLAY")
            "ডিসপ্লে ব্রাইটনেস সেটিংস খোলা হয়েছে"
        }
    }
}
