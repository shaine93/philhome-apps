import com.philhome.sonnettevideo.VoiceFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/**
 * Banc de test HORS TÉLÉPHONE pour VoiceFilter (filtre anti-vent — voir Prefs.windSensitivity).
 * Rejoue un .wav réel (vent enregistré près de la sonnette, ou tentative de Larsen) à travers
 * EXACTEMENT le même code que l'app (VoiceFilter.kt, compilé directement depuis ce fichier —
 * aucune copie, aucun risque de désynchronisation).
 *
 * Pourquoi : avant cet outil, ajuster un seuil = modifier VoiceFilter.kt, recompiler l'APK,
 * réinstaller par USB, provoquer un faux appel, écouter au téléphone. Cycle de plusieurs
 * minutes par essai, incompatible avec un vrai réglage à l'oreille. Ici : modifier, relancer,
 * écouter le .wav de sortie — quelques secondes.
 *
 * Usage : voir run.sh (gère la compilation). Direct :
 *   kotlin -cp bench.jar WindBenchMainKt entree.wav sortie.wav [sensibilite=1.0]
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: wind-bench <entree.wav> <sortie.wav> [sensibilite=1.0]")
        System.err.println("  sensibilite : multiplicateur des seuils de la porte adaptative")
        System.err.println("                (0.7=léger / 1.0=normal / 1.4=fort — voir Prefs.windSensitivity)")
        return
    }
    val inFile = File(args[0])
    val outFile = File(args[1])
    val sensitivity = args.getOrNull(2)?.toDoubleOrNull() ?: 1.0
    require(inFile.exists()) { "Fichier introuvable : ${inFile.path}" }

    // Même format que le micro réel dans DoorbellTalk : PCM 16 bits mono 16 kHz.
    val targetFormat = AudioFormat(16000f, 16, 1, true, false)
    val rawIn = AudioSystem.getAudioInputStream(inFile)
    val stream: AudioInputStream =
        if (rawIn.format.matches(targetFormat)) rawIn
        else AudioSystem.getAudioInputStream(targetFormat, rawIn)

    val filter = VoiceFilter(16000, sensitivity)
    val pcmBuf = ByteArray(2048)
    val filtered = ByteArrayOutputStream()
    var n: Int
    var totalSamples = 0L
    while (stream.read(pcmBuf).also { n = it } > 0) {
        val samples = ShortArray(n / 2)
        for (i in samples.indices) {
            val lo = pcmBuf[2 * i].toInt() and 0xFF
            val hi = pcmBuf[2 * i + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        filter.process(samples, samples.size)
        totalSamples += samples.size
        for (s in samples) {
            val v = s.toInt()
            filtered.write(v and 0xFF)
            filtered.write((v shr 8) and 0xFF)
        }
    }
    stream.close()

    val outBytes = filtered.toByteArray()
    val outStream = AudioInputStream(ByteArrayInputStream(outBytes), targetFormat, (outBytes.size / 2).toLong())
    AudioSystem.write(outStream, AudioFileFormat.Type.WAVE, outFile)

    val seconds = totalSamples / 16000.0
    println("OK — ${inFile.name} -> ${outFile.name} (${"%.1f".format(seconds)}s audio, sensibilité=$sensitivity)")
}
