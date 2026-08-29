package com.philhome.sonnettevideo

import java.io.File
import java.io.RandomAccessFile

/**
 * Écrivain WAV minimal (PCM 16 bits mono) — pour capturer un échantillon réel (micro brut
 * et/ou signal filtré) pendant un test terrain, à analyser ensuite via `tools/dsp-bench` sans
 * deviner les réglages du filtre anti-vent. Diagnostic uniquement — voir [Prefs.audioCaptureEnabled].
 */
class WavWriter(file: File, private val sampleRate: Int) {
    private val raf = RandomAccessFile(file, "rw").apply {
        setLength(0)
        // En-tête WAV provisoire (44 octets) — patché avec les vraies tailles à close().
        write(ByteArray(44))
    }
    private var dataBytes = 0

    fun writeSamples(buf: ShortArray, n: Int) {
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = buf[i].toInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
    }

    fun close() {
        try {
            val byteRate = sampleRate * 2
            raf.seek(0)
            raf.write("RIFF".toByteArray())
            raf.writeIntLE(36 + dataBytes)
            raf.write("WAVE".toByteArray())
            raf.write("fmt ".toByteArray())
            raf.writeIntLE(16)                 // taille du sous-bloc fmt
            raf.writeShortLE(1)                // PCM
            raf.writeShortLE(1)                // mono
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(byteRate)
            raf.writeShortLE(2)                // block align (16 bits mono = 2 octets)
            raf.writeShortLE(16)               // bits par échantillon
            raf.write("data".toByteArray())
            raf.writeIntLE(dataBytes)
        } finally {
            raf.close()
        }
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }
}
