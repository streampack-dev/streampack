/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Associates a post with a tag */
@Entity
@Table(
    name = "post_tags",
    uniqueConstraints = [UniqueConstraint(columnNames = ["post_id", "tag_id"])],
)
data class PostTag(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post = Post(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tag_id", nullable = false)
    val tag: Tag = Tag(),
)
