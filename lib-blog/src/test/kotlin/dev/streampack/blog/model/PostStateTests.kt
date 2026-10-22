/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A post's state from its status and publication time (#273) */
class PostStateTests {
    private val now = Instant.parse("2026-10-21T12:00:00Z")

    @Test
    fun `a post that isn't approved is a draft, whatever its time`() {
        assertEquals(PostState.DRAFT, PostState.of(PostStatus.DRAFT, null, now))
        assertEquals(PostState.DRAFT, PostState.of(PostStatus.DRAFT, now.minusSeconds(60), now))
    }

    @Test
    fun `an approved post with a future time is scheduled`() {
        assertEquals(
            PostState.SCHEDULED,
            PostState.of(PostStatus.APPROVED, now.plusSeconds(60), now),
        )
    }

    @Test
    fun `an approved post with no time is scheduled, not published`() {
        assertEquals(PostState.SCHEDULED, PostState.of(PostStatus.APPROVED, null, now))
    }

    @Test
    fun `an approved post due now or earlier is published`() {
        assertEquals(PostState.PUBLISHED, PostState.of(PostStatus.APPROVED, now, now))
        assertEquals(
            PostState.PUBLISHED,
            PostState.of(PostStatus.APPROVED, now.minusSeconds(60), now),
        )
    }
}
