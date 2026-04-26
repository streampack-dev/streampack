/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Provides OpenAPI metadata and security scheme definitions for springdoc */
@Configuration
class OpenApiConfiguration {

    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("server-streampack API")
                    .version("0.1.0")
                    .description(
                        "Bundled HTTP API for the server-streampack distribution of Streampack. " +
                            "Includes blog, authentication, and related site endpoints. " +
                            "Advisory version headers are supported: clients may send " +
                            "'Accept-Version' and responses include 'Content-Version' and " +
                            "resolved 'Accept-Version'."
                    )
            )
            .components(
                Components()
                    .addSecuritySchemes(
                        "bearerAuth",
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT"),
                    )
            )
}
