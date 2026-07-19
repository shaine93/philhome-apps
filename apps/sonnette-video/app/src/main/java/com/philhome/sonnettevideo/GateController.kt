package com.philhome.sonnettevideo

import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Ouvre le portail : POST sur le webhook HA → HA déclenche switch.turn_on
 * (impulsion START cyclique, inching 500 ms côté relais eWeLink).
 * Un seul bouton (le matériel ne fait qu'une impulsion cyclique).
 */
object GateController {

    fun open(onResult: (Boolean) -> Unit) {
        thread(name = "gate-open") {
            val ok = try {
                val conn = (URL(Config.gateWebhookUrl()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    doOutput = true
                }
                conn.outputStream.use { it.write("{}".toByteArray()) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (_: Exception) {
                false
            }
            onResult(ok)
        }
    }
}
