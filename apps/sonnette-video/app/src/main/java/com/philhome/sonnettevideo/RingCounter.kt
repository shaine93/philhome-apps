package com.philhome.sonnettevideo

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Compteur LOCAL des sonneries **reçues par CE téléphone**, par jour (SharedPreferences).
 *
 * But : vérifier que le tél reçoit bien les appuis. On compare ce chiffre à la « vérité terrain »
 * de HA (`counter.sonnette_appuis_jour`, incrémenté à la détection de l'appui, INDÉPENDANT du token).
 * Un écart « détectés (HA) > reçus (ce tél) » = ce tél a **raté** une sonnerie → problème visible.
 *
 * Ce compteur-ci ne prouve donc PAS à lui seul que rien n'est raté (s'il ne reçoit rien il compte 0) ;
 * c'est le compteur HA qui est la référence robuste aux tokens. Ici = le « reçu réel » à confronter.
 */
object RingCounter {
    private const val FILE = "sonnette_ring_counter"
    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(d: Date = Date()) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

    /** Incrémente le compteur du jour (appelé à chaque `type=ring` reçu). */
    fun increment(ctx: Context) {
        val k = key()
        sp(ctx).edit().putInt(k, sp(ctx).getInt(k, 0) + 1).apply()
        prune(ctx)
    }

    fun today(ctx: Context): Int = sp(ctx).getInt(key(), 0)

    /** Derniers [days] jours (aujourd'hui d'abord) en (label lisible, compte). */
    fun history(ctx: Context, days: Int = 7): List<Pair<String, Int>> {
        val cal = Calendar.getInstance()
        val labelFmt = SimpleDateFormat("EEE d/MM", Locale.FRENCH)
        val out = ArrayList<Pair<String, Int>>(days)
        for (i in 0 until days) {
            out.add(labelFmt.format(cal.time) to sp(ctx).getInt(key(cal.time), 0))
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return out
    }

    /** Ne garde que ~14 jours pour éviter la croissance sans fin. */
    private fun prune(ctx: Context) {
        val keep = HashSet<String>()
        val cal = Calendar.getInstance()
        repeat(14) { keep.add(key(cal.time)); cal.add(Calendar.DAY_OF_MONTH, -1) }
        val e = sp(ctx).edit()
        for (k in sp(ctx).all.keys) if (k !in keep) e.remove(k)
        e.apply()
    }
}
