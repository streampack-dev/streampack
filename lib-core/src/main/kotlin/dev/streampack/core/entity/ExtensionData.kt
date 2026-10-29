/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** One document an extension keeps, keyed by (extension, collection, item_id) (#294). */
@Entity
@Table(name = "extension_data")
data class ExtensionData(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 100) val extension: String = "",
    @Column(nullable = false, length = 100) val collection: String = "",
    @Column(name = "item_id", nullable = false, length = 255) val itemId: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val data: Map<String, Any> = emptyMap(),
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
