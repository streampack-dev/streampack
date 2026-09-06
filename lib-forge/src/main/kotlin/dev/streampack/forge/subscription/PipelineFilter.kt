/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.PipelineOutcome

/** Which pipelines a subscription asked about. */
sealed interface PipelineTarget {
    /** Pipelines that belong to a pull or merge request. */
    data object ChangeRequests : PipelineTarget

    /** Pipelines on whatever the project's default branch is at the time. */
    data object DefaultBranch : PipelineTarget

    /** Pipelines on one named branch, for projects whose working branch is not the default. */
    data class Branch(val name: String) : PipelineTarget
}

/**
 * One `pipelines…` filter token on a subscription: a [target] and whether only failures are wanted.
 *
 * Grammar: `pipelines`, `pipelines:default-branch`, `pipelines:branch:<name>`, each with an
 * optional trailing `:failed`. Branch names may themselves contain `/`, so the branch form is
 * parsed by stripping a trailing `:failed` before taking the rest as the name.
 */
data class PipelineFilter(val target: PipelineTarget, val failedOnly: Boolean) {
    fun matches(event: ForgeEvent.PipelineSettled): Boolean {
        val pipeline = event.pipeline
        val targeted =
            when (target) {
                PipelineTarget.ChangeRequests -> pipeline.changeRequestNumber != null
                PipelineTarget.DefaultBranch ->
                    pipeline.changeRequestNumber == null && event.isDefaultBranch
                is PipelineTarget.Branch ->
                    pipeline.changeRequestNumber == null && pipeline.ref == target.name
            }
        if (!targeted) return false
        return !failedOnly || pipeline.outcome == PipelineOutcome.NEEDS_ATTENTION
    }

    fun render(): String {
        val base =
            when (target) {
                PipelineTarget.ChangeRequests -> PREFIX
                PipelineTarget.DefaultBranch -> "$PREFIX:$DEFAULT_BRANCH"
                is PipelineTarget.Branch -> "$PREFIX:$BRANCH:${target.name}"
            }
        return if (failedOnly) "$base:$FAILED" else base
    }

    companion object {
        const val PREFIX = "pipelines"
        private const val FAILED = "failed"
        private const val DEFAULT_BRANCH = "default-branch"
        private const val BRANCH = "branch"

        /** The filter [token] denotes, or null when it is not a pipeline filter. */
        fun parse(token: String): PipelineFilter? {
            val text = token.trim()
            val lower = text.lowercase()
            if (lower != PREFIX && !lower.startsWith("$PREFIX:")) return null
            var rest = text.substring(PREFIX.length).removePrefix(":")
            var failedOnly = false
            if (rest.lowercase() == FAILED || rest.lowercase().endsWith(":$FAILED")) {
                failedOnly = true
                rest = rest.substring(0, rest.length - FAILED.length).removeSuffix(":")
            }
            val target =
                when {
                    rest.isEmpty() -> PipelineTarget.ChangeRequests
                    rest.equals(DEFAULT_BRANCH, ignoreCase = true) -> PipelineTarget.DefaultBranch
                    rest.lowercase().startsWith("$BRANCH:") -> {
                        val name = rest.substring(BRANCH.length + 1)
                        if (name.isBlank()) return null
                        PipelineTarget.Branch(name)
                    }
                    else -> return null
                }
            return PipelineFilter(target, failedOnly)
        }
    }
}
