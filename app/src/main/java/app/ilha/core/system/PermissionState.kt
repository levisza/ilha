package app.ilha.core.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import app.ilha.overlay.IslandAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Perms(
    val accessibility: Boolean = false,
    val notificationAccess: Boolean = false,
    val overlay: Boolean = false,
    val phone: Boolean = false,
    val bluetooth: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val advancedProtection: Boolean = false,
)

class PermissionState(private val ctx: Context) {
    private val _state = MutableStateFlow(read())
    val state: StateFlow<Perms> = _state

    fun refresh() { _state.value = read() }

    private fun read(): Perms {
        val me = ComponentName(ctx, IslandAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val a11y = enabled.split(':').any {
            it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        return Perms(
            accessibility = a11y,
            notificationAccess = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName),
            overlay = Settings.canDrawOverlays(ctx),
            phone = granted(Manifest.permission.READ_PHONE_STATE),
            bluetooth = granted(Manifest.permission.BLUETOOTH_CONNECT),
            batteryUnrestricted = pm.isIgnoringBatteryOptimizations(ctx.packageName),
            advancedProtection = AdvancedProtection.isOn(ctx),
        )
    }

    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}

/**
 * Android 17: com a Proteção Avançada ligada, apps que não são ferramentas de acessibilidade
 * perdem o acesso à API de acessibilidade. Detectamos por reflexão (classe do Android 16+),
 * então nada quebra em outros aparelhos.
 */
object AdvancedProtection {
    fun isOn(ctx: Context): Boolean = try {
        val cls = Class.forName("android.security.advancedprotection.AdvancedProtectionManager")
        val mgr = ctx.getSystemService(cls)
        if (mgr == null) false else (cls.getMethod("isAdvancedProtectionEnabled").invoke(mgr) as? Boolean) ?: false
    } catch (_: Throwable) {
        false
    }
}
