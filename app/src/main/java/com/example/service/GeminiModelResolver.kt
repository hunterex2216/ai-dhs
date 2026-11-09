package com.example.service

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class ResolvedModels(val live: List<String>, val brain: List<String>)

/**
 * Asks Google which models THIS api key can use right now, and picks the most powerful ones.
 * So the user only gives an API key — model names never need to be edited again.
 */
object GeminiModelResolver {
    @Volatile var cached: ResolvedModels? = null
    @Volatile private var cachedAt = 0L

    private val brainExclude = Regex("tts|image|live|embedding|audio|robotics|computer-use|learnlm|customtools|nano|veo|lyria|aqa|translate|exp-|gemma|diffusion")

    private fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun stable(name: String) = !(name.contains("preview") || name.contains("exp"))

    fun resolve(key: String): ResolvedModels? {
        val now = System.currentTimeMillis()
        cached?.let { if (now - cachedAt < 6 * 3600_000L) return it }
        if (key.isBlank()) return null
        return try {
            val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$key")
                .openConnection() as HttpURLConnection
            c.connectTimeout = 4000; c.readTimeout = 5000
            if (c.responseCode != 200) return null
            val arr: JSONArray = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return null
            val live = mutableListOf<String>()
            val brain = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                val methods = o.optJSONArray("supportedGenerationMethods")
                val has = { m: String -> (0 until (methods?.length() ?: 0)).any { methods!!.optString(it) == m } }
                val low = name.lowercase()
                if (has("bidiGenerateContent") && !low.contains("translate") && !low.contains("image")) live.add(name)
                else if (has("generateContent") && low.startsWith("models/gemini-") && !brainExclude.containsMatchIn(low)) brain.add(name)
            }
            val liveSorted = live.sortedWith(
                compareByDescending<String> { version(it) }
                    .thenByDescending { !it.contains("thinking") && !it.contains("extended") }
                    .thenByDescending { stable(it) }
            )
            val brainSorted = brain.sortedWith(
                compareByDescending<String> { it.contains("-pro") }
                    .thenByDescending { version(it) }
                    .thenByDescending { stable(it) }
            )
            ResolvedModels(liveSorted, brainSorted).also { cached = it; cachedAt = now }
        } catch (_: Exception) { null }
    }
}

/** "Deep think" (Argon-4 / Gemini 3.1 Pro Brain): the most powerful Gemini model plans hard tasks, looking at the current screen. */
object GeminiBrainClient {
    private val FALLBACK = listOf(
        "models/gemini-3.1-pro-preview",
        "models/gemini-3-flash-preview",
        "models/gemini-2.5-pro",
        "models/gemini-2.5-flash"
    )

    fun think(key: String, prompt: String, image: Bitmap?): String {
        val models = (GeminiModelResolver.cached?.brain.orEmpty().take(3) + FALLBACK).distinct()
        var lastErr = ""
        for (m in models.take(5)) {
            try {
                val r = call(key, m, prompt, image)
                if (!r.isNullOrBlank()) return r
            } catch (e: Exception) { lastErr = e.message ?: "" }
        }
        return "deep_think unavailable ($lastErr)"
    }

    private fun call(key: String, model: String, prompt: String, image: Bitmap?): String? {
        val pkg = ScreenMemory.currentPkg
        val appGuide = AppKnowledgeRegistry.guideForPackage(pkg)
        val parts = JSONArray().put(JSONObject().put("text", "$prompt\n\nAPP BLUEPRINT ($pkg): $appGuide"))
        if (image != null) {
            val bos = ByteArrayOutputStream()
            val scaled = if (image.width > 1000) Bitmap.createScaledBitmap(image, 1000, image.height * 1000 / image.width, true) else image
            scaled.compress(Bitmap.CompressFormat.JPEG, 72, bos)
            parts.put(JSONObject().put("inlineData", JSONObject()
                .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                "You are the Argon-4 & Gemini 3.1 Pro planning brain of an autonomous Android OS agent. " +
                    "Given the user's goal, live UI elements (label@x,y), and screenshot, reply with a SHORT numbered plan of concrete next UI actions. " +
                    "CRITICAL: (1) Always verify the current foreground app and visible elements before tapping or typing. " +
                    "(2) For YouTube searches, use OPEN_APP target='YouTube' extra='<clean query>' or type ONLY the clean query with extra='TYPE' (never tap 'Voice search' mic and never open Google Search!). " +
                    "(3) Never type command words like 'CLICK' or coordinates into input boxes. Be precise, max 6 steps, no fluff."))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 1500))
        val c = URL("https://generativelanguage.googleapis.com/v1beta/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 6000; c.readTimeout = 40000
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) throw RuntimeException("$model http ${c.responseCode}")
        val resp = JSONObject(c.inputStream.bufferedReader().readText())
        val ps = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        val sb = StringBuilder()
        for (i in 0 until ps.length()) sb.append(ps.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString().trim()
    }
}
