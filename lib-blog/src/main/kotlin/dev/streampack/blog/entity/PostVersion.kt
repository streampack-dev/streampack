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

/**
 * A post in another form (#196): for now, a recording of it ([KIND_AUDIO]). One per post, [kind]
 * and [variant]; a new upload replaces the content in place, so its address stays the same.
 *
 * The bytes live in media storage under [storageKey], a key the server generates. [sourceHash] is
 * the hash of the post's markdown when this was attached, so a reader can tell whether the post has
 * been edited since; [contentHash] is the hash of the bytes, for ETags.
 */
@Entity
@Table(name = "post_version")
data class PostVersion(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", nullable = false)
    val post: Post = Post(),
    @Column(nullable = false) val kind: String = KIND_AUDIO,
    @Column val variant: String? = null,
    @Column(nullable = false) val mediaType: String = "",
    @Column(name = "size_bytes", nullable = false) val size: Long = 0,
    @Column(name = "duration_seconds") val durationSeconds: Int? = null,
    @Column val note: String? = null,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "maker_id") val maker: User? = null,
    @Column val makerModel: String? = null,
    @Column(nullable = false) val sourceHash: String = "",
    @Column(nullable = false) val contentHash: String = "",
    @Column(nullable = false, unique = true) val storageKey: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column val replacedAt: Instant? = null,
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
) {
    companion object {
        /** A recording of the post: the only kind accepted so far. */
        const val KIND_AUDIO = "audio"

        /** The kinds uploads accept. */
        val KINDS = setOf(KIND_AUDIO)
    }
}
