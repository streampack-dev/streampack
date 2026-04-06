/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.controller

import dev.streampack.karma.service.KarmaService
import dev.streampack.test.TestSecurityConfiguration
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@Import(TestSecurityConfiguration::class)
class KarmaControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var karmaService: KarmaService

    @BeforeEach
    fun seedData() {
        repeat(3) { karmaService.adjustKarma("alice", 1) }
        repeat(2) { karmaService.adjustKarma("bob", 1) }
        repeat(3) { karmaService.adjustKarma("eve", -1) }
        karmaService.adjustKarma("mallory", -1)
        karmaService.adjustKarma("neutral", 1)
        karmaService.adjustKarma("neutral", -1)
    }

    @Test
    fun `GET leaderboard returns top and bottom lists`() {
        mockMvc.get("/karma/leaderboard?limit=2").andExpect {
            status { isOk() }
            jsonPath("$.limit") { value(2) }
            jsonPath("$.top.length()") { value(2) }
            jsonPath("$.bottom.length()") { value(2) }
            jsonPath("$.top[0].subject") { value("alice") }
            jsonPath("$.top[0].score") { value(3) }
            jsonPath("$.top[1].subject") { value("bob") }
            jsonPath("$.bottom[0].subject") { value("eve") }
            jsonPath("$.bottom[0].score") { value(-3) }
            jsonPath("$.bottom[1].subject") { value("mallory") }
            // removed as information we do not want exposed: it's meaningless anyway
            jsonPath("$.top[0].upvotes") { doesNotExist() }
            jsonPath("$.top[0].downvotes") { doesNotExist() }
            jsonPath("$.bottom[0].upvotes") { doesNotExist() }
            jsonPath("$.bottom[0].downvotes") { doesNotExist() }
        }
    }

    @Test
    fun `GET leaderboard clamps invalid limit`() {
        mockMvc.get("/karma/leaderboard?limit=0").andExpect {
            status { isOk() }
            jsonPath("$.limit") { value(1) }
            jsonPath("$.top.length()") { value(1) }
            jsonPath("$.bottom.length()") { value(1) }
        }
    }
}
