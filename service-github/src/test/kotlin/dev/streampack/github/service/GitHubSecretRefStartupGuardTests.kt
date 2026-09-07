/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.repository.GitHubRepoRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class GitHubSecretRefStartupGuardTests {
    private val repository = Mockito.mock(GitHubRepoRepository::class.java)
    private val instanceRepository = Mockito.mock(GitHubInstanceRepository::class.java)
    private val guard =
        GitHubSecretRefStartupGuard(repository, instanceRepository, SecretLookup { null }, true)

    @Test
    fun `env key is host qualified for repositories off the hosted default`() {
        val ghe =
            GitHubInstance(host = "ghe.example.com", apiUrl = "https://ghe.example.com/api/v3")
        assertEquals(
            "GITHUB_GHE_EXAMPLE_COM_OWNER_REPO_TOKEN",
            GitHubSecretRefStartupGuard.envKeyFor(
                GitHubRepo(instance = ghe, owner = "owner", name = "repo")
            ),
        )
        assertEquals(
            "GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN",
            GitHubSecretRefStartupGuard.envKeyFor(ghe),
        )
    }

    @Test
    fun `literal instance token is externalized and a missing active instance variable fails startup`() {
        val literal =
            GitHubInstance(
                host = "ghe.example.com",
                apiUrl = "https://ghe.example.com/api/v3",
                defaultToken = SecretRef.literal("ghp_default"),
            )
        Mockito.`when`(instanceRepository.findAll()).thenReturn(listOf(literal))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(instanceRepository, Mockito.never()).save(Mockito.any())

        guard.enforce { key ->
            if (key == "GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN") "ghp_default" else null
        }
        val captor = ArgumentCaptor.forClass(GitHubInstance::class.java)
        Mockito.verify(instanceRepository).save(captor.capture())
        assertEquals(
            "env://GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN",
            captor.value.defaultToken?.asStoredValue(),
        )

        val referenced =
            literal.copy(defaultToken = SecretRef.env("GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN"))
        Mockito.`when`(instanceRepository.findAll()).thenReturn(listOf(referenced))
        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        guard.enforce { key ->
            if (key == "GITHUB_INSTANCE_GHE_EXAMPLE_COM_TOKEN") "ghp_default" else null
        }
    }

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
    fun `literal token fails startup until its variable exists and is never rewritten early`() {
        val repo = GitHubRepo(owner = "owner", name = "repo", token = SecretRef.literal("ghp_abc"))
        Mockito.`when`(repository.findAll()).thenReturn(listOf(repo))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `literal token is externalized once its variable exists`() {
        val repo = GitHubRepo(owner = "owner", name = "repo", token = SecretRef.literal("ghp_abc"))
        Mockito.`when`(repository.findAll()).thenReturn(listOf(repo))

        guard.enforce { key -> if (key == "GITHUB_OWNER_REPO_TOKEN") "ghp_abc" else null }

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
