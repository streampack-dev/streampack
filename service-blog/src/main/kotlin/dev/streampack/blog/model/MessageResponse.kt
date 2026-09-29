/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

/**
 * A JSON body for an operation whose result is only a sentence ("Account deleted"). Operations
 * return those as plain strings, which suits chat; over HTTP a bare string would be written raw
 * under a JSON content type, which isn't JSON (#91).
 */
data class MessageResponse(val message: String) {
    companion object {
        /**
         * [payload] as an HTTP body: a string becomes a [MessageResponse], anything else is kept.
         */
        fun body(payload: Any?): Any? = if (payload is String) MessageResponse(payload) else payload
    }
}
