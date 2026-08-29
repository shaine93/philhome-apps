package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Écran d'accueil MODE SIMPLE : image de la porte, rien d'autre. Pour un téléphone destiné à
 * quelqu'un qui n'a besoin de rien de plus (ex. la mère de Philippe) — voir [Prefs.uiModeSimple].
 *
 * Volontairement PAS un flux vidéo/audio permanent (coût batterie/données sur un écran ouvert en
 * continu) : un instantané rafraîchi doucement tant que l'écran est visible — même mécanisme que
 * [IncomingCallActivity]. La vraie vidéo live (avec son) reste réservée à un vrai événement :
 * sonnerie ([IncomingCallActivity]) ou alerte présence ([LiveViewActivity]).
 *
 * Réutilise le langage visuel de [LiveViewActivity]/[Ui] pour rester cohérent avec le reste de
 * l'app (fort contraste, grands aplats — déjà pensé pour l'accessibilité).
 */
class SimpleHomeActivity : Activity() {

    private lateinit var snapshot: ImageView
    private lateinit var statusText: TextView
    @Volatile private var refreshing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        Net.prewarm()

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)

        snapshot = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.BG)
        }
        root.addView(snapshot)

        // Voile en bas → statut toujours lisible quelle que soit l'image.
        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(140), Gravity.BOTTOM)
            background = Ui.vScrim(0x00000000, 0xB3000000.toInt())
        })

        statusText = TextView(this).apply {
            text = "Surveillance de la porte"
            setTextColor(Ui.TEXT); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                bottomMargin = dp(28)
            }
        }
        root.addView(statusText)

        // Seul geste caché de cet écran : les réglages avancés, pour Philippe uniquement.
        // Petit, discret, en bas à droite — jamais nommé ni mis en avant pour ne pas inviter
        // Maman à y toucher.
        root.addView(TextView(this).apply {
            text = "⚙"
            setTextColor(Ui.MUTED); textSize = 20f; gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.END).apply {
                bottomMargin = dp(18); rightMargin = dp(14)
            }
            background = Ui.ripple(Ui.circle(0x33000000))
            setOnClickListener { startActivity(Intent(this@SimpleHomeActivity, MainActivity::class.java)) }
        })

        DebugLog.log("SimpleHome", "onCreate — mode simple actif")
        loadSnapshotOnce()   // affichage immédiat, avant que la boucle de onResume() ne démarre
    }

    override fun onResume() {
        super.onResume()
        refreshing = true
        startRefreshLoop()
    }

    override fun onPause() {
        refreshing = false
        super.onPause()
    }

    private fun loadSnapshotOnce() {
        if (Config.HA_LONG_LIVED_TOKEN.isBlank()) return
        thread(name = "home-snapshot-first") { applySnapshot(fetchSnapshot()) }
    }

    /** Boucle légère : une image toutes les ~5s, seulement écran visible — pas de flux permanent. */
    private fun startRefreshLoop() {
        if (Config.HA_LONG_LIVED_TOKEN.isBlank()) return
        thread(name = "home-snapshot-loop") {
            while (refreshing) {
                applySnapshot(fetchSnapshot())
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun applySnapshot(bmp: android.graphics.Bitmap?) {
        if (bmp == null) {
            DebugLog.log("SimpleHome", "snapshot: bitmap nul (voir échec ci-dessus, ou token HA vide)")
            return
        }
        DebugLog.log("SimpleHome", "snapshot appliqué (${bmp.width}x${bmp.height})")
        runOnUiThread {
            snapshot.setImageBitmap(bmp)
            statusText.text = "Vue à ${TIME_FMT.format(Date())}"
        }
    }

    private fun fetchSnapshot(): android.graphics.Bitmap? = try {
        val c = (URL(Config.cameraSnapshotUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000; readTimeout = 5000
            setRequestProperty("Authorization", "Bearer ${Config.HA_LONG_LIVED_TOKEN}")
        }
        c.inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        DebugLog.log("SimpleHome", "snapshot échec: ${e.message}")
        null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.FRANCE)
    }
}
