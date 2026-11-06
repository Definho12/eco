package eco

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")

/** Where Eco keeps its things: %APPDATA%\Eco (settings, the board and copies of the sounds). */
object AppDirs {
    val data: File by lazy {
        val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".config")
        File(base, "Eco").apply { mkdirs() }
    }
    val sounds: File by lazy { File(data, "sons").apply { mkdirs() } }
}

/** A small diary of what Eco did (AppData\Eco\log.txt), so problems can be found. */
object Log {
    private val file: File by lazy {
        File(AppDirs.data, "log.txt").also { f ->
            if (f.length() > 300_000) { File(AppDirs.data, "log-antigo.txt").delete(); f.renameTo(File(AppDirs.data, "log-antigo.txt")) }
        }
    }
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun w(msg: String) {
        try { file.appendText("${fmt.format(Date())}  $msg\n") } catch (_: Exception) {}
    }

    fun error(where: String, e: Throwable) = w("ERRO em $where: ${e::class.java.simpleName}: ${e.message}\n${e.stackTraceToString().take(3000)}")
}

/**
 * Only one Eco at a time: the running one holds a lock on a small file. A second start leaves a
 * "show" note for the first one (which brings its window forward) and quits.
 */
object SingleInstance {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private val lockFile get() = File(AppDirs.data, "instance.lock")
    private val showFile get() = File(AppDirs.data, "show.request")

    fun claim(onShowRequest: () -> Unit): Boolean {
        val l = try {
            val ch = RandomAccessFile(lockFile, "rw").channel
            channel = ch
            ch.tryLock()
        } catch (e: Exception) {
            // couldn't even check (antivirus, permissions…): never block Eco from opening because of that
            Log.error("verificar se já está aberto", e)
            return true
        }
        if (l == null) {
            Log.w("outro Eco já está aberto: pedindo para ele aparecer")
            try { showFile.writeText(System.currentTimeMillis().toString()) } catch (_: Exception) {}
            return false
        }
        lock = l
        showFile.delete()
        Thread({
            try {
                val ws = FileSystems.getDefault().newWatchService()
                AppDirs.data.toPath().register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)
                while (true) {
                    val key = ws.take()
                    var hit = false
                    for (e in key.pollEvents()) if (e.context()?.toString() == showFile.name) hit = true
                    key.reset()
                    if (hit) { showFile.delete(); javax.swing.SwingUtilities.invokeLater(onShowRequest) }
                }
            } catch (_: Exception) {
            }
        }, "eco-single-instance").apply { isDaemon = true; start() }
        return true
    }
}

/** Paints Windows' own title bar dark, in Eco's colors. */
object TitleBar {
    private interface Dwm : StdCallLibrary {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: IntByReference, size: Int): Int
    }
    private val dwm: Dwm? by lazy {
        if (!isWindows) null else try { Native.load("dwmapi", Dwm::class.java) } catch (_: Throwable) { null }
    }

    private fun bgr(rgb: Long): Int = (((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)).toInt()

    fun applyDark(window: java.awt.Window) {
        val d = dwm ?: return
        try {
            val handle = (window as? androidx.compose.ui.awt.ComposeWindow)?.windowHandle ?: return
            val hwnd = WinDef.HWND(Pointer(handle))
            d.DwmSetWindowAttribute(hwnd, 20, IntByReference(1), 4)
            d.DwmSetWindowAttribute(hwnd, 19, IntByReference(1), 4)
            d.DwmSetWindowAttribute(hwnd, 35, IntByReference(bgr(0x101113)), 4)
            d.DwmSetWindowAttribute(hwnd, 34, IntByReference(bgr(0x101113)), 4)
            d.DwmSetWindowAttribute(hwnd, 36, IntByReference(bgr(0xE8E9EC)), 4)
        } catch (_: Throwable) {
        }
    }
}
