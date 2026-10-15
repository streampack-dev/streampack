/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a stored credential still needs from the environment before the next start (#209). */
class SecretRequirementTests {
    private val set = mapOf("MATTERMOST_WORK_TOKEN" to "value")
    private val lookup: (String) -> String? = { set[it] }

    private fun of(current: SecretRef?, required: Boolean = true) =
        SecretRequirement.of(
            current,
            "MATTERMOST_WORK_TOKEN",
            "Mattermost server 'work' token",
            lookup,
            required,
        )

    @Test
    fun `a value stored in the database needs its variable, until the variable is set`() {
        val needed =
            SecretRequirement.of(
                SecretRef.literal("abc"),
                "MATTERMOST_HOME_TOKEN",
                "Mattermost server 'home' token",
                lookup,
            )
        assertEquals(
            SecretRequirement(
                "MATTERMOST_HOME_TOKEN",
                "Mattermost server 'home' token",
                SecretRequirement.State.STORED_IN_DATABASE,
            ),
            needed,
        )
        assertNull(of(SecretRef.literal("abc")))
    }

    @Test
    fun `a reference needs its variable when it isn't set, unless it isn't required`() {
        val missing =
            SecretRequirement.of(
                SecretRef.env("IRC_LIBERA_SASL_PASSWORD"),
                "IRC_LIBERA_SASL_PASSWORD",
                "IRC network 'libera' SASL password",
                lookup,
            )
        assertEquals(SecretRequirement.State.NOT_SET, missing?.state)
        assertEquals("IRC_LIBERA_SASL_PASSWORD", missing?.variable)
        assertNull(
            SecretRequirement.of(
                SecretRef.env("IRC_LIBERA_SASL_PASSWORD"),
                "IRC_LIBERA_SASL_PASSWORD",
                "x",
                lookup,
                required = false,
            ),
        )
        assertNull(of(SecretRef.env("MATTERMOST_WORK_TOKEN")))
    }

    @Test
    fun `a reference names its own variable, whatever the expected one is`() {
        val missing = of(SecretRef.env("SOME_OTHER_TOKEN"))
        assertEquals("SOME_OTHER_TOKEN", missing?.variable)
    }

    @Test
    fun `nothing stored, a blank value or a broken reference needs nothing it can be given`() {
        assertNull(of(null))
        assertNull(of(SecretRef.literal("  ")))
        assertNull(of(SecretRef.parse("env://not a key")))
    }
}
