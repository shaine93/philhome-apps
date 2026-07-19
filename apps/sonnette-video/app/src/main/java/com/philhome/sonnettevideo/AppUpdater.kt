package com.philhome.sonnettevideo

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
 * Mise à jour intégrée (« Mettre à jour l'app ») — plus besoin d'USB/adb ni de taper l'URL :
 * maman se met à jour d'un tap. Compare le versionCode installé (PackageManager) au manifeste
 * distant servi par HA (`Config.APK_VERSION_URL`), et si plus récent télécharge + lance l'install.
 *
 * Versionning : la source de vérité est `build.gradle.kts` (versionCode/versionName) ; le manifeste
 * `sonnette-version.json` sur HA est généré depuis ces valeurs par `HA/publish.sh`.
 */
object AppUpdater {

    data class VersionInfo(val code: Long, val name: String, val notes: String, val apkUrl: String)

    fun currentCode(ctx: Context): Long =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"

    /**
     * Vérifie la version distante ; si plus récente, télécharge l'APK et ouvre l'installateur.
     * [onStatus] reçoit des messages lisibles, toujours sur le thread principal.
     */
    fun checkAndUpdate(activity: Activity, onStatus: (String) -> Unit) {
        val ui = { s: String -> activity.runOnUiThread { onStatus(s) } }
        ui("Vérification…")
        Thread {
            val remote = fetchRemote { ui(it) }
            if (remote == null) { ui("⚠️ Serveur lent ou injoignable — réappuie sur « Mettre à jour »"); return@Thread }

            val cur = currentCode(activity)
            if (remote.code <= cur) { ui("✓ Déjà à jour (v${currentName(activity)})"); return@Thread }

            // Android 8+ : l'app doit être autorisée à installer des applis inconnues.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()) {
                ui("Autorise « installer des applis inconnues » puis réappuie")
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return@Thread
            }

            val notes = if (remote.notes.isNotBlank()) " — ${remote.notes}" else ""
            ui("Téléchargement v${remote.name}$notes…")
            val apk = download(activity, remote.apkUrl)
            if (apk == null) { ui("❌ Échec du téléchargement"); return@Thread }

            ui("Installation v${remote.name}…")
            install(activity, apk)
        }.start()
    }

    /**
     * Client dédié à la MAJ, tolérant à la **connexion à froid** (~10 s la 1ʳᵉ fois : IPv6 qui traîne
     * ou proxy duckdns endormi). Timeouts généreux ; le réessai fait le reste (la 2ᵉ tentative est
     * quasi instantanée, la connexion étant alors chaude).
     */
    private val updClient: OkHttpClient by lazy {
        Net.base.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)   // absorbe la connexion à froid (~10 s)
            .readTimeout(25, TimeUnit.SECONDS)      // par lecture : OK pour le manifeste ET le stream APK
            .build()                                 // PAS de callTimeout : ne tuerait le download de 60 Mo
    }

    /** Récupère le manifeste avec RÉESSAI (3 tentatives) — corrige le « Impossible de vérifier » à froid. */
    private fun fetchRemote(onStatus: (String) -> Unit = {}): VersionInfo? {
        repeat(3) { attempt ->
            try {
                updClient.newCall(Request.Builder().url(Config.APK_VERSION_URL).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    val o = JSONObject(body)
                    val vi = VersionInfo(
                        o.optLong("versionCode", -1),
                        o.optString("versionName", "?"),
                        o.optString("notes", ""),
                        o.optString("apkUrl", Config.APK_URL)   // l'APK peut être hébergée sur GitHub Release
                    )
                    if (vi.code >= 0) return vi
                }
            } catch (e: Exception) {
                DebugLog.log("AppUpdater", "fetchRemote tentative ${attempt + 1}/3 KO: ${e.javaClass.simpleName} ${e.message}")
                if (attempt == 0) onStatus("Serveur lent, nouvelle tentative…")
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) {}
        }
        return null
    }

    private fun download(ctx: Context, apkUrl: String): File? = try {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "sonnette-update.apk")
        updClient.newCall(Request.Builder().url(apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) return null
            file.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        file
    } catch (_: Exception) {
        null
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }
}
