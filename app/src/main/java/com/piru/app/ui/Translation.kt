package com.piru.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.data.Substance
import com.piru.app.data.SubstanceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wrapping tag row for a substance's indication texts. Each tag opens the
 * condition sheet (searchable, tappable per feedback).
 */
@Composable
fun FlowRowTags(
    state: PiruState,
    store: SubstanceStore,
    substance: Substance,
    tags: List<String>,
    onOpenCondition: (String) -> Unit,
) {
    // simple two-row wrap without experimental FlowRow
    val chunks = tags.chunked(3)
    Column {
        chunks.forEach { row ->
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { tag ->
                    AssistChip(
                        onClick = { onOpenCondition(tag) },
                        label = { Text(tag.trim().take(46), fontSize = 10.sp, maxLines = 1) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
    }
}

/**
 * "AI translate" button: sends the label texts to Gemini (user's own key from
 * Settings), caches results locally, and shows them in place. The key never
 * leaves the phone except to Google's API.
 */
@Composable
fun TranslationButton(
    state: PiruState,
    store: SubstanceStore,
    substance: Substance,
    texts: List<String>,
    onDone: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    TextButton(
        onClick = {
            val key = state.repo.getSetting("gemini_key")
            if (key.isNullOrBlank()) {
                state.translateError = "Set a Gemini API key in Tools > Translation first."
                return@TextButton
            }
            scope.launch {
                busy = true
                val out = Gemini.translate(state.repo, key, texts)
                if (out != null) {
                    state.setTranslated(substance.id, out)
                    onDone(true)
                    state.translateError = null
                } else {
                    state.translateError = "Translation failed (check the key or network)."
                }
                busy = false
            }
        },
        enabled = !busy,
    ) {
        Text(if (busy) "…" else "FA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Minimal Gemini text endpoint (user's own key, stored locally). */
object Gemini {
    private const val MODEL = "gemini-2.0-flash"

    private val LANG_NAMES = mapOf("fa" to "Persian (Farsi)", "ar" to "Arabic", "tr" to "Turkish", "es" to "Spanish", "zh" to "Chinese")

    suspend fun translate(repo: com.piru.app.data.AppRepository, apiKey: String, texts: List<String>): Map<String, String>? {
        val lang = LANG_NAMES[repo.getSetting("ui_lang")] ?: "Persian (Farsi)"
        val prompt = "Translate each of these medication-use labels to $lang. " +
            "Return one line per input, in the same order, each formatted as original=>translation. No extra text.\n" +
            texts.joinToString("\n") { "- $it" }
        return try {
            withContext(Dispatchers.IO) {
                val body = org.json.JSONObject()
                    .put("contents", org.json.JSONArray().put(
                        org.json.JSONObject().put("parts", org.json.JSONArray().put(
                            org.json.JSONObject().put("text", prompt)))),
                    )
                    .put("generationConfig", org.json.JSONObject().put("temperature", 0.2).put("maxOutputTokens", 4096))
                    .toString()
                val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 8_000; conn.readTimeout = 30_000
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode != 200) return@withContext null
                val resp = conn.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(resp)
                val text = json.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
                val map = HashMap<String, String>()
                text.lines().forEach { line ->
                    val sep = line.indexOf("=>")
                    if (sep > 0) map[line.substring(0, sep).trim().removePrefix("- ").trim()] = line.substring(sep + 2).trim()
                }
                map.ifEmpty { null }
            }
        } catch (e: Exception) { null }
    }
}
