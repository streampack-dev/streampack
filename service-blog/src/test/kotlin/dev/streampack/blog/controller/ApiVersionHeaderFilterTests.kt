/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    properties =
        [
            "streampack.api.version.current=2026-03-10",
            "streampack.api.version.supported=2026-03-10,2026-04-01",
        ]
)
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class ApiVersionHeaderFilterTests {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `response includes default version headers when request header is absent`() {
        mockMvc.get("/posts").andExpect {
            status { isOk() }
            header { string("Content-Version", "2026-03-10") }
            header { string("Accept-Version", "2026-03-10") }
        }
    }

    @Test
    fun `supported Accept-Version is echoed in response`() {
        mockMvc
            .get("/posts") { header("Accept-Version", "2026-04-01") }
            .andExpect {
                status { isOk() }
                header { string("Content-Version", "2026-03-10") }
                header { string("Accept-Version", "2026-04-01") }
            }
    }

    @Test
    fun `unsupported Accept-Version falls back to current version`() {
        mockMvc
            .get("/posts") { header("Accept-Version", "2099-01-01") }
            .andExpect {
                status { isOk() }
                header { string("Content-Version", "2026-03-10") }
                header { string("Accept-Version", "2026-03-10") }
            }
    }
}
