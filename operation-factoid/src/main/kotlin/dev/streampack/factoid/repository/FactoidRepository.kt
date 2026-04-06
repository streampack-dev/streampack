/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.repository

import dev.streampack.factoid.entity.Factoid
import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FactoidRepository : JpaRepository<Factoid, UUID> {
    fun findBySelectorIgnoreCase(selector: String): Factoid?

    /** Paginated listing of all factoids ordered by selector */
    fun findAllByOrderBySelectorAsc(pageable: Pageable): Page<Factoid>

    /** Paginated search across selectors */
    @Query(
        "SELECT f FROM Factoid f WHERE LOWER(f.selector) LIKE :term ORDER BY f.selector",
        countQuery = "SELECT COUNT(f) FROM Factoid f WHERE LOWER(f.selector) LIKE :term",
    )
    fun searchBySelector(@Param("term") term: String, pageable: Pageable): Page<Factoid>

    /** Atomically increments the access counter and sets last-accessed timestamp */
    @Modifying(clearAutomatically = true)
    @Query(
        "UPDATE factoids SET access_count = access_count + 1, last_accessed_at = NOW() WHERE LOWER(selector) = LOWER(:selector)",
        nativeQuery = true,
    )
    fun incrementAccessCount(@Param("selector") selector: String)
}
