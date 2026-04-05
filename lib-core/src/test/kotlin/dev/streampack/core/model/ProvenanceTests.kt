/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProvenanceTests {

    @Test
    fun `encode mailto with no serviceId`() {
        val provenance = Provenance(protocol = Protocol.MAILTO, replyTo = "user@example.com")
        assertEquals("mailto:///user@example.com", provenance.encode())
    }

    @Test
    fun `encode IRC with serviceId`() {
        val provenance =
            Provenance(protocol = Protocol.IRC, serviceId = "ircservice", replyTo = "oftc/#java")
        assertEquals("irc://ircservice/oftc/%23java", provenance.encode())
    }

    @Test
    fun `encode Discord with serviceId`() {
        val provenance =
            Provenance(
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                replyTo = "#java-help",
            )
        assertEquals("discord://jvm-community/%23java-help", provenance.encode())
    }

    @Test
    fun `decode mailto URI`() {
        val provenance = Provenance.decode("mailto:///user@example.com")
        assertEquals(Protocol.MAILTO, provenance.protocol)
        assertNull(provenance.serviceId)
        assertEquals("user@example.com", provenance.replyTo)
    }

    @Test
    fun `decode IRC URI`() {
        val provenance = Provenance.decode("irc://ircservice/oftc/%23java")
        assertEquals(Protocol.IRC, provenance.protocol)
        assertEquals("ircservice", provenance.serviceId)
        assertEquals("oftc/#java", provenance.replyTo)
    }

    @Test
    fun `decode Discord URI`() {
        val provenance = Provenance.decode("discord://jvm-community/%23java-help")
        assertEquals(Protocol.DISCORD, provenance.protocol)
        assertEquals("jvm-community", provenance.serviceId)
        assertEquals("#java-help", provenance.replyTo)
    }

    @Test
    fun `round trip encode then decode preserves components`() {
        val original =
            Provenance(protocol = Protocol.IRC, serviceId = "ircservice", replyTo = "oftc/#java")
        val decoded = Provenance.decode(original.encode())
        assertEquals(original.protocol, decoded.protocol)
        assertEquals(original.serviceId, decoded.serviceId)
        assertEquals(original.replyTo, decoded.replyTo)
    }

    @Test
    fun `round trip decode then encode preserves URI`() {
        val uri = "discord://jvm-community/%23java-help"
        val provenance = Provenance.decode(uri)
        assertEquals(uri, provenance.encode())
    }

    @Test
    fun `round trip mailto decode then encode preserves URI`() {
        val uri = "mailto:///user@example.com"
        val provenance = Provenance.decode(uri)
        assertEquals(uri, provenance.encode())
    }

    @Test
    fun `encode HTTP with serviceId`() {
        val provenance =
            Provenance(protocol = Protocol.HTTP, serviceId = "http-service", replyTo = "posts")
        assertEquals("http://http-service/posts", provenance.encode())
    }

    @Test
    fun `decode HTTP URI`() {
        val provenance = Provenance.decode("http://http-service/posts")
        assertEquals(Protocol.HTTP, provenance.protocol)
        assertEquals("http-service", provenance.serviceId)
        assertEquals("posts", provenance.replyTo)
    }

    @Test
    fun `provenance carries UserPrincipal`() {
        val principal =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "testuser",
                displayName = "Test User",
                role = Role.USER,
            )
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                replyTo = "posts",
                user = principal,
            )
        assertEquals(principal, provenance.user)
        assertEquals("testuser", provenance.user?.username)
        assertEquals(Role.USER, provenance.user?.role)
    }
}
