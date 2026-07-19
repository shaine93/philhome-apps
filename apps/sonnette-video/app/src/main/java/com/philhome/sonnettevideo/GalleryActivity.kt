package com.philhome.sonnettevideo

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Galerie « historique livreurs / rôdeurs » — 6 mois, DÉFILEMENT JOUR PAR JOUR.
 * Lit l'archive locale [DeliveryStore] (autonome, sans HA). Chaque jour = un en-tête + les
 * vignettes des alertes de ce jour ; tap sur une vignette = photo plein écran.
 */
class GalleryActivity : Activity() {

    private val dayFmt = SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH)
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.FRANCE)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val fullFmt = SimpleDateFormat("d MMMM yyyy 'à' HH:mm", Locale.FRENCH)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(18), dp(24), dp(18), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "📷  Historique livreurs"
            setTextColor(Ui.TEXT); textSize = 22f
            setPadding(0, 0, 0, dp(4))
        })

        val entries = DeliveryStore.entries(this)
        DebugLog.init(applicationContext)
        DebugLog.push("Gallery", "ouverte : ${entries.size} entrée(s) archivée(s)")
        root.addView(TextView(this).apply {
            text = if (entries.isEmpty()) "Aucune alerte archivée pour le moment."
                   else "${entries.size} alerte(s) — conservées 6 mois"
            setTextColor(Ui.MUTED); textSize = 13f
            setPadding(0, 0, 0, dp(16))
        })

        // Regroupe par jour (les entrées sont déjà triées du plus récent au plus ancien).
        var currentDay = ""
        var okThumbs = 0
        for (e in entries) {
            val key = dayKey.format(Date(e.ts))
            if (key != currentDay) {
                currentDay = key
                root.addView(dayHeader(dayFmt.format(Date(e.ts)).replaceFirstChar { it.uppercase() }))
            }
            val (row, ok) = entryRow(e)
            if (ok) okThumbs++
            root.addView(row)
        }
        if (entries.isNotEmpty())
            DebugLog.push("Gallery", "affichage : ${entries.size} lignes, $okThumbs vignettes OK")

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Ui.BG)
            addView(root)
        })
    }

    private fun dayHeader(label: String) = TextView(this).apply {
        text = label
        setTextColor(Ui.ACCENT); textSize = 15f
        setPadding(dp(2), dp(18), 0, dp(8))
    }

    private fun entryRow(e: DeliveryStore.Entry): Pair<View, Boolean> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(Ui.rounded(Ui.SURFACE, dp(14).toFloat()))
            setPadding(dp(10), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
        }

        val bmp = DeliveryStore.thumbnail(e.file)
        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(72))
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = Ui.rounded(Ui.BORDER, dp(8).toFloat())   // boîte visible même sans image
            if (bmp != null) setImageBitmap(bmp)
        }
        row.addView(thumb)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(TextView(this@GalleryActivity).apply {
            text = timeFmt.format(Date(e.ts)) + "  ·  " + e.title
            setTextColor(Ui.TEXT); textSize = 15f
        })
        col.addView(TextView(this@GalleryActivity).apply {
            text = e.text
            setTextColor(Ui.MUTED); textSize = 13f
            maxLines = 3
        })
        row.addView(col)

        row.setOnClickListener { showFull(e) }
        return Pair(row, bmp != null)
    }

    /** Tap → photo plein écran avec PINCER-POUR-ZOOMER (WebView) + bouton fermer. */
    private fun showFull(e: DeliveryStore.Entry) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val web = WebView(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            settings.allowFileAccess = true          // requis (API 30+ : accès fichier désactivé par défaut)
            settings.builtInZoomControls = true       // pincer-pour-zoomer
            settings.displayZoomControls = false      // sans les boutons +/- à l'écran
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true      // image ajustée à l'écran au départ
            loadUrl("file://${e.file.absolutePath}")
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 26f
            setPadding(dp(22), dp(16), dp(22), dp(16))
            setOnClickListener { dialog.dismiss() }
        }

        // Bouton ENVOYER (mail/autres) — la photo horodatée sert de preuve en cas de litige.
        val share = TextView(this).apply {
            text = "📧  Envoyer"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = Ui.ripple(Ui.rounded(Ui.GREEN, dp(14).toFloat()))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { shareEntry(e) }
        }

        val frame = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(web, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(close, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END))
            addView(share, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(28) })
        }
        dialog.setContentView(frame)
        dialog.show()
    }

    /** Partage la photo (mail, etc.) via FileProvider, avec la date/heure en légende (preuve litige). */
    private fun shareEntry(e: DeliveryStore.Entry) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", e.file)
            val quand = fullFmt.format(Date(e.ts))
            val legende = "Photo sonnette du $quand.\n${e.title}\n${e.text}"
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Photo sonnette — $quand")
                putExtra(Intent.EXTRA_TEXT, legende)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Envoyer la photo"))
        } catch (ex: Exception) {
            DebugLog.log("Gallery", "envoi KO: ${ex.javaClass.simpleName} ${ex.message}")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
