/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.format

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.forge.model.PipelineOutcome
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForgeEventFormatterTests {
    private val item = ForgeItem(42, "Fix the thing", "https://example.com/42")

    @Test
    fun `issue text matches the original GitHub wording`() {
        assertEquals(
            "[owner/repo] New issue #42: Fix the thing - https://example.com/42",
            ForgeEventFormatter.format(ForgeKind.GITHUB, "owner/repo", ForgeEvent.IssueOpened(item)),
        )
    }

    @Test
    fun `change request noun follows the forge`() {
        assertEquals(
            "[owner/repo] New PR #42: Fix the thing - https://example.com/42",
            ForgeEventFormatter.format(
                ForgeKind.GITHUB,
                "owner/repo",
                ForgeEvent.ChangeRequestOpened(item),
            ),
        )
        assertEquals(
            "[group/proj] New MR #42: Fix the thing - https://example.com/42",
            ForgeEventFormatter.format(
                ForgeKind.GITLAB,
                "group/proj",
                ForgeEvent.ChangeRequestOpened(item),
            ),
        )
    }

    @Test
    fun `release text uses tag and url`() {
        assertEquals(
            "[owner/repo] New release v1.2.0 - https://example.com/rel",
            ForgeEventFormatter.format(
                ForgeKind.GITHUB,
                "owner/repo",
                ForgeEvent.ReleasePublished(
                    ForgeReleaseInfo("v1.2.0", "Name", "https://example.com/rel")
                ),
            ),
        )
    }

    @Test
    fun `ping includes the note only when present`() {
        assertEquals(
            "[owner/repo] Webhook ping received - setup verified.",
            ForgeEventFormatter.format(ForgeKind.GITHUB, "owner/repo", ForgeEvent.Ping(null)),
        )
        assertEquals(
            "[owner/repo] Webhook ping received - setup verified. (Keep it logically awesome.)",
            ForgeEventFormatter.format(
                ForgeKind.GITHUB,
                "owner/repo",
                ForgeEvent.Ping("Keep it logically awesome."),
            ),
        )
    }

    private fun pipeline(
        name: String? = null,
        ref: String = "development",
        changeRequest: Int? = null,
        outcome: PipelineOutcome = PipelineOutcome.NEEDS_ATTENTION,
        reason: String = "failed",
        duration: Duration? = Duration.ofSeconds(252),
    ) =
        ForgePipeline(
            id = "42",
            name = name,
            ref = ref,
            changeRequestNumber = changeRequest,
            outcome = outcome,
            reason = reason,
            url = "https://gitlab.com/group/proj/-/pipelines/42",
            duration = duration,
            updatedAt = Instant.EPOCH,
        )

    @Test
    fun `failed change request pipeline names the failed jobs`() {
        val event =
            ForgeEvent.PipelineSettled(
                pipeline(changeRequest = 1039),
                listOf("test: unit-tests", "lint: ktfmt"),
                isDefaultBranch = false,
            )
        assertEquals(
            "[group/proj] MR !1039 pipeline FAILED (test: unit-tests, lint: ktfmt) - https://gitlab.com/group/proj/-/pipelines/42",
            ForgeEventFormatter.format(ForgeKind.GITLAB, "group/proj", event),
        )
    }

    @Test
    fun `succeeded pipeline shows the duration and the branch when there is no change request`() {
        val event =
            ForgeEvent.PipelineSettled(
                pipeline(outcome = PipelineOutcome.SUCCEEDED, reason = "success"),
                emptyList(),
                isDefaultBranch = false,
            )
        assertEquals(
            "[group/proj] development pipeline succeeded (4m12s) - https://gitlab.com/group/proj/-/pipelines/42",
            ForgeEventFormatter.format(ForgeKind.GITLAB, "group/proj", event),
        )
    }

    @Test
    fun `github runs name the workflow and use the PR prefix and reasons read as words`() {
        val failed =
            ForgeEvent.PipelineSettled(
                pipeline(
                    name = "CI",
                    changeRequest = 12,
                    reason = "timed_out",
                    duration = Duration.ofSeconds(3725),
                ),
                listOf("unit-tests"),
                isDefaultBranch = false,
            )
        assertEquals(
            "[owner/repo] PR #12 workflow 'CI' TIMED OUT (unit-tests) - https://gitlab.com/group/proj/-/pipelines/42",
            ForgeEventFormatter.format(ForgeKind.GITHUB, "owner/repo", failed),
        )
        val ok =
            ForgeEvent.PipelineSettled(
                pipeline(
                    name = "CI",
                    ref = "main",
                    outcome = PipelineOutcome.SUCCEEDED,
                    reason = "success",
                    duration = Duration.ofSeconds(3725),
                ),
                emptyList(),
                isDefaultBranch = true,
            )
        assertEquals(
            "[owner/repo] main workflow 'CI' succeeded (1h02m05s) - https://gitlab.com/group/proj/-/pipelines/42",
            ForgeEventFormatter.format(ForgeKind.GITHUB, "owner/repo", ok),
        )
        val noJobs =
            ForgeEvent.PipelineSettled(
                pipeline(reason = "canceled", duration = null),
                emptyList(),
                false,
            )
        assertEquals(
            "[x/y] development pipeline CANCELED - https://gitlab.com/group/proj/-/pipelines/42",
            ForgeEventFormatter.format(ForgeKind.GITLAB, "x/y", noJobs),
        )
    }
}
