/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

/**
 * One case per pattern, both ways (#148). The samples are put together at run time from their
 * prefixes, so nothing in the repository looks like a live credential to a push-time scanner.
 */
class SecretScrubberTests {

    private val scrubber = SecretScrubber()

    private fun kindsIn(text: String) = scrubber.scrub(text).kinds.map { it.kind }

    @ParameterizedTest(name = "{0}")
    @MethodSource("secrets")
    fun `a credential is replaced with its kind`(kind: String, secret: String) {
        val result = scrubber.scrub("here you go: $secret thanks")
        assertEquals("here you go: [REDACTED:$kind] thanks", result.text)
        assertEquals(listOf(kind), result.kinds.map { it.kind })
    }

    @ParameterizedTest
    @ValueSource(
        strings =
            [
                "the password is in the vault",
                "what's your password?",
                "password: hunter2",
                "set password=changemeplease",
                "token: \${GITHUB_TOKEN}",
                "api_key=<your-key-here>",
                "I'm a bit risk-averse about sk-learn",
                "sk-this-is-just-a-long-hyphenated-phrase",
                "the commit is deadbeef1234",
                "sha 3f786850e387550fdab836ed7e6dc881de23001b",
                "ghp_short",
                "AKIA is how AWS keys start",
                "see https://example.com/user@host",
                "mail joe@example.com about it",
                "a jwt starts eyJ, that's all",
                "Bearer of bad news",
                "-----BEGIN PUBLIC KEY-----",
                "the token: it's fine",
            ]
    )
    fun `near misses are left alone`(text: String) {
        val result = scrubber.scrub(text)
        assertEquals(text, result.text)
        assertFalse(result.scrubbed)
    }

    @Test
    fun `an assignment keeps its key and loses its value`() {
        val value = "aB3dE5fG7hJ9kL1m"
        assertEquals("DB_PASSWORD=[REDACTED:secret]", scrubber.scrub("DB_PASSWORD=$value").text)
        assertEquals(
            "{\"api_key\": \"[REDACTED:secret]\"}",
            scrubber.scrub("{\"api_key\": \"$value\"}").text,
        )
        assertEquals("token: [REDACTED:secret] ok", scrubber.scrub("token: $value ok").text)
    }

    @Test
    fun `a URL keeps its scheme and host`() {
        assertEquals(
            "postgres://[REDACTED:url-credentials]@db.example.com:5432/app",
            scrubber.scrub("postgres://admin:s3cretpass@db.example.com:5432/app").text,
        )
    }

    @Test
    fun `a bearer token loses only the token`() {
        val token = "Zx81Qw" + "Er77Ty0Ui9Op2As4Df6Gh"
        assertEquals(
            "Authorization: Bearer [REDACTED:bearer-token]",
            scrubber.scrub("Authorization: Bearer $token").text,
        )
    }

    @Test
    fun `a private key block goes whole, and a header alone goes too`() {
        val block =
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEow" +
                "IBAAKCAQEA\nabc\n-----END RSA PRIVATE KEY-----"
        assertEquals("key: [REDACTED:private-key] done", scrubber.scrub("key: $block done").text)
        assertEquals(
            "[REDACTED:private-key]",
            scrubber.scrub("-----BEGIN OPENSSH PRIVATE KEY-----").text,
        )
    }

    @Test
    fun `a named token in an assignment is reported by its name`() {
        val result = scrubber.scrub("GITHUB_TOKEN=${github()}")
        assertEquals("GITHUB_TOKEN=[REDACTED:github-token]", result.text)
        assertEquals(listOf("github-token"), result.kinds.map { it.kind })
    }

    @Test
    fun `several secrets are all scrubbed and each kind reported once`() {
        val text = "${github()} and ${github()} and ${aws()}"
        val result = scrubber.scrub(text)
        assertEquals(
            "[REDACTED:github-token] and [REDACTED:github-token] and [REDACTED:aws-access-key]",
            result.text,
        )
        assertEquals(listOf("github-token", "aws-access-key"), result.kinds.map { it.kind })
    }

    @Test
    fun `an Anthropic key is not also reported as an OpenAI key`() {
        assertEquals(listOf("anthropic-key"), kindsIn(anthropic()))
    }

    @Test
    fun `scrubbing is idempotent`() {
        val once = scrubber.scrub("x ${github()} token: aB3dE5fG7hJ9kL1m").text
        assertEquals(once, scrubber.scrub(once).text)
    }

    @Test
    fun `deployments can add patterns`() {
        val extra =
            SecretScrubber(
                SecretScrubbingProperties(
                    extraPatterns =
                        listOf(
                            SecretScrubbingProperties.ExtraPattern(
                                kind = "internal-token",
                                pattern = "\\bitk_[A-Za-z0-9]{8}\\b",
                            )
                        )
                )
            )
        val result = extra.scrub("use itk_abcd1234 for it")
        assertEquals("use [REDACTED:internal-token] for it", result.text)
        assertEquals("a secret", result.kinds.single().description)
    }

    @Test
    fun `random values look random and words don't`() {
        assertTrue(SecretScrubber.looksRandom("aB3dE5fG7hJ9kL1m"))
        assertTrue(SecretScrubber.looksRandom("Password1234"))
        assertFalse(SecretScrubber.looksRandom("changemeplease"))
        assertFalse(SecretScrubber.looksRandom("hunter2"))
        assertFalse(SecretScrubber.looksRandom("aaaaaaaaaaaa1"))
    }

    companion object {
        private const val ALNUM = "a1B2c3D4e5F6g7H8i9J0kLmNoPqRsTuVwXyZ"

        fun github() = "gh" + "p_" + ALNUM

        fun aws() = "AK" + "IA" + "Q3EGRF7XKZ2M4N5P"

        fun anthropic() = "sk-" + "ant-api03-" + ALNUM

        @JvmStatic
        fun secrets(): List<Arguments> =
            listOf(
                Arguments.of("github-token", github()),
                Arguments.of("github-token", "gh" + "s_" + ALNUM),
                Arguments.of("github-token", "github" + "_pat_11ABCDEFG0" + ALNUM),
                Arguments.of("gitlab-token", "gl" + "pat-" + "xY7zQ2wE9rT4uI1oP3aS"),
                Arguments.of("slack-token", "xo" + "xb-1234567890-0987654321-" + ALNUM.take(24)),
                Arguments.of("slack-token", "xo" + "xp-1234567890-0987654321-" + ALNUM.take(24)),
                Arguments.of("slack-token", "xa" + "pp-1-A0123456789-" + ALNUM.take(20)),
                Arguments.of(
                    "slack-webhook",
                    "https://hooks.slack" + ".com/services/T0000/B0000/" + ALNUM.take(24),
                ),
                Arguments.of("anthropic-key", anthropic()),
                Arguments.of("openai-key", "sk-" + "proj-" + ALNUM + "_" + ALNUM),
                Arguments.of(
                    "openai-key",
                    "sk-" + ALNUM.take(20) + "T3Blbk" + "FJ" + ALNUM.take(20),
                ),
                Arguments.of("aws-access-key", aws()),
                Arguments.of("aws-access-key", "AS" + "IA" + "Q3EGRF7XKZ2M4N5P"),
                Arguments.of("google-api-key", "AI" + "za" + "SyA1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q"),
                Arguments.of("stripe-key", "sk" + "_live_" + ALNUM.take(24)),
                Arguments.of("stripe-key", "rk" + "_live_" + ALNUM.take(24)),
                Arguments.of("npm-token", "np" + "m_" + ALNUM),
                Arguments.of(
                    "discord-webhook",
                    "https://discord" + ".com/api/webhooks/123456789012345678/" + ALNUM,
                ),
                Arguments.of(
                    "jwt",
                    "ey" +
                        "JhbGciOiJIUzI1NiJ9.ey" +
                        "JzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N",
                ),
                Arguments.of(
                    "discord-token",
                    "MT" + "IzNDU2Nzg5MDEyMzQ1Njc4.GaBcDe." + ALNUM.take(30),
                ),
            )
    }
}
