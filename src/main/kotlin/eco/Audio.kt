package eco

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/**
 * The sound engine. Two outputs run side by side:
 *  - "Discord": your microphone + the sounds, sent into the virtual cable (Discord listens to the cable);
 *  - "Fones": only the sounds, in your own headphones, so you hear what you played.
 * Each one mixes 10 ms at a time.
 */
object Audio {
    private val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
    private val monoFormat = AudioFormat(RATE.toFloat(), 16, 1, true, false)
    private const val FRAMES = 480 // 10 ms

    // ---- what the screen shows (written on the UI thread) ----
    var discordStatus by mutableStateOf(Status.Starting)
    var discordDevice by mutableStateOf<String?>(null)
    var micStatus by mutableStateOf(Status.Starting)
    var monitorStatus by mutableStateOf(Status.Starting)
    /** pads playing right now: pad id → (start time, length in ms) */
    val playing = mutableStateMapOf<String, Pair<Long, Long>>()

    enum class Status { Starting, Ok, Missing, Off }

    /** last microphone level (0..1), for the little meter */
    @Volatile var micLevel = 0f
        private set

    // ---- settings the audio threads read ----
    @Volatile var discordGain = 1f
    @Volatile var monitorGain = 0.6f
    @Volatile var micOn = true
    @Volatile var monitorOn = true

    private sealed interface Cmd {
        class Play(val id: String, val data: ShortArray, val gain: Float) : Cmd
        object StopAll : Cmd
    }

    private class Voice(val id: String, val data: ShortArray, val gain: Float) { var pos = 0 }

    private class Output(val name: String, val forDiscord: Boolean) {
        val cmds = ConcurrentLinkedQueue<Cmd>()
        @Volatile var reopen = true
        @Volatile var device: String? = null
        var thread: Thread? = null
    }

    private val discord = Output("discord", true)
    private val monitor = Output("fones", false)

    fun start() {
        for (o in listOf(discord, monitor)) {
            o.thread = Thread({ run(o) }, "eco-${o.name}").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        }
    }

    /** Looks for the devices again (after installing the cable, or changing a setting). */
    fun reopen() { discord.reopen = true; monitor.reopen = true }

    fun play(id: String, data: ShortArray, padVolume: Float) {
        val g = padVolume * padVolume
        // pressing a pad that is already playing starts it over
        discord.cmds.add(Cmd.Play(id, data, g))
        monitor.cmds.add(Cmd.Play(id, data, g))
        val ms = data.size / 2 * 1000L / RATE
        ui { playing[id] = System.currentTimeMillis() to ms }
    }

    fun stopAll() {
        discord.cmds.add(Cmd.StopAll); monitor.cmds.add(Cmd.StopAll)
        ui { playing.clear() }
    }

    private fun ui(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)

    // ---------------- devices ----------------

    private fun hasLine(m: Mixer.Info, cls: Class<*>): Boolean = try {
        AudioSystem.getMixer(m).isLineSupported(DataLine.Info(cls, format)) ||
            (cls == TargetDataLine::class.java && AudioSystem.getMixer(m).isLineSupported(DataLine.Info(cls, monoFormat)))
    } catch (_: Exception) { false }

    /** Device names you can play into. */
    fun outputs(): List<String> = try {
        AudioSystem.getMixerInfo().filter { !it.name.startsWith("Port ") && hasLine(it, SourceDataLine::class.java) }.map { it.name }.distinct()
    } catch (_: Exception) { emptyList() }

    /** Microphones. */
    fun inputs(): List<String> = try {
        AudioSystem.getMixerInfo().filter {
            !it.name.startsWith("Port ") && !it.name.contains("Primary Sound Capture", true) &&
                try { AudioSystem.getMixer(it).targetLineInfo.isNotEmpty() } catch (_: Exception) { false }
        }.map { it.name }.distinct()
    } catch (_: Exception) { emptyList() }

    /** The virtual cable's input (what Discord hears on "CABLE Output"). */
    fun findCable(): String? = outputs().firstOrNull { it.contains("CABLE Input", ignoreCase = true) }
        ?: outputs().firstOrNull { it.contains("VB-Audio", ignoreCase = true) && !it.contains("Output", ignoreCase = true) }

    private fun mixerFor(name: String?): Mixer.Info? =
        if (name.isNullOrEmpty()) null else AudioSystem.getMixerInfo().firstOrNull { it.name == name && !it.name.startsWith("Port ") }

    @Volatile private var lastNote = ""
    private val failedOnce = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun openOut(device: String?): SourceDataLine? = try {
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val m = mixerFor(device)
        val l = (if (m != null) AudioSystem.getMixer(m).getLine(info) else AudioSystem.getLine(info)) as SourceDataLine
        l.open(format, FRAMES * 4 * 4) // ~40 ms
        l.start()
        l
    } catch (e: Exception) {
        if (failedOnce.add("out:$device")) Log.w("não abri a saída '$device': ${e.message}"); null
    }

    /** An open microphone. It may run at any rate; each 10 ms block is stretched to 48 kHz. */
    private class Mic(val line: TargetDataLine, val channels: Int, val rate: Int, val name: String) {
        val frames = rate / 100
        var lastA = 0f
        var lastB = 0f
    }

    /** the microphone actually in use (for the screen) */
    var micName by mutableStateOf<String?>(null)

    private fun isCable(name: String) = name.contains("CABLE", true) || name.contains("VB-Audio", true)

    private fun tryMic(m: Mixer.Info?, label: String): Mic? {
        // 48 kHz first; many headsets and webcams only do 44.1, 32 or 16 kHz
        for (rate in intArrayOf(48000, 44100, 32000, 16000, 96000, 24000, 8000)) for (ch in intArrayOf(2, 1)) {
            val fmt = AudioFormat(rate.toFloat(), 16, ch, true, false)
            try {
                val info = DataLine.Info(TargetDataLine::class.java, fmt)
                val l = (if (m != null) AudioSystem.getMixer(m).getLine(info) else AudioSystem.getLine(info)) as TargetDataLine
                l.open(fmt, (rate / 100) * fmt.frameSize * 8)
                l.start()
                return Mic(l, ch, rate, label)
            } catch (_: Exception) {
            }
        }
        if (failedOnce.add("mic:$label")) Log.w("não abri o microfone '$label' em nenhum formato")
        return null
    }

    /**
     * Opens your microphone: the one chosen in Ajustes, else Windows' default,
     * else any other real microphone (never the virtual cable, that would be Eco hearing itself).
     */
    private fun openMic(device: String?): Mic? {
        val chosen = mixerFor(device)
        if (chosen != null) tryMic(chosen, chosen.name)?.let { return it }
        tryMic(null, "Padrão do Windows")?.let { return it }
        val others = try {
            AudioSystem.getMixerInfo().filter {
                !it.name.startsWith("Port ") && !isCable(it.name) && it.name != device &&
                    !it.name.contains("Primary Sound Capture", true) &&
                    try { AudioSystem.getMixer(it).targetLineInfo.isNotEmpty() } catch (_: Exception) { false }
            }
        } catch (_: Exception) { emptyList() }
        for (m in others) tryMic(m, m.name)?.let { return it }
        return null
    }

    private fun micOpened(mic: Mic?) {
        val name = mic?.name
        val st = if (mic != null) Status.Ok else Status.Missing
        if (mic != null && failedOnce.add("micok:${mic.name}:${mic.rate}:${mic.channels}"))
            Log.w("microfone aberto: ${mic.name} (${mic.rate} Hz, ${mic.channels} canal)")
        ui { micStatus = st; micName = name }
    }

    // ---------------- the mixing loop ----------------

    private fun run(o: Output) {
        var line: SourceDataLine? = null
        var mic: Mic? = null
        var micWanted = false
        val voices = ArrayList<Voice>()
        val mix = FloatArray(FRAMES * 2)
        val bytes = ByteArray(FRAMES * 4)
        val silence = ByteArray(FRAMES * 4)
        var micBytes = ByteArray(FRAMES * 4)
        var lastTry = 0L
        while (true) {
            try {
                // (re)open devices when asked, or every few seconds while something is missing
                val now = System.currentTimeMillis()
                val wantMonitor = o.forDiscord || monitorOn
                if (o.forDiscord && line != null && micOn && mic == null && !o.reopen && now - lastTry > 5000) {
                    // only the microphone is missing: try it again without touching the cable
                    lastTry = now
                    mic = openMic(Settings.micDevice.takeIf { it.isNotEmpty() })
                    if (mic != null) micOpened(mic)
                }
                if (o.reopen || (line == null && wantMonitor && now - lastTry > 4000) || (!wantMonitor && line != null)) {
                    o.reopen = false; lastTry = now
                    line?.let { try { it.close() } catch (_: Exception) {} }; line = null
                    mic?.let { try { it.line.close() } catch (_: Exception) {} }; mic = null
                    if (o.forDiscord) {
                        val chosen = Settings.discordDevice.takeIf { it.isNotEmpty() } ?: findCable()
                        line = if (chosen != null) openOut(chosen) else null
                        o.device = chosen
                        val st = if (line != null) Status.Ok else Status.Missing
                        ui { discordStatus = st; discordDevice = chosen }
                        if (micOn && line != null) {
                            mic = openMic(Settings.micDevice.takeIf { it.isNotEmpty() })
                            micOpened(mic)
                        } else ui { micStatus = if (micOn) Status.Missing else Status.Off; micName = null }
                        micWanted = micOn
                        val note = "Discord: ${chosen ?: "(sem cabo)"} ${if (line != null) "ok" else "falhou"}; microfone: ${if (mic != null) "ok" else "não"}"
                        if (note != lastNote) { Log.w(note); lastNote = note }
                    } else if (wantMonitor) {
                        line = openOut(Settings.monitorDevice.takeIf { it.isNotEmpty() })
                        val st = if (line != null) Status.Ok else Status.Missing
                        ui { monitorStatus = st }
                    } else ui { monitorStatus = Status.Off }
                }
                if (o.forDiscord && micWanted != micOn) { o.reopen = true; continue }

                // commands from the buttons and shortcuts
                while (true) {
                    when (val c = o.cmds.poll() ?: break) {
                        is Cmd.Play -> { voices.removeAll { it.id == c.id }; voices.add(Voice(c.id, c.data, c.gain)) }
                        Cmd.StopAll -> voices.clear()
                    }
                }

                val l = line
                if (l == null) {
                    // nowhere to play: drop what was asked and wait
                    voices.clear(); micLevel = 0f
                    Thread.sleep(100); continue
                }

                java.util.Arrays.fill(mix, 0f)
                val m = mic
                if (o.forDiscord && m != null) {
                    // your voice: blocks ~10 ms, which also keeps the loop in time
                    val n = m.frames
                    val need = n * 2 * m.channels
                    if (micBytes.size != need) micBytes = ByteArray(need)
                    var got = 0
                    while (got < need) { val r = m.line.read(micBytes, got, need - got); if (r <= 0) break; got += r }
                    fun sa(i: Int): Float { val p = i * 2 * m.channels; return ((micBytes[p].toInt() and 0xff) or (micBytes[p + 1].toInt() shl 8)).toShort() / 32768f }
                    fun sb(i: Int): Float { if (m.channels == 1) return sa(i); val p = i * 4 + 2; return ((micBytes[p].toInt() and 0xff) or (micBytes[p + 1].toInt() shl 8)).toShort() / 32768f }
                    var sum = 0.0
                    for (i in 0 until FRAMES) {
                        val a: Float; val b: Float
                        if (n == FRAMES) { a = sa(i); b = sb(i) }
                        else {
                            // stretch this block to 480 samples, joining smoothly with the previous block
                            val pos = (i + 1) * n.toFloat() / FRAMES - 1f
                            val j = kotlin.math.floor(pos).toInt()
                            val fr = pos - j
                            val a0 = if (j < 0) m.lastA else sa(j); val b0 = if (j < 0) m.lastB else sb(j)
                            val j1 = (j + 1).coerceAtMost(n - 1)
                            a = a0 + (sa(j1) - a0) * fr; b = b0 + (sb(j1) - b0) * fr
                        }
                        mix[2 * i] = a; mix[2 * i + 1] = b
                        sum += (a * a + b * b) / 2
                    }
                    m.lastA = sa(n - 1); m.lastB = sb(n - 1)
                    micLevel = sqrt(sum / FRAMES).toFloat()
                } else if (o.forDiscord) micLevel = 0f

                if (voices.isNotEmpty()) {
                    val master = if (o.forDiscord) discordGain.let { it * it } else monitorGain.let { it * it }
                    val it = voices.iterator()
                    while (it.hasNext()) {
                        val v = it.next()
                        val g = v.gain * master / 32768f
                        val d = v.data
                        var p = v.pos
                        var i = 0
                        while (i < FRAMES * 2 && p < d.size) { mix[i] += d[p] * g; i++; p++ }
                        v.pos = p
                        if (p >= d.size) {
                            it.remove()
                            if (o.forDiscord) { val id = v.id; ui { playing.remove(id) } }
                        }
                    }
                }

                for (i in 0 until FRAMES * 2) {
                    // soft limit so a loud sound + your voice never crackle
                    var x = mix[i]
                    if (x > 0.9f) x = 0.9f + (x - 0.9f) / (1f + (x - 0.9f) * 10f)
                    else if (x < -0.9f) x = -0.9f + (x + 0.9f) / (1f - (x + 0.9f) * 10f)
                    val s = (x * 32767f).toInt().coerceIn(-32768, 32767)
                    bytes[2 * i] = (s and 0xff).toByte(); bytes[2 * i + 1] = ((s shr 8) and 0xff).toByte()
                }

                if (m != null) {
                    // the mic and the cable tick on slightly different clocks: keep the delay small
                    val filled = l.bufferSize - l.available()
                    if (filled > bytes.size * 6) continue          // too far ahead: skip 10 ms
                    if (filled < bytes.size) l.write(silence, 0, silence.size) // about to run dry: pad
                }
                var off = 0
                while (off < bytes.size) { val w = l.write(bytes, off, bytes.size - off); if (w <= 0) break; off += w }
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                Log.error("som (${o.name})", e)
                o.reopen = true
                try { Thread.sleep(500) } catch (_: InterruptedException) { return }
            }
        }
    }
}
