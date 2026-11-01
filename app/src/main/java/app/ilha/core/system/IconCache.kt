package app.ilha.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

/** Cache LRU próprio (sem rede, sem Coil). Os providers preenchem; a UI só lê. */
object IconCache {
    private val cache = LruCache<String, ImageBitmap>(48)
    fun put(key: String, bmp: Bitmap) { cache.put(key, bmp.asImageBitmap()) }
    fun get(key: String): ImageBitmap? = cache.get(key)
}

object AppInfo {
    private val labels = ConcurrentHashMap<String, String>()

    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    /** Garante o ícone do app no cache e devolve a chave. */
    fun iconKey(ctx: Context, pkg: String): String {
        val key = "app:$pkg"
        if (IconCache.get(key) == null) {
            try {
                IconCache.put(key, ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96))
            } catch (_: Throwable) {
                // ícone indisponível: a UI cai no fallback
            }
        }
        return key
    }
}
