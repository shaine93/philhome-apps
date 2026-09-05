package com.philhome.sonnettevideo

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.File
import java.io.InputStream
import kotlin.concurrent.thread

/**
 * Envoie le message d'accueil enregistré ([GreetingRecorder]) à la sonnette, dès qu'elle sonne —
 * indépendamment de si quelqu'un décroche côté téléphone. Réutilise le même format audio
 * (AAC-LC 16 kHz mono via [DoorbellTalk.RelayTransport]) que le talk-back en direct, mais la
 * source PCM est le fichier enregistré au lieu du micro en temps réel.
 *
 * Toujours via le RELAIS HA (jamais direct LAN) : ça doit marcher immédiatement à la sonnerie,
 * qu'on soit sur le WiFi de la maison ou en 5G, sans attendre une sonde LAN.
 *
 * **Collision avec le talk-back (trouvée le 2026-09-04)** : la sonnette n'accepte qu'UNE session
 * voix à la fois, mais rien ne coordonnait [sendToDoorbell] et [DoorbellTalk] — si quelqu'un
 * décroche PENDANT l'envoi du message d'accueil (~5s, cas fréquent en usage réel), les deux
 * sessions s'ouvrent en parallèle sur le même canal et se corrompent mutuellement (confirmé par
 * les logs : la session talk-back obtient son ACK AVANT que la session accueil n'ait fini
 * d'envoyer). [cancel] doit être appelé dès le décroché, AVANT [DoorbellTalk.start], pour libérer
 * le canal proprement (STOP_VOICE) avant la nouvelle session.
 */
object GreetingSender {
    private const val TAG = "Greeting"
    private const val MIME = "audio/mp4a-latm"
    private const val CHUNK_SAMPLES = 1024
    // Cadence temps réel : la sonnette s'attend à recevoir l'audio au même rythme qu'un micro live.
    private const val CHUNK_MS = CHUNK_SAMPLES * 1000L / GreetingRecorder.SAMPLE_RATE

    @Volatile private var worker: Thread? = null

    /**
     * Interrompt un envoi en cours (best-effort, sans effet s'il n'y en a pas) et attend que le
     * transport soit refermé (STOP_VOICE envoyé) avant de rendre la main — pour garantir que le
     * canal voix de la sonnette est libre avant qu'un autre appelant (le talk-back) l'ouvre.
     */
    fun cancel() {
        val w = worker ?: return
        w.interrupt()
        try { w.join(500) } catch (_: InterruptedException) {}
    }

    /** Best-effort, ne bloque jamais l'appelant : à lancer depuis un thread ou un service. */
    fun sendToDoorbell(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (!GreetingRecorder.exists(appCtx)) return
        worker = thread(name = "greeting-send") {
            val transport = RelayTransport(DoorbellIp.current(appCtx))
            var encoder: MediaCodec? = null
            try {
                DebugLog.log(TAG, "envoi du message d'accueil à la sonnette…")
                transport.open()
                val enc = MediaCodec.createEncoderByType(MIME)
                val fmt = MediaFormat.createAudioFormat(MIME, GreetingRecorder.SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
                }
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                encoder = enc

                val info = MediaCodec.BufferInfo()
                var totalSamples = 0L
                var frames = 0

                File(appCtx.filesDir, "greeting.pcm").inputStream().use { input ->
                    val raw = ByteArray(CHUNK_SAMPLES * 2)
                    while (true) {
                        val read = readFully(input, raw)
                        if (read <= 0) break
                        val n = read / 2

                        val inIdx = enc.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val ib = enc.getInputBuffer(inIdx)!!
                            ib.clear(); ib.put(raw, 0, read)
                            enc.queueInputBuffer(inIdx, 0, read, totalSamples * 1_000_000L / GreetingRecorder.SAMPLE_RATE, 0)
                            totalSamples += n
                        }
                        var outIdx = enc.dequeueOutputBuffer(info, 0)
                        while (outIdx >= 0) {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val ob = enc.getOutputBuffer(outIdx)!!
                                val frame = ByteArray(7 + info.size)
                                System.arraycopy(AqaraTalkProtocol.adtsHeader(info.size), 0, frame, 0, 7)
                                ob.position(info.offset)
                                ob.get(frame, 7, info.size)
                                transport.sendAdtsFrame(frame)
                                frames++
                            }
                            enc.releaseOutputBuffer(outIdx, false)
                            outIdx = enc.dequeueOutputBuffer(info, 0)
                        }
                        // Pace en temps réel : le fichier se lit bien plus vite que ça ne s'écoute.
                        try { Thread.sleep(CHUNK_MS) } catch (_: InterruptedException) { break }
                    }
                }
                DebugLog.log(TAG, "message d'accueil envoyé ($frames trames)")
            } catch (e: Exception) {
                DebugLog.log(TAG, "envoi message d'accueil KO", e)
            } finally {
                try { encoder?.stop() } catch (_: Exception) {}
                try { encoder?.release() } catch (_: Exception) {}
                try { transport.close() } catch (_: Exception) {}
            }
        }
    }

    /** [InputStream.read] peut renvoyer moins que demandé ; on veut des chunks pleins. */
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
