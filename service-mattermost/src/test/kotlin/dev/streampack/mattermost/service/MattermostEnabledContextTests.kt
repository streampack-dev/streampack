/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** The application starts with Mattermost turned on, its beans all satisfied (#223). */
@SpringBootTest(properties = ["streampack.mattermost.enabled=true"])
class MattermostEnabledContextTests {
    @Autowired lateinit var connectionManager: MattermostConnectionManager

    @Test
    fun `the connection manager is created`() {
        assertNotNull(connectionManager)
    }
}
