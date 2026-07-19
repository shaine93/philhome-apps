
package com.philhome.sonnettevideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Ré-enregistre le token FCM au démarrage du téléphone.
 * Robustesse : après un redémarrage (mise à jour, coupure de courant), le token
 * est repoussé vers Home Assistant sans que personne n'ait à rouvrir l'app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == "android.intent.action.QUICKBOOT_POWERON") {
            // Relance le service permanent dès le boot (BOOT_COMPLETED autorise le démarrage d'un FGS).
            try { KeepAliveService.start(context.applicationContext) } catch (_: Exception) { }
            try {
                FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                    TokenRegistrar.send(context.applicationContext, token)
                }
            } catch (_: Exception) { }
            // Re-programme la ré-inscription périodique (survit aux reboots).
            try { TokenRefreshWorker.schedule(context.applicationContext) } catch (_: Exception) { }
        }
    }
}
