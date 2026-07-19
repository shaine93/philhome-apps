package com.philhome.sonnettevideo

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/**
 * Langage visuel partagé (design sobre, sombre, moderne) : palette + fabriques de fonds arrondis.
 * Utilisé par l'écran d'accueil et la vue live pour un rendu cohérent et accessible (maman, 82 ans :
 * fort contraste, grands aplats). Ne change PAS l'écran d'appel (qui a son propre style, et qui marche).
 */
object Ui {
    const val BG = 0xFF0E1116.toInt()        // fond quasi-noir bleuté
    const val SURFACE = 0xFF171A21.toInt()    // cartes / boutons secondaires
    const val BORDER = 0xFF2A2F3A.toInt()     // liseré discret
    const val ACCENT = 0xFF3B82F6.toInt()     // bleu (marque)
    const val GREEN = 0xFF22C55E.toInt()      // parler / OK
    const val RED = 0xFFEF4444.toInt()        // stop / direct / alerte
    const val AMBER = 0xFFF59E0B.toInt()      // avertissement
    const val TEXT = 0xFFF3F4F6.toInt()       // texte principal
    const val MUTED = 0xFF9AA3B2.toInt()      // texte secondaire

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(color)
    }

    fun stroked(fill: Int, stroke: Int, radiusPx: Float, strokePx: Int) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(fill); setStroke(strokePx, stroke)
    }

    fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    /** Fond arrondi + retour tactile (ondulation) — look moderne au clic. */
    fun ripple(base: Drawable, highlight: Int = 0x33FFFFFF): Drawable =
        RippleDrawable(ColorStateList.valueOf(highlight), base, null)

    /** Dégradé vertical (pour les voiles de lisibilité haut/bas au-dessus de la vidéo). */
    fun vScrim(top: Int, bottom: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
}
