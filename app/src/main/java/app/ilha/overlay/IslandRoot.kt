package app.ilha.overlay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.ilha.core.model.IslandEvent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Quality
import app.ilha.core.model.contentKey
import app.ilha.core.system.IslandGeometry
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Estado que o hospedeiro entrega ao Compose. A janela só desenha; as decisões estão no motor. */
@Stable
class IslandHostState {
    var ui by mutableStateOf<IslandUiState>(IslandUiState.Hidden)
    var geo by mutableStateOf(IslandGeometry.DEFAULT)
    var quality by mutableStateOf(Quality.FULL)
    var haptics by mutableStateOf(true)
}

/**
 * Por que é fluido a 120 Hz:
 *  - largura, altura e raio são Animatables lidos SÓ na fase de desenho (drawBehind): sem recomposição
 *    e sem relayout por quadro;
 *  - o conteúdo tem layout fixo no tamanho final do estado e só muda alpha/escala (graphicsLayer,
 *    ModulateAlpha: sem buffer fora da tela);
 *  - a janela do WindowManager só muda de tamanho nas pontas (cresce antes, encolhe depois de assentar);
 *  - sem blur, sem sombra, sem RenderEffect.
 */
@Composable
fun IslandRoot(
    host: IslandHostState,
    onEvent: (IslandEvent) -> Unit,
    onAct: (app.ilha.core.model.ActivityAction, LiveActivity) -> Unit,
    onSettled: (IslandUiState) -> Unit,
    onAnimating: (Boolean) -> Unit,
) {
    val ui = host.ui
    val g = host.geo
    val q = host.quality
    val motion = remember(q) { MotionSpecs.of(q) }
    val target = IslandSizes.of(ui, g)

    val w = remember { Animatable(g.holeW) }
    val h = remember { Animatable(g.holeH) }
    val r = remember { Animatable(g.holeH / 2f) }
    val contentAlpha = remember { Animatable(0f) }

    val latestUi by rememberUpdatedState(ui)
    var shown by remember { mutableStateOf(ui) }
    val key = ui.contentKey()
    // dados novos fluem direto; troca de estrutura espera o fade-out do conteúdo antigo
    val display = if (key == shown.contentKey()) ui else shown

    // 1) forma: molas diferentes para largura e altura
    LaunchedEffect(target, motion) {
        onAnimating(true)
        try {
            val growing = target.w > w.value || target.h > h.value
            coroutineScope {
                launch { w.animateTo(target.w, if (growing) motion.wExpand else motion.wCollapse) }
                launch { h.animateTo(target.h, if (growing) motion.hExpand else motion.hCollapse) }
                launch { r.animateTo(target.r, motion.radius) }
            }
        } finally {
            onAnimating(false)
        }
        onSettled(latestUi)
    }

    // 2) conteúdo: some, troca, reaparece (com atraso para a forma abrir primeiro)
    LaunchedEffect(key, motion) {
        val structural = shown.contentKey() != key
        if (structural && contentAlpha.value > 0.01f) {
            contentAlpha.animateTo(0f, tween(motion.fadeOutMs, easing = LinearEasing))
        }
        shown = latestUi
        val visible = latestUi !is IslandUiState.Hidden && latestUi !is IslandUiState.Idle
        contentAlpha.animateTo(
            if (visible) 1f else 0f,
            tween(motion.fadeInMs, delayMillis = if (structural) motion.fadeInDelayMs else 0, easing = FastOutSlowInEasing),
        )
    }

    Box(
        Modifier.fillMaxSize().drawBehind {
            val d = density
            val wp = w.value * d
            val hp = h.value * d
            val left = (size.width - wp) / 2f
            val corner = CornerRadius(r.value * d)
            drawRoundRect(Color.Black, Offset(left, 0f), Size(wp, hp), corner)
            if (g.outline) {
                drawRoundRect(Color(0x66FFFFFF), Offset(left, 0f), Size(wp, hp), corner, style = Stroke(1.dp.toPx()))
            }
        },
    ) {
        val act = activityOf(display)
        if (act != null) {
            val cs = IslandSizes.of(display, g)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .requiredSize(cs.w.dp, cs.h.dp)
                    .graphicsLayer {
                        val a = contentAlpha.value
                        alpha = a
                        if (motion.contentScale) {
                            val s = 0.92f + 0.08f * a
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0.5f, 0f)
                        }
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                    .islandGestures(
                        onTap = {
                            when (display) {
                                is IslandUiState.Compact, is IslandUiState.Alert -> onEvent(IslandEvent.Expand(act.id))
                                is IslandUiState.Expanded -> onEvent(IslandEvent.Collapse)
                                else -> Unit
                            }
                        },
                        onLong = { onAct(app.ilha.core.model.ActivityAction.OpenSource, act) },
                        onSwipe = { s ->
                            when (s) {
                                Swipe.UP -> if (display is IslandUiState.Expanded) onEvent(IslandEvent.Collapse)
                                else onEvent(IslandEvent.Dismiss(act.id))
                                Swipe.DOWN -> if (display !is IslandUiState.Expanded) onEvent(IslandEvent.Expand(act.id))
                                Swipe.LEFT, Swipe.RIGHT -> if (display is IslandUiState.Expanded && display.queue.size > 1) {
                                    val list = display.queue
                                    val i = list.indexOfFirst { it.id == act.id }.coerceAtLeast(0)
                                    val step = if (s == Swipe.LEFT) 1 else -1
                                    onEvent(IslandEvent.Select(list[((i + step) % list.size + list.size) % list.size].id))
                                }
                            }
                        },
                    ),
            ) {
                IslandContentFor(display, g, motion, onAct)
            }
        }
    }
}

private fun activityOf(s: IslandUiState): LiveActivity? = when (s) {
    is IslandUiState.Compact -> s.primary
    is IslandUiState.Alert -> s.activity
    is IslandUiState.Expanded -> s.activity
    else -> null
}
