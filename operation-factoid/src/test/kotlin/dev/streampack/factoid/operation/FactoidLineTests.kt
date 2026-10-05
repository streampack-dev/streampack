/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidAttributeType.SEEALSO
import dev.streampack.factoid.model.FactoidAttributeType.TAGS
import dev.streampack.factoid.model.FactoidAttributeType.TEXT
import dev.streampack.factoid.model.FactoidAttributeType.URLS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A factoid fitted to one line by leaving parts out, not by cutting it off (#131). */
class FactoidLineTests {
    private fun factoid(vararg parts: Pair<FactoidAttributeType, String>) =
        parts.map { (type, value) ->
            FactoidAttribute(attributeType = type, attributeValue = value)
        }

    /** As in the knowledge base: cut off in IRC today by its tags and see-also. */
    private val kotauth =
        factoid(
            TEXT to
                "a self-hosted OAuth2/OIDC authentication platform built on Kotlin/Ktor, targeting the gap between Keycloak's configuration weight and Auth0's pricing ceiling. Single Docker image, PostgreSQL backend, multi-tenancy, RBAC, MFA, social login, and a framework-free domain layer.",
            URLS to "https://github.com/InumanSoul/kotauth",
            TAGS to "kotlin,auth,oidc,oauth2,self-hosted",
            SEEALSO to "ktor,exposed,oauth2,oidc,authentication,authorization",
        )

    private val oauth2 =
        factoid(
            TEXT to
                "an authorization delegation framework (RFC 6749) allowing a resource owner to grant a third party limited access to their resources without sharing credentials. Not an authentication protocol on its own — that's a common misuse. Issues access tokens; token shape and semantics are left to the implementation.",
            URLS to "https://oauth.net/2/",
            TAGS to "security,auth,rfc,standard",
            SEEALSO to "oidc,authentication,authorization",
        )

    @Test
    fun `a factoid that fits is said whole, as before`() {
        val openapi =
            factoid(
                TEXT to
                    "a vendor-neutral specification for describing HTTP APIs in machine-readable form.",
                URLS to "https://www.openapis.org,https://spec.openapis.org/oas/latest.html",
                TAGS to "api,http",
            )

        val line = openapi.line("openapi", target = 300)

        assertEquals(openapi.summarize("openapi", ""), line.text)
        assertTrue(line.dropped.isEmpty())
    }

    @Test
    fun `tags go first, then see-also, and a URL stays while the line is under the limit`() {
        val line = kotauth.line("kotauth", target = 300)

        assertEquals(listOf(TAGS, SEEALSO), line.dropped)
        assertTrue(line.text.startsWith("kotauth is a self-hosted OAuth2/OIDC"))
        assertTrue(line.text.endsWith("URL: https://github.com/InumanSoul/kotauth"), line.text)
        assertTrue(line.length <= FactoidLine.LIMIT)
    }

    @Test
    fun `the factoids cut off today fit`() {
        for ((selector, attributes) in listOf("kotauth" to kotauth, "oauth2" to oauth2)) {
            assertTrue(attributes.summarize(selector, "").length > FactoidLine.LIMIT, selector)
            assertTrue(
                attributes.line(selector, target = 300).length <= FactoidLine.LIMIT,
                selector,
            )
        }
    }

    @Test
    fun `text is never cut, even when it alone is too long, and everything else goes`() {
        val long = "x".repeat(450)
        val line =
            factoid(TEXT to long, URLS to "https://example.com", TAGS to "a")
                .line("big", target = 300)

        assertEquals("big is $long.", line.text)
        assertEquals(listOf(TAGS, URLS), line.dropped)
    }

    @Test
    fun `see-also is kept over tags when only one has to go`() {
        val text = "y".repeat(230)
        val line =
            factoid(TEXT to text, TAGS to "alpha,beta,gamma", SEEALSO to "osgi")
                .line("thing", target = 280)

        assertEquals(listOf(TAGS), line.dropped)
        assertTrue(line.text.endsWith("See also: osgi"), line.text)
    }
}
