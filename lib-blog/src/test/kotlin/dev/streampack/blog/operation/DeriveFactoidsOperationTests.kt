/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeriveFactoidsRequest
import dev.streampack.blog.model.DeriveFactoidsResponse
import dev.streampack.blog.model.FactoidMention
import dev.streampack.blog.model.MissingFactoid
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.TypedOperation
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidCatalogEntry
import dev.streampack.factoid.model.FindFactoidCatalogRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

/** The factoids a draft mentions, and its links to factoids that don't exist (#130). */
@SpringBootTest
class DeriveFactoidsOperationTests {
    /** Stands in for operation-factoid's catalog. */
    @TestConfiguration
    class Catalog {
        @Bean
        fun testCatalog() =
            object : TypedOperation<FindFactoidCatalogRequest>(FindFactoidCatalogRequest::class) {
                override val addressed = false

                override fun handle(
                    payload: FindFactoidCatalogRequest,
                    message: Message<*>,
                ): OperationOutcome =
                    OperationResult.Success(
                        FactoidCatalog(
                            listOf(
                                FactoidCatalogEntry("eclipse", "one of the \"big two\" java IDEs."),
                                FactoidCatalogEntry("gradle", "a build tool."),
                                FactoidCatalogEntry(
                                    "spring boot",
                                    "an opinionated layer on Spring.",
                                ),
                                FactoidCatalogEntry("spring", "a Java application framework."),
                                FactoidCatalogEntry("osgi", "a module system for Java."),
                                FactoidCatalogEntry("node", null),
                            )
                        )
                    )
            }
    }

    @Autowired lateinit var eventGateway: EventGateway

    private fun derive(markdown: String, role: Role? = Role.USER): OperationResult {
        val user = role?.let {
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "author",
                displayName = "Author",
                role = it,
            )
        }
        return eventGateway.process(
            MessageBuilder.withPayload(DeriveFactoidsRequest(markdown) as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog",
                        replyTo = "x",
                        user = user,
                    ),
                )
                .build()
        )
    }

    private fun response(markdown: String) =
        assertInstanceOf(OperationResult.Success::class.java, derive(markdown)).payload
            as DeriveFactoidsResponse

    @Test
    fun `the factoids mentioned, in order, with how often and their definitions`() {
        val found =
            response(
                """
                Gradle and Eclipse, then Spring Boot on Spring. Gradle again.
                """
                    .trimIndent()
            )

        assertEquals(
            listOf(
                FactoidMention("gradle", "Gradle", 2, "a build tool.", false),
                FactoidMention("eclipse", "Eclipse", 1, "one of the \"big two\" java IDEs.", false),
                FactoidMention(
                    "spring boot",
                    "Spring Boot",
                    1,
                    "an opinionated layer on Spring.",
                    false,
                ),
                FactoidMention("spring", "Spring", 1, "a Java application framework.", false),
            ),
            found.mentions,
        )
    }

    @Test
    fun `code and links aren't mentions`() {
        val found =
            response(
                """
                Build with `gradle build`, or:

                ```
                ./gradlew eclipse
                ```

                See [the Eclipse site](https://eclipse.org) and Spring.
                """
                    .trimIndent()
            )

        assertEquals(listOf("spring"), found.mentions.map { it.selector })
    }

    @Test
    fun `a factoid linked once is marked linked where it's mentioned again`() {
        val found = response("[[OSGi containers|osgi]] are many; OSGi is old.")

        assertEquals(
            listOf(FactoidMention("osgi", "OSGi", 1, "a module system for Java.", true)),
            found.mentions,
        )
    }

    @Test
    fun `links to factoids that don't exist, in both forms, once each`() {
        val found =
            response(
                "Uses [[aho-corasick]] and [[Aho Corasick matching|aho-corasick]] and [[gradle]] and [[node]]."
            )

        assertEquals(listOf(MissingFactoid("aho-corasick", "aho-corasick")), found.missing)
    }

    @Test
    fun `only for signed-in readers`() {
        assertInstanceOf(OperationResult.Error::class.java, derive("Gradle", role = null))
        assertInstanceOf(OperationResult.Error::class.java, derive("Gradle", role = Role.GUEST))
    }
}
