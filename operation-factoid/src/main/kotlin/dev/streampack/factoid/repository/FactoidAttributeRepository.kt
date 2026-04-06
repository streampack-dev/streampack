/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.repository

import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface FactoidAttributeRepository : JpaRepository<FactoidAttribute, UUID> {
    fun findByFactoidSelectorIgnoreCase(selector: String): List<FactoidAttribute>

    fun findByFactoidIdIn(factoidIds: Collection<UUID>): List<FactoidAttribute>

    fun findByFactoidSelectorIgnoreCaseAndAttributeType(
        selector: String,
        attributeType: FactoidAttributeType,
    ): FactoidAttribute?

    /** Search across attribute values, selectors, and updatedBy for a LIKE term */
    @Query(
        """
        SELECT DISTINCT f.selector FROM Factoid f
        LEFT JOIN FactoidAttribute fa ON fa.factoid = f
        WHERE LOWER(f.selector) LIKE :term
           OR LOWER(fa.attributeValue) LIKE :term
           OR LOWER(fa.updatedBy) LIKE :term
        ORDER BY f.selector
        """
    )
    fun searchForTerm(@Param("term") term: String): List<String>

    /** Finds factoid selectors that have an exact tag match within comma-delimited TAGS values */
    @Query(
        """
        SELECT DISTINCT f.selector FROM factoids f
        JOIN factoid_attributes fa ON fa.factoid_id = f.id
        WHERE fa.attribute_type = 'TAGS'
          AND LOWER(:tag) = ANY(
              SELECT TRIM(BOTH FROM LOWER(t))
              FROM unnest(string_to_array(fa.attribute_value, ',')) AS t
          )
        ORDER BY f.selector
        """,
        nativeQuery = true,
    )
    fun findSelectorsByTag(@Param("tag") tag: String): List<String>

    @Query(
        """
        SELECT TRIM(BOTH FROM LOWER(t.value)) AS name, COUNT(*) AS count
        FROM factoid_attributes fa
        CROSS JOIN LATERAL unnest(string_to_array(fa.attribute_value, ',')) AS t(value)
        WHERE fa.attribute_type = 'TAGS'
          AND fa.attribute_value IS NOT NULL
          AND TRIM(BOTH FROM t.value) <> ''
          AND LEFT(TRIM(BOTH FROM LOWER(t.value)), 1) <> '_'
        GROUP BY TRIM(BOTH FROM LOWER(t.value))
        ORDER BY COUNT(*) DESC, TRIM(BOTH FROM LOWER(t.value)) ASC
        """,
        nativeQuery = true,
    )
    fun findTagCounts(): List<FactoidNameCountProjection>
}

interface FactoidNameCountProjection {
    val name: String
    val count: Long
}
