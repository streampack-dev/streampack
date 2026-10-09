/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/**
 * One of a feed entry's own tags (#139): an RSS `<category>` or an Atom `<category term>`. [name]
 * is normalized (`TagNames.normalize`), one row per entry and name; [raw] is as the feed wrote it.
 */
@Entity
@Table(name = "rss_entry_category")
data class RssEntryCategory(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(name = "entry_id", nullable = false) val entryId: UUID = UUID(0, 0),
    @Column(nullable = false) val name: String = "",
    @Column(nullable = false) val raw: String = "",
)
