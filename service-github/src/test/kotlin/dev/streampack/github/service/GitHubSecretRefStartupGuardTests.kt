/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubRepoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class GitHubSecretRefStartupGuardTests {
    private val repository = Mockito.mock(GitHubRepoRepository::class.java)
    private val guard = GitHubSecretRefStartupGuard(repository, SecretLookup { null }, true)

    @Test
    fun `env key is derived from owner and name`() {
        assertEquals(
            "GITHUB_STREAMPACK_DEV_STREAMPACK_TOKEN",
            GitHubSecretRefStartupGuard.envKeyFor(
                GitHubRepo(owner = "streampack-dev", name = "streampack")
            ),
        )
    }

    @Test
    fun `literal token is externalized and startup fails`() {
        val repo = GitHubRepo(owner = "owner", name = "repo", token = SecretRef.literal("ghp_abc"))
        Mockito.`when`(repository.findAll()).thenReturn(listOf(repo))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }

        val captor = ArgumentCaptor.forClass(GitHubRepo::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("env://GITHUB_OWNER_REPO_TOKEN", captor.value.token?.asStoredValue())
    }

    @Test
    fun `missing environment variable for active repo fails startup`() {
        val repo =
            GitHubRepo(
                owner = "owner",
                name = "repo",
                token = SecretRef.env("GITHUB_OWNER_REPO_TOKEN"),
            )
        Mockito.`when`(repository.findAll()).thenReturn(listOf(repo))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `missing environment variable for inactive repo is tolerated`() {
        val repo =
            GitHubRepo(
                owner = "owner",
                name = "repo",
                token = SecretRef.env("GITHUB_OWNER_REPO_TOKEN"),
                active = false,
            )
        Mockito.`when`(repository.findAll()).thenReturn(listOf(repo))

        guard.enforce { null }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `env-backed token passes when the variable exists and repos without tokens are ignored`() {
        val withToken =
            GitHubRepo(
                owner = "owner",
                name = "repo",
                token = SecretRef.env("GITHUB_OWNER_REPO_TOKEN"),
            )
        val without = GitHubRepo(owner = "owner", name = "public")
        Mockito.`when`(repository.findAll()).thenReturn(listOf(withToken, without))

        guard.enforce { key -> if (key == "GITHUB_OWNER_REPO_TOKEN") "ghp_abc" else null }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }
}
