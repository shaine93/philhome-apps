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
    private var answered = false   // true dès showAnswered() — protège une conversation en cours
    // Incrémenté à chaque (re)démarrage de session d'appel — les callbacks async (sonde LAN,
    // boucle instantané) capturent leur génération et s'auto-annulent s'ils ne correspondent
    // plus à la session courante. Ajouté le 2026-08-29 (incident terrain, voir replaceWithNewCall).
    @Volatile private var sessionGen = 0

    // Un autre téléphone a décroché (ou le visiteur est parti) → HA a poussé "cancel" → on ferme.
    // SCOPÉ par call_id (2026-08-29) : un cancel en retard pour un appel déjà terminé ne doit
    // JAMAIS fermer un appel plus récent affiché depuis — voir CallForegroundService.stop().
    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cancelCallId = intent?.getStringExtra("call_id")
            if (cancelCallId == null || cancelCallId != callId) {
                DebugLog.log("IncomingCall", "cancel ignoré (call_id=$cancelCallId, appel affiché=$callId)")
                return
            }
            DebugLog.log("IncomingCall", "cancel reçu (répondu ailleurs / annulé, call_id=$cancelCallId) → fermeture")
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
        val incomingCallId = intent.getStringExtra("call_id")
        DebugLog.log("IncomingCall", "onNewIntent action=${intent.action} call_id=$incomingCallId (affiché=$callId)")
        when {
            // Sonnerie pour un AUTRE appel que celui affiché : incident terrain du 2026-08-29 —
            // un vieil appel resté ouvert (ex. test oublié) avalait silencieusement la vraie
            // sonnerie suivante (call_id mis à jour en douce, écran resté sur l'ancien état).
            // Remplacement complet, qu'on soit en sonnerie OU en communication : mieux vaut
            // risquer d'interrompre un appel qui traînait que de rater un vrai visiteur — voir
            // discussion avec Codex, 2026-08-29.
            intent.action == "RINGING" && !incomingCallId.isNullOrBlank() && incomingCallId != callId ->
                replaceWithNewCall(incomingCallId)
            // Doublon (même call_id) : idempotent, ne rien refaire (juste rafraîchir l'intent stocké).
            intent.action == "RINGING" -> DebugLog.log("IncomingCall", "RINGING dupliqué (même call_id) — ignoré")
            // Répondre à l'appel déjà affiché : cas normal.
            intent.action == "ANSWER" && (incomingCallId == null || incomingCallId == callId) -> showAnswered()
            // Répondre à un appel DIFFÉRENT de celui affiché : arrive si l'écran était déjà allumé
            // au moment du nouvel appel (notif heads-up sans intent plein-écran RINGING) — le geste
            // « Répondre » de CETTE notif porte le NOUVEL call_id. Remplacer puis décrocher, sinon
            // le visiteur réel n'est jamais pris en charge (trouvé en review Codex, 2026-08-29).
            intent.action == "ANSWER" && !incomingCallId.isNullOrBlank() -> {
                replaceWithNewCall(incomingCallId)
                showAnswered()
            }
        }
    }

    /**
     * Un NOUVEL appel (call_id différent) arrive pendant que celui-ci est affiché (sonnerie ou
     * déjà décroché) : on ferme proprement TOUT l'état de l'ancien avant de repartir à zéro sur
     * le nouveau — jamais un simple changement de `callId` sous un écran qui ne bouge pas.
     */
    private fun replaceWithNewCall(newCallId: String) {
        DebugLog.log("IncomingCall", "Remplacement complet : $callId → $newCallId")
        sessionGen++   // périme tout callback async (sonde LAN, boucle instantané) de l'ancienne session
        stopRinging()
        stopTalk()
        stopVideo()             // détruit webrtc → WebrtcVideo.destroy() invalide définitivement videoWeb
        recreateVideoWeb()      // Android interdit de réutiliser un WebView après destroy() (trouvé en review Codex)
        stopSnapshotRefresh()
        talkIndicator.removeCallbacks(showSpeakNow)
        talkIndicator.visibility = View.GONE
        respondButton?.removeCallbacks(enableRespond)
        GreetingSender.cancel()
        AudioRouter.reset(this)
        gateOpen = false
        onLan = false
        answered = false
        callId = newCallId
        snapshot.visibility = View.VISIBLE
        statusText.text = "Quelqu'un sonne à la porte"
        startVideo()
        showRinging()
    }

    /**
     * Remplace [videoWeb] par un WebView neuf, à la même place dans la hiérarchie de vues.
     * Nécessaire après [stopVideo] : `WebrtcVideo.destroy()` appelle `WebView.destroy()`, et
     * Android interdit formellement de réutiliser un WebView après ça (vue invalidée pour de
     * bon) — sans ça, le chemin vidéo HA/WebRTC (le chemin par défaut) casse silencieusement
     * sur tout appel qui remplace un appel précédent. Trouvé en review Codex, 2026-08-29.
     */
    private fun recreateVideoWeb() {
        val parent = videoWeb.parent as? android.view.ViewGroup
        val index = parent?.indexOfChild(videoWeb) ?: -1
        if (parent != null && index >= 0) parent.removeViewAt(index)
        videoWeb = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
            setBackgroundColor(Color.BLACK)
        }
        if (parent != null) {
            if (index in 0..parent.childCount) parent.addView(videoWeb, index) else parent.addView(videoWeb)
        }
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
        val reponds = bigButton("Répondre", Color.parseColor("#2E7D32")) { vibrate(); showAnswered() }
        buttons.addView(reponds)
        respondButton = reponds
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(silence)
            addView(buttons)
        }
        replaceBottom(container)

        // Bloque « Répondre » le temps que le message d'accueil se diffuse en entier au visiteur
        // (sinon décrocher tôt coupe l'annonce en cours — voir GreetingSender.cancel()). Le
        // visiteur entend TOUJOURS le message complet avant qu'on puisse lui parler directement.
        if (GreetingRecorder.exists(this)) {
            reponds.isEnabled = false
            reponds.alpha = 0.5f
            reponds.text = "⏳ Message en cours…"
            // + 300 ms de marge (latence FCM→ouverture d'écran, l'envoi a déjà pu démarrer avant).
            val delayMs = (GreetingRecorder.durationSeconds(this) * 1000).toLong() + 300L
            reponds.postDelayed(enableRespond, delayMs)
        }
    }

    private var respondButton: Button? = null
    private val enableRespond = Runnable {
        respondButton?.apply { isEnabled = true; alpha = 1f; text = "Répondre" }
    }

    private fun showAnswered() {
        answered = true
        // Décroché PENDANT l'envoi du message d'accueil (cas fréquent, ~5s de fenêtre) : la
        // sonnette n'accepte qu'UNE session voix à la fois — sans ça, le talk-back ouvre sa
        // propre session en parallèle et les deux se corrompent mutuellement (confirmé par les
        // logs le 2026-09-04 : le talk-back obtenait son ACK avant que l'accueil ait fini
        // d'envoyer). Interrompt proprement (STOP_VOICE) AVANT toute chose, pour que le canal
        // soit libre quand startTalk() ouvre sa session.
        GreetingSender.cancel()
        respondButton?.removeCallbacks(enableRespond)
        stopRinging()                        // coupe la sonnerie alarme
        CallForegroundService.stop(this, callId)  // coupe le FGS sonnerie quand on décroche (scopé)
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
        sessionGen++   // périme les callbacks async restants (sonde LAN, boucle instantané)
        stopRinging()
        stopTalk()
        stopVideo()
        stopSnapshotRefresh()
        AudioRouter.reset(this)
        answered = false
        CallForegroundService.stop(this, callId)   // coupe le FGS + sa notif (scopé)
        finishAndRemoveTask()
    }

    override fun onDestroy() {
        sessionGen++   // périme tout callback async restant
        if (cancelRegistered) {
            try { unregisterReceiver(cancelReceiver) } catch (_: Exception) {}
            cancelRegistered = false
        }
        stopRinging()
        stopTalk()
        stopVideo()
        stopSnapshotRefresh()
        AudioRouter.reset(this)   // le routage haut-parleur ne doit jamais survivre à une destruction anormale
        super.onDestroy()
    }

    /* ---------- Talk-back direct (archi C) ---------- */

    private fun startTalk() {
        if (talk != null) return
        val doorbellIp = DoorbellIp.current(this)
        val sensitivity = Prefs.windSensitivity(this)
        DebugLog.log("IncomingCall", "startTalk vers $doorbellIp (${if (onLan) "DIRECT LAN" else "relais HA"}, sensibilité=$sensitivity)")
        setTalkState("connecting")
        val captureDir = if (Prefs.audioCaptureEnabled(this))
            java.io.File(getExternalFilesDir(null), "debug").apply { mkdirs() } else null
        // Génération capturée : stopTalk() n'attend pas la fin du worker précédent (juste
        // interrupt()) — sans ce garde-fou, un callback tardif de l'ANCIENNE session de talk
        // (ex. son "stopped" final) peut arriver après qu'une NOUVELLE session ait démarré et
        // écraser son indicateur/gain. Trouvé en review Codex, 2026-08-29.
        val myGen = sessionGen
        // En LAN : talk DIRECT à la sonnette (sans HA). Hors LAN : relais HA (5G).
        talk = DoorbellTalk(doorbellIp, useRelay = !onLan, sensitivity = sensitivity, captureDir = captureDir).also {
            it.start(
                onState = { state ->
                    if (myGen != sessionGen) return@start
                    DebugLog.log("IncomingCall", "talk: $state")
                    setTalkState(state)
                },
                onGain = { gain -> if (myGen == sessionGen) duckIncomingAudio(gain) }
            )
        }
    }

    /**
     * Anti-Larsen : quand on parle (porte de [VoiceFilter] ouverte → gain proche de 1), on réduit
     * le son du visiteur — sinon haut-parleur ET micro ouverts en même temps sur CE téléphone
     * bouclent (constaté en test réel : « beaucoup de larsen »). Continu et automatique (pas de
     * bouton), directement piloté par le gain déjà lissé de VoiceFilter — pas de lissage en plus.
     * Hors du fil UI (appelé depuis le thread de capture micro) : [RtspVideo.duck] et
     * [WebrtcVideo.duck] gèrent chacun leur propre passage au bon thread.
     */
    private fun duckIncomingAudio(gain: Double) {
        val level = 1.0 - (gain.coerceIn(0.0, 1.0) * DUCK_AMOUNT)
        rtsp?.duck(level)
        webrtc?.duck(level)
    }

    /**
     * Gros voyant accessibilité : orange tant qu'on n'est pas prêt, VERT « PARLEZ MAINTENANT »
     * seulement une fois [DoorbellTalk] ayant RÉELLEMENT confirmé que les trames partent en
     * continu (state "ready") — pas un minuteur à l'aveugle. Avant : délai fixe de 700 ms après
     * "active", qui ne suffisait pas toujours (connexion HA→sonnette parfois ~2 s) → premier mot
     * ("bonjour") avalé pendant que le pipe finissait de s'établir. [SPEAK_READY_FALLBACK_MS] reste
     * un filet de sécurité si "ready" n'arrivait jamais (cas limite), pas le chemin normal.
     */
    private fun setTalkState(state: String) {
        runOnUiThread {
            talkIndicator.visibility = View.VISIBLE
            when {
                state == "active" -> {
                    talkIndicator.text = "⏳ Préparation…"
                    talkIndicator.setBackgroundColor(Color.parseColor("#F9A825")) // orange
                    talkIndicator.removeCallbacks(showSpeakNow)
                    talkIndicator.postDelayed(showSpeakNow, SPEAK_READY_FALLBACK_MS)
                }
                state == "ready" -> {
                    talkIndicator.removeCallbacks(showSpeakNow)
                    showSpeakNow.run()
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
        val myGen = sessionGen
        thread(name = "lan-probe") {
            val useDirect = directPref && Lan.isDoorbellOnLan(this)
            if (myGen != sessionGen) return@thread   // session remplacée/terminée pendant la sonde
            onLan = useDirect
            DebugLog.log("IncomingCall", "chemin: " + when {
                useDirect -> "RTSP DIRECT (sans HA)"
                directPref -> "mode direct ON mais sonnette hors LAN → WebRTC/HA"
                else -> "WebRTC/HA (mode direct OFF)"
            })
            runOnUiThread {
                if (isFinishing || isDestroyed || myGen != sessionGen) return@runOnUiThread
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
        val myGen = sessionGen
        thread(name = "snapshot") {
            var first = true
            // myGen : une ancienne boucle qui dormait encore (Thread.sleep) ne doit pas repartir
            // pour toujours si une NOUVELLE session remet snapshotRunning=true entre-temps — sans
            // ça, deux boucles tournent en même temps sur le même drapeau partagé.
            while (snapshotRunning && myGen == sessionGen) {
                val callIdAtRequest = callId   // capturé AVANT la requête réseau, pour l'archive
                try {
                    val req = Request.Builder()
                        .url(Config.cameraSnapshotUrl())
                        .addHeader("Authorization", "Bearer ${Config.HA_LONG_LIVED_TOKEN}")
                        .build()
                    Net.base.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
                        .newCall(req).execute().use { resp ->
                            // Une requête en vol au moment d'un remplacement d'appel peut revenir
                            // APRÈS coup — revérifier ici (pas juste au tour de boucle précédent)
                            // avant de toucher l'UI ou d'archiver sous le call_id du NOUVEL appel.
                            if (myGen != sessionGen) return@use
                            val bytes = if (resp.isSuccessful) resp.body?.bytes() else null
                            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                            if (bmp != null) runOnUiThread {
                                if (myGen == sessionGen && snapshot.visibility == View.VISIBLE) snapshot.setImageBitmap(bmp)
                            }
                            if (first) {
                                DebugLog.log("IncomingCall", "snapshot ${if (bmp != null) "OK (refresh)" else "vide (HTTP ${resp.code})"}")
                                first = false
                                // 2026-08-16 : repli si la notif d'appel n'avait pas d'image_url (ou son
                                // téléchargement a échoué) — cette image de l'aperçu live sert alors de
                                // photo archivée. Dédupliqué par call_id : sans effet si déjà archivée.
                                if (bmp != null) {
                                    DeliveryStore.recordForCall(this@IncomingCallActivity, callIdAtRequest, "Sonnette", "Sonnette", bmp)
                                }
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
        // Filet de sécurité UNIQUEMENT : le chemin normal est le state "ready" de DoorbellTalk
        // (confirmation réelle que les trames partent). Ne déclenche que si "ready" n'arrive jamais.
        private const val SPEAK_READY_FALLBACK_MS = 2500L
        // Anti-Larsen : force de réduction du son visiteur pendant qu'on parle (0..1). 0.85 = le
        // son visiteur descend à ~15% de son volume pendant la parole active, remonte dès qu'on
        // se tait. À affiner à l'écoute : plus haut = moins de larsen mais visiteur moins entendu
        // par-dessus pendant qu'on parle ; plus bas = l'inverse.
        private const val DUCK_AMOUNT = 0.85
    }
}
