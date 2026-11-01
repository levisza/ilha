package app.ilha.providers

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
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
import app.ilha.core.system.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Estado pelo TelephonyCallback; nome e ações pela notificação (CallStyle ou ações do app de telefone).
 * Atender/recusar: PendingIntent da notificação, com TelecomManager como plano B.
 */
class CallProvider(
    private val ctx: Context,
    private val hub: NotificationHub,
    perms: PermissionState,
) : ActivityProvider {
    override val id = ProviderIds.CALL
    override val requirements = setOf(Requirement.PHONE_STATE)

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var stateSince = 0L

    private fun telephony(): Flow<Int> = callbackFlow {
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) { trySend(state) }
        }
        try {
            tm.registerTelephonyCallback(ctx.mainExecutor, cb)
        } catch (_: SecurityException) {
            trySend(TelephonyManager.CALL_STATE_IDLE)
        }
        awaitClose { try { tm.unregisterTelephonyCallback(cb) } catch (_: Throwable) {} }
    }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val state: Flow<Int> = perms.state.map { it.phone }.distinctUntilChanged().flatMapLatest { granted ->
        if (granted) telephony() else flowOf(TelephonyManager.CALL_STATE_IDLE)
    }

    override val activities: Flow<List<LiveActivity>> =
        combine(state, hub.items) { st, items -> build(st, items) }.distinctUntilChanged()

    private fun callNotif(items: List<NotificationHub.Item>) =
        items.firstOrNull { it.n.category == Notification.CATEGORY_CALL }

    private fun build(st: Int, items: List<NotificationHub.Item>): List<LiveActivity> {
        val now = SystemClock.elapsedRealtime()
        if (st != lastState) { lastState = st; stateSince = now }
        if (st == TelephonyManager.CALL_STATE_IDLE) return emptyList()

        val item = callNotif(items)
        val extras = item?.n?.extras
        val name = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "Chamada"
        val sub = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val ringing = st == TelephonyManager.CALL_STATE_RINGING
        val chrono = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true
        val startedAt = if (chrono && item != null) NotifUtil.whenToElapsed(item.n.`when`) else stateSince
        val pkg = item?.pkg

        return listOf(
            LiveActivity(
                id = "call", providerId = id, kind = ActivityKind.CALL,
                sourcePackage = pkg, appLabel = pkg?.let { AppInfo.label(ctx, it) },
                priority = if (ringing) 100 else 90,
                sticky = true, ttlMs = null,
                alertMs = if (ringing) -1L else 0L,
                leading = Slot.Glyph(GlyphKind.PHONE),
                trailing = if (ringing) Slot.Text("Chamando") else Slot.Timer(startedAt, countDown = false, running = true),
                expanded = ExpandedContent.Call(name, if (ringing) "Chamada recebida" else sub, ringing, if (ringing) null else startedAt),
                actions = if (ringing) listOf(ActivityAction.Answer, ActivityAction.Decline) else listOf(ActivityAction.Decline),
                privacy = Privacy.PRIVATE,
                updatedAt = stateSince,
            ),
        )
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = runCatching {
        val n = callNotif(hub.items.value)?.n
        when (action) {
            ActivityAction.Answer -> if (!viaNotification(n, Notification.EXTRA_ANSWER_INTENT, ANSWER)) {
                ctx.getSystemService(TelecomManager::class.java).acceptRingingCall()
            }
            ActivityAction.Decline -> {
                val ok = viaNotification(n, Notification.EXTRA_DECLINE_INTENT, DECLINE) ||
                    viaNotification(n, Notification.EXTRA_HANG_UP_INTENT, DECLINE)
                if (!ok) endCallCompat()
            }
            ActivityAction.OpenSource -> { NotifActions.send(ctx, n?.contentIntent) }
            else -> Unit
        }
        Unit
    }

    @Suppress("DEPRECATION")
    private fun endCallCompat() {
        ctx.getSystemService(TelecomManager::class.java).endCall()
    }

    private fun viaNotification(n: Notification?, extraKey: String, titleRegex: Regex): Boolean {
        if (n == null) return false
        val pi = n.extras.getParcelable(extraKey, PendingIntent::class.java)
        if (pi != null && NotifActions.send(ctx, pi)) return true
        val idx = n.actions?.indexOfFirst { it.title?.toString()?.contains(titleRegex) == true } ?: -1
        return idx >= 0 && NotifActions.fire(ctx, n, idx)
    }

    private companion object {
        val ANSWER = Regex("atender|answer|accept|aceitar", RegexOption.IGNORE_CASE)
        val DECLINE = Regex("recusar|decline|reject|rejeitar|encerrar|desligar|end|hang", RegexOption.IGNORE_CASE)
    }
}
