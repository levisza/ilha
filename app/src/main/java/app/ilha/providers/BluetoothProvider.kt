package app.ilha.providers

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import app.ilha.core.system.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Conexão e desconexão (ACL). Bateria de fones fica de fora: Galaxy Buds usam protocolo próprio. */
class BluetoothProvider(private val ctx: Context, perms: PermissionState) : ActivityProvider {
    override val id = ProviderIds.BLUETOOTH
    override val requirements = setOf(Requirement.BLUETOOTH_CONNECT)

    private fun events(): Flow<List<LiveActivity>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val connected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                val dev = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                val name = try { dev?.name } catch (_: SecurityException) { null } ?: "Dispositivo Bluetooth"
                trySend(
                    listOf(
                        LiveActivity(
                            id = "bt", providerId = id, kind = ActivityKind.BLUETOOTH,
                            sourcePackage = null, appLabel = null,
                            priority = 45, sticky = false, ttlMs = 4_000L, alertMs = 0L,
                            leading = Slot.Glyph(GlyphKind.BLUETOOTH),
                            trailing = Slot.Text(if (connected) "ON" else "OFF"),
                            expanded = ExpandedContent.Info(name, if (connected) "Conectado" else "Desconectado", null),
                            actions = emptyList(), privacy = Privacy.PUBLIC,
                            updatedAt = SystemClock.elapsedRealtime(),
                        ),
                    ),
                )
            }
        }
        trySend(emptyList())
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { ctx.unregisterReceiver(receiver) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val activities: Flow<List<LiveActivity>> =
        perms.state.map { it.bluetooth }.distinctUntilChanged().flatMapLatest { ok ->
            if (ok) events() else flowOf(emptyList())
        }

    override suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> = Result.success(Unit)
}
