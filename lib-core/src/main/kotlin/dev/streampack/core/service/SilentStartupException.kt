/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/**
 * Startup-failure exception intentionally created without a stack trace to keep console output
 * concise for actionable operational errors.
 */
class SilentStartupException(message: String) : RuntimeException(message, null, false, false)
