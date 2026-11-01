package app.ilha.providers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlin.math.abs

/** ACTION_BATTERY_CHANGED + BatteryManager. Aparece ao plugar o cabo e some sozinho (TTL). */
class ChargingProvider(private val ctx: Context) : ActivityProvider {
    override val id = ProviderIds.CHARGING
    override val requirements = emptySet<Requirement>()

    override val activities: Flow<List<LiveActivity>> = callbackFlow {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        var wasPlugged = false
        var plugAt = 0L
        var first = true   // o primeiro intent (sticky) é só a linha de base, não "acabou de plugar"

        fun handle(i: Intent) {
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val now = SystemClock.elapsedRealtime()
            if (first) {
                first = false
                wasPlugged = plugged
                plugAt = 0L
                trySend(emptyList())
                return
            }
            if (plugged && !wasPlugged) plugAt = now
            wasPlugged = plugged
            if (!plugged) { trySend(emptyList()); return }

            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = (level * 100 / scale).coerceIn(0, 100)
            val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
            var amps = abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)).toFloat()
            // alguns aparelhos reportam em mA, outros em µA
            amps = if (amps > 20_000f) amps / 1_000_000f else amps / 1_000f
            val watts = (amps * volts).takeIf { it > 0.5f }
            val remaining = bm.computeChargeTimeRemaining().takeIf { it > 0 }
            val full = pct >= 100

            trySend(
                listOf(
                    LiveActivity(
                        id = "charging", providerId = id, kind = ActivityKind.CHARGING,
                        sourcePackage = null, appLabel = null,
                        priority = 40, sticky = false, ttlMs = 8_000L, alertMs = 3_500L,
                        leading = Slot.Glyph(GlyphKind.BOLT),
                        trailing = Slot.Progress(pct / 100f),
                        expanded = ExpandedContent.Charging(pct, watts, remaining, full),
                        actions = emptyList(),
                        privacy = Privacy.PUBLIC,
                        updatedAt = plugAt,
                    ),
                ),
            )
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = handle(i)
        }
        // ACTION_BATTERY_CHANGED é "sticky": o registro já entrega o estado atual
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { ctx.unregisterReceiver(receiver) }
    }.conflate()

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = Result.success(Unit)
}
