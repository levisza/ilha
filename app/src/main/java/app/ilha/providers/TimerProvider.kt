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

/** Timer, cronômetro e alarme: notificações do app Relógio (cronômetro nativo da notificação + ações). */
class TimerProvider(private val ctx: Context, private val hub: NotificationHub) : ActivityProvider {
    override val id = ProviderIds.TIMER
    override val requirements = setOf(Requirement.NOTIFICATION_ACCESS)

    private val clockPackages = setOf(
        "com.sec.android.app.clockpackage",   // Relógio Samsung
        "com.google.android.deskclock",
        "com.android.deskclock",
    )

    override val activities: Flow<List<LiveActivity>> = hub.items
        .map { items -> items.filter(::eligible).map(::toActivity) }
        .distinctUntilChanged()

    private fun eligible(i: NotificationHub.Item): Boolean {
        val n = i.n
        if (NotifUtil.isMedia(n)) return false
        return n.category == Notification.CATEGORY_ALARM ||
            n.category == Notification.CATEGORY_STOPWATCH ||
            (i.pkg in clockPackages && (i.sbn.isOngoing || n.category != null))
    }

    private fun toActivity(i: NotificationHub.Item): LiveActivity {
        val n = i.n
        val extras = n.extras
        val label = NotifUtil.title(n) ?: "Relógio"
        val chrono = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val fires = n.actions.orEmpty().take(2).mapIndexed { idx, a ->
            ActivityAction.Fire(i.key, idx, a.title?.toString() ?: "Ação")
        }
        return if (chrono) {
            val countDown = extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
            val timer = Slot.Timer(NotifUtil.whenToElapsed(n.`when`), countDown, running = true)
            LiveActivity(
                id = "notif:${i.key}", providerId = id, kind = ActivityKind.TIMER,
                sourcePackage = i.pkg, appLabel = AppInfo.label(ctx, i.pkg),
                priority = 55, sticky = true, ttlMs = null, alertMs = 0L,
                leading = Slot.Glyph(GlyphKind.TIMER),
                trailing = timer,
                expanded = ExpandedContent.Timer(label, timer),
                actions = fires + ActivityAction.OpenSource,
                privacy = Privacy.PUBLIC,
                updatedAt = i.firstSeen,
            )
        } else {
            // alarme tocando
            LiveActivity(
                id = "notif:${i.key}", providerId = id, kind = ActivityKind.ALARM,
                sourcePackage = i.pkg, appLabel = AppInfo.label(ctx, i.pkg),
                priority = 95, sticky = true, ttlMs = null, alertMs = -1L,
                leading = Slot.Glyph(GlyphKind.BELL),
                trailing = Slot.Text(label.take(10)),
                expanded = ExpandedContent.Timer(label, null),
                actions = fires + ActivityAction.OpenSource,
                privacy = Privacy.PUBLIC,
                updatedAt = i.postedAt,
            )
        }
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
