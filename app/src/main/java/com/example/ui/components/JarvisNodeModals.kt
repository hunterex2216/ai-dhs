package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shop
import androidx.compose.material.icons.filled.SpatialAudioOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AgentMemoryNoteEntity
import com.example.data.PlatformAccountEntity
import com.example.model.AgentPlanResponse
import com.example.model.AgentStep
import com.example.model.MusicMood
import com.example.service.AccessibilityEngineState
import com.example.service.CodingWorkspaceState
import com.example.service.CubeSentinelBus
import com.example.service.CubeSentinelState
import com.example.service.DeviceHardwareSnapshot
import com.example.service.NotificationHubState
import com.example.service.OfflineStudioAssetEngine
import com.example.service.SavedArcherArtifact
import com.example.service.StudioSoundscapePreset
import com.example.service.TouchEffectBus
import com.example.service.VoiceBridgeState
import kotlin.math.abs

@Composable
private fun premiumTextFieldColors(accent: Color = OfficialSkyBlue) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OfficialSkyBlue,
    unfocusedBorderColor = OfficialSlateBorder,
    focusedTextColor = OfficialTextPrimary,
    unfocusedTextColor = OfficialTextPrimary,
    cursorColor = OfficialSkyBlue,
    focusedContainerColor = OfficialSlateInner,
    unfocusedContainerColor = OfficialSlateInner,
    focusedLabelColor = OfficialSkyBlue,
    unfocusedLabelColor = OfficialTextSecondary
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoryAndPlatformIdsSheet(
    memories: List<AgentMemoryNoteEntity>,
    accounts: List<PlatformAccountEntity>,
    onAddAccount: (String, String, String, String) -> Unit,
    onDeleteAccount: (Int) -> Unit,
    onAddMemory: (String, String) -> Unit,
    onDeleteMemory: (Int) -> Unit,
    onRunCommand: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val evolutionState by com.example.service.SmartMemoryAndSelfUpdateEngine.state.collectAsState()
    LaunchedEffect(Unit) {
        com.example.service.SmartMemoryAndSelfUpdateEngine.init(context)
    }

    var activeMemoryTab by rememberSaveable { mutableStateOf("SMART_MEM") }
    var memTitle by rememberSaveable { mutableStateOf("") }
    var memBody by rememberSaveable { mutableStateOf("") }
    var customWorkflowTitle by rememberSaveable { mutableStateOf("") }
    var customWorkflowSteps by rememberSaveable { mutableStateOf("") }
    var appOrSiteQuery by rememberSaveable { mutableStateOf("") }
    var selectedAppExplanation by rememberSaveable { mutableStateOf("") }
    var selfUpdateTitle by rememberSaveable { mutableStateOf("") }
    var selfUpdateSpec by rememberSaveable { mutableStateOf("") }
    var showAddMemoryForm by rememberSaveable { mutableStateOf(false) }
    var showAddPlaybookForm by rememberSaveable { mutableStateOf(false) }
    var showAddSelfUpdateForm by rememberSaveable { mutableStateOf(false) }

    val memoryTabs = remember {
        listOf(
            "SMART_MEM" to "🧠 স্মার্ট মেমোরি",
            "PLAYBOOKS" to "⚡ কাজের ধাপ (${evolutionState.learnedPlaybooks.size})",
            "APP_BRAIN" to "📱 অ্যাপ ও সাইট জ্ঞান",
            "SELF_UPDATE" to "🛠️ সেলফ-আপডেট ও ফিক্স"
        )
    }

    val cleanMemories = remember(memories) {
        memories.filterNot {
            com.example.service.SmartMemoryAndSelfUpdateEngine.isJunkMemoryNote(it.title, it.content)
        }
    }

    val allAppGuides = remember(activeMemoryTab) {
        com.example.service.AppKnowledgeRegistry.listAllKnownAppGuides(context)
    }

    PremiumModalShell(
        badgeText = "SMART NEURAL MEMORY • WORKFLOWS & SELF-EVOLUTION",
        title = "স্মার্ট মেমোরি, কাজের ধাপ ও সেলফ-আপডেট ইঞ্জিন",
        subtitle = "অটো-ফিল্টার সক্রিয় • কাজের ধাপ: ${evolutionState.learnedPlaybooks.size}টি • লাইভ আপডেট: ${evolutionState.installedFeatures.size}টি",
        accentColor = MatrixGreen,
        secondaryAccent = CyberCyan,
        onClose = onClose,
        tabs = memoryTabs,
        selectedTab = activeMemoryTab,
        onSelectTab = { activeMemoryTab = it }
    ) {
        val driveState by com.example.service.GoogleDriveMemorySyncService.state.collectAsState()

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            // TAB 1: SMART MEMORY & AI AUTO-FILTER + 5TB GOOGLE DRIVE SYNC
            if (activeMemoryTab == "SMART_MEM") {
                item {
                    PremiumSectionCard(
                        title = "☁️ 5TB Google Drive + SQLite Hybrid Memory (AI_OS_Memory)",
                        subtitle = "লোকাল SQLite ফাস্ট স্টার্টআপ + 5TB Google Drive REST API v3 অটো-ব্যাকআপ (ফোনের মেইন স্টোরেজ ১০০% ফ্রি)",
                        icon = Icons.Default.Memory,
                        accentColor = CyberCyan,
                        badgeText = if (driveState.isConnected) "5TB CONNECTED" else "LINK GMAIL"
                    ) {
                        Text(
                            text = driveState.lastSyncStatusBn,
                            color = if (driveState.isConnected) MatrixGreen else ChampagneHighlight,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "ব্যাকআপ স্ট্যাটাস (/${driveState.driveFolderName}): মেমোরি ${driveState.backedUpMemoriesCount}টি • কথোপকথন ${driveState.backedUpConversationsCount}টি • কোড/ফাইল ${driveState.backedUpScreenshotsAndFilesCount}টি • বড় ZIP প্যাকেজ ${driveState.backedUpZipPackagesCount}টি",
                            color = Color(0xFFB8D4F2),
                            fontSize = 10.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "📂 অটো সাব-ফোল্ডার: /Memories • /Conversations • /Voice_Profiles • /Argon4_Code • /ZIP_Packages • /Media_Screenshots • /Cloud_Bots • /Master_Backups",
                            color = CyberCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🛡️ মেইন ফোন স্টোরেজ জিরো-ইফেক্ট মোড (Zero Storage Impact)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp
                                )
                                Text(
                                    text = "সব মেমোরি, স্ক্রিনশট ও ফাইল সরাসরি RAM থেকে 5TB Google Drive-এ যাবে",
                                    color = MatrixGreen,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = driveState.zeroPhoneStorageImpactMode,
                                onCheckedChange = {
                                    com.example.service.GoogleDriveMemorySyncService.setZeroPhoneStorageImpactMode(context, it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = CyberCyan
                                )
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.startDirectDriveAuthorization(context)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (driveState.isConnected) MatrixGreen else CyberCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("connect_google_drive_button")
                            ) {
                                Text(
                                    text = if (driveState.isConnected) "✅ API Connected (${driveState.linkedEmail.substringBefore("@")})" else "☁️ Connect Google Drive API",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    maxLines = 1
                                )
                            }
                            if (driveState.isConnected) {
                                OutlinedButton(
                                    onClick = {
                                        com.example.service.GoogleDriveMemorySyncService.disconnectGoogleDrive(context)
                                    },
                                    border = BorderStroke(1.dp, Color(0xFFFF5252)),
                                    modifier = Modifier.testTag("disconnect_google_drive_button")
                                ) {
                                    Text("Unlink", color = Color(0xFFFF8A80), fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.triggerFullSyncFromUi(context)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = CyberCyan,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("sync_all_google_drive_button")
                            ) {
                                Text(
                                    text = if (driveState.isSyncing) "⏳ সিঙ্ক হচ্ছে..." else "🔄 সব ডাটা সিঙ্ক করুন",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.openOrShareToGoogleDriveApp(context)
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = OfficialEmerald,
                                    contentColor = Color.Black
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("open_google_drive_app_button")
                            ) {
                                Text(
                                    text = "📤 Drive অ্যাপে খুলুন",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        if (driveState.recentCloudFiles.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "📂 সর্বশেষ সিঙ্ককৃত ফাইল (/${driveState.driveFolderName}):",
                                color = CyberCyan,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            driveState.recentCloudFiles.take(4).forEach { cf ->
                                Text(
                                    text = "• ${cf.name} — ${cf.snippetText.take(65)}",
                                    color = Color(0xFFD5EDE4),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                item {
                    PremiumSectionCard(
                        title = "এআই অটো-মেমোরি জাজমেন্ট ও টার্বো স্পিড",
                        subtitle = "AI নিজেই বুঝবে কোন কাজের কথা মনে রাখতে হবে আর কোন আলতু-ফালতু কথা বাদ দিতে হবে",
                        icon = Icons.Default.Psychology,
                        accentColor = MatrixGreen,
                        badgeText = if (evolutionState.isAutoMemoryFilterEnabled) "AUTO AI ON" else "MANUAL"
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🧠 AI স্বয়ংক্রিয় মেমোরি ফিল্টার (কাজের জিনিস মনে রাখবে)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "অপ্রয়োজনীয় কথা ফিল্টার হয়েছে: ${evolutionState.filteredJunkCount} বার",
                                    color = CyberCyan,
                                    fontSize = 10.5.sp
                                )
                            }
                            Switch(
                                checked = evolutionState.isAutoMemoryFilterEnabled,
                                onCheckedChange = {
                                    com.example.service.SmartMemoryAndSelfUpdateEngine.setAutoMemoryFilterEnabled(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MatrixGreen
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "⚡ টার্বো ৩x ফাস্ট স্ক্রিন অ্যাকশন (কোনো দেরি ছাড়া কাজ)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "স্ক্রিন দেখে ট্যাপ, টাইপ ও সার্চ নরমালের চেয়ে ৩ গুণ দ্রুত করবে",
                                    color = MatrixGreen,
                                    fontSize = 10.5.sp
                                )
                            }
                            Switch(
                                checked = evolutionState.isTurboUiSpeedEnabled,
                                onCheckedChange = {
                                    com.example.service.SmartMemoryAndSelfUpdateEngine.setTurboUiSpeedEnabled(it)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = CyberCyan
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PremiumActionButton(
                                text = "🧹 আলতু-ফালতু ডেটা ক্লিন",
                                primaryColor = CyberCyan,
                                secondaryColor = MatrixGreen,
                                isOutlined = true,
                                onClick = {
                                    memories.forEach { m ->
                                        if (com.example.service.SmartMemoryAndSelfUpdateEngine.isJunkMemoryNote(m.title, m.content)) {
                                            onDeleteMemory(m.id)
                                        }
                                    }
                                    accounts.forEach { acc ->
                                        onDeleteAccount(acc.id)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "🧹 রিসেন্ট অ্যাপ ক্লিয়ার",
                                primaryColor = MatrixGreen,
                                secondaryColor = CyberCyan,
                                onClick = {
                                    onRunCommand("রিসেন্ট অ্যাপ থেকে সব অ্যাপ সরিয়ে ক্লিয়ার করো")
                                    onClose()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // Compact toggle to add memory manually only when needed
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📌 সংরক্ষিত মেমোরি (${cleanMemories.size}টি):",
                            color = CyberCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showAddMemoryForm) RoseNeon.copy(alpha = 0.25f) else Color(0xFF0A1E1A),
                            border = BorderStroke(1.dp, if (showAddMemoryForm) RoseNeon else MatrixGreen),
                            modifier = Modifier.clickable { showAddMemoryForm = !showAddMemoryForm }
                        ) {
                            Text(
                                text = if (showAddMemoryForm) "✕ ফর্ম বন্ধ করুন" else "➕ নতুন মেমোরি যোগ করুন",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                if (showAddMemoryForm) {
                    item {
                        PremiumSectionCard(
                            title = "গুরুত্বপূর্ণ কাজের তথ্য বা নির্দেশনা মনে রাখুন",
                            subtitle = "মুখে বলুন 'মনে রেখো...' অথবা নিচে লিখে স্থায়ীভাবে সেভ করুন",
                            icon = Icons.Default.Memory,
                            accentColor = CyberCyan,
                            badgeText = "${cleanMemories.size} SAVED"
                        ) {
                            OutlinedTextField(
                                value = memTitle,
                                onValueChange = { memTitle = it },
                                placeholder = { Text("বিষয় (যেমন: আমার পছন্দের নিয়ম / কাজের তথ্য)") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(CyberCyan),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = memBody,
                                    onValueChange = { memBody = it },
                                    placeholder = { Text("যে কথা বা নিয়মটি সবসময় মনে রাখবে...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = premiumTextFieldColors(MatrixGreen),
                                    modifier = Modifier.weight(1f)
                                )
                                PremiumActionButton(
                                    text = "মনে রাখো",
                                    icon = Icons.Default.Add,
                                    primaryColor = MatrixGreen,
                                    secondaryColor = CyberCyan,
                                    onClick = {
                                        if (memBody.isNotBlank()) {
                                            onAddMemory(memTitle.ifBlank { "স্যারের কাজের তথ্য" }, memBody)
                                            memTitle = ""
                                            memBody = ""
                                            showAddMemoryForm = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (cleanMemories.isEmpty()) {
                    item {
                        Surface(
                            color = Color(0xFF071A15),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, MatrixGreen.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "মেমোরি সিস্টেম সম্পূর্ণ পরিষ্কার আছে। কোনো আলতু-ফালতু ডেটা নেই—আপনি যা মনে রাখতে বলবেন বা যেসব গুরুত্বপূর্ণ কাজ করবেন শুধু সেগুলোই এখানে জমা হবে।",
                                color = Color(0xFFD2F6EA),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                items(cleanMemories, key = { "mem_${it.id}" }) { mem ->
                    Surface(
                        color = Color(0xFF071A16),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.1.dp, MatrixGreen.copy(alpha = 0.42f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "📌 ${mem.title} • ${mem.category}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MatrixGreen,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = mem.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White
                                )
                            }
                            IconButton(onClick = { onDeleteMemory(mem.id) }) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "মুছুন",
                                    tint = RoseNeon
                                )
                            }
                        }
                    }
                }
            }

            // TAB 2: LEARNED TASK WORKFLOW PLAYBOOKS (কাজের ধাপ)
            if (activeMemoryTab == "PLAYBOOKS") {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ সংরক্ষিত কাজের ধাপ (${evolutionState.learnedPlaybooks.size}টি):",
                            color = MatrixGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showAddPlaybookForm) RoseNeon.copy(alpha = 0.25f) else Color(0xFF0A1E1A),
                            border = BorderStroke(1.dp, if (showAddPlaybookForm) RoseNeon else CyberCyan),
                            modifier = Modifier.clickable { showAddPlaybookForm = !showAddPlaybookForm }
                        ) {
                            Text(
                                text = if (showAddPlaybookForm) "✕ বন্ধ করুন" else "➕ নতুন ধাপ যোগ করুন",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                if (showAddPlaybookForm) {
                    item {
                        PremiumSectionCard(
                            title = "কাজের ধাপ সংরক্ষণ (Learned Task Playbooks)",
                            subtitle = "এজেন্ট কোনো কাজ যেভাবে ধাপে ধাপে করেছে তা মনে রাখে",
                            icon = Icons.Default.AutoAwesome,
                            accentColor = MatrixGreen,
                            badgeText = "${evolutionState.learnedPlaybooks.size} PLAYBOOKS"
                        ) {
                            OutlinedTextField(
                                value = customWorkflowTitle,
                                onValueChange = { customWorkflowTitle = it },
                                placeholder = { Text("কাজের নাম (যেমন: ইউটিউবে কার্টুন সার্চ ও প্লে)") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(MatrixGreen),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customWorkflowSteps,
                                    onValueChange = { customWorkflowSteps = it },
                                    placeholder = { Text("ধাপগুলো কমা বা -> দিয়ে লিখুন") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = premiumTextFieldColors(CyberCyan),
                                    modifier = Modifier.weight(1f)
                                )
                                PremiumActionButton(
                                    text = "ধাপ সেভ",
                                    primaryColor = MatrixGreen,
                                    secondaryColor = CyberCyan,
                                    onClick = {
                                        if (customWorkflowTitle.isNotBlank() && customWorkflowSteps.isNotBlank()) {
                                            val stepsList = customWorkflowSteps.split("->", ",", ";", "\n")
                                                .map { it.trim() }
                                                .filter { it.isNotBlank() }
                                            com.example.service.SmartMemoryAndSelfUpdateEngine.recordLearnedWorkflowSteps(
                                                taskTitle = customWorkflowTitle,
                                                triggerKeywords = customWorkflowTitle,
                                                targetAppOrSite = "Custom",
                                                executedStepDescriptions = stepsList
                                            )
                                            customWorkflowTitle = ""
                                            customWorkflowSteps = ""
                                            showAddPlaybookForm = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                items(evolutionState.learnedPlaybooks, key = { it.id }) { pb ->
                    Surface(
                        color = Color(0xFF071C17),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.2.dp, CyberCyan.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "⚡ ${pb.taskTitle}",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MatrixGreen,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        text = "অ্যাপ/সাইট: ${pb.targetAppOrSite} • ব্যবহৃত: ${pb.successCount} বার",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyberCyan
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    PremiumActionButton(
                                        text = "আবার করুন",
                                        primaryColor = MatrixGreen,
                                        secondaryColor = CyberCyan,
                                        onClick = {
                                            onRunCommand("${pb.taskTitle} (${pb.steps.joinToString(" -> ")})")
                                            onClose()
                                        }
                                    )
                                    IconButton(
                                        onClick = {
                                            com.example.service.SmartMemoryAndSelfUpdateEngine.deletePlaybook(pb.id)
                                        }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = "মুছুন",
                                            tint = RoseNeon
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            pb.steps.forEachIndexed { idx, stepText ->
                                Text(
                                    text = "ধাপ ${idx + 1}: $stepText",
                                    color = Color(0xFFE2FBF3),
                                    fontSize = 11.5.sp,
                                    modifier = Modifier.padding(vertical = 1.5.dp)
                                )
                            }
                        }
                    }
                }
            }

            // TAB 3: UNIVERSAL APP & WEBSITE KNOWLEDGE ENGINE (সব অ্যাপ ও সাইটের ধারণা)
            if (activeMemoryTab == "APP_BRAIN") {
                item {
                    PremiumSectionCard(
                        title = "সব অ্যাপ ও ওয়েবসাইটের ধারণা ও ইউআই ম্যাপ",
                        subtitle = "যেকোনো অ্যাপ বা সাইট কীভাবে কাজ করে তা এআই বুঝে নেয় এবং আপনাকে জানায়",
                        icon = Icons.Default.Public,
                        accentColor = CyberCyan,
                        badgeText = "UNIVERSAL"
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = appOrSiteQuery,
                                onValueChange = { appOrSiteQuery = it },
                                placeholder = { Text("অ্যাপ বা সাইটের নাম লিখুন (যেমন: YouTube, bKash, Chrome)...") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(CyberCyan),
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "বিশ্লেষণ",
                                primaryColor = CyberCyan,
                                secondaryColor = MatrixGreen,
                                onClick = {
                                    selectedAppExplanation = com.example.service.AppKnowledgeRegistry.explainHowAppOrSiteWorksBn(
                                        context,
                                        appOrSiteQuery.ifBlank { "CURRENT_SCREEN" }
                                    )
                                }
                            )
                        }

                        if (selectedAppExplanation.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = Color(0xFF06231C),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, MatrixGreen),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = selectedAppExplanation,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    PremiumActionButton(
                                        text = "🔊 এজেন্টের মুখে শুনুন",
                                        primaryColor = MatrixGreen,
                                        secondaryColor = CyberCyan,
                                        onClick = {
                                            onRunCommand("${appOrSiteQuery.ifBlank { "এই অ্যাপ" }} কীভাবে কাজ করে আমাকে বুঝিয়ে বলো")
                                            onClose()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                items(allAppGuides, key = { it.first }) { (appTitle, guideText) ->
                    Surface(
                        color = Color(0xFF071B17),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, CyberCyan.copy(alpha = 0.38f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedAppExplanation = "$appTitle যেভাবে কাজ করে: $guideText"
                            }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📱 $appTitle",
                                color = MatrixGreen,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 12.5.sp
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = guideText,
                                color = Color(0xFFD8F6EE),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            // TAB 4: SELF-HEALING & LIVE FEATURE SELF-UPDATE (নিজেকে নিজে ঠিক ও আপডেট করা)
            if (activeMemoryTab == "SELF_UPDATE") {
                item {
                    PremiumSectionCard(
                        title = "নিজেকে নিজে ঠিক করা (Self-Heal & Turbo Repair)",
                        subtitle = "ভয়েস বা বাটনে বললেই অ্যাপ নিজের ল্যাগ, মাইক, মেমোরি ও স্ক্রিন স্পিড নিজেই ঠিক করে নেয়",
                        icon = Icons.Default.Security,
                        accentColor = MatrixGreen,
                        badgeText = "v${evolutionState.selfEvolutionVersion}"
                    ) {
                        Text(
                            text = "সর্বশেষ রিপোর্ট: ${evolutionState.lastDiagnosticSummaryBn}",
                            color = Color(0xFFD9FBF0),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        PremiumActionButton(
                            text = "🔧 এখনই নিজেকে নিজে ঠিক ও ৩x ফাস্ট করুন (Self-Heal Now)",
                            icon = Icons.Default.AutoAwesome,
                            primaryColor = MatrixGreen,
                            secondaryColor = CyberCyan,
                            onClick = {
                                com.example.service.SmartMemoryAndSelfUpdateEngine.runSelfDiagnosticsAndRepair(
                                    context,
                                    "ইউজার ওয়ান-ট্যাপ সেলফ-হিলিং ও টার্বো বুস্ট"
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🚀 ইনস্টল করা লাইভ ফিচার (${evolutionState.installedFeatures.size}টি):",
                            color = LuxuryGold,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showAddSelfUpdateForm) RoseNeon.copy(alpha = 0.25f) else Color(0xFF0A1E1A),
                            border = BorderStroke(1.dp, if (showAddSelfUpdateForm) RoseNeon else LuxuryGold),
                            modifier = Modifier.clickable { showAddSelfUpdateForm = !showAddSelfUpdateForm }
                        ) {
                            Text(
                                text = if (showAddSelfUpdateForm) "✕ বন্ধ করুন" else "➕ নতুন ফিচার যুক্ত করুন",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                if (showAddSelfUpdateForm) {
                    item {
                        PremiumSectionCard(
                            title = "এই অ্যাপের ভেতরেই নতুন ফিচার বা আপডেট যুক্ত করুন",
                            subtitle = "আপনি যা বলবেন বা লিখবেন, এজেন্ট নিজের ভেতরে সেই ফিচার/আপডেটটি সাথে সাথে ইনস্টল করে নেবে",
                            icon = Icons.Default.Download,
                            accentColor = LuxuryGold,
                            badgeText = "${evolutionState.installedFeatures.size} ACTIVE"
                        ) {
                            OutlinedTextField(
                                value = selfUpdateTitle,
                                onValueChange = { selfUpdateTitle = it },
                                placeholder = { Text("ফিচারের নাম (যেমন: রিসেন্ট অ্যাপ অটো-ক্লিনার / শর্টকাট কমান্ড)") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(LuxuryGold),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = selfUpdateSpec,
                                onValueChange = { selfUpdateSpec = it },
                                placeholder = { Text("ফিচারটি কীভাবে কাজ করবে বিস্তারিত লিখুন...") },
                                minLines = 2,
                                maxLines = 4,
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(CyberCyan),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            PremiumActionButton(
                                text = "🚀 নিজের মধ্যে এই ফিচার / আপডেট ইনস্টল করুন",
                                icon = Icons.Default.Download,
                                primaryColor = LuxuryGold,
                                secondaryColor = MatrixGreen,
                                onClick = {
                                    if (selfUpdateSpec.isNotBlank()) {
                                        com.example.service.SmartMemoryAndSelfUpdateEngine.installLiveFeatureUpdate(
                                            featureTitle = selfUpdateTitle.ifBlank { selfUpdateSpec.take(30) },
                                            featurePromptOrRule = selfUpdateSpec,
                                            triggerHint = selfUpdateTitle
                                        )
                                        selfUpdateTitle = ""
                                        selfUpdateSpec = ""
                                        showAddSelfUpdateForm = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                items(evolutionState.installedFeatures, key = { it.id }) { patch ->
                    Surface(
                        color = Color(0xFF081D18),
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.1.dp, LuxuryGold.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "✅ ${patch.featureTitle}",
                                    color = LuxuryGold,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.5.sp
                                )
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = patch.featureRuleBn,
                                    color = Color.White,
                                    fontSize = 11.2.sp,
                                    lineHeight = 15.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    com.example.service.SmartMemoryAndSelfUpdateEngine.removeFeatureUpdate(patch.id)
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "মুছুন",
                                    tint = RoseNeon
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatAndUniversalCrossAppSheet(
    notificationState: NotificationHubState,
    plans: List<AgentPlanResponse>,
    onSendCommand: (String) -> Unit,
    onInstallFromPlayStore: (String) -> Unit,
    onChromeAction: (String) -> Unit,
    onTelegramDownload: (String, String) -> Unit,
    onQuickReplyNotification: (String, String) -> Unit,
    onExecuteStep: (String, AgentStep) -> Unit,
    onClose: () -> Unit,
    onSpeakText: (String) -> Unit = {},
    voiceState: VoiceBridgeState = VoiceBridgeState(),
    onToggleVoiceListening: () -> Unit = {}
) {
    var autonomousCommandText by rememberSaveable { mutableStateOf("") }
    var playStoreQuery by rememberSaveable { mutableStateOf("") }
    var telegramFileQuery by rememberSaveable { mutableStateOf("") }
    var chromeUrlOrFile by rememberSaveable { mutableStateOf("") }
    var inlineReplyText by rememberSaveable { mutableStateOf("জি ভাই, আমি দেখছি—৫ মিনিটের মধ্যে জানাচ্ছি।") }

    PremiumModalShell(
        badgeText = "AUTONOMOUS HUB • CROSS-APP COMMANDER",
        title = "অটোনোমাস কমান্ড, ডাউনলোড ও মেসেজ হাব",
        subtitle = "ভয়েস বা টেক্সট কমান্ড, APK ইনস্টলার, টেলিগ্রাম/ক্রোম ডাউনলোডার ও মেসেজ অটো-রিপ্লাই",
        accentColor = CircuitOrange,
        secondaryAccent = MatrixGreen,
        onClose = onClose
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            // 0. Autonomous Command Input
            item {
                PremiumSectionCard(
                    title = "অটোনোমাস এআই কমান্ড (ভয়েস ও টেক্সট)",
                    subtitle = if (voiceState.isListening) "● লাইভ মাইক শুনছে (${voiceState.languageLabel})" else "যেকোনো অ্যাপ বা কাজের আদেশ লিখুন অথবা মুখে বলুন",
                    icon = Icons.AutoMirrored.Filled.Chat,
                    accentColor = MatrixGreen,
                    badgeText = voiceState.languageLabel
                ) {
                    if (voiceState.isListening && voiceState.partialTranscript.isNotBlank()) {
                        Surface(
                            color = Color(0xFF1A0912),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, RoseNeon),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🎙️ লাইভ ভয়েস: \"${voiceState.partialTranscript}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = autonomousCommandText,
                            onValueChange = { autonomousCommandText = it },
                            placeholder = { Text("মুখে বলুন বা লিখুন—AI নিজে সিদ্ধান্ত নেবে...") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = premiumTextFieldColors(MatrixGreen),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("agent_command_input")
                        )
                        IconButton(
                            onClick = onToggleVoiceListening,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (voiceState.isListening) RoseNeon else Color(0xFF0C2A22)
                                )
                                .border(
                                    1.2.dp,
                                    if (voiceState.isListening) Color.White else CyberCyan,
                                    RoundedCornerShape(14.dp)
                                )
                                .testTag("chat_sheet_voice_mic_btn")
                        ) {
                            Icon(
                                imageVector = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Voice Command",
                                tint = Color.White
                            )
                        }
                        Button(
                            onClick = {
                                if (autonomousCommandText.isNotBlank()) {
                                    onSendCommand(autonomousCommandText)
                                    autonomousCommandText = ""
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MatrixGreen,
                                contentColor = Color.Black
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("send_command_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        }
                    }
                }
            }

            // Compact on-demand toggle for APK / Download tools ( stays hidden when idle! )
            item {
                var showManualCrossAppTools by rememberSaveable { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (showManualCrossAppTools) RoseNeon.copy(alpha = 0.25f) else Color(0xFF0A1D19),
                            border = BorderStroke(1.dp, if (showManualCrossAppTools) RoseNeon else CyberCyan.copy(alpha = 0.6f)),
                            modifier = Modifier.clickable { showManualCrossAppTools = !showManualCrossAppTools }
                        ) {
                            Text(
                                text = if (showManualCrossAppTools) "✕ ডাউনলোড ও APK টুলস লুকান" else "⚡ APK ইনস্টল ও ডাউনলোড টুলস (প্রয়োজনে খুলুন)",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        if (notificationState.messages.isNotEmpty()) {
                            PremiumActionButton(
                                text = "🔊 মেসেজ পড়ুন",
                                primaryColor = CyberCyan,
                                secondaryColor = MatrixGreen,
                                onClick = { onSendCommand("আমার সব নোটিফিকেশন ও মেসেজ পড়ে শোনাও") }
                            )
                        }
                    }

                    if (showManualCrossAppTools) {
                        PremiumSectionCard(
                            title = "APK ইনস্টলার ও টেলিগ্রাম/ক্রোম ডাউনলোডার",
                            subtitle = "অ্যাপ ইনস্টল বা ডাইরেক্ট ফাইল ডাউনলোড",
                            icon = Icons.Default.Download,
                            accentColor = CyberCyan,
                            badgeText = "TOOLS"
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = playStoreQuery,
                                    onValueChange = { playStoreQuery = it },
                                    placeholder = { Text("অ্যাপের নাম বা .apk লিংক...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = premiumTextFieldColors(CyberCyan),
                                    modifier = Modifier.weight(1f)
                                )
                                PremiumActionButton(
                                    text = "APK ইনস্টল",
                                    primaryColor = MatrixGreen,
                                    secondaryColor = CyberCyan,
                                    onClick = {
                                        onInstallFromPlayStore(playStoreQuery.ifBlank { "apk" })
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = chromeUrlOrFile,
                                    onValueChange = { chromeUrlOrFile = it },
                                    placeholder = { Text("ক্রোম সাইট বা টেলিগ্রাম ফাইল...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = premiumTextFieldColors(CircuitOrange),
                                    modifier = Modifier.weight(1f)
                                )
                                PremiumActionButton(
                                    text = "ওপেন",
                                    icon = Icons.Default.Public,
                                    primaryColor = CircuitOrange,
                                    secondaryColor = LuxuryGold,
                                    onClick = {
                                        if (chromeUrlOrFile.isNotBlank()) {
                                            onChromeAction(chromeUrlOrFile)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            items(notificationState.messages, key = { it.key }) { msg ->
                Surface(
                    color = Color(0xFF081B18),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.2.dp, CircuitOrange.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "[${msg.appLabel}] ${msg.senderOrTitle}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MatrixGreen,
                                fontWeight = FontWeight.ExtraBold
                            )
                            PremiumActionButton(
                                text = "অটো-রিপ্লাই",
                                icon = Icons.AutoMirrored.Filled.Send,
                                primaryColor = MatrixGreen,
                                secondaryColor = CyberCyan,
                                onClick = { onQuickReplyNotification(msg.senderOrTitle, inlineReplyText) }
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = msg.messageBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White
                        )
                        if (msg.lastRepliedText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "✓ পাঠানো রিপ্লাই: \"${msg.lastRepliedText}\"",
                                style = MaterialTheme.typography.labelMedium,
                                color = CyberCyan
                            )
                        }
                    }
                }
            }

            // 4. Recent AI Action Plans
            item {
                Text(
                    text = "⚡ সাম্প্রতিক এজেন্ট টাস্ক ও অ্যাকশন লগ:",
                    style = MaterialTheme.typography.titleSmall,
                    color = CyberCyan,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            items(plans.take(6), key = { "plan_${it.id}" }) { plan ->
                Surface(
                    color = Color(0xFF071714),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.1.dp, CyberCyan.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = plan.summaryTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = { onSpeakText(plan.spokenReply) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                border = BorderStroke(1.dp, MatrixGreen),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("plan_speak_btn_${plan.id}")
                            ) {
                                Text("🔊 শুনুন", style = MaterialTheme.typography.labelSmall, color = MatrixGreen)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = plan.spokenReply,
                            style = MaterialTheme.typography.bodySmall,
                            color = CyberCyan
                        )
                        plan.steps.forEach { step ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "• ${step.title} (${step.executionResult.ifBlank { "প্রস্তুত" }})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MatrixGreen,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { onExecuteStep(plan.id, step) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    border = BorderStroke(1.dp, CyberCyan),
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    Text("চালান", style = MaterialTheme.typography.labelMedium, color = CyberCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoulAndMusicWakeSheet(
    sentinelState: CubeSentinelState,
    onToggleSentinel: (Boolean) -> Unit,
    onSimulateHeyCube: () -> Unit,
    onSelectMood: (MusicMood) -> Unit,
    onTogglePlayMusic: () -> Unit,
    onOpenExternalMusic: (String, String) -> Unit,
    onClose: () -> Unit,
    activePersonality: com.example.model.AgentPersonalityPreset = com.example.model.AgentPersonalityPreset.CORE_JARVIS_BOSS,
    activeVoicePreset: com.example.model.AgentVoicePreset = com.example.model.AgentVoicePreset.CHARON_MALE,
    micSensitivityBoost: Float = 2.8f,
    customPersonalityPrompt: String = "",
    proactiveAlertsEnabled: Boolean = true,
    proactiveWatchTasks: List<com.example.model.ProactiveWatchTask> = emptyList(),
    onSelectPersonality: (com.example.model.AgentPersonalityPreset) -> Unit = {},
    onSelectVoicePreset: (com.example.model.AgentVoicePreset) -> Unit = {},
    onChangeMicSensitivityBoost: (Float) -> Unit = {},
    onSaveCustomPersonality: (String) -> Unit = {},
    onToggleProactiveAlerts: (Boolean) -> Unit = {},
    onStartProactiveWatch: (String, Int, String) -> Unit = { _, _, _ -> },
    onCancelProactiveWatch: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val offlineStudioState by OfflineStudioAssetEngine.state.collectAsState()
    LaunchedEffect(Unit) {
        OfflineStudioAssetEngine.inspectBundledAssets(context)
    }

    var greetingText by rememberSaveable { mutableStateOf(sentinelState.bossGreeting) }
    var customPersonaInput by rememberSaveable(customPersonalityPrompt) { mutableStateOf(customPersonalityPrompt) }
    var quickTimerMinutes by rememberSaveable { mutableStateOf("10") }
    var quickWatchKeyword by rememberSaveable { mutableStateOf("লোডিং বা ডাউনলোড শেষ") }
    var voiceFilterMode by rememberSaveable { mutableStateOf("ALL") } // "ALL", "FEMALE", "MALE"
    var activeSoulTab by rememberSaveable { mutableStateOf("VOICES") }

    val soulTabs = remember {
        listOf(
            "VOICES" to "👩 ১৬টি ভয়েস",
            "PERSONA" to "🧠 পার্সোনালিটি",
            "PROACTIVE" to "⏰ প্রো-অ্যাকটিভ",
            "WAKE_GUARD" to "🛡️ ওয়েক গার্ড"
        )
    }

    PremiumModalShell(
        badgeText = "SOUL STUDIO",
        title = "ভয়েস স্টুডিও ও পার্সোনালিটি",
        subtitle = "কণ্ঠ: ${activeVoicePreset.titleBn} • স্বভাব: ${activePersonality.titleBn}",
        accentColor = RoseNeon,
        secondaryAccent = CyberCyan,
        onClose = onClose,
        tabs = soulTabs,
        selectedTab = activeSoulTab,
        onSelectTab = { activeSoulTab = it }
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 18.dp)
        ) {
            // 1. Gemini Live Voice Studio (10 Female + 6 Male Voices in Luxury 2-Column Studio Cards)
            if (activeSoulTab == "VOICES") {
                item {
                    PremiumSectionCard(
                        title = "GEMINI LIVE প্রিমিয়াম ভয়েস স্টুডিও (১৬টি কণ্ঠ)",
                        subtitle = "${activeVoicePreset.titleBn} (${activeVoicePreset.gender.labelBn}) — ${activeVoicePreset.subtitleBn}",
                        icon = Icons.Default.RecordVoiceOver,
                        accentColor = if (activeVoicePreset.isFemale) RoseNeon else CyberCyan,
                        badgeText = activeVoicePreset.liveVoiceName
                    ) {
                        // Category Filter Buttons: All (16) | Female (10) | Male (6)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "ALL" to "সব ভয়েস (১৬)",
                                "FEMALE" to "👩 ফিমেইল (১০)",
                                "MALE" to "🎙️ মেইল (৬)"
                            ).forEach { (modeKey, labelBn) ->
                                val selected = voiceFilterMode == modeKey
                                val tabColor = when (modeKey) {
                                    "FEMALE" -> RoseNeon
                                    "MALE" -> CyberCyan
                                    else -> MatrixGreen
                                }
                                Surface(
                                    color = if (selected) tabColor else Color(0xFF071B16),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        1.2.dp,
                                        if (selected) Color.White else tabColor.copy(alpha = 0.55f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { voiceFilterMode = modeKey }
                                ) {
                                    Text(
                                        text = labelBn,
                                        color = if (selected) Color(0xFF02140C) else Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val filteredVoices = remember(voiceFilterMode) {
                            when (voiceFilterMode) {
                                "FEMALE" -> com.example.model.AgentVoicePreset.entries.filter { it.isFemale }
                                "MALE" -> com.example.model.AgentVoicePreset.entries.filter { !it.isFemale }
                                else -> com.example.model.AgentVoicePreset.entries
                            }
                        }

                        // 2-Column Grid of Luxury Voice Studio Tiles
                        val rows = remember(filteredVoices) { filteredVoices.chunked(2) }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            rows.forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    pair.forEach { vPreset ->
                                        PremiumVoiceStudioTile(
                                            titleBn = vPreset.titleBn,
                                            subtitleBn = vPreset.subtitleBn,
                                            voiceCode = vPreset.liveVoiceName,
                                            isFemale = vPreset.isFemale,
                                            isSelected = activeVoicePreset == vPreset,
                                            onClick = { onSelectVoicePreset(vPreset) },
                                            modifier = Modifier.weight(1f),
                                            testTag = "voice_chip_${vPreset.id.lowercase()}"
                                        )
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Gemini Assistant Listening Power & Mic Gain Boost Card
                item {
                    PremiumSectionCard(
                        title = "কথা শোনার পাওয়ার (Gemini Assistant Mic Gain)",
                        subtitle = "আস্তে, ফিসফিস করে বা দূর থেকে কথা বললেও পরিষ্কারভাবে শুনবে",
                        icon = Icons.Default.Mic,
                        accentColor = MatrixGreen,
                        badgeText = String.format(java.util.Locale.US, "%.1fx BOOST", micSensitivityBoost)
                    ) {
                        Slider(
                            value = micSensitivityBoost.coerceIn(1.0f, 4.0f),
                            onValueChange = { onChangeMicSensitivityBoost(it) },
                            valueRange = 1.0f..4.0f,
                            colors = SliderDefaults.colors(
                                thumbColor = MatrixGreen,
                                activeTrackColor = CyberCyan,
                                inactiveTrackColor = Color(0xFF14352B)
                            ),
                            modifier = Modifier.testTag("mic_sensitivity_slider")
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                1.5f to "১.৫x নরমাল",
                                2.2f to "২.২x হাই",
                                2.8f to "২.৮x জেমিনি প্রো",
                                4.0f to "৪.০x আল্ট্রা"
                            ).forEach { (boostVal, label) ->
                                val isActiveBoost = abs(micSensitivityBoost - boostVal) < 0.18f
                                Surface(
                                    color = if (isActiveBoost) MatrixGreen else Color(0xFF081E18),
                                    shape = RoundedCornerShape(11.dp),
                                    border = BorderStroke(
                                        1.1.dp,
                                        if (isActiveBoost) Color.White else CyberCyan.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onChangeMicSensitivityBoost(boostVal) }
                                ) {
                                    Text(
                                        text = label,
                                        color = if (isActiveBoost) Color(0xFF02130B) else Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Multi-Personality Switcher Card
            if (activeSoulTab == "PERSONA") {
                item {
                    PremiumSectionCard(
                        title = "ARCHER ও GEMINI পার্সোনালিটি সুইচার",
                        subtitle = "সক্রিয় স্বভাব: ${activePersonality.titleBn} — ${activePersonality.subtitleBn}",
                        icon = Icons.Default.Psychology,
                        accentColor = CyberCyan,
                        badgeText = "${com.example.model.AgentPersonalityPreset.entries.size} MODES"
                    ) {
                        val personaRows = remember {
                            com.example.model.AgentPersonalityPreset.entries.chunked(2)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            personaRows.forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowItems.forEach { preset ->
                                        val isSelected = activePersonality == preset
                                        Surface(
                                            color = if (isSelected) Color(0xFF0D2E26) else Color(0xFF071915),
                                            shape = RoundedCornerShape(15.dp),
                                            border = BorderStroke(
                                                width = if (isSelected) 1.7.dp else 1.dp,
                                                color = if (isSelected) MatrixGreen else CyberCyan.copy(alpha = 0.35f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSelectPersonality(preset) }
                                        ) {
                                            Column(modifier = Modifier.padding(11.dp)) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = preset.titleBn,
                                                        color = if (isSelected) MatrixGreen else Color.White,
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 11.5.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            tint = MatrixGreen,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(3.dp))
                                                Text(
                                                    text = preset.subtitleBn,
                                                    color = Color(0xFFAAD8CE),
                                                    fontSize = 9.8.sp,
                                                    lineHeight = 13.sp,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                    if (rowItems.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }

                        if (activePersonality == com.example.model.AgentPersonalityPreset.CUSTOM) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = customPersonaInput,
                                onValueChange = { customPersonaInput = it },
                                label = { Text("আপনার কাস্টম পার্সোনালিটি নির্দেশিকা লিখুন") },
                                placeholder = { Text("যেমন: সংক্ষেপে মজার ছলে উত্তর দেবে এবং বস ডাকবে...") },
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(MatrixGreen),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            PremiumActionButton(
                                text = "কাস্টম পার্সোনালিটি সেভ ও চালু করুন",
                                icon = Icons.Default.AutoAwesome,
                                primaryColor = MatrixGreen,
                                secondaryColor = CyberCyan,
                                onClick = { onSaveCustomPersonality(customPersonaInput) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // 4. Proactive Autonomous Voice Watcher & Timer Card
            if (activeSoulTab == "PROACTIVE") {
                item {
                    PremiumSectionCard(
                        title = "প্রো-অ্যাকটিভ ভয়েস ও স্ক্রিন ওয়াচার (নিজে থেকে জানাবে)",
                        subtitle = "লোডিং/ডাউনলোড শেষ হলে, নির্দিষ্ট টাইমার শেষে বা মেসেজ এলে নিজে থেকেই ডাকবে",
                        icon = Icons.Default.NotificationsActive,
                        accentColor = LuxuryGold,
                        headerTrailing = {
                            Switch(
                                checked = proactiveAlertsEnabled,
                                onCheckedChange = onToggleProactiveAlerts,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MatrixGreen
                                )
                            )
                        }
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PremiumActionButton(
                                text = "লোডিং শেষ হলে জানিয়ো",
                                primaryColor = CyberCyan,
                                secondaryColor = MatrixGreen,
                                onClick = {
                                    onStartProactiveWatch(
                                        "SCREEN_LOADING_FINISH",
                                        0,
                                        quickWatchKeyword.ifBlank { "লোডিং বা ডাউনলোড শেষ" }
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "১০ মি. পর জানিয়ো",
                                primaryColor = MatrixGreen,
                                secondaryColor = LuxuryGold,
                                onClick = {
                                    val mins = quickTimerMinutes.toIntOrNull()?.coerceIn(1, 180) ?: 10
                                    onStartProactiveWatch(
                                        "TIMER_ALERT",
                                        mins * 60,
                                        "$mins মিনিট পর রিমাইন্ডার"
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = quickTimerMinutes,
                                onValueChange = { quickTimerMinutes = it.filter { ch -> ch.isDigit() }.take(3) },
                                label = { Text("মিনিট") },
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(LuxuryGold),
                                modifier = Modifier.width(96.dp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = quickWatchKeyword,
                                onValueChange = { quickWatchKeyword = it },
                                label = { Text("স্ক্রিন বা কাজের শর্ত") },
                                shape = RoundedCornerShape(14.dp),
                                colors = premiumTextFieldColors(CyberCyan),
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        val activeTasks = proactiveWatchTasks.filter { !it.isCompleted }
                        if (activeTasks.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "চলমান প্রো-অ্যাকটিভ ওয়াচার (${activeTasks.size}টি):",
                                style = MaterialTheme.typography.labelMedium,
                                color = LuxuryGold,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            activeTasks.forEach { task ->
                                Surface(
                                    color = Color(0xFF081E19),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, LuxuryGold.copy(alpha = 0.45f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "⏳ ${task.labelBn} (${task.type})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedButton(
                                            onClick = { onCancelProactiveWatch(task.id) },
                                            border = BorderStroke(1.dp, RoseNeon),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                        ) {
                                            Text("বাতিল", style = MaterialTheme.typography.labelSmall, color = RoseNeon)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Wake Guard
            if (activeSoulTab == "WAKE_GUARD") {
                item {
                    PremiumSectionCard(
                        title = "\"Hey Cube / আর্চার / জার্ভিস\" লক-স্ক্রিন ওয়েক গার্ড",
                        subtitle = if (sentinelState.isSentinelActive) "সক্রিয় • অফ থাকলেও নাম ধরে ডাকলে বা অ্যাপে ঢুকলে অ্যাক্টিভ হবে" else "সুইচ অন করে ওয়েক-ওয়ার্ড গার্ড সক্রিয় করুন",
                        icon = Icons.Default.Security,
                        accentColor = MatrixGreen,
                        headerTrailing = {
                            Switch(
                                checked = sentinelState.isSentinelActive,
                                onCheckedChange = onToggleSentinel,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = MatrixGreen
                                )
                            )
                        }
                    ) {
                        OutlinedTextField(
                            value = greetingText,
                            onValueChange = {
                                greetingText = it
                                CubeSentinelBus.updateBossGreeting(it)
                            },
                            label = { Text("ডাক দিলে এজেন্ট কী বলে সাড়া দেবে?") },
                            shape = RoundedCornerShape(14.dp),
                            colors = premiumTextFieldColors(MatrixGreen),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        PremiumActionButton(
                            text = "\"Hey Cube / আর্চার\" ওয়েক কল টেস্ট করুন",
                            icon = Icons.Default.RecordVoiceOver,
                            primaryColor = CyberCyan,
                            secondaryColor = MatrixGreen,
                            onClick = onSimulateHeyCube,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndPermissionsSheet(
    accessibilityState: AccessibilityEngineState,
    notificationState: NotificationHubState,
    hardwareState: DeviceHardwareSnapshot,
    geminiApiKey: String,
    onSaveGeminiApiKey: (String) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onToggleFlashlight: (Boolean) -> Unit,
    onVolumeChange: (String, Int) -> Unit,
    onOpenSystemSetting: (String) -> Unit,
    onTestScreenMacro: (String, String) -> Unit,
    onClose: () -> Unit,
    voiceState: VoiceBridgeState = VoiceBridgeState(),
    onToggleTts: () -> Unit = {},
    onTestTtsVoice: () -> Unit = {},
    onChangeSpeechRate: (Float) -> Unit = {},
    onChangeSpeechPitch: (Float) -> Unit = {},
    onSelectVoiceProfile: (String) -> Unit = {},
    onToggleHandsFreeStt: (Boolean) -> Unit = {},
    onSelectSttLanguage: (String) -> Unit = {},
    onTriggerVoiceListen: () -> Unit = {},
    lockedReplyLanguage: String = "AUTO",
    persistentUserRule: String = "",
    onSetLockedLanguage: (String) -> Unit = {},
    onSavePersistentRule: (String) -> Unit = {},
    onToggleQuickSettingTile: (String, Boolean?) -> Unit = { _, _ -> },
    hasContactsAndCallPermission: Boolean = false,
    onRequestContactsAndCallPermission: () -> Unit = {},
    onOpenCodingWorkspace: () -> Unit = {}
) {
    val context = LocalContext.current
    val touchFxEnabled by TouchEffectBus.enabled.collectAsState()
    val audioRouterState by com.example.service.UnifiedAudioRouterBus.state.collectAsState()
    val driveState by com.example.service.GoogleDriveMemorySyncService.state.collectAsState()

    LaunchedEffect(Unit) {
        OfflineStudioAssetEngine.inspectBundledAssets(context)
        com.example.service.UnifiedAudioRouterBus.init(context)
        com.example.service.GoogleDriveMemorySyncService.init(context)
    }

    var apiKeyInput by rememberSaveable(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var apiKeyStatusHint by rememberSaveable { mutableStateOf("") }
    var customRuleDraft by rememberSaveable(persistentUserRule) { mutableStateOf(persistentUserRule) }
    var activeSettingsTab by rememberSaveable { mutableStateOf("ABILITIES") }

    val settingsTabs = remember {
        listOf(
            "ABILITIES" to "🤖 এআই অ্যাবিলিটি",
            "API_KEY" to "🔑 এপিআই কি (Key)",
            "CONTROL" to "🎛️ কন্ট্রোল সেন্টার",
            "VOICE" to "🎙️ ভয়েস ও ভাষা",
            "SOUND" to "🔊 সাউন্ড মিক্সার",
            "PERMS" to "🛡️ পারমিশন"
        )
    }

    PremiumModalShell(
        badgeText = "ARCHER OS • OFFICIAL SYSTEM SETTINGS",
        title = "মেইন সেটিংস, কন্ট্রোল সেন্টার ও পারমিশন",
        subtitle = "উপরের ট্যাবগুলোতে ট্যাপ করে সেটিংস পেইজ পরিবর্তন করুন • ব্যাটারি ${hardwareState.batteryPercent}%",
        accentColor = OfficialSkyBlue,
        secondaryAccent = OfficialEmerald,
        onClose = onClose,
        tabs = settingsTabs,
        selectedTab = activeSettingsTab,
        onSelectTab = { activeSettingsTab = it }
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            // =========================================================================
            // PAGE 1 ("ABILITIES"): The 7 Ability Permission Switches + Studio Launchers
            // =========================================================================
            if (activeSettingsTab == "ABILITIES") {
                item {
                    AbilityPermissionSwitchesCard(
                        geminiApiKey = geminiApiKey
                    )
                }
            }

            // =========================================================================
            // PAGE 2 ("API_KEY"): Gemini API Key Setup + 5TB Google Drive Cloud Sync
            // =========================================================================
            if (activeSettingsTab == "API_KEY") {
                item {
                    PremiumSectionCard(
                        title = "🔑 Gemini API Key কনফিগারেশন",
                        subtitle = if (geminiApiKey.isNotBlank()) {
                            "কাস্টম প্রিমিয়াম API Key সক্রিয় (${geminiApiKey.take(10)}...) • Nano Banana ও Live API রেডি"
                        } else {
                            "বিল্ট-ইন ক্লাউড AI সক্রিয় • নিজস্ব Gemini API Key (AIzaSy...) বসাতে নিচে পেস্ট করুন"
                        },
                        icon = Icons.Default.Key,
                        accentColor = OfficialSkyBlue,
                        headerTrailing = {
                            FilledTonalButton(
                                onClick = onOpenCodingWorkspace,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = OfficialSlateInner,
                                    contentColor = OfficialSkyBlue
                                ),
                                border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .height(34.dp)
                                    .testTag("open_argon_coding_from_settings_btn")
                            ) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("কোডিং স্টুডিও", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    ) {
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = {
                                apiKeyInput = it
                                apiKeyStatusHint = ""
                            },
                            label = {
                                Text(
                                    text = "আপনার Gemini API Key (AIzaSy...)",
                                    color = OfficialSkyBlue,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            placeholder = {
                                Text(
                                    text = "AIzaSy... (এখানে পেস্ট বা টাইপ করুন)",
                                    color = OfficialTextSecondary,
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = premiumTextFieldColors(OfficialSkyBlue),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gemini_api_key_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PremiumActionButton(
                                text = "📋 পেস্ট করুন",
                                primaryColor = OfficialSkyBlue,
                                isOutlined = true,
                                onClick = {
                                    runCatching {
                                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                        if (clipText.isNotBlank()) {
                                            apiKeyInput = clipText
                                            apiKeyStatusHint = "✔ ক্লিপবোর্ড থেকে API Key পেস্ট হয়েছে! এখন 'সেভ করুন' চাপুন।"
                                        } else {
                                            apiKeyStatusHint = "⚠ ক্লিপবোর্ডে কোনো কপি করা টেক্সট পাওয়া যায়নি।"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "paste_gemini_key_btn"
                            )

                            PremiumActionButton(
                                text = "✅ সেভ করুন",
                                primaryColor = OfficialEmerald,
                                onClick = {
                                    val clean = apiKeyInput.trim()
                                    onSaveGeminiApiKey(clean)
                                    com.example.service.AgentAbilitySwitchesBus.savePremiumApiKey(context, clean)
                                    apiKeyStatusHint = if (clean.isNotBlank()) {
                                        "✔ আপনার Gemini API Key সফলভাবে সেভ ও সক্রিয় হয়েছে!"
                                    } else {
                                        "✔ বিল্ট-ইন ক্লাউড AI সক্রিয় করা হয়েছে!"
                                    }
                                    android.widget.Toast.makeText(
                                        context,
                                        apiKeyStatusHint,
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "save_gemini_key_btn"
                            )

                            PremiumActionButton(
                                text = "🗑️ ক্লিয়ার",
                                primaryColor = OfficialDangerRed,
                                isOutlined = true,
                                onClick = {
                                    apiKeyInput = ""
                                    onSaveGeminiApiKey("")
                                    com.example.service.AgentAbilitySwitchesBus.savePremiumApiKey(context, "")
                                    apiKeyStatusHint = "API Key মুছে বিল্ট-ইন ক্লাউড AI চালু করা হয়েছে।"
                                },
                                modifier = Modifier.weight(0.75f)
                            )
                        }

                        if (apiKeyStatusHint.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(
                                color = OfficialSlateInner,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, OfficialEmerald.copy(alpha = 0.55f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = apiKeyStatusHint,
                                    color = OfficialEmerald,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }
                    }
                }

                // 5TB Google Drive Cloud Memory & Backup Card right inside API_KEY tab
                item {
                    PremiumSectionCard(
                        title = "☁️ 5TB Google Drive (AI_OS_Memory) ক্লাউড কানেকশন",
                        subtitle = if (driveState.isConnected) {
                            "সংযুক্ত: ${driveState.linkedEmail} • ফোনের মেইন স্টোরেজে কোনো চাপ পড়বে না"
                        } else {
                            "জিমেইল অ্যাকাউন্ট কানেক্ট করলে সব মেমোরি, ছবি ও ফাইল সরাসরি 5TB Google Drive-এ ব্যাকআপ হবে"
                        },
                        icon = Icons.Default.Public,
                        accentColor = if (driveState.isConnected) OfficialEmerald else OfficialSkyBlue,
                        badgeText = if (driveState.isConnected) "CONNECTED" else "5TB DRIVE"
                    ) {
                        Text(
                            text = driveState.lastSyncStatusBn,
                            color = if (driveState.isConnected) OfficialEmerald else OfficialTextSecondary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PremiumActionButton(
                                text = if (driveState.isConnected) "✔ জিমেইল পরিবর্তন" else "☁️ Connect Google Drive API",
                                primaryColor = if (driveState.isConnected) OfficialEmerald else OfficialSkyBlue,
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.startDirectDriveAuthorization(context)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = if (driveState.isSyncing) "⏳ সিঙ্ক হচ্ছে..." else "🔄 সব সিঙ্ক করুন",
                                primaryColor = OfficialSkyBlue,
                                isOutlined = true,
                                onClick = {
                                    com.example.service.GoogleDriveMemorySyncService.triggerFullSyncFromUi(context)
                                },
                                modifier = Modifier.weight(0.95f)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        PremiumActionButton(
                            text = "📤 Google Drive অ্যাপে ফাইল খুলুন / সেভ করুন",
                            primaryColor = OfficialEmerald,
                            isOutlined = true,
                            onClick = {
                                com.example.service.GoogleDriveMemorySyncService.openOrShareToGoogleDriveApp(context)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // =========================================================================
            // PAGE 3 ("CONTROL"): 14 Hardware Control Center Tiles in Clean Slate Grid
            // =========================================================================
            if (activeSettingsTab == "CONTROL") {
                item {
                    PremiumSectionCard(
                        title = "🎛️ হার্ডওয়্যার কন্ট্রোল সেন্টার (কুইক টগল)",
                        subtitle = "ট্যাপ করলে ডাইরেক্ট বা কুইক সেটিংস থেকে স্বয়ংক্রিয়ভাবে অন/অফ করবে",
                        icon = Icons.Default.Tune,
                        accentColor = OfficialSkyBlue,
                        badgeText = "14 TILES",
                        modifier = Modifier.testTag("quick_settings_auto_toggle_card")
                    ) {
                        val isScreenCastReady = com.example.service.ScreenCaptureSessionBus.isPermanentlyReady()
                        val quickTiles = listOf(
                            Triple("WIFI", "ওয়াইফাই (Wi-Fi)" to if (hardwareState.isWifiEnabled) "CONNECTED" else "OFF", Icons.Default.Wifi to hardwareState.isWifiEnabled),
                            Triple("MOBILE_DATA", "মোবাইল ডাটা" to if (hardwareState.isNetworkConnected) "ACTIVE" else "OFF", Icons.Default.CellTower to hardwareState.isNetworkConnected),
                            Triple("BLUETOOTH", "ব্লুটুথ (BT)" to if (hardwareState.isBluetoothEnabled) "ON" else "OFF", Icons.Default.Bluetooth to hardwareState.isBluetoothEnabled),
                            Triple("FLASHLIGHT", "টর্চলাইট (Torch)" to if (hardwareState.isFlashlightOn) "ON" else "OFF", Icons.Default.FlashlightOn to hardwareState.isFlashlightOn),
                            Triple("AIRPLANE_MODE", "এরোপ্লেন মোড" to if (hardwareState.isAirplaneModeOn) "ON" else "OFF", Icons.Default.AirplanemodeActive to hardwareState.isAirplaneModeOn),
                            Triple("HOTSPOT", "ওয়াইফাই হটস্পট" to "TAP TO TOGGLE", Icons.Default.WifiTethering to false),
                            Triple("LOCATION", "লোকেশন (GPS)" to "NAVIGATION", Icons.Default.LocationOn to false),
                            Triple("AUTO_ROTATE", "অটো-রোটেট" to if (hardwareState.isAutoRotateOn) "ON" else "LOCKED", Icons.Default.ScreenRotation to hardwareState.isAutoRotateOn),
                            Triple("DND", "DND সাইলেন্ট" to if (hardwareState.ringerMode == "SILENT") "SILENT" else "NORMAL", Icons.Default.DoNotDisturbOn to (hardwareState.ringerMode == "SILENT")),
                            Triple("DARK_MODE", "ডার্ক থিম" to "OFFICIAL SLATE", Icons.Default.DarkMode to true),
                            Triple("BATTERY_SAVER", "ব্যাটারি (${hardwareState.batteryPercent}%)" to if (hardwareState.isCharging) "CHARGING" else "POWER SAVER", Icons.Default.BatterySaver to hardwareState.isCharging),
                            Triple("QUICK_SETTINGS", "নোটিফিকেশন বার" to "PULL DOWN", Icons.Default.ExpandMore to false)
                        )

                        val tileRows = quickTiles.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tileRows.forEach { rowPair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    rowPair.forEach { (targetKey, labels, iconAndState) ->
                                        val (titleText, subText) = labels
                                        val (tileIcon, isOn) = iconAndState
                                        PremiumControlCenterTile(
                                            title = titleText,
                                            statusLabel = subText,
                                            icon = tileIcon,
                                            isActive = isOn,
                                            activeColor = OfficialSkyBlue,
                                            onClick = {
                                                if (targetKey == "FLASHLIGHT") {
                                                    onToggleFlashlight(!hardwareState.isFlashlightOn)
                                                } else {
                                                    onToggleQuickSettingTile(targetKey, null)
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            testTag = "qs_tile_btn_$targetKey"
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PremiumControlCenterTile(
                                title = "নিওন টাচ ইফেক্ট",
                                statusLabel = if (touchFxEnabled) "RIPPLE ON" else "OFF",
                                icon = Icons.Default.TouchApp,
                                isActive = touchFxEnabled,
                                activeColor = OfficialSkyBlue,
                                onClick = { TouchEffectBus.set(context, !touchFxEnabled) },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumControlCenterTile(
                                title = "মেইন স্ক্রিন শেয়ার",
                                statusLabel = if (isScreenCastReady) "LIVE ACTIVE" else "TAP TO ENABLE",
                                icon = Icons.Default.ScreenShare,
                                isActive = isScreenCastReady,
                                activeColor = OfficialEmerald,
                                onClick = {
                                    com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // PAGE 4 ("VOICE"): Language Lock + Studio Voice Profiles + Pitch/Speed + STT
            // =========================================================================
            if (activeSettingsTab == "VOICE") {
                item {
                    PremiumSectionCard(
                        title = "🌐 স্থায়ী নির্দেশনা ও ভাষা লক (Language Lock)",
                        subtitle = "আপনি যে ভাষাতেই কথা বলুন, নির্বাচিত ভাষায় ও নিয়ম মেনে উত্তর দেবে",
                        icon = Icons.Default.Lock,
                        accentColor = OfficialSkyBlue,
                        badgeText = lockedReplyLanguage,
                        modifier = Modifier.testTag("persistent_rule_lock_card")
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "AUTO" to "🌐 সব ভাষা (Auto)",
                                "BENGALI_ONLY" to "🇧🇩 শুধু বাংলা",
                                "ENGLISH_ONLY" to "🇬🇧 শুধু ইংরেজি",
                                "HINDI_ONLY" to "🇮🇳 শুধু হিন্দি"
                            ).forEach { (mode, label) ->
                                val selected = lockedReplyLanguage == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSetLockedLanguage(mode) },
                                    label = {
                                        Text(
                                            text = label,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) OfficialSlateBg else OfficialTextPrimary,
                                            fontSize = 11.5.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OfficialSkyBlue,
                                        containerColor = OfficialSlateInner
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = OfficialSlateBorder,
                                        selectedBorderColor = OfficialSkyBlue
                                    ),
                                    modifier = Modifier.testTag("lang_lock_chip_$mode")
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customRuleDraft,
                                onValueChange = { customRuleDraft = it },
                                placeholder = {
                                    Text(
                                        text = "স্থায়ী নির্দেশনা লিখুন (যেমন: সবসময় সংক্ষেপে উত্তর দেবে)...",
                                        color = OfficialTextSecondary,
                                        fontSize = 11.5.sp
                                    )
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                colors = premiumTextFieldColors(OfficialSkyBlue),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("persistent_rule_input")
                            )
                            PremiumActionButton(
                                text = "সেভ লক",
                                primaryColor = OfficialSkyBlue,
                                onClick = { onSavePersistentRule(customRuleDraft) },
                                testTag = "save_persistent_rule_btn"
                            )
                        }
                    }
                }

                // Studio Voice Profile + Pitch & Speed Sliders Card
                item {
                    PremiumSectionCard(
                        title = "🎙️ স্টুডিও ভয়েস প্রোফাইল, পিচ ও স্পিড",
                        subtitle = if (voiceState.ttsEnabled) {
                            "সক্রিয়: ${voiceState.voiceProfile} • Pitch ${String.format("%.2fx", voiceState.pitch)} • Speed ${String.format("%.2fx", voiceState.speechRate)}"
                        } else {
                            "ভয়েস স্পিকার মিউট করা আছে • চালু করতে ডানপাশের সুইচ অন করুন"
                        },
                        icon = Icons.Default.GraphicEq,
                        accentColor = OfficialSkyBlue,
                        modifier = Modifier.testTag("tts_engine_settings_card"),
                        headerTrailing = {
                            Switch(
                                checked = voiceState.ttsEnabled,
                                onCheckedChange = { onToggleTts() },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OfficialSlateBg,
                                    checkedTrackColor = OfficialSkyBlue,
                                    uncheckedThumbColor = OfficialTextSecondary,
                                    uncheckedTrackColor = OfficialSlateInner
                                ),
                                modifier = Modifier.testTag("tts_enable_switch")
                            )
                        }
                    ) {
                        Text(
                            text = "কণ্ঠ নির্বাচন করুন (Gemini Live ও Studio TTS উভয়েই সিঙ্ক হবে):",
                            style = MaterialTheme.typography.labelMedium,
                            color = OfficialTextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "AOEDE_FEMALE" to "👩 Aoede (মেইন ফিমেইল)",
                                "KORE_FEMALE" to "👩 Kore (স্মার্ট ফিমেইল)",
                                "LEDA_FEMALE" to "👩 Leda (মিষ্টি ফিমেইল)",
                                "ZEPHYR_FEMALE" to "👩 Zephyr (প্রাণবন্ত ফিমেইল)",
                                "CALLIRRHOE_FEMALE" to "👩 Callirrhoe (সুরেলা)",
                                "AUTONOE_FEMALE" to "👩 Autonoe (লেডি বস)",
                                "DESPINA_FEMALE" to "👩 Despina (কোমল)",
                                "ERINOME_FEMALE" to "👩 Erinome (স্টুডিও)",
                                "CHARON_MALE" to "🎙️ Charon (মেইন জারভিস)",
                                "PUCK_MALE" to "🎙️ Puck (বন্ধুসুলভ মেল)",
                                "FENRIR_MALE" to "🔊 Fenrir (প্রফেশনাল মেল)",
                                "ORUS_MALE" to "🗣️ Orus (কমান্ডার মেল)"
                            ).forEach { (profileId, label) ->
                                val selected = voiceState.voiceProfile.equals(profileId, ignoreCase = true)
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectVoiceProfile(profileId) },
                                    label = {
                                        Text(
                                            text = label,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) OfficialSlateBg else OfficialTextPrimary,
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OfficialSkyBlue,
                                        containerColor = OfficialSlateInner
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = OfficialSlateBorder,
                                        selectedBorderColor = OfficialSkyBlue
                                    ),
                                    modifier = Modifier.testTag("voice_profile_chip_$profileId")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "কণ্ঠের গভীরতা (Pitch: ${String.format("%.2fx", voiceState.pitch)})",
                                style = MaterialTheme.typography.labelMedium,
                                color = OfficialTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "↺ 1.0x রিসেট",
                                color = OfficialSkyBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        onChangeSpeechPitch(1.0f)
                                        onChangeSpeechRate(1.0f)
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Slider(
                            value = voiceState.pitch.coerceIn(0.55f, 1.35f),
                            onValueChange = { onChangeSpeechPitch(it) },
                            valueRange = 0.55f..1.35f,
                            colors = SliderDefaults.colors(
                                thumbColor = OfficialSkyBlue,
                                activeTrackColor = OfficialSkyBlue,
                                inactiveTrackColor = OfficialSlateInner
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tts_pitch_slider")
                        )

                        Text(
                            text = "কথার গতি (Speed: ${String.format("%.2fx", voiceState.speechRate)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = OfficialTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Slider(
                            value = voiceState.speechRate.coerceIn(0.65f, 1.45f),
                            onValueChange = { onChangeSpeechRate(it) },
                            valueRange = 0.65f..1.45f,
                            colors = SliderDefaults.colors(
                                thumbColor = OfficialSkyBlue,
                                activeTrackColor = OfficialSkyBlue,
                                inactiveTrackColor = OfficialSlateInner
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("tts_rate_slider")
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        PremiumActionButton(
                            text = "🔊 নির্বাচিত কণ্ঠ টেস্ট করুন (Test Voice)",
                            icon = Icons.Default.GraphicEq,
                            primaryColor = OfficialSkyBlue,
                            onClick = onTestTtsVoice,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "tts_test_voice_btn"
                        )
                    }
                }

                // Speech-to-Text (STT) Hands-Free Voice Recognition Card
                item {
                    PremiumSectionCard(
                        title = "🎤 হ্যান্ডস-ফ্রি ভয়েস রিকগনিশন (Speech-to-Text)",
                        subtitle = if (voiceState.isHandsFreeContinuous) {
                            "হ্যান্ডস-ফ্রি অটো-লিসেন সক্রিয় • বাংলা, ইংরেজি ও হিন্দি সাপোর্ট"
                        } else {
                            "ম্যানুয়াল মাইক মোড • অটো-লিসেন চালু করতে সুইচ অন করুন"
                        },
                        icon = Icons.Default.Language,
                        accentColor = OfficialSkyBlue,
                        modifier = Modifier.testTag("stt_engine_settings_card"),
                        headerTrailing = {
                            Switch(
                                checked = voiceState.isHandsFreeContinuous,
                                onCheckedChange = { onToggleHandsFreeStt(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = OfficialSlateBg,
                                    checkedTrackColor = OfficialSkyBlue,
                                    uncheckedThumbColor = OfficialTextSecondary,
                                    uncheckedTrackColor = OfficialSlateInner
                                ),
                                modifier = Modifier.testTag("stt_hands_free_switch")
                            )
                        }
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "bn-BD" to "🇧🇩 বাংলা (bn-BD)",
                                "en-US" to "🇺🇸 English",
                                "hi-IN" to "🇮🇳 हिन्दी"
                            ).forEach { (code, label) ->
                                val selected = voiceState.languageCode == code
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectSttLanguage(code) },
                                    label = {
                                        Text(
                                            text = label,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) OfficialSlateBg else OfficialTextPrimary,
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OfficialSkyBlue,
                                        containerColor = OfficialSlateInner
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = OfficialSlateBorder,
                                        selectedBorderColor = OfficialSkyBlue
                                    ),
                                    modifier = Modifier.testTag("stt_lang_chip_$code")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        PremiumActionButton(
                            text = if (voiceState.isListening) "⏹ লিসেনিং চলছে... (থামাতে ট্যাপ করুন)" else "🎤 এখনই মুখে কমান্ড দিন (Start Voice STT)",
                            icon = if (voiceState.isListening) Icons.Default.MicOff else Icons.Default.Mic,
                            primaryColor = if (voiceState.isListening) OfficialDangerRed else OfficialSkyBlue,
                            onClick = onTriggerVoiceListen,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "stt_test_listen_btn"
                        )
                    }
                }
            }

            // =========================================================================
            // PAGE 5 ("SOUND"): Unified Audio Router + 6-Channel Studio Volume Mixer
            // =========================================================================
            if (activeSettingsTab == "SOUND") {
                item {
                    PremiumSectionCard(
                        title = "🎧 ইউনিফাইড অডিও রাউটার (Speaker ⇄ Bluetooth/Headset)",
                        subtitle = "আউটপুট: ${audioRouterState.activeOutputDeviceName} • ইনপুট: ${audioRouterState.activeInputDeviceName}",
                        icon = Icons.Default.Bluetooth,
                        accentColor = OfficialSkyBlue,
                        badgeText = audioRouterState.activeOutputEndpoint.shortBadge,
                        modifier = Modifier.testTag("unified_audio_router_card")
                    ) {
                        Text(
                            text = audioRouterState.lastRouteEventBn,
                            style = MaterialTheme.typography.bodySmall,
                            color = OfficialTextSecondary,
                            fontSize = 11.5.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.service.AudioRouteMode.entries.forEach { mode ->
                                val selected = audioRouterState.selectedRouteMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        com.example.service.UnifiedAudioRouterBus.setRouteMode(mode)
                                    },
                                    label = {
                                        Text(
                                            text = mode.titleBn,
                                            fontWeight = FontWeight.Bold,
                                            color = if (selected) OfficialSlateBg else OfficialTextPrimary,
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OfficialSkyBlue,
                                        containerColor = OfficialSlateInner
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderColor = OfficialSlateBorder,
                                        selectedBorderColor = OfficialSkyBlue
                                    ),
                                    modifier = Modifier.testTag("audio_route_chip_${mode.id}")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Surface(
                            color = OfficialSlateInner,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, OfficialSlateBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "🎚️ ভয়েস ক্ল্যারিটি ও ইকো-জিরো বার্জ-ইন DSP",
                                        color = OfficialTextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = if (audioRouterState.isBluetoothHeadsetConnected || audioRouterState.isWiredHeadsetConnected) {
                                            "হেডসেট সংযুক্ত • জিরো-ইকো আল্ট্রা-ফাস্ট কমান্ড রিসিভ সক্রিয়"
                                        } else {
                                            "প্রাইমারি লাউডস্পিকার • লাউডনেস বুস্ট ও ইকো ক্যান্সেলার সক্রিয়"
                                        },
                                        color = OfficialTextSecondary,
                                        fontSize = 10.5.sp
                                    )
                                }
                                Switch(
                                    checked = audioRouterState.isVoiceClarityDspEnabled,
                                    onCheckedChange = {
                                        com.example.service.UnifiedAudioRouterBus.setVoiceClarityDspEnabled(it)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = OfficialSlateBg,
                                        checkedTrackColor = OfficialSkyBlue,
                                        uncheckedThumbColor = OfficialTextSecondary,
                                        uncheckedTrackColor = OfficialSlateCard
                                    ),
                                    modifier = Modifier.testTag("audio_dsp_clarity_switch")
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PremiumActionButton(
                                text = "🔄 ডিভাইস স্ক্যান ও সিঙ্ক",
                                primaryColor = OfficialSkyBlue,
                                isOutlined = true,
                                onClick = {
                                    com.example.service.UnifiedAudioRouterBus.refreshDevicesAndApplyRouting(
                                        "ম্যানুয়াল অডিও ডিভাইস স্ক্যান সম্পন্ন"
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "audio_route_rescan_btn"
                            )
                            PremiumActionButton(
                                text = "🔊 অডিও রুট টেস্ট",
                                primaryColor = OfficialSkyBlue,
                                onClick = {
                                    com.example.service.UnifiedAudioRouterBus.refreshDevicesAndApplyRouting()
                                    onTestTtsVoice()
                                },
                                modifier = Modifier.weight(1f),
                                testTag = "audio_route_test_btn"
                            )
                        }
                    }
                }

                // 6-Channel Official Studio Volume Mixer
                item {
                    PremiumSectionCard(
                        title = "🔊 ৬-চ্যানেল স্টুডিও সাউন্ড ও ভলিউম মিক্সার",
                        subtitle = "বর্তমান রিংগার প্রোফাইল: ${hardwareState.ringerMode}",
                        icon = Icons.Default.VolumeUp,
                        accentColor = OfficialSkyBlue,
                        badgeText = hardwareState.ringerMode,
                        modifier = Modifier.testTag("full_audio_streams_card")
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            PremiumActionButton(
                                text = "🔊 সব ১০০%",
                                primaryColor = OfficialSkyBlue,
                                onClick = { onVolumeChange("ALL", 100) },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "🔉 সব ৫০%",
                                primaryColor = OfficialSkyBlue,
                                isOutlined = true,
                                onClick = { onVolumeChange("ALL", 50) },
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "🔇 সব মিউট",
                                primaryColor = OfficialDangerRed,
                                isOutlined = true,
                                onClick = { onVolumeChange("ALL", 0) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        val streamItems = listOf(
                            Triple("MUSIC", "🎵 মিডিয়া ও গান (Media Audio)", hardwareState.musicVolumePercent),
                            Triple("RING", "📞 রিংটোন (Incoming Call Ring)", hardwareState.ringVolumePercent),
                            Triple("NOTIFICATION", "🔔 নোটিফিকেশন অ্যালার্ট", hardwareState.notificationVolumePercent),
                            Triple("ALARM", "⏰ অ্যালার্ম ও টাইমার (Alarm)", hardwareState.alarmVolumePercent),
                            Triple("VOICE_CALL", "🗣️ ভয়েস কল সাউন্ড (In-Call)", hardwareState.voiceCallVolumePercent),
                            Triple("SYSTEM", "⚙️ সিস্টেম ও টাচ সাউন্ড", hardwareState.systemVolumePercent)
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            streamItems.forEach { (streamKey, label, currentPct) ->
                                PremiumStudioVolumeChannel(
                                    label = label,
                                    percent = currentPct,
                                    accentColor = OfficialSkyBlue,
                                    onPercentChange = { newPct -> onVolumeChange(streamKey, newPct) },
                                    sliderTestTag = "volume_slider_$streamKey"
                                )
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // PAGE 6 ("PERMS"): System Permission Shield with Live Status Refresh
            // =========================================================================
            if (activeSettingsTab == "PERMS") {
                item {
                    var permRefreshTick by remember { mutableStateOf(0) }
                    val isScreenShareReady = remember(permRefreshTick, accessibilityState.isServiceEnabled) {
                        com.example.service.ScreenCaptureSessionBus.isPermanentlyReady()
                    }
                    val canDrawOverlay = remember(permRefreshTick, accessibilityState.isServiceEnabled) {
                        accessibilityState.isServiceEnabled || android.provider.Settings.canDrawOverlays(context)
                    }

                    PremiumSectionCard(
                        title = "🛡️ ফুল মোবাইল অ্যাক্সেস ও সিস্টেম পারমিশন শিল্ড",
                        subtitle = "অ্যাপ ওপেন, নিওন টার্গেট লক, কল এবং OTP পড়ার জন্য নিচের ৫টি পারমিশন সক্রিয় রাখুন",
                        icon = Icons.Default.Security,
                        accentColor = OfficialEmerald,
                        headerTrailing = {
                            Surface(
                                color = OfficialSlateInner,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.6f)),
                                modifier = Modifier.clickable {
                                    permRefreshTick++
                                    com.example.service.AccessibilityAutomationBus.serviceInstance?.refreshSnapshotNow(force = true)
                                }
                            ) {
                                Text(
                                    text = "🔄 রিফ্রেশ",
                                    color = OfficialSkyBlue,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            PremiumPermissionStatusRow(
                                stepNumber = "১",
                                title = "মেইন স্ক্রিন শেয়ার (Live Screen Vision)",
                                description = if (isScreenShareReady) {
                                    "সক্রিয় • এজেন্ট রিয়েল-টাইমে স্ক্রিন দেখে কাজ করার জন্য প্রস্তুত"
                                } else {
                                    "স্ক্রিন দেখে নিখুঁতভাবে কাজ করার জন্য মেইন স্ক্রিন শেয়ার অন করুন"
                                },
                                isGranted = isScreenShareReady,
                                accentColor = OfficialSkyBlue,
                                onClick = {
                                    com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                                }
                            )

                            PremiumPermissionStatusRow(
                                stepNumber = "২",
                                title = "অটো-টাচ, টাইপ ও অ্যাপ কন্ট্রোল (Accessibility)",
                                description = if (accessibilityState.isServiceEnabled) {
                                    "সক্রিয় • নিওন টার্গেট লক, স্ক্রল ও অটো-টাইপ চালু আছে"
                                } else {
                                    "স্ক্রিনে বাটন শনাক্ত ও অটো-টাইপ করার জন্য অ্যাক্সেসিবিলিটি অন করুন"
                                },
                                isGranted = accessibilityState.isServiceEnabled,
                                accentColor = OfficialSkyBlue,
                                onClick = onOpenAccessibilitySettings
                            )

                            PremiumPermissionStatusRow(
                                stepNumber = "৩",
                                title = "কন্টাক্ট লিস্ট ও ডাইরেক্ট কল (Contacts & Phone)",
                                description = if (hasContactsAndCallPermission) {
                                    "সক্রিয় • যেকোনো নাম বললে কন্টাক্ট খুঁজে সরাসরি কল বা মেসেজ প্রস্তুত করবে"
                                } else {
                                    "কন্টাক্ট থেকে নাম খুঁজে কল দেওয়ার জন্য অনুমতি দিন"
                                },
                                isGranted = hasContactsAndCallPermission,
                                buttonTextDenied = "অনুমতি দিন",
                                accentColor = OfficialSkyBlue,
                                onClick = onRequestContactsAndCallPermission,
                                buttonTestTag = "grant_contacts_call_perm_btn"
                            )

                            PremiumPermissionStatusRow(
                                stepNumber = "৪",
                                title = "মেসেজ ও স্মার্ট OTP অ্যাসিস্ট (Notification Listener)",
                                description = if (notificationState.isListenerConnected) {
                                    "সক্রিয় • ইনকামিং মেসেজ ও OTP কোড শনাক্ত করে ভয়েসে জানাবে"
                                } else {
                                    "নোটিফিকেশন ও OTP পড়ার জন্য নোটিফিকেশন অ্যাক্সেস অন করুন"
                                },
                                isGranted = notificationState.isListenerConnected,
                                accentColor = OfficialSkyBlue,
                                onClick = onOpenNotificationSettings
                            )

                            PremiumPermissionStatusRow(
                                stepNumber = "৫",
                                title = "অ্যাপের বাইরে ফ্লোটিং AI গ্লোব ও নিওন HUD (Overlay)",
                                description = if (canDrawOverlay) {
                                    "সক্রিয় • অ্যাপ থেকে বের হলেই ভাসমান গ্লোব ও নিওন টার্গেট রিং দেখাবে"
                                } else {
                                    "অ্যাপের বাইরে ফ্লোটিং গ্লোব দেখানোর জন্য ওভারলে পারমিশন অন করুন"
                                },
                                isGranted = canDrawOverlay,
                                accentColor = OfficialSkyBlue,
                                onClick = {
                                    try {
                                        val intent = android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}")
                                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        onOpenAccessibilitySettings()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodingAndArgonWorkspaceSheet(
    workspaceState: CodingWorkspaceState,
    onSelectModel: (String) -> Unit,
    onToggleAutoRouting: () -> Unit,
    onGenerateCodeWithAi: (String) -> Unit,
    onSaveManualCodeToArcher: (String, String, String, String) -> Unit,
    onShareArtifact: (SavedArcherArtifact) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activeSession by com.example.service.CodingSessionBus.session.collectAsState()

    var activeStudioTab by rememberSaveable { mutableStateOf("EDITOR") }
    var isRawEditMode by rememberSaveable { mutableStateOf(false) }
    var codingPrompt by rememberSaveable { mutableStateOf("") }

    var projectTitle by rememberSaveable(workspaceState.currentProjectTitle, activeSession?.title) {
        mutableStateOf(
            activeSession?.title?.takeIf { it.isNotBlank() }
                ?: workspaceState.currentProjectTitle.ifBlank { "archer_app" }
        )
    }
    var fileName by rememberSaveable(workspaceState.currentFileName, activeSession?.fileName) {
        mutableStateOf(
            activeSession?.fileName?.takeIf { it.isNotBlank() }
                ?: workspaceState.currentFileName.ifBlank { "index.html" }
        )
    }
    var language by rememberSaveable(workspaceState.currentLanguage, activeSession?.language) {
        mutableStateOf(
            activeSession?.language?.takeIf { it.isNotBlank() }
                ?: workspaceState.currentLanguage.ifBlank { "HTML/JS" }
        )
    }
    var codeEditorText by rememberSaveable(workspaceState.currentCode, activeSession?.code) {
        mutableStateOf(
            activeSession?.code?.takeIf { it.isNotBlank() }
                ?: workspaceState.currentCode.ifBlank {
                    """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Argon 4 Studio App</title></head>
                    <body>
                      <h1>Argon 4 Main Coding Studio</h1>
                      <p>ভয়েস বা টেক্সট দিয়ে যেকোনো ওয়েব অ্যাপ, পাইথন স্ক্রিপ্ট বা কোড তৈরি ও লাইভ প্রিভিউ করুন।</p>
                      <button>Run Action</button>
                    </body>
                    </html>
                    """.trimIndent()
                }
        )
    }

    val selectedExt = remember(fileName) {
        fileName.substringAfterLast('.', "html").lowercase()
    }
    val lineCount = remember(codeEditorText) {
        codeEditorText.count { it == '\n' } + 1
    }

    PremiumModalShell(
        badgeText = "ARGON 4 • MAIN CODING STUDIO (MINI AI IDE)",
        title = "Argon 4 মেইন কোডিং স্টুডিও ও লাইভ প্রিভিউ",
        subtitle = "সক্রিয় মডেল: ${workspaceState.activeModelId.removePrefix("models/")} • ফাইল: $fileName ($lineCount লাইন)",
        accentColor = OfficialSkyBlue,
        secondaryAccent = OfficialEmerald,
        onClose = onClose
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 1. Model Selector + Auto-Routing Bar
            Surface(
                color = OfficialSlateCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OfficialSlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        listOf(
                            "models/gemini-2.5-pro" to "⚡ Argon 4 Pro",
                            "models/gemini-2.5-flash" to "🚀 Flash 2.5"
                        ).forEach { (id, label) ->
                            val selected = workspaceState.activeModelId == id
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectModel(id) },
                                label = {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) OfficialSlateBg else OfficialTextPrimary
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OfficialSkyBlue,
                                    containerColor = OfficialSlateInner
                                ),
                                modifier = Modifier.testTag("model_select_chip_$id")
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "অটো",
                            color = OfficialTextSecondary,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Switch(
                            checked = workspaceState.isAutoModelRouting,
                            onCheckedChange = { onToggleAutoRouting() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OfficialSlateBg,
                                checkedTrackColor = OfficialSkyBlue
                            ),
                            modifier = Modifier.testTag("argon_auto_routing_switch")
                        )
                    }
                }
            }

            // 2. Unified 3-Tab Studio Switcher: 💻 Code Editor | ▶ Live Preview / Run | 📂 Saved Files
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    Triple("EDITOR", "💻 Code Editor", OfficialSkyBlue),
                    Triple("PREVIEW", "▶ Live Preview", OfficialEmerald),
                    Triple("FILES", "📂 ফাইল (${workspaceState.savedArtifacts.size})", OfficialSkyBlue)
                )
                tabs.forEach { (tabId, tabLabel, activeColor) ->
                    val isSelected = activeStudioTab == tabId
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) activeColor else OfficialSlateInner)
                            .border(
                                1.dp,
                                if (isSelected) activeColor else OfficialSlateBorder,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable { activeStudioTab = tabId }
                            .testTag("argon_studio_tab_${tabId.lowercase()}")
                    ) {
                        Text(
                            text = tabLabel,
                            color = if (isSelected) OfficialSlateBg else OfficialTextPrimary,
                            fontSize = 11.2.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1
                        )
                    }
                }
            }

            // 3. Main Tab Content Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(OfficialSlateInner)
                    .border(1.dp, OfficialSlateBorder, RoundedCornerShape(14.dp))
            ) {
                when (activeStudioTab) {
                    "EDITOR" -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // File metadata + Edit/Syntax toggle + Single-file extension chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = projectTitle,
                                    onValueChange = { projectTitle = it },
                                    label = { Text("প্রজেক্ট", fontSize = 10.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = premiumTextFieldColors(OfficialSkyBlue),
                                    modifier = Modifier.weight(0.9f)
                                )
                                OutlinedTextField(
                                    value = fileName,
                                    onValueChange = { fileName = it },
                                    label = { Text("ফাইল নাম", fontSize = 10.sp) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp),
                                    colors = premiumTextFieldColors(OfficialSkyBlue),
                                    modifier = Modifier.weight(1.1f)
                                )
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isRawEditMode) OfficialSkyBlue else OfficialSlateCard,
                                    border = BorderStroke(1.dp, OfficialSkyBlue),
                                    modifier = Modifier
                                        .clickable { isRawEditMode = !isRawEditMode }
                                        .testTag("argon_toggle_raw_edit_btn")
                                ) {
                                    Text(
                                        text = if (isRawEditMode) "👁️ Syntax" else "✏️ Edit",
                                        color = if (isRawEditMode) OfficialSlateBg else OfficialSkyBlue,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
                                    )
                                }
                            }

                            // Format selector chips (.html, .py, .js, .kt, .json, .txt)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Format:",
                                    color = OfficialTextSecondary,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                listOf(
                                    "html" to "HTML/JS",
                                    "py" to "Python",
                                    "js" to "JavaScript",
                                    "kt" to "Kotlin",
                                    "json" to "JSON",
                                    "txt" to "Text"
                                ).forEach { (ext, langLabel) ->
                                    val isCurrentExt = selectedExt == ext
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isCurrentExt) OfficialSkyBlue else OfficialSlateCard,
                                        border = BorderStroke(1.dp, if (isCurrentExt) OfficialSkyBlue else OfficialSlateBorder),
                                        modifier = Modifier
                                            .clickable {
                                                val base = fileName.substringBeforeLast('.', projectTitle.ifBlank { "archer_app" })
                                                fileName = "$base.$ext"
                                                language = langLabel
                                            }
                                            .testTag("argon_ext_chip_$ext")
                                    ) {
                                        Text(
                                            text = ".$ext",
                                            color = if (isCurrentExt) OfficialSlateBg else OfficialTextPrimary,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            if (isRawEditMode) {
                                OutlinedTextField(
                                    value = codeEditorText,
                                    onValueChange = { codeEditorText = it },
                                    label = { Text("কোড এডিটর ($language)") },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = premiumTextFieldColors(OfficialSkyBlue),
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .testTag("argon_code_editor_field")
                                )
                            } else {
                                // Syntax-Highlighted Code View with Line Numbers
                                val vScroll = rememberScrollState()
                                val hScroll = rememberScrollState()
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF070B12))
                                        .border(1.dp, OfficialSlateBorder, RoundedCornerShape(10.dp))
                                        .verticalScroll(vScroll)
                                        .padding(vertical = 8.dp)
                                        .clickable { isRawEditMode = true }
                                        .testTag("argon_code_editor_field")
                                ) {
                                    val lineNumbers = remember(lineCount) {
                                        (1..lineCount).joinToString("\n")
                                    }
                                    Text(
                                        text = lineNumbers,
                                        color = Color(0xFF475569),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 17.sp,
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height((lineCount * 17).coerceAtLeast(180).dp)
                                            .background(OfficialSlateBorder.copy(alpha = 0.6f))
                                    )
                                    Text(
                                        text = highlightCodeSyntax(codeEditorText),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        lineHeight = 17.sp,
                                        modifier = Modifier
                                            .weight(1f)
                                            .horizontalScroll(hScroll)
                                            .padding(horizontal = 10.dp)
                                    )
                                }
                            }
                        }
                    }

                    "PREVIEW" -> {
                        NativeComposeSandboxedPreview(
                            code = codeEditorText,
                            fileName = fileName,
                            language = language
                        )
                    }

                    "FILES" -> {
                        if (workspaceState.savedArtifacts.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Download/archer/ ফোল্ডারে এখনো কোনো ফাইল সেভ করা হয়নি।",
                                    color = OfficialTextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(workspaceState.savedArtifacts, key = { it.id }) { artifact ->
                                    Surface(
                                        color = OfficialSlateCard,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, OfficialSlateBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "📄 ${artifact.primaryFileName}",
                                                    color = OfficialTextPrimary,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 12.5.sp,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    PremiumActionButton(
                                                        text = "💻 এডিট",
                                                        primaryColor = OfficialSkyBlue,
                                                        onClick = {
                                                            projectTitle = artifact.projectTitle
                                                            fileName = artifact.primaryFileName
                                                            language = artifact.language
                                                            codeEditorText = artifact.codeContent
                                                            activeStudioTab = "EDITOR"
                                                        }
                                                    )
                                                    PremiumActionButton(
                                                        text = "▶ রান",
                                                        primaryColor = OfficialEmerald,
                                                        onClick = {
                                                            projectTitle = artifact.projectTitle
                                                            fileName = artifact.primaryFileName
                                                            language = artifact.language
                                                            codeEditorText = artifact.codeContent
                                                            activeStudioTab = "PREVIEW"
                                                        }
                                                    )
                                                    PremiumActionButton(
                                                        text = "শেয়ার",
                                                        icon = Icons.Default.Share,
                                                        primaryColor = OfficialSkyBlue,
                                                        isOutlined = true,
                                                        onClick = { onShareArtifact(artifact) }
                                                    )
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "ভাষা: ${artifact.language} • পাথ: ${artifact.singleFilePath}",
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp,
                                                color = OfficialTextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. AI Code Generator & Live Voice/Text Patching Bar
            Surface(
                color = OfficialSlateCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, OfficialSlateBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "✨ Argon 4 AI কোড জেনারেটর ও লাইভ কোড ফিক্সার",
                        color = OfficialSkyBlue,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = codingPrompt,
                            onValueChange = { codingPrompt = it },
                            placeholder = {
                                Text(
                                    text = "নতুন অ্যাপ তৈরি বা কোড পরিবর্তন লিখুন (যেমন: ক্যালকুলেটর অ্যাপ বানিয়ে দাও)...",
                                    color = OfficialTextSecondary,
                                    fontSize = 11.sp
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            colors = premiumTextFieldColors(OfficialSkyBlue),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("argon_coding_prompt_input")
                        )
                        PremiumActionButton(
                            text = "কোড তৈরি",
                            primaryColor = OfficialSkyBlue,
                            onClick = {
                                val clean = codingPrompt.trim()
                                if (clean.isNotBlank()) {
                                    onGenerateCodeWithAi("কোড লিখে কোডিং স্টুডিওতে দেখাও: $clean")
                                    codingPrompt = ""
                                }
                            },
                            testTag = "argon_generate_code_btn"
                        )
                    }
                }
            }

            // 5. Bottom One-Click Actions: Copy | ▶ Live Preview | 💾 Save Single File | ☁️ 5TB Drive
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PremiumActionButton(
                    text = "Copy Code",
                    primaryColor = OfficialSkyBlue,
                    isOutlined = true,
                    onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText(fileName, codeEditorText))
                        android.widget.Toast.makeText(context, "✔ কোড ক্লিপবোর্ডে কপি হয়েছে!", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                )
                PremiumActionButton(
                    text = if (activeStudioTab == "PREVIEW") "💻 Code Editor" else "▶ Live Preview",
                    icon = Icons.Default.PlayArrow,
                    primaryColor = OfficialEmerald,
                    onClick = {
                        activeStudioTab = if (activeStudioTab == "PREVIEW") "EDITOR" else "PREVIEW"
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PremiumActionButton(
                    text = "💾 Save (Download/archer/)",
                    primaryColor = OfficialSkyBlue,
                    onClick = {
                        onSaveManualCodeToArcher(projectTitle, fileName, language, codeEditorText)
                        android.widget.Toast.makeText(
                            context,
                            "✔ সিঙ্গেল ফাইল '$fileName' Download/archer/ ফোল্ডারে সেভ হয়েছে!",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.weight(1f),
                    testTag = "save_to_archer_folder_btn"
                )
                PremiumActionButton(
                    text = "☁️ 5TB Drive Sync",
                    primaryColor = OfficialEmerald,
                    isOutlined = true,
                    onClick = {
                        onSaveManualCodeToArcher(projectTitle, fileName, language, codeEditorText)
                        com.example.service.GoogleDriveMemorySyncService.syncCodeFileFromStudio(
                            context = context,
                            fileName = fileName,
                            code = codeEditorText,
                            projectTitle = projectTitle
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun LiveCameraAndScreenVisionSheet(
    isFrontCamera: Boolean,
    visibleScreenText: String,
    onFlipCamera: () -> Unit,
    onLaunchSystemCamera: () -> Unit,
    onAnalyzeScreenWithAi: () -> Unit,
    onSendVisionPrompt: (String) -> Unit,
    onClose: () -> Unit
) {
    var visionQuestion by rememberSaveable { mutableStateOf("বর্তমান স্ক্রিনে কী দেখা যাচ্ছে বিশ্লেষণ করে বলো") }
    val screenCaptureState by com.example.service.ScreenCaptureSessionBus.state.collectAsState()
    val isMainScreenShareActive = screenCaptureState.hasMediaProjectionGrant && screenCaptureState.isCapturingFrames

    PremiumModalShell(
        badgeText = "SCREEN VISION • REAL-TIME MAIN SCREEN SHARE",
        title = "লাইভ মেইন স্ক্রিন শেয়ার ও ভিশন কন্ট্রোল",
        subtitle = if (isMainScreenShareActive) {
            "মেইন স্ক্রিন শেয়ার সচল আছে (ফ্রেম #${screenCaptureState.capturedFrameCount}) • এজেন্ট সরাসরি স্ক্রিন দেখছে"
        } else {
            "স্ক্রিন দেখার জন্য নিচের 'মেইন স্ক্রিন শেয়ার চালু করুন' বাটনে ট্যাপ করুন"
        },
        accentColor = OfficialSkyBlue,
        secondaryAccent = OfficialEmerald,
        onClose = onClose
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PremiumSectionCard(
                title = if (isMainScreenShareActive) "● MAIN SCREEN SHARE ACTIVE" else "মেইন স্ক্রিন শেয়ার স্ট্যাটাস",
                subtitle = screenCaptureState.statusBn,
                icon = Icons.Default.ScreenShare,
                accentColor = if (isMainScreenShareActive) OfficialEmerald else OfficialSkyBlue,
                badgeText = if (isMainScreenShareActive) "LIVE CAST" else "STANDBY"
            ) {
                Text(
                    text = if (isMainScreenShareActive) {
                        "মেইন স্ক্রিন শেয়ার চালু আছে। আপনি এখন যেকোনো অ্যাপে ঢুকে এজেন্টকে কাজ করতে বলতে পারেন।"
                    } else {
                        "নিচের বাটনে ট্যাপ করলে অ্যান্ড্রয়েড স্ক্রিন শেয়ার পারমিশন বক্স (Start now / Allow) আসবে। একবার Allow করলেই লাইভ স্ক্রিন শেয়ার চালু হবে।"
                    },
                    color = OfficialTextPrimary,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )

                if (visibleScreenText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = OfficialSlateInner,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, OfficialSlateBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "স্ক্রিন টেক্সট প্রিভিউ: ${visibleScreenText.take(220)}",
                            color = OfficialTextSecondary,
                            fontSize = 10.5.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PremiumActionButton(
                        text = if (isMainScreenShareActive) "স্ক্রিন শেয়ার রিস্টার্ট করুন" else "মেইন স্ক্রিন শেয়ার চালু করুন",
                        icon = Icons.Default.ScreenShare,
                        primaryColor = OfficialEmerald,
                        onClick = {
                            com.example.service.ScreenCaptureSessionBus.requestOneTimeScreenCapturePermission()
                            onClose()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    if (isMainScreenShareActive) {
                        val ctx = LocalContext.current
                        PremiumActionButton(
                            text = "স্ক্রিন শেয়ার বন্ধ করুন",
                            primaryColor = OfficialDangerRed,
                            isOutlined = true,
                            onClick = {
                                com.example.service.PermanentScreenCaptureService.stopService(ctx)
                                onClose()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            PremiumSectionCard(
                title = "মেইন স্ক্রিন সম্পর্কে এআই-কে প্রশ্ন করুন",
                subtitle = "স্ক্রিনে যা দেখা যাচ্ছে তার ওপর ভিত্তি করে এআই বিশ্লেষণ করবে",
                icon = Icons.Default.ScreenShare,
                accentColor = OfficialSkyBlue
            ) {
                OutlinedTextField(
                    value = visionQuestion,
                    onValueChange = { visionQuestion = it },
                    label = { Text("আপনার প্রশ্ন লিখুন") },
                    shape = RoundedCornerShape(12.dp),
                    colors = premiumTextFieldColors(OfficialSkyBlue),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                PremiumActionButton(
                    text = "⚡ এআই দিয়ে মেইন স্ক্রিন বিশ্লেষণ করুন",
                    primaryColor = OfficialSkyBlue,
                    onClick = {
                        onSendVisionPrompt(visionQuestion)
                        onClose()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
