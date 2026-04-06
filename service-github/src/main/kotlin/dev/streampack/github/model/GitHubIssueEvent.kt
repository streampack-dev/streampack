/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubIssueEvent
@JsonCreator
constructor(
    @JsonProperty("action") val action: String,
    @JsonProperty("issue") val issue: GitHubIssuePayload,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPullRequestEvent
@JsonCreator
constructor(
    @JsonProperty("action") val action: String,
    @JsonProperty("pull_request") val pullRequest: GitHubPullPayload,
)
