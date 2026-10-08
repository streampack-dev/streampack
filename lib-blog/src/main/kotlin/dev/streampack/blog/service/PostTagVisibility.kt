/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.taxonomy.TagNames
import java.time.Instant

/**
 * Which of a post's tags a response shows (#140). System tags (`_idea`) are for the site's own
 * workflow: an admin sees them on a post that isn't published yet (a draft, or one scheduled for
 * later), so the editor and the pending list keep them. A published post never shows them, to
 * anyone, and neither do listings: the post page, the SSR page and MCP's `get_post` all leave them
 * off.
 */
object PostTagVisibility {

    /** [tags] of [post] as [viewer] sees them. */
    fun forViewer(
        tags: List<String>,
        post: Post,
        viewer: UserPrincipal?,
        now: Instant = Instant.now(),
    ): List<String> = if (isAdmin(viewer) && !isPublished(post, now)) tags else withoutSystem(tags)

    /** [tags] without system tags: what any public response shows. */
    fun withoutSystem(tags: List<String>): List<String> = tags.filterNot(TagNames::isSystem)

    private fun isAdmin(viewer: UserPrincipal?): Boolean =
        viewer?.role == Role.ADMIN || viewer?.role == Role.SUPER_ADMIN

    private fun isPublished(post: Post, now: Instant): Boolean =
        post.status == PostStatus.APPROVED && post.publishedAt?.isAfter(now) == false
}
