package com.philhome.tradingclaudegod

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Appelle l'API **Messages** d'Anthropic pour rédiger une analyse pédagogique.
 * La clé est injectée au build depuis `~/.claude_api_key` → `BuildConfig.CLAUDE_API_KEY`
 * (jamais dans le code source / git). Plafond de dépense = 10 €/mois côté console Anthropic.
 *
 * Modèle éco (Haiku 4.5) : chaque analyse coûte une fraction de centime.
 */
object ClaudeApi {

    private const val MODEL = "claude-haiku-4-5-20251001"
    private const val URL = "https://api.anthropic.com/v1/messages"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    data class Result(val ok: Boolean, val text: String)

    fun configured(): Boolean = BuildConfig.CLAUDE_API_KEY.isNotBlank()

    /** Envoie [prompt] à Claude, renvoie le texte (ou un message d'erreur lisible). Appeler hors UI. */
    fun analyse(prompt: String): Result {
        if (!configured()) return Result(false, "Clé Anthropic non configurée (voir ~/.claude_api_key).")
        return try {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 700)
                .put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt)))
                .toString()
            val req = Request.Builder()
                .url(URL)
                .header("x-api-key", BuildConfig.CLAUDE_API_KEY)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val msg = try { JSONObject(raw).getJSONObject("error").getString("message") }
                              catch (_: Exception) { "HTTP ${resp.code}" }
                    return Result(false, "Analyse indisponible : $msg")
                }
                val content = JSONObject(raw).getJSONArray("content")
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "text") sb.append(block.optString("text"))
                }
                Result(true, sb.toString().trim().ifEmpty { "(réponse vide)" })
            }
        } catch (e: Exception) {
            Result(false, "Analyse indisponible : ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
