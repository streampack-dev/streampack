/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.io.ClassPathResource

/**
 * Adds lib-testsupport's shared test settings, from [RESOURCE], as the source with the lowest
 * precedence.
 *
 * Why: a module's own `src/test/resources/application.yml` replaces every other `application.yml`
 * on the classpath, so shared settings in an `application.yml` would be lost to any module with its
 * own file, the disabled tick scheduler among them. Under their own name they can't be replaced,
 * and as the last source every other source wins over them: a module's own file,
 * `@TestPropertySource` and `@SpringBootTest(properties = …)`. A module overrides one key and keeps
 * the rest.
 *
 * A `config/application.yml` was rejected: Spring Boot ranks it above the module's own file, so a
 * module could not override a shared setting.
 *
 * Runs before [SharedTestDatabaseEnvironmentPostProcessor], which reads the datasource URL set
 * here. A missing [RESOURCE] adds nothing.
 */
class TestDefaultsEnvironmentPostProcessor : EnvironmentPostProcessor, Ordered {
    /** Just after Spring Boot's config data (`application.yml`), so the defaults land after it. */
    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE + 11

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val resource = ClassPathResource(RESOURCE)
        if (!resource.exists()) return
        YamlPropertySourceLoader().load("streampackTestDefaults", resource).forEach {
            environment.propertySources.addLast(it)
        }
    }

    companion object {
        /** The shared test settings, on the classpath. */
        const val RESOURCE = "streampack-test-defaults.yml"
    }
}
