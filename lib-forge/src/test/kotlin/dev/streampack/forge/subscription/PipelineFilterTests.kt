/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.PipelineOutcome
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PipelineFilterTests {
    private fun pipeline(
        ref: String = "feature",
        changeRequest: Int? = null,
        outcome: PipelineOutcome = PipelineOutcome.NEEDS_ATTENTION,
    ) =
        ForgePipeline(
            id = "1",
            name = null,
            ref = ref,
            changeRequestNumber = changeRequest,
            outcome = outcome,
            reason = if (outcome == PipelineOutcome.NEEDS_ATTENTION) "failed" else "success",
            url = "https://example.com/p/1",
            duration = Duration.ofSeconds(90),
            updatedAt = Instant.EPOCH,
        )

    private fun settled(pipeline: ForgePipeline, isDefaultBranch: Boolean = false) =
        ForgeEvent.PipelineSettled(pipeline, emptyList(), isDefaultBranch)

    @Test
    fun `tokens parse and render round trip`() {
        val cases =
            mapOf(
                "pipelines" to PipelineFilter(PipelineTarget.ChangeRequests, failedOnly = false),
                "pipelines:failed" to
                    PipelineFilter(PipelineTarget.ChangeRequests, failedOnly = true),
                "pipelines:default-branch" to PipelineFilter(PipelineTarget.DefaultBranch, false),
                "pipelines:default-branch:failed" to
                    PipelineFilter(PipelineTarget.DefaultBranch, true),
                "pipelines:branch:development" to
                    PipelineFilter(PipelineTarget.Branch("development"), false),
                "pipelines:branch:release/2.0:failed" to
                    PipelineFilter(PipelineTarget.Branch("release/2.0"), true),
            )
        cases.forEach { (token, expected) ->
            assertEquals(expected, PipelineFilter.parse(token), token)
            assertEquals(token, expected.render())
        }
        assertEquals(PipelineFilter.parse("pipelines"), PipelineFilter.parse("PIPELINES"))
        assertNull(PipelineFilter.parse("issues"))
        assertNull(PipelineFilter.parse("pipelines:branch"))
        assertNull(PipelineFilter.parse("pipelines:branch:"))
        assertNull(PipelineFilter.parse("pipelines:bogus"))
    }

    @Test
    fun `change request filters match only pipelines with a change request and honor failed only`() {
        val all = PipelineFilter(PipelineTarget.ChangeRequests, failedOnly = false)
        val failed = PipelineFilter(PipelineTarget.ChangeRequests, failedOnly = true)
        val mrFailed = settled(pipeline(changeRequest = 12))
        val mrOk = settled(pipeline(changeRequest = 12, outcome = PipelineOutcome.SUCCEEDED))
        val branch = settled(pipeline(ref = "development"))
        assertTrue(all.matches(mrFailed))
        assertTrue(all.matches(mrOk))
        assertTrue(failed.matches(mrFailed))
        assertFalse(failed.matches(mrOk))
        assertFalse(all.matches(branch))
    }

    @Test
    fun `branch and default branch filters match by ref and never match change request pipelines`() {
        val dev = PipelineFilter(PipelineTarget.Branch("development"), failedOnly = false)
        val default = PipelineFilter(PipelineTarget.DefaultBranch, failedOnly = false)
        assertTrue(dev.matches(settled(pipeline(ref = "development"))))
        assertFalse(dev.matches(settled(pipeline(ref = "main"))))
        assertFalse(dev.matches(settled(pipeline(ref = "development", changeRequest = 3))))
        assertTrue(default.matches(settled(pipeline(ref = "main"), isDefaultBranch = true)))
        assertFalse(default.matches(settled(pipeline(ref = "main"), isDefaultBranch = false)))
    }

    @Test
    fun `subscription events decide what a subscription wants`() {
        val base = SubscriptionEvents.BASE
        val issue = ForgeEvent.IssueOpened(ForgeItem(1, "t", "u"))
        assertTrue(SubscriptionEvents.wants(base, issue))
        assertTrue(SubscriptionEvents.wants(base, ForgeEvent.Ping(null)))
        assertFalse(SubscriptionEvents.wants(base, settled(pipeline(changeRequest = 1))))

        val withPipelines =
            SubscriptionEvents.withFilters(listOf(PipelineFilter.parse("pipelines:failed")!!))
        assertEquals(base + "pipelines:failed", withPipelines)
        assertTrue(SubscriptionEvents.wants(withPipelines, issue))
        assertTrue(SubscriptionEvents.wants(withPipelines, settled(pipeline(changeRequest = 1))))
        assertFalse(
            SubscriptionEvents.wants(
                withPipelines,
                settled(pipeline(changeRequest = 1, outcome = PipelineOutcome.SUCCEEDED)),
            )
        )
        assertEquals(
            listOf(PipelineFilter(PipelineTarget.ChangeRequests, true)),
            SubscriptionEvents.pipelineFilters(withPipelines),
        )
        /* Unknown stored tokens are ignored rather than fatal */
        assertTrue(SubscriptionEvents.wants(base + "someday:new", issue))
    }
}
