package app.ilha.providers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
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

/** Silencioso / vibração / som. Aparece 2,5 s quando o modo muda. */
class RingerProvider(private val ctx: Context) : ActivityProvider {
    override val id = ProviderIds.RINGER
    override val requirements = emptySet<Requirement>()

    override val activities: Flow<List<LiveActivity>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val mode = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
                val (glyph, title) = when (mode) {
                    AudioManager.RINGER_MODE_SILENT -> GlyphKind.BELL_OFF to "Silencioso"
                    AudioManager.RINGER_MODE_VIBRATE -> GlyphKind.BELL_OFF to "Vibração"
                    else -> GlyphKind.BELL to "Som ativado"
                }
                trySend(
                    listOf(
                        LiveActivity(
                            id = "ringer", providerId = id, kind = ActivityKind.RINGER,
                            sourcePackage = null, appLabel = null,
                            priority = 42, sticky = false, ttlMs = 2_500L, alertMs = 0L,
                            leading = Slot.Glyph(glyph),
                            trailing = Slot.Text(title.take(9)),
                            expanded = ExpandedContent.Info(title, null, null),
                            actions = emptyList(), privacy = Privacy.PUBLIC,
                            updatedAt = SystemClock.elapsedRealtime(),
                        ),
                    ),
                )
            }
        }
        trySend(emptyList())
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        awaitClose { ctx.unregisterReceiver(receiver) }
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = Result.success(Unit)
}
