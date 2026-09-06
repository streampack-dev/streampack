/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

import java.time.Duration
import java.time.Instant

/**
 * Where a pipeline stands. Only the two settled states are ever reported; [IN_PROGRESS] is recorded
 * so a retry that goes back through it is noticed when it settles again.
 */
enum class PipelineOutcome {
    IN_PROGRESS,
    SUCCEEDED,
    NEEDS_ATTENTION,
}

/**
 * A pipeline (GitLab) or workflow run (GitHub) as seen from an API or webhook payload.
 *
 * @param id the forge's pipeline or run id, unique within the project
 * @param name the workflow name on GitHub, where a push starts one run per workflow; null on GitLab
 * @param ref the branch the pipeline ran for, or a merge-request ref
 * @param changeRequestNumber the PR or MR this pipeline belongs to, when the forge says so
 * @param reason the forge's own terminal status word, e.g. `failed`, `canceled`, `timed_out`
 * @param duration wall time when the forge reports it
 * @param updatedAt when the forge last changed the pipeline; polling compares it to the last poll
 */
data class ForgePipeline(
    val id: String,
    val name: String?,
    val ref: String,
    val changeRequestNumber: Int?,
    val outcome: PipelineOutcome,
    val reason: String,
    val url: String,
    val duration: Duration?,
    val updatedAt: Instant,
)
