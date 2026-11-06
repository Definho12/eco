package eco

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Neutral dark base; the mint accent only marks what's playing, the main button and "it's working". */
object C {
    val Bg = Color(0xFF101113)
    val Bar = Color(0xFF0C0D0E)
    val Pad = Color(0xFF17181B)
    val PadHover = Color(0xFF1C1E22)
    val Surface = Color(0xFF16171A)
    val Field = Color(0xFF121315)
    val Line = Color(0xFF26282C)
    val Border = Color(0xFF32353A)
    val Text = Color(0xFFE8E9EC)
    val Sub = Color(0xFF9A9EA6)
    val Faint = Color(0xFF62666D)
    val Accent = Color(0xFF7DD3C0)
    val AccentDim = Color(0xFF7DD3C0).copy(alpha = 0.14f)
    val OnAccent = Color(0xFF0B1A17)
    val Bad = Color(0xFFF08A7E)
    val Warn = Color(0xFFE9C46A)
}

object F {
    private fun font(res: String, w: FontWeight) = try { Font("fonts/$res", w) } catch (_: Exception) { null }
    val Inter: FontFamily by lazy {
        val fs = listOfNotNull(
            font("inter_regular.ttf", FontWeight.Normal),
            font("inter_medium.ttf", FontWeight.Medium),
            font("inter_semibold.ttf", FontWeight.SemiBold),
        )
        if (fs.isEmpty()) FontFamily.SansSerif else FontFamily(fs)
    }
}

@Composable
fun EcoTheme(content: @Composable () -> Unit) {
    val base = Typography()
    fun TextStyle.inter() = copy(fontFamily = F.Inter)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, surface = C.Surface,
            onSurface = C.Text, onBackground = C.Text, surfaceVariant = C.Pad, outline = C.Border,
        ),
        typography = base.copy(
            bodyLarge = base.bodyLarge.inter(), bodyMedium = base.bodyMedium.inter(), bodySmall = base.bodySmall.inter(),
            labelLarge = base.labelLarge.inter(), labelMedium = base.labelMedium.inter(), labelSmall = base.labelSmall.inter(),
            titleMedium = base.titleMedium.inter(), titleSmall = base.titleSmall.inter(),
        ),
        content = content,
    )
}

/** Small line icons, drawn by hand so the app stays light. */
enum class Ico { Plus, Stop, Gear, Mic, MicOff, Headphones, Dots, Close, Wave, Check, Alert }

@Composable
fun Icon(i: Ico, color: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIco(i, color) }
}

fun DrawScope.drawIco(i: Ico, color: Color) {
    val s = size.minDimension
    val w = s * 0.09f
    val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, p(x1, y1), p(x2, y2), w, StrokeCap.Round)
    when (i) {
        Ico.Plus -> { line(0.5f, 0.2f, 0.5f, 0.8f); line(0.2f, 0.5f, 0.8f, 0.5f) }
        Ico.Close -> { line(0.25f, 0.25f, 0.75f, 0.75f); line(0.75f, 0.25f, 0.25f, 0.75f) }
        Ico.Stop -> drawRoundRect(color, p(0.25f, 0.25f), Size(0.5f * s, 0.5f * s), androidx.compose.ui.geometry.CornerRadius(0.08f * s))
        Ico.Dots -> for (x in listOf(0.25f, 0.5f, 0.75f)) drawCircle(color, s * 0.065f, p(x, 0.5f))
        Ico.Check -> { val path = Path().apply { moveTo(0.22f * s, 0.52f * s); lineTo(0.42f * s, 0.72f * s); lineTo(0.78f * s, 0.3f * s) }; drawPath(path, color, style = st) }
        Ico.Alert -> { drawCircle(color, 0.38f * s, p(0.5f, 0.5f), style = st); line(0.5f, 0.3f, 0.5f, 0.55f); drawCircle(color, w * 0.7f, p(0.5f, 0.7f)) }
        Ico.Gear -> {
            drawCircle(color, 0.14f * s, p(0.5f, 0.5f), style = st)
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                val c = Math.cos(a).toFloat(); val sn = Math.sin(a).toFloat()
                line(0.5f + 0.27f * c, 0.5f + 0.27f * sn, 0.5f + 0.4f * c, 0.5f + 0.4f * sn)
            }
            drawCircle(color, 0.27f * s, p(0.5f, 0.5f), style = Stroke(w * 0.8f))
        }
        Ico.Mic, Ico.MicOff -> {
            drawRoundRect(color, p(0.37f, 0.12f), Size(0.26f * s, 0.46f * s), androidx.compose.ui.geometry.CornerRadius(0.13f * s), style = st)
            drawArc(color, 0f, 180f, false, p(0.24f, 0.3f), Size(0.52f * s, 0.42f * s), style = st)
            line(0.5f, 0.72f, 0.5f, 0.88f)
            if (i == Ico.MicOff) line(0.18f, 0.15f, 0.82f, 0.85f)
        }
        Ico.Headphones -> {
            drawArc(color, 180f, 180f, false, p(0.17f, 0.18f), Size(0.66f * s, 0.66f * s), style = st)
            drawRoundRect(color, p(0.14f, 0.5f), Size(0.17f * s, 0.3f * s), androidx.compose.ui.geometry.CornerRadius(0.06f * s))
            drawRoundRect(color, p(0.69f, 0.5f), Size(0.17f * s, 0.3f * s), androidx.compose.ui.geometry.CornerRadius(0.06f * s))
        }
        Ico.Wave -> {
            // the Eco mark: a dot with two arcs going out
            drawCircle(color, 0.09f * s, p(0.22f, 0.5f))
            drawArc(color, -50f, 100f, false, p(0.0f, 0.28f), Size(0.44f * s, 0.44f * s), style = st)
            drawArc(color, -50f, 100f, false, p(-0.2f, 0.08f), Size(0.84f * s, 0.84f * s), style = st)
        }
    }
}
