package com.philhome.sonnettevideo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Exigence A12 : au décrochage, audio sur le HAUT-PARLEUR principal, mains libres,
 * jamais l'oreillette. MODE_IN_COMMUNICATION active l'annulation d'écho matérielle.
 */
object AudioRouter {

    fun toSpeaker(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION

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
