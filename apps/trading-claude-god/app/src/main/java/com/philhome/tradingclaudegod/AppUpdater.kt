package com.philhome.tradingclaudegod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Mise à jour OTA intégrée (« ⬇️ Mettre à jour »). Lit un `version.json` public sur GitHub
 * (raw) → compare le versionCode installé → si plus récent, télécharge l'APK depuis la GitHub
 * Release et lance l'installateur. Aucun câble, aucun secret.
 */
object AppUpdater {

    private const val VERSION_URL =
        "https://raw.githubusercontent.com/shaine93/philhome-apps/main/apps/trading-claude-god/version.json"
    private const val APK_URL_FALLBACK =
        "https://github.com/shaine93/philhome-apps/releases/download/trading-latest/trading-claude-god.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)   // pas de callTimeout : ne pas tuer le download de l'APK
        .build()

    data class VersionInfo(val code: Long, val name: String, val notes: String, val apkUrl: String)

    fun currentCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    fun checkAndUpdate(activity: Activity, onStatus: (String) -> Unit) {
        val ui = { s: String -> activity.runOnUiThread { onStatus(s) } }
        ui("Vérification…")
        Thread {
            val remote = fetchRemote()
            if (remote == null) { ui("⚠️ Serveur injoignable — réessaie"); return@Thread }
            if (remote.code <= currentCode(activity)) { ui("✓ Déjà à jour (v${currentName(activity)})"); return@Thread }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()) {
                ui("Autorise « installer des applis inconnues » puis réappuie")
                activity.startActivity(Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@Thread
            }

            ui("Téléchargement v${remote.name}…")
            val apk = download(activity, remote.apkUrl)
            if (apk == null) { ui("❌ Échec du téléchargement"); return@Thread }
            ui("Installation v${remote.name}…")
            install(activity, apk)
        }.start()
    }

    private fun fetchRemote(): VersionInfo? {
        repeat(3) {
            try {
                client.newCall(Request.Builder().url(VERSION_URL).build()).execute().use { r ->
                    val o = JSONObject(r.body?.string().orEmpty())
                    val vi = VersionInfo(
                        o.optLong("versionCode", -1), o.optString("versionName", "?"),
                        o.optString("notes", ""), o.optString("apkUrl", APK_URL_FALLBACK)
                    )
                    if (vi.code >= 0) return vi
                }
            } catch (_: Exception) { }
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        }
        return null
    }

    private fun download(ctx: Context, apkUrl: String): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "trading-update.apk")
        client.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        file
    } catch (_: Exception) { null }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
