/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.integration.EgressTransformer
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * [TransformerChainService] over every registered [EgressTransformer] bean. Each transformer is
 * checked for enablement (via [OperationConfigService]) and applicability before being invoked.
 */
@Service
class EgressTransformerChain(
    transformers: List<EgressTransformer>,
    private val operationConfigService: OperationConfigService,
) : TransformerChainService {
    private val logger = LoggerFactory.getLogger(EgressTransformerChain::class.java)
    private val sortedTransformers = transformers.sortedBy { it.priority }

    override fun apply(result: OperationResult, provenance: Provenance): OperationResult {
        if (result !is OperationResult.Success) return result
        val provenanceUri = provenance.encode()
        var current = result
        for (transformer in sortedTransformers) {
            if (
                !operationConfigService.isOperationEnabled(
                    provenanceUri,
                    transformer.transformerGroup,
                )
            ) {
                continue
            }
            if (!transformer.canTransform(current, provenance)) {
                continue
            }
            logger.debug(
                "Applying transformer {} to result for {}",
                transformer::class.simpleName,
                provenanceUri,
            )
            current = transformer.transform(current, provenance)
        }
        return current
    }
}
