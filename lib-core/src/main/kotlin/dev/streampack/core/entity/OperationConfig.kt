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

/**
 * Per-provenance operation enablement and configuration, keyed by (provenance_pattern,
 * operation_group)
 */
@Entity
@Table(name = "operation_config")
data class OperationConfig(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 500) val provenancePattern: String = "",
    @Column(nullable = false, length = 100) val operationGroup: String = "",
    @Column(nullable = false) val enabled: Boolean = true,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val config: Map<String, Any> = emptyMap(),
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
)
