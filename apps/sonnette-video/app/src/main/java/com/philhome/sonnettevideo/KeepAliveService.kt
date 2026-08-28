package com.philhome.sonnettevideo

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Service de premier plan PERMANENT (24/7) — le correctif du bug « l'app meurt et ne sonne plus ».
 *
 * POURQUOI CE SERVICE EXISTE :
 *  - Sans service de premier plan, le processus de l'app est classé « en cache » par Android →
 *    c'est le PREMIER tué sous pression mémoire. Sur MIUI c'est brutal (quelques heures).
 *  - Une fois le processus mort, MIUI EMPÊCHE FCM de le relancer (sauf « Autostart » accordé) →
 *    l'app cesse de sonner ET de recevoir les alertes livreur. Constaté en réel : log du téléphone
 *    figé pendant 2 jours (dernière trace 07-13 16:03, plus rien ensuite).
 *  - Ce service garde le processus VIVANT en permanence via une notif ongoing discrète. Un processus
 *    vivant reçoit TOUJOURS le push FCM. START_STICKY + relance au boot + relance à chaque push →
 *    l'app se soigne toute seule.
 *
 * C'est ce que font les vraies apps de sonnette / sécurité (et ce qui manquait ici).
 * Complément indispensable : « Autostart » MIUI activé une fois (réglage manuel, hors code).
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DebugLog.init(applicationContext)
        ensureChannel(this)

        val tapPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Sonnette active")
            .setContentText("Surveillance de la porte en cours")
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tapPi)
            .build()

        // Android 14+ exige un type de FGS. « specialUse » = maintien en vie pour recevoir les pushs
        // de la sonnette (aucun type standard ne correspond au « rester joignable »).
        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
            DebugLog.log("KeepAlive", "service permanent ACTIF (processus maintenu vivant 24/7)")
        } catch (e: Exception) {
            DebugLog.log("KeepAlive", "startForeground KO: ${e.javaClass.simpleName} ${e.message}")
        }
        if (startElapsed == 0L) startElapsed = SystemClock.elapsedRealtime()
        sendHeartbeat(this)           // ping immédiat
        scheduleHeartbeatAlarm(this)  // prochain ping via alarme ANTI-VEILLE (perce Doze)
        scheduleBackupAlarm(this)     // filet : ré-arme le service même si START_STICKY échoue

        // Si MIUI nous tue quand même, START_STICKY demande au système de nous relancer.
        return START_STICKY
    }

    /**
     * L'utilisateur balaie l'app depuis les récentes → MIUI tue le processus.
     * On programme une relance immédiate via AlarmManager (le service repart tout seul).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        DebugLog.log("KeepAlive", "onTaskRemoved (app balayée) → relance programmée")
        try {
            val pi = restartPendingIntent(this)
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
        } catch (_: Exception) { }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DebugLog.log("KeepAlive", "service DÉTRUIT (onDestroy) — le processus va peut-être mourir")
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "sonnette_keepalive"
        const val NOTIF_ID = 4242
        private const val HEARTBEAT_MS = 15 * 60 * 1000L   // ~15 min (setAndAllowWhileIdle plafonne ~9 min en Doze)

        /** Instant de démarrage du processus (elapsedRealtime). Remis à 0 quand le process meurt/repart. */
        @Volatile var startElapsed = 0L

        fun uptimeMin(): Long =
            if (startElapsed == 0L) 0 else (SystemClock.elapsedRealtime() - startElapsed) / 60000

        /**
         * Envoie UN battement de cœur à HA (fire-and-forget, thread séparé car réseau).
         * Appelé par le service au démarrage et par [HeartbeatReceiver] à chaque alarme.
         */
        fun sendHeartbeat(ctx: Context) {
            val app = ctx.applicationContext
            DoorbellIp.refresh(app)   // profite du même cycle ~15 min pour garder l'IP à jour
            thread(name = "heartbeat-ping", isDaemon = true) {
                val upMin = uptimeMin()
                try {
                    val body = JSONObject()
                        .put("device", Build.MODEL)
                        .put("uptime_min", upMin)
                        .put("meeting", Prefs.meetingMode(app))
                        .toString()
                    val req = Request.Builder()
                        .url(Config.heartbeatUrl())
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                    Net.base.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
                        .newCall(req).execute().use { resp ->
                            if (upMin % 30 == 0L)
                                DebugLog.push("Heartbeat", "VIVANT uptime $upMin min (HTTP ${resp.code})")
                            else
                                DebugLog.log("Heartbeat", "ping HTTP ${resp.code} (uptime $upMin min)")
                        }
                } catch (e: Exception) {
                    DebugLog.log("Heartbeat", "ping KO: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        }

        private fun heartbeatPendingIntent(ctx: Context): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 77, Intent(ctx, HeartbeatReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /**
         * Programme le PROCHAIN battement via `setAndAllowWhileIdle` → l'alarme se déclenche MÊME en
         * veille profonde (Doze), contrairement à `Thread.sleep` qui était gelé. [HeartbeatReceiver]
         * ré-appelle cette méthode à chaque fois → chaîne de pings fiable = vraie preuve de vie.
         */
        fun scheduleHeartbeatAlarm(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + HEARTBEAT_MS,
                    heartbeatPendingIntent(ctx)
                )
            } catch (_: Exception) { }
        }

        /** Démarre (ou re-démarre) le service permanent. Idempotent. */
        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, KeepAliveService::class.java)
                )
            } catch (_: Exception) { }
        }

        /** PendingIntent qui (re)démarre le service en premier plan — utilisé par les alarmes. */
        private fun restartPendingIntent(ctx: Context): PendingIntent =
            PendingIntent.getForegroundService(
                ctx, 99, Intent(ctx, KeepAliveService::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        /**
         * Filet de sécurité : une alarme inexacte toutes les ~15 min ré-arme le service.
         * Si START_STICKY ne suffit pas (OEM capricieux), l'alarme le relance — tant que
         * l'app n'a pas été « force-stop » (auquel cas seul le chien de garde HA → SMS reste).
         */
        fun scheduleBackupAlarm(ctx: Context) {
            try {
                val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 15 * 60 * 1000L,
                    15 * 60 * 1000L,
                    restartPendingIntent(ctx)
                )
            } catch (_: Exception) { }
        }

        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID, "Sonnette active", NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Garde la sonnette à l'écoute en permanence (discret)"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
    }
}
