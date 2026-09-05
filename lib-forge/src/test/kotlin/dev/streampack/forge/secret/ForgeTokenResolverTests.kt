/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.secret

import dev.streampack.core.model.SecretRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ForgeTokenResolverTests {
    private val env = mapOf("GITHUB_OWNER_REPO_TOKEN" to "ghp_from_env")
    private val lookup = SecretLookup { env[it] }

    @Test
    fun `null and blank tokens resolve to null`() {
        assertNull(ForgeTokenResolver.resolve(null, lookup))
        assertNull(ForgeTokenResolver.resolve(SecretRef.literal("  "), lookup))
    }

    @Test
    fun `literal resolves to itself`() {
        assertEquals(
            "ghp_literal",
            ForgeTokenResolver.resolve(SecretRef.literal("ghp_literal"), lookup),
        )
    }

    @Test
    fun `env reference resolves through the lookup`() {
        assertEquals(
            "ghp_from_env",
            ForgeTokenResolver.resolve(SecretRef.env("GITHUB_OWNER_REPO_TOKEN"), lookup),
        )
    }

    @Test
    fun `unset env reference resolves to null rather than the reference text`() {
        assertNull(ForgeTokenResolver.resolve(SecretRef.env("GITHUB_MISSING_TOKEN"), lookup))
    }
}
