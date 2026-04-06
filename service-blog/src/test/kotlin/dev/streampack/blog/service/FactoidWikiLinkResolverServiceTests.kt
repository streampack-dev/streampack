/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class FactoidWikiLinkResolverServiceTests {
    @Autowired lateinit var resolver: FactoidWikiLinkResolverService
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var factoidAttributeRepository: FactoidAttributeRepository

    @BeforeEach
    fun setup() {
        factoidAttributeRepository.deleteAll()
        factoidRepository.deleteAll()
    }

    @Test
    fun `resolve returns url and text for valid factoid`() {
        val factoid = factoidRepository.save(Factoid(selector = "thing", updatedBy = "test"))
        factoidAttributeRepository.save(
            FactoidAttribute(
                factoid = factoid,
                attributeType = FactoidAttributeType.TEXT,
                attributeValue = "thing is a thing",
                updatedBy = "test",
            )
        )
        factoidAttributeRepository.save(
            FactoidAttribute(
                factoid = factoid,
                attributeType = FactoidAttributeType.URLS,
                attributeValue = "https://thing.com",
                updatedBy = "test",
            )
        )

        val resolved = resolver.resolve("thing")
        assertNotNull(resolved)
        assertEquals("https://thing.com", resolved?.href)
        assertEquals("thing is a thing", resolved?.title)
    }

    @Test
    fun `resolve ignores non-http url`() {
        val factoid = factoidRepository.save(Factoid(selector = "thing", updatedBy = "test"))
        factoidAttributeRepository.save(
            FactoidAttribute(
                factoid = factoid,
                attributeType = FactoidAttributeType.URLS,
                attributeValue = "javascript:alert(1)",
                updatedBy = "test",
            )
        )
        val resolved = resolver.resolve("thing")
        assertNull(resolved?.href)
    }
}
