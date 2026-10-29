/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Component

/**
 * Stops the start when an extension needs a newer server than this one (#294), naming each such
 * extension and the version it needs. A server with no version (a development build) warns and lets
 * them load.
 */
@Component
class ExtensionRequirementsCheck(
    private val extensions: List<Extension>,
    private val server: ServerInfo,
) : InitializingBean {
    private val logger = LoggerFactory.getLogger(ExtensionRequirementsCheck::class.java)

    override fun afterPropertiesSet() {
        val needing = extensions.filter { it.requiresServer != null }
        if (server.version == null) {
            needing.forEach {
                logger.warn(
                    "Extension {} needs server {}; this build has no version, so it loads.",
                    it.name,
                    it.requiresServer,
                )
            }
            return
        }
        val tooNew = needing.filterNot { server.isAtLeast(it.requiresServer!!) }
        if (tooNew.isEmpty()) return
        System.err.println("============================================================")
        System.err.println("EXTENSION CHECK FAILED: this server is ${server.version}")
        System.err.println("============================================================")
        tooNew.forEach { System.err.println("- ${it.name} needs ${it.requiresServer} or later") }
        System.err.println("Upgrade the server, or remove those jars from the ext directory.")
        System.err.println("============================================================")
        throw SilentStartupException("An extension needs a newer server. See messages above.")
    }
}
