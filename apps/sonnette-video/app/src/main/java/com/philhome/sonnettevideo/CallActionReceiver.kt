package com.philhome.sonnettevideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Actions de la notification d'appel :
 *  - REFUSER : ferme l'appel (arrête le FGS + la notif).
 *  - COUPER LE SON : coupe la sonnerie (son + vibration) SANS raccrocher → utilisable écran
 *    allumé, directement depuis la notif (l'écran d'appel ne s'ouvre pas tout seul écran allumé).
 */
class CallActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DECLINE = "com.philhome.sonnettevideo.DECLINE"
        const val ACTION_MUTE = "com.philhome.sonnettevideo.MUTE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DECLINE -> {
                val callId = intent.getStringExtra("call_id")
                CallForegroundService.stop(context, callId)   // arrête le FGS + sa notif (scopé)
                // Refuser depuis la notif (écran verrouillé, activité pas forcément ouverte) doit
                // aussi fermer l'écran d'appel s'il est affiché — même chemin scopé que l'annulation
                // distante, pour ne jamais fermer un appel plus récent que celui refusé.
                context.sendBroadcast(
                    Intent(IncomingCallActivity.ACTION_CANCEL_CALL)
                        .setPackage(context.packageName)
                        .putExtra("call_id", callId)
                )
            }
            ACTION_MUTE -> RingPlayer.stop()                        // coupe son+vibration, garde l'appel
        }
    }
}
