package app.ilha.core.system

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.PowerManager
import androidx.core.content.ContextCompat
import app.ilha.core.model.EnvSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged

/** Orientado a eventos: tela, bloqueio, rotação e Não perturbe. Zero polling. */
class EnvironmentMonitor(private val ctx: Context) {

    val snapshot: Flow<EnvSnapshot> = callbackFlow {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        val nm = ctx.getSystemService(NotificationManager::class.java)

        fun read() = EnvSnapshot(
            screenOn = pm.isInteractive,
            locked = km.isKeyguardLocked,
            landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            dnd = nm.currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            },
        )

        trySend(read())
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { trySend(read()) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { ctx.unregisterReceiver(receiver) }
    }.distinctUntilChanged().conflate()
}
