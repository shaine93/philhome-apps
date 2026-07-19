package com.philhome.tradingclaudegod

import kotlin.math.abs
import kotlin.math.max

/**
 * AVIS AUTOMATIQUE = lecture technique objective des cours (tendance de fond, volatilité, position
 * dans la fourchette de l'année, momentum). Ce n'est PAS un conseil d'achat ni une prédiction :
 * juste une synthèse lisible de ce que disent les chiffres, pour aider à réfléchir.
 */
object Advisor {

    data class Avis(val headline: String, val color: Int, val points: List<String>)

    fun analyse(closes: List<Double>, price: Double): Avis? {
        if (closes.size < 30 || price <= 0) return null
        val ma50 = if (closes.size >= 50) closes.takeLast(50).average() else null
        val ma200 = if (closes.size >= 200) closes.takeLast(200).average() else null
        val vol = MarketApi.annualVolPct(closes)
        val hi = closes.max(); val lo = closes.min()
        val pos = if (hi > lo) (price - lo) / (hi - lo) * 100 else 50.0
        val monthAgo = closes[max(0, closes.size - 1 - 21)]
        val momentum = if (monthAgo > 0) (price - monthAgo) / monthAgo * 100 else 0.0

        val points = ArrayList<String>()

        // Tendance de fond
        val haussier = ma200 != null && price > ma200
        when {
            ma200 == null -> points.add("📅  Historique court : tendance de fond difficile à juger.")
            haussier -> points.add("📈  Au-dessus de sa moyenne 200 jours → tendance de fond HAUSSIÈRE (bon signe).")
            else -> points.add("📉  Sous sa moyenne 200 jours → tendance de fond FRAGILE / baissière (prudence).")
        }

        // Volatilité (risque)
        if (vol != null) {
            val lab = when { vol < 18 -> "faible"; vol < 35 -> "modérée"; vol < 70 -> "élevée"; else -> "très élevée" }
            points.add("🛡️  Volatilité ${vol.toInt()} % (${lab}) → ${riskWord(vol)}.")
        }

        // Position dans l'année
        when {
            pos >= 85 -> points.add("⚠️  Proche de son plus haut sur 1 an (${pos.toInt()} % de la fourchette) : " +
                "déjà bien monté — un point d'entrée plus bas serait plus prudent.")
            pos <= 20 -> points.add("🔎  Proche de son plus bas sur 1 an (${pos.toInt()} %) : décoté — " +
                "opportunité SI la société est saine, ou signe d'un problème à comprendre.")
            else -> points.add("↔️  Au milieu de sa fourchette annuelle (${pos.toInt()} %).")
        }

        // Momentum 1 mois
        val m = (if (momentum >= 0) "+" else "") + momentum.toInt() + " %"
        points.add("⏱️  Sur 1 mois : $m.")

        // Synthèse (headline) — honnête, jamais un « achète »
        val (headline, color) = when {
            ma200 == null -> "Avis limité (peu d'historique)" to Ui.GOLD
            haussier && pos < 85 -> "Contexte plutôt favorable — reste discipliné sur la taille et le stop" to Ui.GREEN
            haussier && pos >= 85 -> "Tendance saine mais valeur déjà chère à court terme — patience sur le timing" to Ui.GOLD
            !haussier && pos <= 20 -> "Tendance faible mais très décoté — à ne toucher qu'en comprenant pourquoi" to Ui.GOLD
            else -> "Tendance de fond mal orientée — prudence, ce n'est pas le moment évident" to Ui.RED
        }
        return Avis(headline, color, points)
    }

    private fun riskWord(vol: Double) = when {
        vol < 18 -> "ça bouge peu, plutôt tranquille"
        vol < 35 -> "ça bouge modérément"
        vol < 70 -> "ça bouge beaucoup, secousses fréquentes"
        else -> "ça bouge énormément, réservé aux petites parts"
    }
}
