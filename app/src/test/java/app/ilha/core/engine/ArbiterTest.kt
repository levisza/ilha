package app.ilha.core.engine

import app.ilha.core.model.ActivityKind
import app.ilha.core.model.AppMode
import app.ilha.core.model.AppRule
import app.ilha.core.model.EnvSnapshot
import app.ilha.core.model.ExpandedContent
import app.ilha.core.model.IslandUiState
import app.ilha.core.model.LiveActivity
import app.ilha.core.model.Privacy
import app.ilha.core.model.Rules
import app.ilha.core.model.Settings
import app.ilha.core.model.Slot
import app.ilha.core.model.UiIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O motor é Kotlin puro: roda na JVM, sem aparelho. */
class ArbiterTest {
    private val arbiter = Arbiter()

    private fun act(
        id: String, prio: Int, updatedAt: Long = 0L, alertMs: Long = 0L, ttl: Long? = null,
    ) = LiveActivity(
        id = id, providerId = "t", kind = ActivityKind.MESSAGE, sourcePackage = "pkg.$id", appLabel = null,
        priority = prio, sticky = false, ttlMs = ttl, alertMs = alertMs,
        leading = Slot.Empty, trailing = Slot.Empty, expanded = ExpandedContent.Info("t", null, null),
        actions = emptyList(), privacy = Privacy.PUBLIC, updatedAt = updatedAt,
    )

    private fun inputs(
        all: List<LiveActivity>,
        intents: UiIntents = UiIntents(),
        env: EnvSnapshot = EnvSnapshot(),
        settings: Settings = Settings(),
        rules: Rules = Rules(),
    ) = Arbiter.Inputs(all, rules, env, intents, settings)

    @Test
    fun telaApagadaEscondeAIlha() {
        val st = arbiter.pick(inputs(listOf(act("a", 10)), env = EnvSnapshot(screenOn = false)), 0L)
        assertEquals(IslandUiState.Hidden, st)
    }

    @Test
    fun semAtividadesFicaOcultaOuIdleConformeAjuste() {
        assertEquals(IslandUiState.Hidden, arbiter.pick(inputs(emptyList()), 0L))
        assertEquals(IslandUiState.Idle, arbiter.pick(inputs(emptyList(), settings = Settings(alwaysVisible = true)), 0L))
    }

    @Test
    fun maiorPrioridadeVence() {
        val st = arbiter.pick(inputs(listOf(act("a", 10), act("b", 50))), 1_000L) as IslandUiState.Compact
        assertEquals("b", st.primary.id)
        assertEquals("a", st.secondary?.id)
    }

    @Test
    fun alertaViraCompactoDepoisDoPrazo() {
        val a = act("m", 65, updatedAt = 0L, alertMs = 4_500L)
        assertTrue(arbiter.pick(inputs(listOf(a)), 1_000L) is IslandUiState.Alert)
        assertTrue(arbiter.pick(inputs(listOf(a)), 5_000L) is IslandUiState.Compact)
    }

    @Test
    fun alertaNegativoDuraEnquantoExistir() {
        val call = act("call", 100, updatedAt = 0L, alertMs = -1L)
        assertTrue(arbiter.pick(inputs(listOf(call)), 10_000_000L) is IslandUiState.Alert)
    }

    @Test
    fun ttlExpiraSozinho() {
        val a = act("x", 40, updatedAt = 0L, ttl = 3_000L)
        assertTrue(arbiter.pick(inputs(listOf(a)), 2_000L) is IslandUiState.Compact)
        assertEquals(IslandUiState.Hidden, arbiter.pick(inputs(listOf(a)), 3_000L))
    }

    @Test
    fun dispensarEscondeAteAtualizar() {
        val a = act("d", 40, updatedAt = 100L)
        val dismissed = UiIntents(dismissed = mapOf("d" to 200L))
        assertEquals(IslandUiState.Hidden, arbiter.pick(inputs(listOf(a), dismissed), 300L))
        val updated = a.copy(updatedAt = 400L)
        assertTrue(arbiter.pick(inputs(listOf(updated), dismissed), 500L) is IslandUiState.Compact)
    }

    @Test
    fun expandirRespeitaASelecao() {
        val list = listOf(act("a", 10), act("b", 50))
        val st = arbiter.pick(inputs(list, UiIntents(expandedId = "b", selectedId = "a")), 0L) as IslandUiState.Expanded
        assertEquals("a", st.activity.id)
        assertEquals(2, st.queue.size)
    }

    @Test
    fun regraDesligadaOcultaOApp() {
        val rules = Rules(mapOf("pkg.a" to AppRule(AppMode.OFF)))
        assertEquals(IslandUiState.Hidden, arbiter.pick(inputs(listOf(act("a", 10)), rules = rules), 0L))
    }

    @Test
    fun proximoPrazoEhOMenorFuturo() {
        val list = listOf(
            act("a", 10, updatedAt = 0L, ttl = 10_000L),
            act("b", 10, updatedAt = 0L, alertMs = 4_000L),
            act("c", 10),
        )
        assertEquals(4_000L, arbiter.nextDeadline(list, 1_000L))
        assertNull(arbiter.nextDeadline(listOf(act("c", 10)), 0L))
    }
}
