/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.entity.User
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** An event read off a console stream: its name (null for a comment) and data. */
data class StreamEvent(val name: String?, val data: String) {
    private val json by lazy { JacksonMappers.standard().readTree(data) }

    fun field(name: String): String? = json.get(name)?.takeIf { !it.isNull }?.asString()

    fun has(name: String): Boolean = json.has(name)
}

/** Accounts, tokens, and reading a console stream through MockMvc, for the web console's tests. */
class WebConsoleTestSupport(
    private val mockMvc: MockMvc,
    private val userRepository: UserRepository,
    private val jwtService: JwtService,
) {
    fun account(name: String, role: Role): User =
        userRepository.save(
            User(
                username = name,
                email = "$name@test.com",
                displayName = name.replaceFirstChar { it.uppercase() },
                emailVerified = true,
                role = role,
            )
        )

    fun token(user: User): String = jwtService.generateToken(user.toUserPrincipal())

    /** Submits [line] as [token]'s bearer, with the console header. */
    fun submit(token: String, line: String) =
        mockMvc.post("/admin/console") {
            header("Authorization", "Bearer $token")
            header(WebConsoleController.HEADER, "1")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = JacksonMappers.standard().writeValueAsString(mapOf("line" to line))
        }

    /** The correlation id of an accepted [line]. */
    fun run(token: String, line: String): String {
        val result = submit(token, line).andReturn()
        check(result.response.status == 202) {
            "not accepted: ${result.response.status} ${result.response.contentAsString}"
        }
        return JacksonMappers.standard()
            .readTree(result.response.contentAsString)
            .get("correlationId")
            .asString()
    }

    /** Opens a stream as [token]'s bearer, once its `ready` event is in. */
    fun open(token: String): MvcResult {
        val result =
            mockMvc
                .get("/admin/console/stream") { header("Authorization", "Bearer $token") }
                .andReturn()
        check(result.request.isAsyncStarted) {
            "no stream: ${result.response.status} ${result.response.contentAsString}"
        }
        await("ready") { events(result).any { it.name == "ready" } }
        return result
    }

    /** The events written to [stream] so far. */
    fun events(stream: MvcResult): List<StreamEvent> =
        stream.response.contentAsString
            .split("\n\n")
            .filter { it.isNotBlank() }
            .map { block ->
                val lines = block.lines()
                val name = lines.firstOrNull { it.startsWith("event:") }?.removePrefix("event:")
                val data =
                    lines
                        .filter { it.startsWith("data:") }
                        .joinToString("\n") { it.removePrefix("data:") }
                StreamEvent(if (lines.all { it.startsWith(":") }) null else name, data)
            }

    /** The `result` events on [stream] for [correlationId]. */
    fun results(stream: MvcResult, correlationId: String?): List<StreamEvent> =
        events(stream).filter { it.name == "result" && it.field("correlationId") == correlationId }

    /** Whether the server has ended [stream] (its emitter completed). */
    fun ended(stream: MvcResult): Boolean = runCatching { stream.getAsyncResult(1) }.isSuccess

    companion object {
        /** Waits up to [seconds] for [condition], failing with [what] if it never holds. */
        fun await(what: String, seconds: Long = 10, condition: () -> Boolean) {
            val deadline = System.nanoTime() + seconds * 1_000_000_000
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(20)
            }
            error("Timed out waiting for $what")
        }

        /** Holds for [millis] that [condition] stays false, as evidence something didn't happen. */
        fun never(millis: Long = 500, condition: () -> Boolean) {
            val deadline = System.nanoTime() + millis * 1_000_000
            while (System.nanoTime() < deadline) {
                check(!condition()) { "It happened" }
                Thread.sleep(20)
            }
        }
    }
}
