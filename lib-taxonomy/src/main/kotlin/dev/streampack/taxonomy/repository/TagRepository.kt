/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.repository

import dev.streampack.taxonomy.entity.Tag
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** Queries for flat content tags */
interface TagRepository : JpaRepository<Tag, UUID> {
    @Query("SELECT t FROM Tag t WHERE t.deleted = false") fun findActive(): List<Tag>

    fun findBySlug(slug: String): Tag?

    fun findByName(name: String): Tag?
}
