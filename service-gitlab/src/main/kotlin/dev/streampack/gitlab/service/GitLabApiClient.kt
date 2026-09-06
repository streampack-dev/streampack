/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.forge.model.ForgeItem
import dev.streampack.forge.model.ForgeProjectRef
import dev.streampack.forge.model.ForgeReleaseInfo
import dev.streampack.gitlab.config.ConditionalOnGitLab
import dev.streampack.gitlab.config.GitLabProperties
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode

/**
 * Client for the GitLab REST API v4 on Spring's [RestClient]. Every call names the API base URL of
 * the instance it targets (`https://gitlab.com/api/v4` or a self-hosted equivalent). Projects are
 * addressed by their URL-encoded full path, which the API accepts wherever a project id is.
 */
@Service
@ConditionalOnGitLab
class GitLabApiClient(properties: GitLabProperties) {
    private val logger = LoggerFactory.getLogger(GitLabApiClient::class.java)
    private val mapper = JacksonMappers.standard()
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

    /** The project at [path] with its numeric id, or null when missing or not readable */
    fun lookupProject(apiUrl: String, path: String, token: String?): ForgeProjectRef? {
        val node = get(apiUrl, "/projects/${encode(path)}", token, path) ?: return null
        val id = node.path("id").asLong(0)
        val canonical = node.path("path_with_namespace").asString().ifBlank { path }
        if (id <= 0) return null
        return ForgeProjectRef(path = canonical, externalId = id.toString())
    }

    /** Issues with `iid` above [sinceIid], newest first */
    fun fetchIssues(apiUrl: String, path: String, token: String?, sinceIid: Int): List<ForgeItem> =
        fetchNumbered(apiUrl, path, "issues", token, sinceIid)

    /** Merge requests with `iid` above [sinceIid], newest first */
    fun fetchMergeRequests(
        apiUrl: String,
        path: String,
        token: String?,
        sinceIid: Int,
    ): List<ForgeItem> = fetchNumbered(apiUrl, path, "merge_requests", token, sinceIid)

    /** Releases, newest first, up to the first page the API returns */
    fun fetchReleases(apiUrl: String, path: String, token: String?): List<ForgeReleaseInfo> {
        val node =
            get(apiUrl, "/projects/${encode(path)}/releases?per_page=$PAGE_SIZE", token, path)
                ?: return emptyList()
        return node.mapNotNull { release ->
            val tag = release.path("tag_name").asString()
            if (tag.isBlank()) null
            else
                ForgeReleaseInfo(
                    tag = tag,
                    name = release.path("name").asString(null),
                    url = release.path("_links").path("self").asString(""),
                )
        }
    }

    /**
     * Walks pages newest-first and stops at the first item at or below [sinceIid], so a poll
     * fetches one page in the common case. Bounded by [MAX_PAGES] so a huge project's baseline
     * cannot run away; the cursor only needs the highest iid, which is on the first page.
     */
    private fun fetchNumbered(
        apiUrl: String,
        path: String,
        resource: String,
        token: String?,
        sinceIid: Int,
    ): List<ForgeItem> {
        val items = mutableListOf<ForgeItem>()
        for (page in 1..MAX_PAGES) {
            val query = "state=all&order_by=created_at&sort=desc&per_page=$PAGE_SIZE&page=$page"
            val node =
                get(apiUrl, "/projects/${encode(path)}/$resource?$query", token, path)
                    ?: return items
            var reachedCursor = false
            for (item in node) {
                val iid = item.path("iid").asInt(0)
                if (iid <= sinceIid) {
                    reachedCursor = true
                    continue
                }
                items +=
                    ForgeItem(
                        number = iid,
                        title = item.path("title").asString(""),
                        url = item.path("web_url").asString(""),
                    )
            }
            if (reachedCursor || node.size() < PAGE_SIZE) break
        }
        return items
    }

    /** GET [relative] under [apiUrl]; null on any failure, which the caller treats as empty */
    private fun get(apiUrl: String, relative: String, token: String?, path: String): JsonNode? {
        val uri = URI.create(apiUrl.trimEnd('/') + relative)
        return try {
            val body =
                restClient
                    .get()
                    .uri(uri)
                    .headers { headers ->
                        if (!token.isNullOrBlank()) headers.set(TOKEN_HEADER, token)
                    }
                    .retrieve()
                    .body(String::class.java) ?: return null
            mapper.readTree(body)
        } catch (e: RestClientResponseException) {
            logger.debug(
                "GitLab API {} for {} returned {}",
                relative.substringBefore('?'),
                path,
                e.statusCode.value(),
            )
            null
        } catch (e: Exception) {
            logger.warn("GitLab API call for {} failed: {}", path, e.message)
            null
        }
    }

    private fun encode(path: String): String = URLEncoder.encode(path, StandardCharsets.UTF_8)

    companion object {
        const val TOKEN_HEADER = "PRIVATE-TOKEN"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 10
    }
}
