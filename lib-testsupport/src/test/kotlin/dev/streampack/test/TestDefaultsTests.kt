/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

/**
 * lib-testsupport's shared test settings, and how a module's own test configuration combines with
 * them. `spring.config.location` stands in for a module's `application.yml`, which replaces every
 * other `application.yml` on the classpath.
 */
class TestDefaultsTests {

    @Configuration(proxyBeanMethods = false) class Empty

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `module config with defaults`(
        name: String,
        moduleFile: String,
        key: String,
        expected: String,
    ) {
        SpringApplicationBuilder(Empty::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.config.location=classpath:/test-defaults/$moduleFile",
                "streampack.test.shared-database=false",
            )
            .run()
            .use { context -> assertEquals(expected, context.environment.getProperty(key)) }
    }

    @ParameterizedTest(name = "shared database {0}")
    @ValueSource(booleans = [false, true])
    fun `database url has timeouts`(shared: Boolean) {
        SpringApplicationBuilder(Empty::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "spring.config.location=classpath:/test-defaults/module-other.yml",
                "streampack.test.shared-database=$shared",
            )
            .run()
            .use { context ->
                val url = context.environment.getProperty("spring.datasource.url").orEmpty()
                assertTrue(url.contains("connectTimeout=10"), url)
                assertTrue(url.contains("loginTimeout=10"), url)
            }
    }

    @Test
    fun `stub server binds loopback`() {
        val server = loopbackHttpServer()
        try {
            val address = server.address as InetSocketAddress
            assertTrue(address.address.isLoopbackAddress, "bound to ${address.address}")
            assertEquals(InetAddress.getLoopbackAddress(), address.address)
            assertNotEquals(0, address.port)
        } finally {
            server.stop(0)
        }
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> {
            val json =
                TestDefaultsTests::class.java.getResource("/test-defaults/cases.json")?.readText()
                    ?: error("test-defaults/cases.json is missing")
            val cases = JsonMapper.builder().build().readTree(json)
            return (0 until cases.size())
                .map { cases[it] }
                .map {
                    Arguments.of(
                        it["name"].asString(),
                        it["moduleFile"].asString(),
                        it["key"].asString(),
                        it["expected"].asString(),
                    )
                }
        }
    }
}
