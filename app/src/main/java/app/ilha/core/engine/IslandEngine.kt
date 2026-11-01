package app.ilha.core.engine

import app.ilha.core.model.ActivityAction
import app.ilha.core.model.ActivityKind
import app.ilha.core.model.ActivityProvider
import app.ilha.core.model.EnvSnapshot
import app.ilha.core.model.IslandEvent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Rules
import app.ilha.core.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

/**
 * Registry → Regras → Árbitro → StateFlow<IslandUiState>.
 * Sem polling: reavalia sozinho só nos instantes de expiração (TTL / fim do Alert).
 */
class IslandEngine(
    private val providers: List<ActivityProvider>,
    rules: Flow<Rules>,
    env: Flow<EnvSnapshot>,
    private val intents: UiIntentStore,
    settings: Flow<Settings>,
    private val arbiter: Arbiter,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val performer: suspend (ActivityAction, LiveActivity) -> Result<Unit>,
) {
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val uiState: StateFlow<IslandUiState> = run {
        val all: Flow<List<LiveActivity>> = settings
            .map { it.enabledProviders }
            .distinctUntilChanged()
            .flatMapLatest { enabled ->
                val flows = providers.filter { it.id in enabled }.map { it.activities }
                if (flows.isEmpty()) flowOf(emptyList())
                else combine(flows) { arr -> arr.toList().flatten() }
            }
            // chamada não espera; o resto agrupa rajadas de 40 ms
            .debounce { list -> if (list.any { it.kind == ActivityKind.CALL }) 0L else 40L }

        combine(all, rules, env, intents.state, settings) { a, r, e, i, s -> Arbiter.Inputs(a, r, e, i, s) }
            .transformLatest { inp ->
                while (true) {
                    val now = clock()
                    val st = arbiter.pick(inp, now)
                    if (st !is IslandUiState.Expanded) inp.intents.expandedId?.let { intents.collapseIf(it) }
                    emit(st)
                    val next = arbiter.nextDeadline(inp.all, now) ?: break
                    delay((next - now).coerceAtLeast(1L))
                }
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), IslandUiState.Hidden)
    }

    fun onEvent(e: IslandEvent) {
        when (e) {
            is IslandEvent.Expand -> intents.expand(e.id)
            IslandEvent.Collapse -> intents.collapse()
            is IslandEvent.Dismiss -> intents.dismiss(e.id, clock())
            is IslandEvent.Select -> intents.select(e.id)
            is IslandEvent.Act -> {
                if (e.action is ActivityAction.DismissFromIsland) {
                    intents.dismiss(e.activity.id, clock())
                    return
                }
                scope.launch {
                    performer(e.action, e.activity)
                }
                val keepOpen = e.action is ActivityAction.Media || e.action is ActivityAction.Seek
                if (!keepOpen) intents.collapse()
            }
        }
    }
}
