/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.factoid.model.FactoidAttributeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Unit tests for SetFactoidOperation.parseInput delimiter and attribute parsing */
class SetFactoidParseInputTests {

    // -- equals delimiter (existing behavior) --

    @Test
    fun `equals delimiter parses selector and value`() {
        val result = SetFactoidOperation.parseInput("spring=A Java framework")
        assertNotNull(result)
        assertEquals("spring", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("A Java framework", result.value)
    }

    @Test
    fun `equals delimiter with attribute`() {
        val result = SetFactoidOperation.parseInput("spring.url=https://spring.io")
        assertNotNull(result)
        assertEquals("spring", result!!.selector)
        assertEquals(FactoidAttributeType.URLS, result.attribute)
        assertEquals("https://spring.io", result.value)
    }

    // -- is delimiter --

    @Test
    fun `is delimiter parses selector and value`() {
        val result = SetFactoidOperation.parseInput("spring is A Java framework")
        assertNotNull(result)
        assertEquals("spring", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("A Java framework", result.value)
    }

    @Test
    fun `is delimiter with attribute`() {
        val result = SetFactoidOperation.parseInput("spring.url is https://spring.io")
        assertNotNull(result)
        assertEquals("spring", result!!.selector)
        assertEquals(FactoidAttributeType.URLS, result.attribute)
        assertEquals("https://spring.io", result.value)
    }

    // -- delimiter precedence --

    @Test
    fun `is before equals uses is as delimiter`() {
        // "foo is bar=baz" -> selector=foo, value=bar=baz
        val result = SetFactoidOperation.parseInput("foo is bar=baz")
        assertNotNull(result)
        assertEquals("foo", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("bar=baz", result.value)
    }

    @Test
    fun `equals before is uses equals as delimiter`() {
        // "foo=bar is baz" -> selector=foo, value=bar is baz
        val result = SetFactoidOperation.parseInput("foo=bar is baz")
        assertNotNull(result)
        assertEquals("foo", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("bar is baz", result.value)
    }

    // -- attribute-qualified split takes priority --

    @Test
    fun `attribute split takes priority over simple is delimiter`() {
        // "foo is bar.text=baz" -> .text= found, selector=foo is bar, value=baz
        val result = SetFactoidOperation.parseInput("foo is bar.text=baz")
        assertNotNull(result)
        assertEquals("foo is bar", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("baz", result.value)
    }

    @Test
    fun `attribute with is delimiter after selector containing is`() {
        // "foo is bar.text is baz" -> .text is found, selector=foo is bar, value=baz
        val result = SetFactoidOperation.parseInput("foo is bar.text is baz")
        assertNotNull(result)
        assertEquals("foo is bar", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("baz", result.value)
    }

    @Test
    fun `equals in selector via attribute split is an error`() {
        // "foo=bar.text is baz" -> .text is found, selector=foo=bar -> contains =, error
        val result = SetFactoidOperation.parseInput("foo=bar.text is baz")
        assertNull(result)
    }

    // -- edge cases --

    @Test
    fun `blank selector returns null`() {
        assertNull(SetFactoidOperation.parseInput("=value"))
        assertNull(SetFactoidOperation.parseInput(" is value"))
    }

    @Test
    fun `blank value returns null`() {
        assertNull(SetFactoidOperation.parseInput("key="))
        assertNull(SetFactoidOperation.parseInput("key is "))
    }

    @Test
    fun `no delimiter returns null`() {
        assertNull(SetFactoidOperation.parseInput("just some text"))
    }

    @Test
    fun `is without surrounding spaces is not a delimiter`() {
        // "this" contains "is" but not " is "
        assertNull(SetFactoidOperation.parseInput("this has no delimiter"))
    }

    @Test
    fun `multi-word selector with is delimiter`() {
        val result = SetFactoidOperation.parseInput("spring boot is An opinionated framework")
        assertNotNull(result)
        assertEquals("spring boot", result!!.selector)
        assertEquals("An opinionated framework", result.value)
    }

    // -- SEE attribute --

    @Test
    fun `see attribute with equals delimiter`() {
        val result = SetFactoidOperation.parseInput("foo.see=bar")
        assertNotNull(result)
        assertEquals("foo", result!!.selector)
        assertEquals(FactoidAttributeType.SEE, result.attribute)
        assertEquals("bar", result.value)
    }

    @Test
    fun `see attribute with is delimiter`() {
        val result = SetFactoidOperation.parseInput("foo.see is bar")
        assertNotNull(result)
        assertEquals("foo", result!!.selector)
        assertEquals(FactoidAttributeType.SEE, result.attribute)
        assertEquals("bar", result.value)
    }

    // -- Set verb parser --

    @Test
    fun `set verb parses selector and attribute and value`() {
        val result = SetFactoidVerbOperation.parseSetArgument("spring.text A Java framework")
        assertNotNull(result)
        assertEquals("spring", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("A Java framework", result.value)
    }

    @Test
    fun `set verb parses multi-word selector`() {
        val result =
            SetFactoidVerbOperation.parseSetArgument("spring boot.text An opinionated framework")
        assertNotNull(result)
        assertEquals("spring boot", result!!.selector)
        assertEquals(FactoidAttributeType.TEXT, result.attribute)
        assertEquals("An opinionated framework", result.value)
    }

    @Test
    fun `set verb parses see attribute`() {
        val result = SetFactoidVerbOperation.parseSetArgument("foo.see bar")
        assertNotNull(result)
        assertEquals("foo", result!!.selector)
        assertEquals(FactoidAttributeType.SEE, result.attribute)
        assertEquals("bar", result.value)
    }

    @Test
    fun `set verb without attribute returns null`() {
        assertNull(SetFactoidVerbOperation.parseSetArgument("foo bar baz"))
    }

    @Test
    fun `set verb with blank value returns null`() {
        assertNull(SetFactoidVerbOperation.parseSetArgument("foo.text "))
    }

    @Test
    fun `set verb with blank selector returns null`() {
        assertNull(SetFactoidVerbOperation.parseSetArgument(".text value"))
    }
}
