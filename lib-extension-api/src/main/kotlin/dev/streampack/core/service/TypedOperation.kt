/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.OperationOutcome
import kotlin.reflect.KClass
import org.springframework.messaging.Message

/**
 * Base class for operations that accept a specific payload type.
 *
 * Eliminates the canHandle/cast boilerplate that every typed operation repeats. Subclasses declare
 * their payload type via the constructor and implement [handle] with a pre-cast payload.
 *
 * Operations that need raw message access or want to accept multiple payload types should implement
 * [Operation] directly instead.
 */
abstract class TypedOperation<T : Any>(private val payloadType: KClass<T>) : Operation {

    override fun canHandle(message: Message<*>): Boolean {
        if (!payloadType.isInstance(message.payload)) return false
        @Suppress("UNCHECKED_CAST")
        return canHandle(message.payload as T, message)
    }

    /** Typed pre-flight check, called only after the payload type has been verified. */
    open fun canHandle(payload: T, message: Message<*>): Boolean = true

    override fun execute(message: Message<*>): OperationOutcome? {
        @Suppress("UNCHECKED_CAST")
        return handle(message.payload as T, message)
    }

    /** Process the typed payload and produce a result */
    abstract fun handle(payload: T, message: Message<*>): OperationOutcome?
}
