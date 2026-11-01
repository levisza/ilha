package app.ilha.ui

import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ilha.core.model.GlassMode
import app.ilha.graph

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IlhaTheme { IlhaScreens() } }
    }
}

@Composable
fun IlhaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF30D158),
            onPrimary = Color.Black,
            background = Color(0xFF05060A),
            surface = Color(0xFF111113),
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content,
    )
}

enum class Screen { HOME, CALIBRATION, APPS, LAB, DIAG }

private val TABS = listOf(
    TabItem("Início", Icons.Filled.Home),
    TabItem("Calibrar", Icons.Filled.Place),
    TabItem("Apps", Icons.Filled.Menu),
    TabItem("Lab", Icons.Filled.Build),
    TabItem("Diagnóstico", Icons.Filled.Info),
)

@Composable
private fun IlhaScreens() {
    val ctx = LocalContext.current
    val graph = ctx.graph
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var powerSave by remember { mutableStateOf(false) }
    val settings by graph.settings.collectAsStateWithLifecycle()
    val perms by graph.perms.state.collectAsStateWithLifecycle()

    LifecycleResumeEffect(Unit) {
        graph.perms.refresh()
        graph.coordinator.recheck()
        powerSave = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
        onPauseOrDispose { }
    }
    // o modo de calibração (ilha visível com contorno) só vale enquanto a aba Calibrar está aberta
    DisposableEffect(screen) {
        graph.settingsRepo.calibrating.value = screen == Screen.CALIBRATION
        onDispose { graph.settingsRepo.calibrating.value = false }
    }

    // O vidro do app e a ilha dividem a mesma RenderThread. No Laboratório e na economia de bateria
    // o app cai para o vidro leve (sem shader) para o teste medir a ilha, e não o app.
    val glass = when {
        screen == Screen.LAB -> GlassMode.LIGHT
        powerSave && settings.glassMode == GlassMode.FULL -> GlassMode.LIGHT
        else -> settings.glassMode
    }

    GlassBackground(glass) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (screen) {
                Screen.HOME -> HomeScreen(graph, settings, perms)
                Screen.CALIBRATION -> CalibrationScreen(graph, settings)
                Screen.APPS -> AppsScreen(graph)
                Screen.LAB -> LabScreen(graph)
                Screen.DIAG -> DiagScreen(graph)
            }
        }
        GlassBottomBar(
            tabs = TABS,
            selected = screen.ordinal,
            onSelect = { screen = Screen.entries[it] },
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
        )
    }
}
