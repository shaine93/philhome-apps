package com.philhome.sonnettevideo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reçoit l'alarme `setAndAllowWhileIdle` du [KeepAliveService] (qui perce la veille Doze),
 * envoie UN battement de cœur, puis reprogramme le prochain. Cette chaîne remplace l'ancien
 * `Thread.sleep(10 min)` qui était gelé par la veille → les pings deviennent FIABLES et le
 * heartbeat redevient une vraie preuve de vie (plus de fausses alertes « app morte »).
 */
class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        DebugLog.init(context.applicationContext)
        KeepAliveService.sendHeartbeat(context)          // ping (thread interne)
        KeepAliveService.scheduleHeartbeatAlarm(context) // reprogramme le suivant
        // Sécurité : s'assurer que le service permanent tourne toujours.
        KeepAliveService.start(context)
    }
}
