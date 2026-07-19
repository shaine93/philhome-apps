package com.philhome.sonnettevideo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Biquad RBJ (Direct Form I). Filtre du 2e ordre réutilisable pour le DSP audio :
 *  - [VoiceFilter] : voix MONTANTE (micro du téléphone → sonnette).
 *  - [WindAudioProcessor] : son DESCENDANT (micro de la sonnette → téléphone), filtre anti-vent.
 *
 * Un Biquad porte son propre état (x1/x2/y1/y2) → **une instance par canal/étage**, non partageable.
 */
internal class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double
) {
    private var x1 = 0.0; private var x2 = 0.0
    private var y1 = 0.0; private var y2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    companion object {
        fun highpass(fc: Double, fs: Double, q: Double): Biquad {
            val w0 = 2 * PI * fc / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                b0 = (1 + cw) / 2 / a0,
                b1 = -(1 + cw) / a0,
                b2 = (1 + cw) / 2 / a0,
                a1 = (-2 * cw) / a0,
                a2 = (1 - alpha) / a0
            )
        }

        fun lowpass(fc: Double, fs: Double, q: Double): Biquad {
            val w0 = 2 * PI * fc / fs
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2 * q)
            val a0 = 1 + alpha
            return Biquad(
                b0 = (1 - cw) / 2 / a0,
                b1 = (1 - cw) / a0,
                b2 = (1 - cw) / 2 / a0,
                a1 = (-2 * cw) / a0,
                a2 = (1 - alpha) / a0
            )
        }
    }
}
