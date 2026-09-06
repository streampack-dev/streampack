/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

/**
 * Marks a bean that exists only when `streampack.gitlab.enabled=true`. Every operation, service,
 * and controller in the module carries it, so a deployment that does not watch GitLab has no
 * `gitlab` commands, no webhook route, and no polling.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(prefix = "streampack.gitlab", name = ["enabled"], havingValue = "true")
annotation class ConditionalOnGitLab
