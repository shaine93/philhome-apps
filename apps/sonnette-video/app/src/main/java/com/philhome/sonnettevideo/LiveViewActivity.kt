package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL

/**
 * Vue live « regarder qui est là » — ouverte au tap sur une alerte Présence / Livreur
 * ([DeliveryAlertNotifier]). Design IMMERSIF SOMBRE : vidéo plein écran, contrôles flottants.
 *
 * Volontairement SÉPARÉE de l'écran d'appel : ici on ne sonne pas, on regarde tranquillement
 * (voir + entendre). PAS de talk-back : on ne parle pas à un livreur/passant qui n'a pas sonné.
 * Le talk-back reste réservé à l'écran d'appel réel. Ne touche pas au chemin d'appel.
 */
class LiveViewActivity : Activity() {

    private var webrtc: WebrtcVideo? = null
    private lateinit var snapshot: ImageView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        Net.prewarm()
        showOverLockscreen()
        goImmersive()

        val root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        setContentView(root)

        val web = WebView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH, MATCH) }
        root.addView(web)

        // Snapshot affiché tout de suite (anti écran-noir), fondu quand la vidéo live arrive.
        snapshot = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Ui.BG)
        }
        root.addView(snapshot)
        loadSnapshotAsync(intent.getStringExtra("image_url"))

        // Voile dégradé en haut → badge + bouton fermer toujours lisibles quelle que soit l'image.
        root.addView(View(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, dp(150), Gravity.TOP)
            background = Ui.vScrim(0xB3000000.toInt(), 0x00000000)
        })

        // Barre haute : badge « ● EN DIRECT » (gauche) + fermer (droite).
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
            setPadding(dp(16), dp(22), dp(16), 0)
            addView(liveBadge())
            addView(View(this@LiveViewActivity), LinearLayout.LayoutParams(0, WRAP, 1f)) // ressort
            addView(closeButton())
        })

        // Vidéo live + son (on ENTEND la rue) ; le fondu du snapshot se déclenche quand ça joue.
        // Pas de micro sortant ici : on regarde/écoute seulement, on ne parle pas (livreur/passant).
        webrtc = WebrtcVideo(web) { runOnUiThread { hideSnapshot() } }.also {
            it.setup()
            it.play(Config.CAM_LINK_HI, mode = "webrtc", media = "video+audio")
            it.unmute()
        }
        DebugLog.log("LiveView", "ouverte (vue live door_hi, sans talk-back)")
    }

    private fun liveBadge() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.rounded(0x99000000.toInt(), dp(999).toFloat())
        setPadding(dp(12), dp(8), dp(14), dp(8))
        addView(View(this@LiveViewActivity).apply {
            background = Ui.circle(Ui.RED)
        }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(8) })
        addView(TextView(this@LiveViewActivity).apply {
            text = "EN DIRECT"; setTextColor(Ui.TEXT); textSize = 13f
            typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.08f
        })
    }

    private fun closeButton() = TextView(this).apply {
        text = "✕"; setTextColor(Ui.TEXT); textSize = 20f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        background = Ui.ripple(Ui.circle(0x99000000.toInt()))
        setOnClickListener { finish() }
    }

    private fun hideSnapshot() {
        if (snapshot.visibility != View.GONE) {
            snapshot.animate().alpha(0f).setDuration(350)
                .withEndAction { snapshot.visibility = View.GONE }.start()
        }
    }

    private fun loadSnapshotAsync(url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            val bmp = try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000; readTimeout = 4000; instanceFollowRedirects = true
                }
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) { null }
            if (bmp != null) runOnUiThread { if (snapshot.visibility != View.GONE) snapshot.setImageBitmap(bmp) }
        }.start()
    }

    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun goImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    override fun onDestroy() {
        try { webrtc?.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
