package app.ilha.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ilha.core.model.ActivityAction
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.GlyphKind
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.MediaCmd
import app.ilha.core.model.Slot
import app.ilha.core.system.IslandGeometry
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue

/** Respostas rápidas por toque: janela de overlay não abre teclado. Resposta livre abre o app de origem. */
val QUICK_REPLIES = listOf("Ok 👍", "Já te respondo", "Estou ocupado")

/** Conteúdo da ilha. Layouts fixos no tamanho FINAL do estado; só a forma (fundo) anima de tamanho. */
@Composable
fun IslandContentFor(
    display: IslandUiState,
    g: IslandGeometry,
    motion: Motion,
    onAct: (ActivityAction, LiveActivity) -> Unit,
) {
    when (display) {
        is IslandUiState.Compact -> Band(display.primary, g, motion)
        is IslandUiState.Alert -> Column(Modifier.fillMaxSize()) {
            Band(display.activity, g, motion)
            Body(display.activity, expanded = false, motion = motion) { onAct(it, display.activity) }
        }
        is IslandUiState.Expanded -> Column(Modifier.fillMaxSize()) {
            Band(display.activity, g, motion)
            Body(display.activity, expanded = true, motion = motion) { onAct(it, display.activity) }
        }
        else -> Unit
    }
}

/** Faixa do furo: slot à esquerda, vão do furo no centro exato, slot à direita. */
@Composable
private fun Band(a: LiveActivity, g: IslandGeometry, motion: Motion) {
    Row(Modifier.fillMaxWidth().height(g.bandH.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
            SlotView(a.leading, a, motion)
        }
        Spacer(Modifier.width(g.holeW.dp))
        Box(Modifier.weight(1f).padding(end = 14.dp), contentAlignment = Alignment.CenterEnd) {
            SlotView(a.trailing, a, motion)
        }
    }
}

@Composable
private fun SlotView(slot: Slot, a: LiveActivity, motion: Motion) {
    val accent = accentFor(a.kind)
    when (slot) {
        is Slot.Icon -> KeyImage(slot.key, 22.dp, 6.dp)
        is Slot.Text -> IText(slot.value, 12.5f, color = accent, weight = FontWeight.SemiBold)
        is Slot.Timer -> TimerText(slot, 13f, accent)
        is Slot.Progress -> ProgressRing(slot.fraction, accent, 20.dp, motion)
        is Slot.Glyph -> GlyphIcon(slot.kind, if (slot.kind == GlyphKind.PAUSE) Color.White else accent, 20.dp)
        Slot.Waveform -> Equalizer(true, motion.eqFps, accent, Modifier.size(20.dp))
        Slot.Empty -> Unit
    }
}

@Composable
private fun Body(a: LiveActivity, expanded: Boolean, motion: Motion, onAct: (ActivityAction) -> Unit) {
    Box(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, bottom = 14.dp)) {
        when (val c = a.expanded) {
            is ExpandedContent.Media -> MediaBody(c, onAct)
            is ExpandedContent.Call -> CallBody(c, expanded, onAct)
            is ExpandedContent.Message -> MessageBody(c, expanded, onAct)
            is ExpandedContent.Timer -> TimerBody(c, expanded, a, onAct)
            is ExpandedContent.Charging -> ChargingBody(c, motion)
            is ExpandedContent.Progress -> ProgressBody(a, c, expanded, motion, onAct)
            is ExpandedContent.Info -> InfoBody(a, c, motion)
        }
    }
}

// ---------- botões ----------

@Composable
private fun RoundButton(glyph: GlyphKind, bg: Color, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.pressable(onClick).size(size).clip(CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(glyph, Color.White, size * 0.46f) }
}

@Composable
private fun CtrlButton(glyph: GlyphKind, size: Dp, onClick: () -> Unit) {
    Box(Modifier.pressable(onClick).size(size), contentAlignment = Alignment.Center) {
        GlyphIcon(glyph, Color.White, size * 0.56f)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Box(
        Modifier.pressable(onClick).clip(RoundedCornerShape(50)).background(Palette.Surface2)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) { IText(label, 13f, weight = FontWeight.SemiBold) }
}

// ---------- corpos por tipo ----------

@Composable
private fun MediaBody(c: ExpandedContent.Media, onAct: (ActivityAction) -> Unit) {
    val now by rememberTicker(c.playing, 1000L)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KeyImage(c.artKey ?: c.appIconKey, 48.dp, 11.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                IText(c.title, 15f, weight = FontWeight.SemiBold)
                IText(c.artist, 13f, color = Palette.Dim)
            }
        }
        Column {
            MediaProgress(c) { onAct(ActivityAction.Seek(it)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                IText(mediaPositionLabel(c, now), 11f, color = Palette.Dim, tabular = true)
                if (c.durationMs > 0) IText(fmtSeconds(c.durationMs / 1000), 11f, color = Palette.Dim, tabular = true)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CtrlButton(GlyphKind.PREV, 40.dp) { onAct(ActivityAction.Media(MediaCmd.PREV)) }
            CtrlButton(if (c.playing) GlyphKind.PAUSE else GlyphKind.PLAY, 46.dp) {
                onAct(ActivityAction.Media(if (c.playing) MediaCmd.PAUSE else MediaCmd.PLAY))
            }
            CtrlButton(GlyphKind.NEXT, 40.dp) { onAct(ActivityAction.Media(MediaCmd.NEXT)) }
        }
    }
}

@Composable
private fun CallBody(c: ExpandedContent.Call, expanded: Boolean, onAct: (ActivityAction) -> Unit) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (c.ringing) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Palette.Surface2), contentAlignment = Alignment.Center) {
                GlyphIcon(GlyphKind.PHONE, Palette.Green, 20.dp)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            IText(c.name, if (expanded || c.ringing) 17f else 15f, weight = FontWeight.SemiBold)
            if (c.ringing) {
                IText(c.subtitle ?: "", 12.5f, color = Palette.Dim)
            } else if (c.connectedAt != null) {
                TimerText(Slot.Timer(c.connectedAt, countDown = false, running = true), 14f, Palette.Green)
            }
        }
        if (c.ringing) {
            RoundButton(GlyphKind.HANGUP, Palette.Red, 44.dp) { onAct(ActivityAction.Decline) }
            Spacer(Modifier.width(14.dp))
            RoundButton(GlyphKind.PHONE, Palette.Green, 44.dp) { onAct(ActivityAction.Answer) }
        } else if (expanded) {
            RoundButton(GlyphKind.HANGUP, Palette.Red, 48.dp) { onAct(ActivityAction.Decline) }
        }
    }
}

@Composable
private fun MessageBody(c: ExpandedContent.Message, expanded: Boolean, onAct: (ActivityAction) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.Top) {
            KeyImage(c.iconKey, 34.dp, 9.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                IText(c.sender, 14.5f, weight = FontWeight.SemiBold)
                IText(c.text, 13.5f, color = Palette.Soft, maxLines = if (expanded) 3 else 2)
            }
        }
        if (expanded && c.quickReplies) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_REPLIES.forEach { r -> Pill(r) { onAct(ActivityAction.Reply(r)) } }
            }
        }
    }
}

@Composable
private fun TimerBody(c: ExpandedContent.Timer, expanded: Boolean, a: LiveActivity, onAct: (ActivityAction) -> Unit) {
    val fires = a.actions.filterIsInstance<ActivityAction.Fire>()
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            IText(c.label, 13f, color = Palette.Dim)
            if (c.timer != null) TimerText(c.timer, if (expanded) 30f else 22f, Palette.Orange)
        }
        if (expanded || c.timer == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fires.forEach { f -> Pill(f.label) { onAct(f) } }
            }
        }
    }
}

@Composable
private fun ChargingBody(c: ExpandedContent.Charging, motion: Motion) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            ProgressRing(c.percent / 100f, Palette.Green, 44.dp, motion, thickness = 4.dp)
            IText("${c.percent}", 13f, align = TextAlign.Center, weight = FontWeight.SemiBold, tabular = true)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            IText(if (c.full) "Carga completa" else "Carregando", 15f, weight = FontWeight.SemiBold)
            val parts = buildList {
                c.watts?.let { add(String.format(java.util.Locale.getDefault(), "%.1f W", it)) }
                c.remainingMs?.let { add("${(it / 60_000L).coerceAtLeast(1L)} min até 100%") }
            }
            if (parts.isNotEmpty()) IText(parts.joinToString(" • "), 12.5f, color = Palette.Dim)
        }
    }
}

@Composable
private fun ProgressBody(a: LiveActivity, c: ExpandedContent.Progress, expanded: Boolean, motion: Motion, onAct: (ActivityAction) -> Unit) {
    val fires = a.actions.filterIsInstance<ActivityAction.Fire>()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.iconKey != null) { KeyImage(c.iconKey, 34.dp, 9.dp); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f)) {
                IText(c.title, 14.5f, weight = FontWeight.SemiBold)
                if (c.subtitle != null) IText(c.subtitle, 12.5f, color = Palette.Dim)
            }
            if (c.eta != null) IText(c.eta, 13f, color = accentFor(a.kind), weight = FontWeight.SemiBold)
        }
        if (c.fraction != null) LinearProgress(c.fraction, accentFor(a.kind))
        if (expanded && fires.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { fires.forEach { f -> Pill(f.label) { onAct(f) } } }
        }
    }
}

@Composable
private fun InfoBody(a: LiveActivity, c: ExpandedContent.Info, motion: Motion) {
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        if (c.iconKey != null) { KeyImage(c.iconKey, 34.dp, 9.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            IText(c.title, 15f, weight = FontWeight.SemiBold)
            if (c.subtitle != null) IText(c.subtitle, 12.5f, color = Palette.Dim)
        }
    }
}
