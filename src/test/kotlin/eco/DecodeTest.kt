package eco

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DecodeTest {
    private fun seconds(d: ShortArray) = d.size / 2.0 / RATE

    @Test fun starterSoundsDecode() {
        val dir = File(javaClass.getResource("/starter/Boing.wav")!!.toURI()).parentFile
        val files = dir.listFiles { f -> f.extension == "wav" }!!
        assertEquals(9, files.size)
        for (f in files) {
            val d = Decode.file(f)
            assertTrue("${f.name} vazio", seconds(d) > 0.5)
            assertTrue("${f.name} sem som", d.any { kotlin.math.abs(it.toInt()) > 1000 })
        }
        assertEquals(1.0, seconds(Decode.file(File(dir, "Boing.wav"))), 0.01)
    }

    @Test fun resampleKeepsLength() {
        val n = 44100
        val l = FloatArray(n) { kotlin.math.sin(it * 0.05f) * 0.5f }
        val out = Decode.toStereo48k(l, l, 44100)
        assertEquals(1.0, seconds(out), 0.001)
    }

    /** MP3, OGG and other WAV kinds made by the build machine with ffmpeg (each is 2 s of tone). */
    @Test fun otherFormats() {
        val dir = System.getenv("ECO_TEST_MEDIA")?.takeIf { it.isNotEmpty() }?.let { File(it) } ?: return
        val files = dir.listFiles()!!.filter { it.extension in Decode.extensions }
        assertTrue("faltam arquivos de teste", files.size >= 4)
        for (f in files) {
            val d = try { Decode.file(f) } catch (e: Exception) { throw AssertionError("${f.name}: ${e.message}", e) }
            assertEquals("${f.name} [${Decode.lastInfo}]", 2.0, seconds(d), 0.12)
            val peak = d.maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("${f.name} peak $peak", peak in 6000..20000)
        }
    }

    @Test fun rejectsVideo() {
        val f = File.createTempFile("eco", ".mp3").apply { writeText("isto não é um mp3"); deleteOnExit() }
        try { Decode.file(f); throw AssertionError("deveria recusar") } catch (_: DecodeException) {}
    }
}
