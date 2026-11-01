package app.ilha.core.engine

import app.ilha.core.model.EnvSnapshot
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Privacy
import app.ilha.core.model.Rules
import app.ilha.core.model.Settings
import app.ilha.core.model.UiIntents

/** Decide o que a ilha mostra. Função pura: recebe o "agora", não lê relógio. */
class Arbiter {

    data class Inputs(
        val all: List<LiveActivity>,
        val rules: Rules,
        val env: EnvSnapshot,
        val intents: UiIntents,
        val settings: Settings,
    )

    fun pick(i: Inputs, now: Long): IslandUiState {
        val e = i.env
        val s = i.settings
        if (!e.screenOn) return IslandUiState.Hidden
        if (e.landscape && s.hideInLandscape && !s.calibrating) return IslandUiState.Hidden
        if (e.locked && !s.showOnLockscreen && !s.calibrating) return IslandUiState.Hidden

        val live = visible(i, now)
        if (live.isEmpty()) {
            return if (s.alwaysVisible || s.calibrating) IslandUiState.Idle else IslandUiState.Hidden
        }

        val expId = i.intents.expandedId
        if (expId != null) {
            val wanted = i.intents.selectedId ?: expId
            val chosen = live.firstOrNull { it.id == wanted } ?: live.firstOrNull { it.id == expId }
            if (chosen != null) return IslandUiState.Expanded(chosen, live)
        }
        val top = live.first()
        if (top.isAlerting(now)) return IslandUiState.Alert(top, live)
        return IslandUiState.Compact(top, live.getOrNull(1))
    }

    fun visible(i: Inputs, now: Long): List<LiveActivity> = i.all.asSequence()
        .filter { !it.isExpired(now) }
        .filter { i.rules.allows(it, now) }
        .filter { a -> (i.intents.dismissed[a.id] ?: Long.MIN_VALUE) < a.updatedAt }
        .map { redact(it, i) }
        .sortedWith(compareByDescending<LiveActivity> { it.priority }.thenByDescending { it.updatedAt })
        .toList()

    /** Próximo instante em que algo expira ou deixa de ser Alert (para reavaliar sem polling). */
    fun nextDeadline(all: List<LiveActivity>, now: Long): Long? {
        var best = Long.MAX_VALUE
        for (a in all) {
            a.ttlMs?.let { val t = a.updatedAt + it; if (t > now && t < best) best = t }
            if (a.alertMs > 0) { val t = a.updatedAt + a.alertMs; if (t > now && t < best) best = t }
        }
        return if (best == Long.MAX_VALUE) null else best
    }

    private fun redact(a: LiveActivity, i: Inputs): LiveActivity {
        val hide = i.rules.ruleFor(a.sourcePackage).hideContent || (a.privacy != Privacy.PUBLIC && i.env.locked)
        if (!hide) return a
        return when (val c = a.expanded) {
            is ExpandedContent.Message -> a.copy(expanded = c.copy(text = "Nova mensagem", quickReplies = false))
            is ExpandedContent.Call -> a.copy(expanded = c.copy(name = "Chamada"))
            else -> a
        }
    }
}
