package com.philhome.sonnettevideo

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Écran "Réglages requis" pour que l'écran d'appel s'ouvre VRAIMENT téléphone
 * verrouillé, sur Redmi/MIUI + Android 14.
 *
 * Le FGS type phoneCall ([CallForegroundService]) est nécessaire mais pas
 * suffisant : MIUI bloque par défaut l'autostart et l'ouverture de fenêtres en
 * arrière-plan, et l'optimisation batterie peut tuer FCM. Cet écran guide
 * l'utilisatrice (ou l'installateur) vers chaque réglage, avec un état ✓ / ⚠️.
 */
class PermissionsActivity : Activity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    private fun rebuild() {
        container.removeAllViews()
        container.addView(TextView(this).apply {
            text = "Réglages requis (écran verrouillé)"
            textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        // 1) Notifications
        item(
            "Notifications", notifEnabled(),
            "Autoriser l'affichage des notifications de la sonnette."
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else openAppNotifSettings()
        }

        // 2) Notifications plein écran (Android 14)
        item(
            "Notifications plein écran", fullScreenAllowed(),
            "Indispensable pour ouvrir l'écran d'appel par-dessus le verrouillage."
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                safeStart(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                    .setData(Uri.parse("package:$packageName")))
            } else openAppDetails()
        }

        // 3) Optimisation batterie (sinon FCM/FGS peuvent être tués)
        item(
            "Sans restriction batterie", batteryUnrestricted(),
            "Empêche le système de couper la réception des appels en veille."
        ) {
            safeStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName")))
        }

        // 4) MIUI : démarrage auto + pop-up en arrière-plan (état non lisible → ⚠️ info)
        item(
            "MIUI : Démarrage auto + Pop-up", null,
            "Sur Xiaomi/Redmi : activer « Démarrage auto » et « Afficher les " +
            "fenêtres pop-up en arrière-plan » pour cette app. Sans ça, l'écran " +
            "d'appel s'ouvre puis se referme."
        ) {
            openMiuiPermissionsOrDetails()
        }

        container.addView(TextView(this).apply {
            text = "\n✓ = OK   ⚠️ = à régler   ⓘ = à vérifier manuellement"
            textSize = 12f; setTextColor(Color.DKGRAY)
        })
    }

    /* ---------- États ---------- */

    private fun notifEnabled(): Boolean =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    private fun fullScreenAllowed(): Boolean {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm.canUseFullScreenIntent()
    }

    private fun batteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /* ---------- UI ---------- */

    /** [ok] null = état non déterminable (info manuelle). */
    private fun item(title: String, ok: Boolean?, desc: String, onClick: () -> Unit) {
        val badge = when (ok) { true -> "✓"; false -> "⚠️"; null -> "ⓘ" }
        container.addView(TextView(this).apply {
            text = "$badge  $title"
            textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (ok == false) Color.parseColor("#C62828") else Color.parseColor("#1B5E20"))
            setPadding(0, dp(14), 0, dp(2))
        })
        container.addView(TextView(this).apply {
            text = desc; textSize = 14f; setTextColor(Color.DKGRAY)
        })
        container.addView(Button(this).apply {
            text = "Ouvrir le réglage"
            setOnClickListener { onClick() }
        })
    }

    /* ---------- Navigation réglages ---------- */

    private fun openAppDetails() {
        // Fallback ultime : ne jamais rappeler safeStart() ici (sinon récursion).
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName")))
        } catch (_: Exception) { }
    }

    private fun openAppNotifSettings() = safeStart(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
    )

    /** Tente l'éditeur de permissions MIUI ; sinon retombe sur les détails de l'app. */
    private fun openMiuiPermissionsOrDetails() {
        val miui = Intent().apply {
            setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            )
            putExtra("extra_pkgname", packageName)
        }
        if (miui.resolveActivity(packageManager) != null) safeStart(miui) else openAppDetails()
    }

    private fun safeStart(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { openAppDetails() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
