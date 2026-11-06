package eco

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.io.File

fun main() {
    try {
        realMain()
    } catch (e: Throwable) {
        Log.error("abrir o Eco", e)
        try {
            javax.swing.JOptionPane.showMessageDialog(
                null, "O Eco não conseguiu abrir:\n${e::class.java.simpleName}: ${e.message}\n\nDetalhes em %APPDATA%\\Eco\\log.txt",
                "Eco", javax.swing.JOptionPane.ERROR_MESSAGE
            )
        } catch (_: Throwable) {}
        kotlin.system.exitProcess(1)
    }
}

object Win {
    var shown by mutableStateOf(true)
    var window: java.awt.Window? = null
    var state: androidx.compose.ui.window.WindowState? = null
    fun bringToFront() {
        shown = true
        state?.isMinimized = false
        window?.let { it.toFront(); it.requestFocus() }
    }
}

private fun realMain() {
    Log.w("iniciando (Java ${System.getProperty("java.version")}, ${System.getProperty("os.name")})")
    Thread.setDefaultUncaughtExceptionHandler { t, e -> Log.error("thread ${t.name}", e) }
    val shot = System.getenv("ECO_SCREENSHOT")
    if (shot != null) System.setProperty("skiko.renderApi", "SOFTWARE")
    if (shot == null && !SingleInstance.claim { Win.bringToFront() }) return
    // the screen's state is only touched on the screen's thread
    javax.swing.SwingUtilities.invokeAndWait {
        Settings.load()
        Board.load()
        Settings.push()
    }
    Audio.start()
    Hotkeys.init()
    Board.warmUp()
    Log.w("abrindo a janela (${Board.pads.size} sons)")

    application {
        val state = rememberWindowState(width = 1000.dp, height = 680.dp)
        fun quit() { Log.w("saindo"); Settings.save(); exitApplication(); kotlin.system.exitProcess(0) }
        val tray = isTraySupported && shot == null
        val trayState = rememberTrayState()
        if (tray) {
            Tray(
                state = trayState,
                icon = painterResource("img/icon.png"),
                tooltip = "Eco",
                onAction = { Win.bringToFront() },
                menu = {
                    Item("Abrir o Eco", onClick = { Win.bringToFront() })
                    Item("Parar todos os sons", onClick = { Audio.stopAll() })
                    Separator()
                    Item("Sair", onClick = { quit() })
                }
            )
        }
        if (Win.shown) {
            Window(
                // closing keeps Eco running near the clock, so the shortcuts keep working in games
                onCloseRequest = {
                    if (!tray) quit()
                    else {
                        Win.shown = false
                        Board.save()
                        if (!Settings.trayNoticeShown) {
                            Settings.trayNoticeShown = true; Settings.save()
                            trayState.sendNotification(Notification(
                                "O Eco continua ligado",
                                "Ele fica perto do relógio para os atalhos funcionarem no jogo. Para sair, clique com o botão direito no ícone."
                            ))
                        }
                        Thread { Thread.sleep(1500); System.gc() }.apply { isDaemon = true; start() }
                    }
                },
                title = "Eco",
                icon = painterResource("img/icon.png"),
                state = state,
                onPreviewKeyEvent = { handleKey(it) },
            ) {
                window.minimumSize = java.awt.Dimension(760, 480)
                SideEffect { Win.window = window; Win.state = state }
                LaunchedEffect(window) { TitleBar.applyDark(window) }
                EcoTheme { App(window) }
                if (shot != null) ScreenshotRunner(shot, window) { quit() }
            }
        }
    }
}

// ---------- screenshot check (build machine only) ----------

@Composable
private fun ScreenshotRunner(path: String, window: java.awt.Window, done: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(3000)
        // pretend two sounds are playing, at different points
        val now = System.currentTimeMillis()
        Board.pads.getOrNull(1)?.let { Audio.playing[it.id] = (now - 1200) to 2000L }
        Board.pads.getOrNull(4)?.let { Audio.playing[it.id] = (now - 300) to 1600L }
        delay(150)
        capture(window, path.replace(".png", "-1.png"))
        Audio.playing.clear()
        UiState.settingsOpen = true
        delay(1200)
        capture(window, path.replace(".png", "-2.png"))
        UiState.settingsOpen = false; UiState.guideOpen = true
        delay(1200)
        capture(window, path.replace(".png", "-3.png"))
        UiState.guideOpen = false
        UiState.editing = Board.pads.firstOrNull()
        delay(1200)
        capture(window, path.replace(".png", "-4.png"))
        UiState.editing = null
        UiState.toast("2 sons adicionados. Não deu: video.mp4: só dá para usar MP3 ou WAV")
        delay(800)
        capture(window, path.replace(".png", "-5.png"))
        done()
    }
}

private fun capture(w: java.awt.Window, path: String) {
    try {
        val r = java.awt.Rectangle(w.locationOnScreen, w.size)
        val img = java.awt.Robot().createScreenCapture(r)
        javax.imageio.ImageIO.write(img, "png", File(path))
    } catch (e: Exception) {
        System.err.println("screenshot failed: $e")
    }
}
