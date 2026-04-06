/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.repository

import dev.streampack.urltitle.entity.IgnoredHost
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface IgnoredHostRepository : JpaRepository<IgnoredHost, UUID> {
    fun findByHostNameIgnoreCase(hostName: String): IgnoredHost?
}
