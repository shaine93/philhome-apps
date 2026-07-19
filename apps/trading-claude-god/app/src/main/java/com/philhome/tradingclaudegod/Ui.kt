package com.philhome.tradingclaudegod

import android.graphics.drawable.GradientDrawable

/**
 * Langage visuel de l'app (sombre, sobre, lisible). Palette « finance » : vert = hausse/positif,
 * rouge = baisse, or = accent. UI construite en code (pas de XML), comme l'app sonnette.
 */
object Ui {
    const val BG = 0xFF0B0E14.toInt()        // fond quasi-noir
    const val SURFACE = 0xFF141A24.toInt()    // cartes
    const val BORDER = 0xFF232B39.toInt()     // liseré
    const val GREEN = 0xFF22C55E.toInt()      // hausse / positif
    const val RED = 0xFFEF4444.toInt()        // baisse / perte
    const val GOLD = 0xFFF59E0B.toInt()       // accent
    const val TEXT = 0xFFF3F4F6.toInt()       // texte principal
    const val MUTED = 0xFF95A0B3.toInt()      // texte secondaire

    fun rounded(color: Int, radiusPx: Float) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(color)
    }

    fun stroked(fill: Int, stroke: Int, radiusPx: Float, strokePx: Int) = GradientDrawable().apply {
        cornerRadius = radiusPx; setColor(fill); setStroke(strokePx, stroke)
    }
}
