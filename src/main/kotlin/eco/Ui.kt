@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package eco

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File

// ---------- screen state ----------

/** What a recorded shortcut is for: one pad, or "stop everything". */
sealed interface RecTarget {
    class ForPad(val pad: Pad) : RecTarget
    object Stop : RecTarget
}

object UiState {
    var settingsOpen by mutableStateOf(false)
    var guideOpen by mutableStateOf(false)
    var editing by mutableStateOf<Pad?>(null)
    var recording by mutableStateOf<RecTarget?>(null)
    var recordHint by mutableStateOf<String?>(null)
    var toast by mutableStateOf<String?>(null)
    var toastId by mutableStateOf(0)
    var adding by mutableStateOf(false)
    var dragOver by mutableStateOf(false)

    fun toast(msg: String) { toast = msg; toastId++ }

    fun startRecording(t: RecTarget) { recording = t; recordHint = null; Hotkeys.suspend(true) }
    fun stopRecording() { recording = null; recordHint = null; Hotkeys.suspend(false) }

    fun closeTop(): Boolean = when {
        recording != null -> { stopRecording(); true }
        editing != null -> { editing = null; Board.save(); true }
        guideOpen -> { guideOpen = false; true }
        settingsOpen -> { settingsOpen = false; true }
        else -> false
    }
}

/** Keys pressed in the window: recording a shortcut, or Esc to close things. */
fun handleKey(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val rec = UiState.recording
    if (rec != null) {
        if (e.key == Key.Escape) { UiState.stopRecording(); return true }
        val vk = e.key.nativeKeyCode
        if (vk in listOf(16, 17, 18, 20, 524, 525, 0)) return true // just a modifier
        val combo = Combo(e.isCtrlPressed, e.isAltPressed, e.isShiftPressed, vk)
        if (!combo.hasModifier && !Combo.okAlone(vk)) {
            UiState.recordHint = "Use Ctrl, Alt ou Shift junto com a tecla (sozinhas só F1–F12 e o teclado numérico)"
            return true
        }
        when (rec) {
            is RecTarget.ForPad -> { Board.claimCombo(combo, rec.pad); rec.pad.hotkey = combo; Board.save() }
            RecTarget.Stop -> { Board.claimCombo(combo, null); Settings.stopCombo = combo; Settings.save(); Board.save() }
        }
        UiState.stopRecording()
        return true
    }
    if (e.key == Key.Escape) return UiState.closeTop()
    return false
}

fun addSounds(files: List<File>) {
    if (files.isEmpty() || UiState.adding) return
    UiState.adding = true
    Thread({
        val r = Board.addFiles(files)
        javax.swing.SwingUtilities.invokeLater {
            UiState.adding = false
            UiState.toast(when {
                r.failed.isEmpty() -> if (r.added == 1) "Som adicionado" else "${r.added} sons adicionados"
                r.added == 0 -> "Não deu: " + r.failed.joinToString("; ")
                else -> "${r.added} adicionados. Não deu: " + r.failed.joinToString("; ")
            })
        }
    }, "eco-add").apply { isDaemon = true; start() }
}

fun pickFiles(window: java.awt.Window?) {
    val d = java.awt.FileDialog(window as? java.awt.Frame, "Escolha os sons (MP3 ou WAV)", java.awt.FileDialog.LOAD)
    d.isMultipleMode = true
    d.file = "*.mp3;*.wav"
    d.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in Decode.extensions }
    d.isVisible = true
    addSounds(d.files?.toList() ?: emptyList())
}

// ---------- the window ----------

@Composable
fun App(window: java.awt.Window?) {
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { UiState.dragOver = true }
            override fun onExited(event: DragAndDropEvent) { UiState.dragOver = false }
            override fun onEnded(event: DragAndDropEvent) { UiState.dragOver = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                UiState.dragOver = false
                val data = event.dragData()
                if (data !is DragData.FilesList) return false
                val files = data.readFiles().mapNotNull { u -> try { File(java.net.URI(u)) } catch (_: Exception) { null } }
                addSounds(files)
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize().background(C.Bg).dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)) {
        Column(Modifier.fillMaxSize()) {
            Header(window)
            Box(Modifier.weight(1f).fillMaxWidth()) { Pads(window) }
            Footer()
        }
        if (UiState.dragOver) {
            Box(Modifier.fillMaxSize().padding(12.dp).clip(RoundedCornerShape(18.dp)).background(C.Bg.copy(alpha = 0.85f))
                .border(2.dp, C.Accent, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
                T("Solte para adicionar", 20.sp, C.Accent, FontWeight.Medium)
            }
        }
        if (UiState.settingsOpen) SettingsPanel()
        if (UiState.guideOpen) GuideDialog()
        UiState.editing?.let { EditDialog(it) }
        Toast()
    }
}

@Composable
fun T(text: String, size: TextUnit = 14.sp, color: Color = C.Text, weight: FontWeight = FontWeight.Normal,
      modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, lineHeight: TextUnit = TextUnit.Unspecified) {
    Text(text, modifier, color = color, fontSize = size, fontWeight = weight, fontFamily = F.Inter, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis, lineHeight = lineHeight)
}

@Composable
fun Btn(text: String, icon: Ico? = null, primary: Boolean = false, sub: String? = null, enabled: Boolean = true,
        color: Color? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val bg = when { primary -> if (hover) C.Accent.copy(alpha = 0.9f) else C.Accent; hover -> C.PadHover; else -> Color.Transparent }
    val fg = color ?: if (primary) C.OnAccent else C.Text
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(10.dp)).background(bg)
            .then(if (primary) Modifier else Modifier.border(1.dp, C.Border, RoundedCornerShape(10.dp)))
            .hoverable(src).clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, fg, 16.dp); Spacer(Modifier.width(8.dp)) }
        T(text, 13.5.sp, fg, FontWeight.Medium)
        if (sub != null) { Spacer(Modifier.width(8.dp)); T(sub, 11.5.sp, if (primary) C.OnAccent.copy(alpha = 0.6f) else C.Faint) }
    }
}

@Composable
fun IconBtn(icon: Ico, tint: Color = C.Sub, size: Dp = 34.dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    Box(Modifier.size(size).clip(RoundedCornerShape(9.dp)).background(if (hover) C.PadHover else Color.Transparent)
        .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, if (hover) C.Text else tint, size * 0.52f)
    }
}

// ---------- header ----------

@Composable
private fun Header(window: java.awt.Window?) {
    Row(Modifier.fillMaxWidth().height(64.dp).background(C.Bar).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Ico.Wave, C.Accent, 22.dp)
        Spacer(Modifier.width(10.dp))
        T("Eco", 18.sp, C.Text, FontWeight.SemiBold)
        Spacer(Modifier.width(20.dp))
        StatusChip()
        Spacer(Modifier.weight(1f))
        Btn("Parar tudo", Ico.Stop, sub = Settings.stopCombo?.label()) { Audio.stopAll() }
        Spacer(Modifier.width(10.dp))
        Btn(if (UiState.adding) "Adicionando…" else "Adicionar sons", Ico.Plus, primary = true, enabled = !UiState.adding) { pickFiles(window) }
        Spacer(Modifier.width(6.dp))
        IconBtn(Ico.Gear) { UiState.settingsOpen = !UiState.settingsOpen }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
}

@Composable
private fun StatusChip() {
    val (dot, text) = when (Audio.discordStatus) {
        Audio.Status.Ok -> C.Accent to "Ligado ao Discord"
        Audio.Status.Missing -> C.Bad to "Cabo virtual não encontrado"
        else -> C.Faint to "Preparando o som…"
    }
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(if (hover) C.PadHover else C.Surface).border(1.dp, C.Line, RoundedCornerShape(20.dp))
        .hoverable(src).clickable(interactionSource = src, indication = null) { UiState.guideOpen = true }
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        T(text, 12.5.sp, C.Sub)
        if (Audio.discordStatus == Audio.Status.Missing) { Spacer(Modifier.width(8.dp)); T("Ver como ligar", 12.5.sp, C.Text, FontWeight.Medium) }
    }
}

// ---------- pads ----------

@Composable
private fun Pads(window: java.awt.Window?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyPlaying = Audio.playing.isNotEmpty()
    LaunchedEffect(anyPlaying) {
        while (Audio.playing.isNotEmpty()) {
            now = System.currentTimeMillis()
            // forget pads whose time is up (in case the sound had nowhere to play)
            Audio.playing.entries.filter { now - it.value.first > it.value.second + 300 }.forEach { Audio.playing.remove(it.key) }
            delay(33)
        }
    }
    if (Board.pads.isEmpty()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Ico.Wave, C.Faint, 40.dp)
            Spacer(Modifier.height(14.dp))
            T("Nenhum som ainda", 17.sp, C.Text, FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            T("Adicione arquivos MP3 ou WAV, ou arraste para cá", 13.5.sp, C.Sub)
            Spacer(Modifier.height(18.dp))
            Btn("Adicionar sons", Ico.Plus, primary = true) { pickFiles(window) }
        }
        return
    }
    LazyVerticalGrid(
        GridCells.Adaptive(176.dp), Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(Board.pads, key = { it.id }) { p ->
            val prog = Audio.playing[p.id]?.let { (start, len) -> if (len <= 0) 1f else ((now - start).toFloat() / len).coerceIn(0f, 1f) }
            PadCard(p, prog)
        }
    }
}

@Composable
private fun PadCard(p: Pad, progress: Float?) {
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(14.dp)
    val playing = progress != null
    Box(
        Modifier.fillMaxWidth().height(108.dp).clip(shape).background(if (hover) C.PadHover else C.Pad)
            .border(if (playing) 1.5.dp else 1.dp, if (playing) C.Accent else C.Line, shape)
            .hoverable(src)
            .pointerInput(p.id) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type == PointerEventType.Press && e.buttons.isSecondaryPressed) UiState.editing = p
                    }
                }
            }
            .clickable(interactionSource = src, indication = null) { Board.play(p) },
    ) {
        if (progress != null) {
            // the pad fills up while it plays
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(C.AccentDim))
            Box(Modifier.align(Alignment.BottomStart).height(3.dp).fillMaxWidth(progress).background(C.Accent))
        }
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp)) {
            Row {
                T(p.name, 15.sp, if (playing) C.Accent else C.Text, FontWeight.Medium, Modifier.weight(1f).padding(end = 4.dp), maxLines = 2, lineHeight = 19.sp)
                if (hover) IconBtn(Ico.Dots, C.Sub, 26.dp) { UiState.editing = p } else Spacer(Modifier.size(26.dp))
            }
            Spacer(Modifier.weight(1f))
            val prob = p.problem
            val combo = p.hotkey
            when {
                prob != null -> T(prob, 11.5.sp, C.Bad, maxLines = 1)
                combo != null && Hotkeys.taken[combo.encode()] == true -> T("${combo.label()} · já usado por outro programa", 11.5.sp, C.Warn, maxLines = 1)
                combo != null -> T(combo.label(), 11.5.sp, C.Faint, maxLines = 1)
            }
        }
    }
}

// ---------- footer ----------

@Composable
private fun Footer() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(C.Line))
    Row(Modifier.fillMaxWidth().height(66.dp).background(C.Bar).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        // microphone
        IconBtn(if (Settings.micOn) Ico.Mic else Ico.MicOff, if (Settings.micOn) C.Text else C.Bad) { Settings.micOn = !Settings.micOn; Settings.save() }
        Spacer(Modifier.width(8.dp))
        Column {
            val micMissing = Settings.micOn && Audio.discordStatus == Audio.Status.Ok && Audio.micStatus == Audio.Status.Missing
            T(when { !Settings.micOn -> "Microfone desligado"; micMissing -> "Microfone não encontrado"; else -> "Seu microfone" }, 12.5.sp,
                when { !Settings.micOn -> C.Bad; micMissing -> C.Warn; else -> C.Sub })
            Spacer(Modifier.height(5.dp))
            MicMeter()
        }
        Spacer(Modifier.width(28.dp))
        VolumeGroup("No Discord", Settings.discordVolume) { Settings.discordVolume = it; Settings.push() }
        Spacer(Modifier.width(28.dp))
        IconBtn(Ico.Headphones, if (Settings.monitorOn) C.Text else C.Faint) { Settings.monitorOn = !Settings.monitorOn; Settings.save() }
        Spacer(Modifier.width(8.dp))
        VolumeGroup(if (Settings.monitorOn) "Nos seus fones" else "Fones desligados", Settings.monitorVolume, Settings.monitorOn) { Settings.monitorVolume = it; Settings.push() }
        Spacer(Modifier.weight(1f))
        T("Botão direito num som para editar", 12.sp, C.Faint)
    }
}

@Composable
private fun MicMeter() {
    var level by remember { mutableStateOf(0f) }
    LaunchedEffect(Settings.micOn) {
        while (true) {
            level = (level * 0.6f).coerceAtLeast(kotlin.math.sqrt(Audio.micLevel.coerceIn(0f, 1f)) * 1.6f).coerceAtMost(1f)
            delay(60)
        }
    }
    val ok = Settings.micOn && Audio.micStatus == Audio.Status.Ok
    Box(Modifier.width(110.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(C.Line)) {
        if (ok) Box(Modifier.fillMaxHeight().fillMaxWidth(level).background(C.Accent))
    }
}

@Composable
private fun VolumeGroup(label: String, value: Float, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(label, 12.5.sp, C.Sub)
            Spacer(Modifier.width(6.dp))
            T("${(value * 100).toInt()}%", 12.sp, C.Faint)
        }
        ThinSlider(value, onChange, Modifier.width(150.dp), enabled = enabled, onFinished = { Settings.save() })
    }
}

// ---------- dialogs ----------

@Composable
private fun Scrim(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose), content = content)
}

@Composable
private fun Card(width: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.width(width).clip(RoundedCornerShape(16.dp)).background(C.Surface).border(1.dp, C.Border, RoundedCornerShape(16.dp))
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) { content() }
}

@Composable
private fun DialogTitle(text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        T(text, 17.sp, C.Text, FontWeight.SemiBold, Modifier.weight(1f))
        IconBtn(Ico.Close, C.Sub, 30.dp, onClose)
    }
}

@Composable
private fun Label(text: String) = T(text.uppercase(), 11.sp, C.Faint, FontWeight.Medium)

@Composable
private fun EditDialog(p: Pad) {
    var confirmRemove by remember(p.id) { mutableStateOf(false) }
    Scrim({ if (UiState.recording == null) { UiState.editing = null; Board.save() } }) {
        Card(440.dp, Modifier.align(Alignment.Center)) {
            Column(Modifier.padding(22.dp)) {
                DialogTitle("Editar som") { UiState.stopRecording(); UiState.editing = null; Board.save() }
                Spacer(Modifier.height(18.dp))
                Label("Nome")
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    p.name, { p.name = it.take(40) }, singleLine = true,
                    textStyle = TextStyle(color = C.Text, fontSize = 15.sp, fontFamily = F.Inter),
                    cursorBrush = SolidColor(C.Accent),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Field)
                        .border(1.dp, C.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                )
                Spacer(Modifier.height(18.dp))
                Label("Volume deste som")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ThinSlider(p.volume / 1.5f, { p.volume = (it * 1.5f).coerceAtLeast(0.05f) }, Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    T("${(p.volume * 100).toInt()}%", 12.5.sp, C.Sub, modifier = Modifier.width(42.dp))
                    Btn("Testar") { Board.play(p) }
                }
                Spacer(Modifier.height(14.dp))
                Label("Atalho")
                Spacer(Modifier.height(8.dp))
                ShortcutRow(p.hotkey, (UiState.recording as? RecTarget.ForPad)?.pad === p,
                    onRecord = { UiState.startRecording(RecTarget.ForPad(p)) }, onClear = { p.hotkey = null; Board.save() })
                p.problem?.let { Spacer(Modifier.height(12.dp)); T("Problema: $it", 12.5.sp, C.Bad) }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Btn(if (confirmRemove) "Clique de novo para remover" else "Remover", color = C.Bad) {
                        if (confirmRemove) { UiState.editing = null; Board.remove(p) } else confirmRemove = true
                    }
                    Spacer(Modifier.weight(1f))
                    val i = Board.pads.indexOf(p)
                    IconBtnText("←", enabled = i > 0) { Board.move(i, i - 1) }
                    IconBtnText("→", enabled = i < Board.pads.size - 1) { Board.move(i, i + 1) }
                    Spacer(Modifier.width(10.dp))
                    Btn("Pronto", primary = true) { UiState.stopRecording(); UiState.editing = null; Board.save() }
                }
            }
        }
    }
}

@Composable
private fun IconBtnText(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        T(text, 16.sp, if (enabled) C.Sub else C.Line)
    }
}

@Composable
private fun ShortcutRow(combo: Combo?, recording: Boolean, onRecord: () -> Unit, onClear: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(C.Field)
                .border(1.dp, if (recording) C.Accent else C.Border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart) {
                when {
                    recording -> T("Aperte a combinação…  (Esc cancela)", 13.5.sp, C.Accent)
                    combo != null -> T(combo.label(), 14.sp, C.Text, FontWeight.Medium)
                    else -> T("Nenhum", 14.sp, C.Faint)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (recording) Btn("Cancelar") { UiState.stopRecording() }
            else {
                Btn(if (combo == null) "Gravar" else "Trocar", onClick = onRecord)
                if (combo != null) { Spacer(Modifier.width(6.dp)); Btn("Tirar", onClick = onClear) }
            }
        }
        val hint = UiState.recordHint
        if (recording && hint != null) { Spacer(Modifier.height(6.dp)); T(hint, 12.sp, C.Warn) }
        if (!recording && combo != null && Hotkeys.taken[combo.encode()] == true) {
            Spacer(Modifier.height(6.dp)); T("Outro programa já usa essa combinação. Escolha outra.", 12.sp, C.Warn)
        }
    }
}

@Composable
private fun SettingsPanel() {
    val outs = remember { Audio.outputs() }
    val ins = remember { Audio.inputs() }
    Scrim({ if (UiState.recording == null) UiState.settingsOpen = false }) {
        Card(440.dp, Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = 16.dp)) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState())) {
                DialogTitle("Ajustes") { UiState.stopRecording(); UiState.settingsOpen = false }
                Spacer(Modifier.height(16.dp))
                Label("Para onde vai o som")
                Spacer(Modifier.height(10.dp))
                DevicePick("Saída para o Discord", Settings.discordDevice, "Automático (procura o cabo virtual)", outs) {
                    Settings.discordDevice = it; Settings.save(); Audio.reopen()
                }
                Spacer(Modifier.height(10.dp))
                DevicePick("Seu microfone", Settings.micDevice, "Padrão do Windows", ins.filter { !it.contains("CABLE Output", true) }) {
                    Settings.micDevice = it; Settings.save(); Audio.reopen()
                }
                Spacer(Modifier.height(10.dp))
                DevicePick("Seus fones / caixas", Settings.monitorDevice, "Padrão do Windows", outs.filter { !it.contains("CABLE", true) }) {
                    Settings.monitorDevice = it; Settings.save(); Audio.reopen()
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Btn("Procurar de novo") { Audio.reopen(); UiState.toast("Procurando os aparelhos de som…") }
                    Spacer(Modifier.width(8.dp))
                    Btn("Como ligar ao Discord") { UiState.guideOpen = true }
                }
                Spacer(Modifier.height(22.dp))
                Label("Atalho para parar tudo")
                Spacer(Modifier.height(8.dp))
                ShortcutRow(Settings.stopCombo, UiState.recording == RecTarget.Stop,
                    onRecord = { UiState.startRecording(RecTarget.Stop) }, onClear = { Settings.stopCombo = null; Settings.save(); Board.save() })
                Spacer(Modifier.height(8.dp))
                T("Os atalhos funcionam mesmo dentro do jogo. O de cada som fica no botão direito do som.", 12.5.sp, C.Sub, lineHeight = 17.sp)
                Spacer(Modifier.height(22.dp))
                Label("Seus sons")
                Spacer(Modifier.height(8.dp))
                T("O Eco guarda uma cópia de cada som. Os arquivos que você escolheu nunca são mexidos.", 12.5.sp, C.Sub, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                Btn("Abrir a pasta dos sons") { try { java.awt.Desktop.getDesktop().open(AppDirs.sounds) } catch (_: Exception) {} }
            }
        }
    }
}

@Composable
private fun DevicePick(label: String, current: String, autoLabel: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        T(label, 12.5.sp, C.Sub)
        Spacer(Modifier.height(5.dp))
        Box {
            Row(Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(10.dp)).background(C.Field)
                .border(1.dp, C.Border, RoundedCornerShape(10.dp)).clickable { open = true }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                T(current.ifEmpty { autoLabel }, 13.5.sp, C.Text, modifier = Modifier.weight(1f), maxLines = 1)
                T("▾", 13.sp, C.Faint)
            }
            DropdownMenu(open, { open = false }, Modifier.background(C.Surface).widthIn(min = 360.dp)) {
                DropdownMenuItem({ T(autoLabel, 13.5.sp, if (current.isEmpty()) C.Accent else C.Text) }, { open = false; onPick("") })
                for (o in options) DropdownMenuItem({ T(o, 13.5.sp, if (o == current) C.Accent else C.Text) }, { open = false; onPick(o) })
            }
        }
    }
}

@Composable
private fun GuideDialog() {
    Scrim({ UiState.guideOpen = false }) {
        Card(580.dp, Modifier.align(Alignment.Center)) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                DialogTitle("Ligar o Eco ao Discord (uma vez só)") { UiState.guideOpen = false }
                Spacer(Modifier.height(10.dp))
                val ok = Audio.discordStatus == Audio.Status.Ok
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (ok) Ico.Check else Ico.Alert, if (ok) C.Accent else C.Bad, 16.dp)
                    Spacer(Modifier.width(8.dp))
                    T(if (ok) "Cabo virtual encontrado: ${Audio.discordDevice ?: ""}" else "Cabo virtual ainda não encontrado", 13.sp, if (ok) C.Accent else C.Bad, maxLines = 1)
                }
                Spacer(Modifier.height(16.dp))
                Step(1, "Instale o cabo virtual (VB-CABLE, grátis)",
                    "Baixe o .zip no site, extraia, clique com o botão direito em VBCABLE_Setup_x64.exe → Executar como administrador → Install Driver. Depois reinicie o PC.") {
                    Btn("Abrir o site do VB-CABLE") { try { java.awt.Desktop.getDesktop().browse(java.net.URI("https://vb-audio.com/Cable/")) } catch (_: Exception) {} }
                }
                Step(2, "No Discord, escolha o cabo como microfone",
                    "Configurações → Voz e vídeo → Dispositivo de entrada: CABLE Output (VB-Audio Virtual Cable). Não precisa mudar nada no som do Windows.")
                Step(3, "Desligue a supressão de ruído do Discord",
                    "Ainda em Voz e vídeo, deixe a Supressão de ruído em Nenhuma. Senão o Discord apaga os sons achando que são barulho. Se ainda cortar, desligue também o Cancelamento de eco.")
                Step(4, "Pronto",
                    "Fale no microfone: a barrinha aqui embaixo mexe e seus amigos ouvem sua voz e os sons. Se você usa \"Pressionar para falar\", os sons só passam enquanto segura a tecla.")
                Spacer(Modifier.height(8.dp))
                Row {
                    Btn("Procurar o cabo de novo") { Audio.reopen(); UiState.toast("Procurando o cabo virtual…") }
                    Spacer(Modifier.weight(1f))
                    Btn("Fechar", primary = true) { UiState.guideOpen = false }
                }
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.padding(bottom = 16.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).border(1.dp, C.Border, CircleShape), contentAlignment = Alignment.Center) {
            T("$n", 12.sp, C.Sub, FontWeight.Medium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            T(title, 14.5.sp, C.Text, FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            T(body, 13.sp, C.Sub, lineHeight = 18.sp)
            if (extra != null) { Spacer(Modifier.height(8.dp)); extra() }
        }
    }
}

@Composable
private fun BoxScope.Toast() {
    val msg = UiState.toast
    LaunchedEffect(UiState.toastId) { if (msg != null) { delay(4500); UiState.toast = null } }
    AnimatedVisibility(msg != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 84.dp), enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.widthIn(max = 640.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF24262B)).border(1.dp, C.Border, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 11.dp)) {
            T(msg ?: "", 13.5.sp, C.Text, lineHeight = 18.sp)
        }
    }
}

/** A thin, quiet slider (0..1). */
@Composable
fun ThinSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, onFinished: () -> Unit = {}) {
    var widthPx by remember { mutableStateOf(1f) }
    val src = remember { MutableInteractionSource() }
    val hover by src.collectIsHoveredAsState()
    fun at(x: Float) = (x / widthPx).coerceIn(0f, 1f)
    androidx.compose.foundation.Canvas(
        modifier.height(20.dp).hoverable(src)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .then(if (!enabled) Modifier else Modifier
                .pointerInput(Unit) { detectTapGestures { onChange(at(it.x)); onFinished() } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = onFinished, onDragCancel = onFinished) { ch, _ -> onChange(at(ch.position.x)) }
                })
    ) {
        val r = 2.dp.toPx(); val y = size.height / 2
        val x = value.coerceIn(0f, 1f) * size.width
        drawRoundRect(C.Line, androidx.compose.ui.geometry.Offset(0f, y - r), androidx.compose.ui.geometry.Size(size.width, 2 * r), androidx.compose.ui.geometry.CornerRadius(r))
        drawRoundRect(if (enabled) C.Sub else C.Border, androidx.compose.ui.geometry.Offset(0f, y - r), androidx.compose.ui.geometry.Size(x, 2 * r), androidx.compose.ui.geometry.CornerRadius(r))
        if (enabled) drawCircle(C.Text, (if (hover) 7.dp else 6.dp).toPx(), androidx.compose.ui.geometry.Offset(x.coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), y))
    }
}
