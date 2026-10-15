/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ExtensionDirectoryReportTests {
    @TempDir lateinit var dir: Path

    @Test
    fun `no directory says nothing`() {
        assertNull(ExtensionDirectoryReport.describe(dir.resolve("missing")))
    }

    @Test
    fun `an empty directory says so`() {
        assertEquals("Extensions: $dir is empty", ExtensionDirectoryReport.describe(dir))
    }

    @Test
    fun `jars and resources are listed by name`() {
        Files.writeString(dir.resolve("greet.jar"), "")
        Files.writeString(dir.resolve("audit.jar"), "")
        Files.writeString(dir.resolve("banner.txt"), "")
        Files.createDirectory(dir.resolve("templates"))

        assertEquals(
            "Extensions from $dir: jars audit.jar, greet.jar; resources banner.txt, templates/",
            ExtensionDirectoryReport.describe(dir),
        )
    }

    @Test
    fun `an application yml at the top is found, one in config is not`() {
        assertNull(ExtensionDirectoryReport.shadowedConfiguration(dir))
        Files.createDirectory(dir.resolve("config"))
        Files.writeString(dir.resolve("config").resolve("application.yml"), "")
        assertNull(ExtensionDirectoryReport.shadowedConfiguration(dir))

        Files.writeString(dir.resolve("application.yml"), "")
        val warning = ExtensionDirectoryReport.shadowedConfiguration(dir)!!
        assertEquals(true, warning.contains("application.yml"), warning)
        assertEquals(true, warning.contains("config/"), warning)
    }
}
