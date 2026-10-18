/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import jakarta.annotation.PreDestroy
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.PolyglotException
import org.graalvm.polyglot.Source
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Draws Mermaid diagrams as SVG with [Pele](https://pele.run) (#252), run by GraalJS inside the
 * JVM.
 *
 * On a stock JDK such as Temurin, GraalJS runs interpreted: about 2 ms a diagram once warm, and
 * under a second to load the first time. Compiled mode needs the Graal compiler on the module path
 * and a JDK whose JVMCI matches it build for build, which a post rendered once at save doesn't
 * justify. The engine starts on the first diagram, not with the server.
 *
 * Each diagram gets a fresh context on the shared engine: no I/O, nothing kept from one diagram to
 * the next, and of the host only [MermaidTextMeasurer.width], which measures labels in the bundled
 * diagram fonts. Pele has no time limit of its own, so a render that runs past [timeout] is
 * cancelled. Links and images are turned off: a diagram can't send a reader anywhere or make the
 * browser fetch anything.
 */
@Component
class MermaidRenderer(
    @Value("\${streampack.blog.mermaid.enabled:true}") private val enabled: Boolean = true,
    @Value("\${streampack.blog.mermaid.timeout:5s}")
    private val timeout: Duration = Duration.ofSeconds(5),
    /** Measure labels in the bundled diagram fonts; off, Pele estimates from its own table */
    private val measure: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(MermaidRenderer::class.java)

    /** What became of one diagram */
    sealed interface Outcome {
        /** Drawn: [svg] is Pele's output, before any sanitizing; [type] is the diagram's kind */
        data class Drawn(val svg: String, val type: String) : Outcome

        /** Pele refused it: [message] is its first line, which may quote the diagram's text */
        data class Refused(val message: String) : Outcome

        /** Not attempted: turned off, or Pele couldn't be loaded */
        data object Unavailable : Outcome
    }

    private val engine: Engine? by lazy { if (enabled) startEngine() else null }
    private val source: Source? by lazy { if (enabled) loadPele() else null }
    private val measurer: MermaidTextMeasurer? by lazy {
        if (enabled && measure) MermaidTextMeasurer.load() else null
    }

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mermaid-watchdog").apply { isDaemon = true }
    }

    /**
     * Draws [diagram], its element ids starting with [idPrefix] so diagrams on a page don't clash
     */
    fun render(diagram: String, idPrefix: String): Outcome {
        val engine = engine ?: return Outcome.Unavailable
        val source = source ?: return Outcome.Unavailable
        val started = System.nanoTime()
        val measurer = measurer
        // Nothing of the host but the measurer's exported width()
        val context =
            Context.newBuilder("js")
                .engine(engine)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.EXPLICIT)
                .build()
        val cancel =
            watchdog.schedule({ context.close(true) }, timeout.toMillis(), TimeUnit.MILLISECONDS)
        return try {
            context.eval(source)
            val result =
                context
                    .eval("js", RENDER)
                    .execute(
                        diagram.trimEnd(),
                        idPrefix,
                        SOURCE_LIMIT,
                        OUTPUT_LIMIT,
                        measurer,
                        SANS_STACK,
                        MONO_STACK,
                    )
            Outcome.Drawn(result.getMember("svg").asString(), result.getMember("type").asString())
                .also {
                    logger.debug(
                        "Drew a {} diagram in {} ms",
                        it.type,
                        (System.nanoTime() - started) / 1_000_000,
                    )
                }
        } catch (e: PolyglotException) {
            when {
                e.isCancelled -> Outcome.Refused("It took longer than $timeout to draw.")
                isPeleError(e) -> Outcome.Refused(reason(e.message))
                else -> {
                    logger.warn("Mermaid diagram failed in the renderer", e)
                    Outcome.Refused("The renderer failed.")
                }
            }
        } finally {
            cancel.cancel(false)
            runCatching { context.close(true) }
        }
    }

    private fun startEngine(): Engine? =
        try {
            Engine.newBuilder("js")
                // Interpreted is expected on a stock JDK; see the class comment.
                .option("engine.WarnInterpreterOnly", "false")
                .logHandler(SlfHandler)
                .build()
                .also { logger.info("Mermaid diagrams: GraalJS {}", it.implementationName) }
        } catch (e: Throwable) {
            logger.error("Mermaid diagrams are off: GraalJS didn't start", e)
            null
        }

    private fun loadPele(): Source? =
        try {
            val script =
                MermaidRenderer::class.java.getResource(PELE_RESOURCE)?.readText()
                    ?: error("$PELE_RESOURCE is missing")
            Source.newBuilder("js", script, "pele.global.js").build().also {
                logger.info(
                    "Mermaid diagrams: Pele {}",
                    PELE_BANNER.find(script.take(200))?.groupValues?.get(1) ?: "(unknown version)",
                )
            }
        } catch (e: Exception) {
            logger.error("Mermaid diagrams are off: Pele couldn't be loaded", e)
            null
        }

    /** Pele's own refusal (bad syntax, a limit), as against a fault in Pele or the engine */
    private fun isPeleError(e: PolyglotException): Boolean =
        e.isGuestException &&
            runCatching { e.guestObject?.getMember("name")?.asString() == "PeleError" }
                .getOrDefault(false)

    /**
     * Pele's message, made one line: a syntax error's first line names where, its last says what
     * was expected ("Parse error on line 3: Expecting 'NODE_STRING', got 'EOF'"); the lines between
     * quote the source, which the figure shows already.
     */
    private fun reason(message: String?): String {
        val lines =
            message.orEmpty().lines().map(String::trim).filter(String::isNotEmpty).ifEmpty {
                return "It isn't a diagram Pele can draw."
            }
        val first = lines.first().removePrefix("PeleError: ")
        return if (lines.size < 3) first else first.removeSuffix(":") + ": " + lines.last()
    }

    @PreDestroy
    fun close() {
        watchdog.shutdownNow()
        if (enabled) runCatching { engine?.close() }
    }

    /** GraalJS's own log lines, into ours */
    private object SlfHandler : Handler() {
        private val log = LoggerFactory.getLogger("dev.streampack.blog.service.GraalJS")

        override fun publish(record: LogRecord) {
            log.info("{}", record.message)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        const val PELE_RESOURCE = "/pele/pele.global.js"
        private val PELE_BANNER = Regex("""Pele (\S+)""")

        /** The longest diagram source Pele will take, in characters */
        const val SOURCE_LIMIT = 20_000

        /** The longest SVG Pele will write, in characters */
        const val OUTPUT_LIMIT = 1_000_000

        /** The fonts a measured diagram names, as the measurer measured them */
        private const val SANS_STACK = "\"${MermaidTextMeasurer.SANS_FAMILY}\", sans-serif"
        private const val MONO_STACK = "\"${MermaidTextMeasurer.MONO_FAMILY}\", monospace"

        /**
         * Pele's render, with the options the server keeps for itself: authors set none of them.
         * With a measurer, labels are measured in the diagram fonts, and the SVG names them.
         */
        private const val RENDER =
            """(source, idPrefix, limit, outputLimit, measurer, sans, mono) => {
                const options = { idPrefix, limit, outputLimit, links: false, images: false };
                if (measurer) {
                    options.measurer = {
                        width: (text, size, style) => measurer.width(text, size, style)
                    };
                    options.fontFamily = sans;
                    options.fontFamilyMono = mono;
                }
                const r = pele.render(source, options);
                return { svg: r.svg, type: r.type };
            }"""
    }
}
