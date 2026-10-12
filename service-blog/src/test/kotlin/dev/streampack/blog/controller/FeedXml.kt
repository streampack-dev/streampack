/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import java.io.ByteArrayInputStream
import javax.xml.namespace.NamespaceContext
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Document
import org.w3c.dom.NodeList

/** Reads feeds in tests by XPath, with `a:` for Atom and `itunes:` for the podcast tags. */
class FeedXml(xml: String) {
    private val document: Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray()))

    private val xpath =
        XPathFactory.newInstance().newXPath().apply {
            namespaceContext =
                object : NamespaceContext {
                    override fun getNamespaceURI(prefix: String): String =
                        when (prefix) {
                            "a" -> "http://www.w3.org/2005/Atom"
                            "itunes" -> "http://www.itunes.com/dtds/podcast-1.0.dtd"
                            else -> ""
                        }

                    override fun getPrefix(namespaceURI: String): String? = null

                    override fun getPrefixes(namespaceURI: String) = emptyList<String>().iterator()
                }
        }

    /** The text of what [expression] selects; empty when it selects nothing. */
    fun text(expression: String): String = xpath.evaluate(expression, document)

    /** How many nodes [expression] selects. */
    fun count(expression: String): Int =
        (xpath.evaluate(expression, document, XPathConstants.NODESET) as NodeList).length
}
