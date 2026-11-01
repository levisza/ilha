package app.ilha.overlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import app.ilha.core.model.HostKind
import app.ilha.graph

/**
 * Modo A. Existe só para poder criar janelas TYPE_ACCESSIBILITY_OVERLAY (a única camada acima da
 * barra de status). Não lê conteúdo de tela e não pede canRetrieveWindowContent.
 */
class IslandAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        graph.perms.refresh()
        graph.coordinator.onHostContext(HostKind.ACCESSIBILITY, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        graph.coordinator.refreshGeometry()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        graph.coordinator.onHostContextGone(HostKind.ACCESSIBILITY)
        graph.perms.refresh()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        graph.coordinator.onHostContextGone(HostKind.ACCESSIBILITY)
        super.onDestroy()
    }
}
