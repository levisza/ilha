package app.ilha.core.engine

import app.ilha.core.model.UiIntents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Escolhas manuais do usuário: expandir, avançar na fila, dispensar. Quarto fluxo do combine do motor. */
class UiIntentStore {
    private val _state = MutableStateFlow(UiIntents())
    val state: StateFlow<UiIntents> = _state

    fun expand(id: String) = _state.update { it.copy(expandedId = id, selectedId = id) }

    fun select(id: String) = _state.update { if (it.expandedId != null) it.copy(selectedId = id) else it }

    fun collapse() = _state.update { it.copy(expandedId = null, selectedId = null) }

    fun collapseIf(id: String) = _state.update {
        if (it.expandedId == id) it.copy(expandedId = null, selectedId = null) else it
    }

    fun dismiss(id: String, at: Long) = _state.update { cur ->
        var map = cur.dismissed + (id to at)
        if (map.size > 64) map = map.entries.sortedBy { it.value }.drop(map.size - 64).associate { it.key to it.value }
        cur.copy(
            dismissed = map,
            expandedId = if (cur.expandedId == id) null else cur.expandedId,
            selectedId = if (cur.selectedId == id) null else cur.selectedId,
        )
    }
}
