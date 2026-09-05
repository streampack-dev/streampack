/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.entity

import dev.streampack.core.model.SecretRef
import dev.streampack.core.persistence.SecretRefConverter
import dev.streampack.forge.ForgeKind
import dev.streampack.forge.model.ForgeInstance
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.UuidGenerator

/** A GitHub installation: github.com or a GitHub Enterprise Server reached at its own API URL */
@Entity
@Table(name = "github_instances")
data class GitHubInstance(
    @Id @UuidGenerator(style = UuidGenerator.Style.VERSION_7) val id: UUID = UUID(0, 0),
    @Column(nullable = false, length = 255) override val host: String = DEFAULT_HOST,
    @Column(nullable = false, length = 2048, name = "api_url")
    override val apiUrl: String = DEFAULT_API_URL,
    @Convert(converter = SecretRefConverter::class)
    @Column(length = 500, name = "default_token")
    override val defaultToken: SecretRef? = null,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
    @Column(nullable = false) override val active: Boolean = true,
) : ForgeInstance {
    /** True for the github.com row, which `on <host>` selects when omitted */
    val isDefault: Boolean
        get() = host == ForgeKind.GITHUB.defaultHost

    companion object {
        /** Matches [ForgeKind.GITHUB]'s default host; the guard test below pins the two together */
        const val DEFAULT_HOST: String = "github.com"
        const val DEFAULT_API_URL: String = "https://api.github.com"

        /**
         * Derives the host users type and the API URL hub4j connects to from what an operator gives
         * `github instance add`: a bare host, a base URL, or a full API URL.
         *
         * A URL with no path gets GitHub Enterprise Server's `/api/v3` appended; a path is kept as
         * given. `github.com` in any form maps to `api.github.com`.
         */
        fun endpointFor(input: String): Pair<String, String>? {
            val text = input.trim().trimEnd('/')
            if (text.isBlank()) return null
            val withScheme = if ("://" in text) text else "https://$text"
            val uri =
                try {
                    URI(withScheme)
                } catch (_: Exception) {
                    return null
                }
            val host = uri.host?.lowercase() ?: return null
            if (host == DEFAULT_HOST || host == "api.github.com") {
                return DEFAULT_HOST to DEFAULT_API_URL
            }
            val authority = buildString {
                append(uri.scheme).append("://").append(host)
                if (uri.port != -1) append(':').append(uri.port)
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val apiUrl = if (path.isBlank()) "$authority/api/v3" else "$authority$path"
            return host to apiUrl
        }
    }
}
