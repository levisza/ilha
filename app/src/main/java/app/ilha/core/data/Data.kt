package app.ilha.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ilha.core.model.AppMode
import app.ilha.core.model.AppRule
import app.ilha.core.model.GlassMode
import app.ilha.core.model.HostMode
import app.ilha.core.model.ProviderIds
import app.ilha.core.model.QualityMode
import app.ilha.core.model.Rules
import app.ilha.core.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.ilhaStore: DataStore<Preferences> by preferencesDataStore(name = "ilha")

object Keys {
    val enabled = stringSetPreferencesKey("enabled_providers")
    val alwaysVisible = booleanPreferencesKey("always_visible")
    val hostMode = stringPreferencesKey("host_mode")
    val offsetX = floatPreferencesKey("offset_x")
    val offsetY = floatPreferencesKey("offset_y")
    val widthScale = floatPreferencesKey("width_scale")
    val haptics = booleanPreferencesKey("haptics")
    val hideLandscape = booleanPreferencesKey("hide_landscape")
    val lockscreen = booleanPreferencesKey("lockscreen")
    val qualityMode = stringPreferencesKey("quality_mode")
    val glassMode = stringPreferencesKey("glass_mode")
    val appRules = stringSetPreferencesKey("app_rules")
}

data class CalibPreview(val x: Float, val y: Float, val scale: Float)

class SettingsRepository(private val ctx: Context) {
    /** Não persistido: ligado só enquanto a tela de calibração está aberta. */
    val calibrating = MutableStateFlow(false)
    /** Pré-visualização ao arrastar os sliders, sem escrever no disco a cada pixel. */
    val preview = MutableStateFlow<CalibPreview?>(null)

    val settings: Flow<Settings> = combine(ctx.ilhaStore.data, calibrating, preview) { p, cal, pv ->
        val base = p.toSettings()
        base.copy(
            calibrating = cal,
            offsetXdp = pv?.x ?: base.offsetXdp,
            offsetYdp = pv?.y ?: base.offsetYdp,
            widthScale = pv?.scale ?: base.widthScale,
        )
    }.distinctUntilChanged()

    suspend fun <T> set(key: Preferences.Key<T>, value: T) { ctx.ilhaStore.edit { it[key] = value } }

    suspend fun setProvider(id: String, on: Boolean) {
        ctx.ilhaStore.edit {
            val cur = it[Keys.enabled] ?: ProviderIds.ALL
            it[Keys.enabled] = if (on) cur + id else cur - id
        }
    }

    suspend fun commitCalibration(x: Float, y: Float, scale: Float) {
        ctx.ilhaStore.edit { it[Keys.offsetX] = x; it[Keys.offsetY] = y; it[Keys.widthScale] = scale }
        preview.value = null
    }

    private fun Preferences.toSettings() = Settings(
        enabledProviders = this[Keys.enabled] ?: ProviderIds.ALL,
        alwaysVisible = this[Keys.alwaysVisible] ?: false,
        hostMode = this[Keys.hostMode]?.let { runCatching { HostMode.valueOf(it) }.getOrNull() } ?: HostMode.AUTO,
        offsetXdp = this[Keys.offsetX] ?: 0f,
        offsetYdp = this[Keys.offsetY] ?: 0f,
        widthScale = this[Keys.widthScale] ?: 1f,
        haptics = this[Keys.haptics] ?: true,
        hideInLandscape = this[Keys.hideLandscape] ?: true,
        showOnLockscreen = this[Keys.lockscreen] ?: false,
        qualityMode = this[Keys.qualityMode]?.let { runCatching { QualityMode.valueOf(it) }.getOrNull() } ?: QualityMode.AUTO,
        glassMode = this[Keys.glassMode]?.let { runCatching { GlassMode.valueOf(it) }.getOrNull() } ?: GlassMode.FULL,
    )
}

/** Regras por app, guardadas como "pacote|MODO|ocultar". Pacote ausente = padrão (ligado). */
class RulesRepository(private val ctx: Context) {
    val rules: Flow<Rules> = ctx.ilhaStore.data
        .map { p -> Rules(parse(p[Keys.appRules] ?: emptySet())) }
        .distinctUntilChanged()

    suspend fun setRule(pkg: String, rule: AppRule) {
        ctx.ilhaStore.edit { p ->
            val cur = (p[Keys.appRules] ?: emptySet()).filterNot { it.startsWith("$pkg|") }.toMutableSet()
            if (rule != AppRule()) cur += "$pkg|${rule.mode.name}|${if (rule.hideContent) 1 else 0}"
            p[Keys.appRules] = cur
        }
    }

    private fun parse(set: Set<String>): Map<String, AppRule> = buildMap {
        for (line in set) {
            val parts = line.split('|')
            if (parts.size < 3) continue
            val mode = runCatching { AppMode.valueOf(parts[1]) }.getOrNull() ?: continue
            put(parts[0], AppRule(mode, parts[2] == "1"))
        }
    }
}
