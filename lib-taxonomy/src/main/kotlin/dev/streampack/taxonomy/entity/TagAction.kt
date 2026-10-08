/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.entity

import dev.streampack.taxonomy.model.TagActionType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** One change to the tag vocabulary (#140): what, to which tag, who made it and when. */
@Entity
@Table(name = "tag_action")
data class TagAction(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val action: TagActionType = TagActionType.ALIAS,
    @Column(nullable = false) val subject: String = "",
    @Column(columnDefinition = "TEXT") val detail: String? = null,
    @Column(nullable = false) val actor: String = "",
    @Column(nullable = false) val actedAt: Instant = Instant.now(),
)
