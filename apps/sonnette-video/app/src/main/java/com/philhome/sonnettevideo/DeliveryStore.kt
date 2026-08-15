package com.philhome.sonnettevideo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/**
 * Archive locale des alertes « livreur / rôdeur » (photo + description), pour la galerie 6 mois
 * consultable jour par jour DANS l'app — sans dépendre du serveur HA (autonome, survit au nettoyage
 * des fichiers HA). La photo est enregistrée au MOMENT du push (déjà téléchargée par
 * [DeliveryAlertNotifier]), donc l'historique est fidèle même si HA écrase/supprime son snapshot.
 *
 * Stockage : filesDir/gallery/  →  <epochMillis>.jpg + index.jsonl (1 ligne JSON par événement).
 * Rétention : 180 jours (~6 mois), purge automatique à chaque nouvel enregistrement.
 */
object DeliveryStore {

    private const val RETENTION_MS = 180L * 24 * 3600 * 1000   // ~6 mois
    private val lock = Any()

    // 2026-08-16 : dédup en mémoire (par call_id) pour les sonnettes "ring" — la photo peut être
    // récupérée par deux chemins différents (notif d'appel + aperçu live de l'écran d'appel) ; on ne
    // veut archiver que la première arrivée. Volontairement non persisté : survit uniquement le temps
    // du processus, ce qui suffit (un seul call_id ne revit jamais après un redémarrage de l'app).
    private val archivedCallIds = LinkedHashSet<String>()
    private const val ARCHIVED_IDS_CAP = 50

    data class Entry(val ts: Long, val title: String, val text: String, val file: File)

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "gallery").apply { mkdirs() }

    private fun index(ctx: Context): File = File(dir(ctx), "index.jsonl")

    /** Enregistre une alerte + sa photo. À appeler depuis un thread (I/O). */
    fun record(ctx: Context, title: String, text: String, photo: Bitmap) {
        synchronized(lock) {
            try {
                val ts = System.currentTimeMillis()
                val img = File(dir(ctx), "$ts.jpg")
                img.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                val line = JSONObject()
                    .put("ts", ts).put("title", title).put("text", text)
                    .put("file", img.name).toString()
                index(ctx).appendText(line + "\n")
                prune(ctx)
                DebugLog.log("Gallery", "alerte archivée ($ts) : $title")
            } catch (e: Exception) {
                DebugLog.log("Gallery", "record KO: ${e.message}")
            }
        }
    }

    /**
     * Comme [record], mais pour les sonnettes "ring" : n'archive qu'une fois par [callId], même si
     * appelé depuis plusieurs endroits (notif d'appel, aperçu live) pour le même événement. Si
     * [callId] est null (jamais censé arriver pour un vrai push, robustesse), archive quand même —
     * mieux vaut un doublon occasionnel qu'une sonnette silencieusement sans photo.
     */
    fun recordForCall(ctx: Context, callId: String?, title: String, text: String, photo: Bitmap) {
        if (callId != null) {
            synchronized(lock) {
                if (!archivedCallIds.add(callId)) return
                if (archivedCallIds.size > ARCHIVED_IDS_CAP) {
                    archivedCallIds.iterator().let { it.next(); it.remove() }
                }
            }
        }
        record(ctx, title, text, photo)
    }

    /** Toutes les entrées valides, les plus récentes d'abord. */
    fun entries(ctx: Context): List<Entry> {
        val f = index(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<Entry>()
        try {
            for (l in f.readLines()) {
                if (l.isBlank()) continue
                val o = JSONObject(l)
                val img = File(dir(ctx), o.getString("file"))
                if (img.exists())
                    out.add(Entry(o.getLong("ts"), o.optString("title"), o.optString("text"), img))
            }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.ts }
    }

    /** Décode une vignette (échantillonnée) pour l'affichage liste. Robuste + journalisé. */
    fun thumbnail(file: File, targetPx: Int = 220): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var s = 1
            while (bounds.outWidth > 0 && bounds.outWidth / s > targetPx * 2) s *= 2
            val bmp = BitmapFactory.decodeFile(file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = s })
                ?: BitmapFactory.decodeFile(file.absolutePath)   // repli : décodage plein
            DebugLog.log("Gallery",
                "thumb ${file.name}: ${if (bmp != null) "${bmp.width}x${bmp.height}" else "NULL (existe=${file.exists()} taille=${file.length()})"}")
            bmp
        } catch (e: Exception) {
            DebugLog.log("Gallery", "thumb ${file.name} EXCEPTION ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun prune(ctx: Context) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val f = index(ctx)
        if (!f.exists()) return
        val kept = ArrayList<String>()
        var changed = false
        for (l in f.readLines()) {
            if (l.isBlank()) continue
            try {
                val o = JSONObject(l)
                if (o.getLong("ts") < cutoff) {
                    File(dir(ctx), o.getString("file")).delete(); changed = true
                } else kept.add(l)
            } catch (_: Exception) { changed = true }
        }
        if (changed) f.writeText(kept.joinToString("\n").let { if (it.isEmpty()) "" else it + "\n" })
    }
}
