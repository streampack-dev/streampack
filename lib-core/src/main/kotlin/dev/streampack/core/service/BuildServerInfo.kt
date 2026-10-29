/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Service

/** [ServerInfo] from the build's own record, which a test or an IDE run doesn't have. */
@Service
class BuildServerInfo(@Autowired(required = false) buildProperties: BuildProperties?) : ServerInfo {
    override val version: String? = buildProperties?.version
}
