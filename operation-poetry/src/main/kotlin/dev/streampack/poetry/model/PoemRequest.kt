/* Joseph B. Ottinger (C)2026 */
package dev.streampack.poetry.model

/** Request to generate a poem about a topic in a given form */
data class PoemRequest(val topic: String, val form: String = "short poem")
