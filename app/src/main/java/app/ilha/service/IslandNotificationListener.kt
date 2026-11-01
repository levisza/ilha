package app.ilha.service

import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import app.ilha.core.model.HostKind
import app.ilha.graph

/**
 * Fonte de dados (notificações + autorização para MediaSessionManager) e, no modo B,
 * dono da janela TYPE_APPLICATION_OVERLAY. O sistema mantém este serviço vivo.
 */
class IslandNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        graph.hub.onConnected(this)
        graph.perms.refresh()
        graph.coordinator.onHostContext(HostKind.APPLICATION, this)
    }

    override fun onListenerDisconnected() {
        graph.coordinator.onHostContextGone(HostKind.APPLICATION)
        graph.hub.onDisconnected()
        graph.perms.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) graph.hub.onPosted(sbn, rankingMap)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        if (sbn != null) graph.hub.onRemoved(sbn.key)
    }
}
