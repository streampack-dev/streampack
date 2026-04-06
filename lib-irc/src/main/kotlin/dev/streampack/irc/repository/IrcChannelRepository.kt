/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.repository

import dev.streampack.irc.entity.IrcChannel
import dev.streampack.irc.entity.IrcNetwork
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface IrcChannelRepository : JpaRepository<IrcChannel, UUID> {
    fun findByNetworkAndNameAndDeletedFalse(network: IrcNetwork, name: String): IrcChannel?

    fun findByNetworkAndDeletedFalse(network: IrcNetwork): List<IrcChannel>
}
