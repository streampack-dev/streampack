/* Joseph B. Ottinger (C)2026 */
package dev.streampack.extension

import dev.streampack.ServerStreampackApplication
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.JwtService
import dev.streampack.core.service.RequestCaller
import dev.streampack.test.TestChannelConfiguration
import dev.streampack.web.auth.AuthCookieNames
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import

/**
 * An extension's controller in the running server (#302): it gets the server's [RequestCaller], and
 * a request signed in with the server's own JWT reaches it through the server's security chain. The
 * controller is [extensionprobe.ProbeController], outside `dev.streampack`, so no other test's scan
 * finds it (the OpenAPI document written by OpenApiGeneratorTest stays the server's own).
 */
@SpringBootTest(
    classes = [ServerStreampackApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.main.allow-bean-definition-overriding=true"],
)
@Import(TestChannelConfiguration::class, extensionprobe.ProbeController::class)
class ExtensionEndpointTests {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jwtService: JwtService

    private val ada = UserPrincipal(UUID.randomUUID(), "ada", "Ada", Role.ADMIN)

    private fun get(vararg headers: String): HttpResponse<String> =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:$port/ext/probe"))
                    .apply { if (headers.isNotEmpty()) headers(*headers) }
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

    @Test
    fun `nobody signed in is nobody`() {
        val response = get()

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).isEqualTo("nobody")
    }

    @Test
    fun `a bearer token names the caller`() {
        val response = get("Authorization", "Bearer ${jwtService.generateToken(ada)}")

        assertThat(response.body()).isEqualTo("ada ADMIN bearer")
    }

    @Test
    fun `the access-token cookie names the caller`() {
        val response =
            get("Cookie", "${AuthCookieNames.ACCESS_TOKEN}=${jwtService.generateToken(ada)}")

        assertThat(response.body()).isEqualTo("ada ADMIN cookie")
    }
}
