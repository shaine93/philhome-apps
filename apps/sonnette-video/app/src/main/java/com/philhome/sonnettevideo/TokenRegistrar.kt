package com.philhome.sonnettevideo

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Remonte le token FCM de l'app vers un webhook HA, qui le stocke pour la VM.
 *
 * ROBUSTESSE VITALE : en cas d'échec (réseau ou HA indisponible au démarrage du
 * téléphone, ex. après coupure de courant), RÉESSAIE automatiquement avec backoff
 * jusqu'à ~17 min. Le téléphone de l'utilisatrice finit donc toujours par
 * s'enregistrer seul, sans que personne ait à rouvrir l'app.
 */
object TokenRegistrar {

    @Volatile var lastResult: String = "en attente…"
    @Volatile var lastToken: String = ""

    // 0s, 5s, 15s, 30s, 1min, 2min, 5min, 10min
    private val RETRY_DELAYS_MS = longArrayOf(0, 5_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000)

    fun send(ctx: Context, token: String, onDone: ((String) -> Unit)? = null) {
        lastToken = token
        thread(name = "fcm-register") {
            for ((i, delay) in RETRY_DELAYS_MS.withIndex()) {
                if (delay > 0) {
                    try { Thread.sleep(delay) } catch (_: InterruptedException) { return@thread }
                }
                val (ok, msg) = attempt(token)
                lastResult = if (ok) msg else "$msg (essai ${i + 1}/${RETRY_DELAYS_MS.size})"
                DebugLog.log("FCM-register", "$lastResult → ${Config.fcmRegisterUrl()}")
                onDone?.invoke(lastResult)
                if (ok) return@thread
            }
        }
    }

    /** Une tentative d'enregistrement (OkHttp + DNS-over-HTTPS via [Net]). @return (succès, message). */
    private fun attempt(token: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("token", token)
                .toString()
            val req = Request.Builder()
                .url(Config.fcmRegisterUrl())
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            Net.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
                .newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) Pair(true, "OK (HTTP ${resp.code})")
                    else Pair(false, "Refusé par le serveur (HTTP ${resp.code})")
                }
        } catch (e: Exception) {
            Pair(false, "ÉCHEC: ${e.javaClass.simpleName} — ${(e.message ?: "").take(90)}")
        }
    }
}
