/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.repository

import dev.streampack.irc.entity.IrcNetwork
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface IrcNetworkRepository : JpaRepository<IrcNetwork, UUID> {
    fun findByNameAndDeletedFalse(name: String): IrcNetwork?

    /** Any row by name, removed or not; names stay unique across soft deletes */
    fun findByName(name: String): IrcNetwork?

    fun findByDeletedFalse(): List<IrcNetwork>

    fun findByAutoconnectTrueAndDeletedFalse(): List<IrcNetwork>
}
