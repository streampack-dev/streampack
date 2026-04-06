/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Hierarchical content category with URL-friendly slug */
@Entity
@Table(name = "categories")
data class Category(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true) val name: String = "",
    @Column(nullable = false, unique = true) val slug: String = "",
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") val parent: Category? = null,
    @Column(nullable = false) val deleted: Boolean = false,
)
