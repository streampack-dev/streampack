/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidCatalogEntry
import dev.streampack.factoid.model.FindFactoidCatalogRequest
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

/** Every factoid with its text, for matching against writing (#130); read outside a transaction. */
@SpringBootTest
class FindFactoidCatalogOperationTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var attributeRepository: FactoidAttributeRepository

    @BeforeEach
    fun setup() {
        attributeRepository.deleteAll()
        factoidRepository.deleteAll()
    }

    @Test
    fun `every factoid, with its text when it has one`() {
        val osgi = factoidRepository.save(Factoid(selector = "osgi", updatedBy = "test"))
        attributeRepository.save(
            FactoidAttribute(
                factoid = osgi,
                attributeType = FactoidAttributeType.TEXT,
                attributeValue = "a module system for Java.",
                updatedBy = "test",
            )
        )
        attributeRepository.save(
            FactoidAttribute(
                factoid = osgi,
                attributeType = FactoidAttributeType.URLS,
                attributeValue = "https://osgi.org",
            )
        )
        factoidRepository.save(Factoid(selector = "node", updatedBy = "test"))

        val result =
            eventGateway.process(
                MessageBuilder.withPayload(FindFactoidCatalogRequest as Any).build()
            )

        val catalog =
            assertInstanceOf(OperationResult.Success::class.java, result).payload as FactoidCatalog
        assertEquals(
            setOf(
                FactoidCatalogEntry("osgi", "a module system for Java."),
                FactoidCatalogEntry("node", null),
            ),
            catalog.entries.toSet(),
        )
    }
}
