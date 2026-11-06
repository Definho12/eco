package eco

import androidx.compose.runtime.mutableStateMapOf
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

/**
 * System-wide shortcuts (they work while a game is in front), using Windows' RegisterHotKey.
 * Id 1 is "stop everything"; the pads use 10, 11, 12…
 */
object Hotkeys {
    /** combos Windows refused because another program already uses them (encoded combo → true) */
    val taken = mutableStateMapOf<String, Boolean>()

    private var threadId = 0
    @Volatile private var suspended = false
    @Volatile private var padIds: List<String> = emptyList()
    private const val WM_HOTKEY = 0x0312
    private const val WM_RELOAD = 0x8000 + 1
    private const val STOP_ID = 1
    private const val PAD_BASE = 10

    fun init() { if (isWindows) start() }

    /** While you're recording a new combo, the shortcuts are switched off so they don't fire. */
    fun suspend(on: Boolean) { suspended = on; reload() }

    fun reload() {
        if (isWindows && threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WM_RELOAD, WinDef.WPARAM(0), WinDef.LPARAM(0))
    }

    private fun mods(c: Combo): Int {
        var m = 0x4000 // MOD_NOREPEAT: holding the keys plays it once
        if (c.alt) m = m or 0x1; if (c.ctrl) m = m or 0x2; if (c.shift) m = m or 0x4
        return m
    }

    private var registered = 0

    private fun start() {
        Thread({
            val u = User32.INSTANCE
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            fun registerAll() {
                u.UnregisterHotKey(null, STOP_ID)
                for (i in 0 until registered) u.UnregisterHotKey(null, PAD_BASE + i)
                registered = 0
                // read the current setup on the screen's thread
                var stop: Combo? = null
                var list: List<Pair<String, Combo?>> = emptyList()
                javax.swing.SwingUtilities.invokeAndWait {
                    stop = Settings.stopCombo
                    list = Board.pads.map { it.id to it.hotkey }
                }
                val refused = HashMap<String, Boolean>()
                if (!suspended) {
                    stop?.let { if (!u.RegisterHotKey(null, STOP_ID, mods(it), it.vk)) refused[it.encode()] = true }
                    for ((i, p) in list.withIndex()) {
                        val c = p.second ?: continue
                        if (!u.RegisterHotKey(null, PAD_BASE + i, mods(c), c.vk)) refused[c.encode()] = true
                    }
                    registered = list.size
                }
                padIds = list.map { it.first }
                javax.swing.SwingUtilities.invokeLater { taken.clear(); taken.putAll(refused) }
            }
            try {
                registerAll()
                val msg = WinUser.MSG()
                while (u.GetMessage(msg, null, 0, 0) > 0) {
                    when (msg.message) {
                        WM_HOTKEY -> {
                            val id = msg.wParam.toInt()
                            if (id == STOP_ID) Audio.stopAll()
                            else padIds.getOrNull(id - PAD_BASE)?.let { pid -> javax.swing.SwingUtilities.invokeLater { Board.playById(pid) } }
                        }
                        WM_RELOAD -> registerAll()
                    }
                }
            } catch (e: Throwable) {
                Log.error("atalhos", e)
            }
        }, "eco-hotkeys").apply { isDaemon = true; start() }
    }
}
