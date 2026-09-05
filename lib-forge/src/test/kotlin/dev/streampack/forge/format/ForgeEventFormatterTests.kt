/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.format

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeReleaseInfo
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
}
