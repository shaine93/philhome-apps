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
