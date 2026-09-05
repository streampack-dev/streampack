/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InstanceSelectorTests {
    @Test
    fun `a bare identifier selects no host`() {
        assertEquals(InstanceSelector("owner/repo", null), InstanceSelector.parse("owner/repo"))
    }

    @Test
    fun `a trailing on host is split off`() {
        assertEquals(
            InstanceSelector("owner/repo", "ghe.example.com"),
            InstanceSelector.parse("owner/repo on ghe.example.com"),
        )
    }

    @Test
    fun `the on keyword is case insensitive and whitespace tolerant`() {
        assertEquals(
            InstanceSelector("group/sub/project", "gitlab.example.com"),
            InstanceSelector.parse("  group/sub/project   ON   gitlab.example.com  "),
        )
    }

    @Test
    fun `an identifier that is only on host is not split`() {
        assertEquals(
            InstanceSelector("on ghe.example.com", null),
            InstanceSelector.parse("on ghe.example.com"),
        )
    }

    @Test
    fun `host is normalized to lowercase`() {
        assertEquals(
            "ghe.example.com",
            InstanceSelector.parse("owner/repo on GHE.Example.COM").host,
        )
    }
}
