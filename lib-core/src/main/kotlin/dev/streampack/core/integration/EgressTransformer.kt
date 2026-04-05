/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Provenance

/**
 * Transforms operation results before they reach the egress channel. Analogous to [Operation] but
 * for the output path. Transformers are ordered by [priority] (lower runs first) and applied
 * sequentially by [TransformerChainService].
 */
interface EgressTransformer {
    val priority: Int
        get() = 50

    /** Group name used for enable/disable and config via OperationConfigService */
    val transformerGroup: String

    /** Returns true if this transformer should act on the given result and provenance */
    fun canTransform(result: OperationResult, provenance: Provenance): Boolean

    /** Applies the transformation and returns the (possibly modified) result */
    fun transform(result: OperationResult, provenance: Provenance): OperationResult
}
