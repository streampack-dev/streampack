/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.testing

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ExtensionDataStore
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.ServerInfo
import dev.streampack.core.service.TransformerChainService
import java.util.concurrent.ConcurrentHashMap
import org.springframework.messaging.Message
import org.springframework.messaging.MessageChannel

/** [ExtensionDataStore] in memory. */
class InMemoryExtensionDataStore : ExtensionDataStore {
    private val docs = ConcurrentHashMap<Triple<String, String, String>, Map<String, Any>>()

    override fun get(extension: String, collection: String, id: String) =
        docs[Triple(extension, collection, id)]

    override fun put(extension: String, collection: String, id: String, data: Map<String, Any>) {
        docs[Triple(extension, collection, id)] = data
    }

    override fun delete(extension: String, collection: String, id: String) =
        docs.remove(Triple(extension, collection, id)) != null

    override fun list(extension: String, collection: String) =
        docs
            .filterKeys { it.first == extension && it.second == collection }
            .mapKeys { it.key.third }
}

/** [ProvenanceStateService] in memory. */
class InMemoryProvenanceStateService : ProvenanceStateService {
    private val states = ConcurrentHashMap<Pair<String, String>, Map<String, Any>>()

    override fun getState(provenanceUri: String, key: String) = states[provenanceUri to key]

    override fun setState(provenanceUri: String, key: String, data: Map<String, Any>) {
        states[provenanceUri to key] = data
    }

    override fun clearState(provenanceUri: String, key: String) {
        states.remove(provenanceUri to key)
    }
}

/** A [TransformerChainService] with no transformers: a result goes out as it came in. */
class NoTransformers : TransformerChainService {
    override fun apply(result: OperationResult, provenance: Provenance) = result
}

/** A [ServerInfo] that says [version]; null, the default, is a development build. */
class FixedServerInfo(override val version: String? = null) : ServerInfo

/**
 * The server's egress channel, which keeps what is sent to it: what an extension sends by itself,
 * such as a timer's message, in the order it was sent.
 */
class RecordingChannel : MessageChannel {
    val messages: MutableList<Message<*>> = mutableListOf()

    /** The text of each [OperationResult.Success] sent, in order. */
    val texts: List<String>
        get() = messages.mapNotNull {
            (it.payload as? OperationResult.Success)?.payload?.toString()
        }

    override fun send(message: Message<*>, timeout: Long): Boolean {
        messages += message
        return true
    }
}
