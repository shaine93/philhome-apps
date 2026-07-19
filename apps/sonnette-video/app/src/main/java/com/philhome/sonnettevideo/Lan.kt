package com.philhome.sonnettevideo

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Détection « la sonnette est-elle joignable en LAN ? » → décide du chemin DIRECT (RTSP + talk direct,
 * SANS Home Assistant) vs le chemin de secours HA (WebRTC/relais) quand on est en 5G / hors du LAN.
 *
 * Sonde = une connexion TCP courte sur le port de contrôle talk de la sonnette (54324). C'est le port
 * le plus spécifique (RTSP 8554 pourrait répondre via un proxy) et il prouve que le vrai protocole LAN
 * est atteignable. **À appeler hors du thread principal** (I/O réseau).
 */
object Lan {
    private const val PROBE_TIMEOUT_MS = 600

    /** true si la sonnette répond en direct sur le LAN (donc chemin sans-HA possible). */
    fun isDoorbellOnLan(
        host: String = Config.DOORBELL_IP,
        port: Int = Config.RTSP_CONTROL_PORT
    ): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    } catch (_: Exception) {
        false
    }
}
