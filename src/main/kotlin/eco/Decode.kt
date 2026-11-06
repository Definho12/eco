package eco

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** Every sound is kept in memory as 48 kHz stereo 16-bit samples (left, right, left, right…). */
const val RATE = 48000
const val MAX_SECONDS = 180

class DecodeException(msg: String) : Exception(msg)

object Decode {
    val extensions = setOf("mp3", "wav", "ogg")
    /** what the last file looked like (for the log and the tests) */
    @Volatile var lastInfo = ""

    /** Reads an MP3, WAV or OGG file into 48 kHz stereo samples. */
    fun file(f: File): ShortArray {
        val ext = f.extension.lowercase()
        val src: AudioInputStream = try {
            when (ext) {
                "mp3" -> javazoom.spi.mpeg.sampled.file.MpegAudioFileReader().getAudioInputStream(f)
                "ogg" -> javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader().getAudioInputStream(f)
                else -> AudioSystem.getAudioInputStream(f)
            }
        } catch (e: Exception) {
            throw DecodeException("não consegui ler esse arquivo (${e.message ?: e::class.java.simpleName})")
        }
        src.use { s ->
            val base = s.format
            val rate = base.sampleRate.takeIf { it > 0 } ?: 44100f
            val ch = base.channels.takeIf { it in 1..8 } ?: 2
            val target = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false)
            val pcm = if (base.encoding == AudioFormat.Encoding.PCM_SIGNED && base.sampleSizeInBits == 16 && !base.isBigEndian) s
            else try { AudioSystem.getAudioInputStream(target, s) } catch (e: Exception) {
                throw DecodeException("formato de áudio não suportado")
            }
            // trust what the decoder actually hands back, not what was asked for
            val got = pcm.format
            val outRate = got.sampleRate.takeIf { it > 0 } ?: rate
            val outCh = got.channels.takeIf { it in 1..8 } ?: ch
            val big = got.isBigEndian
            lastInfo = "$base -> $got"
            if (got.sampleSizeInBits != 16) throw DecodeException("formato de áudio não suportado")
            readFrames(pcm, outRate, outCh, big).let { return it }
        }
    }

    private fun readFrames(pcm: AudioInputStream, rate: Float, ch: Int, big: Boolean): ShortArray {
        run {
            val limit = (rate * ch * 2 * (MAX_SECONDS + 1)).toLong()
            val bytes = readUpTo(pcm, limit)
            if (bytes.size >= limit) throw DecodeException("som longo demais (o máximo é $MAX_SECONDS segundos)")
            val frames = bytes.size / (ch * 2)
            if (frames < 10) throw DecodeException("o arquivo não tem som")
            // to float, then to stereo
            val left = FloatArray(frames); val right = FloatArray(frames)
            for (i in 0 until frames) {
                val o = i * ch * 2
                fun sample(c: Int): Float {
                    val p = o + c * 2
                    val lo = if (big) bytes[p + 1] else bytes[p]
                    val hi = if (big) bytes[p] else bytes[p + 1]
                    return ((lo.toInt() and 0xff) or (hi.toInt() shl 8)).toShort() / 32768f
                }
                if (ch == 1) { val v = sample(0); left[i] = v; right[i] = v }
                else { left[i] = sample(0); right[i] = sample(1) }
            }
            return toStereo48k(left, right, rate.toInt())
        }
    }

    private fun readUpTo(s: AudioInputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Linear resampling to 48 kHz, interleaved 16-bit. */
    fun toStereo48k(left: FloatArray, right: FloatArray, srcRate: Int): ShortArray {
        val n = left.size
        if (srcRate == RATE) {
            val out = ShortArray(n * 2)
            for (i in 0 until n) { out[2 * i] = toShort(left[i]); out[2 * i + 1] = toShort(right[i]) }
            return out
        }
        val outFrames = (n.toLong() * RATE / srcRate).toInt()
        val out = ShortArray(outFrames * 2)
        val step = srcRate.toDouble() / RATE
        for (j in 0 until outFrames) {
            val pos = j * step
            val i = pos.toInt().coerceAtMost(n - 1)
            val fr = (pos - i).toFloat()
            val i2 = (i + 1).coerceAtMost(n - 1)
            out[2 * j] = toShort(left[i] + (left[i2] - left[i]) * fr)
            out[2 * j + 1] = toShort(right[i] + (right[i2] - right[i]) * fr)
        }
        return out
    }

    private fun toShort(v: Float): Short = (v * 32767f).toInt().coerceIn(-32768, 32767).toShort()
}
