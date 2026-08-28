package com.philhome.sonnettevideo

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.concurrent.thread

/**
 * IP LAN actuelle de la sonnette — résolue depuis HA au lieu d'être figée en dur dans l'app.
 *
 * Pourquoi : la sonnette (Wi-Fi, bail DHCP) a déjà changé d'IP au moins une fois (.38 → .39) sans
 * que l'app ne le sache — panne silencieuse du talk-back (relais HA visait l'ancienne IP) tant que
 * personne ne rebuild/réinstalle. HA, lui, connaît la bonne IP (go2rtc.yaml + l'intégration Aqara),
 * donc HA devient la source de vérité unique : l'app la lit dans l'entité HA
 * `input_text.aqara_doorbell_ip` (à tenir à jour côté HA si l'IP change à nouveau — idéalement via
 * réservation DHCP pour que ça n'arrive plus).
 *
 * Lecture SYNCHRONE = dernière valeur connue en cache (jamais de blocage réseau sur le chemin
 * décrocher, priorité accessibilité). Rafraîchissement ASYNCHRONE en tâche de fond (déclenché à la
 * sonnerie + au heartbeat périodique) : ne change le cache QUE pour le PROCHAIN appel.
 */
object DoorbellIp {
    private const val TAG = "DoorbellIp"
    private const val FILE = "sonnette_prefs"
    private const val KEY = "doorbell_ip_cached"
    private const val ENTITY = "input_text.aqara_doorbell_ip"

    // Dernière IP confirmée manuellement (2026-08-28, port 8554 vérifié ouvert) — sert de secours
    // tant qu'aucun rafraîchissement HA n'a encore réussi (ex. tout premier lancement de l'app).
    private const val FALLBACK = "192.168.1.39"

    private val IPV4 = Pattern.compile("""^\d{1,3}(\.\d{1,3}){3}$""")

    @Volatile private var cached: String? = null

    /** IP à utiliser MAINTENANT (cache mémoire → SharedPreferences → repli codé en dur). */
    fun current(ctx: Context): String {
        cached?.let { return it }
        val stored = ctx.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
        val ip = if (stored != null && IPV4.matcher(stored).matches()) stored else FALLBACK
        cached = ip
        return ip
    }

    /**
     * Interroge HA en tâche de fond et met à jour le cache si l'IP a changé. Fire-and-forget :
     * un échec réseau (5G, HA down…) laisse simplement l'ancienne valeur en place.
     */
    fun refresh(ctx: Context) {
        val appCtx = ctx.applicationContext
        thread(name = "doorbell-ip-refresh", isDaemon = true) {
            try {
                val req = Request.Builder()
                    .url("${Config.HA_BASE_URL}/api/states/$ENTITY")
                    .addHeader("Authorization", "Bearer ${Config.HA_LONG_LIVED_TOKEN}")
                    .build()
                Net.base.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            DebugLog.log(TAG, "refresh HTTP ${resp.code} (garde IP en cache)")
                            return@use
                        }
                        val state = JSONObject(resp.body?.string().orEmpty()).optString("state")
                        if (!IPV4.matcher(state).matches()) {
                            DebugLog.log(TAG, "refresh: état HA invalide ('$state'), ignoré")
                            return@use
                        }
                        if (state != cached) {
                            appCtx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                                .edit().putString(KEY, state).apply()
                            DebugLog.log(TAG, "IP mise à jour: ${cached ?: "?"} → $state")
                            cached = state
                        }
                    }
            } catch (e: Exception) {
                DebugLog.log(TAG, "refresh échec (garde IP en cache): ${e.message}")
            }
        }
    }
}
