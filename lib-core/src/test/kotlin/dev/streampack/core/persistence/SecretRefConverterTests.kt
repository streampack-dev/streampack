/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.persistence

import com.enigmastation.streampack.core.model.SecretRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SecretRefConverterTests {
    private val converter = SecretRefConverter()

    @Test
    fun `to database persists literal as-is`() {
        val dbValue = converter.convertToDatabaseColumn(SecretRef.literal("hunter2"))
        assertEquals("hunter2", dbValue)
    }

    @Test
    fun `to database persists env ref verbatim`() {
        val dbValue = converter.convertToDatabaseColumn(SecretRef.env("IRC_LIBERA_SASL_PASSWORD"))
        assertEquals("env://IRC_LIBERA_SASL_PASSWORD", dbValue)
    }

    @Test
    fun `to entity parses stored value into SecretRef`() {
        val ref = converter.convertToEntityAttribute("env://SLACK_PRIMARY_BOT_TOKEN")
        assertEquals("SLACK_PRIMARY_BOT_TOKEN", ref?.envKeyOrNull())
    }

    @Test
    fun `null handling is preserved`() {
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }
}
