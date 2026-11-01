package app.ilha.core.system

import android.content.Context
import android.view.Choreographer
import android.os.PowerManager
import android.os.SystemClock
import app.ilha.core.model.Quality
import app.ilha.core.model.QualityMode
import app.ilha.core.model.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Se uma função deixa a animação capada, desative."
 * Mede quadros perdidos só durante as animações da ilha (custo zero parado) e desce de
 * FULL → BALANCED → LITE sozinho. Também respeita calor do aparelho e economia de bateria.
 *
 *  FULL     molas com leve rebote, conteúdo com escala, equalizador a 30 fps
 *  BALANCED molas amortecidas, só fade, equalizador a 15 fps
 *  LITE     tween curto, sem rebote, tudo estático
 */
class PerformanceGovernor(
    private val ctx: Context,
    settings: Flow<Settings>,
    scope: CoroutineScope,
) {
    @Volatile private var mode = QualityMode.AUTO
    @Volatile private var auto = Quality.FULL
    private var ema = 0f
    private var samples = 0
    private var calm = 0
    private var lastChange = 0L

    init {
        scope.launch { settings.map { it.qualityMode }.distinctUntilChanged().collect { mode = it } }
    }

    fun current(): Quality = when (mode) {
        QualityMode.FULL -> Quality.FULL
        QualityMode.BALANCED -> Quality.BALANCED
        QualityMode.LITE -> Quality.LITE
        QualityMode.AUTO -> worse(auto, hardwareCap())
    }

    fun describe(): String = "${current().name.lowercase()} (modo ${mode.name.lowercase()}, auto=${auto.name.lowercase()})"

    /** Chamado na thread principal ao fim de cada animação. */
    fun report(frames: Int, dropped: Int) {
        if (frames < 6) return
        val ratio = dropped.toFloat() / frames
        ema = if (samples == 0) ratio else ema * 0.6f + ratio * 0.4f
        samples++
        val now = SystemClock.elapsedRealtime()
        if (ema > 0.08f && auto != Quality.LITE && now - lastChange > 2_000) {
            auto = Quality.entries[auto.ordinal + 1]
            Diag.log("jank ${"%.0f".format(ema * 100)}% -> qualidade ${auto.name.lowercase()}")
            lastChange = now; ema = 0f; samples = 0; calm = 0
        } else if (ratio < 0.01f) {
            calm++
            if (calm >= 25 && auto != Quality.FULL && now - lastChange > 300_000) {
                auto = Quality.entries[auto.ordinal - 1]
                Diag.log("estável -> qualidade ${auto.name.lowercase()}")
                lastChange = now; calm = 0
            }
        } else {
            calm = 0
        }
    }

    private fun hardwareCap(): Quality {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val thermal = pm.currentThermalStatus
        return when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Quality.LITE
            thermal >= PowerManager.THERMAL_STATUS_MODERATE || pm.isPowerSaveMode -> Quality.BALANCED
            else -> Quality.FULL
        }
    }

    private fun worse(a: Quality, b: Quality) = if (a.ordinal >= b.ordinal) a else b
}

/** Conta quadros perdidos via Choreographer, só enquanto uma animação roda. */
class FrameJankMonitor(private val refreshHz: () -> Float) : Choreographer.FrameCallback {
    private var last = 0L
    private var frames = 0
    private var dropped = 0
    private var running = false

    fun start() {
        if (running) return
        running = true; last = 0L; frames = 0; dropped = 0
        Choreographer.getInstance().postFrameCallback(this)
    }

    /** Devolve (quadros, perdidos). */
    fun stop(): Pair<Int, Int> {
        if (!running) return 0 to 0
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        return frames to dropped
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last != 0L) {
            val dt = (frameTimeNanos - last) / 1_000_000f
            val period = 1000f / refreshHz().coerceAtLeast(30f)
            frames++
            if (dt > period * 1.9f && dt > 22f) dropped++
        }
        last = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}

object Diag {
    private val buf = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(msg: String) = synchronized(buf) {
        buf.addLast("${fmt.format(Date())}  $msg")
        while (buf.size > 80) buf.removeFirst()
    }

    fun lines(): List<String> = synchronized(buf) { buf.toList().asReversed() }
}
