/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.taxonomy.model.FindFactoidTagTaxonomyRequest
import dev.streampack.taxonomy.model.TaxonomyTermCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FindFactoidTagTaxonomyOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private fun commandMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
            )
            .setHeader("nick", "tester")
            .build()

    private fun requestMessage(payload: Any) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
            )
            .build()

    @Test
    fun `factoid tag taxonomy returns grouped counts and excludes underscore terms`() {
        eventGateway.process(commandMessage("alpha=one"))
        eventGateway.process(commandMessage("alpha.tags=xyz,tools,_page"))
        eventGateway.process(commandMessage("beta=two"))
        eventGateway.process(commandMessage("beta.tags=xyz"))

        val result = eventGateway.process(requestMessage(FindFactoidTagTaxonomyRequest))
        assertInstanceOf(OperationResult.Success::class.java, result)

        val tags = (result as OperationResult.Success).payload as List<*>
        val counts = tags.filterIsInstance<TaxonomyTermCount>().associate { it.name to it.count }

        assertEquals(2L, counts["xyz"])
        assertEquals(1L, counts["tools"])
        assertTrue("_page" !in counts.keys)
    }
}
