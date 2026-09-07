/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MattermostUserView(
    val id: String = "",
    val username: String = "",
    @JsonProperty("first_name") val firstName: String? = null,
    @JsonProperty("last_name") val lastName: String? = null,
    val nickname: String? = null,
) {
    /** Full name, else nickname, else username */
    val displayName: String
        get() =
            listOfNotNull(firstName, lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { nickname?.ifBlank { null } ?: username }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MattermostTeamView(
    val id: String = "",
    val name: String = "",
    @JsonProperty("display_name") val displayName: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MattermostChannelView(
    val id: String = "",
    val name: String = "",
    @JsonProperty("display_name") val displayName: String? = null,
    @JsonProperty("team_id") val teamId: String? = null,
    val type: String? = null,
)

internal data class MattermostChannelSearchRequest(val term: String)

internal data class MattermostCreatePostRequest(
    @JsonProperty("channel_id") val channelId: String,
    val message: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class MattermostPostView(
    val id: String = "",
    @JsonProperty("user_id") val userId: String = "",
    @JsonProperty("channel_id") val channelId: String = "",
    val message: String = "",
    val type: String = "",
)
