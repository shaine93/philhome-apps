package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.concurrent.thread

/**
 * Message d'accueil enregistré une fois par l'utilisateur ("Bonjour, ne bougez pas, nous allons
 * vous répondre…"), stocké en PCM brut 16 kHz mono (même format que [DoorbellTalk], donc envoyable
 * tel quel à la sonnette par [GreetingSender] sans conversion). Un seul message à la fois : un
 * nouvel enregistrement remplace le précédent.
 *
 * Stockage : filesDir/greeting.pcm — PAS de métadonnées séparées, la durée se déduit de la taille
 * du fichier (2 octets/échantillon × [SAMPLE_RATE] échantillons/s).
 */
object GreetingRecorder {
    const val SAMPLE_RATE = 16000
    private const val MAX_DURATION_MS = 15_000L   // message d'accueil court, pas une conversation
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun file(ctx: Context): File = File(ctx.filesDir, "greeting.pcm")

    fun exists(ctx: Context): Boolean = file(ctx).let { it.exists() && it.length() > 0 }

    /** Durée approximative du message enregistré, en secondes. */
    fun durationSeconds(ctx: Context): Double =
        if (!exists(ctx)) 0.0 else file(ctx).length() / 2.0 / SAMPLE_RATE

    fun delete(ctx: Context) {
        file(ctx).delete()
        DebugLog.log("Greeting", "message d'accueil supprimé")
    }

    @Volatile private var recording = false
    private var recordThread: Thread? = null

    /**
     * Enregistre jusqu'à [MAX_DURATION_MS] ou jusqu'à [stopRecording]. [onTick] reçoit la durée
     * écoulée (s) à intervalles réguliers pour l'affichage ; [onDone] est appelé à la fin
     * (succès ou erreur) sur le thread appelant [onTick]/[onDone] — toujours le thread principal.
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO assurée par PermissionsActivity/MainActivity
    fun startRecording(ctx: Context, onTick: (Double) -> Unit, onDone: (Boolean) -> Unit) {
        if (recording) return
        recording = true
        val appCtx = ctx.applicationContext
        recordThread = thread(name = "greeting-record") {
            var ok = false
            var rec: AudioRecord? = null
            var ns: NoiseSuppressor? = null
            var agc: AutomaticGainControl? = null
            var aec: AcousticEchoCanceler? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf.coerceAtLeast(2048)
                )
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("micro indisponible")
                }
                try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
                try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}
                try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } } catch (_: Exception) {}

                val buf = ShortArray(1024)
                val bytes = ByteArray(buf.size * 2)
                val tmp = File(appCtx.filesDir, "greeting.pcm.tmp")
                var totalSamples = 0L
                val maxSamples = MAX_DURATION_MS * SAMPLE_RATE / 1000

                tmp.outputStream().use { out ->
                    rec.startRecording()
                    DebugLog.log("Greeting", "enregistrement démarré")
                    var lastTickAt = 0L
                    while (recording && totalSamples < maxSamples) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        for (i in 0 until n) {
                            val v = buf[i].toInt()
                            bytes[2 * i] = (v and 0xFF).toByte()
                            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                        }
                        out.write(bytes, 0, n * 2)
                        totalSamples += n
                        val elapsedMs = totalSamples * 1000 / SAMPLE_RATE
                        if (elapsedMs - lastTickAt >= 200) {
                            lastTickAt = elapsedMs
                            val secs = elapsedMs / 1000.0
                            mainHandler.post { onTick(secs) }
                        }
                    }
                }
                tmp.copyTo(file(appCtx), overwrite = true)
                tmp.delete()
                ok = totalSamples > 0
                DebugLog.log("Greeting", "enregistrement terminé (${totalSamples * 1000 / SAMPLE_RATE / 1000.0}s)")
            } catch (e: Exception) {
                DebugLog.log("Greeting", "enregistrement KO", e)
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                try { rec?.release() } catch (_: Exception) {}
                try { ns?.release() } catch (_: Exception) {}
                try { agc?.release() } catch (_: Exception) {}
                try { aec?.release() } catch (_: Exception) {}
                recording = false
                mainHandler.post { onDone(ok) }
            }
        }
    }

    fun stopRecording() { recording = false }

    @Volatile private var playing = false

    /** Rejoue le message enregistré sur le haut-parleur du téléphone (relecture, pas envoi sonnette). */
    fun preview(ctx: Context, onDone: () -> Unit) {
        if (playing || !exists(ctx)) { onDone(); return }
        playing = true
        thread(name = "greeting-preview") {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf.coerceAtLeast(2048))
                    .build()
                track.play()
                file(ctx).inputStream().use { it.copyTo(TrackWriter(track)) }
            } catch (e: Exception) {
                DebugLog.log("Greeting", "preview KO", e)
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
                playing = false
                mainHandler.post { onDone() }
            }
        }
    }

    /** Petit adaptateur OutputStream → AudioTrack.write, pour réutiliser InputStream.copyTo. */
    private class TrackWriter(private val track: AudioTrack) : java.io.OutputStream() {
        override fun write(b: Int) { write(byteArrayOf(b.toByte())) }
        override fun write(b: ByteArray, off: Int, len: Int) { track.write(b, off, len) }
    }
}
