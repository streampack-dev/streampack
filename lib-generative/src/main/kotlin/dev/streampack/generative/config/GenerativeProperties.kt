/* Joseph B. Ottinger (C)2026 */
package dev.streampack.generative.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for shared generative prompt loading.
 *
 * `lib-generative` resolves prompts from a runtime filesystem directory before falling back to
 * bundled classpath resources supplied by the consumer module.
 *
 * Environment-variable form:
 *
 * `STREAMPACK_GENERATIVE_PROMPT_DIR`
 *
 * Typical value:
 *
 * `/opt/streampack/generative`
 *
 * When blank, only bundled classpath fallback prompts are used.
 */
@ConfigurationProperties(prefix = "streampack.generative")
data class GenerativeProperties(
    /**
     * Filesystem directory to search for external prompt overrides.
     *
     * For a prompt named `suggest-prompt`, this directory is checked for:
     *
     * 1. `suggest-prompt.clj`
     * 2. `suggest-prompt.txt`
     *
     * before the caller-provided classpath fallback is used.
     */
    val promptDir: String = ""
)
