/* Joseph B. Ottinger (C)2026 */
package dev.streampack

import dev.streampack.test.TestChannelConfiguration
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import

@SpringBootTest(
    classes = [ServerStreampackApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestChannelConfiguration::class)
class ServerStreampackApplicationTests {

    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        assertNotNull(context)
    }
}
