/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab

import dev.streampack.test.TestSecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** `/features` lists `gitlab` exactly when the module is switched on. */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfiguration::class)
class GitLabFeatureToggleTests {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `features lists gitlab when enabled`() {
        mockMvc.get("/features").andExpect {
            status { isOk() }
            jsonPath("$.operationGroups[?(@ == 'gitlab')]") { isNotEmpty() }
        }
    }
}

@SpringBootTest(properties = ["streampack.gitlab.enabled=false"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfiguration::class)
class GitLabFeatureDisabledTests {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `features omits gitlab and the webhook route is absent when disabled`() {
        mockMvc.get("/features").andExpect {
            status { isOk() }
            jsonPath("$.operationGroups[?(@ == 'gitlab')]") { isEmpty() }
        }
        mockMvc
            .post("/webhooks/gitlab") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = "{}"
                header("X-Gitlab-Event", "Issue Hook")
                header("X-Gitlab-Token", "x")
            }
            .andExpect { status { isNotFound() } }
    }
}
