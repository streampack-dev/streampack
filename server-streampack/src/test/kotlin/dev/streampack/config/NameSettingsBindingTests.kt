/* Joseph B. Ottinger (C)2026 */
package dev.streampack.config

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.config.RssFeedProperties
import dev.streampack.blog.outgoing.OutgoingLinksProperties
import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.fetch.FetchProperties
import dev.streampack.irc.config.IrcProperties
import dev.streampack.karma.config.KarmaProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource

/**
 * STREAMPACK_NAME names the deployment (#228): the application, and the defaults of the site's
 * name, the IRC identity, the karma-immune subjects and the user agent. Each setting of its own
 * still wins. Unset, everything is as it was. The main application.yml is read from the source
 * tree, as the test one shadows it on the classpath.
 */
class NameSettingsBindingTests {
    private fun binder(environment: Map<String, Any>): Binder {
        val env = StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                environment,
            ),
        )
        YamlPropertySourceLoader()
            .load("application.yml", FileSystemResource("src/main/resources/application.yml"))
            .forEach { env.propertySources.addLast(it) }
        return Binder.get(env)
    }

    private fun <T : Any> Binder.of(prefix: String, type: Class<T>): T = bind(prefix, type).get()

    @Test
    fun `unset, the names are as they were`() {
        val b = binder(emptyMap())
        assertEquals("nevet", b.bind("spring.application.name", String::class.java).get())
        assertEquals("nevet", b.of("streampack", StreampackProperties::class.java).name)
        assertEquals("Nevet", b.of("streampack.blog", BlogProperties::class.java).siteName)
        assertEquals("Nevet IRC Bridge", b.of("streampack.irc", IrcProperties::class.java).identity)
        assertEquals(
            listOf("nevet"),
            b.of("streampack.karma", KarmaProperties::class.java).immuneSubjects,
        )
        assertEquals(
            "ByteCode.News (+https://bytecode.news)",
            b.of("streampack.fetch", FetchProperties::class.java).userAgent,
        )
        assertEquals(
            "ByteCode.News mentions (+https://bytecode.news)",
            b.of("streampack.blog.outgoing", OutgoingLinksProperties::class.java).userAgent,
        )
    }

    @Test
    fun `STREAMPACK_NAME sets every name it defaults`() {
        val b = binder(mapOf("STREAMPACK_NAME" to "gholem", "BASE_URL" to "https://gholem.example"))
        assertEquals("gholem", b.bind("spring.application.name", String::class.java).get())
        assertEquals("gholem", b.of("streampack", StreampackProperties::class.java).name)
        assertEquals("gholem", b.of("streampack.blog", BlogProperties::class.java).siteName)
        assertEquals(
            "gholem IRC Bridge",
            b.of("streampack.irc", IrcProperties::class.java).identity,
        )
        assertEquals(
            listOf("gholem"),
            b.of("streampack.karma", KarmaProperties::class.java).immuneSubjects,
        )
        assertEquals(
            "gholem (+https://gholem.example)",
            b.of("streampack.fetch", FetchProperties::class.java).userAgent,
        )
        assertEquals(
            "gholem mentions (+https://gholem.example)",
            b.of("streampack.blog.outgoing", OutgoingLinksProperties::class.java).userAgent,
        )
    }

    @Test
    fun `a setting of its own still wins over the name`() {
        val b =
            binder(
                mapOf(
                    "STREAMPACK_NAME" to "gholem",
                    "BLOG_SITE_NAME" to "Gholem News",
                    "IRC_IDENTITY" to "Gholem Bot",
                    "KARMA_IMMUNE_SUBJECTS" to "gholem,golem",
                    "STREAMPACK_FETCH_USERAGENT" to "custom/1.0",
                ),
            )
        assertEquals("Gholem News", b.of("streampack.blog", BlogProperties::class.java).siteName)
        assertEquals("Gholem Bot", b.of("streampack.irc", IrcProperties::class.java).identity)
        assertEquals(
            listOf("gholem", "golem"),
            b.of("streampack.karma", KarmaProperties::class.java).immuneSubjects,
        )
        assertEquals("custom/1.0", b.of("streampack.fetch", FetchProperties::class.java).userAgent)
    }

    @Test
    fun `the feed's title follows the site's name, and its own settings win (#233)`() {
        val feed = { env: Map<String, Any> ->
            binder(env).of("streampack.blog.feed", RssFeedProperties::class.java)
        }
        assertEquals("Nevet", feed(emptyMap()).title)
        assertEquals("JVM ecosystem news and community content", feed(emptyMap()).description)
        assertEquals("ByteCode.News", feed(mapOf("BLOG_SITE_NAME" to "ByteCode.News")).title)
        assertEquals("gholem", feed(mapOf("STREAMPACK_NAME" to "gholem")).title)
        val own =
            feed(
                mapOf(
                    "BLOG_SITE_NAME" to "ByteCode.News",
                    "BLOG_FEED_TITLE" to "bytecode.news",
                    "BLOG_FEED_DESCRIPTION" to "News for the team",
                ),
            )
        assertEquals("bytecode.news", own.title)
        assertEquals("News for the team", own.description)
    }
}
