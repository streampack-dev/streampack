/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.entity

import dev.streampack.core.model.Protocol
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** Maps a protocol-specific external identity to an internal User */
@Entity
@Table(name = "service_bindings")
data class ServiceBinding(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User = User(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) val protocol: Protocol = Protocol.HTTP,
    @Column(nullable = false) val serviceId: String = "",
    @Column(nullable = false) val externalIdentifier: String = "",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val metadata: Map<String, Any> = emptyMap(),
)
