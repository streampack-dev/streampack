/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.blog.webconsole.WebConsoleTestSupport.Companion.await
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.test.ResetDatabaseBeforeEach
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * A console stream's life (#115): heartbeats and rechecks, expiry, slow and failed clients,
 * oversized output, shutdown, and reconnecting.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@TestPropertySource(
    properties =
        [
            "streampack.webconsole.heartbeat=100ms",
            "streampack.webconsole.queue-events=5",
            "streampack.webconsole.max-event-bytes=2048",
        ]
)
class WebConsoleStreamTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var streams: WebConsoleStreams

    private lateinit var console: WebConsoleTestSupport
    private val made = CopyOnWriteArrayList<Recording>()

    /** An emitter that records what's written to it, and may be slow or broken. */
    class Recording(timeout: Long) : SseEmitter(timeout) {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var completed = false
        @Volatile var gate: CountDownLatch? = null
        @Volatile var broken = false

        override fun send(builder: SseEventBuilder) {
            gate?.await(10, TimeUnit.SECONDS)
            if (broken) throw IOException("the client went away")
            sent.add(builder.build().joinToString("") { it.data.toString() })
        }

        override fun complete() {
            completed = true
        }

        fun results() = sent.filter { it.contains("event:result") }
    }

    @BeforeEach
    fun setUp() {
        console = WebConsoleTestSupport(mockMvc, userRepository, jwtService)
        made.clear()
        streams.newEmitter = { timeout -> Recording(timeout).also { made.add(it) } }
    }

    @AfterEach
    fun tearDown() {
        streams.closeAll()
        streams.newEmitter = { SseEmitter(it) }
    }

    private fun admin() =
        console.account("streamadmin-${UUID.randomUUID().toString().take(6)}", Role.ADMIN)

    private fun open(owner: UUID, expiresAt: Instant = Instant.now().plusSeconds(3600)): Recording {
        assertNotNull(streams.open(owner, "someone", expiresAt))
        return made.last()
    }

    private fun result(text: String, id: String? = "c") = WebConsoleResult(id, "success", text)

    @Test
    fun `an open stream gets heartbeats`() {
        val stream = open(admin().id)

        await("heartbeats") { stream.sent.count { it.contains(":heartbeat") } >= 2 }
    }

    @Test
    fun `a stream closes on a heartbeat once its owner isn't an admin, even with nothing to send`() {
        val owner = admin()
        val stream = open(owner.id)
        userRepository.save(owner.copy(role = Role.USER))

        await("the close") { stream.completed }
        assertEquals(0, streams.count(owner.id))
    }

    @Test
    fun `a stream closes when its credential expires`() {
        val owner = admin().id
        val stream = open(owner, Instant.now().plusMillis(250))

        await("the close") { stream.completed }
        streams.publish(owner, result("too late"))
        assertTrue(stream.results().isEmpty())
    }

    @Test
    fun `a slow stream is closed, not waited on, and the others carry on`() {
        val owner = admin().id
        val slow = open(owner).apply { gate = CountDownLatch(1) }
        val quick = open(owner)

        val started = System.nanoTime()
        repeat(20) { streams.publish(owner, result("line $it")) }
        val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue(took < 2000, "publishing waited on the slow stream: ${took}ms")
        await("the slow stream closed") { slow.completed }
        await("the quick stream's output") { quick.results().size >= 5 }
        slow.gate?.countDown()
    }

    @Test
    fun `a stream whose writes fail is closed and forgotten`() {
        val owner = admin().id
        val broken = open(owner).apply { this.broken = true }

        streams.publish(owner, result("anyone there?"))

        await("the close") { broken.completed && streams.count(owner) == 0 }
    }

    @Test
    fun `output too large for one event is reported as too large, never cut short`() {
        val owner = admin().id
        val stream = open(owner)

        streams.publish(owner, result("x".repeat(5000)))

        await("the report") { stream.results().isNotEmpty() }
        val sent = stream.results().single()
        assertTrue(sent.contains("too large to show"), sent)
        assertTrue(sent.contains("5000 characters"), sent)
        assertTrue(!sent.contains("xxxxxxxxxx"), sent)
    }

    @Test
    fun `shutting down closes every stream`() {
        val first = open(admin().id)
        val second = open(admin().id)

        streams.closeAll()

        assertTrue(first.completed && second.completed)
    }

    @Test
    fun `a reconnected stream starts afresh, with nothing replayed`() {
        val owner = admin().id
        val before = open(owner)
        streams.closeAll()
        streams.publish(owner, result("while away"))

        val after = open(owner)
        streams.publish(owner, result("back"))

        await("the new output") { after.results().isNotEmpty() }
        assertEquals(1, after.results().size)
        assertTrue(after.results().single().contains("back"))
        assertTrue(before.results().isEmpty())
    }
}
