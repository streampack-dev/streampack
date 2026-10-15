/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.test.TestChannelConfiguration
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Every post feed takes its channel from the same settings (#233). */
abstract class FeedChannelTests {
    @Autowired lateinit var mockMvc: MockMvc

    fun channel(path: String): String =
        mockMvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString

    val paths = listOf("/blog/rss.xml", "/blog/atom.xml", "/feed.xml", "/feed.atom")
}

@SpringBootTest(
    properties =
        ["streampack.blog.site-name=Gholem", "streampack.blog.feed.description=News for the team"],
)
@AutoConfigureMockMvc
@Import(TestChannelConfiguration::class)
class FeedSettingsTests : FeedChannelTests() {
    @Test
    fun `the title is the site's name, and the description the setting, on every post feed`() {
        for (path in paths) {
            val xml = channel(path)
            assertTrue(xml.contains("<title>Gholem</title>"), "$path: $xml")
            assertTrue(xml.contains("News for the team"), "$path: $xml")
            assertFalse(xml.contains("bytecode.news"), "$path: $xml")
        }
    }
}

@SpringBootTest(
    properties =
        ["streampack.blog.site-name=Gholem", "streampack.blog.feed.title=Gholem Dispatches"],
)
@AutoConfigureMockMvc
@Import(TestChannelConfiguration::class)
class FeedTitleSettingTests : FeedChannelTests() {
    @Test
    fun `a feed title of its own wins over the site's name`() {
        for (path in paths) {
            assertTrue(channel(path).contains("<title>Gholem Dispatches</title>"), path)
        }
    }
}
