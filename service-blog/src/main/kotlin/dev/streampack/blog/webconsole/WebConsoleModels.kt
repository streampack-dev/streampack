/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** A command for the web console: one line, as typed at the stdin console. */
@Schema(description = "One command line, as at the console: no prefix, no newlines.")
data class WebConsoleCommand(val line: String? = null)

/** A command accepted for processing: its id, which its results on the stream carry. */
data class WebConsoleAccepted(val correlationId: String)

/**
 * A `result` event on the stream: what a command (or something addressed to this admin's console)
 * produced. A command may produce none, one or several.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "The data of a `result` event on GET /admin/console/stream.")
data class WebConsoleResult(
    @field:Schema(description = "The command it answers; null for output nobody asked for here.")
    val correlationId: String?,
    @field:Schema(allowableValues = ["success", "error", "unhandled"]) val status: String,
    @field:Schema(description = "Plain text, which may span lines; absent when unhandled.")
    val text: String? = null,
)

/** The data of the `ready` event, sent once a stream is registered. */
data class WebConsoleReady(val username: String)

/**
 * A web console's address: `webconsole://web/users/<user id>`, one per admin whatever their
 * windows, tabs or credentials, and keyed by the immutable id so a rename can't move it.
 */
object WebConsoleAddress {
    const val SERVICE = "web"
    private const val PREFIX = "users/"

    fun replyTo(owner: UUID) = PREFIX + owner

    /** The admin [replyTo] belongs to, or null if it isn't a console address. */
    fun owner(replyTo: String): UUID? =
        replyTo
            .takeIf { it.startsWith(PREFIX) }
            ?.removePrefix(PREFIX)
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
