package com.philhome.sonnettevideo

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Reçoit les pushs envoyés par la VM (FCM HTTP v1, message DATA priorité haute).
 * Payload attendu (data) :
 *   type      = "ring"
 *   call_id   = identifiant d'événement (anti-doublon)
 *   image_url = URL signée de la photo du visiteur (camera_proxy + access_token)
 *   title     = texte affiché (optionnel)
 *
 * IMPORTANT : message DATA (pas notification) → onMessageReceived est appelé même
 * app en arrière-plan/tuée, ce qui permet de construire nous-mêmes la notif CallStyle.
 */
class SonnetteMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        DebugLog.init(applicationContext)
        val data = message.data
        DebugLog.push("FCM", "push reçu type=${data["type"]} from=${message.from}")
        // Auto-réparation : si on a été réveillé par un push, c'est peut-être que le processus avait
        // été tué. On (re)démarre le service permanent pour rester vivant ensuite.
        KeepAliveService.start(applicationContext)
        if (data["type"] == "ring") {
            Net.prewarm()               // chauffe la connexion dès la sonnerie
            DoorbellIp.refresh(this)    // rafraîchit l'IP pour le PROCHAIN appel (pas celui-ci)
        }
        when (data["type"]) {
            // Démarre le FGS type phoneCall : c'est lui qui porte la notif full-screen
            // et ancre l'écran d'appel pour qu'il NE se referme pas (téléphone verrouillé).
            "ring" -> {
                RingCounter.increment(this)   // compteur local "sonneries reçues sur ce tél" (à comparer au compteur HA)
                CallForegroundService.ring(
                    ctx = this,
                    title = data["title"] ?: "Quelqu'un sonne à la porte",
                    callId = data["call_id"] ?: System.currentTimeMillis().toString(),
                    imageUrl = data["image_url"]
                )
                // 2026-08-16 : message d'accueil auto vers la sonnette, indépendant de si quelqu'un
                // décroche côté téléphone — voir GreetingRecorder (enregistrement) / GreetingSender.
                GreetingSender.sendToDoorbell(applicationContext)
            }
            "cancel" -> {                                  // visiteur reparti / déjà répondu sur l'autre tél
                // SCOPÉ par call_id (2026-08-29, incident terrain) : un "cancel" en retard pour un
                // appel déjà terminé ne doit JAMAIS fermer/couper un appel plus récent affiché
                // depuis. CallForegroundService.stop et l'écran d'appel vérifient chacun eux-mêmes
                // que ce call_id correspond bien à ce qu'ils portent actuellement avant d'agir.
                val cancelCallId = data["call_id"]
                if (cancelCallId.isNullOrBlank()) {
                    // Payload distant incomplet (legacy/malformé) : contrairement à un arrêt LOCAL
                    // (bouton Raccrocher, où null = "on sait ce qu'on ferme"), un cancel DISTANT
                    // sans call_id est une donnée non fiable — CallForegroundService.stop(ctx, null)
                    // arrête sans condition, donc ne JAMAIS lui passer un null venu du réseau ici,
                    // sous peine de couper un appel actif plus récent sans rapport. Trouvé en
                    // review Codex, 2026-08-29. (return interdit ici : sauterait la re-confirmation
                    // du token FCM plus bas dans la fonction.)
                    DebugLog.push("Coord", "CANCEL reçu SANS call_id → ignoré (payload non fiable)")
                } else {
                    DebugLog.push("Coord", "CANCEL reçu (call_id=$cancelCallId) → arrêt scopé + broadcast")
                    CallForegroundService.stop(this, cancelCallId)
                    // ferme aussi l'écran d'appel s'il est ouvert (broadcast interne au package)
                    sendBroadcast(
                        Intent(IncomingCallActivity.ACTION_CANCEL_CALL)
                            .setPackage(packageName)
                            .putExtra("call_id", cancelCallId)
                    )
                }
            }
            // Présence / livreur détecté par l'IA (personne qui s'approche SANS sonner) : notif DOUCE
            // avec photo, PAS d'écran d'appel, PAS de sonnerie. Téléchargement photo → thread.
            "motion" -> {
                val title = data["title"] ?: "Quelqu'un à la porte"
                val text = data["text"] ?: data["description"] ?: "Présence détectée"
                val img = data["image_url"]
                Thread { DeliveryAlertNotifier.show(applicationContext, title, text, img) }.start()
            }
        }
        // Sécurité token : à chaque push reçu, on re-confirme l'enregistrement côté HA (auto-réparation
        // si le token avait changé). Asynchrone → ne retarde pas la sonnerie.
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener {
                TokenRegistrar.send(applicationContext, it)
            }
        } catch (_: Exception) {}
    }

    override fun onNewToken(token: String) {
        DebugLog.init(applicationContext)
        DebugLog.log("FCM", "onNewToken ${token.take(16)}…")
        // Remonte le token à HA pour que la VM sache où pousser.
        TokenRegistrar.send(applicationContext, token)
    }
}
