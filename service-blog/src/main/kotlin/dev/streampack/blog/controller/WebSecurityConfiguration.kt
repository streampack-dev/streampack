/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * HTTP security configuration for the REST API.
 *
 * Authentication and authorization are handled at the operation layer, not the HTTP filter layer.
 * This chain disables CSRF (stateless API), enforces stateless sessions, and permits all requests.
 * OAuth2 login is activated only when OAuth2 client registrations are configured.
 */
@Configuration
class WebSecurityConfiguration(
    @Value("\${CORS_ORIGINS:http://localhost:3000,http://localhost:3003,https://bytecode.news}")
    private val corsOrigins: String,
    @Autowired(required = false) private val oidcSuccessHandler: OidcAuthenticationSuccessHandler?,
    @Autowired(required = false)
    private val clientRegistrationRepository: ClientRegistrationRepository?,
) {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = corsOrigins.split(",").map { it.trim() }
        config.allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
        config.allowedHeaders =
            listOf("Authorization", "Content-Type", "Accept", "Accept-Version", "Cookie")
        config.exposedHeaders = listOf("Content-Version", "Accept-Version", "Set-Cookie")
        config.allowCredentials = true
        config.maxAge = 3600L
        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }

        if (oidcSuccessHandler != null && clientRegistrationRepository != null) {
            http.oauth2Login { it.successHandler(oidcSuccessHandler) }
        }

        return http.build()
    }
}
