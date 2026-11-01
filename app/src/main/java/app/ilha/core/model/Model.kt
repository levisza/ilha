package app.ilha.core.model

import kotlinx.coroutines.flow.Flow

/** Kotlin puro: nada de android.* aqui, para testar o motor sem aparelho. */

enum class ActivityKind { CALL, ALARM, TIMER, NAVIGATION, LIVE_UPDATE, MESSAGE, MEDIA, CHARGING, BLUETOOTH, RINGER, PROGRESS }

enum class Privacy { PUBLIC, PRIVATE, SECRET }

enum class GlyphKind { PHONE, HANGUP, PLAY, PAUSE, NEXT, PREV, BOLT, BLUETOOTH, BELL, BELL_OFF, TIMER, MESSAGE, NAVIGATION, DOWNLOAD }

enum class MediaCmd { PLAY, PAUSE, NEXT, PREV }

enum class Requirement { NOTIFICATION_ACCESS, PHONE_STATE, BLUETOOTH_CONNECT }

enum class HostKind { ACCESSIBILITY, APPLICATION }

/**
 * Uma "atividade viva". Tempos em SystemClock.elapsedRealtime().
 * alertMs: 0 = nunca vira Alert; >0 = Alert por esse tempo desde updatedAt; <0 = Alert enquanto existir (chamada tocando).
 */
data class LiveActivity(
    val id: String,
    val providerId: String,
    val kind: ActivityKind,
    val sourcePackage: String?,
    val appLabel: String?,
    val priority: Int,
    val sticky: Boolean,
    val ttlMs: Long?,
    val alertMs: Long,
    val leading: Slot,
    val trailing: Slot,
    val expanded: ExpandedContent,
    val actions: List<ActivityAction>,
    val privacy: Privacy,
    val updatedAt: Long,
) {
    fun isExpired(now: Long): Boolean = ttlMs != null && now - updatedAt >= ttlMs
    fun isAlerting(now: Long): Boolean = alertMs < 0 || (alertMs > 0 && now - updatedAt < alertMs)
}

sealed interface Slot {
    data class Icon(val key: String) : Slot                       // bitmap vem do IconCache
    data class Text(val value: String) : Slot
    /** base: countDown = instante final; senão = instante de início. Ambos em elapsedRealtime. */
    data class Timer(val base: Long, val countDown: Boolean, val running: Boolean, val frozenMs: Long = 0L) : Slot
    data class Progress(val fraction: Float?) : Slot              // null = indeterminado
    data class Glyph(val kind: GlyphKind) : Slot
    data object Waveform : Slot
    data object Empty : Slot
}

sealed interface ExpandedContent {
    data class Media(
        val title: String,
        val artist: String,
        val artKey: String?,
        val appIconKey: String?,
        val playing: Boolean,
        val positionMs: Long,
        val durationMs: Long,
        val positionAt: Long,
        val speed: Float,
    ) : ExpandedContent

    data class Call(val name: String, val subtitle: String?, val ringing: Boolean, val connectedAt: Long?) : ExpandedContent
    data class Message(val sender: String, val text: String, val quickReplies: Boolean, val iconKey: String?) : ExpandedContent
    data class Timer(val label: String, val timer: Slot.Timer?) : ExpandedContent
    data class Charging(val percent: Int, val watts: Float?, val remainingMs: Long?, val full: Boolean) : ExpandedContent
    data class Progress(val title: String, val subtitle: String?, val fraction: Float?, val eta: String?, val iconKey: String?) : ExpandedContent
    data class Info(val title: String, val subtitle: String?, val iconKey: String?) : ExpandedContent
}

sealed interface ActivityAction {
    data class Media(val cmd: MediaCmd) : ActivityAction
    data class Seek(val positionMs: Long) : ActivityAction
    data object Answer : ActivityAction
    data object Decline : ActivityAction
    data class Reply(val text: String) : ActivityAction
    data class Fire(val key: String, val index: Int, val label: String) : ActivityAction
    data object OpenSource : ActivityAction
    data object DismissFromIsland : ActivityAction
}

interface ActivityProvider {
    val id: String
    val requirements: Set<Requirement>
    /** Frio: só coleta enquanto o provider está habilitado e a ilha está sendo observada. */
    val activities: Flow<List<LiveActivity>>
    suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit>
}

sealed interface IslandUiState {
    data object Hidden : IslandUiState
    data object Idle : IslandUiState
    data class Compact(val primary: LiveActivity, val secondary: LiveActivity?) : IslandUiState
    data class Expanded(val activity: LiveActivity, val queue: List<LiveActivity>) : IslandUiState
    data class Alert(val activity: LiveActivity, val queue: List<LiveActivity>) : IslandUiState
}

/** Chave de estrutura: muda quando o layout muda (não quando só os dados mudam). */
fun IslandUiState.contentKey(): String = when (this) {
    IslandUiState.Hidden -> "hidden"
    IslandUiState.Idle -> "idle"
    is IslandUiState.Compact -> "c:${primary.id}"
    is IslandUiState.Alert -> "a:${activity.id}"
    is IslandUiState.Expanded -> "e:${activity.id}"
}

data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int)

sealed interface IslandEvent {
    data class Expand(val id: String) : IslandEvent
    data object Collapse : IslandEvent
    data class Dismiss(val id: String) : IslandEvent
    data class Select(val id: String) : IslandEvent
    data class Act(val action: ActivityAction, val activity: LiveActivity) : IslandEvent
}

// ---- ambiente, regras, ajustes (também sem Android) ----

data class EnvSnapshot(
    val screenOn: Boolean = true,
    val locked: Boolean = false,
    val landscape: Boolean = false,
    val dnd: Boolean = false,
)

data class UiIntents(
    val expandedId: String? = null,
    val selectedId: String? = null,
    /** id -> instante (elapsedRealtime) em que o usuário dispensou; volta quando a atividade atualiza. */
    val dismissed: Map<String, Long> = emptyMap(),
)

enum class AppMode { ON, ALERT_ONLY, OFF }

data class AppRule(val mode: AppMode = AppMode.ON, val hideContent: Boolean = false)

data class Rules(val byPackage: Map<String, AppRule> = emptyMap()) {
    fun ruleFor(pkg: String?): AppRule = pkg?.let { byPackage[it] } ?: AppRule()
    fun allows(a: LiveActivity, now: Long): Boolean = when (ruleFor(a.sourcePackage).mode) {
        AppMode.OFF -> false
        AppMode.ALERT_ONLY -> a.isAlerting(now)
        AppMode.ON -> true
    }
}

enum class HostMode { AUTO, ACCESSIBILITY_ONLY, OVERLAY_ONLY }
enum class QualityMode { AUTO, FULL, BALANCED, LITE }
enum class Quality { FULL, BALANCED, LITE }

/** Aparência do APP (a ilha nunca usa vidro: ela é preta e precisa de 120 Hz). */
enum class GlassMode { FULL, LIGHT, OFF }

object ProviderIds {
    const val MEDIA = "media"
    const val CALL = "call"
    const val MESSAGE = "message"
    const val TIMER = "timer"
    const val CHARGING = "charging"
    const val PROGRESS = "progress"
    const val BLUETOOTH = "bluetooth"
    const val RINGER = "ringer"
    const val LAB = "lab"
    val ALL = setOf(MEDIA, CALL, MESSAGE, TIMER, CHARGING, PROGRESS, BLUETOOTH, RINGER, LAB)
}

data class Settings(
    val enabledProviders: Set<String> = ProviderIds.ALL,
    val alwaysVisible: Boolean = false,
    val hostMode: HostMode = HostMode.AUTO,
    val offsetXdp: Float = 0f,
    val offsetYdp: Float = 0f,
    val widthScale: Float = 1f,
    val haptics: Boolean = true,
    val hideInLandscape: Boolean = true,
    val showOnLockscreen: Boolean = false,
    val qualityMode: QualityMode = QualityMode.AUTO,
    val glassMode: GlassMode = GlassMode.FULL,
    val calibrating: Boolean = false,
)
