/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

class CvePropertiesTests {

    private fun bind(vararg pairs: Pair<String, String>): CveProperties =
        Binder(MapConfigurationPropertySource(mapOf(*pairs)))
            .bindOrCreate("streampack.specs.cve", CveProperties::class.java)

    @Test
    fun `configured aliases add to the shipped ones, replace them, or drop them`() {
        val properties =
            bind(
                "streampack.specs.cve.aliases.vertx" to "io.vertx:vertx-core,io.vertx:vertx-web",
                "streampack.specs.cve.aliases.guava" to "com.example:guava",
                "streampack.specs.cve.aliases.h2" to "",
            )
        val aliases = properties.effectiveAliases

        assertEquals(listOf("io.vertx:vertx-core", "io.vertx:vertx-web"), aliases["vertx"])
        assertEquals(listOf("com.example:guava"), aliases["guava"])
        assertFalse("h2" in aliases)
        assertEquals(
            listOf("log4j:log4j", "org.apache.logging.log4j:log4j-core"),
            aliases["log4j"],
        )
    }

    @Test
    fun `expiries are configurable`() {
        val properties = bind("streampack.specs.cve.package-ttl" to "2h")
        assertEquals(java.time.Duration.ofHours(2), properties.packageTtl)
        assertEquals(java.time.Duration.ofDays(1), properties.recordTtl)
    }

    @Test
    fun `the throttle and Maven Central's gap and back-off are configurable`() {
        val defaults = bind()
        assertEquals(10, defaults.throttleRequests)
        assertEquals(java.time.Duration.ofHours(1), defaults.throttleWindow)
        assertEquals(java.time.Duration.ofSeconds(2), defaults.mavenSearchGap)
        assertEquals(java.time.Duration.ofMinutes(15), defaults.mavenBackOff)

        val properties =
            bind(
                "streampack.specs.cve.throttle-requests" to "3",
                "streampack.specs.cve.throttle-window" to "30m",
                "streampack.specs.cve.maven-search-gap" to "5s",
                "streampack.specs.cve.maven-back-off" to "1h",
            )
        assertEquals(3, properties.throttleRequests)
        assertEquals(java.time.Duration.ofMinutes(30), properties.throttleWindow)
        assertEquals(java.time.Duration.ofSeconds(5), properties.mavenSearchGap)
        assertEquals(java.time.Duration.ofHours(1), properties.mavenBackOff)
    }
}
