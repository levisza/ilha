package app.ilha.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.ilha.AppGraph
import app.ilha.core.model.ActivityAction
import app.ilha.core.model.HostKind
import app.ilha.core.model.IslandEvent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.WindowBounds
import app.ilha.core.system.CutoutProvider
import app.ilha.core.system.Diag
import app.ilha.core.system.FrameJankMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Janela da ilha. kind = ACCESSIBILITY usa TYPE_ACCESSIBILITY_OVERLAY (acima da barra de status);
 * kind = APPLICATION usa TYPE_APPLICATION_OVERLAY (abaixo dela).
 *
 * Regra de ouro: a janela só muda de tamanho nas pontas da animação.
 *   - estado novo MAIOR  -> janela cresce ANTES de a forma animar;
 *   - estado novo MENOR  -> janela encolhe DEPOIS de a forma assentar (onSettled).
 */
class WindowOverlayHost(
    private val graph: AppGraph,
    override val kind: HostKind,
    private val windowContext: Context,
) : OverlayHost {

    private val wm = windowContext.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val owner = OverlayLifecycleOwner()
    private val state = IslandHostState()
    private val cutout = CutoutProvider(windowContext, wm)
    private val jank = FrameJankMonitor { root?.display?.refreshRate ?: 60f }

    private var root: IslandRootLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var current: WindowBounds? = null
    private var latest: IslandUiState = IslandUiState.Hidden
    private var hidden = true
    private var animating = false
    private var forceUntouchable = false
    private var settings = graph.settings.value

    override fun attach(ui: StateFlow<IslandUiState>) {
        owner.start()
        state.geo = cutout.read(settings)
        scope.launch {
            graph.settings.collect { s ->
                settings = s
                state.haptics = s.haptics
                refreshGeometry()
            }
        }
        scope.launch { ui.collect { handle(it) } }
    }

    override fun refreshGeometry() {
        state.geo = cutout.read(settings)
        if (root != null && !hidden) applyBounds(IslandSizes.windowBounds(latest, state.geo))
    }

    override fun resize(bounds: WindowBounds) = applyBounds(bounds)

    override fun setTouchable(touchable: Boolean) {
        forceUntouchable = !touchable
        pushLayout(current ?: return)
    }

    override fun detach() {
        scope.cancel()
        root?.let { r -> try { wm.removeViewImmediate(r) } catch (_: Throwable) {} }
        root = null; params = null; current = null; hidden = true
        owner.stop()
    }

    // ---------- fluxo de estados ----------

    private fun handle(s: IslandUiState) {
        latest = s
        if (s is IslandUiState.Hidden && hidden) return

        state.quality = graph.governor.current()
        val target = IslandSizes.windowBounds(s, state.geo)
        val created = ensureAttached(target)
        val cur = current ?: target
        val grown = target.copy(width = max(cur.width, target.width), height = max(cur.height, target.height))
        if (created || grown != cur) applyBounds(grown) else pushLayout(cur)

        root?.visibility = View.VISIBLE
        hidden = false
        // um quadro depois, quando a janela maior já existe, a forma começa a animar
        root?.post { state.ui = latest }
    }

    private fun onSettled(settled: IslandUiState) {
        val g = state.geo
        if (IslandSizes.of(settled, g) != IslandSizes.of(latest, g)) return   // chegou outro estado no meio
        applyBounds(IslandSizes.windowBounds(latest, g))
        if (latest is IslandUiState.Hidden) {
            root?.visibility = View.GONE
            hidden = true
        }
    }

    private fun onAnimating(on: Boolean) {
        if (on == animating) return
        animating = on
        if (on) jank.start() else {
            val (frames, dropped) = jank.stop()
            graph.governor.report(frames, dropped)
        }
        current?.let { pushLayout(it) }   // pede 120 Hz só enquanto anima
    }

    private fun dispatch(e: IslandEvent) {
        if (e is IslandEvent.Expand && settings.haptics) {
            root?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        graph.engine.onEvent(e)
    }

    private fun act(action: ActivityAction, activity: LiveActivity) {
        graph.engine.onEvent(IslandEvent.Act(action, activity))
    }

    // ---------- janela ----------

    private fun ensureAttached(initial: WindowBounds): Boolean {
        if (root != null) return false
        val r = IslandRootLayout(windowContext) {
            if (latest is IslandUiState.Expanded) graph.engine.onEvent(IslandEvent.Collapse)
        }
        r.setViewTreeLifecycleOwner(owner)
        r.setViewTreeSavedStateRegistryOwner(owner)
        val compose = ComposeView(windowContext).apply {
            setContent {
                IslandRoot(
                    host = state,
                    onEvent = { dispatch(it) },
                    onAct = { action, activity -> act(action, activity) },
                    onSettled = { onSettled(it) },
                    onAnimating = { onAnimating(it) },
                )
            }
        }
        r.addView(compose, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val type = if (kind == HostKind.ACCESSIBILITY) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val lp = WindowManager.LayoutParams(initial.width, initial.height, type, flags(), PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = initial.x
            y = initial.y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            title = "Ilha"
        }
        return try {
            wm.addView(r, lp)
            root = r; params = lp; current = initial
            true
        } catch (t: Throwable) {
            Diag.log("addView falhou (${kind.name.lowercase()}): ${t.javaClass.simpleName}")
            false
        }
    }

    private fun flags(): Int {
        var f = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (latest is IslandUiState.Expanded) f = f or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        if (latest is IslandUiState.Idle || latest is IslandUiState.Hidden || forceUntouchable) {
            f = f or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        return f
    }

    private fun applyBounds(b: WindowBounds) {
        current = b
        pushLayout(b)
    }

    /** Só chama o WindowManager se algo realmente mudou. */
    private fun pushLayout(b: WindowBounds) {
        val r = root ?: return
        val lp = params ?: return
        val f = flags()
        val rate = if (animating) 120f else 0f
        if (lp.x == b.x && lp.y == b.y && lp.width == b.width && lp.height == b.height &&
            lp.flags == f && lp.preferredRefreshRate == rate
        ) return
        lp.x = b.x; lp.y = b.y; lp.width = b.width; lp.height = b.height
        lp.flags = f
        lp.preferredRefreshRate = rate
        try { wm.updateViewLayout(r, lp) } catch (_: Throwable) {}
    }
}
