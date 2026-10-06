/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.factoid.entity.Factoid
import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** Where the factoid graph could be better (#133); read outside a transaction, as served. */
@SpringBootTest
class FactoidGraphReportServiceTests {
    @Autowired lateinit var service: FactoidGraphReportService
    @Autowired lateinit var factoidRepository: FactoidRepository
    @Autowired lateinit var attributeRepository: FactoidAttributeRepository

    private fun factoid(selector: String, vararg attributes: Pair<FactoidAttributeType, String>) {
        val saved = factoidRepository.save(Factoid(selector = selector, updatedBy = "test"))
        attributes.forEach { (type, value) ->
            attributeRepository.save(
                FactoidAttribute(factoid = saved, attributeType = type, attributeValue = value)
            )
        }
    }

    @BeforeEach
    fun setup() {
        attributeRepository.deleteAll()
        factoidRepository.deleteAll()
        // A hub written top-down: osgi lists its children; karaf doesn't link back, felix does.
        factoid(
            "osgi",
            FactoidAttributeType.TEXT to "<reply>OSGi is a module system for Java.",
            FactoidAttributeType.SEEALSO to "karaf,felix",
        )
        factoid("karaf", FactoidAttributeType.TEXT to "a small OSGi runtime for bundles.")
        factoid(
            "felix",
            FactoidAttributeType.TEXT to "an OSGi framework.",
            FactoidAttributeType.SEEALSO to "osgi",
        )
        factoid("xml", FactoidAttributeType.TEXT to "a markup language.")
        factoid("ant", FactoidAttributeType.TEXT to "a build tool driven by XML files.")
        factoid(
            "comprehension debt",
            FactoidAttributeType.TEXT to "what code costs when nobody understands it.",
            FactoidAttributeType.SEEALSO to "technical debt,xml",
        )
        factoid(
            "kotauth",
            FactoidAttributeType.TEXT to "x".repeat(250),
            FactoidAttributeType.URLS to "https://github.com/InumanSoul/kotauth",
            FactoidAttributeType.TAGS to "kotlin,auth,oidc,oauth2,self-hosted",
            FactoidAttributeType.SEEALSO to "osgi,felix",
        )
        factoid("sprawl", FactoidAttributeType.TEXT to "y".repeat(450))
    }

    @Test
    fun `a child naming a parent that lists it, without linking back, comes first`() {
        val report = service.report()

        assertEquals(
            listOf(FactoidGraphReport.UnlinkedMentions("karaf", listOf("osgi"))),
            report.parentsNotLinked,
        )
    }

    @Test
    fun `other mentions not linked are listed apart`() {
        val report = service.report()

        assertEquals(
            listOf(FactoidGraphReport.UnlinkedMentions("ant", listOf("xml"))),
            report.mentionsNotLinked,
        )
    }

    @Test
    fun `see-also to factoids that don't exist`() {
        assertEquals(
            listOf(
                FactoidGraphReport.SeeAlsoToNothing("comprehension debt", listOf("technical debt"))
            ),
            service.report().seeAlsoToNothing,
        )
    }

    @Test
    fun `lines that leave parts out, and one cut off even so`() {
        val lines = service.report().linesThatDontFit.associateBy { it.selector }

        val kotauth = lines.getValue("kotauth")
        assertEquals(listOf("tags", "seealso"), kotauth.dropped)
        assertTrue(kotauth.length > kotauth.saidLength)
        assertEquals(false, kotauth.cutOff)
        assertEquals(true, lines.getValue("sprawl").cutOff)
        assertEquals(setOf("kotauth", "sprawl"), lines.keys)
    }
}
