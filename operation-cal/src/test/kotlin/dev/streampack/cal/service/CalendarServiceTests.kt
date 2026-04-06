/* Joseph B. Ottinger (C)2026 */
package dev.streampack.cal.service

import dev.streampack.cal.model.CalendarSystem
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class CalendarServiceTests {
    @Autowired lateinit var service: CalendarService

    @Autowired lateinit var calendars: List<CalendarSystem>

    @Test
    fun `each calendar system returns a non-blank date`() {
        for (calendar in calendars) {
            val result = calendar.today()
            assertTrue(result.isNotBlank(), "${calendar.name} returned blank")
        }
    }

    @Test
    fun `getCalendar finds hebrew by lowercase name`() {
        val cal = service.getCalendar("hebrew")
        assertNotNull(cal)
        assertEquals("Hebrew", cal!!.displayName)
    }

    @Test
    fun `getCalendar is case-insensitive`() {
        val cal = service.getCalendar("HEBREW")
        assertNotNull(cal)
        assertEquals("hebrew", cal!!.name)
    }

    @Test
    fun `getCalendar returns null for unknown name`() {
        assertNull(service.getCalendar("nonexistent"))
    }

    @Test
    fun `listCalendars returns all 6 calendars`() {
        val list = service.listCalendars()
        assertEquals(6, list.size)
    }

    @Test
    fun `defaultCalendar returns Gregorian`() {
        val cal = service.defaultCalendar()
        assertEquals("gregorian", cal.name)
    }

    @Test
    fun `formatDate with explicit date returns non-blank for all calendars`() {
        val date = LocalDate.of(2026, 3, 15)
        for (calendar in calendars) {
            val result = service.formatDate(date, calendar.name)
            assertNotNull(result, "${calendar.name} returned null for formatDate")
            assertTrue(result!!.isNotBlank(), "${calendar.name} returned blank for formatDate")
        }
    }

    @Test
    fun `formatDate with null calendar uses Gregorian`() {
        val date = LocalDate.of(2026, 1, 1)
        val result = service.formatDate(date)
        assertNotNull(result)
        assertTrue(result!!.contains("2026"), "Expected year 2026 in Gregorian: $result")
    }

    @Test
    fun `formatDate with unknown calendar returns null`() {
        assertNull(service.formatDate(LocalDate.now(), "nonexistent"))
    }
}
