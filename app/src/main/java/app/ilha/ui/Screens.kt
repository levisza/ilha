package app.ilha.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ilha.AppGraph
import app.ilha.core.data.CalibPreview
import app.ilha.core.data.Keys
import app.ilha.core.model.AppMode
import app.ilha.core.model.GlassMode
import app.ilha.core.model.HostMode
import app.ilha.core.model.ProviderIds
import app.ilha.core.model.QualityMode
import app.ilha.core.model.Rules
import app.ilha.core.model.Settings as IlhaSettings
import app.ilha.core.system.Diag
import app.ilha.core.system.Perms
import app.ilha.overlay.Palette
import app.ilha.providers.LabProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BottomSpace = PaddingValues(top = 4.dp, bottom = 118.dp)

@Composable
private fun LargeTitle(title: String) {
    Text(title, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 30.dp, end = 16.dp, top = 18.dp, bottom = 2.dp))
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, color = Palette.Dim, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
        GlassSwitch(checked, onChange)
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, text: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) Palette.Green else Color(0x55FFFFFF)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text, color = Palette.Dim, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (!ok) {
            Spacer(Modifier.width(10.dp))
            GlassButton(action, onClick, tint = Palette.Green, compact = true)
        }
    }
}

private val PROVIDER_LABELS = listOf(
    ProviderIds.MEDIA to "Mídia",
    ProviderIds.CALL to "Chamadas",
    ProviderIds.MESSAGE to "Mensagens",
    ProviderIds.TIMER to "Timer, cronômetro e alarme",
    ProviderIds.CHARGING to "Carregamento",
    ProviderIds.PROGRESS to "Progresso, navegação e Live Updates",
    ProviderIds.BLUETOOTH to "Bluetooth",
    ProviderIds.RINGER to "Silencioso e vibração",
    ProviderIds.LAB to "Laboratório (atividades de teste)",
)

@Composable
fun HomeScreen(graph: AppGraph, s: IlhaSettings, p: Perms) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var disclosure by remember { mutableStateOf(false) }
    val askPhone = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { graph.perms.refresh() }
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { graph.perms.refresh() }
    fun open(i: Intent) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun <T> save(key: Preferences.Key<T>, v: T) { scope.launch { graph.settingsRepo.set(key, v) } }

    val active = (p.accessibility && !p.advancedProtection) || (p.notificationAccess && p.overlay)
    val hostText = when {
        p.accessibility && !p.advancedProtection -> "Modo A · acima da barra de status"
        p.notificationAccess && p.overlay -> "Modo B · abaixo da barra de status"
        else -> "Inativa · falta ativar permissões"
    }

    LazyColumn(contentPadding = BottomSpace) {
        item { LargeTitle("Ilha") }
        item { StatusChip(hostText, active) }
        if (p.advancedProtection) {
            item {
                GlassCard {
                    Text(
                        "A Proteção Avançada do Android está ligada: o sistema bloqueia a acessibilidade para apps que não são ferramentas de acessibilidade. A Ilha usa o modo B.",
                        color = Palette.Soft, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item { SectionTitle("Permissões") }
        item {
            GlassCard {
                PermRow("Acesso a notificações", p.notificationAccess, "Fonte de mensagens, mídia, timers e progresso.", "Ativar") {
                    open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                GlassDivider()
                PermRow("Janela acima da barra de status", p.accessibility, "Acessibilidade, só para desenhar a ilha. Não lê a tela.", "Ativar") { disclosure = true }
                GlassDivider()
                PermRow("Sobreposição a outros apps", p.overlay, "Plano B, se a acessibilidade não estiver disponível.", "Permitir") {
                    open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                GlassDivider()
                PermRow("Chamadas", p.phone, "Estado da chamada e botões de atender e recusar.", "Permitir") {
                    askPhone.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS))
                }
                GlassDivider()
                PermRow("Bluetooth", p.bluetooth, "Nome do dispositivo ao conectar.", "Permitir") { askBt.launch(Manifest.permission.BLUETOOTH_CONNECT) }
                GlassDivider()
                PermRow("Bateria sem restrição", p.batteryUnrestricted, "Coloque a Ilha em “Apps que nunca dormem” na One UI.", "Abrir") {
                    open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
        item {
            Footnote("APK fora da Play Store: se o Android bloquear a ativação, abra Informações do app, menu ⋮, e toque em “Permitir configurações restritas”.")
        }
        item {
            Row(Modifier.padding(horizontal = 30.dp, vertical = 4.dp)) {
                GlassButton("Informações do app", {
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }, compact = true)
            }
        }

        item { SectionTitle("Comportamento") }
        item {
            GlassCard {
                SwitchRow("Ilha sempre visível", "Pílula preta em volta da câmera mesmo sem atividade", s.alwaysVisible) { save(Keys.alwaysVisible, it) }
                GlassDivider()
                SwitchRow("Vibração ao expandir", null, s.haptics) { save(Keys.haptics, it) }
                GlassDivider()
                SwitchRow("Ocultar na horizontal", null, s.hideInLandscape) { save(Keys.hideLandscape, it) }
                GlassDivider()
                SwitchRow("Mostrar na tela de bloqueio", "Conteúdo privado fica oculto lá", s.showOnLockscreen) { save(Keys.lockscreen, it) }
            }
        }

        item { SectionTitle("Janela da ilha") }
        item {
            GlassSegmented(listOf("Auto", "Só A", "Só B"), s.hostMode.ordinal) {
                save(Keys.hostMode, HostMode.entries[it].name)
            }
        }

        item { SectionTitle("Desempenho da ilha") }
        item {
            GlassSegmented(listOf("Auto", "Máxima", "Média", "Leve"), s.qualityMode.ordinal) {
                save(Keys.qualityMode, QualityMode.entries[it].name)
            }
        }
        item {
            Footnote("No Auto, a Ilha mede quadros perdidos durante as animações e desliga sozinha o que pesa (rebote, escala, equalizador). Também reduz com calor e economia de bateria.")
        }

        item { SectionTitle("Aparência do app") }
        item {
            GlassSegmented(listOf("Vidro", "Leve", "Sólido"), s.glassMode.ordinal) {
                save(Keys.glassMode, GlassMode.entries[it].name)
            }
        }
        item {
            Footnote("Liquid Glass só vale para este app. A ilha continua preta e sem shader, para fechar quadros a 120 Hz. No Laboratório e na economia de bateria o app usa o vidro leve.")
        }

        item { SectionTitle("Funções") }
        item {
            GlassCard {
                PROVIDER_LABELS.forEachIndexed { i, (id, label) ->
                    if (i > 0) GlassDivider()
                    SwitchRow(label, null, id in s.enabledProviders) { on -> scope.launch { graph.settingsRepo.setProvider(id, on) } }
                }
            }
        }
    }

    if (disclosure) {
        AlertDialog(
            onDismissRequest = { disclosure = false },
            containerColor = Color(0xFF1C1C1E),
            titleContentColor = Color.White,
            textContentColor = Palette.Soft,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Antes de ativar") },
            text = {
                Text(
                    "A Ilha usa o serviço de acessibilidade apenas para desenhar uma janela acima da barra de status, ao redor da câmera. " +
                        "Ela não lê o conteúdo da tela, não observa o que você digita, não toca na tela por você e não tem permissão de internet: nada sai do aparelho.\n\n" +
                        "Na próxima tela, ative “Ilha (janela acima da barra de status)”.",
                )
            },
            confirmButton = {
                TextButton(onClick = { disclosure = false; open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Concordo", color = Palette.Green) }
            },
            dismissButton = { TextButton(onClick = { disclosure = false }) { Text("Cancelar", color = Palette.Dim) } },
        )
    }
}

@Composable
private fun glassSliderColors() = SliderDefaults.colors(
    thumbColor = Color.White,
    activeTrackColor = Palette.Green,
    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
)

@Composable
fun CalibrationScreen(graph: AppGraph, s: IlhaSettings) {
    val scope = rememberCoroutineScope()
    var x by remember { mutableFloatStateOf(s.offsetXdp) }
    var y by remember { mutableFloatStateOf(s.offsetYdp) }
    var w by remember { mutableFloatStateOf(s.widthScale) }
    fun preview() { graph.settingsRepo.preview.value = CalibPreview(x, y, w) }
    fun commit() { scope.launch { graph.settingsRepo.commitCalibration(x, y, w) } }

    LazyColumn(contentPadding = BottomSpace) {
        item { LargeTitle("Calibrar") }
        item { Footnote("A ilha aparece com contorno em volta da câmera. A posição vem do recorte real da tela; ajuste só se houver diferença.") }
        item {
            GlassCard {
                Text("Horizontal · ${"%.1f".format(x)} dp", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                Slider(x, { x = it; preview() }, Modifier.padding(horizontal = 12.dp), valueRange = -40f..40f, onValueChangeFinished = { commit() }, colors = glassSliderColors())
                GlassDivider()
                Text("Vertical · ${"%.1f".format(y)} dp", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                Slider(y, { y = it; preview() }, Modifier.padding(horizontal = 12.dp), valueRange = -12f..12f, onValueChangeFinished = { commit() }, colors = glassSliderColors())
                GlassDivider()
                Text("Largura · ${"%.0f".format(w * 100)}%", color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 10.dp))
                Slider(w, { w = it; preview() }, Modifier.padding(horizontal = 12.dp), valueRange = 0.8f..1.4f, onValueChangeFinished = { commit() }, colors = glassSliderColors())
            }
        }
        item {
            Row(Modifier.padding(horizontal = 30.dp, vertical = 8.dp)) {
                GlassButton("Restaurar", { x = 0f; y = 0f; w = 1f; preview(); commit() })
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String)

@Composable
fun AppsScreen(graph: AppGraph) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules by graph.rulesRepo.rules.collectAsStateWithLifecycle(initialValue = Rules())
    val apps by produceState(emptyList<AppEntry>()) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(q, PackageManager.ResolveInfoFlags.of(0))
                .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                .filter { it.pkg != ctx.packageName }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }
    }
    LazyColumn(contentPadding = BottomSpace) {
        item { LargeTitle("Apps") }
        item { Footnote("Toque no modo para alternar: Ligado, Só alerta, Desligado. A caixa oculta o conteúdo das mensagens.") }
        items(apps, key = { it.pkg }) { app ->
            val rule = rules.ruleFor(app.pkg)
            val icon by produceState<ImageBitmap?>(null, app.pkg) {
                value = withContext(Dispatchers.IO) {
                    runCatching { ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    .lightGlass(RoundedCornerShape(22.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bmp = icon
                if (bmp != null) Image(bmp, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp))) else Spacer(Modifier.size(36.dp))
                Spacer(Modifier.width(12.dp))
                Text(app.label, color = Color.White, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
                val (label, tint) = when (rule.mode) {
                    AppMode.ON -> "Ligado" to Palette.Green
                    AppMode.ALERT_ONLY -> "Só alerta" to Palette.Orange
                    AppMode.OFF -> "Desligado" to Palette.Red
                }
                Box(
                    Modifier.clip(CircleShape).background(tint.copy(alpha = 0.22f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            val next = when (rule.mode) { AppMode.ON -> AppMode.ALERT_ONLY; AppMode.ALERT_ONLY -> AppMode.OFF; AppMode.OFF -> AppMode.ON }
                            scope.launch { graph.rulesRepo.setRule(app.pkg, rule.copy(mode = next)) }
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.width(10.dp))
                GlassSwitch(rule.hideContent) { scope.launch { graph.rulesRepo.setRule(app.pkg, rule.copy(hideContent = it)) } }
            }
        }
    }
}

private fun labTint(k: LabProvider.Kind): Color = when (k) {
    LabProvider.Kind.CALL, LabProvider.Kind.CHARGING, LabProvider.Kind.NAVIGATION -> Palette.Green
    LabProvider.Kind.MEDIA -> Palette.Pink
    LabProvider.Kind.TIMER, LabProvider.Kind.RINGER -> Palette.Orange
    LabProvider.Kind.MESSAGE, LabProvider.Kind.BLUETOOTH, LabProvider.Kind.DOWNLOAD -> Palette.Blue
}

@Composable
fun LabScreen(graph: AppGraph) {
    LazyColumn(contentPadding = BottomSpace) {
        item { LargeTitle("Laboratório") }
        item { Footnote("Gera atividades falsas para ver a ilha sem esperar eventos reais. Este app usa o vidro leve aqui para o teste medir a ilha, não o app.") }
        items(LabProvider.Kind.entries.chunked(2)) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { k -> GlassButton(k.label, { graph.lab.fire(k) }, Modifier.weight(1f), tint = labTint(k)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("Teste de estresse", { graph.lab.stress() }, Modifier.weight(1f))
                GlassButton("Limpar", { graph.lab.clear() }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DiagScreen(graph: AppGraph) {
    var tick by remember { mutableIntStateOf(0) }
    val p by graph.perms.state.collectAsStateWithLifecycle()
    val lines = remember(tick) { Diag.lines() }
    LazyColumn(contentPadding = BottomSpace) {
        item { LargeTitle("Diagnóstico") }
        item {
            GlassCard {
                Text(
                    buildString {
                        appendLine("Qualidade da animação: ${graph.governor.describe()}")
                        appendLine("Acessibilidade: ${p.accessibility}   Notificações: ${p.notificationAccess}   Overlay: ${p.overlay}")
                        appendLine("Proteção Avançada: ${p.advancedProtection}   Bateria livre: ${p.batteryUnrestricted}")
                        append("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}), ${android.os.Build.MODEL}")
                    },
                    color = Palette.Soft, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
        item { Row(Modifier.padding(horizontal = 30.dp, vertical = 8.dp)) { GlassButton("Atualizar", { tick++ }, compact = true) } }
        item { SectionTitle("Eventos") }
        if (lines.isEmpty()) item { Footnote("Nada registrado ainda.") }
        items(lines) { l ->
            Text(l, color = Palette.Dim, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 30.dp, vertical = 2.dp))
        }
    }
}
