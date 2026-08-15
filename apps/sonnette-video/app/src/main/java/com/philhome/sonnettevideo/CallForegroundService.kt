package com.philhome.sonnettevideo

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Service de premier plan type "phoneCall" qui PORTE la notification CallStyle
 * full-screen de l'appel entrant.
 *
 * POURQUOI CE SERVICE EXISTE — c'est le correctif du bug "l'écran d'appel s'ouvre
 * 2 s puis se ferme" quand le push FCM arrive téléphone verrouillé :
 *  - Le push FCM data haute priorité réveille [SonnetteMessagingService] même app
 *    tuée, et lui accorde une courte fenêtre pour démarrer un FGS depuis le fond.
 *  - On démarre CE service en startForeground(type = PHONE_CALL). La notif full-
 *    screen est alors ANCRÉE à un appel en cours : MIUI/Android 14 autorise le
 *    lancement de l'activité depuis l'arrière-plan et NE la referme plus.
 *  - Sans cet ancrage, le full-screen intent ouvrait l'activité puis le système la
 *    fermait aussitôt (lancement d'activité en arrière-plan non ancré).
 *
 * Le service vit le temps de l'appel : il s'arrête sur "Refuser"/"Raccrocher"
 * (action STOP) ou si l'activité se termine.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "STOP reçu → arrêt du service d'appel")
                stopAndRemove()
                return START_NOT_STICKY
            }
            ACTION_RING -> startRinging(intent)
            else -> {
                // Démarrage sans action exploitable : on ne reste pas zombie.
                Log.w(TAG, "onStartCommand sans action → stopSelf")
                stopAndRemove()
            }
        }
        return START_NOT_STICKY
    }

    private fun startRinging(intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Quelqu'un sonne à la porte"
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: System.currentTimeMillis().toString()
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL)

        // 0) SON + VIBRATION TOUT DE SUITE (source unique, écran allumé OU éteint). Le canal de notif
        //    est silencieux → c'est RingPlayer qui sonne, coupable par « Couper le son »/décroché.
        RingPlayer.start(this)

        // 1) SONNER TOUT DE SUITE — notif full-screen sans photo, portée par le FGS.
        val notif = IncomingCallNotifier.build(
            this, title, callId, imageUrl, largeIcon = null, withFullScreen = true
        )
        try {
            ServiceCompat.startForeground(
                this, Config.INCOMING_NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
            Log.i(TAG, "startForeground(PHONE_CALL) OK — écran d'appel ancré")
        } catch (e: Exception) {
            // Si le type phoneCall est refusé (prérequis non remplis selon l'OEM),
            // on poste quand même la notif full-screen : on a au moins sonné.
            Log.e(TAG, "startForeground(PHONE_CALL) refusé: ${e.javaClass.simpleName} — ${e.message}", e)
            try {
                NotificationManagerCompat.from(this).notify(Config.INCOMING_NOTIF_ID, notif)
            } catch (e2: Exception) {
                Log.e(TAG, "notify() de secours a échoué aussi: ${e2.message}", e2)
            }
        }

        // 2) Best-effort : récupérer la photo en arrière-plan (timeout court), puis
        //    mettre à jour la MÊME notif. Un réseau lent ne retarde JAMAIS la sonnerie.
        if (!imageUrl.isNullOrBlank()) {
            Thread {
                val bmp = IncomingCallNotifier.loadBitmap(imageUrl) ?: return@Thread
                val withPhoto = IncomingCallNotifier.build(
                    this, title, callId, imageUrl, largeIcon = bmp, withFullScreen = false
                )
                try {
                    NotificationManagerCompat.from(this).notify(Config.INCOMING_NOTIF_ID, withPhoto)
                } catch (_: Exception) { }
                // 2026-08-16 : une sonnette pressée ("ring") n'archivait jamais de photo dans la
                // galerie — seul l'événement séparé "motion" (IA) le faisait. On réutilise la photo
                // déjà téléchargée ci-dessus pour la notif, sans appel réseau supplémentaire.
                DeliveryStore.recordForCall(this, callId, title, "Sonnette", bmp)
            }.apply { isDaemon = true }.start()
        }
    }

    private fun stopAndRemove() {
        RingPlayer.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        IncomingCallNotifier.cancel(this)
        stopSelf()
    }

    override fun onDestroy() {
        try { DebugLog.push("CallFg", "onDestroy → RingPlayer.stop()") } catch (_: Exception) {}
        RingPlayer.stop()
        IncomingCallNotifier.cancel(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallFgService"

        const val ACTION_RING = "com.philhome.sonnettevideo.CALL_RING"
        const val ACTION_STOP = "com.philhome.sonnettevideo.CALL_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_IMAGE_URL = "image_url"

        /** Démarre l'appel entrant (depuis le push FCM ou le bouton de test). */
        fun ring(ctx: Context, title: String, callId: String, imageUrl: String?) {
            val i = Intent(ctx, CallForegroundService::class.java).apply {
                action = ACTION_RING
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_IMAGE_URL, imageUrl)
            }
            ContextCompat.startForegroundService(ctx, i)
        }

        /** Arrête l'appel (Refuser / Raccrocher / annulation distante). */
        fun stop(ctx: Context) {
            // stopService suffit à déclencher onDestroy ; on évite startForegroundService
            // ici (sinon obligation d'appeler startForeground sous 5 s).
            ctx.stopService(Intent(ctx, CallForegroundService::class.java))
            IncomingCallNotifier.cancel(ctx)
        }
    }
}
