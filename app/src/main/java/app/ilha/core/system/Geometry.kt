package app.ilha.core.system

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager
import app.ilha.core.model.Settings
import kotlin.math.max

/**
 * Tudo em dp, exceto os campos *px. O furo é lido em runtime (Display.cutout); nunca fixado em pixels.
 */
data class IslandGeometry(
    val density: Float,
    val screenWpx: Int,
    val centerXpx: Float,
    val centerYpx: Float,
    val holeW: Float,
    val holeH: Float,
    val offsetX: Float,
    val offsetY: Float,
    val widthScale: Float,
    val outline: Boolean,
) {
    val screenW: Float get() = screenWpx / density
    /** Faixa da "cabeça" da ilha: onde fica o furo, com os slots de cada lado. */
    val bandH: Float get() = max(holeH + 10f, 36f)
    val idleW: Float get() = holeW + 24f

    companion object {
        val DEFAULT = IslandGeometry(2.625f, 1080, 540f, 52f, 24f, 24f, 0f, 0f, 1f, false)
    }
}

class CutoutProvider(private val ctx: Context, private val wm: WindowManager) {

    fun read(s: Settings): IslandGeometry {
        val d = ctx.resources.displayMetrics.density
        val bounds = wm.maximumWindowMetrics.bounds
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val rect = display?.cutout?.boundingRectTop?.takeIf { !it.isEmpty }
        return IslandGeometry(
            density = d,
            screenWpx = bounds.width(),
            centerXpx = rect?.exactCenterX() ?: (bounds.width() / 2f),
            centerYpx = rect?.exactCenterY() ?: (20f * d),
            holeW = rect?.let { it.width() / d } ?: 22f,
            holeH = rect?.let { it.height() / d } ?: 22f,
            offsetX = s.offsetXdp,
            offsetY = s.offsetYdp,
            widthScale = s.widthScale,
            outline = s.calibrating,
        )
    }
}
