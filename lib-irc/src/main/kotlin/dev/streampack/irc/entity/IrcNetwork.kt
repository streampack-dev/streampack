/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.entity

import dev.streampack.core.model.SecretRef
import dev.streampack.core.persistence.SecretRefConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** Persistent IRC network configuration record */
@Entity
@Table(name = "irc_networks")
data class IrcNetwork(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, unique = true, length = 100) val name: String = "",
    @Column(nullable = false) val host: String = "",
    @Column(nullable = false) val port: Int = 6697,
    @Column(nullable = false) val tls: Boolean = true,
    @Column(nullable = false, length = 50) val nick: String = "",
    @Convert(converter = SecretRefConverter::class)
    @Column(length = 100)
    val saslAccount: SecretRef? = null,
    @Convert(converter = SecretRefConverter::class) val saslPassword: SecretRef? = null,
    @Column(length = 10) val signalCharacter: String? = null,
    @Column(nullable = false) val autoconnect: Boolean = false,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) val updatedAt: Instant = Instant.now(),
    @Column(nullable = false) val deleted: Boolean = false,
) {
    /** Summary string for status display */
    fun toSummary(): String {
        val tlsFlag = if (tls) "TLS" else "plaintext"
        val autoFlag = if (autoconnect) "autoconnect" else "manual"
        return "$name ($host:$port $tlsFlag, nick=$nick, $autoFlag)"
    }
}
