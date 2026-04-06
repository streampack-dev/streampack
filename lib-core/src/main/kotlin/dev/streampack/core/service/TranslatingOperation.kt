/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.OperationOutcome
import kotlin.reflect.KClass
import org.springframework.messaging.Message

/**
 * Base class for operations that accept both a typed request and raw strings.
 *
 * Extends the [TypedOperation] concept for operations whose input can arrive either as a
 * pre-constructed typed request (from REST controllers or other operations) or as a raw string
 * (from IRC, console, or an HTTP command line). Subclasses implement [translate] to parse strings
 * into their typed request, and [handle] to process the typed request regardless of how it arrived.
 *
 * When the payload is already the correct type, [translate] is never called. When the payload is a
 * String, [translate] is called to attempt conversion; returning null signals that this operation
 * cannot handle the string.
 */
abstract class TranslatingOperation<T : Any>(private val payloadType: KClass<T>) : Operation {
    /** Parse a string payload into the typed request, or null if this string is not handleable */
    abstract fun translate(payload: String, message: Message<*>): T?

    /** Process the typed request and produce a result */
    abstract fun handle(payload: T, message: Message<*>): OperationOutcome?

    /** Typed pre-flight check, called only after the payload type has been resolved. */
    @Suppress("UNCHECKED_CAST") open fun canHandle(payload: T, message: Message<*>): Boolean = true

    final override fun canHandle(message: Message<*>): Boolean {
        @Suppress("UNCHECKED_CAST")
        val resolved =
            when (val p = message.payload) {
                is String -> translate(p, message) ?: return false
                else -> if (payloadType.isInstance(p)) p as T else return false
            }
        return canHandle(resolved, message)
    }

    override fun execute(message: Message<*>): OperationOutcome? {
        @Suppress("UNCHECKED_CAST")
        val typed =
            when (val p = message.payload) {
                is String -> translate(p, message) ?: return null
                else ->
                    if (payloadType.isInstance(p)) {
                        p as T
                    } else {
                        return null
                    }
            }
        return handle(typed, message)
    }
}
