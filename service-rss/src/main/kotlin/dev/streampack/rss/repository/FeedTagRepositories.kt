/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.repository

import dev.streampack.rss.entity.RssEntryCategory
import dev.streampack.rss.entity.RssFeedTag
import dev.streampack.rss.model.FeedTagStatus
import java.util.UUID
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/** How many entries, across how many feeds, carry the feed tag [name]. */
interface FeedTagCount {
    val name: String
    val entries: Long
    val feeds: Long
}

/** Feed entries' own tags (#139). */
interface RssEntryCategoryRepository : JpaRepository<RssEntryCategory, UUID> {
    fun findByEntryIdIn(entryIds: Collection<UUID>): List<RssEntryCategory>

    @Query(
        value =
            """
            SELECT c.name AS name, COUNT(*) AS entries, COUNT(DISTINCT e.feed_id) AS feeds
            FROM rss_entry_category c JOIN rss_entries e ON e.id = c.entry_id
            WHERE c.name IN (:names)
            GROUP BY c.name
            """,
        nativeQuery = true,
    )
    fun countByName(@Param("names") names: Collection<String>): List<FeedTagCount>

    /** The ways feeds wrote [name], most used first. */
    @Query(
        value =
            """
            SELECT c.raw FROM rss_entry_category c WHERE c.name = :name
            GROUP BY c.raw ORDER BY COUNT(*) DESC, c.raw LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findWritten(@Param("name") name: String, @Param("limit") limit: Int): List<String>

    /** The newest entries carrying [name]. */
    @Query(
        value =
            """
            SELECT c.entry_id FROM rss_entry_category c JOIN rss_entries e ON e.id = c.entry_id
            WHERE c.name = :name
            ORDER BY COALESCE(e.published_at, e.created_at) DESC, e.created_at DESC LIMIT :limit
            """,
        nativeQuery = true,
    )
    fun findExampleEntryIds(@Param("name") name: String, @Param("limit") limit: Int): List<UUID>
}

/** Feed tags the vocabulary didn't know, and what became of them (#139). */
interface RssFeedTagRepository : JpaRepository<RssFeedTag, String> {
    /** Those in [status] (all when null): the most carried first, then the most recently seen. */
    @Query(
        """
        SELECT t FROM RssFeedTag t
        WHERE (:status IS NULL OR t.status = :status)
        ORDER BY t.entries DESC, t.feeds DESC, t.lastSeen DESC, t.name
        """
    )
    fun findPage(status: FeedTagStatus?, pageable: Pageable): List<RssFeedTag>

    fun countByStatus(status: FeedTagStatus): Long
}
