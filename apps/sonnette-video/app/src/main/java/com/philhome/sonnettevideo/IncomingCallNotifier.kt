package com.philhome.sonnettevideo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import java.net.HttpURLConnection
import java.net.URL

/**
 * Construit la notification CallStyle plein écran (gros bouton vert "Répondre" /
 * rouge "Refuser", par-dessus l'écran verrouillé).
 *
 * Depuis le correctif "écran d'appel verrouillé", ce n'est plus cet objet qui
 * POSTE la notif : c'est [CallForegroundService] (service de premier plan type
 * "phoneCall") qui la porte via startForeground(). Ancrer la notif full-screen à
 * un FGS d'appel est le seul moyen fiable, sur MIUI/Android 14, d'ouvrir l'écran
 * d'appel depuis l'arrière-plan SANS qu'il se referme après ~2 s.
 *
 * Ici on ne fournit donc que : la création du canal, la CONSTRUCTION de la notif
 * (avec ou sans photo), le téléchargement best-effort de la photo, et l'annulation.
 */
object IncomingCallNotifier {

    /** Construit la notif CallStyle. [largeIcon] = photo visiteur (peut être null). */
    fun build(
        ctx: Context, title: String, callId: String,
        imageUrl: String?, largeIcon: Bitmap?, withFullScreen: Boolean
    ): Notification {
        ensureChannel(ctx)
        val caller = Person.Builder().setName(title).build()

        // VERT : ouvre l'écran d'appel en mode décroché.
        val answerIntent = Intent(ctx, IncomingCallActivity::class.java).apply {
            action = "ANSWER"
            putExtra("call_id", callId)
            putExtra("image_url", imageUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val answerPi = PendingIntent.getActivity(
            ctx, 1, answerIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ROUGE : refuse / ferme (arrête le FGS via le receiver).
        val declinePi = PendingIntent.getBroadcast(
            ctx, 2,
            Intent(ctx, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_DECLINE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // COUPER LE SON : coupe la sonnerie sans raccrocher (dispo directement dans la notif, écran allumé).
        val mutePi = PendingIntent.getBroadcast(
            ctx, 4,
            Intent(ctx, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_MUTE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Plein écran : écran d'appel en mode SONNERIE (photo + vert/rouge).
        val ringingIntent = Intent(ctx, IncomingCallActivity::class.java).apply {
            action = "RINGING"
            putExtra("call_id", callId)
            putExtra("image_url", imageUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val fullScreenPi = PendingIntent.getActivity(
            ctx, 3, ringingIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(ctx, Config.INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi))
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)   // la MAJ photo ne doit pas rejouer le son
            // Taper la notif (écran allumé) ouvre l'écran d'appel → accès à « Couper le son », vidéo, etc.
            .setContentIntent(fullScreenPi)
            // Action muet directement dans la notif (utile écran allumé, sans ouvrir l'écran d'appel).
            .addAction(android.R.drawable.ic_lock_silent_mode, "Couper le son", mutePi)
        if (withFullScreen) builder.setFullScreenIntent(fullScreenPi, true)
        largeIcon?.let { builder.setLargeIcon(it) }

        return builder.build()
    }

    fun cancel(ctx: Context) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(Config.INCOMING_NOTIF_ID)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Config.INCOMING_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            Config.INCOMING_CHANNEL_ID,
            "Sonnette",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Quelqu'un sonne à la porte"
            // Canal SILENCIEUX : le son ET la vibration sont joués par IncomingCallActivity
            // (source unique, contrôlable par « Couper le son »). Ici, aucun son ni vibration
            // du canal → plus de 2e sonnerie décalée. La notif reste plein écran / haute importance.
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    private fun hasRawSound(ctx: Context): Boolean =
        ctx.resources.getIdentifier("sonnerie", "raw", ctx.packageName) != 0

    /** Télécharge la photo avec timeouts courts (jamais bloquant indéfiniment). */
    fun loadBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
