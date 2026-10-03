/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.dto

import io.swagger.v3.oas.annotations.media.Schema

/** A factoid attribute to set: its value, and which attribute (the text, unless named). */
data class FactoidSetHttpRequest(
    @field:Schema(
        description = "The attribute's new value",
        example = "a string-searching algorithm",
    )
    val value: String,
    @field:Schema(
        description =
            "The attribute to set: text (the default), urls, tags, languages, type, seealso, see or maven",
        example = "text",
    )
    val attribute: String? = null,
)
