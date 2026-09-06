/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github

import dev.streampack.github.entity.GitHubInstance
import dev.streampack.github.repository.GitHubInstanceRepository
import dev.streampack.github.service.GitHubForgeStore

/**
 * Points the `github.com` instance row at a test HTTP server and restores it afterwards, so tests
 * exercise the same per-instance endpoint resolution production uses.
 */
class DefaultInstanceEndpoint(
    private val instanceRepository: GitHubInstanceRepository,
    private val store: GitHubForgeStore,
) {
    fun pointAt(apiUrl: String): GitHubInstance =
        instanceRepository.save(store.defaultInstance().copy(apiUrl = apiUrl))

    fun restore() {
        instanceRepository.findByHost(GitHubInstance.DEFAULT_HOST)?.let {
            instanceRepository.save(it.copy(apiUrl = GitHubInstance.DEFAULT_API_URL))
        }
    }
}
