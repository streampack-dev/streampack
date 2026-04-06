/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubReleaseEvent
@JsonCreator
constructor(
    @JsonProperty("action") val action: String,
    @JsonProperty("release") val release: GitHubReleasePayload,
)
