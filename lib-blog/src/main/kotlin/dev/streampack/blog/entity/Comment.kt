/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import dev.streampack.core.entity.User
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

/** Nested comment on a blog post, registered users only */
@Entity
@Table(name = "comments")
data class Comment(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post = Post(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    val author: User = User(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_comment_id")
    val parentComment: Comment? = null,
    @Column(columnDefinition = "text", nullable = false) val markdownSource: String = "",
    @Column(columnDefinition = "text", nullable = false) val renderedHtml: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
)
