/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.model.GitHubApiItem
import dev.streampack.github.model.GitHubApiRelease
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Client for the GitHub REST API, backed by hub4j/github-api. Every call names the API base URL of
 * the instance it targets (`https://api.github.com` or an Enterprise Server's `/api/v3`).
 */
@Service
class GitHubApiClient {
    private val logger = LoggerFactory.getLogger(GitHubApiClient::class.java)

    /** Validate that a repository exists and is accessible */
    fun validateRepo(apiUrl: String, owner: String, name: String, token: String?): Boolean {
        return try {
            connect(apiUrl, token).getRepository("$owner/$name")
            true
        } catch (e: Exception) {
            logger.debug("Repository {}/{} not accessible: {}", owner, name, e.message)
            false
        }
    }

    /** Fetch issues with number greater than sinceNumber, excluding pull requests */
    fun fetchIssues(
        apiUrl: String,
        owner: String,
        name: String,
        token: String?,
        sinceNumber: Int,
    ): List<GitHubApiItem> {
        return try {
            val repo = connect(apiUrl, token).getRepository("$owner/$name")
            repo
                .getIssues(GHIssueState.ALL)
                .filter { !it.isPullRequest && it.number > sinceNumber }
                .map { issue ->
                    GitHubApiItem(
                        number = issue.number,
                        title = issue.title ?: "",
                        htmlUrl = issue.htmlUrl?.toString() ?: "",
                        pullRequest = false,
                    )
                }
        } catch (e: Exception) {
            logger.warn("Failed to fetch issues for {}/{}: {}", owner, name, e.message)
            emptyList()
        }
    }

    /** Fetch pull requests with number greater than sinceNumber */
    fun fetchPulls(
        apiUrl: String,
        owner: String,
        name: String,
        token: String?,
        sinceNumber: Int,
    ): List<GitHubApiItem> {
        return try {
            val repo = connect(apiUrl, token).getRepository("$owner/$name")
            repo
                .getPullRequests(GHIssueState.ALL)
                .filter { it.number > sinceNumber }
                .map { pr ->
                    GitHubApiItem(
                        number = pr.number,
                        title = pr.title ?: "",
                        htmlUrl = pr.htmlUrl?.toString() ?: "",
                        pullRequest = true,
                    )
                }
        } catch (e: Exception) {
            logger.warn("Failed to fetch PRs for {}/{}: {}", owner, name, e.message)
            emptyList()
        }
    }

    /** Fetch all releases (up to 100) */
    fun fetchReleases(
        apiUrl: String,
        owner: String,
        name: String,
        token: String?,
    ): List<GitHubApiRelease> {
        return try {
            val repo = connect(apiUrl, token).getRepository("$owner/$name")
            repo.listReleases().toList().map { release ->
                GitHubApiRelease(
                    tagName = release.tagName ?: "",
                    name = release.name,
                    htmlUrl = release.htmlUrl?.toString() ?: "",
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch releases for {}/{}: {}", owner, name, e.message)
            emptyList()
        }
    }

    /** Build a GitHub client for [apiUrl] with an optional token */
    private fun connect(apiUrl: String, token: String?): GitHub {
        val builder = GitHubBuilder().withEndpoint(apiUrl)
        if (!token.isNullOrBlank()) {
            builder.withOAuthToken(token)
        }
        return builder.build()
    }
}
