/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.json

import tools.jackson.core.json.JsonReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import tools.jackson.module.kotlin.jsonMapper

/**
 * Central mapper factory for project-wide Jackson behavior.
 *
 * We intentionally construct mappers per use-site (lightweight in Jackson 3), but from one place so
 * defaults and compatibility toggles remain consistent and auditable.
 */
object JacksonMappers {
    fun standard(): JsonMapper = jsonMapper()

    @Deprecated(
        message =
            "Pretty JSON is docs/debug-only. Avoid for normal runtime paths due payload overhead.",
        level = DeprecationLevel.WARNING,
    )
    fun pretty(): JsonMapper =
        jacksonMapperBuilder().enable(SerializationFeature.INDENT_OUTPUT).build()

    fun allowNullForPrimitives(): JsonMapper = jsonMapper {
        disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
    }

    fun lenientInput(): JsonMapper =
        jacksonMapperBuilder()
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .build()
}
