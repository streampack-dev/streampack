/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SilentStartupException
import dev.streampack.slack.entity.SlackWorkspace
import dev.streampack.slack.repository.SlackWorkspaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.core.env.Environment

class SlackSecretRefStartupGuardTests {
    private val repository = Mockito.mock(SlackWorkspaceRepository::class.java)
    private val environment = Mockito.mock(Environment::class.java)
    private val guard = SlackSecretRefStartupGuard(repository, environment, true)

    @Test
    fun `literal slack tokens are externalized and startup fails`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.literal("xoxb-123"),
                appToken = SecretRef.literal("xapp-456"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(workspace))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }

        val captor = ArgumentCaptor.forClass(SlackWorkspace::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("env://SLACK_JVM_NEWS_BOT_TOKEN", captor.value.botToken.asStoredValue())
        assertEquals("env://SLACK_JVM_NEWS_APP_TOKEN", captor.value.appToken.asStoredValue())
    }

    @Test
    fun `missing environment variable for slack env ref fails startup`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.env("SLACK_JVM_NEWS_BOT_TOKEN"),
                appToken = SecretRef.env("SLACK_JVM_NEWS_APP_TOKEN"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(workspace))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `env-backed slack tokens pass when environment values exist`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.env("SLACK_JVM_NEWS_BOT_TOKEN"),
                appToken = SecretRef.env("SLACK_JVM_NEWS_APP_TOKEN"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(workspace))

        guard.enforce { key ->
            when (key) {
                "SLACK_JVM_NEWS_BOT_TOKEN" -> "xoxb-123"
                "SLACK_JVM_NEWS_APP_TOKEN" -> "xapp-456"
                else -> null
            }
        }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }
}
