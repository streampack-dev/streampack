/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.pipeline

import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.PipelineOutcome

/**
 * Decides whether a pipeline sighting is a new settlement worth reporting, using the module's
 * pipelines table as memory. Polling and webhooks both pass through here, so the two paths cannot
 * report the same settlement twice, and a retry (terminal, then in progress, then terminal again)
 * is reported on each settlement.
 *
 * @param status the last recorded outcome for a pipeline id, or null when never seen
 * @param record persists the outcome just seen
 */
class PipelineSettlementGate<P : ForgeProject>(
    private val status: (P, String) -> PipelineOutcome?,
    private val record: (P, String, PipelineOutcome) -> Unit,
) {
    fun shouldNotify(project: P, pipeline: ForgePipeline): Boolean {
        val previous = status(project, pipeline.id)
        if (previous != pipeline.outcome) record(project, pipeline.id, pipeline.outcome)
        if (pipeline.outcome == PipelineOutcome.IN_PROGRESS) return false
        return previous != pipeline.outcome
    }
}
