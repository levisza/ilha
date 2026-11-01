package app.ilha.providers

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
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

/** Só o que faria heads-up: importância alta, passando no Não perturbe. */
class MessageProvider(private val ctx: Context, private val hub: NotificationHub) : ActivityProvider {
    override val id = ProviderIds.MESSAGE
    override val requirements = setOf(Requirement.NOTIFICATION_ACCESS)

    override val activities: Flow<List<LiveActivity>> = hub.items
        .map { items ->
            items.filter(::eligible).sortedByDescending { it.postedAt }.take(4).map(::toActivity)
        }
        .distinctUntilChanged()

    private fun eligible(i: NotificationHub.Item): Boolean {
        val n = i.n
        if (i.sbn.isOngoing) return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (NotifUtil.isMedia(n)) return false
        val messaging = n.category == Notification.CATEGORY_MESSAGE ||
            n.extras.getString(Notification.EXTRA_TEMPLATE)?.endsWith("MessagingStyle") == true
        return messaging && i.importance >= NotificationManager.IMPORTANCE_HIGH && i.passesFilter
    }

    private fun toActivity(i: NotificationHub.Item): LiveActivity {
        val n = i.n
        val extras = n.extras
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = NotifUtil.title(n)
        var text = NotifUtil.text(n) ?: ""
        var sender = conversation ?: title ?: AppInfo.label(ctx, i.pkg)

        val raw = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        if (raw != null && raw.isNotEmpty()) {
            val last = Notification.MessagingStyle.Message.getMessagesFromBundleArray(raw).lastOrNull()
            if (last != null) {
                val who = last.senderPerson?.name?.toString()
                text = last.text?.toString() ?: text
                if (conversation != null && who != null) text = "$who: $text"
                if (conversation == null && who != null) sender = who
            }
        }
        val iconKey = AppInfo.iconKey(ctx, i.pkg)
        val canReply = NotifActions.canReply(n)
        return LiveActivity(
            id = "notif:${i.key}", providerId = id, kind = ActivityKind.MESSAGE,
            sourcePackage = i.pkg, appLabel = AppInfo.label(ctx, i.pkg),
            priority = 65, sticky = false, ttlMs = 14_000L, alertMs = 4_500L,
            leading = Slot.Icon(iconKey),
            trailing = Slot.Glyph(GlyphKind.MESSAGE),
            expanded = ExpandedContent.Message(sender, text, canReply, iconKey),
            actions = listOf(ActivityAction.OpenSource, ActivityAction.DismissFromIsland),
            privacy = Privacy.PRIVATE,
            updatedAt = i.postedAt,
        )
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = runCatching {
        val key = on.id.removePrefix("notif:")
        val n = hub.items.value.firstOrNull { it.key == key }?.n ?: error("notificação sumiu")
        when (action) {
            is ActivityAction.Reply -> NotifActions.reply(ctx, n, action.text)
            ActivityAction.OpenSource -> NotifActions.send(ctx, n.contentIntent)
            is ActivityAction.Fire -> NotifActions.fire(ctx, n, action.index)
            else -> false
        }
        Unit
    }
}
