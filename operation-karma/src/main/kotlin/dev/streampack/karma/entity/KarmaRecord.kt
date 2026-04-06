/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

@Entity
@Table(name = "karma_records")
data class KarmaRecord(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 200) val subject: String = "",
    @Column(nullable = false) val recordDate: LocalDate = LocalDate.now(),
    @Column(nullable = false) val delta: Int = 0,
)
