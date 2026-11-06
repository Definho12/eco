package eco

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.awt.event.KeyEvent as AwtKey

/** A key combination. [vk] is the Windows virtual-key code (same numbers as Java's KeyEvent for normal keys). */
data class Combo(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val vk: Int) {
    fun label(): String {
        val parts = ArrayList<String>()
        if (ctrl) parts += "Ctrl"; if (alt) parts += "Alt"; if (shift) parts += "Shift"
        parts += keyName(vk)
        return parts.joinToString(" + ")
    }
    fun encode() = "${if (ctrl) 1 else 0}${if (alt) 1 else 0}${if (shift) 1 else 0}:$vk"
    val hasModifier get() = ctrl || alt || shift

    companion object {
        fun decode(s: String?): Combo? = try {
            val (m, v) = s!!.split(':'); Combo(m[0] == '1', m[1] == '1', m[2] == '1', v.toInt())
        } catch (_: Exception) { null }

        fun ctrlAlt(vk: Int) = Combo(true, true, false, vk)

        fun keyName(vk: Int): String = when (vk) {
            0x25 -> "←"; 0x26 -> "↑"; 0x27 -> "→"; 0x28 -> "↓"; 0x20 -> "Espaço"
            0x21 -> "Page Up"; 0x22 -> "Page Down"; 0x24 -> "Home"; 0x23 -> "End"; 0x2D -> "Insert"; 0x2E -> "Delete"
            in 0x70..0x87 -> "F${vk - 0x6F}"
            in 0x60..0x69 -> "Num ${vk - 0x60}"
            0x6B -> "Num +"; 0x6D -> "Num −"; 0x6A -> "Num *"; 0x6F -> "Num /"
            in 0x30..0x39 -> (vk - 0x30).toString()
            in 0x41..0x5A -> vk.toChar().toString()
            else -> AwtKey.getKeyText(vk)
        }

        /** Keys that are fine on their own (no Ctrl/Alt), because nobody types with them. */
        fun okAlone(vk: Int) = vk in 0x70..0x87 || vk in 0x60..0x6F
    }
}

class Pad(val id: String, name: String, val file: String, volume: Float, hotkey: Combo?) {
    var name by mutableStateOf(name)
    var volume by mutableStateOf(volume)
    var hotkey by mutableStateOf(hotkey)
    /** why it can't play (file missing or unreadable), null when fine */
    var problem by mutableStateOf<String?>(null)
}

/** Your choices. Saved as settings.json. */
object Settings {
    private val file get() = File(AppDirs.data, "settings.json")
    var discordDevice by mutableStateOf("")   // "" = find the virtual cable by itself
    var micDevice by mutableStateOf("")       // "" = Windows' default microphone
    var monitorDevice by mutableStateOf("")   // "" = Windows' default speakers/headphones
    var discordVolume by mutableStateOf(0.85f)
    var monitorVolume by mutableStateOf(0.6f)
    var micOn by mutableStateOf(true)
    var monitorOn by mutableStateOf(true)
    var stopCombo by mutableStateOf<Combo?>(Combo.ctrlAlt(0x30)) // Ctrl+Alt+0
    var trayNoticeShown = false

    fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            discordDevice = o.optString("discordDevice", "")
            micDevice = o.optString("micDevice", "")
            monitorDevice = o.optString("monitorDevice", "")
            discordVolume = o.optDouble("discordVolume", 0.85).toFloat()
            monitorVolume = o.optDouble("monitorVolume", 0.6).toFloat()
            micOn = o.optBoolean("micOn", true)
            monitorOn = o.optBoolean("monitorOn", true)
            stopCombo = if (o.has("stopCombo")) Combo.decode(o.optString("stopCombo")) else Combo.ctrlAlt(0x30)
            trayNoticeShown = o.optBoolean("trayNoticeShown", false)
        } catch (e: Exception) { Log.error("ler settings.json", e) }
    }

    fun save() {
        try {
            val o = JSONObject()
            o.put("discordDevice", discordDevice); o.put("micDevice", micDevice); o.put("monitorDevice", monitorDevice)
            o.put("discordVolume", discordVolume.toDouble()); o.put("monitorVolume", monitorVolume.toDouble())
            o.put("micOn", micOn); o.put("monitorOn", monitorOn)
            o.put("stopCombo", stopCombo?.encode() ?: "")
            o.put("trayNoticeShown", trayNoticeShown)
            file.writeText(o.toString(2))
        } catch (e: Exception) { Log.error("salvar settings.json", e) }
        push()
    }

    /** hands the current values to the audio threads */
    fun push() {
        Audio.discordGain = discordVolume; Audio.monitorGain = monitorVolume
        Audio.micOn = micOn; Audio.monitorOn = monitorOn
    }
}

/**
 * The pads. Each sound is a copy kept in AppData\Eco\sons, so the files you added
 * from elsewhere are never touched. Saved as board.json.
 */
object Board {
    private val file get() = File(AppDirs.data, "board.json")
    val pads = mutableStateListOf<Pad>()
    private val cache = ConcurrentHashMap<String, ShortArray>()

    private val starter = listOf("Buzina", "Ba dum tss", "Trombone triste", "Erro", "Acerto", "Boing", "Grilos", "Apito subindo", "Suspense")

    fun load() {
        pads.clear()
        if (!file.exists()) { addStarter(); return }
        try {
            val a = JSONObject(file.readText()).getJSONArray("pads")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                pads += Pad(o.getString("id"), o.getString("name"), o.getString("file"), o.optDouble("volume", 1.0).toFloat(),
                    Combo.decode(o.optString("hotkey", "")))
            }
        } catch (e: Exception) {
            Log.error("ler board.json", e)
        }
    }

    fun save() {
        try {
            val a = JSONArray()
            for (p in pads) a.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("file", p.file); put("volume", p.volume.toDouble())
                put("hotkey", p.hotkey?.encode() ?: "")
            })
            val tmp = File(AppDirs.data, "board.json.tmp")
            tmp.writeText(JSONObject().put("pads", a).toString(2))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) { Log.error("salvar board.json", e) }
        Hotkeys.reload()
    }

    private fun addStarter() {
        for ((i, name) in starter.withIndex()) {
            try {
                val dest = File(AppDirs.sounds, "$name.wav")
                Board::class.java.getResourceAsStream("/starter/$name.wav")?.use { input -> dest.outputStream().use { input.copyTo(it) } }
                pads += Pad(newId(), name, dest.name, 1f, Combo.ctrlAlt(0x31 + i))
            } catch (e: Exception) { Log.error("som inicial $name", e) }
        }
        save()
    }

    private fun newId() = java.util.UUID.randomUUID().toString().take(8)

    /** Reads every sound into memory, in the background. */
    fun warmUp() {
        Thread({ for (p in pads.toList()) data(p) }, "eco-load").apply { isDaemon = true; start() }
    }

    fun data(p: Pad): ShortArray? {
        cache[p.id]?.let { return it }
        return try {
            val f = File(AppDirs.sounds, p.file)
            if (!f.isFile) throw DecodeException("o arquivo do som sumiu")
            Decode.file(f).also { cache[p.id] = it; ui { p.problem = null } }
        } catch (e: Exception) {
            val msg = (e as? DecodeException)?.message ?: "não consegui ler o som"
            Log.w("som '${p.name}': $msg (${e.message})")
            ui { p.problem = msg }
            null
        }
    }

    private fun ui(block: () -> Unit) = if (javax.swing.SwingUtilities.isEventDispatchThread()) block() else javax.swing.SwingUtilities.invokeLater(block)

    fun play(p: Pad) {
        // reading happens off the screen's thread the first time
        val d = cache[p.id]
        if (d != null) Audio.play(p.id, d, p.volume)
        else Thread({ data(p)?.let { Audio.play(p.id, it, p.volume) } }, "eco-load-one").apply { isDaemon = true; start() }
    }

    fun playById(id: String) { pads.firstOrNull { it.id == id }?.let { play(it) } }

    /** Result of adding files: how many went in and what failed. */
    class AddResult(val added: Int, val failed: List<String>)

    /** Copies the chosen files in as new pads. Runs off the screen's thread. */
    fun addFiles(files: List<File>): AddResult {
        var added = 0
        val failed = ArrayList<String>()
        for (src in files) {
            if (src.extension.lowercase() !in Decode.extensions) { failed += "${src.name}: só dá para usar MP3 ou WAV"; continue }
            try {
                val data = Decode.file(src) // check it plays before keeping it
                var dest = File(AppDirs.sounds, src.name)
                var n = 2
                while (dest.exists()) { dest = File(AppDirs.sounds, "${src.nameWithoutExtension} ($n).${src.extension}"); n++ }
                src.copyTo(dest)
                val used = pads.mapNotNull { it.hotkey }.toSet() + listOfNotNull(Settings.stopCombo)
                val free = (0x31..0x39).map { Combo.ctrlAlt(it) }.firstOrNull { it !in used }
                val pad = Pad(newId(), src.nameWithoutExtension.take(40), dest.name, 1f, free)
                cache[pad.id] = data
                ui { pads += pad }
                added++
            } catch (e: Exception) {
                failed += "${src.name}: ${(e as? DecodeException)?.message ?: "não consegui copiar (${e.message})"}"
            }
        }
        ui { save() }
        return AddResult(added, failed)
    }

    fun remove(p: Pad) {
        pads.remove(p)
        cache.remove(p.id)
        // the copy in AppData goes away; the original file you added stays where it was
        if (pads.none { it.file == p.file }) try { File(AppDirs.sounds, p.file).delete() } catch (_: Exception) {}
        save()
    }

    fun move(from: Int, to: Int) {
        if (from !in pads.indices || to !in pads.indices || from == to) return
        val p = pads.removeAt(from); pads.add(to, p); save()
    }

    /** gives [combo] to [owner] and takes it away from whoever had it */
    fun claimCombo(combo: Combo, owner: Pad?) {
        for (p in pads) if (p !== owner && p.hotkey == combo) p.hotkey = null
        if (owner != null && Settings.stopCombo == combo) { Settings.stopCombo = null; Settings.save() }
    }
}
