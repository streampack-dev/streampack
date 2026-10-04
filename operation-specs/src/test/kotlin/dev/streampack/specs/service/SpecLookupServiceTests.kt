/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.specs.model.SpecType
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class SpecLookupServiceTests {

    @Autowired lateinit var lookupService: SpecLookupService

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `extracts RFC title from HTML page`() {
        httpServer.createContext("/rfc") { exchange ->
            val html = rfcHtml(2812, "Internet Relay Chat: Client Protocol")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val title = lookupService.lookupUrl("$baseUrl/rfc", SpecType.RFC)
        assertNotNull(title)
        assertEquals("Internet Relay Chat: Client Protocol", title)
    }

    @Test
    fun `extracts JEP title from HTML page`() {
        httpServer.createContext("/jep") { exchange ->
            val html = jepHtml(3, "JDK Release Process")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val title = lookupService.lookupUrl("$baseUrl/jep", SpecType.JEP)
        assertNotNull(title)
        assertEquals("JDK Release Process", title)
    }

    @Test
    fun `extracts JSR title from HTML page`() {
        httpServer.createContext("/jsr") { exchange ->
            val html = jsrHtml(3, "Java Management Extensions (JMX) Specification")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val title = lookupService.lookupUrl("$baseUrl/jsr", SpecType.JSR)
        assertNotNull(title)
        assertEquals("Java Management Extensions (JMX) Specification", title)
    }

    @Test
    fun `extracts PEP title from HTML page`() {
        httpServer.createContext("/pep") { exchange ->
            val html = pepHtml(3, "This is a Sample PEP")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val title = lookupService.lookupUrl("$baseUrl/pep", SpecType.PEP)
        assertNotNull(title)
        assertEquals("This is a Sample PEP", title)
    }

    private fun serve(path: String, html: String) {
        httpServer.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }
    }

    @Test
    fun `a JSR that doesn't exist is no title, though jcp org answers 200`() {
        serve(
            "/nojsr",
            "<html><head></head><body><h1>The specified JSR was not found.</h1></body></html>",
        )

        assertNull(lookupService.lookupUrl("$baseUrl/nojsr", SpecType.JSR))
    }

    @Test
    fun `an older JSR's trademark mark is left out of its title`() {
        serve(
            "/jsr914",
            """<html><body><h1>JSR 914: Java<sup><font size="-2">TM</font></sup> Message Service (JMS) API</h1></body></html>""",
        )

        assertEquals(
            "Java Message Service (JMS) API",
            lookupService.lookupUrl("$baseUrl/jsr914", SpecType.JSR),
        )
    }

    @Test
    fun `a title keeps every TM that isn't a trademark mark`() {
        serve("/rfc7992", rfcHtml(7992, "HTML Format for RFCs"))

        assertEquals(
            "HTML Format for RFCs",
            lookupService.lookupUrl("$baseUrl/rfc7992", SpecType.RFC),
        )
    }

    @Test
    fun `returns null for 404 response`() {
        httpServer.createContext("/missing") { exchange -> exchange.sendResponseHeaders(404, -1) }

        val title = lookupService.lookupUrl("$baseUrl/missing", SpecType.RFC)
        assertNull(title)
    }

    @Test
    fun `returns null for unreachable server`() {
        val title = lookupService.lookupUrl("http://localhost:1/unreachable", SpecType.RFC)
        assertNull(title)
    }

    @Test
    fun `returns null for page with no matching element`() {
        httpServer.createContext("/empty") { exchange ->
            val html = "<html><head></head><body>No title here</body></html>"
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val title = lookupService.lookupUrl("$baseUrl/empty", SpecType.JSR)
        assertNull(title)
    }

    companion object {
        /** The RFC Editor's info page for an RFC, which has a title for every RFC. */
        fun rfcHtml(number: Int, title: String): String =
            """
            <html>
            <head><title>RFC $number: $title | RFC Editor</title></head>
            <body><h1>$title</h1></body>
            </html>
            """
                .trimIndent()

        fun jepHtml(number: Int, title: String): String =
            """
            <html>
            <head><title>JEP $number: $title</title></head>
            <body><h1>$title</h1></body>
            </html>
            """
                .trimIndent()

        /** A JSR's page on jcp.org: no <title> (script sets it), the JSR in its <h1>. */
        fun jsrHtml(number: Int, title: String): String =
            """
            <html>
            <head><script>document.title = 'JSR $number Detail: $title';</script></head>
            <body><h1>JSR $number: $title</h1><h2>Description</h2></body>
            </html>
            """
                .trimIndent()

        fun pepHtml(number: Int, title: String): String =
            """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="color-scheme" content="light dark">
    <title>PEP $number – $title | peps.python.org</title>
    <link rel="shortcut icon" href="../_static/py.png">
    <link rel="canonical" href="https://peps.python.org/pep-0257/">
    <link rel="stylesheet" href="../_static/style.css" type="text/css">
    <link rel="stylesheet" href="../_static/mq.css" type="text/css">
    <link rel="stylesheet" href="../_static/pygments.css" type="text/css" media="(prefers-color-scheme: light)" id="pyg-light">
    <link rel="stylesheet" href="../_static/pygments_dark.css" type="text/css" media="(prefers-color-scheme: dark)" id="pyg-dark">
    <link rel="alternate" type="application/rss+xml" title="Latest PEPs" href="https://peps.python.org/peps.rss">
    <meta property="og:title" content='PEP 257 – Docstring Conventions | peps.python.org'>
    <meta property="og:description" content="This PEP documents the semantics and conventions associated with Python docstrings.">
    <meta property="og:type" content="website">
    <meta property="og:url" content="https://peps.python.org/pep-0257/">
    <meta property="og:site_name" content="Python Enhancement Proposals (PEPs)">
    <meta property="og:image" content="https://peps.python.org/_static/og-image.png">
    <meta property="og:image:alt" content="Python PEPs">
    <meta property="og:image:width" content="200">
    <meta property="og:image:height" content="200">
    <meta name="description" content="This PEP documents the semantics and conventions associated with Python docstrings.">
    <meta name="theme-color" content="#3776ab">
</head>
<body>     
             <span data-pagefind-meta="title:PEP 257 – Docstring Conventions" data-pagefind-weight="10" class="visually-hidden">PEP 257 – Docstring Conventions</span>
            <section id="pep-content">
<h1 class="page-title">PEP $number – $title</h1>
</body>
</html>"""
                .trimIndent()
    }
}
