/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidTagging
import dev.streampack.factoid.model.FindFactoidTaggingRequest
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

/** Every tagged factoid's tags, read as the taxonomy counts them, for the Atlas (ui-pudl#184). */
@SpringBootTest
class FindFactoidTaggingOperationTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var attributeRepository: FactoidAttributeRepository

    @BeforeEach
    fun setup() {
        attributeRepository.deleteAll()
        factoidRepository.deleteAll()
    }

    private fun tag(selector: String, tags: String?) {
        val factoid = factoidRepository.save(Factoid(selector = selector, updatedBy = "test"))
        attributeRepository.save(
            FactoidAttribute(
                factoid = factoid,
                attributeType = FactoidAttributeType.TAGS,
                attributeValue = tags,
            )
        )
    }

    @Test
    fun `each tagged factoid's tags, trimmed and lowercased, without blanks or hidden tags`() {
        tag("osgi", " Java, modules ,, _page,java")
        tag("node", "_hidden")
        factoidRepository.save(Factoid(selector = "plain", updatedBy = "test"))

        val result =
            eventGateway.process(
                MessageBuilder.withPayload(FindFactoidTaggingRequest as Any).build()
            )

        val tagging =
            assertInstanceOf(OperationResult.Success::class.java, result).payload as FactoidTagging
        assertEquals(listOf("osgi"), tagging.entries.map { it.selector })
        assertEquals(listOf("java", "modules"), tagging.entries.single().tags)
    }
}
