package com.philhome.tradingclaudegod

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Écran détail d'une valeur : GRAND graphique sur la période choisie (1 sem / 1 mois / 3 mois / 1 an)
 * + variation sur la période, plus haut / plus bas, et une explication en langage débutant.
 */
class AssetDetailActivity : Activity() {

    private lateinit var name: String
    private lateinit var symbol: String
    private lateinit var kind: String

    private lateinit var chart: SparklineView
    private lateinit var priceTv: TextView
    private lateinit var periodTv: TextView
    private lateinit var statsTv: TextView
    private lateinit var explainTv: TextView
    private val rangeButtons = HashMap<String, TextView>()
    private var currentRange = "1mo"

    private lateinit var avisBox: LinearLayout
    private lateinit var claudeBtn: TextView
    private lateinit var claudeText: TextView
    private lateinit var positionTv: TextView
    private lateinit var buyBtn: TextView
    private lateinit var sellBtn: TextView
    private var lastPrice = 0.0        // prix courant (devise native)
    private var lastCurrency = ""
    private var lastPriceEur = 0.0     // prix courant converti en €
    private val buyable get() = kind != "Indice"

    // libellé -> (range Yahoo, texte période)
    private val ranges = listOf(
        Triple("1 sem", "5d", "la semaine"),
        Triple("1 mois", "1mo", "le mois"),
        Triple("3 mois", "3mo", "3 mois"),
        Triple("1 an", "1y", "l'année")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra("name") ?: "Valeur"
        symbol = intent.getStringExtra("symbol") ?: return finish()
        kind = intent.getStringExtra("kind") ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "‹  Retour"; textSize = 14f; setTextColor(Ui.MUTED)
            setPadding(0, 0, 0, dp(12)); setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = name; textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
        })
        root.addView(TextView(this).apply {
            text = kind; textSize = 13f; setTextColor(Ui.MUTED); setPadding(0, dp(2), 0, 0)
        })

        priceTv = TextView(this).apply {
            text = "…"; textSize = 28f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
            setPadding(0, dp(14), 0, 0)
        }
        root.addView(priceTv)
        periodTv = TextView(this).apply {
            text = ""; textSize = 15f; setTextColor(Ui.MUTED); setPadding(0, dp(2), 0, dp(4))
        }
        root.addView(periodTv)

        // Carte AVIS AUTOMATIQUE
        avisBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(14).toFloat(), dp(1))
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = mp(dp(14))
            addView(TextView(this@AssetDetailActivity).apply {
                text = "AVIS AUTOMATIQUE"; textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
            })
            addView(TextView(this@AssetDetailActivity).apply {
                text = "Analyse en cours…"; textSize = 14f; setTextColor(Ui.MUTED); setPadding(0, dp(6), 0, 0)
            })
        }
        root.addView(avisBox)

        // Bouton + carte ANALYSE DE CLAUDE
        claudeBtn = TextView(this).apply {
            text = "🤖  Demander l'analyse de Claude"
            textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF0B0E14.toInt())
            background = Ui.rounded(Ui.GOLD, dp(14).toFloat())
            layoutParams = mp(dp(12)).apply { height = dp(52) }
            setOnClickListener { askClaude() }
        }
        root.addView(claudeBtn)
        claudeText = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(Ui.TEXT)
            visibility = android.view.View.GONE
            background = Ui.stroked(Ui.SURFACE, Ui.GOLD, dp(14).toFloat(), dp(1))
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = mp(dp(10))
        }
        root.addView(claudeText)

        // Sélecteur de période
        val rangeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = mp(dp(14))
        }
        for ((label, range, _) in ranges) {
            val btn = TextView(this).apply {
                text = label; textSize = 13.5f; gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }
                setOnClickListener { select(range) }
            }
            rangeButtons[range] = btn
            rangeRow.addView(btn)
        }
        root.addView(rangeRow)

        // Grand graphique
        chart = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)).apply { topMargin = dp(14) }
            background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        root.addView(chart)

        statsTv = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(Ui.TEXT); setPadding(dp(2), dp(16), dp(2), 0)
        }
        root.addView(statsTv)
        explainTv = TextView(this).apply {
            text = ""; textSize = 13.5f; setTextColor(Ui.MUTED); setPadding(dp(2), dp(12), dp(2), 0)
        }
        root.addView(explainTv)

        // --- Zone TRADING (simulation) ---
        positionTv = TextView(this).apply {
            text = ""; textSize = 14f; setTextColor(Ui.TEXT)
            background = Ui.rounded(Ui.SURFACE, dp(12).toFloat())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = mp(dp(22)); visibility = android.view.View.GONE
        }
        root.addView(positionTv)

        buyBtn = TextView(this).apply {
            text = "🟢  Acheter (simulation)"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF0B0E14.toInt())
            background = Ui.rounded(Ui.GREEN, dp(14).toFloat())
            layoutParams = mp(dp(14)).apply { height = dp(56) }
            setOnClickListener { if (buyable) buyDialog() }
        }
        root.addView(buyBtn)

        sellBtn = TextView(this).apply {
            text = "🔴  Vendre (simulation)"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(Ui.TEXT)
            background = Ui.stroked(Ui.SURFACE, Ui.RED, dp(14).toFloat(), dp(1))
            layoutParams = mp(dp(10)).apply { height = dp(52) }
            visibility = android.view.View.GONE
            setOnClickListener { sellDialog() }
        }
        root.addView(sellBtn)

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        select(currentRange)
        refreshPosition()
        loadAvis()
    }

    private fun askClaude() {
        if (!ClaudeApi.configured()) { toast("Clé Claude non configurée (build)."); return }
        claudeText.visibility = android.view.View.VISIBLE
        claudeText.text = "🤖 Claude analyse ${name}…"
        claudeText.setTextColor(Ui.MUTED)
        claudeBtn.isEnabled = false
        claudeBtn.text = "⏳  Analyse en cours…"
        thread(isDaemon = true) {
            val s = MarketApi.fetchSeries(symbol, "1y")
            val prompt = buildPrompt(s)
            val r = ClaudeApi.analyse(prompt)
            runOnUiThread {
                claudeText.text = r.text
                claudeText.setTextColor(if (r.ok) Ui.TEXT else Ui.MUTED)
                claudeBtn.isEnabled = true
                claudeBtn.text = "🤖  Redemander l'analyse de Claude"
            }
        }
    }

    private fun buildPrompt(s: MarketApi.Series): String {
        val cur = if (s.currency.isNotBlank()) currencySymbol(s.currency) else lastCurrency
        val price = if (s.price > 0) s.price else lastPrice
        val closes = s.closes
        val ma200 = if (closes.size >= 200) closes.takeLast(200).average() else null
        val trend = when {
            ma200 == null -> "historique trop court pour juger"
            price > ma200 -> "au-dessus de sa moyenne 200 jours (tendance de fond haussière)"
            else -> "sous sa moyenne 200 jours (tendance de fond fragile)"
        }
        val vol = MarketApi.annualVolPct(closes)
        val volTxt = vol?.let { "${it.toInt()} %" } ?: "inconnue"
        val hi = closes.maxOrNull() ?: price
        val lo = closes.minOrNull() ?: price
        val pos = if (hi > lo) ((price - lo) / (hi - lo) * 100).toInt() else 50
        fun over(n: Int) = if (closes.size > n) ((price - closes[closes.size - 1 - n]) / closes[closes.size - 1 - n] * 100).toInt() else null
        val week = over(5)?.let { (if (it >= 0) "+" else "") + it + " %" } ?: "?"
        val month = over(21)?.let { (if (it >= 0) "+" else "") + it + " %" } ?: "?"

        return """
Tu es un analyste financier PÉDAGOGUE qui explique à un DÉBUTANT TOTAL en bourse (français).
Voici les données factuelles (cours réels) d'une valeur :
- Nom : $name ($kind)
- Prix actuel : ${fmt(price)} $cur
- Tendance de fond : $trend
- Volatilité annualisée : $volTxt (plus c'est haut, plus ça bouge)
- Position sur 1 an : $pos % de sa fourchette (du plus bas au plus haut)
- Variation : $week sur 7 jours, $month sur 30 jours

Écris une analyse CLAIRE et COURTE en français (5 à 8 phrases), pour quelqu'un qui n'y connaît rien :
explique simplement ce que disent ces chiffres, les points d'attention, et le niveau de risque.
RÈGLES STRICTES : ne donne JAMAIS d'ordre d'achat ni de vente. Aucune prédiction de prix.
Reste factuel, prudent, rassurant sur la méthode (diversifier, ne pas tout miser, stop de protection).
Termine par la phrase exacte : « Ceci n'est pas un conseil financier. »
""".trim()
    }

    private fun loadAvis() {
        thread(isDaemon = true) {
            val s = MarketApi.fetchSeries(symbol, "1y")
            val avis = if (s.ok) Advisor.analyse(s.closes, if (s.price > 0) s.price else s.closes.last()) else null
            runOnUiThread { paintAvis(avis) }
        }
    }

    private fun paintAvis(avis: Advisor.Avis?) {
        avisBox.removeAllViews()
        avisBox.addView(TextView(this).apply {
            text = "AVIS AUTOMATIQUE"; textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
        })
        if (avis == null) {
            avisBox.addView(TextView(this).apply {
                text = "Analyse indisponible (données insuffisantes)."
                textSize = 14f; setTextColor(Ui.MUTED); setPadding(0, dp(6), 0, 0)
            })
            return
        }
        avisBox.addView(TextView(this).apply {
            text = avis.headline; textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(avis.color); setPadding(0, dp(7), 0, dp(6))
        })
        for (p in avis.points) avisBox.addView(TextView(this).apply {
            text = p; textSize = 13.5f; setTextColor(Ui.TEXT); setPadding(0, dp(3), 0, dp(3))
        })
        avisBox.addView(TextView(this).apply {
            text = "Lecture technique automatique — pas un conseil ni une prédiction."
            textSize = 11.5f; setTextColor(Ui.MUTED); setPadding(0, dp(8), 0, 0)
        })
    }

    override fun onResume() { super.onResume(); refreshPosition() }

    private fun select(range: String) {
        currentRange = range
        rangeButtons.forEach { (r, btn) ->
            val on = r == range
            btn.background = Ui.rounded(if (on) Ui.GOLD else Ui.SURFACE, dp(10).toFloat())
            btn.setTextColor(if (on) 0xFF0B0E14.toInt() else Ui.TEXT)
            btn.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        statsTv.text = "Chargement…"; explainTv.text = ""
        thread(isDaemon = true) {
            val s = MarketApi.fetchSeries(symbol, range)
            runOnUiThread { paint(s, range) }
        }
    }

    private fun paint(s: MarketApi.Series, range: String) {
        if (!s.ok || s.closes.size < 2) {
            statsTv.text = "Données indisponibles pour cette période."
            chart.set(emptyList(), true); return
        }
        val label = ranges.firstOrNull { it.second == range }?.third ?: "la période"
        val cur = currencySymbol(s.currency)
        val first = s.closes.first()
        val last = if (s.price > 0) s.price else s.closes.last()
        val pct = if (first > 0) (last - first) / first * 100 else 0.0
        val up = pct >= 0
        val hi = s.closes.max()
        val lo = s.closes.min()

        lastPrice = last; lastCurrency = s.currency
        thread(isDaemon = true) {
            val pe = MarketApi.toEur(last, s.currency)
            runOnUiThread { lastPriceEur = pe; refreshPosition() }
        }

        priceTv.text = fmt(last) + " " + cur
        periodTv.text = "Cours actuel"
        periodTv.setTextColor(Ui.MUTED)

        chart.set(s.closes, up)

        statsTv.text = "Sur $label : " + (if (up) "+" else "") + fmt(pct) + " %\n" +
            "Plus haut : " + fmt(hi) + " " + cur + "   ·   Plus bas : " + fmt(lo) + " " + cur
        statsTv.setTextColor(if (up) Ui.GREEN else Ui.RED)

        explainTv.text = if (up)
            "La courbe monte sur $label : la valeur s'est appréciée. Une tendance qui monte " +
                "régulièrement est plutôt bon signe — mais une hausse déjà forte peut aussi signifier " +
                "que c'est « cher » à court terme."
        else
            "La courbe baisse sur $label : la valeur a reculé. Ça peut être une opportunité d'achat " +
                "sur une bonne société… ou le signe d'un problème. Le graphique seul ne suffit pas : " +
                "c'est là que l'analyse (à venir) aide."
    }

    /** Met à jour l'encart position + la visibilité des boutons. */
    private fun refreshPosition() {
        if (!buyable) {
            buyBtn.text = "Un indice ne s'achète pas directement"
            buyBtn.background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
            buyBtn.setTextColor(Ui.MUTED)
            sellBtn.visibility = android.view.View.GONE
            positionTv.visibility = android.view.View.VISIBLE
            positionTv.text = "ℹ️  Un indice (CAC 40, S&P 500) mesure le marché mais ne s'achète pas " +
                "tel quel. On l'achète via un ETF qui le réplique (ex. « ETF Monde »)."
            return
        }
        val p = PortfolioStore.position(this, symbol)
        if (p == null || p.qty <= 0.0) {
            positionTv.visibility = android.view.View.GONE
            sellBtn.visibility = android.view.View.GONE
            return
        }
        positionTv.visibility = android.view.View.VISIBLE
        sellBtn.visibility = android.view.View.VISIBLE
        if (lastPriceEur > 0) {
            val value = p.qty * lastPriceEur
            val pnl = value - p.costEur
            val pnlPct = if (p.costEur > 0) pnl / p.costEur * 100 else 0.0
            val up = pnl >= 0
            positionTv.text = "TA POSITION\n" +
                "Valeur actuelle : ${PortfolioStore.money(value)}\n" +
                "Investi : ${PortfolioStore.money(p.costEur)}\n" +
                "Gain / perte : " + (if (up) "+" else "") + PortfolioStore.money(pnl) +
                "  (" + (if (up) "+" else "") + fmt(pnlPct) + " %)"
            positionTv.setTextColor(if (up) Ui.GREEN else Ui.RED)
        } else {
            positionTv.text = "TA POSITION\nInvesti : ${PortfolioStore.money(p.costEur)} (valeur en cours de calcul…)"
            positionTv.setTextColor(Ui.TEXT)
        }
    }

    private fun buyDialog() {
        if (lastPriceEur <= 0) { toast("Prix en cours de chargement, réessaie dans 1 s."); return }
        val cash = PortfolioStore.cash(this)
        val invested = PortfolioStore.positions(this).sumOf { it.costEur }
        val total = cash + invested   // capital total (approx.) pour la taille de position

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        box.addView(TextView(this).apply {
            text = "Prix : ${PortfolioStore.money(lastPriceEur)} / part\n" +
                "Liquidités fictives : ${PortfolioStore.money(cash)}"
            textSize = 14f; setTextColor(Ui.TEXT)
        })
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Montant en € (ex. 500)"
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
            setPadding(0, dp(12), 0, dp(4))
        }
        box.addView(input)
        val hint = TextView(this).apply {
            textSize = 13f; setTextColor(Ui.MUTED); setPadding(0, dp(8), 0, 0)
            text = "💡 Règle d'or : ne mets pas plus de 20 % de ton portefeuille sur une seule valeur."
        }
        box.addView(hint)

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val amt = s.toString().toDoubleOrNull() ?: 0.0
                if (amt <= 0) { hint.text = "💡 Ne mets pas plus de 20 % sur une seule valeur."; hint.setTextColor(Ui.MUTED); return }
                val qty = amt / lastPriceEur
                val pct = if (total > 0) amt / total * 100 else 0.0
                val stop = lastPriceEur * 0.92
                val txt = "≈ ${fmt(qty)} parts · ${fmt(pct)} % du portefeuille\n" +
                    "🛡️ Stop de protection conseillé : ${PortfolioStore.money(stop)} (−8 %)"
                hint.text = if (pct > 20) "⚠️ $txt\nC'est beaucoup sur une seule ligne (> 20 %)." else txt
                hint.setTextColor(if (pct > 20) Ui.GOLD else Ui.MUTED)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        AlertDialog.Builder(this)
            .setTitle("Acheter $name (fictif)")
            .setView(box)
            .setPositiveButton("Acheter") { _, _ ->
                val amt = input.text.toString().toDoubleOrNull() ?: 0.0
                val err = PortfolioStore.buy(this, symbol, name, lastCurrency, lastPriceEur, amt)
                if (err != null) toast(err)
                else { toast("Acheté pour ${PortfolioStore.money(amt)} (fictif) ✓"); refreshPosition() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun sellDialog() {
        val p = PortfolioStore.position(this, symbol) ?: return
        if (lastPriceEur <= 0) { toast("Prix en cours de chargement, réessaie dans 1 s."); return }
        val value = p.qty * lastPriceEur
        AlertDialog.Builder(this)
            .setTitle("Vendre $name (fictif)")
            .setMessage("Valeur actuelle de ta position : ${PortfolioStore.money(value)}.\n" +
                "Vendre te rend cette somme en liquidités fictives.")
            .setPositiveButton("Tout vendre") { _, _ ->
                val err = PortfolioStore.sell(this, symbol, lastPriceEur, 0.0, all = true)
                if (err != null) toast(err)
                else { toast("Position vendue (fictif) ✓"); refreshPosition() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun currencySymbol(c: String) = when (c.uppercase()) {
        "EUR" -> "€"; "USD" -> "$"; "GBP" -> "£"; else -> c
    }

    private fun fmt(v: Double): String {
        val digits = if (kotlin.math.abs(v) >= 1000) 0 else 2
        return String.format(Locale.FRANCE, "%,.${digits}f", v)
    }

    private fun mp(topMarginPx: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = topMarginPx }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
