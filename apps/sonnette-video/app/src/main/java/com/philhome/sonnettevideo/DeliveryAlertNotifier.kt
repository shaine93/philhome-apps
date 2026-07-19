package com.philhome.sonnettevideo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

/**
 * Notification DOUCE « Présence / Livreur » : quand l'IA détecte une personne / un livreur qui
 * s'approche de la porte SANS sonner (cf. automatisation HA « détection livreur »).
 *
 * Volontairement DIFFÉRENTE de l'appel entrant :
 *  - PAS de plein écran, PAS de sonnerie, PAS de vibration (canal IMPORTANCE_LOW) → « elle voit
 *    sans être dérangée » (choix utilisateur pour maman, 82 ans).
 *  - grande photo (BigPicture) du snapshot pris au moment de la détection.
 *  - un tap ouvre la vue live [LiveViewActivity] (voir + entendre + parler si elle veut).
 *
 * Le push FCM correspondant a `type="motion"` ; ce chemin NE démarre PAS le service d'appel.
 */
object DeliveryAlertNotifier {

    /**
     * Poste la notification. À appeler depuis un thread (télécharge la photo).
     * @param title   ex. « Livreur à la porte » (dérivé du verdict IA).
     * @param text    description courte (ex. « personne avec un colis »).
     * @param imageUrl snapshot HA (URL /local/… publique, ou camera_proxy) — peut être null.
     */
    fun show(ctx: Context, title: String, text: String, imageUrl: String?) {
        ensureChannel(ctx)
        val photo = loadBitmap(imageUrl)

        // Archive locale pour la galerie 6 mois (la photo est déjà téléchargée → on la garde).
        if (photo != null) try { DeliveryStore.record(ctx, title, text, photo) } catch (_: Exception) {}

        // Tap → vue live (voir/entendre/parler), sans la machinerie d'appel.
        val openIntent = Intent(ctx, LiveViewActivity::class.java).apply {
            action = "VIEW_LIVE"
            putExtra("image_url", imageUrl)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            ctx, 11, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(ctx, Config.MOTION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)   // fait le « dong » (pré-API 26)
            .setSound(dongUri(ctx))                             // ignoré API 26+ (le canal gère), utile avant
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        photo?.let {
            builder.setLargeIcon(it)
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(it)
                    .bigLargeIcon(null as Bitmap?)   // évite le doublon de vignette une fois déplié
                    .setSummaryText(text)
            )
        }

        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Config.MOTION_NOTIF_ID, builder.build())
    }

    /** URI du son « dong » embarqué (res/raw/dong.m4a). */
    private fun dongUri(ctx: Context): Uri =
        Uri.parse("android.resource://${ctx.packageName}/${R.raw.dong}")

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Config.MOTION_CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT = joue le son + apparaît dans le volet, SANS bannière intrusive.
        // Le son est fixé ICI (immuable une fois le canal créé → d'où l'ID « _v2 »).
        val channel = NotificationChannel(
            Config.MOTION_CHANNEL_ID,
            "Présence / Livreur",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Quelqu'un s'approche de la porte sans sonner (livreur, visiteur)"
            setSound(
                dongUri(ctx),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(false)   // juste le « dong », pas de vibration → reste doux
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    /** Télécharge la photo avec timeouts courts (jamais bloquant indéfiniment). */
    private fun loadBitmap(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
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
