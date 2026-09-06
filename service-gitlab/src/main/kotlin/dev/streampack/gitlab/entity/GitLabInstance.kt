/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.entity

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

/** A GitLab installation: gitlab.com or a self-hosted server reached at its own REST v4 URL */
@Entity
@Table(name = "gitlab_instances")
data class GitLabInstance(
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
    /** True for the gitlab.com row, which `on <host>` selects when omitted */
    val isDefault: Boolean
        get() = host == ForgeKind.GITLAB.defaultHost

    companion object {
        /** Matches [ForgeKind.GITLAB]'s default host */
        const val DEFAULT_HOST: String = "gitlab.com"
        const val DEFAULT_API_URL: String = "https://gitlab.com/api/v4"

        /**
         * Derives the host users type and the API URL from what an operator gives `gitlab instance
         * add`: a bare host, a base URL, or a full API URL. A URL with no path gets `/api/v4`
         * appended; a path is kept as given.
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
            if (host == DEFAULT_HOST) return DEFAULT_HOST to DEFAULT_API_URL
            val authority = buildString {
                append(uri.scheme).append("://").append(host)
                if (uri.port != -1) append(':').append(uri.port)
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val apiUrl = if (path.isBlank()) "$authority/api/v4" else "$authority$path"
            return host to apiUrl
        }
    }
}
