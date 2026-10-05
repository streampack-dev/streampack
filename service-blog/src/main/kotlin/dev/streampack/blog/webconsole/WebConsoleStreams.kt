/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import dev.streampack.core.json.JacksonMappers
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * The open web console streams (#115), by the admin they belong to.
 *
 * Each stream has a bounded queue and its own writer: [publish] only enqueues, so egress (which is
 * synchronous, on whatever thread produced the result) never waits on a browser. A stream whose
 * queue overflows, by count or by bytes, is too slow to keep up and is closed rather than silently
 * dropping output; so is one whose write fails. Every [WebConsoleProperties.heartbeat] each stream
 * gets a heartbeat comment, after its credential's expiry and its owner's authority are checked
 * again, so a revoked or expired stream closes even when idle.
 *
 * In memory: this assumes one backend instance. Output with no stream open is dropped, and a
 * reconnected stream starts afresh, with no replay.
 */
@Component
class WebConsoleStreams(
    private val properties: WebConsoleProperties,
    private val access: WebConsoleAccess,
    private val clock: Clock = Clock.systemUTC(),
) : DisposableBean {
    private val logger = LoggerFactory.getLogger(WebConsoleStreams::class.java)
    private val mapper = JacksonMappers.standard()
    private val byOwner = ConcurrentHashMap<UUID, CopyOnWriteArrayList<Stream>>()

    /** Makes a stream's emitter, with its timeout in milliseconds; tests stand in a slow one. */
    internal var newEmitter: (Long) -> SseEmitter = { SseEmitter(it) }
    private val heartbeats: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory())

    init {
        val every = properties.heartbeat.toMillis()
        heartbeats.scheduleAtFixedRate({ heartbeat() }, every, every, TimeUnit.MILLISECONDS)
    }

    /** One event to write: its name (null for a heartbeat comment), its data, and its size. */
    private class Frame(val name: String?, val data: Any?, val bytes: Int)

    /** One browser connection: its emitter, its queue, and the thread that writes it. */
    private inner class Stream(val owner: UUID, val expiresAt: Instant, val emitter: SseEmitter) {
        val queue = LinkedBlockingQueue<Frame>()
        val queuedBytes = AtomicInteger()
        val closed = AtomicBoolean()

        val writer: Thread =
            Thread.ofVirtual().name("webconsole-$owner").unstarted {
                try {
                    while (!closed.get()) {
                        val frame = queue.take()
                        queuedBytes.addAndGet(-frame.bytes)
                        write(frame)
                    }
                } catch (_: InterruptedException) {
                    // closing
                } catch (e: IOException) {
                    logger.debug("Web console stream for {} failed to write: {}", owner, e.message)
                    close(this, completeEmitter = true)
                } catch (e: Exception) {
                    logger.debug("Web console stream for {} failed: {}", owner, e.message)
                    close(this, completeEmitter = true)
                }
            }

        private fun write(frame: Frame) {
            val event =
                if (frame.name == null) SseEmitter.event().comment("heartbeat")
                else
                    SseEmitter.event()
                        .name(frame.name)
                        .data(frame.data!!, MediaType.APPLICATION_JSON)
            emitter.send(event)
        }

        /** Queues [frame], or returns false if that would overrun this stream's bounds. */
        fun offer(frame: Frame): Boolean {
            if (closed.get()) return false
            if (queue.size >= properties.queueEvents) return false
            if (queuedBytes.get() + frame.bytes > properties.queueBytes) return false
            queuedBytes.addAndGet(frame.bytes)
            queue.add(frame)
            return true
        }
    }

    /**
     * Registers a stream for [owner] whose credential expires at [expiresAt], with the `ready`
     * event queued; or returns null if they have as many open as they may.
     */
    fun open(owner: UUID, username: String, expiresAt: Instant): SseEmitter? {
        val list = byOwner.computeIfAbsent(owner) { CopyOnWriteArrayList() }
        val lifetime =
            minOf(Duration.between(clock.instant(), expiresAt), properties.maxStreamAge)
                .coerceAtLeast(Duration.ofSeconds(1))
        val emitter = newEmitter(lifetime.toMillis())
        val stream = Stream(owner, expiresAt, emitter)
        synchronized(list) {
            if (list.size >= properties.maxStreamsPerUser) return null
            list.add(stream)
        }
        emitter.onCompletion { close(stream, completeEmitter = false) }
        emitter.onTimeout { close(stream, completeEmitter = true) }
        emitter.onError { close(stream, completeEmitter = false) }
        stream.writer.start()
        enqueue(stream, "ready", WebConsoleReady(username))
        return emitter
    }

    /** How many streams [owner] has open. */
    fun count(owner: UUID): Int = byOwner[owner]?.size ?: 0

    /**
     * Sends [result] to every open stream of [owner]. Output too large for one event is reported as
     * too large rather than truncated. A stream past its credential's expiry, or too far behind to
     * take it, is closed instead.
     */
    fun publish(owner: UUID, result: WebConsoleResult) {
        val streams = byOwner[owner] ?: return
        if (streams.isEmpty()) return
        val sized =
            if (size(result) <= properties.maxEventBytes) result
            else
                WebConsoleResult(
                    result.correlationId,
                    "error",
                    "That output is too large to show here (${result.text?.length ?: 0} characters).",
                )
        val now = clock.instant()
        for (stream in streams) {
            if (!now.isBefore(stream.expiresAt)) close(stream, completeEmitter = true)
            else enqueue(stream, "result", sized)
        }
    }

    private fun enqueue(stream: Stream, name: String, data: Any) {
        if (!stream.offer(Frame(name, data, size(data)))) {
            logger.info("Web console stream for {} fell behind; closing it", stream.owner)
            close(stream, completeEmitter = true)
        }
    }

    private fun size(data: Any): Int = mapper.writeValueAsBytes(data).size

    /** Rechecks every stream's credential and owner, and sends the rest a heartbeat. */
    fun heartbeat() {
        val now = clock.instant()
        for ((owner, streams) in byOwner) {
            if (streams.isEmpty()) continue
            val allowed = runCatching { access.currentAdmin(owner) != null }.getOrDefault(false)
            for (stream in streams) {
                if (!allowed || !now.isBefore(stream.expiresAt)) {
                    close(stream, completeEmitter = true)
                } else if (!stream.offer(Frame(null, null, 0))) {
                    close(stream, completeEmitter = true)
                }
            }
        }
        byOwner.entries.removeIf { it.value.isEmpty() }
    }

    private fun close(stream: Stream, completeEmitter: Boolean) {
        if (!stream.closed.compareAndSet(false, true)) return
        byOwner[stream.owner]?.remove(stream)
        stream.writer.interrupt()
        stream.queue.clear()
        if (completeEmitter) runCatching { stream.emitter.complete() }
    }

    /** Closes every stream: on shutdown, and for tests. */
    fun closeAll() {
        byOwner.values.flatMap { it.toList() }.forEach { close(it, completeEmitter = true) }
        byOwner.clear()
    }

    override fun destroy() {
        heartbeats.shutdownNow()
        closeAll()
    }
}
