/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.taxonomy.TagCuration
import dev.streampack.taxonomy.TagVocabulary
import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * Factoid tags and the vocabulary (#140): written canonical through chat with the usual single
 * reply, found by alias, and re-pointed when an admin aliases or splits a tag.
 */
@SpringBootTest
@Transactional
class FactoidTagVocabularyTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var curation: TagCuration
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var reviews: TagReviewRepository

    private fun say(text: String): Any? =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.CONSOLE,
                        serviceId = "",
                        replyTo = "local",
                        user = UserPrincipal(UUID.randomUUID(), "testuser", "Test", Role.USER),
                    ),
                )
                .setHeader("nick", "testuser")
                .build()
        )

    private fun tagsOf(selector: String): String? =
        factoidService
            .findBySelector(selector)
            .single { it.attributeType.name == "TAGS" }
            .attributeValue

    @BeforeEach
    fun setUp() {
        listOf("kubernetes", "compilers", "java", "kotlin").forEach { vocabulary.ensureTag(it) }
        curation.alias("k8s", "kubernetes", "admin")
        curation.stop("self-hosted", "admin")
    }

    @Test
    fun `chat-set tags are stored canonical, with the usual single reply`() {
        val plain = say("tailscale.tags=vpn")
        val vocabulary = say("headscale.tags=K8s, self-hosted, #Kotlin, compiler")

        assertInstanceOf(OperationResult.Success::class.java, vocabulary)
        assertEquals(
            (plain as OperationResult.Success).payload.toString().replace("tailscale", "headscale"),
            (vocabulary as OperationResult.Success).payload.toString(),
        )
        assertEquals("kubernetes,kotlin,compiler", tagsOf("headscale"))
        // The new tag is created and queued for an admin, never asked about.
        assertNotNull(tags.findByName("compiler"))
        val entry = reviews.findByTag("compiler")!!
        assertEquals(TagHintKind.PLURAL, entry.hintKind)
        assertEquals("factoid", entry.source)
    }

    @Test
    fun `an alias finds its tag's factoids`() {
        say("headscale.tags=kubernetes")
        val result = say("tag K8s") as OperationResult.Success
        assertTrue(
            result.payload.toString().contains("{{ref:headscale}}"),
            result.payload.toString(),
        )
    }

    @Test
    fun `aliasing a tag rewrites the factoid lists that carry it, keeping the rest`() {
        say("gcc.tags=compiler, java")
        say("clang.tags=compilers,compiler")
        val result = curation.alias("compiler", "compilers", "admin")
        assertEquals(2, result.factoids)
        assertEquals("compilers,java", tagsOf("gcc"))
        assertEquals("compilers", tagsOf("clang"))
        assertEquals(listOf("clang", "gcc"), factoidService.searchByTag("compiler"))
    }

    @Test
    fun `splitting a tag rewrites the factoid lists into its parts`() {
        say("jk.tags=java kotlin, compilers")
        curation.split("java kotlin", null, "admin")
        assertEquals("java,kotlin,compilers", tagsOf("jk"))
    }
}
