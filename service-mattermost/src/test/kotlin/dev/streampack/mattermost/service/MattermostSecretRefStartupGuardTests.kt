/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.service.SilentStartupException
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class MattermostSecretRefStartupGuardTests {
    private val repository = Mockito.mock(MattermostServerRepository::class.java)
    private val guard =
        MattermostSecretRefStartupGuard(
            repository,
            Mockito.mock(org.springframework.core.env.Environment::class.java),
            true,
        )

    @Test
    fun `literal token fails startup until its variable exists and is never printed or rewritten early`() {
        val server =
            MattermostServer(
                name = "work",
                baseUrl = "https://mm.example.com",
                token = SecretRef.literal("mm-abc"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(server))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())

        guard.enforce { key -> if (key == "MATTERMOST_WORK_TOKEN") "mm-abc" else null }
        val captor = ArgumentCaptor.forClass(MattermostServer::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("env://MATTERMOST_WORK_TOKEN", captor.value.token.asStoredValue())
    }

    @Test
    fun `missing variable fails and a present one passes`() {
        val server =
            MattermostServer(
                name = "work",
                baseUrl = "https://mm.example.com",
                token = SecretRef.env("MATTERMOST_WORK_TOKEN"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(server))
        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        guard.enforce { key -> if (key == "MATTERMOST_WORK_TOKEN") "mm-abc" else null }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `lists what the next start needs, by variable, until it's set`() {
        val environment = Mockito.mock(org.springframework.core.env.Environment::class.java)
        val guard = MattermostSecretRefStartupGuard(repository, environment, true)
        val server =
            MattermostServer(
                name = "work",
                baseUrl = "https://mm.example.com",
                token = SecretRef.literal("mm-abc"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(server))

        assertEquals(
            listOf(
                SecretRequirement(
                    "MATTERMOST_WORK_TOKEN",
                    "Mattermost server 'work' token",
                    SecretRequirement.State.STORED_IN_DATABASE,
                ),
            ),
            guard.secretRequirements(),
        )
        Mockito.`when`(environment.getProperty("MATTERMOST_WORK_TOKEN")).thenReturn("mm-abc")
        assertEquals(emptyList<SecretRequirement>(), guard.secretRequirements())
    }
}
