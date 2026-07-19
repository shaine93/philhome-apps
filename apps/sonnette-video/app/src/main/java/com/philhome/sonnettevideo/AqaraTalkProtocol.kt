package com.philhome.sonnettevideo

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocole LAN talk-back Aqara (G400) — port fidèle de aqara_lan_talk.py / protocol.py.
 * Reverse-engineeré depuis l'app Aqara (com.lumi.module.rtsp).
 *
 * Canal de contrôle : TCP 54324 (paquets LmLocalPacket, MAGIC FE EF, CRC-16/X-25).
 * Canal audio        : UDP 54323 (RTP 12 octets + trame AAC-LC ADTS).
 * Format audio       : AAC-LC ADTS, 16 kHz, mono, 32 kbps.
 */
object AqaraTalkProtocol {

    val MAGIC = byteArrayOf(0xFE.toByte(), 0xEF.toByte())

    const val TYPE_START_VOICE = 0
    const val TYPE_STOP_VOICE = 1
    const val TYPE_ACK = 2
    const val TYPE_HEARTBEAT = 3

    const val RTP_PAYLOAD_TYPE = 97   // PT dynamique pour l'AAC
    const val SAMPLE_RATE = 16000
    const val SAMPLES_PER_AAC_FRAME = 1024

    /**
     * CRC-16/X-25 (appelé « KERMIT variant » dans le code Python) :
     * poly réfléchi 0x8408, init 0xFFFF, refin/refout, xorout 0xFFFF.
     * Implémentation bit-à-bit = équivalente à la table 256 entrées du Python.
     */
    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0xFFFF
        for (i in offset until offset + length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0x8408 else crc ushr 1
            }
        }
        return crc.inv() and 0xFFFF
    }

    private fun u16be(value: Int): ByteArray =
        byteArrayOf(((value ushr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    /**
     * Construit un LmLocalPacket pour le canal de contrôle.
     * ACK = payload 1 octet ; sinon = uint64 big-endian (timestamp epoch-ms de session).
     */
    fun buildPacket(type: Int, value: Long): ByteArray {
        val payload: ByteArray = if (type == TYPE_ACK) {
            byteArrayOf((value and 0xFF).toByte())
        } else {
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(value).array()
        }
        val header = MAGIC + byteArrayOf(type.toByte()) + u16be(payload.size)
        // CRC calculé sur header[2:] + payload (= type + len + payload)
        val crcInput = header.copyOfRange(2, header.size) + payload
        val crc = crc16(crcInput)
        return header + payload + u16be(crc)
    }

    /** Résultat de parsing d'un paquet de contrôle reçu. */
    data class Packet(val type: Int, val value: Long)

    /** Parse un paquet reçu (renvoie null si invalide / CRC faux). */
    fun parsePacket(data: ByteArray, length: Int = data.size): Packet? {
        if (length < 8) return null
        if (data[0] != MAGIC[0] || data[1] != MAGIC[1]) return null
        val type = data[2].toInt() and 0xFF
        if (type > 3) return null
        val payloadLen = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        if (length < 5 + payloadLen + 2) return null
        val expectedCrc = ((data[5 + payloadLen].toInt() and 0xFF) shl 8) or
            (data[6 + payloadLen].toInt() and 0xFF)
        if (crc16(data, 2, 3 + payloadLen) != expectedCrc) return null
        val value: Long = if (type == TYPE_ACK) {
            (data[5].toInt() and 0xFF).toLong()
        } else {
            var v = 0L
            for (i in 0 until payloadLen) v = (v shl 8) or (data[5 + i].toLong() and 0xFF)
            v
        }
        return Packet(type, value)
    }

    /** En-tête RTP minimal 12 octets (RFC 3550) : V=2, pas de marker/extension. */
    fun rtpHeader(payloadType: Int, timestamp: Long, ssrc: Long, seq: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).apply {
            put(0x80.toByte())
            put((payloadType and 0x7F).toByte())
            putShort((seq and 0xFFFF).toShort())
            putInt((timestamp and 0xFFFFFFFFL).toInt())
            putInt((ssrc and 0xFFFFFFFFL).toInt())
        }.array()

    /**
     * En-tête ADTS 7 octets pour une trame AAC-LC brute issue de MediaCodec.
     * AAC-LC (AOT 2 → profil ADTS 1), 16 kHz (index 8), mono (config 1), sans CRC.
     * @param aacPayloadLen taille de la trame AAC brute (hors en-tête ADTS).
     */
    fun adtsHeader(aacPayloadLen: Int): ByteArray {
        val profile = 1            // AAC-LC = AOT 2 → profil ADTS = AOT-1
        val freqIdx = 8            // 16000 Hz
        val chanCfg = 1            // mono
        val frameLen = aacPayloadLen + 7
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()                                              // syncword
        h[1] = 0xF1.toByte()                                              // sync + MPEG-4 + layer 0 + no CRC
        h[2] = (((profile and 0x3) shl 6) or ((freqIdx and 0xF) shl 2) or ((chanCfg shr 2) and 0x1)).toByte()
        h[3] = (((chanCfg and 0x3) shl 6) or ((frameLen shr 11) and 0x3)).toByte()
        h[4] = ((frameLen shr 3) and 0xFF).toByte()
        h[5] = (((frameLen and 0x7) shl 5) or 0x1F).toByte()             // + buffer fullness (haut)
        h[6] = 0xFC.toByte()                                             // buffer fullness (bas) + 0 raw blocks
        return h
    }
}