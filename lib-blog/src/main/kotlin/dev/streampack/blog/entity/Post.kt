/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.entity

import dev.streampack.blog.model.PostStatus
import dev.streampack.core.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UuidGenerator
import org.hibernate.type.SqlTypes

/** Blog post with markdown source and pre-rendered HTML */
@Entity
@Table(name = "posts")
data class Post(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false) val title: String = "",
    @Column(columnDefinition = "text", nullable = false) val markdownSource: String = "",
    @Column(columnDefinition = "text", nullable = false) val renderedHtml: String = "",
    @Column(columnDefinition = "text") val excerpt: String? = null,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: PostStatus = PostStatus.DRAFT,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_id") val author: User? = null,
    val publishedAt: Instant? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
    @Column(nullable = false) val sortOrder: Int = 0,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    val metadata: Map<String, Any> = emptyMap(),
)
