/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** URL path alias pointing to a post */
@Entity
@Table(name = "slugs")
data class Slug(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true) val path: String = "",
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post = Post(),
    @Column(nullable = false) val canonical: Boolean = false,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
