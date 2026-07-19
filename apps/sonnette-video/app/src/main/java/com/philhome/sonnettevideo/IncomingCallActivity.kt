package com.philhome.sonnettevideo

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import org.videolan.libvlc.util.VLCVideoLayout
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Écran d'appel plein écran, pensé accessibilité (81 ans, fauteuil) :
 * - photo du visiteur en grand
 * - deux énormes boutons : VERT "Répondre" / ROUGE "Refuser"
 * - après "Répondre" : haut-parleur forcé (A12) + bouton "Ouvrir le portail" + "Raccrocher"
 * Lot 1 = photo + boutons + portail. La vidéo/voix WebRTC (Lot 2/3) s'insère dans showAnswered().
 */
class IncomingCallActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var videoWeb: WebView
    private lateinit var vlcLayout: VLCVideoLayout   // rendu vidéo RTSP direct libVLC (chemin sans HA)
    private lateinit var statusText: TextView
    private lateinit var talkIndicator: TextView   // gros voyant "PARLEZ MAINTENANT" (accessibilité)
    private lateinit var snapshot: ImageView       // instantané / vidéo de secours (rafraîchi en boucle)
    @Volatile private var snapshotRunning = false
    private var gateOpen = false   // portail cyclique : on alterne l'affichage à chaque impulsion
    // La sonnerie (son + vibration) est désormais jouée par RingPlayer (détenu par le service).
    private var talk: DoorbellTalk? = null        // talk-back direct vers la sonnette (archi C)
    private var webrtc: WebrtcVideo? = null        // vidéo live WebRTC (go2rtc via HA, IPv6 sans TURN)
    private var rtsp: RtspVideo? = null            // vidéo live RTSP DIRECTE (sans HA), quand LAN
    @Volatile private var onLan = false            // sonnette joignable en LAN → chemin direct sans HA
    private var callId: String? = null            // identifiant d'appel (coordination multi-appareils)
    private var cancelRegistered = false

    // Un autre téléphone a décroché (ou le visiteur est parti) → HA a poussé "cancel" → on ferme.
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            DebugLog.log("IncomingCall", "cancel reçu (répondu ailleurs / annulé) → fermeture")
            endCall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        callId = intent.getStringExtra("call_id")
        DebugLog.log("IncomingCall", "onCreate action=${intent.action} call_id=$callId")
        Net.prewarm()
        showOverLockscreen()
        ContextCompat.registerReceiver(
            this, cancelReceiver, IntentFilter(ACTION_CANCEL_CALL), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        cancelRegistered = true

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        setContentView(root)

        statusText = TextView(this).apply {
            text = "Quelqu'un sonne à la porte"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, dp(12))
        }
        videoWeb = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.BLACK)
        }
        vlcLayout = VLCVideoLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        snapshot = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.DKGRAY)
        }
        // Le flux vidéo (WebView) en fond, l'instantané par-dessus jusqu'à l'arrivée du live.
        val videoFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            addView(videoWeb)
            addView(vlcLayout)
            addView(snapshot)
        }
        talkIndicator = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(18))
            visibility = View.GONE
        }

        root.addView(statusText)
        root.addView(talkIndicator)
        root.addView(videoFrame)

        startVideo()             // sonde LAN → RTSP direct (sans HA) sinon secours WebRTC+snapshot HA

        when (intent.action) {
            "ANSWER" -> showAnswered()   // décroché depuis le bouton vert de la notif
            else -> showRinging()        // sonnerie plein écran (vert/rouge)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("call_id")?.let { callId = it }
        DebugLog.log("IncomingCall", "onNewIntent action=${intent.action}")
        if (intent.action == "ANSWER") showAnswered()
    }

    /* ---------- États d'écran ---------- */

    private fun showRinging() {
        startRinging()
        // Bouton discret : couper la sonnerie SANS raccrocher (l'écran + la vidéo restent, on peut répondre).
        val silence = Button(this).apply {
            text = "🔇 Couper le son"
            textSize = 16f; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#455A64"))
            layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), 0, dp(8), dp(6)) }
            setOnClickListener { stopRinging(); isEnabled = false; text = "🔕 Sonnerie coupée" }
        }
        val buttons = bottomRow()
        buttons.addView(bigButton("Refuser", Color.parseColor("#D32F2F")) { decline() })
        buttons.addView(bigButton("Répondre", Color.parseColor("#2E7D32")) { vibrate(); showAnswered() })
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(silence)
            addView(buttons)
        }
        replaceBottom(container)
    }

    private fun showAnswered() {
        stopRinging()                        // coupe la sonnerie alarme
        CallForegroundService.stop(this)     // coupe le FGS sonnerie quand on décroche
        CallCoordinator.answered(callId)     // prévient HA → coupe la sonnerie sur les AUTRES téléphones
        statusText.text = "En communication"
        AudioRouter.toSpeaker(this)          // A12 : haut-parleur mains libres
        webrtc?.unmute()                     // le geste « Répondre » autorise le son → on ENTEND le visiteur
        rtsp?.unmute()                       // idem chemin RTSP direct (sans HA)
        startTalk()                          // talk-back direct (voix → sonnette), micro filtré

        val buttons = bottomRow()
        buttons.addView(bigButton("Portail", Color.parseColor("#1565C0")) {
            vibrate()
            val opening = !gateOpen
            statusText.text = if (opening) "Ouverture du portail…" else "Fermeture du portail…"
            GateController.open { ok ->
                runOnUiThread {
                    if (ok) {
                        gateOpen = opening
                        statusText.text = if (gateOpen) "Portail ouvert ✓" else "Portail fermé ✓"
                    } else {
                        statusText.text = "Échec — réessayez"
                    }
                }
            }
        })
        buttons.addView(bigButton("Raccrocher", Color.parseColor("#D32F2F")) { hangUp() })
        replaceBottom(buttons)
    }

    /* ---------- Actions ---------- */

    private fun decline() = endCall()

    private fun hangUp() = endCall()

    /** Termine l'appel proprement : Refuser / Raccrocher / annulation distante (cancel). */
    private fun endCall() {
        stopRinging()
        stopTalk()
        stopVideo()
        stopSnapshotRefresh()
        AudioRouter.reset(this)
        CallForegroundService.stop(this)   // coupe le FGS + sa notif
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        if (cancelRegistered) {
            try { unregisterReceiver(cancelReceiver) } catch (_: Exception) {}
            cancelRegistered = false
        }
        stopRinging()
        stopTalk()
        stopVideo()
        stopSnapshotRefresh()
        super.onDestroy()
    }

    /* ---------- Talk-back direct (archi C) ---------- */

    private fun startTalk() {
        if (talk != null) return
        DebugLog.log("IncomingCall", "startTalk vers ${Config.DOORBELL_IP} (${if (onLan) "DIRECT LAN" else "relais HA"})")
        setTalkState("connecting")
        // En LAN : talk DIRECT à la sonnette (sans HA). Hors LAN : relais HA (5G).
        talk = DoorbellTalk(Config.DOORBELL_IP, useRelay = !onLan).also {
            it.start { state ->
                DebugLog.log("IncomingCall", "talk: $state")
                setTalkState(state)
            }
        }
    }

    /**
     * Gros voyant accessibilité : orange tant qu'on n'est pas prêt, VERT « PARLEZ MAINTENANT »
     * seulement quand la voix passera vraiment (on attend ~1,3 s après "active" = tampon de la
     * sonnette) — pour que la maman ne parle pas trop tôt et ne se répète pas.
     */
    private fun setTalkState(state: String) {
        runOnUiThread {
            talkIndicator.visibility = View.VISIBLE
            when {
                state == "active" -> {
                    talkIndicator.text = "⏳ Préparation…"
                    talkIndicator.setBackgroundColor(Color.parseColor("#F9A825")) // orange
                    talkIndicator.removeCallbacks(showSpeakNow)
                    talkIndicator.postDelayed(showSpeakNow, SPEAK_READY_DELAY_MS)
                }
                state.startsWith("connecting") -> {
                    talkIndicator.text = "⏳ Connexion à la sonnette…"
                    talkIndicator.setBackgroundColor(Color.parseColor("#F9A825")) // orange
                }
                state.startsWith("error") -> {
                    talkIndicator.removeCallbacks(showSpeakNow)
                    talkIndicator.text = "⚠️ Problème — patientez"
                    talkIndicator.setBackgroundColor(Color.parseColor("#D32F2F")) // rouge
                }
                state == "stopped" -> {
                    talkIndicator.removeCallbacks(showSpeakNow)
                    talkIndicator.visibility = View.GONE
                }
            }
        }
    }

    private val showSpeakNow = Runnable {
        talkIndicator.text = "🟢 PARLEZ MAINTENANT"
        talkIndicator.setBackgroundColor(Color.parseColor("#2E7D32")) // vert
    }

    private fun stopTalk() {
        talk?.stop()
        talk = null
    }

    /* ---------- Vidéo live WebRTC (go2rtc via HA, IPv6 sans TURN) ---------- */

    /**
     * Choix du chemin vidéo. Le mode DIRECT (RTSP + talk sans HA) n'est tenté que si
     * l'interrupteur [Prefs.directLan] est ACTIVÉ **et** la sonnette est joignable en LAN ; sinon
     * chemin HA historique (WebRTC + snapshot). Défaut interrupteur = OFF → rien ne change.
     */
    private fun startVideo() {
        val directPref = Prefs.directLan(this)
        thread(name = "lan-probe") {
            val useDirect = directPref && Lan.isDoorbellOnLan()
            onLan = useDirect
            DebugLog.log("IncomingCall", "chemin: " + when {
                useDirect -> "RTSP DIRECT (sans HA)"
                directPref -> "mode direct ON mais sonnette hors LAN → WebRTC/HA"
                else -> "WebRTC/HA (mode direct OFF)"
            })
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (useDirect) startVideoDirect() else startVideoHa()
            }
        }
    }

    /** Vidéo EN DIRECT depuis la sonnette (RTSP, filtre anti-vent in-app), SANS Home Assistant. */
    private fun startVideoDirect() {
        if (rtsp != null) return
        DebugLog.log("IncomingCall", "video RTSP directe start (ch1)")
        vlcLayout.visibility = View.VISIBLE
        videoWeb.visibility = View.GONE
        rtsp = RtspVideo(this, vlcLayout, onPlaying = {
            snapshotRunning = false
            runOnUiThread { snapshot.visibility = View.GONE }
        }).also {
            // vidéo+son dès la sonnerie mais MUET ; dé-muté au « Répondre ».
            it.play("ch1", muted = true)
        }
    }

    /** Secours (hors LAN / 5G) : vidéo WebRTC via HA + instantané anti-écran-noir HA. */
    private fun startVideoHa() {
        startSnapshotRefresh()
        if (webrtc != null) return
        DebugLog.log("IncomingCall", "video WebRTC start (${Config.CAM_LINK_HI}, video+audio)")
        vlcLayout.visibility = View.GONE
        webrtc = WebrtcVideo(videoWeb, onPlaying = {
            // la vidéo live (WebRTC) est arrivée → on arrête le rafraîchissement snapshot et on le masque
            snapshotRunning = false
            runOnUiThread { snapshot.visibility = View.GONE }
        }).also {
            it.setup()
            // vidéo+audio dès la sonnerie, mais MUET (autoplay autorisé) ; dé-muté au décrochage.
            it.play(Config.CAM_LINK_HI, mode = "webrtc", media = "video+audio")
        }
    }

    private fun stopVideo() {
        webrtc?.destroy()
        webrtc = null
        rtsp?.destroy()
        rtsp = null
    }

    /**
     * Rafraîchit l'instantané de la sonnette EN BOUCLE (~1 image/1,3 s, en HTTP → marche sur TOUT
     * réseau, 5G comprise). Rôle : (1) pas d'écran noir au démarrage ; (2) **vidéo de secours** quand
     * le WebRTC ne démarre pas (ex. 5G sans IPv6/TURN). S'arrête dès que le WebRTC joue (onPlaying).
     */
    private fun startSnapshotRefresh() {
        if (Config.HA_LONG_LIVED_TOKEN.isBlank() || snapshotRunning) return
        snapshotRunning = true
        thread(name = "snapshot") {
            var first = true
            while (snapshotRunning) {
                try {
                    val req = Request.Builder()
                        .url(Config.cameraSnapshotUrl())
                        .addHeader("Authorization", "Bearer ${Config.HA_LONG_LIVED_TOKEN}")
                        .build()
                    Net.base.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                        .newCall(req).execute().use { resp ->
                            val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
                            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (bmp != null) runOnUiThread {
                                if (snapshot.visibility == View.VISIBLE) snapshot.setImageBitmap(bmp)
                            }
                            if (first) {
                                DebugLog.log("IncomingCall", "snapshot ${if (bmp != null) "OK (refresh)" else "vide (HTTP ${resp.code})"}")
                                first = false
                            }
                        }
                } catch (e: Exception) {
                    if (first) { DebugLog.log("IncomingCall", "snapshot échec: ${e.message}"); first = false }
                }
                if (snapshotRunning) try { Thread.sleep(1300) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun stopSnapshotRefresh() { snapshotRunning = false }

    /**
     * La sonnerie est jouée par [RingPlayer] (détenu par le service) → elle sonne déjà quand
     * l'écran d'appel s'ouvre. On l'appelle quand même (idempotent) au cas où l'activité serait
     * lancée sans passer par le service. « Couper le son » / décroché → [stopRinging].
     */
    private fun startRinging() = RingPlayer.start(this)

    private fun stopRinging() = RingPlayer.stop()

    /* ---------- UI helpers ---------- */

    private var bottom: View? = null
    private fun bottomRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(120))
        weightSum = 2f
    }
    private fun replaceBottom(v: View) {
        bottom?.let { root.removeView(it) }
        bottom = v
        root.addView(v)
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        isAllCaps = false
        layoutParams = LinearLayout.LayoutParams(0, MATCH, 1f).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) }
        setOnClickListener { onClick() }
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // vidéo reste visible
    }

    private fun vibrate() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Broadcast interne : un autre téléphone a décroché (ou annulation) → fermer l'écran d'appel. */
        const val ACTION_CANCEL_CALL = "com.philhome.sonnettevideo.CANCEL_CALL"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        // Délai avant le vert « PARLEZ » après que le talk soit "active" (= marge tampon sonnette).
        // À régler après écoute : trop court = 1er mot coupé ; trop long = peu réactif.
        private const val SPEAK_READY_DELAY_MS = 700L
    }
}
