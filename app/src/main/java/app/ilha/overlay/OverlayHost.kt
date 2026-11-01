package app.ilha.overlay

import app.ilha.core.model.HostKind
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.WindowBounds
import kotlinx.coroutines.flow.StateFlow

/** Dois hospedeiros intercambiáveis (acessibilidade / overlay comum) atrás da mesma interface. */
interface OverlayHost {
    val kind: HostKind
    fun attach(ui: StateFlow<IslandUiState>)
    /** Só nas pontas da animação: a janela nunca muda de tamanho a cada quadro. */
    fun resize(bounds: WindowBounds)
    fun setTouchable(touchable: Boolean)
    fun refreshGeometry()
    fun detach()
}
