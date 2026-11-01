package app.ilha.providers

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
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
import app.ilha.core.system.AppInfo
import app.ilha.core.system.IconCache
import app.ilha.service.IslandNotificationListener
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.util.concurrent.ConcurrentHashMap

/** MediaSessionManager + MediaController.Callback. Notificações MediaStyle são ignoradas pelos outros providers. */
class MediaProvider(private val ctx: Context, private val hub: NotificationHub) : ActivityProvider {
    override val id = ProviderIds.MEDIA
    override val requirements = setOf(Requirement.NOTIFICATION_ACCESS)

    private val controllers = ConcurrentHashMap<String, MediaController>()
    private val stamps = HashMap<String, Pair<String, Long>>()   // pacote -> (assinatura, instante)
    private val component = ComponentName(ctx, IslandNotificationListener::class.java)
    private val main = Handler(Looper.getMainLooper())

    @OptIn(ExperimentalCoroutinesApi::class)
    override val activities: Flow<List<LiveActivity>> = hub.connected.flatMapLatest { connected ->
        if (connected) sessions() else flowOf(emptyList())
    }.distinctUntilChanged()

    private fun sessions(): Flow<List<LiveActivity>> = callbackFlow {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val lock = Any()
        val registered = ArrayList<Pair<MediaController, MediaController.Callback>>()

        fun publish() {
            val list = synchronized(lock) { registered.map { it.first } }
            trySend(build(list))
        }

        fun rebuild(list: List<MediaController>?) {
            synchronized(lock) {
                registered.forEach { (c, cb) -> c.unregisterCallback(cb) }
                registered.clear()
                list.orEmpty().forEach { c ->
                    val cb = object : MediaController.Callback() {
                        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                        override fun onSessionDestroyed() = publish()
                    }
                    c.registerCallback(cb, main)
                    registered += c to cb
                }
            }
            publish()
        }

        val listener = MediaSessionManager.OnActiveSessionsChangedListener { rebuild(it) }
        try {
            msm.addOnActiveSessionsChangedListener(listener, component, main)
            rebuild(msm.getActiveSessions(component))
        } catch (_: SecurityException) {
            trySend(emptyList())
        }
        awaitClose {
            try { msm.removeOnActiveSessionsChangedListener(listener) } catch (_: Throwable) {}
            synchronized(lock) {
                registered.forEach { (c, cb) -> c.unregisterCallback(cb) }
                registered.clear()
            }
        }
    }

    private fun build(cs: List<MediaController>): List<LiveActivity> {
        val now = SystemClock.elapsedRealtime()
        var best: Pair<MediaController, PlaybackState>? = null
        for (c in cs) {
            val st = c.playbackState ?: continue
            val s = st.state
            val active = s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
            val paused = s == PlaybackState.STATE_PAUSED
            if (!active && !paused) continue
            if (best == null || (active && !isActive(best.second))) best = c to st
        }
        val (c, st) = best ?: return emptyList()

        val pkg = c.packageName
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "Mídia"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: AppInfo.label(ctx, pkg)
        val duration = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val playing = isActive(st)

        val appKey = AppInfo.iconKey(ctx, pkg)
        val artKey = artFor(pkg, title, artist, md)

        // updatedAt só muda em troca de faixa ou de estado (não a cada posição)
        val sig = "$title|$artist|$playing"
        val prev = stamps[pkg]
        val stamp = if (prev != null && prev.first == sig) prev.second else now.also { stamps[pkg] = sig to it }

        val activityId = "media:$pkg"
        controllers[activityId] = c
        return listOf(
            LiveActivity(
                id = activityId, providerId = id, kind = ActivityKind.MEDIA,
                sourcePackage = pkg, appLabel = AppInfo.label(ctx, pkg),
                priority = if (playing) 30 else 25,
                sticky = playing,
                ttlMs = if (playing) null else 45_000L,
                alertMs = 0L,
                leading = Slot.Icon(artKey ?: appKey),
                trailing = if (playing) Slot.Waveform else Slot.Glyph(GlyphKind.PAUSE),
                expanded = ExpandedContent.Media(
                    title = title, artist = artist, artKey = artKey, appIconKey = appKey,
                    playing = playing, positionMs = st.position.coerceAtLeast(0L), durationMs = duration.coerceAtLeast(0L),
                    positionAt = st.lastPositionUpdateTime, speed = st.playbackSpeed,
                ),
                actions = listOf(
                    ActivityAction.Media(MediaCmd.PREV),
                    ActivityAction.Media(if (playing) MediaCmd.PAUSE else MediaCmd.PLAY),
                    ActivityAction.Media(MediaCmd.NEXT),
                ),
                privacy = Privacy.PUBLIC,
                updatedAt = stamp,
            ),
        )
    }

    private fun isActive(st: PlaybackState) =
        st.state == PlaybackState.STATE_PLAYING || st.state == PlaybackState.STATE_BUFFERING

    private fun artFor(pkg: String, title: String, artist: String, md: MediaMetadata?): String? {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return null
        val key = "art:$pkg:${(title + artist).hashCode()}"
        if (IconCache.get(key) == null) {
            try {
                val s = minOf(bmp.width, bmp.height)
                val square = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
                IconCache.put(key, Bitmap.createScaledBitmap(square, 160, 160, true))
            } catch (_: Throwable) {
                return null
            }
        }
        return key
    }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = runCatching {
        val c = controllers[on.id] ?: error("sessão de mídia sumiu")
        val t = c.transportControls
        when (action) {
            is ActivityAction.Media -> when (action.cmd) {
                MediaCmd.PLAY -> t.play()
                MediaCmd.PAUSE -> t.pause()
                MediaCmd.NEXT -> t.skipToNext()
                MediaCmd.PREV -> t.skipToPrevious()
            }
            is ActivityAction.Seek -> t.seekTo(action.positionMs)
            ActivityAction.OpenSource -> { NotifActions.send(ctx, c.sessionActivity) }
            else -> Unit
        }
        Unit
    }
}
