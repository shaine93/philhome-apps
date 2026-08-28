package com.philhome.sonnettevideo

import kotlin.math.abs
import kotlin.math.max

/**
 * Filtre « uniquement la voix » appliqué au micro avant l'encodage AAC.
 *
 * Objectif (demande explicite) : ne laisser passer QUE la voix — pas le vent,
 * pas le frottement des doigts sur le téléphone, pas le souffle.
 *
 * Chaîne (PCM 16 bits mono 16 kHz, traité en place) :
 *  1. Passe-haut ~250 Hz  → supprime vent, grondement, chocs/frottements (énergie grave)
 *  2. Passe-bas  ~3800 Hz → supprime souffle/sifflements aigus hors voix
 *  3. Porte de bruit ADAPTATIVE → les seuils suivent un plancher de bruit ambiant mesuré en
 *     continu, au lieu de valeurs fixes réglées une fois pour un vent donné. Par vent fort, le
 *     plancher monte tout seul → la porte devient moins sensible (le vent seul ne l'ouvre plus) ;
 *     par calme, le plancher redescend → redevient sensible aux voix douces. Pas de réglage manuel.
 *
 * [gain] (0..1, lissé attaque/relâche) est exposé après chaque appel à [process] : réutilisé
 * ailleurs (ex. anti-Larsen) comme signal « en train de parler » déjà lissé, sans docodage séparé.
 *
 * [sensitivity] : réglage manuel (voir [Prefs.windSensitivity]), EN PLUS de l'auto-ajustement —
 * ne remplace pas le plancher adaptatif, déplace juste son point de départ (1.0 = normal).
 */
class VoiceFilter(sampleRate: Int = AqaraTalkProtocol.SAMPLE_RATE, private val sensitivity: Double = 1.0) {

    companion object {
        // --- Filtres passe-bande ---
        private const val HIGHPASS_HZ = 320.0      // ↑ coupe davantage le VENT/frottements (était 250)
        private const val LOWPASS_HZ = 3600.0      // ↓ coupe le souffle aigu
        private const val Q = 0.707                // Butterworth (réponse plate)

        // --- Porte de bruit adaptative ---
        // Seuils = fonction du plancher de bruit mesuré, pas de constantes absolues.
        private const val OPEN_RATIO = 3.2         // porte s'ouvre à ~3.2x le plancher de bruit
        private const val OPEN_MARGIN = 80.0       // + marge absolue (évite hypersensibilité par silence quasi total)
        private const val CLOSE_RATIO = 1.7        // hystérésis : ferme sous ~1.7x le plancher
        private const val CLOSE_MARGIN = 40.0
        private const val GATE_FLOOR = 0.04        // gain résiduel porte fermée (proche silence)
        private const val GATE_HOLD_MS = 200.0     // maintien ouvert après la fin d'un mot
        private const val ENV_RISE = 0.30          // suivi d'enveloppe (voix) : montée rapide
        private const val ENV_FALL = 0.0030        // suivi d'enveloppe (voix) : descente lente
        private const val GAIN_ATTACK = 0.25       // rampe de gain à l'ouverture (rapide)
        private const val GAIN_RELEASE = 0.02      // rampe de gain à la fermeture (douce)

        // Plancher de bruit ambiant : seulement mis à jour quand la porte est FERMÉE (pas pendant
        // la voix, sinon on apprendrait la voix elle-même comme du bruit). Monte assez vite quand le
        // vent forcit (protection rapide), redescend très lentement (évite le clignotement après une
        // rafale, laisse le temps de confirmer que le calme est revenu).
        private const val FLOOR_RISE = 0.02
        private const val FLOOR_FALL = 0.0006

        // --- Gain de sortie (volume à la sonnette) ---
        // Amplifie la voix avant l'encodage AAC → plus FORT au haut-parleur de la sonnette.
        // ~2x ; le clamp ±32767 protège (léger écrêtage acceptable pour la voix). Monter si besoin.
        private const val MAKEUP_GAIN = 2.2
    }

    private val highpass = Biquad.highpass(HIGHPASS_HZ, sampleRate.toDouble(), Q)
    private val lowpass = Biquad.lowpass(LOWPASS_HZ, sampleRate.toDouble(), Q)

    private var env = 0.0
    private var noiseFloor = 0.0
    private var gateOpen = false
    private var holdSamples = 0
    private val holdMax = (GATE_HOLD_MS * sampleRate / 1000.0).toInt()
    private var gain = GATE_FLOOR

    /** Dernier gain lissé (0..1) — instantané utile hors du thread de capture (lecture seule). */
    @Volatile var lastGain: Double = GATE_FLOOR
        private set

    /**
     * Traite n échantillons en place. [onGain], si fourni, reçoit le gain lissé courant UNE FOIS
     * par appel (pas par échantillon — suffisant pour un usage type anti-Larsen, coût négligeable).
     */
    fun process(buf: ShortArray, n: Int, onGain: ((Double) -> Unit)? = null) {
        for (i in 0 until n) {
            var x = buf[i].toDouble()

            // 1+2 : passe-bande
            x = highpass.process(x)
            x = lowpass.process(x)

            // 3 : porte de bruit adaptative sur l'enveloppe du signal filtré
            val a = abs(x)
            env += (if (a > env) ENV_RISE else ENV_FALL) * (a - env)

            val openLevel = max(OPEN_MARGIN, noiseFloor * OPEN_RATIO * sensitivity + OPEN_MARGIN)
            val closeLevel = max(CLOSE_MARGIN, noiseFloor * CLOSE_RATIO * sensitivity + CLOSE_MARGIN)

            if (env >= openLevel) {
                gateOpen = true
                holdSamples = holdMax
            } else if (env < closeLevel) {
                if (holdSamples > 0) holdSamples-- else gateOpen = false
            }

            // Plancher appris UNIQUEMENT porte fermée (ne pas apprendre la voix comme du bruit).
            if (!gateOpen) {
                noiseFloor += (if (env > noiseFloor) FLOOR_RISE else FLOOR_FALL) * (env - noiseFloor)
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
        lastGain = gain
        onGain?.invoke(gain)
    }
}
