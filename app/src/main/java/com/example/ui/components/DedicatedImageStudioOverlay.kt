package com.example.ui.components

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.AgentAbilitySwitchesBus
import com.example.service.PremiumImageModelTier
import kotlinx.coroutines.launch
import java.io.File

/**
 * Dedicated "ছবি তৈরি" (Image Studio) Page powered by Nano Banana (`gemini-2.5-flash-image`):
 * - Automatically opens whenever the user asks via voice or text to generate or edit an image.
 * - Uses official endpoint: `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-image:generateContent`
 *   with 30s timeout and 2x automatic retry.
 * - Auto-enhances Bangla/short prompts into cinematic 8K English prompts in the background.
 * - Clean Official Enterprise Dark UI (`#0B0F19` background, `#1E293B` cards, `1px` `#334155` borders, `#38BDF8` accent).
 * - Includes "Save to Download/archer/", "Sync to 5TB Google Drive", "Edit by Voice", and "Regenerate".
 */
@Composable
fun DedicatedImageStudioOverlay(
    apiKey: String = "",
    onStatusSpoken: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AgentAbilitySwitchesBus.state.collectAsState()

    if (!state.showDedicatedImageStudioPage) return

    BackHandler {
        AgentAbilitySwitchesBus.closeDedicatedImageStudioPage()
    }

    var promptInput by remember(state.activeImagePrompt) {
        mutableStateOf(state.activeImagePrompt)
    }

    var showPremiumAccessSheet by remember { mutableStateOf(false) }
    var premiumKeyInput by remember(state.premiumImageApiKey, apiKey) {
        mutableStateOf(state.premiumImageApiKey.ifBlank { apiKey })
    }
    var gmailInput by remember(state.connectedGmailAccount) {
        mutableStateOf(state.connectedGmailAccount)
    }

    // Official Enterprise Palette
    val enterpriseBg = Color(0xFF0B0F19)
    val enterpriseCard = Color(0xFF1E293B)
    val enterpriseBorder = Color(0xFF334155)
    val officialBlue = Color(0xFF38BDF8)
    val officialGreen = Color(0xFF22C55E)

    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (pickedEmail.isNotBlank()) {
                gmailInput = pickedEmail
                val msg = AgentAbilitySwitchesBus.connectMainGmailForPremium(context, pickedEmail)
                onStatusSpoken(msg)
            }
        }
    }

    // Voice Edit Launcher: listens to user's spoken edit instruction and sends current image + instruction to gemini-2.5-flash-image
    val voiceEditLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenEdit = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spokenEdit.isNotBlank()) {
                promptInput = spokenEdit
                scope.launch {
                    val res = AgentAbilitySwitchesBus.createImageWithGemini(
                        context = context,
                        apiKey = state.premiumImageApiKey.ifBlank { apiKey },
                        prompt = spokenEdit,
                        editExistingImage = true
                    )
                    onStatusSpoken(res)
                }
            }
        }
    }

    val decodedBitmap = remember(state.lastGeneratedImagePath) {
        val path = state.lastGeneratedImagePath
        if (path.isNotBlank() && File(path).exists()) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        } else {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(enterpriseBg)
            .safeDrawingPadding()
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
            .testTag("dedicated_image_studio_page")
    ) {
        // 1. Official Enterprise Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(enterpriseCard)
                .border(1.dp, enterpriseBorder, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(officialBlue.copy(alpha = 0.16f))
                        .border(1.dp, officialBlue, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Nano Banana Image Studio",
                        tint = officialBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "NANO BANANA • GEMINI-2.5-FLASH-IMAGE",
                        color = officialBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "ছবি তৈরি স্টুডিও (8K Image Studio)",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = Color(0xFF0F172A),
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, enterpriseBorder, CircleShape)
                    .clickable { AgentAbilitySwitchesBus.closeDedicatedImageStudioPage() }
                    .testTag("btn_close_dedicated_image_page")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close Image Studio",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Model Selector & API / Gmail Configuration Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(enterpriseCard)
                .border(1.dp, enterpriseBorder, RoundedCornerShape(14.dp))
                .padding(11.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "মডেল ইঞ্জিন: ${state.selectedImageModelTier.badgeLabel}",
                        color = officialBlue,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Auto-Prompt Enhancer (Bangla -> 8K Cinematic English) সক্রিয়",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, enterpriseBorder, RoundedCornerShape(8.dp))
                        .clickable { showPremiumAccessSheet = !showPremiumAccessSheet }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("btn_toggle_premium_gmail_api_panel")
                ) {
                    Text(
                        text = when {
                            state.isGmailPremiumLinked -> "✔ ${state.connectedGmailAccount.substringBefore("@")}"
                            state.premiumImageApiKey.isNotBlank() || apiKey.isNotBlank() -> "🔑 API Active"
                            else -> "🔑 API / Gmail"
                        },
                        color = officialBlue,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PremiumImageModelTier.entries.forEach { tier ->
                    val isSelected = state.selectedImageModelTier == tier
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) officialBlue.copy(alpha = 0.2f) else Color(0xFF0F172A))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) officialBlue else enterpriseBorder,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                AgentAbilitySwitchesBus.setSelectedImageModelTier(context, tier)
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                            .testTag("pill_image_model_${tier.id}")
                    ) {
                        Text(
                            text = tier.badgeLabel,
                            color = if (isSelected) officialBlue else Color(0xFF94A3B8),
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            if (showPremiumAccessSheet) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, enterpriseBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                        .testTag("panel_premium_gmail_and_api_key")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Key,
                            contentDescription = "Gemini API Key",
                            tint = officialBlue,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Gemini API Key (gemini-2.5-flash-image):",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = premiumKeyInput,
                        onValueChange = { premiumKeyInput = it },
                        placeholder = {
                            Text("AIzaSy... API Key বসান", color = Color(0xFF64748B), fontSize = 11.sp)
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = officialBlue,
                            unfocusedBorderColor = enterpriseBorder,
                            cursorColor = officialBlue
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_premium_image_api_key")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(officialBlue)
                                .clickable {
                                    val msg = AgentAbilitySwitchesBus.savePremiumApiKey(context, premiumKeyInput)
                                    onStatusSpoken(msg)
                                }
                                .padding(vertical = 8.dp)
                                .testTag("btn_save_premium_image_api_key"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Save API Key",
                                color = Color(0xFF0B0F19),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(enterpriseCard)
                                .border(1.dp, enterpriseBorder, RoundedCornerShape(8.dp))
                                .clickable {
                                    if (gmailInput.isBlank()) {
                                        runCatching {
                                            val pickIntent = AccountManager.newChooseAccountIntent(
                                                null, null, arrayOf("com.google"), null, null, null, null
                                            )
                                            googleAccountPickerLauncher.launch(pickIntent)
                                        }
                                    } else {
                                        val msg = AgentAbilitySwitchesBus.connectMainGmailForPremium(context, gmailInput)
                                        onStatusSpoken(msg)
                                    }
                                }
                                .padding(vertical = 8.dp)
                                .testTag("btn_connect_main_gmail"),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AccountCircle,
                                    contentDescription = "Link Gmail",
                                    tint = officialBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Link Gmail",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Prompt Input & Auto-Enhanced 8K Prompt Preview Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(enterpriseCard)
                .border(1.dp, enterpriseBorder, RoundedCornerShape(14.dp))
                .padding(12.dp)
        ) {
            Text(
                text = "ছবির বর্ণনা বা এডিট নির্দেশনা (বাংলা বা ইংরেজিতে বলুন বা লিখুন):",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = promptInput,
                onValueChange = { promptInput = it },
                placeholder = {
                    Text(
                        "যেমন: বৃষ্টির দিনে চায়ের দোকানে বসে চা খাওয়ার ফটোরিয়ালিস্টিক ছবি...",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                },
                singleLine = false,
                maxLines = 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = officialBlue,
                    unfocusedBorderColor = enterpriseBorder,
                    cursorColor = officialBlue,
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0F172A)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_dedicated_image_prompt")
            )

            if (state.expandedEnglish8kPrompt.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "✨ 8K Auto-Enhanced Prompt: ${state.expandedEnglish8kPrompt}",
                    color = officialBlue,
                    fontSize = 9.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(officialBlue)
                        .clickable(enabled = !state.isGeneratingImage) {
                            val targetPrompt = promptInput.trim().ifBlank {
                                state.activeImagePrompt.ifBlank { "Realistic scenic river in Bangladesh at golden hour" }
                            }
                            scope.launch {
                                val res = AgentAbilitySwitchesBus.createImageWithGemini(
                                    context = context,
                                    apiKey = state.premiumImageApiKey.ifBlank { apiKey },
                                    prompt = targetPrompt,
                                    editExistingImage = false
                                )
                                onStatusSpoken(res)
                            }
                        }
                        .padding(vertical = 11.dp)
                        .testTag("btn_dedicated_generate_real_image"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isGeneratingImage) "⏳ Generating 8K HD..." else "🎨 Generate with Nano Banana",
                        color = Color(0xFF0B0F19),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                if (decodedBitmap != null) {
                    Box(
                        modifier = Modifier
                            .weight(0.85f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, officialBlue, RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.isGeneratingImage) {
                                val targetPrompt = promptInput.trim().ifBlank { "Make the lighting more dramatic and enhance 8K details" }
                                scope.launch {
                                    val res = AgentAbilitySwitchesBus.createImageWithGemini(
                                        context = context,
                                        apiKey = state.premiumImageApiKey.ifBlank { apiKey },
                                        prompt = targetPrompt,
                                        editExistingImage = true
                                    )
                                    onStatusSpoken(res)
                                }
                            }
                            .padding(vertical = 11.dp)
                            .testTag("btn_dedicated_edit_real_image"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Apply Edit",
                                tint = officialBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "Apply Edit",
                                color = officialBlue,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 4. Center High-Resolution Image Viewport + Sleek Futuristic Loading Indicator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0F172A))
                .border(
                    width = 1.dp,
                    color = if (state.isGeneratingImage) officialBlue else enterpriseBorder,
                    shape = RoundedCornerShape(16.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isGeneratingImage -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = officialBlue,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(50.dp)
                        )
                        Text(
                            text = "Nano Banana (gemini-2.5-flash-image) 8K রেন্ডারিং চলছে...",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "\"${state.activeImagePrompt}\"",
                            color = officialBlue,
                            fontSize = 11.5.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        LinearProgressIndicator(
                            color = officialBlue,
                            trackColor = enterpriseCard,
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }

                decodedBitmap != null -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            bitmap = decodedBitmap,
                            contentDescription = state.activeImagePrompt.ifBlank { "Generated Nano Banana HD Image" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xD90B0F19))
                                .border(1.dp, enterpriseBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "⚡ ${state.lastUsedImageModelName}",
                                color = officialBlue,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(text = "🖼️", fontSize = 36.sp)
                        Text(
                            text = state.imageProgressMessageBn.ifBlank {
                                "যেকোনো ছবির কথা বললে বা উপরে লিখে ট্যাপ করলে Nano Banana (gemini-2.5-flash-image) দিয়ে ফুল এইচডি ছবি তৈরি হবে।"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 5. Action Buttons Below Image: "Save to Download/archer/", "Sync to 5TB Google Drive", "Edit by Voice", "Regenerate"
        if (decodedBitmap != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(enterpriseCard)
                    .border(1.dp, enterpriseBorder, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.imageProgressMessageBn.isNotBlank()) {
                    Text(
                        text = state.imageProgressMessageBn,
                        color = if (state.isImageSavedToArcher || state.isImageSyncedToDrive) officialGreen else Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Row 1: Save to Download/archer/ + Sync to 5TB Google Drive
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(officialGreen)
                            .clickable {
                                val msg = AgentAbilitySwitchesBus.confirmSavePendingImageToArcherFolder(context)
                                onStatusSpoken(msg)
                            }
                            .padding(vertical = 11.dp)
                            .testTag("btn_confirm_save_image"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Save to Download/archer/",
                                tint = Color(0xFF052E16),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Save to Download/archer/",
                                color = Color(0xFF052E16),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(officialBlue)
                            .clickable {
                                scope.launch {
                                    val msg = AgentAbilitySwitchesBus.syncCurrentImageToGoogleDrive(context)
                                    onStatusSpoken(msg)
                                }
                            }
                            .padding(vertical = 11.dp)
                            .testTag("btn_sync_image_to_drive"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CloudUpload,
                                contentDescription = "Sync to 5TB Google Drive",
                                tint = Color(0xFF0B0F19),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Sync to 5TB Google Drive",
                                color = Color(0xFF0B0F19),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }

                // Row 2: Edit by Voice + Regenerate
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, officialBlue, RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.isGeneratingImage) {
                                try {
                                    val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(
                                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                        )
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                                        putExtra(
                                            RecognizerIntent.EXTRA_PROMPT,
                                            "ছবিতে কী পরিবর্তন বা এডিট করতে চান বলুন..."
                                        )
                                    }
                                    voiceEditLauncher.launch(speechIntent)
                                } catch (_: Exception) {
                                    val editText = promptInput.trim().ifBlank { "Enhance details and lighting" }
                                    scope.launch {
                                        val res = AgentAbilitySwitchesBus.createImageWithGemini(
                                            context = context,
                                            apiKey = state.premiumImageApiKey.ifBlank { apiKey },
                                            prompt = editText,
                                            editExistingImage = true
                                        )
                                        onStatusSpoken(res)
                                    }
                                }
                            }
                            .padding(vertical = 10.dp)
                            .testTag("btn_edit_image_by_voice"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = "Edit by Voice",
                                tint = officialBlue,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Edit by Voice",
                                color = officialBlue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0F172A))
                            .border(1.dp, enterpriseBorder, RoundedCornerShape(10.dp))
                            .clickable(enabled = !state.isGeneratingImage) {
                                val targetPrompt = promptInput.trim().ifBlank {
                                    state.activeImagePrompt.ifBlank { "Cinematic 8k photograph" }
                                }
                                scope.launch {
                                    val res = AgentAbilitySwitchesBus.createImageWithGemini(
                                        context = context,
                                        apiKey = state.premiumImageApiKey.ifBlank { apiKey },
                                        prompt = targetPrompt,
                                        editExistingImage = false
                                    )
                                    onStatusSpoken(res)
                                }
                            }
                            .padding(vertical = 10.dp)
                            .testTag("btn_regenerate_image"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Regenerate",
                                tint = Color(0xFFE2E8F0),
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "Regenerate",
                                color = Color(0xFFE2E8F0),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
