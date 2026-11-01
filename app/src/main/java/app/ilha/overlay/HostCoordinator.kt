package app.ilha.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import app.ilha.AppGraph
import app.ilha.core.model.HostKind
import app.ilha.core.model.HostMode
import app.ilha.core.system.AdvancedProtection
import app.ilha.core.system.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Escolhe qual hospedeiro está ativo:
 *   A (acessibilidade, acima da barra de status) se o serviço está ligado e a Proteção Avançada está desligada;
 *   senão B (overlay comum, abaixo da barra) se há permissão de sobreposição e o listener está ligado.
 */
class HostCoordinator(private val graph: AppGraph) {
    private val main = Handler(Looper.getMainLooper())
    private var a11yCtx: Context? = null
    private var appCtx: Context? = null
    private var active: OverlayHost? = null
    private var activeCtx: Context? = null

    init {
        graph.scope.launch(Dispatchers.Main) {
            graph.settings.map { it.hostMode }.distinctUntilChanged().collect { reevaluate(force = false) }
        }
    }

    fun onHostContext(kind: HostKind, ctx: Context) = onMain {
        when (kind) {
            HostKind.ACCESSIBILITY -> a11yCtx = ctx
            HostKind.APPLICATION -> appCtx = ctx
        }
        reevaluate(force = active?.kind == kind)
    }

    fun onHostContextGone(kind: HostKind) = onMain {
        when (kind) {
            HostKind.ACCESSIBILITY -> a11yCtx = null
            HostKind.APPLICATION -> appCtx = null
        }
        if (active?.kind == kind) { detachActive() }
        reevaluate(force = false)
    }

    fun refreshGeometry() = onMain { active?.refreshGeometry() }

    /** Chamado pela tela inicial depois de o usuário mudar permissões. */
    fun recheck() = onMain { reevaluate(force = false) }

    private fun reevaluate(force: Boolean) {
        val s = graph.settings.value
        val protectionOn = AdvancedProtection.isOn(graph.app)
        val canA = a11yCtx != null && s.hostMode != HostMode.OVERLAY_ONLY && !protectionOn
        val canB = appCtx != null && Settings.canDrawOverlays(graph.app) && s.hostMode != HostMode.ACCESSIBILITY_ONLY
        val want = when {
            canA -> HostKind.ACCESSIBILITY
            canB -> HostKind.APPLICATION
            else -> null
        }
        if (!force && active?.kind == want) return
        detachActive()
        val ctx = when (want) {
            HostKind.ACCESSIBILITY -> a11yCtx
            HostKind.APPLICATION -> appCtx?.let { applicationOverlayContext(it) }
            null -> null
        } ?: return
        val host = WindowOverlayHost(graph, want!!, ctx)
        active = host
        activeCtx = ctx
        host.attach(graph.engine.uiState)
        Diag.log("host ativo: ${want.name.lowercase()}")
    }

    private fun detachActive() {
        active?.detach()
        active = null
        activeCtx = null
    }

    /** Contexto de janela para TYPE_APPLICATION_OVERLAY (o serviço em si não é um contexto visual). */
    private fun applicationOverlayContext(base: Context): Context {
        val display = base.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return base.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
