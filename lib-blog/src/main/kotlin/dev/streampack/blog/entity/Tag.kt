/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Flat content tag with URL-friendly slug */
@Entity
@Table(name = "tags")
data class Tag(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true) val name: String = "",
    @Column(nullable = false, unique = true) val slug: String = "",
    @Column(nullable = false) val deleted: Boolean = false,
)
