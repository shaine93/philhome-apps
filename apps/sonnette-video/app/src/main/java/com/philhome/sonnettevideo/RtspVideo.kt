package com.philhome.sonnettevideo

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * Vidéo live de la sonnette EN DIRECT via **RTSP LAN**, décodée par **libVLC**, SANS Home Assistant.
 *
 * Pourquoi libVLC et pas ExoPlayer/Media3 : le serveur RTSP du G400 (live555) annonce la piste H264
 * SANS ligne `fmtp`/`sprop-parameter-sets` (SPS/PPS envoyés in-band). ExoPlayer l'EXIGE et refuse
 * (`missing attribute fmtp`) ; libVLC — même famille ffmpeg que l'ijkplayer d'Aqara — la tolère.
 *
 * Anti-vent : l'**égaliseur VLC** écrase les bandes graves (<300 Hz) où vit le grondement du vent
 * (équivalent du preset go2rtc `opuswind`, mais 100% dans l'app).
 *
 * API alignée sur [WebrtcVideo] : [play]/[mute]/[unmute]/[destroy] + callback [onPlaying]
 * (1ʳᵉ sortie vidéo = Vout) pour masquer l'instantané anti-écran-noir.
 */
class RtspVideo(
    private val context: Context,
    private val videoLayout: VLCVideoLayout,
    private val onPlaying: () -> Unit = {}
) {
    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    @Volatile private var playingNotified = false

    /**
     * Démarre la lecture. [channel] = "ch1" (1200p) / "ch2" (960p) / "ch3" (480p).
     * [muted] : lancée muette dès la sonnerie (aperçu), dé-mutée au « Répondre ».
     */
    fun play(channel: String = "ch1", muted: Boolean = true) {
        if (player != null) return
        val url = "rtsp://${Config.RTSP_USER}:${Config.RTSP_PASS}@${DoorbellIp.current(context)}:8554/$channel"
        DebugLog.log("RtspVideo", "play $url (libVLC, tcp) muted=$muted")

        // --rtsp-tcp = RTP interleaved sur TCP (fiable derrière NAT local). Cache bas = latence basse.
        val vlc = LibVLC(context, arrayListOf(
            "--rtsp-tcp",
            "--network-caching=250",
            "--live-caching=250",
            "--clock-jitter=0",
            "--no-audio-time-stretch",
            // I-frames 1200p volumineuses : buffer RTP par défaut (250 Ko) trop petit → data droppée
            // = artefacts. On monte à 2 Mo pour recevoir les keyframes entières.
            "--rtsp-frame-buffer-size=2000000"
        ))
        libVlc = vlc

        val mp = MediaPlayer(vlc)
        mp.attachViews(videoLayout, null, false, false)
        mp.volume = if (muted) 0 else 100
        applyWindEqualizer(mp)

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Vout -> if (ev.voutCount > 0 && !playingNotified) {
                    playingNotified = true
                    DebugLog.log("RtspVideo", "PLAYING (Vout)")
                    onPlaying()
                }
                MediaPlayer.Event.EncounteredError ->
                    DebugLog.log("RtspVideo", "ERREUR libVLC (EncounteredError)")
            }
        }

        val media = Media(vlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)   // décodage matériel H264 (fluide, basse conso)
            addOption(":network-caching=250")
            addOption(":rtsp-tcp")
        }
        mp.media = media
        media.release()
        player = mp
        mp.play()
    }

    /** Anti-vent : coupe fort les bandes graves de l'égaliseur (le « BRRRUUT » vit sous ~300 Hz). */
    private fun applyWindEqualizer(mp: MediaPlayer) {
        try {
            val eq = MediaPlayer.Equalizer.create()
            val bands = MediaPlayer.Equalizer.getBandCount()
            for (i in 0 until bands) {
                val f = MediaPlayer.Equalizer.getBandFrequency(i)
                when {
                    f < 100f -> eq.setAmp(i, -20f)   // grondement profond
                    f < 300f -> eq.setAmp(i, -12f)   // bas du vent
                }
            }
            mp.setEqualizer(eq)
        } catch (e: Exception) {
            DebugLog.log("RtspVideo", "égaliseur anti-vent indispo: ${e.message}")
        }
    }

    fun mute() { player?.volume = 0 }

    fun unmute() {
        DebugLog.log("RtspVideo", "unmute (audio visiteur)")
        player?.volume = 100
    }

    /**
     * Anti-Larsen : réduit EN CONTINU le volume du visiteur pendant qu'on parle (évite la boucle
     * acoustique haut-parleur→micro sur le même téléphone). [level] 0..1 (1 = plein volume, comme
     * après [unmute]). Appelé à chaque tampon micro (~64 ms) par [IncomingCallActivity] — pas
     * d'inertie ajoutée ici, le lissage vient déjà du gain de [VoiceFilter] côté appelant.
     */
    fun duck(level: Double) { player?.volume = (level.coerceIn(0.0, 1.0) * 100).toInt() }

    fun destroy() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.detachViews() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        try { libVlc?.release() } catch (_: Exception) {}
        player = null; libVlc = null
    }
}
