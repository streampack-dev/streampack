/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance

/**
 * The server's egress transformers, applied to a result before it reaches the egress channel: what
 * an extension calls for output it sends by itself, such as a timer's message (#294).
 */
interface TransformerChainService {
    /** Applies every enabled, applicable transformer to [result], in priority order. */
    fun apply(result: OperationResult, provenance: Provenance): OperationResult
}
