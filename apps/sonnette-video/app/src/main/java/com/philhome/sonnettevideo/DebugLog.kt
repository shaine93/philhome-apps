package com.philhome.sonnettevideo

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Journal de debug persistant sur le téléphone, pour diagnostiquer SANS câble.
 *
 * Écrit dans : /sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log
 * Récupérable par :
 *   adb pull /sdcard/Android/data/com.philhome.sonnettevideo/files/debug/sonnette-debug.log
 * Lisible aussi dans l'app (MainActivity → « 📋 Voir le log debug »).
 *
 * Toutes les briques (talk-back, relais, FCM) écrivent ici → après un test 5G, on relit le
 * fichier et on corrige directement, au lieu de chercher la cause à l'aveugle.
 */
object DebugLog {

    private const val TAG = "SonnetteDBG"
    private const val MAX_BYTES = 256 * 1024   // rotation simple si le fichier grossit trop
    private val lock = Any()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile private var file: File? = null

    /** À appeler tôt (onCreate des activités/services). Idempotent. */
    fun init(ctx: Context) {
        if (file != null) return
        try {
            val dir = File(ctx.getExternalFilesDir(null), "debug").apply { mkdirs() }
            file = File(dir, "sonnette-debug.log")
            log("DebugLog", "init OK -> ${file?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
        }
    }

    fun log(tag: String, msg: String) {
        Log.i(TAG, "[$tag] $msg")
        val f = file ?: return
        try {
            synchronized(lock) {
                if (f.length() > MAX_BYTES) f.writeText("")   // rotation simple
                f.appendText("${fmt.format(Date())} [$tag] $msg\n")
            }
        } catch (_: Exception) {}
    }

    fun log(tag: String, msg: String, e: Throwable) {
        log(tag, "$msg :: ${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * Comme [log], mais REMONTE aussi la ligne à Home Assistant (webhook sv_log) → permet de
     * lire les logs des DEUX téléphones à distance, SANS câble USB. Fire-and-forget : un échec
     * réseau n'impacte jamais l'app. À réserver aux événements importants (sonnerie, FCM, mort/vie).
     */
    fun push(tag: String, msg: String) {
        log(tag, msg)
        thread(isDaemon = true) {
            try {
                val body = JSONObject()
                    .put("device", Build.MODEL)
                    .put("line", "[$tag] $msg")
                    .toString()
                val c = (URL(Config.logUrl()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8000
                    readTimeout = 8000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write(body.toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) { }
        }
    }

    /** Dernières lignes (pour affichage dans l'app). */
    fun tail(maxLines: Int = 60): String {
        val f = file ?: return "(log non initialisé)"
        return try {
            val lines = f.readLines()
            if (lines.size <= maxLines) lines.joinToString("\n")
            else lines.subList(lines.size - maxLines, lines.size).joinToString("\n")
        } catch (e: Exception) {
            "(lecture impossible : ${e.message})"
        }
    }

    fun clear() {
        try { file?.writeText("") } catch (_: Exception) {}
        log("DebugLog", "log vidé")
    }

    fun path(): String = file?.absolutePath ?: "(non initialisé)"
}