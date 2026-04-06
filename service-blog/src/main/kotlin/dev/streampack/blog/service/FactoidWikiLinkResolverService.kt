/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.factoid.model.FactoidLinkMetadataResponse
import dev.streampack.factoid.model.FindFactoidLinkMetadataRequest
import java.net.URI
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

@Service
class FactoidWikiLinkResolverService(private val eventGateway: EventGateway) :
    FactoidWikiLinkResolver {
    override fun resolve(selector: String): FactoidWikiLinkMetadata? {
        if (selector.isBlank()) return null
        val metadata = lookup(selector.trim())
        val urls = metadata?.urls.orEmpty()
        val text = metadata?.text.orEmpty()

        val safeUrl = firstHttpUrl(urls)
        if (safeUrl == null && text.isBlank()) return null
        return FactoidWikiLinkMetadata(href = safeUrl, title = text.ifBlank { null })
    }

    private fun lookup(selector: String): FactoidLinkMetadataResponse? {
        val provenance =
            Provenance(protocol = Protocol.HTTP, serviceId = "blog-service", replyTo = "factoid")
        val message =
            MessageBuilder.withPayload(FindFactoidLinkMetadataRequest(selector))
                .setHeader(Provenance.HEADER, provenance)
                .build()
        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> result.payload as? FactoidLinkMetadataResponse
            else -> null
        }
    }

    private fun firstHttpUrl(urls: List<String>): String? {
        if (urls.isEmpty()) return null
        return urls.asSequence().map { it.trim() }.firstNotNullOfOrNull { sanitizeHttpUrl(it) }
    }

    private fun sanitizeHttpUrl(url: String): String? =
        try {
            if (url.isBlank()) return null
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase().orEmpty()
            if (scheme != "http" && scheme != "https") null else uri.toString()
        } catch (_: Exception) {
            null
        }
}
