/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.test.TestChannelConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.cors.CorsConfigurationSource

/** Verifies default CORS origins from application.yml */
@SpringBootTest
@Import(TestChannelConfiguration::class)
class CorsConfigurationTests {

    @Autowired lateinit var corsConfigurationSource: CorsConfigurationSource

    @Test
    fun `default CORS origins include localhost and bytecode`() {
        val config =
            corsConfigurationSource.getCorsConfiguration(
                org.springframework.mock.web.MockHttpServletRequest()
            )!!
        val origins = config.allowedOrigins!!
        assertTrue(origins.contains("http://localhost:3000"))
        assertTrue(origins.contains("https://bytecode.news"))
    }
}

/** Verifies custom CORS origins from env var override */
@SpringBootTest(properties = ["CORS_ORIGINS=https://example.com,https://other.example.com"])
@Import(TestChannelConfiguration::class)
class CustomCorsConfigurationTests {

    @Autowired lateinit var corsConfigurationSource: CorsConfigurationSource

    @Test
    fun `CORS origins are configurable via env var`() {
        val config =
            corsConfigurationSource.getCorsConfiguration(
                org.springframework.mock.web.MockHttpServletRequest()
            )!!
        val origins = config.allowedOrigins!!
        assertEquals(2, origins.size)
        assertTrue(origins.contains("https://example.com"))
        assertTrue(origins.contains("https://other.example.com"))
    }
}
