package app.ilha.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ilha.core.model.GlassMode
import app.ilha.overlay.Palette
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlin.math.roundToInt

/*
 * Liquid Glass só no APP (Kyant0/AndroidLiquidGlass, io.github.kyant0:backdrop).
 * A ilha NÃO usa isto: ela é preta, não precisa de fundo para refratar e tem que fechar quadros a 120 Hz.
 *
 * Custo controlado:
 *   FULL  = shader de verdade (vibrancy + blur + lens) em poucas superfícies grandes (grupos, barra, botões);
 *   LIGHT = vidro "falso" (preenchimento translúcido + borda em degradê), zero shader, usado em listas e no Laboratório;
 *   OFF   = superfície sólida.
 */

val Ink = Color(0xFF05060A)

/**
 * O Compose guarda o backdrop dentro desta lambda: assim ninguém precisa citar o tipo da biblioteca,
 * e trocar de versão mexe em um lugar só.
 */
class GlassEnv(val mode: GlassMode, val full: (Modifier, Shape, Color?, Float) -> Modifier)

val LocalGlass = staticCompositionLocalOf<GlassEnv?> { null }

@Composable
fun GlassBackground(mode: GlassMode, content: @Composable BoxScope.() -> Unit) {
    val backdrop = rememberLayerBackdrop {
        drawRect(Ink)
        drawContent()
    }
    val full: (Modifier, Shape, Color?, Float) -> Modifier = remember(backdrop) {
        { m, shape, tint, k ->
            m.drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(6f.dp.toPx())
                    lens(14f.dp.toPx() * k, 28f.dp.toPx() * k)
                },
                onDrawSurface = {
                    if (tint != null) {
                        drawRect(tint, blendMode = BlendMode.Hue)
                        drawRect(tint.copy(alpha = 0.55f))
                    } else {
                        drawRect(Color.White.copy(alpha = 0.07f))
                    }
                },
            )
        }
    }
    val env = remember(mode, full) { GlassEnv(mode, full) }
    CompositionLocalProvider(LocalGlass provides env) {
        Box(Modifier.fillMaxSize()) {
            // fundo estático: gravado uma vez na camada do backdrop, sem animação contínua (bateria)
            Aurora(Modifier.fillMaxSize().layerBackdrop(backdrop))
            content()
        }
    }
}

@Composable
private fun Aurora(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(Ink)
        fun blob(cx: Float, cy: Float, r: Float, c: Color) {
            val center = Offset(size.width * cx, size.height * cy)
            val radius = size.minDimension * r
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center = center, radius = radius), radius = radius, center = center)
        }
        blob(0.10f, 0.08f, 0.80f, Color(0x992B59FF))
        blob(0.98f, 0.30f, 0.70f, Color(0x88B517FF))
        blob(0.05f, 0.58f, 0.75f, Color(0x8800C2B8))
        blob(0.90f, 0.88f, 0.70f, Color(0x7730D158))
        blob(0.15f, 0.98f, 0.55f, Color(0x667A5CFF))
    }
}

fun Modifier.lightGlass(shape: Shape, tint: Color? = null): Modifier =
    this.background(tint?.copy(alpha = 0.30f) ?: Color.White.copy(alpha = 0.09f), shape)
        .border(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f))), shape)

@Composable
fun Modifier.glass(shape: Shape, tint: Color? = null, lensScale: Float = 1f): Modifier {
    val env = LocalGlass.current
    return when {
        env == null || env.mode == GlassMode.OFF ->
            this.background(Color(0xFF1C1C1E), shape).border(0.6.dp, Color.White.copy(alpha = 0.10f), shape)
        env.mode == GlassMode.LIGHT -> this.lightGlass(shape, tint)
        else -> env.full(this, shape, tint, lensScale)
    }
}

// ---------------- componentes ----------------

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .glass(RoundedCornerShape(30.dp))
            .padding(vertical = 6.dp),
        content = content,
    )
}

@Composable
fun GlassDivider() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), color = Palette.Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 30.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
fun Footnote(text: String) {
    Text(text, color = Palette.Dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 6.dp))
}

@Composable
fun GlassButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null, compact: Boolean = false) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.55f, stiffness = 520f), label = "btn")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(CircleShape, tint, lensScale = 0.8f)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 14.dp else 22.dp, vertical = if (compact) 8.dp else 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.sp else 15.sp)
    }
}

/** Interruptor estilo iOS 26: o miolo estica ao segurar e volta com mola. */
@Composable
fun GlassSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val pos by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 0.62f, stiffness = 420f), label = "sw")
    val track by animateColorAsState(if (checked) Palette.Green else Color(0x40FFFFFF), tween(180), label = "swc")
    val thumbW by animateDpAsState(if (pressed) 36.dp else 28.dp, spring(dampingRatio = 0.55f, stiffness = 600f), label = "tw")
    Box(
        Modifier.size(width = 54.dp, height = 32.dp).clip(CircleShape).background(track)
            .clickable(interactionSource = src, indication = null) { onChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset { IntOffset(((50.dp - thumbW).toPx() * pos).roundToInt(), 0) }
                .size(thumbW, 28.dp)
                .shadow(4.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

/** Seletor segmentado em cápsula de vidro, com indicador deslizante em mola. */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).height(48.dp)
            .glass(CircleShape, lensScale = 0.7f).padding(3.dp),
    ) {
        val segW = maxWidth / options.size
        val x by animateDpAsState(segW * selected, spring(dampingRatio = 0.7f, stiffness = 450f), label = "seg")
        Box(Modifier.offset(x = x).width(segW).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (i == selected) Color.White else Palette.Dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/** Barra inferior de vidro com indicador que "escorrega" entre as abas. */
@Composable
fun GlassBottomBar(tabs: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier.padding(horizontal = 20.dp).fillMaxWidth().height(68.dp)
            .glass(CircleShape, lensScale = 1.1f).padding(5.dp),
    ) {
        val w = maxWidth / tabs.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.62f, stiffness = 380f), label = "tab")
        Box(Modifier.offset(x = x).width(w).fillMaxHeight().clip(CircleShape).background(Color.White.copy(alpha = 0.20f)))
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, t ->
                val on = i == selected
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(i) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = if (on) Color.White else Palette.Dim, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(t.label, color = if (on) Color.White else Palette.Dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun StatusChip(text: String, active: Boolean) {
    Row(
        Modifier.padding(horizontal = 30.dp, vertical = 6.dp).glass(CircleShape, lensScale = 0.8f).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) Palette.Green else Palette.Orange))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
