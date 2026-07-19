package com.philhome.tradingclaudegod

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Écran principal — portefeuille virtuel + liste de valeurs avec cours EN DIRECT.
 * Les prix sont réels (Yahoo Finance) ; l'argent, lui, reste FICTIF (simulation).
 * Tout est expliqué en langage débutant. Aucun conseil financier, aucun ordre réel.
 */
class MainActivity : Activity() {

    private class Row(
        val price: TextView, val change: TextView, val trend: TextView,
        val wm: TextView, val spark: SparklineView
    )
    private val rows = HashMap<String, Row>()
    private lateinit var refreshBtn: TextView
    private lateinit var portfolioValueTv: TextView
    private lateinit var portfolioSubTv: TextView
    private lateinit var positionsBox: LinearLayout
    private lateinit var assetsBox: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private val lastPrices = HashMap<String, Double>()   // symbol -> prix en €

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(26), dp(18), dp(26))
        }

        // En-tête
        root.addView(TextView(this).apply {
            text = "📈  Trading Claude GOD"
            textSize = 25f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
        })
        root.addView(TextView(this).apply {
            text = "Simulateur — tu apprends sans risquer un centime"
            textSize = 13.5f; setTextColor(Ui.MUTED); setPadding(0, dp(3), 0, 0)
        })

        // Carte portefeuille virtuel (dynamique)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(16).toFloat(), dp(1))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = mp(dp(22))
            addView(TextView(this@MainActivity).apply {
                text = "PORTEFEUILLE VIRTUEL"
                textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
            })
            portfolioValueTv = TextView(this@MainActivity).apply {
                text = "…"; textSize = 30f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
                setPadding(0, dp(4), 0, 0)
            }
            addView(portfolioValueTv)
            portfolioSubTv = TextView(this@MainActivity).apply {
                text = "Argent fictif · rien de réel n'est engagé"
                textSize = 12.5f; setTextColor(Ui.MUTED); setPadding(0, dp(2), 0, 0)
            }
            addView(portfolioSubTv)
        })

        // Positions détenues (rempli dynamiquement)
        positionsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = android.view.View.GONE
        }
        root.addView(positionsBox)

        // Bouton IDÉES D'INVESTISSEMENT (par niveau de risque)
        root.addView(TextView(this).apply {
            text = "💡  Idées d'investissement (par risque)"
            textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(0xFF0B0E14.toInt())
            background = Ui.rounded(Ui.GOLD, dp(14).toFloat())
            layoutParams = mp(dp(20)).apply { height = dp(56) }
            setOnClickListener { startActivity(Intent(this@MainActivity, IdeasActivity::class.java)) }
        })

        // Titre liste + bouton actualiser
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = mp(dp(26))
            addView(TextView(this@MainActivity).apply {
                text = "MES VALEURS"
                textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            refreshBtn = TextView(this@MainActivity).apply {
                text = "🔄  Actualiser"
                textSize = 13f; setTextColor(Ui.TEXT)
                background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener { loadQuotes() }
            }
            addView(refreshBtn)
        })
        root.addView(TextView(this).apply {
            text = "Prix réels, en direct. La couleur dit la variation du jour ; la ligne du bas, " +
                "la tendance de fond."
            textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(2), dp(6), dp(2), dp(6))
        })

        // Conteneur des cartes (rempli dynamiquement : défaut + ajouts perso)
        assetsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(assetsBox)

        // Bouton AJOUTER une valeur
        root.addView(TextView(this).apply {
            text = "➕  Ajouter une valeur"
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT)
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(12).toFloat(), dp(1))
            layoutParams = mp(dp(12)).apply { height = dp(50) }
            setOnClickListener { addDialog() }
        })

        // Section APPLICATION (mise à jour OTA)
        root.addView(TextView(this).apply {
            text = "APPLICATION"; textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
            setPadding(dp(2), dp(24), 0, dp(8))
        })
        val updateStatus = TextView(this).apply {
            text = "v" + AppUpdater.currentName(this@MainActivity)
            textSize = 13f; setTextColor(Ui.MUTED); setPadding(dp(2), 0, 0, dp(6))
        }
        root.addView(TextView(this).apply {
            text = "⬇️  Mettre à jour l'app"
            textSize = 15f; gravity = Gravity.CENTER; setTextColor(Ui.TEXT)
            background = Ui.stroked(Ui.SURFACE, Ui.BORDER, dp(12).toFloat(), dp(1))
            layoutParams = mp(dp(4)).apply { height = dp(50) }
            setOnClickListener { AppUpdater.checkAndUpdate(this@MainActivity) { s -> updateStatus.text = s } }
        })
        root.addView(updateStatus)

        // Note alertes
        root.addView(TextView(this).apply {
            text = "🔔  Alertes de chute actives : tu seras prévenu par notification si une de tes valeurs " +
                "détenues chute fortement (−6 % dans la journée) ou passe sous ton seuil de protection."
            textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(2), dp(22), dp(2), 0)
        })

        // Avertissement
        root.addView(TextView(this).apply {
            text = "⚠️  Outil éducatif de simulation. Ce n'est pas un conseil financier. " +
                "Investir comporte un risque de perte."
            textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(2), dp(14), dp(2), 0)
        })

        val scroll = ScrollView(this).apply { setBackgroundColor(Ui.BG); addView(root) }
        swipe = SwipeRefreshLayout(this).apply {
            addView(scroll)
            setColorSchemeColors(Ui.GREEN, Ui.GOLD)
            setProgressBackgroundColorSchemeColor(Ui.SURFACE)
            setOnRefreshListener { loadQuotes() }
        }
        setContentView(swipe)
        buildAssetList()
        loadQuotes()
        requestNotifPermission()
        scheduleAlerts()
    }

    /** Programme la surveillance périodique des positions (alertes de chute). */
    private fun scheduleAlerts() {
        AlertWorker.ensureChannel(this)
        val work = PeriodicWorkRequestBuilder<AlertWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("chute", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    /** (Re)construit les cartes de la liste = valeurs par défaut + ajouts perso. */
    private fun buildAssetList() {
        assetsBox.removeAllViews()
        rows.clear()
        for (a in WatchlistStore.all(this)) assetsBox.addView(assetCard(a))
    }

    /** Recherche + ajout d'une valeur par son nom (ex. « Renault », « Nvidia »). */
    private fun addDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        val input = EditText(this).apply {
            hint = "Nom ou symbole (ex. Renault, Danone…)"
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED)
        }
        box.addView(input)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(results)
        val info = TextView(this).apply {
            text = "Tape un nom puis « Chercher »."; textSize = 12.5f; setTextColor(Ui.MUTED)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(info)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Ajouter une valeur")
            .setView(box)
            .setNegativeButton("Fermer", null)
            .setPositiveButton("Chercher", null)   // on gère le clic nous-mêmes (pour ne pas fermer)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return@setOnClickListener
            info.text = "Recherche…"; results.removeAllViews()
            thread(isDaemon = true) {
                val found = MarketApi.search(q)
                runOnUiThread {
                    info.text = if (found.isEmpty()) "Aucun résultat." else "Choisis une valeur :"
                    results.removeAllViews()
                    for (f in found.take(6)) results.addView(TextView(this).apply {
                        text = "${f.name}\n${f.symbol} · ${f.kind}"
                        textSize = 14f; setTextColor(Ui.TEXT)
                        background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = mp(dp(6))
                        setOnClickListener {
                            WatchlistStore.add(this@MainActivity, Asset(f.name, f.symbol, f.kind))
                            Toast.makeText(this@MainActivity, "${f.name} ajoutée ✓", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            buildAssetList(); loadQuotes()
                        }
                    })
                }
            }
        }
    }

    private fun assetCard(a: Asset): ViewGroup {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
            setPadding(dp(15), dp(13), dp(15), dp(13))
            layoutParams = mp(dp(9))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, AssetDetailActivity::class.java).apply {
                    putExtra("name", a.name); putExtra("symbol", a.symbol); putExtra("kind", a.kind)
                })
            }
            // Appui long : retirer une valeur ajoutée manuellement (les valeurs par défaut restent).
            setOnLongClickListener {
                if (WatchlistStore.isCustom(this@MainActivity, a.symbol)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Retirer ${a.name} ?")
                        .setMessage("Elle disparaîtra de ta liste (ça ne touche pas au portefeuille).")
                        .setPositiveButton("Retirer") { _, _ ->
                            WatchlistStore.remove(this@MainActivity, a.symbol)
                            buildAssetList(); loadQuotes()
                        }
                        .setNegativeButton("Annuler", null)
                        .show()
                    true
                } else {
                    Toast.makeText(this@MainActivity, "Valeur par défaut (non supprimable)", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
        // Ligne haut : nom + catégorie (gauche) | prix + variation (droite)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = a.name; textSize = 16.5f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT)
            })
            addView(TextView(this@MainActivity).apply {
                text = a.kind; textSize = 12f; setTextColor(Ui.MUTED)
            })
        }
        val priceTv = TextView(this).apply {
            text = "…"; textSize = 16.5f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT); gravity = Gravity.END
        }
        val changeTv = TextView(this).apply {
            text = ""; textSize = 13f; setTextColor(Ui.MUTED); gravity = Gravity.END
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(priceTv); addView(changeTv)
        }
        top.addView(left); top.addView(right)
        card.addView(top)

        val trendTv = TextView(this).apply {
            text = "chargement…"; textSize = 13f; setTextColor(Ui.MUTED)
            setPadding(0, dp(9), 0, 0)
        }
        card.addView(trendTv)

        // Ligne bas : variation 7j/30j (gauche) + mini-graphique du mois (droite)
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        val wmTv = TextView(this).apply {
            text = ""; textSize = 12.5f; setTextColor(Ui.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val spark = SparklineView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(30))
        }
        bottom.addView(wmTv); bottom.addView(spark)
        card.addView(bottom)

        rows[a.symbol] = Row(priceTv, changeTv, trendTv, wmTv, spark)
        return card
    }

    override fun onResume() { super.onResume(); updatePortfolio() }

    private fun loadQuotes() {
        refreshBtn.text = "⏳  ..."
        val list = WatchlistStore.all(this)
        thread(isDaemon = true) {
            for (a in list) {
                val q = MarketApi.fetch(a.symbol)
                if (q.ok) lastPrices[a.symbol] = MarketApi.toEur(q.price, q.currency)  // fond (réseau OK)
                runOnUiThread { paint(a, q); updatePortfolio() }
            }
            runOnUiThread { refreshBtn.text = "🔄  Actualiser"; swipe.isRefreshing = false }
        }
    }

    /** Recalcule la valeur totale (liquidités + positions) et affiche le gain/perte + les positions. */
    private fun updatePortfolio() {
        val cash = PortfolioStore.cash(this)
        val positions = PortfolioStore.positions(this)
        var posValue = 0.0
        for (p in positions) {
            val pe = lastPrices[p.symbol]
            posValue += if (pe != null && pe > 0) p.qty * pe else p.costEur  // repli : coût si prix inconnu
        }
        val total = cash + posValue
        val pnl = total - PortfolioStore.START_CASH
        val up = pnl >= 0

        portfolioValueTv.text = PortfolioStore.money(total)
        portfolioValueTv.setTextColor(Ui.TEXT)
        portfolioSubTv.text = "Liquidités ${PortfolioStore.money(cash)}  ·  " +
            (if (up) "+" else "") + PortfolioStore.money(pnl) + " depuis le départ (fictif)"
        portfolioSubTv.setTextColor(if (pnl == 0.0) Ui.MUTED else if (up) Ui.GREEN else Ui.RED)

        // Liste des positions
        positionsBox.removeAllViews()
        if (positions.isEmpty()) { positionsBox.visibility = android.view.View.GONE; return }
        positionsBox.visibility = android.view.View.VISIBLE
        positionsBox.addView(TextView(this).apply {
            text = "MES POSITIONS"; textSize = 11.5f; setTextColor(Ui.MUTED); letterSpacing = 0.08f
            setPadding(dp(2), dp(20), 0, dp(8))
        })
        for (p in positions) {
            val pe = lastPrices[p.symbol]
            val value = if (pe != null && pe > 0) p.qty * pe else p.costEur
            val gpnl = value - p.costEur
            val pos = gpnl >= 0
            positionsBox.addView(TextView(this).apply {
                text = "${p.name}\n${PortfolioStore.money(value)}   " +
                    "(" + (if (pos) "+" else "") + PortfolioStore.money(gpnl) + ")"
                textSize = 14.5f; setTextColor(if (pos) Ui.GREEN else Ui.RED)
                background = Ui.rounded(Ui.SURFACE, dp(12).toFloat())
                setPadding(dp(14), dp(11), dp(14), dp(11))
                layoutParams = mp(dp(7))
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, AssetDetailActivity::class.java).apply {
                        putExtra("name", p.name); putExtra("symbol", p.symbol); putExtra("kind", "")
                    })
                }
            })
        }
    }

    private fun paint(a: Asset, q: MarketApi.Quote) {
        val r = rows[a.symbol] ?: return
        if (!q.ok) {
            r.price.text = "indispo"; r.price.setTextColor(Ui.MUTED)
            r.change.text = ""
            r.trend.text = "cours momentanément indisponible"; r.trend.setTextColor(Ui.MUTED)
            r.wm.text = ""; r.spark.set(emptyList(), true)
            return
        }
        val sym = currencySymbol(q.currency)
        r.price.text = fmt(q.price) + " " + sym
        r.price.setTextColor(Ui.TEXT)

        val up = q.changePct >= 0
        r.change.text = (if (up) "▲ +" else "▼ ") + fmt(q.changePct) + " %"
        r.change.setTextColor(if (up) Ui.GREEN else Ui.RED)

        when (q.trend) {
            MarketApi.Trend.HAUSSE -> {
                r.trend.text = "📈  Tendance haussière de fond"; r.trend.setTextColor(Ui.GREEN)
            }
            MarketApi.Trend.BAISSE -> {
                r.trend.text = "📉  Tendance baissière"; r.trend.setTextColor(Ui.RED)
            }
            MarketApi.Trend.NEUTRE -> {
                r.trend.text = "➖  Tendance neutre / indécise"; r.trend.setTextColor(Ui.GOLD)
            }
            MarketApi.Trend.INCONNU -> {
                r.trend.text = "—  historique insuffisant"; r.trend.setTextColor(Ui.MUTED)
            }
        }

        // Historique 7j / 30j + mini-graphique
        r.wm.text = "7j " + signed(q.weekPct) + "   ·   30j " + signed(q.monthPct)
        val monthUp = (q.monthPct ?: 0.0) >= 0
        r.wm.setTextColor(if (q.monthPct == null) Ui.MUTED else if (monthUp) Ui.GREEN else Ui.RED)
        r.spark.set(q.history, monthUp)
    }

    /** "+2,3 %" / "-1,4 %" / "—" si inconnu. */
    private fun signed(v: Double?): String {
        if (v == null) return "—"
        return (if (v >= 0) "+" else "") + fmt(v) + " %"
    }

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
