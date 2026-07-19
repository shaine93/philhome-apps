package com.philhome.tradingclaudegod

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Portefeuille VIRTUEL persistant (argent fictif). Tout est compté en euros.
 * - `cash` = liquidités fictives (départ : 10 000 €).
 * - une position = une valeur détenue : quantité + coût total investi (en €).
 *   La quantité est en « parts » (fractions autorisées, comme chez les courtiers modernes).
 *
 * P&L d'une position = valeur actuelle (qty × prix en €) − coût investi.
 */
object PortfolioStore {

    const val START_CASH = 10_000.0

    data class Position(
        val symbol: String, val name: String, val currency: String,
        var qty: Double, var costEur: Double
    )

    private var loaded = false
    private var cash = START_CASH
    private val positions = LinkedHashMap<String, Position>()

    @Synchronized
    private fun ensure(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = file(ctx)
        if (!f.exists()) { cash = START_CASH; return }
        try {
            val o = JSONObject(f.readText())
            cash = o.optDouble("cash", START_CASH)
            val pos = o.optJSONObject("positions") ?: JSONObject()
            for (k in pos.keys()) {
                val p = pos.getJSONObject(k)
                positions[k] = Position(
                    k, p.optString("name", k), p.optString("currency", "EUR"),
                    p.optDouble("qty", 0.0), p.optDouble("costEur", 0.0)
                )
            }
        } catch (_: Exception) { cash = START_CASH }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, "portfolio.json")

    @Synchronized
    private fun save(ctx: Context) {
        try {
            val pos = JSONObject()
            for ((k, p) in positions) pos.put(k, JSONObject()
                .put("name", p.name).put("currency", p.currency)
                .put("qty", p.qty).put("costEur", p.costEur))
            file(ctx).writeText(JSONObject().put("cash", cash).put("positions", pos).toString())
        } catch (_: Exception) { }
    }

    @Synchronized fun cash(ctx: Context): Double { ensure(ctx); return cash }
    @Synchronized fun positions(ctx: Context): List<Position> { ensure(ctx); return positions.values.toList() }
    @Synchronized fun position(ctx: Context, symbol: String): Position? { ensure(ctx); return positions[symbol] }

    /** Achat de `amountEur` € de la valeur au prix `priceEur` (€/part). @return message d'erreur ou null si OK. */
    @Synchronized
    fun buy(ctx: Context, symbol: String, name: String, currency: String,
            priceEur: Double, amountEur: Double): String? {
        ensure(ctx)
        if (priceEur <= 0) return "Prix indisponible."
        if (amountEur <= 0) return "Montant invalide."
        if (amountEur > cash + 0.001) return "Liquidités insuffisantes (${money(cash)} dispo)."
        val qty = amountEur / priceEur
        val p = positions[symbol]
        if (p == null) positions[symbol] = Position(symbol, name, currency, qty, amountEur)
        else { p.qty += qty; p.costEur += amountEur }
        cash -= amountEur
        save(ctx); return null
    }

    /** Vente : `amountEur` € au prix courant, ou tout si `all`. @return message d'erreur ou null si OK. */
    @Synchronized
    fun sell(ctx: Context, symbol: String, priceEur: Double, amountEur: Double, all: Boolean): String? {
        ensure(ctx)
        val p = positions[symbol] ?: return "Tu ne détiens pas cette valeur."
        if (priceEur <= 0) return "Prix indisponible."
        val positionValue = p.qty * priceEur
        val proceeds = if (all) positionValue else minOf(amountEur, positionValue)
        if (proceeds <= 0) return "Montant invalide."
        val fraction = proceeds / positionValue
        // On réduit la quantité et le coût au prorata (pour garder un P&L cohérent).
        p.qty -= p.qty * fraction
        p.costEur -= p.costEur * fraction
        cash += proceeds
        if (all || p.qty <= 0.000001) positions.remove(symbol)
        save(ctx); return null
    }

    /** Remet le portefeuille à zéro (10 000 € fictifs, aucune position). */
    @Synchronized
    fun reset(ctx: Context) {
        ensure(ctx); cash = START_CASH; positions.clear(); save(ctx)
    }

    fun money(v: Double): String =
        String.format(java.util.Locale.FRANCE, "%,.2f €", v)
}
