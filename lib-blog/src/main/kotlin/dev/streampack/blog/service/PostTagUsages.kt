/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.taxonomy.TagUsages
import dev.streampack.taxonomy.repository.TagRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Posts' tags for the vocabulary (#140): an alias or split re-points `post_tags` from the old tag
 * to the new ones. A post that already carries a new tag keeps its one row for it. The search
 * vector follows through the `post_tags` triggers.
 */
@Component
class PostTagUsages(private val tags: TagRepository, private val jdbc: JdbcTemplate) : TagUsages {
    override val kind: String = "posts"

    override fun retag(from: String, to: List<String>, actor: String): Int {
        val old =
            jdbc.queryForList(
                "SELECT id FROM tags WHERE LOWER(TRIM(name)) = ?",
                java.util.UUID::class.java,
                from,
            )
        if (old.isEmpty()) return 0
        val targets = to.map { name -> tags.findByName(name)?.id ?: error("No tag '$name'") }
        var posts = 0
        for (oldId in old) {
            posts +=
                jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT post_id) FROM post_tags WHERE tag_id = ?",
                    Int::class.java,
                    oldId,
                ) ?: 0
            for (target in targets) {
                if (target == oldId) continue
                jdbc.update(
                    """
                    INSERT INTO post_tags (id, post_id, tag_id)
                    SELECT gen_random_uuid(), pt.post_id, ?
                    FROM post_tags pt
                    WHERE pt.tag_id = ?
                      AND NOT EXISTS (
                        SELECT 1 FROM post_tags x WHERE x.post_id = pt.post_id AND x.tag_id = ?
                      )
                    """,
                    target,
                    oldId,
                    target,
                )
            }
            jdbc.update("DELETE FROM post_tags WHERE tag_id = ?", oldId)
        }
        return posts
    }
}
