package com.philhome.sonnettevideo

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Talk-back vers la sonnette Aqara : capture micro 16 kHz → filtre voix ([VoiceFilter])
 * + effets natifs (NS/AGC/AEC) → AAC-LC 16 kHz mono 32 k (MediaCodec) → trame ADTS.
 *
 * Deux transports (la capture/encodage est identique) :
 *  - **RELAIS** ([RelayTransport], défaut) : envoie les trames AAC à Home Assistant en WebSocket
 *    HTTPS ; HA les pousse à la sonnette. **Marche en 5G ET en WiFi** (connexion sortante, pas de TURN).
 *  - **DIRECT** ([DirectTransport]) : envoie le RTP directement à la sonnette sur le LAN. **WiFi only.**
 *
 * @param host IP LAN de la sonnette (utilisée telle quelle en direct, et passée à HA en relais).
 * @param useRelay true = relais HA (défaut, pour la 5G) ; false = direct LAN.
 * @param captureDir si non-null (voir [Prefs.audioCaptureEnabled]), enregistre le micro BRUT et
 *   le signal FILTRÉ dans deux .wav de ce dossier — pour mesurer le filtre anti-vent sur un
 *   vrai test terrain au lieu de deviner les réglages. Diagnostic uniquement.
 */
class DoorbellTalk(
    private val host: String,
    private val useRelay: Boolean = true,
    private val sensitivity: Double = 1.0,
    private val captureDir: java.io.File? = null
) {
    companion object {
        private const val TAG = "DoorbellTalk"
        private const val SR = AqaraTalkProtocol.SAMPLE_RATE   // 16000
        private const val MIME = "audio/mp4a-latm"
        // Nb de trames envoyées avec SUCCÈS d'affilée avant de considérer le pipe « chaud » et
        // d'annoncer onState("ready") — 7 trames ≈ 450 ms à 16 kHz/1024 échantillons/trame.
        // Choisi pour éviter le symptôme vécu : indicateur vert trop tôt → 1er mot ("bonjour") avalé
        // pendant que la connexion HA↔sonnette finit de s'établir.
        private const val READY_FRAMES = 7
    }

    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var encoder: MediaCodec? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null
    private var transport: TalkTransport? = null
    private var worker: Thread? = null

    /**
     * [onState] : "connecting" / "active" / "ready" (trames confirmées, sûr de parler) /
     * "stopped" / "error: …". [onGain] : gain lissé (0..1) de [VoiceFilter], ~1x par tampon
     * (~64 ms) — réutilisable pour un ducking anti-Larsen du son entrant pendant qu'on parle.
     */
    fun start(onState: (String) -> Unit = {}, onGain: (Double) -> Unit = {}) {
        if (running) return
        running = true
        worker = thread(name = "doorbell-talk") {
            val mode = if (useRelay) "RELAIS HA" else "DIRECT LAN"
            DebugLog.log(TAG, "start() host=$host mode=$mode")
            val tr: TalkTransport = if (useRelay) RelayTransport(host) else DirectTransport(host)
            transport = tr
            try {
                onState("connecting")
                tr.open()
                DebugLog.log(TAG, "transport ouvert ($mode)")
                captureLoop(tr, onState, onGain)
            } catch (e: Exception) {
                DebugLog.log(TAG, "ERREUR talk", e)
                onState("error: ${e.message}")
            } finally {
                cleanup()
                DebugLog.log(TAG, "stopped")
                onState("stopped")
            }
        }
    }

    fun stop() {
        running = false
        try { worker?.interrupt() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO assurée par PermissionsActivity
    private fun captureLoop(tr: TalkTransport, onState: (String) -> Unit, onGain: (Double) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,   // NS/AGC/AEC matériels + anti-larsen
            SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(2048)                       // buffer mini = latence de capture mini
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("micro indisponible (permission RECORD_AUDIO ?)")
        }
        record = rec
        enableEffects(rec.audioSessionId)

        val enc = MediaCodec.createEncoderByType(MIME)
        val fmt = MediaFormat.createAudioFormat(MIME, SR, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            // Demande à l'encodeur le mode basse latence (honoré si l'appareil le supporte).
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                setInteger(MediaFormat.KEY_LATENCY, 1)
            }
        }
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        encoder = enc

        val filter = VoiceFilter(SR, sensitivity)
        val pcm = ShortArray(1024)
        val pcmBytes = ByteArray(pcm.size * 2)
        val info = MediaCodec.BufferInfo()
        var totalSamples = 0L

        // Best-effort : la capture diagnostic ne doit JAMAIS empêcher un vrai talk-back (ex.
        // stockage externe indisponible) — toute erreur ici désactive juste la capture.
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        var rawWriter: WavWriter? = null
        var filteredWriter: WavWriter? = null
        // DIAGNOSTIC (2026-09-03) : copie exacte des trames ADTS envoyées sur le réseau, pour
        // pouvoir les rejouer sur un ordinateur (ffplay/VLC) et vérifier si MediaCodec produit un
        // flux AAC valide — le protocole/relais/sonnette sont déjà prouvés fonctionnels avec un
        // fichier AAC pré-encodé (ffmpeg), seule cette chaîne temps réel reste à isoler.
        var sentAacFile: java.io.FileOutputStream? = null
        if (captureDir != null) {
            try {
                rawWriter = WavWriter(java.io.File(captureDir, "capture-$stamp-brut.wav"), SR)
                filteredWriter = WavWriter(java.io.File(captureDir, "capture-$stamp-filtre.wav"), SR)
                sentAacFile = java.io.FileOutputStream(java.io.File(captureDir, "capture-$stamp-envoye.aac"))
                DebugLog.log(TAG, "capture debug ACTIVE → capture-$stamp-{brut,filtre,envoye.aac}")
            } catch (e: Exception) {
                DebugLog.log(TAG, "capture debug indisponible (ignorée, talk-back continue)", e)
                try { rawWriter?.close() } catch (_: Exception) {}
                try { sentAacFile?.close() } catch (_: Exception) {}
                rawWriter = null; filteredWriter = null; sentAacFile = null
            }
        }

        rec.startRecording()
        DebugLog.log(TAG, "capture micro active (16 kHz mono) — envoi de l'AAC en cours")
        onState("active")
        var sent = 0L
        var consecutiveOk = 0
        var readyNotified = false

        try {
            while (running) {
                val n = rec.read(pcm, 0, pcm.size)
                if (n <= 0) continue

                // AVANT filtre — signal micro brut. Best-effort : un échec d'écriture EN COURS DE
                // SESSION (stockage plein, etc.) désactive juste la capture, ne doit jamais couper
                // le talk-back réel (trouvé en review Codex après le premier passage best-effort
                // qui ne couvrait que l'ouverture des fichiers, pas les écritures elles-mêmes).
                if (rawWriter != null) {
                    try { rawWriter.writeSamples(pcm, n) } catch (e: Exception) {
                        DebugLog.log(TAG, "capture brut: écriture échouée, capture désactivée", e)
                        try { rawWriter.close() } catch (_: Exception) {}
                        rawWriter = null
                    }
                }

                filter.process(pcm, n, onGain)   // « uniquement la voix »

                if (filteredWriter != null) {   // APRÈS filtre — ce qui part réellement
                    try { filteredWriter.writeSamples(pcm, n) } catch (e: Exception) {
                        DebugLog.log(TAG, "capture filtrée: écriture échouée, capture désactivée", e)
                        try { filteredWriter.close() } catch (_: Exception) {}
                        filteredWriter = null
                    }
                }

                for (i in 0 until n) {
                    val v = pcm[i].toInt()
                    pcmBytes[2 * i] = (v and 0xFF).toByte()
                    pcmBytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                }

                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear(); ib.put(pcmBytes, 0, n * 2)
                    enc.queueInputBuffer(inIdx, 0, n * 2, totalSamples * 1_000_000L / SR, 0)
                    totalSamples += n
                }

                var outIdx = enc.dequeueOutputBuffer(info, 0)
                while (outIdx != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        // DIAGNOSTIC : format réel produit par l'encodeur (csd-0 = AudioSpecificConfig).
                        val of = enc.outputFormat
                        val csd0 = of.getByteBuffer("csd-0")
                        val csd0Hex = csd0?.let {
                            val a = ByteArray(it.remaining()); it.duplicate().get(a)
                            a.joinToString(" ") { b -> "%02X".format(b) }
                        } ?: "absent"
                        DebugLog.log(TAG, "encodeur: outputFormat=$of csd-0=$csd0Hex")
                    } else if (outIdx >= 0) {
                        if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val ob = enc.getOutputBuffer(outIdx)!!
                            // Trame ADTS complète = en-tête ADTS 7 o + AAC brut (ce que la sonnette attend).
                            val frame = ByteArray(7 + info.size)
                            System.arraycopy(AqaraTalkProtocol.adtsHeader(info.size), 0, frame, 0, 7)
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            ob.get(frame, 7, info.size)
                            val ok = tr.sendAdtsFrame(frame)
                            if (sentAacFile != null) {
                                try { sentAacFile.write(frame) } catch (e: Exception) {
                                    DebugLog.log(TAG, "dump AAC envoyé: écriture échouée, désactivé", e)
                                    try { sentAacFile.close() } catch (_: Exception) {}
                                    sentAacFile = null
                                }
                            }
                            if (++sent % 100L == 0L) DebugLog.log(TAG, "$sent trames AAC envoyées")
                            // « ready » = confirmation RÉELLE que les trames partent en continu, pas un
                            // minuteur à l'aveugle — évite le mot avalé pendant que la connexion se stabilise.
                            if (ok) consecutiveOk++ else consecutiveOk = 0
                            if (!readyNotified && consecutiveOk >= READY_FRAMES) {
                                readyNotified = true
                                DebugLog.log(TAG, "pipe confirmé chaud ($READY_FRAMES trames d'affilée) → ready")
                                onState("ready")
                            }
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                    }
                    outIdx = enc.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            rawWriter?.close()
            filteredWriter?.close()
            sentAacFile?.close()
            if (captureDir != null) DebugLog.log(TAG, "capture debug terminée: capture-$stamp-{brut,filtre,envoye.aac}")
        }
    }

    private fun enableEffects(sessionId: Int) {
        try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
        try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
        DebugLog.log(TAG, "effets micro natifs: NS=${ns != null} AGC=${agc != null} AEC=${aec != null}")
    }

    private fun cleanup() {
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { agc?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        try { transport?.close() } catch (_: Exception) {}
        record = null; encoder = null; ns = null; agc = null; aec = null; transport = null
        DebugLog.log(TAG, "ressources libérées")
    }
}

/* ===================================================================================== */

/** Sortie d'une trame AAC-LC ADTS vers la sonnette. */
interface TalkTransport {
    fun open()
    /** @return true si la trame a été acceptée par le transport (pas de garantie de réception par la sonnette). */
    fun sendAdtsFrame(frame: ByteArray): Boolean
    fun close()
}

/**
 * Relais via Home Assistant (WebSocket HTTPS). Marche en 5G et en WiFi.
 * HA ouvre la session voix LAN et pousse les trames ; l'app n'envoie que l'AAC.
 */
class RelayTransport(private val doorbellIp: String) : TalkTransport {
    private companion object {
        // Anti-accumulation (2026-09-05, délai croissant constaté en 5G) : le WebSocket (TCP)
        // met les trames en FILE D'ATTENTE sans bloquer si le réseau ne suit pas (perte/gigue
        // cellulaire) — contrairement à de l'UDP/RTP pur, qui laisserait juste tomber une trame
        // en retard. Sans garde-fou, cette file grossit et le décalage de la conversation
        // s'accumule au fil du temps au lieu de rester stable. On préfère perdre une trame que
        // laisser le retard grandir : au-delà de ~6 trames (≈380 ms) en attente, on saute l'envoi
        // au lieu d'empiler. 256 o/trame (AAC-LC 32 kbps × 64 ms) + marge pour l'entête WebSocket.
        private const val MAX_QUEUED_BYTES = 6 * 320L
        const val ATTEMPTS = 3
    }

    private val client = Net.base.newBuilder()       // DoH : ne dépend pas du DNS du téléphone
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private var droppedFrames = 0L

    override fun open() {
        val token = Config.HA_LONG_LIVED_TOKEN
        if (token.isBlank()) {
            throw IllegalStateException("Jeton HA manquant (Config.HA_LONG_LIVED_TOKEN) — relais 5G impossible")
        }
        val url = Config.talkWsUrl(doorbellIp)                 // https://…/api/aqara_talk/<ip>
        // Retry : sur 5G la résolution DNS / la connexion peut échouer de façon transitoire.
        var lastErr: String? = null
        for (attempt in 1..ATTEMPTS) {
            DebugLog.log("RelayTransport", "connexion WS HA (essai $attempt/$ATTEMPTS) → $url")
            val err = tryConnect(url, token)
            if (err == null) {
                DebugLog.log("RelayTransport", "WebSocket HA ouvert vers $doorbellIp")
                return
            }
            lastErr = err
            DebugLog.log("RelayTransport", "essai $attempt échoué: $err")
            if (attempt < ATTEMPTS) { try { Thread.sleep(1200) } catch (_: InterruptedException) { break } }
        }
        throw IllegalStateException("relais HA ($ATTEMPTS essais) : $lastErr")
    }

    /**
     * Une tentative de connexion. @return null si OK, sinon le message d'erreur.
     *
     * Deux étapes, TOUTES DEUX requises avant de considérer la session comme établie :
     *  1. Handshake HTTP du WebSocket (onOpen) — prouve juste que HA est joignable.
     *  2. Message "ready" envoyé par HA — PREUVE RÉELLE que HA a obtenu un ACK physique de
     *     la sonnette (AqaraLanTalkClient.connect() côté talk_ws.py). Avant ce 2e palier
     *     (ajouté le 2026-08-29), l'étape 1 seule faisait croire l'app "prête" alors que HA
     *     n'avait même pas encore tenté de joindre la sonnette — ni détecté qu'elle est
     *     parfois occupée par la session du message d'accueil auto (GreetingSender), qui
     *     tourne en parallèle dès la sonnerie et peut encore tenir le canal voix (un seul
     *     autorisé à la fois côté sonnette). Le retry existant (3 essais, 1.2s d'écart)
     *     absorbe cette collision : si la sonnette répond "refusée", on retente un peu plus
     *     tard, quand le message d'accueil a fini.
     */
    private fun tryConnect(url: String, token: String): String? {
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .build()
        val openLatch = CountDownLatch(1)
        val readyLatch = CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val sock = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                DebugLog.log("RelayTransport", "onOpen HTTP ${response.code}")
                openLatch.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                DebugLog.log("RelayTransport", "message HA: $text")
                if (text == "ready") {
                    readyLatch.countDown()
                } else if (text.startsWith("error")) {
                    failure.set(text)
                    readyLatch.countDown()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set((t.message ?: "échec WebSocket") + (response?.let { " (HTTP ${it.code})" } ?: ""))
                DebugLog.log("RelayTransport", "onFailure", t)
                openLatch.countDown()
                readyLatch.countDown()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                DebugLog.log("RelayTransport", "onClosed $code $reason")
                readyLatch.countDown()
            }
        })
        if (!openLatch.await(8, TimeUnit.SECONDS)) { sock.cancel(); return "pas de réponse (timeout 8s)" }
        failure.get()?.let { sock.cancel(); return it }
        // Étape 2 : vraie confirmation côté sonnette (ou erreur/fermeture) — 5s suffisent, HA
        // envoie "ready" dès qu'AqaraLanTalkClient.connect() revient (opération LAN rapide).
        if (!readyLatch.await(5, TimeUnit.SECONDS)) { sock.cancel(); return "pas de confirmation sonnette (timeout 5s)" }
        failure.get()?.let { sock.cancel(); return it }
        ws = sock
        return null
    }

    override fun sendAdtsFrame(frame: ByteArray): Boolean {
        val socket = ws ?: return false
        if (socket.queueSize() > MAX_QUEUED_BYTES) {
            if (++droppedFrames % 20L == 1L) {
                DebugLog.log("RelayTransport", "file d'attente pleine (${socket.queueSize()} o) — trame sautée (total $droppedFrames)")
            }
            return false
        }
        return socket.send(ByteString.of(*frame))
    }

    override fun close() {
        try { ws?.send("stop") } catch (_: Exception) {}
        try { ws?.close(1000, "stop") } catch (_: Exception) {}
        ws = null
    }
}

/**
 * Envoi direct à la sonnette sur le LAN (contrôle TCP 54324 + RTP UDP 54323). WiFi only.
 */
class DirectTransport(private val host: String) : TalkTransport {
    companion object {
        private const val CONTROL_PORT = 54324
        private const val AUDIO_PORT = 54323
        private const val HEARTBEAT_MS = 5000L
    }

    @Volatile private var alive = false
    private var tcp: Socket? = null
    private var udp: DatagramSocket? = null
    private var addr: InetAddress? = null
    private var heartbeat: Thread? = null
    private val tcpLock = Any()
    private var sessionTs = 0L
    private val ssrc = Random.nextInt(1, Int.MAX_VALUE).toLong()
    private var seq = 0
    private var rtpTs = 0L

    override fun open() {
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, CONTROL_PORT), 3000)
        s.soTimeout = 3000
        tcp = s
        sessionTs = System.currentTimeMillis()
        synchronized(tcpLock) {
            s.getOutputStream().write(AqaraTalkProtocol.buildPacket(AqaraTalkProtocol.TYPE_START_VOICE, sessionTs))
            s.getOutputStream().flush()
        }
        val resp = ByteArray(64)
        val n = s.getInputStream().read(resp)
        val pkt = AqaraTalkProtocol.parsePacket(resp, n)
        if (pkt == null || pkt.type != AqaraTalkProtocol.TYPE_ACK || pkt.value != 0L) {
            throw IllegalStateException("session voix refusée par la sonnette (canal occupé ?)")
        }
        udp = DatagramSocket()
        addr = InetAddress.getByName(host)
        alive = true
        startHeartbeat()
    }

    override fun sendAdtsFrame(frame: ByteArray): Boolean {
        val a = addr ?: return false
        val rtp = AqaraTalkProtocol.rtpHeader(AqaraTalkProtocol.RTP_PAYLOAD_TYPE, rtpTs, ssrc, seq)
        seq = (seq + 1) and 0xFFFF
        rtpTs += AqaraTalkProtocol.SAMPLES_PER_AAC_FRAME
        val pkt = ByteArray(rtp.size + frame.size)
        System.arraycopy(rtp, 0, pkt, 0, rtp.size)
        System.arraycopy(frame, 0, pkt, rtp.size, frame.size)
        return try {
            udp?.send(DatagramPacket(pkt, pkt.size, a, AUDIO_PORT))
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        alive = false
        try { heartbeat?.interrupt() } catch (_: Exception) {}
        val s = tcp
        if (s != null) {
            try {
                synchronized(tcpLock) {
                    s.getOutputStream().write(AqaraTalkProtocol.buildPacket(AqaraTalkProtocol.TYPE_STOP_VOICE, sessionTs))
                    s.getOutputStream().flush()
                }
            } catch (_: Exception) {}
        }
        try { udp?.close() } catch (_: Exception) {}
        try { tcp?.close() } catch (_: Exception) {}
        udp = null; tcp = null; addr = null
    }

    private fun startHeartbeat() {
        heartbeat = thread(name = "doorbell-hb") {
            val buf = ByteArray(64)
            while (alive) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    val s = tcp ?: break
                    synchronized(tcpLock) {
                        s.getOutputStream().write(AqaraTalkProtocol.buildPacket(AqaraTalkProtocol.TYPE_HEARTBEAT, sessionTs))
                        s.getOutputStream().flush()
                    }
                    try { s.getInputStream().read(buf) } catch (_: Exception) {}
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    break
                }
            }
        }
    }
}