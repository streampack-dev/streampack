/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest
class BlogApplicationTests {

    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `application context loads`() {
        assertNotNull(context)
    }
}
