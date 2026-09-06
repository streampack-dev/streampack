/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.pipeline

import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.PipelineOutcome
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/** The one place that decides "is this pipeline newly settled?" for polling and webhooks alike. */
class PipelineSettlementGateTests {
    private val recorded = mutableMapOf<String, PipelineOutcome>()
    private val project = Mockito.mock(ForgeProject::class.java)
    private val gate =
        PipelineSettlementGate<ForgeProject>(
            status = { _, id -> recorded[id] },
            record = { _, id, outcome -> recorded[id] = outcome },
        )

    private fun pipeline(id: String, outcome: PipelineOutcome) =
        ForgePipeline(
            id,
            null,
            "main",
            null,
            outcome,
            outcome.name.lowercase(),
            "u",
            null,
            Instant.EPOCH,
        )

    @Test
    fun `in progress is recorded but never notified`() {
        assertFalse(gate.shouldNotify(project, pipeline("7", PipelineOutcome.IN_PROGRESS)))
        assertEquals(PipelineOutcome.IN_PROGRESS, recorded["7"])
    }

    @Test
    fun `first terminal sighting notifies once and repeats do not`() {
        assertTrue(gate.shouldNotify(project, pipeline("7", PipelineOutcome.NEEDS_ATTENTION)))
        assertFalse(gate.shouldNotify(project, pipeline("7", PipelineOutcome.NEEDS_ATTENTION)))
    }

    @Test
    fun `a retry that changes the terminal state notifies again`() {
        assertTrue(gate.shouldNotify(project, pipeline("7", PipelineOutcome.NEEDS_ATTENTION)))
        assertFalse(gate.shouldNotify(project, pipeline("7", PipelineOutcome.IN_PROGRESS)))
        assertTrue(gate.shouldNotify(project, pipeline("7", PipelineOutcome.SUCCEEDED)))
        assertFalse(gate.shouldNotify(project, pipeline("7", PipelineOutcome.SUCCEEDED)))
    }
}
