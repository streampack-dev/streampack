/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubPullPayload
@JsonCreator
constructor(
    @JsonProperty("number") val number: Int,
    @JsonProperty("title") val title: String,
    @JsonProperty("html_url") val htmlUrl: String,
)
