package com.philhome.sonnettevideo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Exigence A12 : au décrochage, audio sur le HAUT-PARLEUR principal, mains libres,
 * jamais l'oreillette.
 *
 * NE PAS poser `AudioManager.MODE_IN_COMMUNICATION` (retiré le 2026-09-03) : Android mute
 * automatiquement tout lecteur usage=MEDIA (donc l'audio du visiteur, joué par la WebView WebRTC
 * ou par libVLC — aucun des deux ne permet de changer son AudioAttributes.usage) dès que ce mode
 * est actif EN MÊME TEMPS qu'une capture AudioSource.VOICE_COMMUNICATION (le talk-back, toujours
 * actif pendant l'appel) — politique anti-fuite d'Android, pas un bug applicatif. Confirmé par
 * `dumpsys audio` en conditions réelles : dès `setMode(MODE_IN_COMMUNICATION)`, le lecteur WebView
 * passe `event:muted updated source:streamVolume` (visiteur inaudible côté téléphone, alors que lui
 * nous entend toujours puisque le talk-back n'utilise aucun lecteur audio local). Sans ce mode,
 * `setCommunicationDevice()`/`isSpeakerphoneOn` routent quand même vers le haut-parleur, et
 * l'AudioSource.VOICE_COMMUNICATION + AcousticEchoCanceler du micro restent actifs normalement —
 * seule la politique de mute disparaît. Root-cause confirmée + solution validée avec GPT-5.4 (voir
 * skill sonnette-video). Si du Larsen réapparaît suite à ce changement, c'est le prochain point à
 * creuser (piste de repli : jouer l'audio entrant via un AudioTrack dédié USAGE_VOICE_COMMUNICATION
 * au lieu de la WebView/libVLC, plutôt que remettre MODE_IN_COMMUNICATION).
 */
object AudioRouter {

    fun toSpeaker(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // Android 12+
            val speaker = am.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null) am.setCommunicationDevice(speaker)
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }

        // Volume voix poussé haut d'office (personne âgée).
        am.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
            0
        )
    }

    fun reset(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        am.mode = AudioManager.MODE_NORMAL
    }
}
