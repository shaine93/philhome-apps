package com.philhome.tradingclaudegod

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Valeurs ajoutées MANUELLEMENT par l'utilisateur (en plus de la sélection par défaut).
 * Persistées dans filesDir/watchlist.json.
 */
object WatchlistStore {

    private fun file(ctx: Context) = File(ctx.filesDir, "watchlist.json")

    fun custom(ctx: Context): List<Asset> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Asset(o.getString("name"), o.getString("symbol"), o.optString("kind", "Valeur"))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Liste complète affichée = valeurs par défaut + ajouts perso (sans doublon de symbole). */
    fun all(ctx: Context): List<Asset> {
        val seen = HashSet<String>()
        val out = ArrayList<Asset>()
        for (a in Assets.DEFAULT + custom(ctx)) if (seen.add(a.symbol)) out.add(a)
        return out
    }

    fun isCustom(ctx: Context, symbol: String) = custom(ctx).any { it.symbol == symbol }

    fun add(ctx: Context, asset: Asset) {
        if (Assets.DEFAULT.any { it.symbol == asset.symbol }) return
        val list = custom(ctx).toMutableList()
        if (list.any { it.symbol == asset.symbol }) return
        list.add(asset)
        save(ctx, list)
    }

    fun remove(ctx: Context, symbol: String) {
        save(ctx, custom(ctx).filter { it.symbol != symbol })
    }

    private fun save(ctx: Context, list: List<Asset>) {
        try {
            val arr = JSONArray()
            for (a in list) arr.put(JSONObject()
                .put("name", a.name).put("symbol", a.symbol).put("kind", a.kind))
            file(ctx).writeText(arr.toString())
        } catch (_: Exception) { }
    }
}
