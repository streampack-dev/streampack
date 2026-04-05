/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.service.OperationConfigService
import org.springframework.stereotype.Component

/**
 * Replaces words in outbound messages based on per-channel config. The config map IS the
 * replacement map: each key is a word to replace, each value is the replacement text. Set via
 * `channel set content-filter badword goodword`.
 *
 * Skips bridged messages (already transformed when originally produced or relayed as-is).
 */
@Component
class ContentFilterTransformer(private val configService: OperationConfigService) :
    EgressTransformer {

    override val transformerGroup: String = "content-filter"

    override fun canTransform(result: OperationResult, provenance: Provenance): Boolean {
        if (result !is OperationResult.Success) return false
        if (provenance.metadata.containsKey(Provenance.BRIDGED)) return false
        return true
    }

    override fun transform(result: OperationResult, provenance: Provenance): OperationResult {
        val success = result as OperationResult.Success
        val provenanceUri = provenance.encode()
        val replacements = configService.getOperationConfig(provenanceUri, transformerGroup)
        if (replacements.isEmpty()) return result

        var text = success.payload.toString()
        for ((word, replacement) in replacements) {
            text = text.replace(word, replacement.toString(), ignoreCase = true)
        }
        return if (text == success.payload.toString()) result else success.copy(payload = text)
    }
}
