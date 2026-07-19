package com.philhome.sonnettevideo

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Réseau partagé de l'app, avec un **DNS robuste** pour la 5G :
 *  1. **cache** : une fois `duckdns` résolu, on réutilise l'IP (le DNS cellulaire est instable et
 *     peut échouer la seconde d'après — sans cache, talk-back et vidéo échouaient en alternance) ;
 *  2. **système d'abord** (rapide quand il marche) ;
 *  3. **DNS-over-HTTPS (Cloudflare) en secours** quand le système échoue.
 *
 * Résultat : rapide quand le DNS du tél marche, fiable sinon, et stable pendant tout l'appel
 * (la 1ʳᵉ résolution réussie sert ensuite à tout — talk-back, vidéo, FCM), quel que soit le DNS
 * configuré sur le téléphone (AdGuard, etc.).
 */
object Net {

    private const val CACHE_TTL_MS = 10 * 60 * 1000L   // 10 min

    private val doh: DnsOverHttps by lazy {
        DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())          // client d'amorçage
            .url("https://1.1.1.1/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1")
            )
            .build()
    }

    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    private val robustDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // 1) cache encore valide
            cache[hostname]?.let { (addrs, expiry) ->
                if (System.currentTimeMillis() < expiry) return addrs
            }
            // 2) système, 3) DoH en secours
            val result = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (e: Exception) {
                DebugLog.log("Net", "DNS système KO pour $hostname → bascule DoH")
                try {
                    doh.lookup(hostname)
                } catch (e2: Exception) {
                    // 4) dernier recours : un cache périmé vaut mieux qu'un échec
                    cache[hostname]?.let {
                        DebugLog.log("Net", "DoH KO aussi → réutilise cache périmé pour $hostname")
                        return it.first
                    }
                    throw e2
                }
            }
            cache[hostname] = result to (System.currentTimeMillis() + CACHE_TTL_MS)
            return result
        }
    }

    /** Client de base : DNS robuste + timeout de connexion. À dériver via newBuilder() selon l'usage. */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(robustDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Pré-chauffe la connexion vers HA (résout le DNS → cache, ouvre TLS → pool) pour que le
     * talk-back et la vidéo se connectent **vite** ensuite (réduit le délai de 1ʳᵉ parole).
     * À appeler tôt : ouverture de l'app, réception de la sonnerie, ouverture de l'écran d'appel.
     */
    fun prewarm() {
        thread(name = "net-prewarm") {
            try {
                val req = Request.Builder().url(Config.HA_BASE_URL).head().build()
                base.newCall(req).execute().use {
                    DebugLog.log("Net", "prewarm OK (HTTP ${it.code})")
                }
            } catch (e: Exception) {
                DebugLog.log("Net", "prewarm: ${e.message}")
            }
        }
    }
}