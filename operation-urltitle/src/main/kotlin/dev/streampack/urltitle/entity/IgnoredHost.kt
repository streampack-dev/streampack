/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

@Entity
@Table(name = "ignored_hosts")
data class IgnoredHost(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true, length = 255) val hostName: String = "",
)
