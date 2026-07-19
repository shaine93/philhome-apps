package com.philhome.tradingclaudegod

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Vérifie périodiquement les valeurs DÉTENUES (portefeuille fictif) et envoie une notification si :
 *  - chute BRUTALE du jour (≤ −6 %), ou
 *  - la position passe sous un seuil de perte (≤ −10 % vs prix d'achat moyen).
 * Anti-spam : une même alerte n'est pas répétée avant 12 h.
 *
 * Note : en arrière-plan, la fréquence dépend d'Android (min ~15 min) et de la gestion d'énergie du
 * téléphone (MIUI peut retarder). Pour un usage simulation/apprentissage, c'est suffisant.
 */
class AlertWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val positions = PortfolioStore.positions(ctx)
        for (p in positions) {
            if (p.qty <= 0) continue
            val q = MarketApi.fetch(p.symbol)
            if (!q.ok) continue
            val priceEur = MarketApi.toEur(q.price, q.currency)
            val pnlPct = if (p.costEur > 0) (p.qty * priceEur - p.costEur) / p.costEur * 100 else 0.0
            val dayPct = q.changePct

            if (dayPct <= -6.0 && canAlert(ctx, p.symbol + "_day")) {
                notify(ctx, p.symbol.hashCode(),
                    "📉 ${p.name} chute aujourd'hui",
                    "${fmt(dayPct)} % sur la journée. Vérifie ta position (stop de protection ?).")
            } else if (pnlPct <= -10.0 && canAlert(ctx, p.symbol + "_pos")) {
                notify(ctx, p.symbol.hashCode() + 1,
                    "⚠️ ${p.name} : ta position perd du terrain",
                    "${fmt(pnlPct)} % depuis ton achat. Le seuil de protection (−8/−10 %) est franchi.")
            }
        }
        return Result.success()
    }

    private fun canAlert(ctx: Context, key: String): Boolean {
        val sp = ctx.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val last = sp.getLong(key, 0L)
        val now = System.currentTimeMillis()
        if (now - last < 12 * 3600_000L) return false
        sp.edit().putLong(key, now).apply()
        return true
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.FRANCE, "%.1f", v)

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            androidx.core.app.NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) { /* permission notif non accordée */ }
    }

    companion object {
        const val CHANNEL = "chute"
        fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Alertes de chute", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Prévient si une valeur détenue chute fortement" }
            )
        }
    }
}
