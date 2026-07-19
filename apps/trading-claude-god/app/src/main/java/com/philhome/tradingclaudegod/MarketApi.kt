package com.philhome.tradingclaudegod

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Récupère les cours via l'API publique Yahoo Finance (gratuite, sans clé) :
 *   https://query1.finance.yahoo.com/v8/finance/chart/<symbole>?interval=1d&range=1y
 * Fournit : prix actuel, clôture de la veille (→ variation du jour), devise, et les moyennes
 * mobiles 50/200 jours (→ verdict de tendance simple et explicable).
 */
object MarketApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    enum class Trend { HAUSSE, BAISSE, NEUTRE, INCONNU }

    data class Quote(
        val ok: Boolean,
        val price: Double = 0.0,
        val prevClose: Double = 0.0,
        val currency: String = "",
        val ma50: Double? = null,
        val ma200: Double? = null,
        val history: List<Double> = emptyList()   // ~40 dernières clôtures (pour le mini-graphique)
    ) {
        val changePct: Double
            get() = if (prevClose > 0) (price - prevClose) / prevClose * 100 else 0.0

        /** Variation sur `days` jours de bourse (7j ≈ 5, 30j ≈ 21). Null si historique trop court. */
        fun pctOver(days: Int): Double? {
            if (history.size <= days) return null
            val base = history[history.size - 1 - days]
            return if (base > 0) (price - base) / base * 100 else null
        }
        val weekPct: Double? get() = pctOver(5)
        val monthPct: Double? get() = pctOver(21)

        /** Verdict de tendance de fond, simple et explicable au débutant. */
        val trend: Trend
            get() {
                val m50 = ma50; val m200 = ma200
                if (m50 == null || m200 == null || price <= 0) return Trend.INCONNU
                return when {
                    price > m50 && m50 >= m200 -> Trend.HAUSSE
                    price < m50 && m50 <= m200 -> Trend.BAISSE
                    else -> Trend.NEUTRE
                }
            }
    }

    /** Résultat de recherche (ajout manuel d'une valeur). */
    data class Found(val symbol: String, val name: String, val kind: String)

    fun search(query: String): List<Found> {
        return try {
            val url = "https://query1.finance.yahoo.com/v1/finance/search?q=" +
                java.net.URLEncoder.encode(query, "UTF-8") + "&quotesCount=8&newsCount=0"
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) TradingClaudeGOD").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val quotes = JSONObject(body).optJSONArray("quotes") ?: return emptyList()
                val out = ArrayList<Found>()
                for (i in 0 until quotes.length()) {
                    val q = quotes.getJSONObject(i)
                    val sym = q.optString("symbol", "")
                    val type = q.optString("quoteType", "")
                    val kind = when (type) {
                        "EQUITY" -> "Action"; "ETF" -> "ETF"; "CRYPTOCURRENCY" -> "Crypto"
                        "INDEX" -> "Indice"; "MUTUALFUND" -> "Fonds"; else -> null
                    } ?: continue
                    val name = q.optString("shortname", q.optString("longname", sym))
                    if (sym.isNotBlank()) out.add(Found(sym, name, kind))
                }
                out
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Série de cours pour le graphique détail (sur une période choisie). */
    data class Series(
        val ok: Boolean,
        val closes: List<Double> = emptyList(),
        val currency: String = "",
        val price: Double = 0.0
    )

    fun fetchSeries(symbol: String, range: String, interval: String = "1d"): Series {
        return try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
                symbol + "?interval=" + interval + "&range=" + range
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Android) TradingClaudeGOD").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Series(false)
                val body = resp.body?.string() ?: return Series(false)
                val result = JSONObject(body).getJSONObject("chart")
                    .getJSONArray("result").getJSONObject(0)
                val meta = result.getJSONObject("meta")
                val closes = ArrayList<Double>()
                val arr = result.getJSONObject("indicators").getJSONArray("quote")
                    .getJSONObject(0).getJSONArray("close")
                for (i in 0 until arr.length()) if (!arr.isNull(i)) closes.add(arr.getDouble(i))
                Series(
                    ok = closes.isNotEmpty(),
                    closes = closes,
                    currency = meta.optString("currency", ""),
                    price = meta.optDouble("regularMarketPrice", closes.lastOrNull() ?: 0.0)
                )
            }
        } catch (_: Exception) {
            Series(false)
        }
    }

    // Taux de change €/$ (mis en cache 1 h) pour compter les valeurs US en euros.
    @Volatile private var eurUsd = 0.0
    @Volatile private var eurUsdAt = 0L

    private fun eurUsdRate(): Double {
        val now = System.currentTimeMillis()
        if (eurUsd > 0 && now - eurUsdAt < 3_600_000) return eurUsd
        val s = fetchSeries("EURUSD=X", "5d")
        if (s.ok && s.price > 0) { eurUsd = s.price; eurUsdAt = now }
        return if (eurUsd > 0) eurUsd else 1.08
    }

    /** Convertit un prix en euros selon sa devise (EUR tel quel, USD via le change). */
    fun toEur(price: Double, currency: String): Double =
        if (currency.equals("USD", true)) price / eurUsdRate() else price

    /**
     * Volatilité annualisée (%) = mesure OBJECTIVE du risque : à quel point le cours bouge.
     * Repères : < 18 % faible · 18–35 % modérée · 35–70 % élevée · > 70 % très élevée.
     */
    fun annualVolPct(closes: List<Double>): Double? {
        if (closes.size < 20) return null
        val rets = ArrayList<Double>()
        for (i in 1 until closes.size)
            if (closes[i - 1] > 0) rets.add((closes[i] - closes[i - 1]) / closes[i - 1])
        if (rets.size < 10) return null
        val mean = rets.average()
        val variance = rets.sumOf { (it - mean) * (it - mean) } / rets.size
        return Math.sqrt(variance) * Math.sqrt(252.0) * 100
    }

    fun fetch(symbol: String): Quote {
        return try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/" +
                symbol + "?interval=1d&range=1y"
            val req = Request.Builder()
                .url(url)
                // Yahoo bloque les requêtes sans User-Agent « navigateur ».
                .header("User-Agent", "Mozilla/5.0 (Android) TradingClaudeGOD")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Quote(false)
                val body = resp.body?.string() ?: return Quote(false)
                parse(body)
            }
        } catch (_: Exception) {
            Quote(false)
        }
    }

    private fun parse(json: String): Quote {
        val result = JSONObject(json).getJSONObject("chart")
            .getJSONArray("result").getJSONObject(0)
        val meta = result.getJSONObject("meta")
        val price = meta.optDouble("regularMarketPrice", Double.NaN)
        val prev = meta.optDouble("previousClose", meta.optDouble("chartPreviousClose", Double.NaN))
        val currency = meta.optString("currency", "")
        if (price.isNaN()) return Quote(false)

        // Clôtures journalières → moyennes mobiles 50 / 200.
        val closes = ArrayList<Double>()
        try {
            val arr = result.getJSONObject("indicators").getJSONArray("quote")
                .getJSONObject(0).getJSONArray("close")
            for (i in 0 until arr.length()) {
                if (!arr.isNull(i)) closes.add(arr.getDouble(i))
            }
        } catch (_: Exception) { }

        fun ma(n: Int): Double? =
            if (closes.size >= n) closes.takeLast(n).average() else null

        return Quote(
            ok = true,
            price = price,
            prevClose = if (prev.isNaN()) 0.0 else prev,
            currency = currency,
            ma50 = ma(50),
            ma200 = ma(200),
            history = if (closes.size > 40) closes.takeLast(40) else closes
        )
    }
}
