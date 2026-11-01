package app.ilha.overlay

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import app.ilha.core.model.ActivityKind
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Quality
import app.ilha.core.model.Slot
import app.ilha.core.model.WindowBounds
import app.ilha.core.system.IslandGeometry
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Especificação de movimento por nível de qualidade (o PerformanceGovernor escolhe o nível). */
@Immutable
class Motion(
    val wExpand: AnimationSpec<Float>,
    val hExpand: AnimationSpec<Float>,
    val wCollapse: AnimationSpec<Float>,
    val hCollapse: AnimationSpec<Float>,
    val radius: AnimationSpec<Float>,
    val fadeOutMs: Int,
    val fadeInMs: Int,
    val fadeInDelayMs: Int,
    val contentScale: Boolean,
    val eqFps: Int,
    val spinnerFps: Int,
)

object MotionSpecs {
    fun of(q: Quality): Motion = when (q) {
        // Largura e altura com molas diferentes: é isso que dá o ar "líquido" do Dynamic Island.
        Quality.FULL -> Motion(
            wExpand = spring<Float>(dampingRatio = 0.70f, stiffness = 520f),
            hExpand = spring<Float>(dampingRatio = 0.74f, stiffness = 440f),
            wCollapse = spring<Float>(dampingRatio = 0.88f, stiffness = 680f),
            hCollapse = spring<Float>(dampingRatio = 0.90f, stiffness = 640f),
            radius = spring<Float>(dampingRatio = 1f, stiffness = 700f),
            fadeOutMs = 90, fadeInMs = 200, fadeInDelayMs = 70,
            contentScale = true, eqFps = 30, spinnerFps = 30,
        )
        Quality.BALANCED -> Motion(
            wExpand = spring<Float>(dampingRatio = 0.88f, stiffness = 600f),
            hExpand = spring<Float>(dampingRatio = 0.90f, stiffness = 560f),
            wCollapse = spring<Float>(dampingRatio = 1f, stiffness = 700f),
            hCollapse = spring<Float>(dampingRatio = 1f, stiffness = 700f),
            radius = spring<Float>(dampingRatio = 1f, stiffness = 700f),
            fadeOutMs = 70, fadeInMs = 150, fadeInDelayMs = 50,
            contentScale = false, eqFps = 15, spinnerFps = 15,
        )
        Quality.LITE -> {
            val t = tween<Float>(durationMillis = 180, easing = FastOutSlowInEasing)
            Motion(
                wExpand = t, hExpand = t, wCollapse = t, hCollapse = t, radius = t,
                fadeOutMs = 50, fadeInMs = 110, fadeInDelayMs = 30,
                contentScale = false, eqFps = 0, spinnerFps = 0,
            )
        }
    }
}

data class IslandSize(val w: Float, val h: Float, val r: Float)

/** Medidas em dp de cada estado, e os limites da janela (px) que o comportam. */
object IslandSizes {
    /** Folga para o rebote da mola não ser cortado pela janela. */
    private const val PAD_X = 12f
    private const val PAD_BOTTOM = 18f

    fun of(s: IslandUiState, g: IslandGeometry): IslandSize = when (s) {
        IslandUiState.Hidden -> IslandSize(g.holeW, g.holeH, g.holeH / 2f)
        IslandUiState.Idle -> IslandSize(g.idleW, g.bandH, g.bandH / 2f)
        is IslandUiState.Compact -> IslandSize(compactW(s.primary, g), g.bandH, g.bandH / 2f)
        is IslandUiState.Alert -> IslandSize(min(g.screenW - 28f, 344f), g.bandH + bodyH(s.activity, false), 34f)
        is IslandUiState.Expanded -> IslandSize(min(g.screenW - 16f, 384f), g.bandH + bodyH(s.activity, true), 38f)
    }

    fun windowBounds(s: IslandUiState, g: IslandGeometry): WindowBounds {
        val z = of(s, g)
        val d = g.density
        val w = ceil((z.w + 2 * PAD_X) * d).toInt()
        val h = ceil((z.h + PAD_BOTTOM) * d).toInt()
        val x = (g.centerXpx + g.offsetX * d - g.screenWpx / 2f).roundToInt()
        val y = max(0f, g.centerYpx + g.offsetY * d - g.bandH * d / 2f).roundToInt()
        return WindowBounds(x, y, w, h)
    }

    private fun slotW(slot: Slot): Float = when (slot) {
        is Slot.Icon -> 22f
        is Slot.Text -> (slot.value.length * 7.4f).coerceIn(14f, 70f)
        is Slot.Timer -> 46f
        is Slot.Progress -> 20f
        is Slot.Glyph -> 20f
        Slot.Waveform -> 20f
        Slot.Empty -> 0f
    }

    /** Simétrico em volta do furo, para a ilha ficar sempre centralizada nele. */
    private fun compactW(a: LiveActivity, g: IslandGeometry): Float {
        val side = max(slotW(a.leading), slotW(a.trailing)) + 22f
        return max(g.idleW, (g.holeW + 2f * side) * g.widthScale)
    }

    /** Altura do corpo abaixo da faixa do furo. */
    fun bodyH(a: LiveActivity, expanded: Boolean): Float = when (val c = a.expanded) {
        is ExpandedContent.Media -> if (expanded) 140f else 60f
        is ExpandedContent.Call -> if (c.ringing) 64f else if (expanded) 68f else 56f
        is ExpandedContent.Message -> if (expanded && c.quickReplies) 108f else if (expanded) 74f else 60f
        is ExpandedContent.Timer -> if (expanded) 84f else 60f
        is ExpandedContent.Charging -> if (expanded) 72f else 60f
        is ExpandedContent.Progress -> if (expanded) (if (c.fraction != null) 88f else 70f) else 62f
        is ExpandedContent.Info -> if (expanded) 66f else 56f
    }
}

fun accentFor(kind: ActivityKind): androidx.compose.ui.graphics.Color = when (kind) {
    ActivityKind.CALL, ActivityKind.CHARGING, ActivityKind.NAVIGATION -> Palette.Green
    ActivityKind.ALARM, ActivityKind.TIMER, ActivityKind.RINGER -> Palette.Orange
    ActivityKind.MEDIA -> Palette.Pink
    ActivityKind.MESSAGE, ActivityKind.BLUETOOTH, ActivityKind.LIVE_UPDATE, ActivityKind.PROGRESS -> Palette.Blue
}
