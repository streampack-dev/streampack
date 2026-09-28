/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * What a public response depends on, as cheap aggregate queries, so a conditional GET can be
 * answered with 304 without running the listing, rendering or serialising anything (#83).
 *
 * Each validator mirrors the filters of the query behind its endpoint. A post is visible to
 * anonymous readers when it is approved, published by now and not deleted; the board, tag listings,
 * search and taxonomy tag counts also leave out posts in hidden (`_`) categories. Sets are
 * fingerprinted by their members, their newest change and their comments, so a post falling due (no
 * row changes, only time) or a comment being added changes the validator too.
 */
@Component
class ContentValidators(private val jdbc: NamedParameterJdbcTemplate) {
    /** An opaque fingerprint of the data, and the newest change it knows of (for Last-Modified). */
    data class Validator(val fingerprint: String, val lastModified: Instant?)

    fun board(now: Instant): Validator = postSet(now, NOT_HIDDEN)

    fun byTag(tag: String, now: Instant): Validator =
        postSet(
            now,
            """EXISTS (SELECT 1 FROM post_tags pt JOIN tags t ON t.id = pt.tag_id
                       WHERE pt.post_id = p.id AND lower(t.name) = lower(:tag)) AND $NOT_HIDDEN""",
            mapOf("tag" to tag),
        )

    fun byCategory(category: String, now: Instant): Validator =
        postSet(
            now,
            """EXISTS (SELECT 1 FROM post_categories pc JOIN categories c ON c.id = pc.category_id
                       WHERE pc.post_id = p.id AND c.name = :category)""",
            mapOf("category" to category),
        )

    /** Search can match any visible post, so its validator covers them all. */
    fun searchable(now: Instant): Validator = postSet(now, NOT_HIDDEN)

    /** The post a slug path names, with its comments, or null if anonymous readers can't see it. */
    fun article(path: String, now: Instant): Validator? =
        single("s.path = :path", mapOf("path" to path), now)

    /** A page: a post in a hidden category, addressed by its slug. */
    fun page(slug: String, now: Instant): Validator? =
        single(
            """s.path = :path AND EXISTS (SELECT 1 FROM post_categories pc JOIN categories c
                   ON c.id = pc.category_id WHERE pc.post_id = p.id AND c.name LIKE '\_%' ESCAPE '\')""",
            mapOf("path" to slug),
            now,
        )

    /**
     * Everything the taxonomy snapshot counts: visible posts' tags and categories, and factoid
     * tags.
     */
    fun taxonomy(now: Instant): Validator {
        val posts = postSet(now, "TRUE")
        val terms =
            jdbc.queryForObject(
                """
                SELECT concat_ws('|',
                  (SELECT count(*) || ':' || coalesce(sum(hashtext(name || deleted::text)), 0) FROM tags),
                  (SELECT count(*) || ':' || coalesce(sum(hashtext(name || deleted::text)), 0) FROM categories),
                  (SELECT count(*) || ':' || coalesce(sum(hashtext(post_id::text || category_id::text)), 0)
                     FROM post_categories))
                """,
                MapSqlParameterSource(),
                String::class.java,
            )
        val factoids =
            if (factoidTablesPresent) {
                jdbc.queryForObject(
                    """
                    SELECT count(*) || ':' || coalesce(max(updated_at)::text, '') || ':'
                        || coalesce(sum(hashtext(coalesce(attribute_value, ''))), 0)
                    FROM factoid_attributes WHERE attribute_type = 'TAGS'
                    """,
                    MapSqlParameterSource(),
                    String::class.java,
                )
            } else {
                ""
            }
        return Validator("${posts.fingerprint}|$terms|$factoids", posts.lastModified)
    }

    // Factoids are an optional module; taxonomy counts them only when their tables exist.
    private val factoidTablesPresent: Boolean by lazy {
        jdbc.queryForObject(
            "SELECT to_regclass('public.factoid_attributes') IS NOT NULL",
            MapSqlParameterSource(),
            Boolean::class.java,
        ) == true
    }

    private fun postSet(
        now: Instant,
        filter: String,
        params: Map<String, Any> = emptyMap(),
    ): Validator {
        val source = MapSqlParameterSource(params).addValue("now", Timestamp.from(now))
        return jdbc.queryForObject(
            """
            WITH visible AS (
              SELECT p.id, p.updated_at, p.published_at FROM posts p
              WHERE $VISIBLE AND $filter
            )
            SELECT
              (SELECT count(*) || ':' || coalesce(sum(hashtext(id::text)), 0) FROM visible) AS members,
              (SELECT max(greatest(updated_at, published_at)) FROM visible) AS latest,
              (SELECT count(*) || ':' || coalesce(sum(hashtext(pt.post_id::text || pt.tag_id::text)), 0)
                 FROM post_tags pt WHERE pt.post_id IN (SELECT id FROM visible)) AS tagging,
              (SELECT count(*) || ':' || count(*) FILTER (WHERE NOT c.deleted) || ':'
                   || coalesce(max(c.updated_at)::text, '')
                 FROM comments c WHERE c.post_id IN (SELECT id FROM visible)) AS talk
            """,
            source,
        ) { rs, _ ->
            val latest = rs.getTimestamp("latest")?.toInstant()
            Validator(
                listOf(
                        rs.getString("members"),
                        latest,
                        rs.getString("tagging"),
                        rs.getString("talk"),
                    )
                    .joinToString("|"),
                latest,
            )
        }!!
    }

    private fun single(where: String, params: Map<String, Any>, now: Instant): Validator? {
        val source = MapSqlParameterSource(params).addValue("now", Timestamp.from(now))
        return jdbc
            .query(
                """
                SELECT p.id, p.updated_at, p.published_at,
                  (SELECT s2.path FROM slugs s2 WHERE s2.post_id = p.id AND s2.canonical LIMIT 1) AS canonical,
                  (SELECT count(*) || ':' || coalesce(sum(hashtext(pt.tag_id::text)), 0)
                     FROM post_tags pt WHERE pt.post_id = p.id) AS tagging,
                  (SELECT count(*) || ':' || coalesce(sum(hashtext(pc.category_id::text)), 0)
                     FROM post_categories pc WHERE pc.post_id = p.id) AS filing,
                  (SELECT count(*) || ':' || count(*) FILTER (WHERE NOT c.deleted) FROM comments c
                     WHERE c.post_id = p.id) AS talk,
                  (SELECT max(c.updated_at) FROM comments c WHERE c.post_id = p.id) AS talked
                FROM slugs s JOIN posts p ON p.id = s.post_id
                WHERE $where AND $VISIBLE
                LIMIT 1
                """,
                source,
            ) { rs, _ ->
                val updated = rs.getTimestamp("updated_at").toInstant()
                val published = rs.getTimestamp("published_at").toInstant()
                val talked = rs.getTimestamp("talked")?.toInstant()
                val lastModified = listOfNotNull(updated, published, talked).max()
                Validator(
                    listOf(
                            rs.getString("id"),
                            updated,
                            published,
                            rs.getString("canonical"),
                            rs.getString("tagging"),
                            rs.getString("filing"),
                            rs.getString("talk"),
                            talked,
                        )
                        .joinToString("|"),
                    lastModified,
                )
            }
            .firstOrNull()
    }

    companion object {
        private const val VISIBLE =
            "p.status = 'APPROVED' AND p.deleted = FALSE AND p.published_at <= :now"
        private const val NOT_HIDDEN =
            """NOT EXISTS (SELECT 1 FROM post_categories pc JOIN categories c ON c.id = pc.category_id
                           WHERE pc.post_id = p.id AND c.name LIKE '\_%' ESCAPE '\')"""
    }
}
