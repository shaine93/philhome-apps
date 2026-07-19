package com.philhome.sonnettevideo

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Coordination multi-appareils. Quand CE téléphone décroche, il prévient Home Assistant
 * (webhook) avec le `call_id` + son nom d'appareil. L'automatisation HA pousse alors un FCM
 * `cancel` (même call_id) à TOUS les AUTRES téléphones → leur sonnerie/écran s'arrête, et la
 * session voix unique de la sonnette n'est pas disputée.
 *
 * Asynchrone + via [Net] (DNS-over-HTTPS) → fiable en 5G, ne bloque pas l'UI.
 */
object CallCoordinator {

    fun answered(callId: String?) = notify(callId, "answered")

    private fun notify(callId: String?, action: String) {
        if (callId.isNullOrBlank()) return
        thread(name = "call-coord") {
            try {
                val body = JSONObject()
                    .put("call_id", callId)
                    .put("device", android.os.Build.MODEL)
                    .put("action", action)
                    .toString()
                val req = Request.Builder()
                    .url(Config.callEventUrl())
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                Net.base.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                    .newCall(req).execute().use {
                        DebugLog.log("CallCoord", "$action call_id=$callId → HTTP ${it.code}")
                    }
            } catch (e: Exception) {
                DebugLog.log("CallCoord", "$action échec: ${e.message}")
            }
        }
    }
}