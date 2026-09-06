/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.format

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.PipelineOutcome
import java.time.Duration

/** The single place notification text is built, so polling and webhooks read identically. */
object ForgeEventFormatter {
    fun format(kind: ForgeKind, projectName: String, event: ForgeEvent): String =
        when (event) {
            is ForgeEvent.IssueOpened ->
                "[$projectName] New issue #${event.item.number}: ${event.item.title} - ${event.item.url}"
            is ForgeEvent.ChangeRequestOpened ->
                "[$projectName] New ${kind.changeRequestNoun} #${event.item.number}: " +
                    "${event.item.title} - ${event.item.url}"
            is ForgeEvent.ReleasePublished ->
                "[$projectName] New release ${event.release.tag} - ${event.release.url}"
            is ForgeEvent.PipelineSettled -> pipeline(kind, projectName, event)
            is ForgeEvent.Ping -> {
                val suffix = if (event.note.isNullOrBlank()) "" else " (${event.note})"
                "[$projectName] Webhook ping received - setup verified.$suffix"
            }
        }

    /**
     * `[proj] MR !1039 pipeline FAILED (test: unit-tests) - url` or `[proj] main workflow 'CI'
     * succeeded (4m12s) - url`. The subject is the change request when there is one, else the
     * branch; the unit is "pipeline" unless the forge names workflows.
     */
    private fun pipeline(
        kind: ForgeKind,
        projectName: String,
        event: ForgeEvent.PipelineSettled,
    ): String {
        val p = event.pipeline
        val subject =
            p.changeRequestNumber?.let {
                "${kind.changeRequestNoun} ${kind.changeRequestPrefix}$it"
            } ?: p.ref
        val unit = p.name?.let { "workflow '$it'" } ?: "pipeline"
        val verdict =
            when (p.outcome) {
                PipelineOutcome.SUCCEEDED -> "succeeded"
                PipelineOutcome.NEEDS_ATTENTION -> reasonWords(p.reason)
                PipelineOutcome.IN_PROGRESS -> "in progress"
            }
        val detail =
            when {
                event.failedJobs.isNotEmpty() -> " (${event.failedJobs.joinToString(", ")})"
                p.outcome == PipelineOutcome.SUCCEEDED && p.duration != null ->
                    " (${duration(p.duration)})"
                else -> ""
            }
        return "[$projectName] $subject $unit $verdict$detail - ${p.url}"
    }

    /** The forge's status word as shouting words: `timed_out` becomes `TIMED OUT`. */
    private fun reasonWords(reason: String): String =
        reason.ifBlank { "failed" }.uppercase().replace('_', ' ').replace('-', ' ')

    /** `4m12s`, `1h02m05s`, `45s` */
    fun duration(duration: Duration): String {
        val total = duration.seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return when {
            hours > 0 -> "%dh%02dm%02ds".format(hours, minutes, seconds)
            minutes > 0 -> "%dm%02ds".format(minutes, seconds)
            else -> "${seconds}s"
        }
    }
}
