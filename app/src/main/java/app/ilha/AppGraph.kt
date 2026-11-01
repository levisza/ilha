package app.ilha

import android.app.Application
import android.os.SystemClock
import app.ilha.core.data.RulesRepository
import app.ilha.core.data.SettingsRepository
import app.ilha.core.engine.Arbiter
import app.ilha.core.engine.IslandEngine
import app.ilha.core.engine.UiIntentStore
import app.ilha.core.model.ActivityAction
import app.ilha.core.model.ActivityProvider
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Settings
import app.ilha.core.system.EnvironmentMonitor
import app.ilha.core.system.PerformanceGovernor
import app.ilha.core.system.PermissionState
import app.ilha.overlay.HostCoordinator
import app.ilha.providers.BluetoothProvider
import app.ilha.providers.CallProvider
import app.ilha.providers.ChargingProvider
import app.ilha.providers.LabProvider
import app.ilha.providers.MediaProvider
import app.ilha.providers.MessageProvider
import app.ilha.providers.NotificationHub
import app.ilha.providers.ProgressProvider
import app.ilha.providers.RingerProvider
import app.ilha.providers.TimerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Injeção manual (no lugar do Hilt do documento): menos peças no build, mesmo desenho.
 * Os dois serviços do sistema compartilham estes singletons dentro do mesmo processo.
 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    val rulesRepo = RulesRepository(app)

    val env = EnvironmentMonitor(app)
    val perms = PermissionState(app)
    val hub = NotificationHub(app.packageName)
    val governor = PerformanceGovernor(app, settings, scope)

    val lab = LabProvider(app)
    val providers: List<ActivityProvider> = listOf(
        MediaProvider(app, hub),
        CallProvider(app, hub, perms),
        MessageProvider(app, hub),
        TimerProvider(app, hub),
        ChargingProvider(app),
        ProgressProvider(app, hub),
        BluetoothProvider(app, perms),
        RingerProvider(app),
        lab,
    )

    val intents = UiIntentStore()
    val engine = IslandEngine(
        providers = providers,
        rules = rulesRepo.rules,
        env = env.snapshot,
        intents = intents,
        settings = settings,
        arbiter = Arbiter(),
        scope = scope,
        clock = SystemClock::elapsedRealtime,
        performer = ::perform,
    )

    val coordinator = HostCoordinator(this)

    private suspend fun perform(action: ActivityAction, on: LiveActivity): Result<Unit> {
        val p = providers.firstOrNull { it.id == on.providerId }
            ?: return Result.failure(IllegalStateException("provider ${on.providerId} não existe"))
        return p.perform(action, on)
    }
}
