package com.philhome.sonnettevideo

import android.graphics.BitmapFactory
import android.widget.ImageView
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Lecteur MJPEG simple pour afficher le flux vidéo de la sonnette dans un [ImageView].
 *
 * Source : endpoint HA `camera_proxy_stream` (multipart/x-mixed-replace) servi via duckdns
 * HTTPS + jeton longue durée → marche en 5G ET en WiFi, sans serveur TURN (simple pull HTTP).
 * Réseau via [Net] (DNS-over-HTTPS) → insensible au DNS du téléphone (AdGuard).
 *
 * Robustesse : reconnexion automatique tant que [start] est actif (coupures 5G transitoires).
 */
class MjpegView(
    private val target: ImageView,
    private val url: String,
    private val token: String
) {
    private val client = Net.base.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)   // une trame doit arriver dans ce délai
        .build()

    @Volatile private var running = false
    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        worker = thread(name = "mjpeg") {
            DebugLog.log("Mjpeg", "démarrage flux $url")
            while (running) {
                try {
                    streamOnce()
                } catch (e: Exception) {
                    DebugLog.log("Mjpeg", "flux coupé, reconnexion…", e)
                }
                if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            DebugLog.log("Mjpeg", "flux arrêté")
        }
    }

    fun stop() {
        running = false
        try { worker?.interrupt() } catch (_: Exception) {}
    }

    private fun streamOnce() {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        val resp = client.newCall(req).execute()
        try {
            if (!resp.isSuccessful) {
                DebugLog.log("Mjpeg", "HTTP ${resp.code} (au lieu de 200)")
                return
            }
            val input = BufferedInputStream(resp.body!!.byteStream(), 32 * 1024)
            var frames = 0
            var t0 = System.currentTimeMillis()
            while (running) {
                val len = readPartLength(input) ?: break
                val jpeg = ByteArray(len)
                readFully(input, jpeg)
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, len)
                if (bmp != null) {
                    target.post { target.setImageBitmap(bmp) }
                    if (++frames == 1) DebugLog.log("Mjpeg", "1re image reçue (${bmp.width}x${bmp.height})")
                    if (frames % 30 == 0) {
                        val dt = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                        DebugLog.log("Mjpeg", "$frames images, ~${30000 / dt} fps")
                        t0 = System.currentTimeMillis()
                    }
                }
            }
        } finally {
            try { resp.close() } catch (_: Exception) {}
        }
    }

    /** Lit les en-têtes d'une partie multipart et renvoie la taille du JPEG (Content-Length). */
    private fun readPartLength(input: InputStream): Int? {
        var len = -1
        while (true) {
            val line = readLine(input) ?: return null   // EOF
            val l = line.trim()
            when {
                l.startsWith("Content-Length:", ignoreCase = true) ->
                    len = l.substringAfter(":").trim().toIntOrNull() ?: -1
                l.isEmpty() && len >= 0 -> return len     // fin des en-têtes → le corps suit
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw java.io.EOFException("flux interrompu")
            off += n
        }
    }
}