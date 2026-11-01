package app.ilha.providers

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Estado compartilhado das notificações ativas. O listener empurra eventos (sem polling) e vários
 * providers filtram o mesmo fluxo. Tempos em elapsedRealtime.
 */
class NotificationHub(private val selfPackage: String) {

    data class Item(
        val sbn: StatusBarNotification,
        val importance: Int,
        val passesFilter: Boolean,
        /** primeira vez que vimos esta chave (estável em atualizações de progresso) */
        val firstSeen: Long,
        /** último post/atualização */
        val postedAt: Long,
    ) {
        val key: String get() = sbn.key
        val n: Notification get() = sbn.notification
        val pkg: String get() = sbn.packageName
    }

    private val lock = Any()
    private val map = LinkedHashMap<String, Item>()
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun onConnected(s: NotificationListenerService) {
        val ranking = try { s.currentRanking } catch (_: Throwable) { null }
        val active: Array<StatusBarNotification> =
            try { s.activeNotifications ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        synchronized(lock) {
            map.clear()
            active.forEach { put(it, ranking) }
            publish()
        }
        _connected.value = true
    }

    fun onDisconnected() {
        _connected.value = false
        synchronized(lock) { map.clear(); publish() }
    }

    fun onPosted(sbn: StatusBarNotification, ranking: RankingMap?) = synchronized(lock) {
        put(sbn, ranking)
        publish()
    }

    fun onRemoved(key: String) = synchronized(lock) {
        if (map.remove(key) != null) publish()
    }

    private fun put(sbn: StatusBarNotification, ranking: RankingMap?) {
        if (sbn.packageName == selfPackage) return
        val r = NotificationListenerService.Ranking()
        val ok = ranking?.getRanking(sbn.key, r) == true
        val now = SystemClock.elapsedRealtime()
        val age = (System.currentTimeMillis() - sbn.postTime).coerceAtLeast(0L)
        val posted = now - age
        val prev = map[sbn.key]
        map[sbn.key] = Item(
            sbn = sbn,
            importance = if (ok) r.importance else NotificationManager.IMPORTANCE_DEFAULT,
            passesFilter = if (ok) r.matchesInterruptionFilter() else true,
            firstSeen = prev?.firstSeen ?: posted,
            postedAt = posted,
        )
    }

    private fun publish() { _items.value = map.values.toList() }
}

object NotifUtil {
    fun isMedia(n: Notification): Boolean =
        n.category == Notification.CATEGORY_TRANSPORT ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MediaStyle") == true

    fun title(n: Notification): String? = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    fun text(n: Notification): String? = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** `when` da notificação (relógio de parede) convertido para elapsedRealtime. */
    fun whenToElapsed(whenMs: Long): Long = SystemClock.elapsedRealtime() + (whenMs - System.currentTimeMillis())

    /** Android 16+: texto curto do Live Update (ex.: "5 min"). Por reflexão para não depender do SDK. */
    fun shortCriticalText(n: Notification): String? = try {
        (Notification::class.java.getMethod("getShortCriticalText").invoke(n) as? CharSequence)?.toString()
    } catch (_: Throwable) {
        null
    }
}

/** Dispara PendingIntents e respostas das notificações de outros apps. */
object NotifActions {

    private fun options(): Bundle = ActivityOptions.makeBasic().apply {
        setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }.toBundle()

    fun send(ctx: Context, pi: PendingIntent?, fillIn: Intent? = null): Boolean {
        if (pi == null) return false
        return try {
            pi.send(ctx, 0, fillIn, null, null, null, options())
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun fire(ctx: Context, n: Notification, index: Int): Boolean =
        send(ctx, n.actions?.getOrNull(index)?.actionIntent)

    fun reply(ctx: Context, n: Notification, text: String): Boolean {
        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return false
        val inputs = action.remoteInputs
        val results = Bundle().also { b -> inputs.forEach { b.putCharSequence(it.resultKey, text) } }
        val intent = Intent()
        android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
        return send(ctx, action.actionIntent, intent)
    }

    fun canReply(n: Notification): Boolean = n.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
}
