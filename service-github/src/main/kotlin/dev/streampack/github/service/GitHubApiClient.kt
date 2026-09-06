/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.forge.model.ForgePipeline
import dev.streampack.forge.model.PipelineOutcome
import dev.streampack.github.config.GitHubProperties
import dev.streampack.github.model.GitHubApiItem
import dev.streampack.github.model.GitHubApiRelease
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.kohsuke.github.GHIssueState
import org.kohsuke.github.GitHub
import org.kohsuke.github.GitHubBuilder
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

/**
 * Client for the GitHub REST API, backed by hub4j/github-api. Every call names the API base URL of
 * the instance it targets (`https://api.github.com` or an Enterprise Server's `/api/v3`).
 */
@Service
class GitHubApiClient(properties: GitHubProperties) {
    private val logger = LoggerFactory.getLogger(GitHubApiClient::class.java)
    private val mapper = JacksonMappers.standard()

    /*
     * The Actions endpoints are read as plain JSON rather than through hub4j: its workflow-run
     * wrapper refreshes every linked pull request with a separate API call just to expose the
     * number, which the payload already carries.
     */
    private val restClient: RestClient =
        RestClient.builder()
            .requestFactory(
                JdkClientHttpRequestFactory(
                        HttpClient.newBuilder()
                            .connectTimeout(
                                Duration.ofSeconds(properties.connectTimeoutSeconds.toLong())
                            )
                            .build()
                    )
                    .apply {
                        setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds.toLong()))
                    }
            )
            .build()

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

    /** The repository's default branch, or null when the repository cannot be read */
    fun fetchDefaultBranch(apiUrl: String, owner: String, name: String, token: String?): String? =
        try {
            connect(apiUrl, token).getRepository("$owner/$name").defaultBranch
        } catch (e: Exception) {
            logger.warn("Failed to read default branch for {}/{}: {}", owner, name, e.message)
            null
        }

    /**
     * Workflow runs created since [since], newest first. The Actions API filters on creation, not
     * update, so callers pass a lookback and apply their own update-time check.
     */
    fun fetchWorkflowRuns(
        apiUrl: String,
        owner: String,
        name: String,
        token: String?,
        since: Instant,
    ): List<ForgePipeline> {
        val created = DateTimeFormatter.ISO_INSTANT.format(since.minus(RUN_LOOKBACK))
        val node =
            getJson(
                apiUrl,
                "/repos/$owner/$name/actions/runs?created=%3E%3D$created&per_page=$PAGE_SIZE",
                token,
            ) ?: return emptyList()
        return node.path("workflow_runs").mapNotNull { run -> toPipeline(run) }
    }

    /**
     * Names of jobs in [runId] that concluded in failure; the latest job id per name wins so a
     * re-run does not report both attempts.
     */
    fun fetchFailedJobs(
        apiUrl: String,
        owner: String,
        name: String,
        token: String?,
        runId: String,
    ): List<String> {
        val node =
            getJson(
                apiUrl,
                "/repos/$owner/$name/actions/runs/$runId/jobs?per_page=$PAGE_SIZE",
                token,
            ) ?: return emptyList()
        val latestByName = linkedMapOf<String, Pair<Long, String>>()
        for (job in node.path("jobs")) {
            val jobName = job.path("name").asString("")
            val id = job.path("id").asLong(0)
            val previous = latestByName[jobName]
            if (previous == null || id > previous.first) {
                latestByName[jobName] = id to job.path("conclusion").asString("")
            }
        }
        return latestByName.filter { it.value.second == "failure" }.keys.toList()
    }

    private fun toPipeline(run: JsonNode): ForgePipeline? {
        val id = run.path("id").asLong(0)
        if (id <= 0) return null
        val status = run.path("status").asString("")
        val conclusion = run.path("conclusion").asString("")
        val outcome =
            if (status != "completed") PipelineOutcome.IN_PROGRESS
            else conclusionOutcome(conclusion)
        val updatedAt = instant(run.path("updated_at").asString(null)) ?: Instant.now()
        val startedAt = instant(run.path("run_started_at").asString(null))
        val pullRequest =
            run.path("pull_requests").firstOrNull()?.path("number")?.asInt(0)?.takeIf { it > 0 }
        return ForgePipeline(
            id = id.toString(),
            name = run.path("name").asString("").ifBlank { null },
            ref = run.path("head_branch").asString(""),
            changeRequestNumber = pullRequest,
            outcome = outcome,
            reason = reasonWord(conclusion),
            url = run.path("html_url").asString(""),
            duration =
                if (startedAt != null && outcome != PipelineOutcome.IN_PROGRESS) {
                    Duration.between(startedAt, updatedAt)
                } else {
                    null
                },
            updatedAt = updatedAt,
        )
    }

    private fun getJson(apiUrl: String, relative: String, token: String?): JsonNode? {
        val uri = URI.create(apiUrl.trimEnd('/') + relative)
        return try {
            val body =
                restClient
                    .get()
                    .uri(uri)
                    .headers { headers ->
                        headers.set("Accept", "application/vnd.github+json")
                        if (!token.isNullOrBlank()) headers.setBearerAuth(token)
                    }
                    .retrieve()
                    .body(String::class.java) ?: return null
            mapper.readTree(body)
        } catch (e: Exception) {
            logger.warn("GitHub API call {} failed: {}", relative.substringBefore('?'), e.message)
            null
        }
    }

    private fun instant(text: String?): Instant? =
        text?.let {
            try {
                Instant.parse(it)
            } catch (_: Exception) {
                null
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

    companion object {
        private const val PAGE_SIZE = 100
        /** Runs created before the last poll can still settle after it */
        private val RUN_LOOKBACK: Duration = Duration.ofDays(1)

        /** GitHub's conclusion words mapped onto the shared outcomes (issue #57) */
        fun conclusionOutcome(conclusion: String): PipelineOutcome =
            when (conclusion) {
                "success",
                "neutral",
                "skipped" -> PipelineOutcome.SUCCEEDED
                "" -> PipelineOutcome.IN_PROGRESS
                else -> PipelineOutcome.NEEDS_ATTENTION
            }

        /** `failure` reads better as `failed` next to GitLab's wording; the rest stay as given */
        fun reasonWord(conclusion: String): String =
            when (conclusion) {
                "failure" -> "failed"
                "cancelled" -> "canceled"
                else -> conclusion
            }
    }
}
