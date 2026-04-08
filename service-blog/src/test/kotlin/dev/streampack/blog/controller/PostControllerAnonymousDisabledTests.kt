/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** Verifies that anonymous submission is rejected when the feature flag is off */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
@TestPropertySource(properties = ["streampack.blog.anonymous-submission=false"])
class PostControllerAnonymousDisabledTests {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `POST without auth returns 401 when anonymous submission is disabled`() {
        mockMvc
            .post("/posts") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"title":"Community Post","markdownSource":"Anonymous content.","formLoadedAt":${System.currentTimeMillis() - 5000}}"""
            }
            .andExpect {
                status { isUnauthorized() }
                jsonPath("$.detail") { value("Authentication required") }
            }
    }
}
