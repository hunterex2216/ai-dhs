package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.AgentAbilitySwitchesBus
import com.example.service.CodingSession
import com.example.service.CodingSessionBus
import com.example.service.FloatingAvatarCharacter
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Page 1 of Main Settings ("🤖 এআই অ্যাবিলিটি"):
 * Official Dark Slate & Sky-Blue 7-Switch Ability Permissions Card + Studio Launchers.
 */
@Composable
fun AbilityPermissionSwitchesCard(
    geminiApiKey: String,
    modifier: Modifier = Modifier,
    onResultAnnouncement: (String) -> Unit = {}
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AgentAbilitySwitchesBus.ensureInitialized(context)
    }
    val abilityState by AgentAbilitySwitchesBus.state.collectAsState()

    val cardShape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("ability_permission_switches_card"),
        shape = cardShape,
        color = OfficialSlateCard,
        border = BorderStroke(1.dp, OfficialSlateBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🤖 এআই কোর অ্যাবিলিটি ও পারমিশন সুইচ",
                        color = OfficialTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "আপনার প্রয়োজন অনুযায়ী যেকোনো এআই ক্ষমতা অন বা অফ করুন",
                        color = OfficialTextSecondary,
                        fontSize = 11.sp
                    )
                }
                Surface(
                    color = OfficialSlateInner,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, OfficialSkyBlue.copy(alpha = 0.55f))
                ) {
                    Text(
                        text = "7 ABILITIES",
                        color = OfficialSkyBlue,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = OfficialSlateInner,
                border = BorderStroke(1.dp, OfficialSlateBorder)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // 1. Silent execution
                    AbilitySwitchRowItem(
                        icon = Icons.Default.VolumeOff,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "Silent Execution (সাইলেন্ট অ্যাকশন)",
                        subtitle = if (abilityState.silentExecution) {
                            "সক্রিয় — চুপচাপ সব ধাপ শেষ করে শেষে একবার রিপোর্ট দেবে"
                        } else {
                            "বন্ধ — প্রতিটি ধাপ মুখে বলে কাজ করবে"
                        },
                        checked = abilityState.silentExecution,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setSilentExecution(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Silent execution ON" else "Silent execution OFF"
                            )
                        },
                        testTag = "switch_silent_execution"
                    )

                    AbilityRowDivider()

                    // 2. Background web search
                    AbilitySwitchRowItem(
                        icon = Icons.Default.Search,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "Background Web & Maps Search",
                        subtitle = if (abilityState.backgroundWebSearch) {
                            "সক্রিয় — ব্রাউজার না খুলেই ব্যাকগ্রাউন্ডে তথ্য ও লোকেশন খুঁজবে"
                        } else {
                            "বন্ধ — ব্যাকগ্রাউন্ড ওয়েব সার্চ অফ আছে"
                        },
                        checked = abilityState.backgroundWebSearch,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setBackgroundWebSearch(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Background web search ON" else "Background web search OFF"
                            )
                        },
                        testTag = "switch_background_web_search"
                    )

                    AbilityRowDivider()

                    // 3. Make images with Gemini (Nano Banana 2.5 Flash Image)
                    AbilitySwitchRowItem(
                        icon = Icons.Default.Brush,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "ছবি তৈরি ও এডিট (Nano Banana 8K)",
                        subtitle = if (abilityState.makeImagesWithGemini) {
                            "সক্রিয় — ভয়েস বা টেক্সটে বললেই ডেডিকেটেড ইমেজ স্টুডিওতে ছবি তৈরি করবে"
                        } else {
                            "বন্ধ — ছবি তৈরি অফ আছে"
                        },
                        checked = abilityState.makeImagesWithGemini,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setMakeImagesWithGemini(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Make images with Gemini ON" else "Make images with Gemini OFF"
                            )
                        },
                        testTag = "switch_make_images_gemini"
                    )

                    AbilityRowDivider()

                    // 4. Ignore background audio / Noise Cancellation
                    AbilitySwitchRowItem(
                        icon = Icons.Default.GraphicEq,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "Noise Cancel (ব্যাকগ্রাউন্ড কথা ইগনোর)",
                        subtitle = if (abilityState.ignoreBackgroundAudio) {
                            "সক্রিয় — শুধু আপনার স্পষ্ট কথা শুনবে, আশেপাশের কথা ইগনোর করবে"
                        } else {
                            "বন্ধ — আশেপাশের সব শব্দ শুনবে"
                        },
                        checked = abilityState.ignoreBackgroundAudio,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setIgnoreBackgroundAudio(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Noise cancellation & background audio ignore ON" else "Ignore background audio OFF"
                            )
                        },
                        testTag = "switch_ignore_background_audio"
                    )

                    AbilityRowDivider()

                    // 5. Show tap indicator
                    AbilitySwitchRowItem(
                        icon = Icons.Default.TouchApp,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "Show Tap & Neon Target Indicator",
                        subtitle = if (abilityState.showTapIndicator) {
                            "সক্রিয় — কোথায় ক্লিক করবে তা নিওন টার্গেট রিং দিয়ে দেখাবে"
                        } else {
                            "বন্ধ — ট্যাপ ইন্ডিকেটর লুকানো থাকবে"
                        },
                        checked = abilityState.showTapIndicator,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setShowTapIndicator(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Show tap indicator ON" else "Show tap indicator OFF"
                            )
                        },
                        testTag = "switch_show_tap_indicator"
                    )

                    AbilityRowDivider()

                    // 6. Send crash reports & Self-Diagnostics
                    AbilitySwitchRowItem(
                        icon = Icons.Default.BugReport,
                        iconBadgeBg = OfficialSkyBlue,
                        title = "Auto Error Guard & Diagnostics",
                        subtitle = if (abilityState.sendCrashReports) {
                            "সক্রিয় — কোনো এরর হলে স্বয়ংক্রিয়ভাবে রিট্রাই ও লগ করবে (${abilityState.crashReportsCount} logged)"
                        } else {
                            "বন্ধ — অটো এরর লগিং অফ আছে"
                        },
                        checked = abilityState.sendCrashReports,
                        onCheckedChange = { enabled ->
                            AgentAbilitySwitchesBus.setSendCrashReports(context, enabled)
                            onResultAnnouncement(
                                if (enabled) "Send crash reports ON" else "Send crash reports OFF"
                            )
                        },
                        testTag = "switch_send_crash_reports"
                    )

                    AbilityRowDivider()

                    // 7. Floating 3D Globe Button (Appears ONLY when exiting the app)
                    val isFloatingGlobeEnabled = abilityState.floatingAvatar != FloatingAvatarCharacter.OFF
                    AbilitySwitchRowItem(
                        icon = Icons.Default.Public,
                        iconBadgeBg = OfficialEmerald,
                        title = "Floating AI Orb (অ্যাপের বাইরে ভাসমান গ্লোব)",
                        subtitle = if (isFloatingGlobeEnabled) {
                            "সক্রিয় — অ্যাপ থেকে বের হলেই ভাসমান গ্লোব ও লাইভ ভয়েস ওয়েভফর্ম আসবে"
                        } else {
                            "বন্ধ — ভাসমান গ্লোব বাটন অফ আছে"
                        },
                        checked = isFloatingGlobeEnabled,
                        onCheckedChange = { enabled ->
                            val target = if (enabled) FloatingAvatarCharacter.ARCHER else FloatingAvatarCharacter.OFF
                            AgentAbilitySwitchesBus.setFloatingAvatar(context, target)
                            onResultAnnouncement(
                                if (enabled) "Floating 3D Globe Button ON (Outside App)" else "Floating 3D Globe Button OFF"
                            )
                        },
                        testTag = "row_floating_avatar"
                    )
                }
            }

            // Studio Quick Launchers & On-Demand Tools
            ArcherAiStudioFeaturesCard(
                geminiApiKey = geminiApiKey,
                onResultAnnouncement = onResultAnnouncement
            )
        }
    }
}

/**
 * Clean Official Dark Slate Studio Launchers (Nano Banana Image Studio, Dedicated Coding Studio IDE, Maps & Search).
 */
@Composable
fun ArcherAiStudioFeaturesCard(
    geminiApiKey: String,
    modifier: Modifier = Modifier,
    onResultAnnouncement: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        AgentAbilitySwitchesBus.ensureInitialized(context)
    }
    val abilityState by AgentAbilitySwitchesBus.state.collectAsState()

    var activeQuickTool by rememberSaveable { mutableStateOf("NONE") } // "NONE", "IMAGE", "MAPS"
    var imagePromptDraft by rememberSaveable { mutableStateOf("") }
    var searchQueryDraft by rememberSaveable { mutableStateOf("") }
    var statusBannerText by rememberSaveable { mutableStateOf("") }

    val hasImageOutput = abilityState.lastGeneratedImagePath.isNotBlank()
    val hasSearchOutput = abilityState.lastBackgroundSearchSummary.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("archer_ai_studio_features_card"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 3 Clean Official Studio Launch Buttons: Image Studio | Coding Studio | Maps Search
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PremiumActionButton(
                text = "🎨 ছবি তৈরি স্টুডিও",
                primaryColor = OfficialSkyBlue,
                onClick = {
                    AgentAbilitySwitchesBus.openDedicatedImageStudioPage(imagePromptDraft)
                },
                modifier = Modifier.weight(1f)
            )

            PremiumActionButton(
                text = "💻 কোডিং স্টুডিও",
                primaryColor = OfficialEmerald,
                onClick = {
                    if (CodingSessionBus.session.value == null) {
                        CodingSessionBus.start(
                            CodingSession(
                                id = "code_${System.currentTimeMillis()}",
                                title = "Archer AI Coding Studio",
                                fileName = "index.html",
                                language = "HTML/JS",
                                code = """<!DOCTYPE html>
<html lang="bn">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>ARCHER AI — Coding Studio</title>
  <style>
    body { background: #0B0F19; color: #F8FAFC; font-family: system-ui, sans-serif; text-align: center; padding: 32px; margin: 0; }
    .card { border: 1px solid #334155; border-radius: 16px; padding: 24px; max-width: 460px; margin: 0 auto; background: #1E293B; }
    h1 { color: #38BDF8; font-size: 20px; }
    button { background: #38BDF8; color: #0B0F19; border: none; padding: 10px 20px; border-radius: 8px; font-weight: bold; cursor: pointer; }
  </style>
</head>
<body>
  <div class="card">
    <h1>ARCHER AI • MINI AI IDE</h1>
    <p>কোড এডিটর এবং লাইভ ওয়েবভিউ প্রিভিউ প্রস্তুত।</p>
    <button onclick="this.textContent='✔ প্রিভিউ সচল!'">Run Preview</button>
  </div>
</body>
</html>""",
                                activeTab = "EDITOR",
                                statusMessageBn = "ভয়েস বা টেক্সট দিয়ে যেকোনো ওয়েব অ্যাপ বা কোড তৈরি ও লাইভ প্রিভিউ করুন।"
                            )
                        )
                    }
                },
                modifier = Modifier.weight(1f)
            )

            PremiumActionButton(
                text = if (activeQuickTool == "MAPS") "📍 ম্যাপস ✕" else "📍 ম্যাপস সার্চ",
                primaryColor = OfficialSkyBlue,
                isOutlined = activeQuickTool != "MAPS",
                onClick = {
                    activeQuickTool = if (activeQuickTool == "MAPS") "NONE" else "MAPS"
                },
                modifier = Modifier.weight(1f)
            )
        }

        if (abilityState.isWorkingOnStudioTask || statusBannerText.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = OfficialSlateInner,
                border = BorderStroke(
                    1.dp,
                    if (statusBannerText.startsWith("ABILITY_SWITCH_OFF")) OfficialDangerRed else OfficialSkyBlue
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (abilityState.isWorkingOnStudioTask) {
                            "⏳ এআই কাজ করছে... অনুগ্রহ করে অপেক্ষা করুন"
                        } else {
                            statusBannerText
                        },
                        color = OfficialTextPrimary,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 15.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!abilityState.isWorkingOnStudioTask && statusBannerText.isNotBlank()) {
                        Text(
                            text = "✕",
                            color = OfficialDangerRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clickable { statusBannerText = "" }
                        )
                    }
                }
            }
        }

        // IMAGE PREVIEW SUMMARY
        AnimatedVisibility(visible = activeQuickTool == "IMAGE" || hasImageOutput) {
            PremiumSectionCard(
                title = "🎨 ছবি তৈরি ও এডিট (Nano Banana 2.5 Flash Image)",
                subtitle = "ফুল এইচডি প্রিভিউ এবং ভয়েস এডিট করতে ইমেজ স্টুডিওতে ট্যাপ করুন",
                icon = Icons.Default.AutoFixHigh,
                accentColor = OfficialSkyBlue,
                badgeText = if (abilityState.makeImagesWithGemini) "NANO BANANA" else "OFF"
            ) {
                if (hasImageOutput) {
                    val imgFile = File(abilityState.lastGeneratedImagePath)
                    val previewBitmap = remember(abilityState.lastGeneratedImagePath) {
                        if (imgFile.exists()) BitmapFactory.decodeFile(imgFile.absolutePath) else null
                    }
                    Text(
                        text = if (abilityState.isImageSavedToArcher) {
                            "✔ Saved: ${abilityState.savedArcherImagePath.substringAfterLast('/')}"
                        } else {
                            "ছবিটি প্রস্তুত — ফুল এইচডি দেখতে বা সেভ করতে নিচে ট্যাপ করুন"
                        },
                        color = if (abilityState.isImageSavedToArcher) OfficialEmerald else OfficialSkyBlue,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (previewBitmap != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Generated or edited Gemini image",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(165.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, OfficialSlateBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    AgentAbilitySwitchesBus.openDedicatedImageStudioPage()
                                }
                        )
                    }
                    if (abilityState.pendingImageSaveConfirmation && !abilityState.isImageSavedToArcher) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PremiumActionButton(
                                text = "💾 Download/archer/-এ সেভ",
                                onClick = {
                                    val msg = AgentAbilitySwitchesBus.confirmSavePendingImageToArcherFolder(context)
                                    statusBannerText = msg
                                    onResultAnnouncement(msg)
                                },
                                primaryColor = OfficialEmerald,
                                modifier = Modifier.weight(1f)
                            )
                            PremiumActionButton(
                                text = "🎨 ফুল স্টুডিও খুলুন",
                                onClick = {
                                    AgentAbilitySwitchesBus.openDedicatedImageStudioPage()
                                },
                                primaryColor = OfficialSkyBlue,
                                isOutlined = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // MAPS / SEARCH TOOL
        AnimatedVisibility(visible = activeQuickTool == "MAPS" || hasSearchOutput) {
            PremiumSectionCard(
                title = "📍 ব্যাকগ্রাউন্ড ওয়েব ও ম্যাপস সার্চ",
                subtitle = "ব্রাউজার না খুলেই লোকেশন, দূরত্ব ও তথ্য",
                icon = Icons.Default.LocationOn,
                accentColor = OfficialSkyBlue,
                badgeText = "LIVE MAPS"
            ) {
                if (activeQuickTool == "MAPS") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQueryDraft,
                            onValueChange = { searchQueryDraft = it },
                            placeholder = {
                                Text(
                                    text = "লোকেশন বা তথ্য খুঁজুন...",
                                    color = OfficialTextSecondary,
                                    fontSize = 12.sp
                                )
                            },
                            singleLine = true,
                            colors = darkStudioTextFieldColors(OfficialSkyBlue),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("input_background_web_search")
                        )
                        PremiumActionButton(
                            text = "সার্চ",
                            onClick = {
                                val q = searchQueryDraft.trim().ifBlank { "Top hospitals in Dhaka" }
                                scope.launch {
                                    val res = AgentAbilitySwitchesBus.searchWebOrMapsInBackground(
                                        context = context,
                                        apiKey = geminiApiKey,
                                        query = q,
                                        useMapsGrounding = true
                                    )
                                    statusBannerText = res
                                    onResultAnnouncement(res)
                                }
                            },
                            primaryColor = OfficialSkyBlue,
                            testTag = "btn_run_background_web_search"
                        )
                    }
                }

                if (hasSearchOutput) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = OfficialSlateInner,
                        border = BorderStroke(1.dp, OfficialSlateBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = abilityState.lastBackgroundSearchSummary,
                            color = OfficialTextPrimary,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun darkStudioTextFieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = OfficialTextPrimary,
    unfocusedTextColor = OfficialTextPrimary,
    focusedContainerColor = OfficialSlateInner,
    unfocusedContainerColor = OfficialSlateInner,
    focusedBorderColor = accent,
    unfocusedBorderColor = OfficialSlateBorder,
    cursorColor = accent
)

@Composable
private fun AbilitySwitchRowItem(
    icon: ImageVector,
    iconBadgeBg: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    iconTint: Color = OfficialSkyBlue
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (checked) OfficialSkyBlue.copy(alpha = 0.16f) else OfficialSlateCard
                    )
                    .border(
                        1.dp,
                        if (checked) OfficialSkyBlue.copy(alpha = 0.6f) else OfficialSlateBorder,
                        RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (checked) OfficialSkyBlue else OfficialTextSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = OfficialTextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = OfficialTextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = OfficialSlateBg,
                checkedTrackColor = OfficialSkyBlue,
                checkedBorderColor = OfficialSkyBlue,
                uncheckedThumbColor = OfficialTextSecondary,
                uncheckedTrackColor = OfficialSlateCard,
                uncheckedBorderColor = OfficialSlateBorder
            )
        )
    }
}

@Composable
private fun AbilityRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp, end = 12.dp),
        thickness = 1.dp,
        color = OfficialSlateBorder.copy(alpha = 0.6f)
    )
}

private data class FloatingGlobePoint(
    val baseX: Float,
    val baseY: Float,
    val baseZ: Float,
    val theta: Float,
    val phiRatio: Float
)

/**
 * Draggable 3D Holographic Floating Globe Button that matches the Main 3D Globe (`MatrixGreen`,
 * `CyberCyan`, `ParticleGold`) and renders the exact same amplified 3D spherical harmonic particle wave,
 * expanding sonic energy wave rings, and 360° sinusoidal voice waveform when speaking or listening!
 */
@Composable
fun InAppFloatingCharacterBubble(
    isSpeaking: Boolean,
    onTapBubble: () -> Unit,
    modifier: Modifier = Modifier,
    isListening: Boolean = false,
    rmsLevel: Float = 0f
) {
    val abilityState by AgentAbilitySwitchesBus.state.collectAsState()
    if (abilityState.floatingAvatar == FloatingAvatarCharacter.OFF) return

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val isActiveVoice = isSpeaking || isListening || rmsLevel > 0.05f
    val infiniteTransition = rememberInfiniteTransition(label = "floating_globe_transition")

    val fibonacciPoints = remember {
        val count = 110
        val goldenAngle = (PI * (3.0 - sqrt(5.0))).toFloat()
        List(count) { i ->
            val y = 1f - (i.toFloat() / (count - 1).toFloat()) * 2f
            val rAtY = sqrt((1f - y * y).coerceAtLeast(0f))
            val theta = goldenAngle * i
            FloatingGlobePoint(
                baseX = cos(theta) * rAtY,
                baseY = y,
                baseZ = sin(theta) * rAtY,
                theta = theta,
                phiRatio = i.toFloat() / count.toFloat()
            )
        }
    }

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 4800 else 9600,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_globe_rotation"
    )

    val harmonicWavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 880 else 2200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_globe_wave_phase"
    )

    val hudOrbitDeg by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_globe_hud_orbit"
    )

    val counterOrbitDeg by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_globe_counter_orbit"
    )

    val wavePulse by infiniteTransition.animateFloat(
        initialValue = 0.91f,
        targetValue = if (isActiveVoice) 1.18f else 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 340 else 1500,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floating_globe_pulse"
    )

    val sonicWaveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isActiveVoice) 1050 else 2300,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "floating_sonic_wave"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(76.dp)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .clip(CircleShape)
            .clickable { onTapBubble() }
            .testTag("in_app_floating_avatar_bubble")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f, size.height * 0.46f)
            val baseRadius = size.minDimension * 0.31f
            val rmsBoost = 1f + rmsLevel * 0.28f
            val sphereRadius = baseRadius * (wavePulse * rmsBoost).coerceIn(0.90f, 1.22f)

            // 1. Deep Holographic Emerald-Cyan Plasma Aura & Core (Matching Main Globe)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        MatrixGreen.copy(alpha = if (isActiveVoice) 0.54f else 0.38f),
                        CyberCyan.copy(alpha = if (isActiveVoice) 0.38f else 0.26f),
                        Color(0xFF042B18).copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = baseRadius * 1.54f
                ),
                radius = baseRadius * 1.54f,
                center = center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFE0FFFF).copy(alpha = if (isActiveVoice) 0.98f else 0.90f),
                        CyberCyan.copy(alpha = if (isActiveVoice) 0.82f else 0.68f),
                        MatrixGreen.copy(alpha = 0.50f),
                        Color(0xFF031B12).copy(alpha = 0.92f)
                    ),
                    center = center,
                    radius = sphereRadius * 0.96f
                ),
                radius = sphereRadius * 0.96f,
                center = center
            )

            // 2. Expanding Sonic Energy Wave Rings & 360° Sinusoidal Voice Waveform (Exact match with Main Globe)
            if (isActiveVoice) {
                val waveColors = listOf(MatrixGreen, CyberCyan, ParticleGold)
                for (wIdx in 0..2) {
                    val phase = (sonicWaveProgress + wIdx * 0.33f) % 1f
                    val fade = (1f - phase).coerceIn(0f, 1f)
                    val ringRadius = baseRadius * (0.98f + phase * (0.50f + rmsLevel * 0.18f))
                    drawCircle(
                        color = waveColors[wIdx].copy(alpha = (0.72f * fade).coerceIn(0f, 0.88f)),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = (1.1f + fade * 1.6f).dp.toPx())
                    )
                }

                val wavePath = Path()
                val waveSteps = 54
                val dynamicWaveAmp = (3.0f + rmsLevel * 7.8f + (if (isSpeaking) 2.5f else 1.2f)).dp.toPx()
                for (s in 0..waveSteps) {
                    val angle = (2.0 * PI * s / waveSteps).toFloat()
                    val mod1 = sin(angle * 8f + harmonicWavePhase * 2.2f)
                    val mod2 = cos(angle * 5f - harmonicWavePhase * 1.5f)
                    val rWave = sphereRadius * 1.06f + (mod1 * 0.65f + mod2 * 0.35f) * dynamicWaveAmp
                    val wx = center.x + cos(angle) * rWave
                    val wy = center.y + sin(angle) * rWave
                    if (s == 0) wavePath.moveTo(wx, wy) else wavePath.lineTo(wx, wy)
                }
                wavePath.close()
                drawPath(
                    path = wavePath,
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            MatrixGreen.copy(alpha = 0.90f),
                            CyberCyan.copy(alpha = 0.94f),
                            ParticleGold.copy(alpha = 0.88f),
                            CyberCyan.copy(alpha = 0.94f),
                            MatrixGreen.copy(alpha = 0.90f)
                        ),
                        center = center
                    ),
                    style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // 3. Counter-Rotating 3D Tactical HUD Radar Rings
            rotate(degrees = hudOrbitDeg, pivot = center) {
                val arcRadius = baseRadius * 1.15f
                val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                for (a in 0 until 4) {
                    val arcColor = when (a) {
                        0 -> MatrixGreen.copy(alpha = 0.80f)
                        1 -> CyberCyan.copy(alpha = 0.80f)
                        2 -> ParticleGold.copy(alpha = 0.76f)
                        else -> CyberCyan.copy(alpha = 0.80f)
                    }
                    drawArc(
                        color = arcColor,
                        startAngle = a * 90f + 10f,
                        sweepAngle = 54f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            rotate(degrees = counterOrbitDeg, pivot = center) {
                val innerArcR = baseRadius * 1.07f
                val innerTopLeft = Offset(center.x - innerArcR, center.y - innerArcR)
                val innerSize = Size(innerArcR * 2f, innerArcR * 2f)
                for (b in 0 until 6) {
                    drawArc(
                        color = if (b % 2 == 0) CyberCyan.copy(alpha = 0.55f) else MatrixGreen.copy(alpha = 0.55f),
                        startAngle = b * 60f + 8f,
                        sweepAngle = 30f,
                        useCenter = false,
                        topLeft = innerTopLeft,
                        size = innerSize,
                        style = Stroke(width = 1.1.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // 4. 3D Fibonacci Sphere with Amplified Spherical Harmonic Wave Displacement
            val cosRotY = cos(rotationAngle)
            val sinRotY = sin(rotationAngle)
            val tiltX = 0.24f
            val cosTiltX = cos(tiltX)
            val sinTiltX = sin(tiltX)

            val waveAmplitude = when {
                isSpeaking -> 0.175f + rmsLevel * 0.20f
                isListening -> 0.145f + rmsLevel * 0.18f
                else -> 0.025f
            }

            for (idx in fibonacciPoints.indices) {
                val pt = fibonacciPoints[idx]
                val xRotY = pt.baseX * cosRotY + pt.baseZ * sinRotY
                val zRotY = -pt.baseX * sinRotY + pt.baseZ * cosRotY
                val y3d = pt.baseY * cosTiltX - zRotY * sinTiltX
                val z3d = pt.baseY * sinTiltX + zRotY * cosTiltX
                val x3d = xRotY

                val primaryHarmonic = sin(pt.theta * 5f + harmonicWavePhase * 1.4f) *
                    cos(pt.phiRatio * 14f - harmonicWavePhase * 1.2f)
                val secondaryHarmonic = sin(pt.theta * 9f - harmonicWavePhase * 2.1f) * 0.42f
                val harmonicWave = 1f + waveAmplitude * (primaryHarmonic + secondaryHarmonic)

                val perspective = 2.6f / (2.6f - z3d * 0.55f)
                val effectiveRadius = sphereRadius * harmonicWave * perspective * 0.93f

                val projX = center.x + x3d * effectiveRadius
                val projY = center.y + y3d * effectiveRadius
                val depthNormalized = ((z3d + 1f) * 0.5f).coerceIn(0.16f, 1f)
                val dotRadius = (0.75f + depthNormalized * 1.25f).dp.toPx()

                val particleColor = when {
                    x3d < -0.20f && y3d > -0.65f && y3d < 0.68f -> {
                        ParticleGold.copy(alpha = (0.30f + depthNormalized * 0.70f).coerceIn(0.2f, 1f))
                    }
                    x3d < -0.04f && idx % 2 == 0 -> {
                        MatrixGreen.copy(alpha = (0.28f + depthNormalized * 0.70f).coerceIn(0.2f, 0.96f))
                    }
                    else -> {
                        CyberCyan.copy(alpha = (0.26f + depthNormalized * 0.74f).coerceIn(0.2f, 0.98f))
                    }
                }

                drawCircle(
                    color = particleColor,
                    radius = dotRadius,
                    center = Offset(projX, projY)
                )
            }
        }

        // Bottom Micro-Badge matching Main Globe
        Surface(
            color = Color(0xD9031510),
            shape = RoundedCornerShape(50),
            border = BorderStroke(0.9.dp, MatrixGreen.copy(alpha = 0.80f)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 2.dp)
        ) {
            Text(
                text = when {
                    isSpeaking -> "SPEAKING"
                    isActiveVoice -> "LIVE"
                    else -> "GLOBE"
                },
                color = Color(0xFFD8FFF0),
                fontSize = 7.5.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
            )
        }
    }
}
