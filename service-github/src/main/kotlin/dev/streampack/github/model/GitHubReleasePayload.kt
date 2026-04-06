/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubReleasePayload
@JsonCreator
constructor(
    @JsonProperty("tag_name") val tagName: String,
    @JsonProperty("html_url") val htmlUrl: String,
)
