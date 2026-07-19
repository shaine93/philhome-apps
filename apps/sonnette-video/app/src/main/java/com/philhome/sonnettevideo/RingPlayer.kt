package com.philhome.sonnettevideo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Lecteur UNIQUE de la sonnerie d'appel (son + vibration).
 *
 * Détenu par [CallForegroundService] : la sonnerie démarre **dès l'appel**, que l'écran soit
 * ALLUMÉ ou éteint — indépendamment de l'ouverture de l'écran d'appel (qui, écran allumé,
 * ne s'ouvre pas tout seul : Android n'affiche qu'une notif heads-up).
 *
 * Une SEULE source de son (le canal de notif est silencieux) → plus de double sonnerie décalée,
 * et [stop] coupe VRAIMENT (son + vibration). Son = `res/raw/sonnerie` si présent, sinon la
 * sonnerie par défaut, en USAGE_ALARM (audible même en silencieux). Idempotent.
 */
object RingPlayer {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var active = false   // idempotence indépendante du son (en mode réunion, player reste null)
    private var appCtx: Context? = null
    private var savedAlarmVol = -1   // volume alarme d'origine à restaurer (−1 = rien à restaurer)

    @Synchronized
    fun start(ctx: Context) {
        if (active) return   // déjà en train de sonner (son ET/OU vibration)
        active = true
        appCtx = ctx.applicationContext

        // MODE RÉUNION : on VIBRE mais on ne joue AUCUN son. Sinon, sonnerie normale (son + vibration).
        val silent = Prefs.meetingMode(ctx)
        DebugLog.init(ctx)
        DebugLog.push("RingPlayer", if (silent) "SONNERIE reçue → MODE RÉUNION : silencieux + vibration seule" else "SONNERIE reçue → sonnerie normale (son + vibration)")

        // SÉCURITÉ AUDIBILITÉ : hors mode réunion, forcer le volume ALARME au MAX (sauvegarde pour
        // restaurer ensuite). Sans ça, si le volume alarme du téléphone est bas/à zéro, la sonnette
        // « sonne » en silence et personne ne l'entend — inacceptable pour la sécurité de maman.
        if (!silent) try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            savedAlarmVol = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0
            )
            DebugLog.log("RingPlayer", "volume alarme forcé au max (était $savedAlarmVol)")
        } catch (e: Exception) {
            DebugLog.log("RingPlayer", "forçage volume alarme KO: ${e.message}")
        }

        if (!silent) try {
            val rawId = ctx.resources.getIdentifier("sonnerie", "raw", ctx.packageName)
            val uri = if (rawId != 0)
                android.net.Uri.parse("android.resource://${ctx.packageName}/raw/sonnerie")
            else
                RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_RINGTONE)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            player = MediaPlayer().apply {
                setDataSource(ctx, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}
        try {
            DebugLog.init(ctx)
            val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as android.os.VibratorManager).defaultVibrator
            val hasAmp = v.hasAmplitudeControl()
            DebugLog.log("RingPlayer", "vib hasVibrator=${v.hasVibrator()} hasAmplitude=$hasAmp")
            val timings = longArrayOf(0, 700, 500, 700, 500)
            // Si l'appareil ne gère pas l'amplitude, un effet AVEC amplitudes est ignoré → fallback timing.
            val effect = if (hasAmp)
                VibrationEffect.createWaveform(timings, intArrayOf(0, 255, 0, 255, 0), 0)
            else
                VibrationEffect.createWaveform(timings, 0)
            // USAGE_RINGTONE (plus fiable que ALARM sur MIUI) via VibrationAttributes (API 33+),
            // sinon AudioAttributes ALARME.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val va = android.os.VibrationAttributes.Builder()
                    .setUsage(android.os.VibrationAttributes.USAGE_RINGTONE).build()
                v.vibrate(effect, va)
            } else {
                val aa = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                v.vibrate(effect, aa)
            }
            vibrator = v
            DebugLog.log("RingPlayer", "vibrate() lancé (${if (hasAmp) "amp" else "timing"})")
        } catch (e: Exception) {
            DebugLog.log("RingPlayer", "vib EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        try { DebugLog.push("RingPlayer", "STOP appelé (sonnait=${active}, player=${player != null})") } catch (_: Exception) {}
        active = false
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        // Restaurer le volume alarme d'origine (on ne modifie pas durablement le réglage du téléphone).
        if (savedAlarmVol >= 0) {
            try {
                val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                am?.setStreamVolume(android.media.AudioManager.STREAM_ALARM, savedAlarmVol, 0)
            } catch (_: Exception) {}
            savedAlarmVol = -1
        }
    }
}
