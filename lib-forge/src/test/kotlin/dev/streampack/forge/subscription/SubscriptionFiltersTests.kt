/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeReleaseInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Event selectors on a subscription (#267), and what the stored tokens mean. */
class SubscriptionFiltersTests {
    private val item = ForgeItem(7, "A thing", "https://example.com/7")

    private fun parsed(vararg tokens: String): SubscriptionFilters =
        assertInstanceOf(
                SubscriptionFilters.Parsed.Filters::class.java,
                SubscriptionFilters.parse(tokens.toList()),
            )
            .filters

    @Test
    fun `no selectors means today's defaults`() {
        val filters = parsed()
        assertTrue(filters.isEmpty)
        assertEquals(listOf("issues", "change_requests", "releases"), filters.tokens())
        assertEquals(SubscriptionEvents.BASE, filters.tokens())
    }

    @Test
    fun `pipeline filters alone keep the defaults, as before`() {
        val filters = parsed("pipelines:failed")
        assertNull(filters.kinds)
        assertEquals(
            listOf("issues", "change_requests", "releases", "pipelines:failed"),
            filters.tokens(),
        )
    }

    @Test
    fun `any other selector makes the given selectors the whole list`() {
        assertEquals(
            listOf("change_requests:merged", "releases"),
            parsed("prs:merged", "releases").tokens(),
        )
        assertEquals(
            listOf("change_requests", "change_requests:merged", "pipelines:failed"),
            parsed("mrs", "mrs:merged", "pipelines:failed").tokens(),
        )
    }

    @Test
    fun `prs and mrs both work on either forge, and opened is the bare word's meaning`() {
        for (word in listOf("prs", "mrs", "prs:opened", "mrs:opened", "PRs")) {
            assertEquals(setOf(EventKind.CHANGE_REQUESTS_OPENED), parsed(word).kinds, word)
        }
        assertEquals(setOf(EventKind.ISSUES_OPENED), parsed("issues:opened").kinds)
        assertEquals(setOf(EventKind.ISSUES_CLOSED), parsed("issues:closed").kinds)
        assertEquals(setOf(EventKind.CHANGE_REQUESTS_CLOSED), parsed("mrs:closed").kinds)
    }

    @Test
    fun `an unknown selector is named`() {
        val unknown =
            assertInstanceOf(
                SubscriptionFilters.Parsed.Unknown::class.java,
                SubscriptionFilters.parse(listOf("prs:merged", "prs:reviewed")),
            )
        assertEquals("prs:reviewed", unknown.token)
    }

    @Test
    fun `stored tokens read back, and render in the forge's own words`() {
        val stored =
            SubscriptionFilters.fromStored(
                listOf("change_requests", "change_requests:merged", "pipelines:failed"),
            )
        assertEquals(" [mrs mrs:merged pipelines:failed]", stored.render(ForgeKind.GITLAB))
        assertEquals(" [prs prs:merged pipelines:failed]", stored.render(ForgeKind.GITHUB))
    }

    @Test
    fun `a subscription with the defaults shows only its pipeline filters, as before`() {
        assertEquals(
            "",
            SubscriptionFilters.fromStored(SubscriptionEvents.BASE).render(ForgeKind.GITHUB),
        )
        assertEquals(
            " [pipelines:failed]",
            SubscriptionFilters.fromStored(SubscriptionEvents.BASE + "pipelines:failed")
                .render(ForgeKind.GITHUB),
        )
    }

    @Test
    fun `a stored subscription from before #267 gets exactly what it got`() {
        val before = SubscriptionEvents.BASE + "pipelines"
        assertTrue(SubscriptionEvents.wants(before, ForgeEvent.IssueOpened(item)))
        assertTrue(SubscriptionEvents.wants(before, ForgeEvent.ChangeRequestOpened(item)))
        assertTrue(
            SubscriptionEvents.wants(
                before,
                ForgeEvent.ReleasePublished(ForgeReleaseInfo("v1", null, "u")),
            ),
        )
        assertFalse(SubscriptionEvents.wants(before, ForgeEvent.IssueClosed(item)))
        assertFalse(SubscriptionEvents.wants(before, ForgeEvent.ChangeRequestMerged(item)))
        assertFalse(SubscriptionEvents.wants(before, ForgeEvent.ChangeRequestClosed(item)))
    }

    @Test
    fun `each new event goes only to the subscriptions that asked for it`() {
        val merges = parsed("prs:merged").tokens()
        assertTrue(SubscriptionEvents.wants(merges, ForgeEvent.ChangeRequestMerged(item)))
        assertFalse(SubscriptionEvents.wants(merges, ForgeEvent.ChangeRequestOpened(item)))
        assertFalse(SubscriptionEvents.wants(merges, ForgeEvent.ChangeRequestClosed(item)))
        assertFalse(SubscriptionEvents.wants(merges, ForgeEvent.IssueOpened(item)))
        val closes = parsed("issues:closed", "prs:closed").tokens()
        assertTrue(SubscriptionEvents.wants(closes, ForgeEvent.IssueClosed(item)))
        assertTrue(SubscriptionEvents.wants(closes, ForgeEvent.ChangeRequestClosed(item)))
        assertFalse(SubscriptionEvents.wants(closes, ForgeEvent.ChangeRequestMerged(item)))
    }

    @Test
    fun `asking for closes or merges is what makes polling look for them`() {
        assertFalse(SubscriptionEvents.wantsClosures(SubscriptionEvents.BASE))
        assertTrue(SubscriptionEvents.wantsClosures(parsed("prs:merged").tokens()))
        assertTrue(SubscriptionEvents.wantsClosures(parsed("issues:closed").tokens()))
        assertFalse(SubscriptionEvents.wantsClosures(parsed("issues", "pipelines").tokens()))
    }
}
