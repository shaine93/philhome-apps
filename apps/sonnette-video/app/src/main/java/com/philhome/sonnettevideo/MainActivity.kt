package com.philhome.sonnettevideo

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Écran d'accueil + DIAGNOSTIC (design sobre sombre, cf. [Ui]).
 * Affiche l'état de la permission plein écran, le token FCM, le résultat de l'enregistrement
 * auprès de Home Assistant, et regroupe les actions en sections (Tests / Réglages / Diagnostic).
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var diag: TextView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.init(applicationContext)
        DebugLog.log("MainActivity", "ouverture app")
        KeepAliveService.start(applicationContext)   // garde le processus vivant 24/7 (reçoit tous les pushs)
        Net.prewarm()   // chauffe DNS+TLS vers HA → connexion talk/vidéo plus rapide ensuite

        window.statusBarColor = Ui.BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })

        // En-tête
        root.addView(TextView(this).apply {
            text = "Sonnette Vidéo"
            textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Interphone vidéo · maison"
            textSize = 14f; setTextColor(Ui.MUTED); setPadding(0, dp(2), 0, 0)
        })

        // BOUTON PRINCIPAL EN HAUT — le plus visible : galerie historique livreurs.
        root.addView(TextView(this).apply {
            text = "📷  HISTORIQUE LIVREURS"
            setTextColor(Color.WHITE); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rounded(Ui.GREEN, dp(16).toFloat()))
            layoutParams = lp(dp(20)).apply { height = dp(72) }
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GalleryActivity::class.java))
            }
        })
        root.addView(TextView(this).apply {
            text = "Photos des livreurs / rôdeurs — 6 mois, jour par jour, avec zoom"
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Carte d'état (permission plein écran)
        status = TextView(this).apply {
            textSize = 15f; setTextColor(Ui.TEXT)
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(14).toFloat(), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(dp(18))
        }
        root.addView(status)

        // Carte COMPTEUR (sonneries reçues sur ce tél, par jour)
        root.addView(sectionLabel("Compteur des sonneries"))
        counter = TextView(this).apply {
            textSize = 15f; setTextColor(Ui.TEXT)
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(14).toFloat(), dp(1))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = lp(dp(8))
        }
        root.addView(counter)

        // Section TESTS
        root.addView(sectionLabel("Tests"))
        root.addView(primaryBtn("🔔  Tester la sonnerie") {
            CallForegroundService.ring(this, "Test — quelqu'un sonne", "test", null)
        })
        root.addView(secondaryBtn("🎤  Tester le talk-back (direct)") {
            DebugLog.log("MainActivity", "bouton Tester talk-back")
            startActivity(
                Intent(this, IncomingCallActivity::class.java)
                    .setAction("ANSWER").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        })

        // Section ÉCRAN D'ACCUEIL — bascule entre le mode avancé (cet écran, historique) et le
        // mode simple (SimpleHomeActivity : image de la porte, rien d'autre — pour un téléphone
        // destiné à quelqu'un qui n'a besoin de rien de plus, ex. celui de maman). Volontaire
        // uniquement : jamais changé tout seul. Voir Prefs.uiModeSimple / HomeActivity.
        root.addView(sectionLabel("Écran d'accueil"))
        val uiModeBtn = secondaryBtn("") {}
        fun paintUiMode() {
            uiModeBtn.text = if (Prefs.uiModeSimple(this))
                "🟢  Mode simple ACTIVÉ (image de la porte)"
            else
                "⚪  Mode simple désactivé (cet écran par défaut)"
        }
        uiModeBtn.setOnClickListener {
            Prefs.setUiModeSimple(this, !Prefs.uiModeSimple(this))
            paintUiMode()
            DebugLog.log("MainActivity", "uiModeSimple = ${Prefs.uiModeSimple(this)}")
        }
        paintUiMode()
        root.addView(uiModeBtn)
        root.addView(TextView(this).apply {
            text = "Activé = à la prochaine ouverture de l'app, l'écran d'accueil affiche " +
                "directement l'image de la porte, sans rien d'autre. Cet écran-ci (tous les " +
                "réglages) reste accessible via la petite roue ⚙ en bas à droite. " +
                "À activer sur le téléphone de maman, pas sur celui de test."
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Section MODE (transition sans-HA) — interrupteur « bypass ».
        // OFF (défaut) = comportement historique via HA → rien ne casse (à laisser sur le tél de maman).
        // ON = vidéo + voix EN DIRECT à la sonnette (RTSP + LAN, sans HA) quand on est à la maison.
        root.addView(sectionLabel("Mode (transition)"))
        val modeBtn = secondaryBtn("") {}
        fun paintMode() {
            modeBtn.text = if (Prefs.directLan(this))
                "🟢  Direct LAN : ACTIVÉ (sans HA)"
            else
                "⚪  Direct LAN : désactivé (via HA)"
        }
        modeBtn.setOnClickListener {
            Prefs.setDirectLan(this, !Prefs.directLan(this))
            paintMode()
            DebugLog.log("MainActivity", "interrupteur Direct LAN = ${Prefs.directLan(this)}")
        }
        paintMode()
        root.addView(modeBtn)
        root.addView(TextView(this).apply {
            text = "Activé = vidéo + voix en direct à la sonnette, sans Home Assistant (à la maison). " +
                "Laisse désactivé sur le téléphone de maman tant qu'on teste."
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Section MODE RÉUNION — sonnerie silencieuse mais vibration (discret pendant une réunion).
        root.addView(sectionLabel("Mode réunion"))
        val meetingBtn = secondaryBtn("") {}
        fun paintMeeting() {
            meetingBtn.text = if (Prefs.meetingMode(this))
                "🔕  Réunion : ACTIVÉ (silencieux + vibreur)"
            else
                "🔔  Réunion : désactivé (sonnerie normale)"
        }
        meetingBtn.setOnClickListener {
            Prefs.setMeetingMode(this, !Prefs.meetingMode(this))
            paintMeeting()
            DebugLog.log("MainActivity", "Mode réunion = ${Prefs.meetingMode(this)}")
        }
        paintMeeting()
        root.addView(meetingBtn)
        root.addView(TextView(this).apply {
            text = "Activé = la sonnette VIBRE mais ne fait AUCUN bruit. Idéal en réunion. " +
                "Pense à le désactiver après."
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Section FILTRE ANTI-VENT (ta voix → visiteur) — réglage manuel EN PLUS de
        // l'auto-ajustement permanent (le plancher de bruit s'adapte déjà tout seul au vent
        // ambiant). Ce bouton ne fait que déplacer le point de départ.
        root.addView(sectionLabel("Filtre anti-vent (ta voix)"))
        val windBtn = secondaryBtn("") {}
        fun paintWind() {
            windBtn.text = when (Prefs.windFilterLevel(this)) {
                0 -> "🍃  Léger (laisse plus passer)"
                2 -> "💨  Fort (plus sélectif par vent fort)"
                else -> "🌤️  Normal (par défaut)"
            }
        }
        windBtn.setOnClickListener {
            val next = (Prefs.windFilterLevel(this) + 1) % 3
            Prefs.setWindFilterLevel(this, next)
            paintWind()
            DebugLog.log("MainActivity", "Filtre anti-vent = niveau $next")
        }
        paintWind()
        root.addView(windBtn)
        root.addView(TextView(this).apply {
            text = "Le filtre s'adapte déjà tout seul au bruit ambiant (auto-ajustement permanent). " +
                "Ce bouton règle juste le point de départ : Léger si des mots doux sont parfois coupés, " +
                "Fort si le vent passe encore trop par très fort vent."
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Capture diagnostic (micro brut + filtré dans deux .wav) pour MESURER le filtre sur un
        // vrai test terrain au lieu de deviner les réglages — voir tools/dsp-bench. À activer
        // juste avant un test dehors, désactiver après (fichiers dans le dossier debug de l'app).
        val captureBtn = secondaryBtn("") {}
        fun paintCapture() {
            captureBtn.text = if (Prefs.audioCaptureEnabled(this))
                "🔴  Capture micro (test vent) ACTIVÉE"
            else
                "⚪  Capture micro (test vent) désactivée"
        }
        captureBtn.setOnClickListener {
            Prefs.setAudioCaptureEnabled(this, !Prefs.audioCaptureEnabled(this))
            paintCapture()
            DebugLog.log("MainActivity", "audioCaptureEnabled = ${Prefs.audioCaptureEnabled(this)}")
        }
        paintCapture()
        root.addView(captureBtn)
        root.addView(TextView(this).apply {
            text = "Active AVANT un test terrain (dehors, vent réel) : enregistre le micro brut " +
                "et le signal filtré dans deux .wav pendant le prochain talk-back. Récupérables via " +
                "adb pull, à rejouer avec tools/dsp-bench. Désactive après le test."
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Section MESSAGE D'ACCUEIL — envoyé automatiquement à la sonnette dès qu'elle sonne,
        // que quelqu'un décroche ou non côté téléphone. Enregistré une fois, réutilisé à chaque sonnerie.
        root.addView(sectionLabel("Message d'accueil sonnette"))
        val greetingBtn = secondaryBtn("") {}
        val greetingPreviewBtn = secondaryBtn("▶️  Écouter") {}
        val greetingDeleteBtn = secondaryBtn("🗑  Supprimer") {}
        var recording = false
        fun paintGreeting() {
            val has = GreetingRecorder.exists(this)
            greetingBtn.text = when {
                recording -> "⏺  Enregistrement…"
                has -> "🎙️  Ré-enregistrer (remplace l'actuel)"
                else -> "🎙️  Enregistrer un message"
            }
            greetingPreviewBtn.visibility = if (has && !recording) ViewGroup.VISIBLE else ViewGroup.GONE
            greetingDeleteBtn.visibility = if (has && !recording) ViewGroup.VISIBLE else ViewGroup.GONE
            greetingPreviewBtn.text = if (has) "▶️  Écouter (${"%.0f".format(GreetingRecorder.durationSeconds(this))}s)" else "▶️  Écouter"
        }
        greetingBtn.setOnClickListener {
            if (recording) {
                GreetingRecorder.stopRecording()
            } else {
                recording = true
                paintGreeting()
                GreetingRecorder.startRecording(
                    this,
                    onTick = { secs -> greetingBtn.text = "⏺  Enregistrement… %.0fs".format(secs) },
                    onDone = { ok ->
                        recording = false
                        paintGreeting()
                        DebugLog.log("MainActivity", "message d'accueil enregistré: $ok")
                    }
                )
            }
        }
        greetingPreviewBtn.setOnClickListener {
            greetingPreviewBtn.isEnabled = false
            GreetingRecorder.preview(this) { greetingPreviewBtn.isEnabled = true }
        }
        greetingDeleteBtn.setOnClickListener {
            GreetingRecorder.delete(this)
            paintGreeting()
        }
        paintGreeting()
        root.addView(greetingBtn)
        root.addView(greetingPreviewBtn)
        root.addView(greetingDeleteBtn)
        root.addView(TextView(this).apply {
            text = "Envoyé automatiquement à la sonnette dès qu'elle sonne (livreur ou visiteur), " +
                "que tu décroches ou non. Ex: « Bonjour, ne bougez pas, nous allons vous répondre. »"
            textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(6)).apply { leftMargin = dp(4) }
        })

        // Section RÉGLAGES
        root.addView(sectionLabel("Réglages"))
        root.addView(secondaryBtn("⚙️  Réglages requis (écran verrouillé)") {
            startActivity(Intent(this, PermissionsActivity::class.java))
        })
        root.addView(secondaryBtn("🔄  Réessayer l'enregistrement") { registerFcmToken() })

        // Section APPLICATION (version + mise à jour intégrée)
        root.addView(sectionLabel("Application"))
        val appVersion = TextView(this).apply {
            text = "Version installée : v${AppUpdater.currentName(this@MainActivity)}"
            textSize = 13f; setTextColor(Ui.MUTED)
            layoutParams = lp(dp(8)).apply { leftMargin = dp(4) }
        }
        root.addView(appVersion)
        root.addView(primaryBtn("⬇️  Mettre à jour l'app") {
            appVersion.text = "Vérification…"
            AppUpdater.checkAndUpdate(this) { appVersion.text = it }
        })

        // Section DIAGNOSTIC
        root.addView(sectionLabel("Diagnostic"))
        root.addView(secondaryBtn("🎬  Vidéo (dev)") {
            startActivity(Intent(this, DevVideoActivity::class.java))
        })
        root.addView(secondaryBtn("📋  Voir le log debug") { diag.text = DebugLog.tail(80) })
        root.addView(secondaryBtn("🗑  Effacer le log debug") {
            DebugLog.clear(); diag.text = "log vidé\n${DebugLog.path()}"
        })

        diag = TextView(this).apply {
            textSize = 12.5f; setTextColor(Ui.MUTED); setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            background = Ui.rounded(Ui.SURFACE, dp(12).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = lp(dp(14))
        }
        root.addView(diag)

        requestNeededPermissions()
        registerFcmToken()
        // Fiabilité : ré-inscription périodique du token (toutes les 12 h, même app fermée).
        TokenRefreshWorker.schedule(applicationContext)
    }

    override fun onResume() { super.onResume(); refreshStatus(); refreshCounter() }

    private fun refreshCounter() {
        val hist = RingCounter.history(this, 7)
        val today = hist.firstOrNull()?.second ?: 0
        val past = hist.drop(1)
            .joinToString("\n") { (label, n) -> "   $label : $n" }
        counter.text = "📞  Reçues sur ce tél aujourd'hui : $today\n\n" +
            "7 derniers jours :\n$past\n\n" +
            "ℹ️ À comparer au compteur HA « appuis détectés » (indépendant du token). " +
            "Un écart = une sonnerie ratée."
    }

    // ---- construction UI ----

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Color.WHITE); textSize = 17f
        typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        background = Ui.ripple(Ui.rounded(Ui.ACCENT, dp(14).toFloat()))
        layoutParams = (lp(dp(10))).apply { height = dp(56) }
        setOnClickListener { onClick() }
    }

    private fun secondaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; setTextColor(Ui.TEXT); textSize = 16f; gravity = Gravity.CENTER
        background = Ui.ripple(Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(14).toFloat(), dp(1)))
        layoutParams = (lp(dp(10))).apply { height = dp(52) }
        setOnClickListener { onClick() }
    }

    private fun sectionLabel(t: String) = TextView(this).apply {
        text = t.uppercase(); setTextColor(Ui.MUTED); textSize = 12f
        letterSpacing = 0.12f; typeface = Typeface.DEFAULT_BOLD
        layoutParams = lp(dp(24)).apply { bottomMargin = dp(2); leftMargin = dp(4) }
    }

    /** LinearLayout.LayoutParams pleine largeur avec marge haute. */
    private fun lp(topMarginPx: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = topMarginPx }

    // ---- logique (inchangée) ----

    private fun requestNeededPermissions() {
        val perms = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (perms.isNotEmpty()) requestPermissions(perms.toTypedArray(), 1)
    }

    private fun registerFcmToken() {
        diag.text = "Enregistrement en cours…"
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                TokenRegistrar.send(applicationContext, token) { _ ->
                    runOnUiThread { refreshDiag() }
                }
                runOnUiThread { refreshDiag() }
            }
            .addOnFailureListener { e ->
                runOnUiThread {
                    diag.text = "❌ Token FCM introuvable (Firebase) :\n${e.javaClass.simpleName} — ${e.message}"
                }
            }
    }

    private fun refreshDiag() {
        val tok = TokenRegistrar.lastToken
        val tokShort = if (tok.length > 24) tok.take(16) + "…" + tok.takeLast(6) else tok.ifEmpty { "(pas encore)" }
        diag.text = "Token FCM : $tokShort\n\nEnregistrement serveur : ${TokenRegistrar.lastResult}\n\nURL : ${Config.fcmRegisterUrl()}"
    }

    private fun refreshStatus() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val fsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
        if (!fsOk) {
            status.setTextColor(Ui.AMBER)
            status.text = "⚠️  Autorise les « Notifications plein écran » (appuie ici)"
            status.setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(android.net.Uri.parse("package:$packageName")))
            }
        } else {
            status.setTextColor(Ui.GREEN)
            status.text = "✓  Notifications plein écran autorisées"
            status.setOnClickListener(null)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
