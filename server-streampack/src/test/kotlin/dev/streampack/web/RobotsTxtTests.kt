/* Joseph B. Ottinger (C)2026 */
package dev.streampack.web

import dev.streampack.ServerStreampackApplication
import dev.streampack.test.TestChannelConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * The API's robots.txt (#259). The API's JSON is no use to a search crawler, and the sites' own
 * sitemaps cover the content, so every crawler is asked to stay away from all of it. The test
 * fetches it over HTTP, as a crawler does.
 */
@SpringBootTest(
    classes = [ServerStreampackApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestChannelConfiguration::class)
class RobotsTxtTests {
    @LocalServerPort var port: Int = 0

    @Test
    fun `robots txt asks every crawler to stay away from the whole API`() {
        val response =
            HttpClient.newHttpClient()
                .send(
                    HttpRequest.newBuilder(URI.create("http://localhost:$port/robots.txt")).build(),
                    HttpResponse.BodyHandlers.ofString(),
                )

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
            .startsWith("text/plain")
        assertThat(response.body()).isEqualTo("User-agent: *\nDisallow: /\n")
    }
}
