/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.operation

import dev.streampack.github.model.AddRepoRequest
import dev.streampack.github.model.GitHubWebhookEnableRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class GitHubCommandParsingTests {

    @Autowired lateinit var addOperation: GitHubAddOperation

    @Autowired lateinit var webhookOperation: GitHubWebhookOperation

    @Test
    fun `github add parses owner repo with irregular whitespace`() {
        val translated = addOperation.translate("  github   add   owner/repo  ", message())

        assertEquals(AddRepoRequest("owner/repo", null), translated)
    }

    @Test
    fun `github add preserves quoted token with spaces`() {
        val translated =
            addOperation.translate("""github add owner/repo "token value"""", message())

        assertEquals(AddRepoRequest("owner/repo", "token value"), translated)
    }

    @Test
    fun `github add requires owner repo argument`() {
        val translated = addOperation.translate("github add", message())

        assertNull(translated)
    }

    @Test
    fun `github webhook parses owner repo with irregular whitespace`() {
        val translated = webhookOperation.translate("  github   webhook   owner/repo  ", message())

        assertEquals(GitHubWebhookEnableRequest("owner/repo", privateMode = false), translated)
    }

    @Test
    fun `github webhook parses private owner repo syntax`() {
        val translated = webhookOperation.translate("github webhook private owner/repo", message())

        assertEquals(GitHubWebhookEnableRequest("owner/repo", privateMode = true), translated)
    }

    @Test
    fun `github webhook requires owner repo argument`() {
        val translated = webhookOperation.translate("github webhook", message())

        assertNull(translated)
    }

    private fun message() = MessageBuilder.withPayload("ignored").build()
}
