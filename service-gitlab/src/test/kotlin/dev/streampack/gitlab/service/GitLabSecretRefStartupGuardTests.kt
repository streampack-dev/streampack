/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.service.SilentStartupException
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.entity.GitLabProject
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito

class GitLabSecretRefStartupGuardTests {
    private val projects = Mockito.mock(GitLabProjectRepository::class.java)
    private val instances = Mockito.mock(GitLabInstanceRepository::class.java)
    private val guard =
        GitLabSecretRefStartupGuard(projects, instances, SecretLookup { null }, true)

    @Test
    fun `env keys flatten the path and qualify the host off gitlab dot com`() {
        val hosted = GitLabProject(fullPath = "group/sub/project")
        assertEquals(
            "GITLAB_GROUP_SUB_PROJECT_TOKEN",
            GitLabSecretRefStartupGuard.envKeyFor(hosted),
        )
        val self =
            GitLabInstance(
                host = "gitlab.example.com",
                apiUrl = "https://gitlab.example.com/api/v4",
            )
        assertEquals(
            "GITLAB_GITLAB_EXAMPLE_COM_GROUP_PROJECT_TOKEN",
            GitLabSecretRefStartupGuard.envKeyFor(
                GitLabProject(instance = self, fullPath = "group/project"),
            ),
        )
        assertEquals(
            "GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN",
            GitLabSecretRefStartupGuard.envKeyFor(self),
        )
    }

    @Test
    fun `literal tokens are externalized and missing active variables fail startup`() {
        val project =
            GitLabProject(fullPath = "group/project", token = SecretRef.literal("glpat-abc"))
        Mockito.`when`(projects.findAll()).thenReturn(listOf(project))
        Mockito.`when`(instances.findAll()).thenReturn(emptyList())

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(projects, Mockito.never()).save(Mockito.any())

        guard.enforce { key -> if (key == "GITLAB_GROUP_PROJECT_TOKEN") "glpat-abc" else null }
        val captor = ArgumentCaptor.forClass(GitLabProject::class.java)
        Mockito.verify(projects).save(captor.capture())
        assertEquals("env://GITLAB_GROUP_PROJECT_TOKEN", captor.value.token?.asStoredValue())

        Mockito.`when`(projects.findAll())
            .thenReturn(listOf(project.copy(token = SecretRef.env("GITLAB_GROUP_PROJECT_TOKEN"))))
        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        guard.enforce { key -> if (key == "GITLAB_GROUP_PROJECT_TOKEN") "glpat-abc" else null }
    }

    @Test
    fun `lists what the next start needs, by variable, as the startup check judges it`() {
        val stored =
            GitLabProject(fullPath = "group/stored", token = SecretRef.literal("glpat-abc"))
        val missing =
            GitLabProject(
                fullPath = "group/missing",
                token = SecretRef.env("GITLAB_GROUP_MISSING_TOKEN"),
            )
        val idle =
            GitLabProject(
                fullPath = "group/idle",
                token = SecretRef.env("GITLAB_GROUP_IDLE_TOKEN"),
                active = false,
            )
        val present =
            GitLabProject(
                fullPath = "group/present",
                token = SecretRef.env("GITLAB_GROUP_PRESENT_TOKEN"),
            )
        Mockito.`when`(projects.findAll()).thenReturn(listOf(stored, missing, idle, present))
        Mockito.`when`(instances.findAll()).thenReturn(emptyList())
        val guard =
            GitLabSecretRefStartupGuard(
                projects,
                instances,
                SecretLookup { key -> if (key == "GITLAB_GROUP_PRESENT_TOKEN") "set" else null },
                true,
            )

        assertEquals(
            listOf(
                SecretRequirement(
                    "GITLAB_GROUP_STORED_TOKEN",
                    "GitLab project 'group/stored' token",
                    SecretRequirement.State.STORED_IN_DATABASE,
                ),
                SecretRequirement(
                    "GITLAB_GROUP_MISSING_TOKEN",
                    "GitLab project 'group/missing' token",
                    SecretRequirement.State.NOT_SET,
                ),
            ),
            guard.secretRequirements(),
        )
    }
}
