/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.Instant

/**
 * A name that means another tag (#140): [alias] is written as, and looked up as, [tag]. The alias
 * is a normalized name (`TagNames.normalize`), and never a tag of its own.
 */
@Entity
@Table(name = "tag_alias")
data class TagAlias(
    @Id val alias: String = "",
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "tag_id", nullable = false)
    val tag: Tag = Tag(),
    @Column(nullable = false) val createdBy: String = "",
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
