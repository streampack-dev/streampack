/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance

/**
 * Sends a message by itself, with no command to answer, such as a timer's or a feed's (#294). The
 * server applies its egress transformers, then delivers it to [Provenance]'s channel. A failure is
 * logged, not thrown, so a notifier's loop goes on to the next destination.
 */
interface Egress {
    /** Sends [result] to the channel [to] names. */
    fun send(result: OperationResult, to: Provenance)

    /** Sends [text] to the channel [destinationUri] encodes; one that doesn't decode is logged. */
    fun send(text: String, destinationUri: String)
}
