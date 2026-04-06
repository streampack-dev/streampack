/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Shared test-only security configuration for isolated web-layer/service tests that do not load the
 * app module's production security chain.
 */
@Configuration
class TestSecurityConfiguration {

    @Bean
    @ConditionalOnBean(HttpSecurity::class)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .build()
}
