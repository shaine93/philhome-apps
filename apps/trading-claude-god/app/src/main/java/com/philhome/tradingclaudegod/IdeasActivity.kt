package com.philhome.tradingclaudegod

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Idées d'investissement par NIVEAU DE RISQUE (pédagogique, pas un conseil).
 * Pour chaque valeur : le « pourquoi » + la volatilité MESURÉE (risque objectif) + accès au détail.
 */
class IdeasActivity : Activity() {

    private val volTv = HashMap<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(24), dp(18), dp(26))
        }

        root.addView(TextView(this).apply {
            text = "‹  Retour"; textSize = 14f; setTextColor(Ui.MUTED)
            setPadding(0, 0, 0, dp(10)); setOnClickListener { finish() }
        })
        root.addView(TextView(this).apply {
            text = "💡  Idées d'investissement"
            textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Pour COMPRENDRE les niveaux de risque, avec des exemples. Ce ne sont pas des conseils " +
                "personnalisés. Le « % vol. » = la volatilité mesurée : plus il est haut, plus ça bouge (risque)."
            textSize = 12.5f; setTextColor(Ui.MUTED); setPadding(0, dp(6), 0, dp(4))
        })

        for (tier in Tier.values()) {
            val color = when (tier) {
                Tier.FAIBLE -> Ui.GREEN; Tier.MOYEN -> Ui.GOLD; Tier.ELEVE -> Ui.RED
            }
            root.addView(TextView(this).apply {
                text = tier.titre; textSize = 17f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(color); setPadding(dp(2), dp(24), 0, dp(6))
            })
            root.addView(TextView(this).apply {
                text = tier.intro; textSize = 13f; setTextColor(Ui.MUTED); setPadding(dp(2), 0, dp(2), dp(8))
            })
            for (idea in Ideas.byTier(tier)) root.addView(ideaCard(idea, color))
        }

        root.addView(TextView(this).apply {
            text = "⚠️  Outil éducatif de simulation. Aucune de ces valeurs n'est une recommandation " +
                "d'achat. Investir comporte un risque de perte, y compris sur les valeurs « à risque plus faible »."
            textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(2), dp(24), dp(2), 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) })
        loadVolatilities()
    }

    private fun ideaCard(idea: Idea, color: Int): ViewGroup {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = mp(dp(8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@IdeasActivity, AssetDetailActivity::class.java).apply {
                    putExtra("name", idea.name); putExtra("symbol", idea.symbol); putExtra("kind", idea.kind)
                })
            }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(TextView(this).apply {
            text = idea.name; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val vt = TextView(this).apply {
            text = "…"; textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color)
            background = Ui.rounded(Ui.BG, dp(8).toFloat()); setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        volTv[idea.symbol] = vt
        top.addView(vt)
        card.addView(top)
        card.addView(TextView(this).apply {
            text = idea.kind; textSize = 12f; setTextColor(Ui.MUTED); setPadding(0, dp(1), 0, dp(6))
        })
        card.addView(TextView(this).apply {
            text = idea.why; textSize = 13.5f; setTextColor(Ui.TEXT)
        })
        return card
    }

    private fun loadVolatilities() {
        thread(isDaemon = true) {
            for (idea in Ideas.ALL) {
                val s = MarketApi.fetchSeries(idea.symbol, "1y")
                val vol = if (s.ok) MarketApi.annualVolPct(s.closes) else null
                runOnUiThread {
                    volTv[idea.symbol]?.text = if (vol == null) "vol. ?"
                    else String.format(Locale.FRANCE, "%.0f %% vol.", vol) + "  " + label(vol)
                }
            }
        }
    }

    private fun label(vol: Double) = when {
        vol < 18 -> "faible"; vol < 35 -> "modérée"; vol < 70 -> "élevée"; else -> "très élevée"
    }

    private fun mp(topMarginPx: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = topMarginPx }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
