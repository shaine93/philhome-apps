package com.philhome.sonnettevideo

import kotlin.math.abs

/**
 * Filtre « uniquement la voix » appliqué au micro avant l'encodage AAC.
 *
 * Objectif (demande explicite) : ne laisser passer QUE la voix — pas le vent,
 * pas le frottement des doigts sur le téléphone, pas le souffle.
 *
 * Chaîne (PCM 16 bits mono 16 kHz, traité en place) :
 *  1. Passe-haut ~250 Hz  → supprime vent, grondement, chocs/frottements (énergie grave)
 *  2. Passe-bas  ~3800 Hz → supprime souffle/sifflements aigus hors voix
 *  3. Porte de bruit douce → atténue le fond entre les mots (rampe lissée = pas de hachage)
 *
 * Réglages en tête de fichier, faciles à ajuster après écoute près de la sonnette.
 */
class VoiceFilter(sampleRate: Int = AqaraTalkProtocol.SAMPLE_RATE) {

    companion object {
        // --- Filtres passe-bande ---
        private const val HIGHPASS_HZ = 320.0      // ↑ coupe davantage le VENT/frottements (était 250)
        private const val LOWPASS_HZ = 3600.0      // ↓ coupe le souffle aigu
        private const val Q = 0.707                // Butterworth (réponse plate)

        // --- Porte de bruit (un peu plus stricte pour ne laisser passer que la voix) ---
        private const val GATE_OPEN_LEVEL = 850.0  // env. d'ouverture (↑ = plus sélectif vs vent)
        private const val GATE_CLOSE_LEVEL = 400.0 // hystérésis : seuil de fermeture (plus bas)
        private const val GATE_FLOOR = 0.04        // gain résiduel porte fermée (proche silence)
        private const val GATE_HOLD_MS = 200.0     // maintien ouvert après la fin d'un mot
        private const val ENV_RISE = 0.30          // suivi d'enveloppe : montée rapide
        private const val ENV_FALL = 0.0030        // descente lente
        private const val GAIN_ATTACK = 0.25       // rampe de gain à l'ouverture (rapide)
        private const val GAIN_RELEASE = 0.02      // rampe de gain à la fermeture (douce)

        // --- Gain de sortie (volume à la sonnette) ---
        // Amplifie la voix avant l'encodage AAC → plus FORT au haut-parleur de la sonnette.
        // ~2x ; le clamp ±32767 protège (léger écrêtage acceptable pour la voix). Monter si besoin.
        private const val MAKEUP_GAIN = 2.2
    }

    private val highpass = Biquad.highpass(HIGHPASS_HZ, sampleRate.toDouble(), Q)
    private val lowpass = Biquad.lowpass(LOWPASS_HZ, sampleRate.toDouble(), Q)

    private var env = 0.0
    private var gateOpen = false
    private var holdSamples = 0
    private val holdMax = (GATE_HOLD_MS * sampleRate / 1000.0).toInt()
    private var gain = GATE_FLOOR

    /** Traite n échantillons en place. */
    fun process(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            var x = buf[i].toDouble()

            // 1+2 : passe-bande
            x = highpass.process(x)
            x = lowpass.process(x)

            // 3 : porte de bruit sur l'enveloppe du signal filtré
            val a = abs(x)
            env += (if (a > env) ENV_RISE else ENV_FALL) * (a - env)

            if (env >= GATE_OPEN_LEVEL) {
                gateOpen = true
                holdSamples = holdMax
            } else if (env < GATE_CLOSE_LEVEL) {
                if (holdSamples > 0) holdSamples-- else gateOpen = false
            }

            val target = if (gateOpen) 1.0 else GATE_FLOOR
            val coeff = if (target > gain) GAIN_ATTACK else GAIN_RELEASE
            gain += coeff * (target - gain)

            val y = x * gain * MAKEUP_GAIN
            buf[i] = when {
                y > 32767.0 -> 32767
                y < -32768.0 -> -32768
                else -> y.toInt().toShort()
            }
        }
    }
}
