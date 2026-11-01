package app.ilha.providers

import android.app.Notification
import android.content.Context
import app.ilha.core.model.ActivityAction
import app.ilha.core.model.ActivityKind
import app.ilha.core.model.ActivityProvider
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.GlyphKind
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Privacy
import app.ilha.core.model.ProviderIds
import app.ilha.core.model.Requirement
import app.ilha.core.model.Slot
import app.ilha.core.system.AppInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Progresso genérico (downloads, uploads), navegação (CATEGORY_NAVIGATION) e Live Updates do Android 16+
 * (template ProgressStyle, com o texto curto de ETA quando o app informa). Tudo vem de notificações ongoing.
 */
class ProgressProvider(private val ctx: Context, private val hub: NotificationHub) : ActivityProvider {
    override val id = ProviderIds.PROGRESS
    override val requirements = setOf(Requirement.NOTIFICATION_ACCESS)

    override val activities: Flow<List<LiveActivity>> = hub.items
        .map { items -> items.filter(::eligible).take(3).map(::toActivity) }
        .distinctUntilChanged()

    private fun templateOf(n: Notification) = n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()

    private fun eligible(i: NotificationHub.Item): Boolean {
        val n = i.n
        if (!i.sbn.isOngoing) return false
        if (NotifUtil.isMedia(n)) return false
        if (i.pkg.contains("clock", ignoreCase = true)) return false   // Relógio é do TimerProvider
        if (n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM ||
            n.category == Notification.CATEGORY_STOPWATCH
        ) return false
        val e = n.extras
        val hasProgress = e.getInt(Notification.EXTRA_PROGRESS_MAX) > 0 || e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
        return hasProgress || n.category == Notification.CATEGORY_NAVIGATION || templateOf(n).endsWith("ProgressStyle")
    }

    private fun toActivity(i: NotificationHub.Item): LiveActivity {
        val n = i.n
        val e = n.extras
        val nav = n.category == Notification.CATEGORY_NAVIGATION
        val live = templateOf(n).endsWith("ProgressStyle")
        val kind = when {
            nav -> ActivityKind.NAVIGATION
            live -> ActivityKind.LIVE_UPDATE
            else -> ActivityKind.PROGRESS
        }
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX)
        val cur = e.getInt(Notification.EXTRA_PROGRESS)
        val fraction = if (max > 0 && !e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)) {
            (cur.toFloat() / max).coerceIn(0f, 1f)
        } else null

        val appLabel = AppInfo.label(ctx, i.pkg)
        val iconKey = AppInfo.iconKey(ctx, i.pkg)
        val eta = NotifUtil.shortCriticalText(n)
        val title = NotifUtil.title(n) ?: appLabel
        val subtitle = NotifUtil.text(n) ?: e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        val trailing: Slot = when {
            nav -> if (eta != null) Slot.Text(eta.take(8)) else Slot.Glyph(GlyphKind.NAVIGATION)
            live && eta != null -> Slot.Text(eta.take(8))
            else -> Slot.Progress(fraction)
        }
        val fires = n.actions.orEmpty().take(2).mapIndexed { idx, a ->
            ActivityAction.Fire(i.key, idx, a.title?.toString() ?: "Ação")
        }
        return LiveActivity(
            id = "notif:${i.key}", providerId = id, kind = kind,
            sourcePackage = i.pkg, appLabel = appLabel,
            priority = when (kind) { ActivityKind.NAVIGATION -> 50; ActivityKind.LIVE_UPDATE -> 52; else -> 35 },
            sticky = true, ttlMs = null, alertMs = 0L,
            leading = Slot.Icon(iconKey),
            trailing = trailing,
            expanded = ExpandedContent.Progress(title, subtitle, fraction, eta, iconKey),
            actions = fires + ActivityAction.OpenSource,
            privacy = Privacy.PUBLIC,
            updatedAt = i.firstSeen,
        )
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = runCatching {
        val key = on.id.removePrefix("notif:")
        val n = hub.items.value.firstOrNull { it.key == key }?.n ?: error("notificação sumiu")
        when (action) {
            is ActivityAction.Fire -> NotifActions.fire(ctx, n, action.index)
            ActivityAction.OpenSource -> NotifActions.send(ctx, n.contentIntent)
            else -> false
        }
        Unit
    }
}
