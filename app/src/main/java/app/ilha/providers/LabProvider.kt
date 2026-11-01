package app.ilha.providers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import app.ilha.core.model.ActivityAction
import app.ilha.core.model.ActivityKind
import app.ilha.core.model.ActivityProvider
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.GlyphKind
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.MediaCmd
import app.ilha.core.model.Privacy
import app.ilha.core.model.ProviderIds
import app.ilha.core.model.Requirement
import app.ilha.core.model.Slot
import app.ilha.core.system.IconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Laboratório: gera atividades falsas em todos os formatos, para testar animação, calibração e demos. */
class LabProvider(@Suppress("unused") private val ctx: Context) : ActivityProvider {
    override val id = ProviderIds.LAB
    override val requirements = emptySet<Requirement>()

    enum class Kind(val label: String) {
        CALL("Chamada tocando"), MEDIA("Música"), MESSAGE("Mensagem"), TIMER("Timer"),
        CHARGING("Carregando"), BLUETOOTH("Bluetooth"), RINGER("Silencioso"),
        DOWNLOAD("Download"), NAVIGATION("Navegação"),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val state = MutableStateFlow<Map<String, LiveActivity>>(emptyMap())
    private var msgCounter = 0
    private var mediaPos = 62_000L
    private var mediaAt = 0L
    private var mediaPlaying = true
    private var downloadJob: Job? = null
    private var stressJob: Job? = null

    override val activities: Flow<List<LiveActivity>> = state.map { it.values.toList() }

    private fun put(a: LiveActivity) = state.update { it + (a.id to a) }
    private fun remove(id: String) = state.update { it - id }
    private fun now() = SystemClock.elapsedRealtime()

    fun clear() {
        downloadJob?.cancel(); stressJob?.cancel()
        state.value = emptyMap()
    }

    fun fire(kind: Kind) {
        when (kind) {
            Kind.CALL -> put(call(ringing = true))
            Kind.MEDIA -> { mediaAt = now(); mediaPlaying = true; put(media()) }
            Kind.MESSAGE -> put(message())
            Kind.TIMER -> put(timer())
            Kind.CHARGING -> put(charging())
            Kind.BLUETOOTH -> put(info("lab:bt", ActivityKind.BLUETOOTH, GlyphKind.BLUETOOTH, "ON", "Galaxy Buds", "Conectado", 4_000L, 45))
            Kind.RINGER -> put(info("lab:ringer", ActivityKind.RINGER, GlyphKind.BELL_OFF, "Silencioso", "Silencioso", null, 2_500L, 42))
            Kind.DOWNLOAD -> download()
            Kind.NAVIGATION -> put(navigation())
        }
    }

    /** Rajada de eventos para provar que a animação aguenta troca rápida de estado. */
    fun stress() {
        stressJob?.cancel()
        stressJob = scope.launch {
            val seq = listOf(Kind.MEDIA, Kind.MESSAGE, Kind.CHARGING, Kind.TIMER, Kind.BLUETOOTH, Kind.CALL, Kind.MESSAGE, Kind.RINGER, Kind.NAVIGATION)
            repeat(2) {
                for (k in seq) { if (!isActive) return@launch; fire(k); delay(900) }
            }
            remove("lab:call")
        }
    }

    // ---- fábricas ----

    private fun call(ringing: Boolean): LiveActivity {
        val t = now()
        return LiveActivity(
            id = "lab:call", providerId = id, kind = ActivityKind.CALL, sourcePackage = null, appLabel = "Telefone",
            priority = if (ringing) 100 else 90, sticky = true, ttlMs = null, alertMs = if (ringing) -1L else 0L,
            leading = Slot.Glyph(GlyphKind.PHONE),
            trailing = if (ringing) Slot.Text("Chamando") else Slot.Timer(t, countDown = false, running = true),
            expanded = ExpandedContent.Call("Marina Souza", if (ringing) "Chamada recebida" else "Celular", ringing, if (ringing) null else t),
            actions = if (ringing) listOf(ActivityAction.Answer, ActivityAction.Decline) else listOf(ActivityAction.Decline),
            privacy = Privacy.PRIVATE, updatedAt = t,
        )
    }

    private fun media(): LiveActivity {
        ensureArt()
        val t = now()
        return LiveActivity(
            id = "lab:media", providerId = id, kind = ActivityKind.MEDIA, sourcePackage = null, appLabel = "Música",
            priority = if (mediaPlaying) 30 else 25, sticky = mediaPlaying, ttlMs = if (mediaPlaying) null else 45_000L, alertMs = 0L,
            leading = Slot.Icon("lab:art"),
            trailing = if (mediaPlaying) Slot.Waveform else Slot.Glyph(GlyphKind.PAUSE),
            expanded = ExpandedContent.Media("Midnight City", "M83", "lab:art", null, mediaPlaying, mediaPos, 244_000L, mediaAt, 1f),
            actions = listOf(
                ActivityAction.Media(MediaCmd.PREV),
                ActivityAction.Media(if (mediaPlaying) MediaCmd.PAUSE else MediaCmd.PLAY),
                ActivityAction.Media(MediaCmd.NEXT),
            ),
            privacy = Privacy.PUBLIC, updatedAt = t,
        )
    }

    private fun message(): LiveActivity {
        msgCounter++
        return LiveActivity(
            id = "lab:msg:$msgCounter", providerId = id, kind = ActivityKind.MESSAGE, sourcePackage = null, appLabel = "Mensagens",
            priority = 65, sticky = false, ttlMs = 14_000L, alertMs = 4_500L,
            leading = Slot.Glyph(GlyphKind.MESSAGE), trailing = Slot.Glyph(GlyphKind.MESSAGE),
            expanded = ExpandedContent.Message("Rafael", "Chega aí, a mesa já está pronta 🍕", true, null),
            actions = listOf(ActivityAction.OpenSource, ActivityAction.DismissFromIsland),
            privacy = Privacy.PRIVATE, updatedAt = now(),
        )
    }

    private fun timer(): LiveActivity {
        val t = Slot.Timer(now() + 5 * 60_000L, countDown = true, running = true)
        return LiveActivity(
            id = "lab:timer", providerId = id, kind = ActivityKind.TIMER, sourcePackage = null, appLabel = "Relógio",
            priority = 55, sticky = true, ttlMs = null, alertMs = 0L,
            leading = Slot.Glyph(GlyphKind.TIMER), trailing = t,
            expanded = ExpandedContent.Timer("Macarrão", t),
            actions = listOf(ActivityAction.Fire("lab", 0, "Zerar")),
            privacy = Privacy.PUBLIC, updatedAt = now(),
        )
    }

    private fun charging() = LiveActivity(
        id = "lab:charging", providerId = id, kind = ActivityKind.CHARGING, sourcePackage = null, appLabel = null,
        priority = 40, sticky = false, ttlMs = 8_000L, alertMs = 3_500L,
        leading = Slot.Glyph(GlyphKind.BOLT), trailing = Slot.Progress(0.64f),
        expanded = ExpandedContent.Charging(64, 24.8f, 41 * 60_000L, false),
        actions = emptyList(), privacy = Privacy.PUBLIC, updatedAt = now(),
    )

    private fun navigation() = LiveActivity(
        id = "lab:nav", providerId = id, kind = ActivityKind.NAVIGATION, sourcePackage = null, appLabel = "Mapas",
        priority = 50, sticky = true, ttlMs = null, alertMs = 0L,
        leading = Slot.Glyph(GlyphKind.NAVIGATION), trailing = Slot.Text("200 m"),
        expanded = ExpandedContent.Info("Vire à direita em 200 m", "Av. Paulista • 12 min", null),
        actions = emptyList(), privacy = Privacy.PUBLIC, updatedAt = now(),
    )

    private fun info(
        id: String, kind: ActivityKind, glyph: GlyphKind, trailing: String, title: String, sub: String?, ttl: Long, prio: Int,
    ) = LiveActivity(
        id = id, providerId = this.id, kind = kind, sourcePackage = null, appLabel = null,
        priority = prio, sticky = false, ttlMs = ttl, alertMs = 0L,
        leading = Slot.Glyph(glyph), trailing = Slot.Text(trailing),
        expanded = ExpandedContent.Info(title, sub, null),
        actions = emptyList(), privacy = Privacy.PUBLIC, updatedAt = now(),
    )

    private fun download() {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            val started = now()
            var f = 0f
            while (isActive && f < 1f) {
                put(
                    LiveActivity(
                        id = "lab:dl", providerId = id, kind = ActivityKind.PROGRESS, sourcePackage = null, appLabel = "Downloads",
                        priority = 35, sticky = true, ttlMs = null, alertMs = 0L,
                        leading = Slot.Glyph(GlyphKind.DOWNLOAD), trailing = Slot.Progress(f),
                        expanded = ExpandedContent.Progress("filme-4k.mkv", "${(f * 100).toInt()}% • 1,8 GB", f, null, null),
                        actions = emptyList(), privacy = Privacy.PUBLIC, updatedAt = started,
                    ),
                )
                delay(500)
                f += 0.04f
            }
            remove("lab:dl")
        }
    }

    private fun ensureArt() {
        if (IconCache.get("lab:art") != null) return
        val bmp = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 160f, 160f, 0xFFFF375F.toInt(), 0xFF5E5CE6.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 160f, 160f, p)
        p.shader = null
        p.color = 0x55FFFFFF
        c.drawCircle(80f, 80f, 46f, p)
        p.color = 0xCC000000.toInt()
        c.drawCircle(80f, 80f, 9f, p)
        IconCache.put("lab:art", bmp)
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = runCatching {
        when (action) {
            ActivityAction.Answer -> put(call(ringing = false))
            ActivityAction.Decline -> remove("lab:call")
            is ActivityAction.Media -> if (on.id == "lab:media") {
                val t = now()
                val wasPlaying = mediaPlaying
                if (wasPlaying) mediaPos += ((t - mediaAt)).coerceAtLeast(0L)
                mediaAt = t
                when (action.cmd) {
                    MediaCmd.PLAY -> mediaPlaying = true
                    MediaCmd.PAUSE -> mediaPlaying = false
                    MediaCmd.NEXT, MediaCmd.PREV -> mediaPos = 0L
                }
                put(media())
            }
            is ActivityAction.Seek -> { mediaPos = action.positionMs; mediaAt = now(); put(media()) }
            is ActivityAction.Fire -> if (action.label == "Zerar") remove(on.id)
            else -> Unit
        }
        Unit
    }
}
