package app.ilha.overlay

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ilha.core.model.ActivityKind
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.GlyphKind
import app.ilha.core.model.Slot
import app.ilha.core.system.IconCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

object Palette {
    val Green = Color(0xFF30D158)
    val Red = Color(0xFFFF453A)
    val Blue = Color(0xFF0A84FF)
    val Orange = Color(0xFFFF9F0A)
    val Pink = Color(0xFFFF375F)
    val Surface = Color(0xFF1C1C1E)
    val Surface2 = Color(0xFF2C2C2E)
    val Dim = Color(0xFF9A9AA0)
    val Soft = Color(0xFFD1D1D6)
}

// ---------- texto ----------

/** Texto sem Material (a ilha tem tema próprio) e sem escala de fonte: o tamanho da ilha é fixo. */
@Composable
fun IText(
    text: String,
    size: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    weight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Unspecified,
    tabular: Boolean = false,
) {
    val fs = LocalDensity.current.fontScale
    BasicText(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = (size / fs).sp,
            fontWeight = weight,
            textAlign = align,
            fontFeatureSettings = if (tabular) "tnum" else null,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

fun fmtSeconds(total: Long): String {
    val s = total.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
}

// ---------- relógios baratos (só recompõem o que lê o estado) ----------

@Composable
fun rememberTicker(active: Boolean, intervalMs: Long, phaseMs: Long? = null): State<Long> {
    val t = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(active, intervalMs, phaseMs) {
        t.longValue = SystemClock.elapsedRealtime()
        if (!active) return@LaunchedEffect
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            t.longValue = now
            val wait = if (phaseMs != null) {
                intervalMs - (((now - phaseMs) % intervalMs) + intervalMs) % intervalMs + 2
            } else intervalMs
            delay(wait)
        }
    }
    return t
}

/** Fase em segundos, atualizada a `fps` (limitado de propósito). Lida só na fase de desenho. */
@Composable
fun rememberPhase(active: Boolean, fps: Int): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, fps) {
        if (!active || fps <= 0) return@LaunchedEffect
        val step = 1000L / fps
        while (isActive) {
            phase.floatValue = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f
            delay(step)
        }
    }
    return phase
}

@Composable
fun TimerText(t: Slot.Timer, size: Float, color: Color, weight: FontWeight = FontWeight.SemiBold) {
    val tick by rememberTicker(t.running, 1000L, t.base)
    val ms = when {
        !t.running -> t.frozenMs
        t.countDown -> t.base - tick
        else -> tick - t.base
    }
    val secs = if (t.countDown) (ms + 999) / 1000 else ms / 1000
    IText(fmtSeconds(secs), size, color = color, weight = weight, tabular = true)
}

// ---------- gestos ----------

enum class Swipe { UP, DOWN, LEFT, RIGHT }

@Composable
fun Modifier.islandGestures(onTap: () -> Unit, onLong: () -> Unit, onSwipe: (Swipe) -> Unit): Modifier {
    val tap = rememberUpdatedState(onTap)
    val long = rememberUpdatedState(onLong)
    val swipe = rememberUpdatedState(onSwipe)
    return this
        .pointerInput(Unit) {
            detectTapGestures(onTap = { tap.value() }, onLongPress = { long.value() })
        }
        .pointerInput(Unit) {
            val th = 22.dp.toPx()
            var dx = 0f
            var dy = 0f
            detectDragGestures(
                onDragStart = { dx = 0f; dy = 0f },
                onDragEnd = {
                    val vertical = abs(dy) >= abs(dx)
                    val s = when {
                        vertical && dy < -th -> Swipe.UP
                        vertical && dy > th -> Swipe.DOWN
                        !vertical && dx > th -> Swipe.RIGHT
                        !vertical && dx < -th -> Swipe.LEFT
                        else -> null
                    }
                    if (s != null) swipe.value(s)
                },
                onDragCancel = {},
            ) { change, amount ->
                change.consume()
                dx += amount.x
                dy += amount.y
            }
        }
}

/** Toque com leve "afundar" (spring), sem depender de ripple do Material. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(stiffness = 900f), label = "press")
    val cb = rememberUpdatedState(onClick)
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                onTap = { cb.value() },
            )
        }
}

// ---------- ícones ----------

@Composable
private fun VectorGlyph(v: ImageVector, tint: Color, size: Dp, modifier: Modifier) {
    Image(rememberVectorPainter(v), null, modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

@Composable
fun GlyphIcon(kind: GlyphKind, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    when (kind) {
        GlyphKind.PHONE -> VectorGlyph(Icons.Filled.Call, tint, size, modifier)
        GlyphKind.HANGUP -> VectorGlyph(Icons.Filled.Call, tint, size, modifier.rotate(135f))
        GlyphKind.BELL -> VectorGlyph(Icons.Filled.Notifications, tint, size, modifier)
        GlyphKind.MESSAGE -> VectorGlyph(Icons.Filled.Email, tint, size, modifier)
        GlyphKind.BELL_OFF -> Box(modifier.size(size)) {
            VectorGlyph(Icons.Filled.Notifications, tint, size, Modifier)
            Canvas(Modifier.matchParentSize()) {
                drawLine(tint, Offset(this.size.width * .16f, this.size.height * .14f), Offset(this.size.width * .84f, this.size.height * .86f),
                    strokeWidth = this.size.width * .09f, cap = StrokeCap.Round)
            }
        }
        else -> Canvas(modifier.size(size)) { drawGlyph(kind, tint) }
    }
}

private fun DrawScope.drawGlyph(kind: GlyphKind, c: Color) {
    val w = size.width
    val h = size.height
    val round = Stroke(width = w * .10f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val p = Path().apply { moveTo(w * x1, h * y1); lineTo(w * x2, h * y2); lineTo(w * x3, h * y3); close() }
        drawPath(p, c)
        drawPath(p, c, style = round)
    }
    when (kind) {
        GlyphKind.PLAY -> tri(.30f, .18f, .30f, .82f, .84f, .50f)
        GlyphKind.PAUSE -> {
            drawRoundRect(c, Offset(w * .22f, h * .16f), Size(w * .20f, h * .68f), CornerRadius(w * .06f))
            drawRoundRect(c, Offset(w * .58f, h * .16f), Size(w * .20f, h * .68f), CornerRadius(w * .06f))
        }
        GlyphKind.NEXT -> {
            tri(.14f, .22f, .14f, .78f, .62f, .50f)
            drawRoundRect(c, Offset(w * .72f, h * .20f), Size(w * .12f, h * .60f), CornerRadius(w * .04f))
        }
        GlyphKind.PREV -> {
            tri(.86f, .22f, .86f, .78f, .38f, .50f)
            drawRoundRect(c, Offset(w * .16f, h * .20f), Size(w * .12f, h * .60f), CornerRadius(w * .04f))
        }
        GlyphKind.BOLT -> {
            val p = Path().apply {
                moveTo(w * .58f, h * .04f); lineTo(w * .20f, h * .56f); lineTo(w * .46f, h * .56f)
                lineTo(w * .40f, h * .96f); lineTo(w * .80f, h * .40f); lineTo(w * .54f, h * .40f); close()
            }
            drawPath(p, c)
        }
        GlyphKind.BLUETOOTH -> {
            val p = Path().apply {
                moveTo(w * .26f, h * .30f); lineTo(w * .74f, h * .70f); lineTo(w * .50f, h * .92f)
                lineTo(w * .50f, h * .08f); lineTo(w * .74f, h * .30f); lineTo(w * .26f, h * .70f)
            }
            drawPath(p, c, style = round)
        }
        GlyphKind.TIMER -> {
            drawCircle(c, radius = w * .38f, center = Offset(w * .5f, h * .56f), style = round)
            drawLine(c, Offset(w * .5f, h * .56f), Offset(w * .5f, h * .36f), strokeWidth = w * .10f, cap = StrokeCap.Round)
            drawLine(c, Offset(w * .40f, h * .10f), Offset(w * .60f, h * .10f), strokeWidth = w * .10f, cap = StrokeCap.Round)
        }
        GlyphKind.NAVIGATION -> {
            val p = Path().apply {
                moveTo(w * .5f, h * .08f); lineTo(w * .84f, h * .90f); lineTo(w * .5f, h * .70f); lineTo(w * .16f, h * .90f); close()
            }
            drawPath(p, c)
        }
        GlyphKind.DOWNLOAD -> {
            drawLine(c, Offset(w * .5f, h * .12f), Offset(w * .5f, h * .62f), strokeWidth = w * .10f, cap = StrokeCap.Round)
            val p = Path().apply { moveTo(w * .28f, h * .44f); lineTo(w * .5f, h * .66f); lineTo(w * .72f, h * .44f) }
            drawPath(p, c, style = round)
            drawLine(c, Offset(w * .2f, h * .88f), Offset(w * .8f, h * .88f), strokeWidth = w * .10f, cap = StrokeCap.Round)
        }
        else -> Unit
    }
}

@Composable
fun KeyImage(key: String?, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    val bmp = remember(key) { key?.let { IconCache.get(it) } }
    val shape = RoundedCornerShape(corner)
    if (bmp != null) {
        Image(bmp, null, modifier.size(size).clip(shape), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.size(size).clip(shape).background(Palette.Surface2))
    }
}

// ---------- widgets animados ----------

/** Equalizador: lê a fase só no desenho (zero recomposição) e roda a no máximo `fps` quadros/s. */
@Composable
fun Equalizer(active: Boolean, fps: Int, color: Color, modifier: Modifier = Modifier) {
    val phase = rememberPhase(active, fps)
    val rest = remember { floatArrayOf(.45f, .8f, .6f, .35f) }
    Canvas(modifier) {
        val n = 4
        val gap = size.width * .12f
        val bw = (size.width - gap * (n - 1)) / n
        val t = phase.value
        for (i in 0 until n) {
            val f = if (active && fps > 0) .3f + .7f * (.5f + .5f * sin(t * (2.2f + i * .9f) + i * 1.7f)) else rest[i]
            val bh = size.height * f
            drawRoundRect(color, Offset(i * (bw + gap), (size.height - bh) / 2f), Size(bw, bh), CornerRadius(bw / 2f))
        }
    }
}

@Composable
fun ProgressRing(fraction: Float?, color: Color, diameter: Dp, motion: Motion, modifier: Modifier = Modifier, thickness: Dp = 3.dp) {
    val spin = rememberPhase(fraction == null, motion.spinnerFps)
    Canvas(modifier.size(diameter)) {
        val stroke = thickness.toPx()
        val inset = stroke / 2f
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        if (fraction != null) {
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round))
        } else {
            val angle = ((spin.value % 1.2f) / 1.2f) * 360f
            drawArc(color, angle, 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
    }
}

@Composable
fun LinearProgress(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val r = CornerRadius(size.height / 2f)
        drawRoundRect(Color(0x33FFFFFF), Offset.Zero, size, r)
        drawRoundRect(color, Offset.Zero, Size(size.width * fraction.coerceIn(0f, 1f), size.height), r)
    }
}

@Composable
fun MediaProgress(c: ExpandedContent.Media, onSeek: (Long) -> Unit) {
    val fast = rememberTicker(c.playing, 250L)
    Canvas(
        Modifier.fillMaxWidth().height(14.dp)
            .pointerInput(c.durationMs) {
                detectTapGestures { o -> if (c.durationMs > 0) onSeek((o.x / size.width * c.durationMs).toLong()) }
            },
    ) {
        val p = if (c.playing) c.positionMs + ((fast.value - c.positionAt) * c.speed).toLong() else c.positionMs
        val f = if (c.durationMs > 0) p.coerceIn(0L, c.durationMs).toFloat() / c.durationMs else 0f
        val th = 4.dp.toPx()
        val y = (size.height - th) / 2f
        val r = CornerRadius(th / 2f)
        drawRoundRect(Color(0x33FFFFFF), Offset(0f, y), Size(size.width, th), r)
        drawRoundRect(Color.White, Offset(0f, y), Size(size.width * f.coerceIn(0f, 1f), th), r)
    }
}

fun mediaPositionLabel(c: ExpandedContent.Media, now: Long): String {
    val p = if (c.playing) c.positionMs + ((now - c.positionAt) * c.speed).toLong() else c.positionMs
    val clamped = if (c.durationMs > 0) p.coerceIn(0L, c.durationMs) else max(0L, p)
    return fmtSeconds(clamped / 1000)
}
