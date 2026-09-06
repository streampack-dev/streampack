/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.model

/**
 * A forge-neutral change event. Both polling and webhook ingestion produce these, so formatting and
 * fan-out are written once.
 */
sealed interface ForgeEvent {
    data class IssueOpened(val item: ForgeItem) : ForgeEvent

    data class ChangeRequestOpened(val item: ForgeItem) : ForgeEvent

    data class ReleasePublished(val release: ForgeReleaseInfo) : ForgeEvent

    /**
     * A pipeline left the in-progress state. [failedJobs] are display names (`stage: job` on
     * GitLab, the job name on GitHub) of jobs that failed and were not allowed to; empty when the
     * outcome is not a failure or the forge reported none. [isDefaultBranch] is decided by whoever
     * built the event, since only the poller or the payload knows the project's default branch.
     */
    data class PipelineSettled(
        val pipeline: ForgePipeline,
        val failedJobs: List<String>,
        val isDefaultBranch: Boolean,
    ) : ForgeEvent

    /** A webhook handshake or test delivery; [note] is any forge-supplied text. */
    data class Ping(val note: String?) : ForgeEvent
}
