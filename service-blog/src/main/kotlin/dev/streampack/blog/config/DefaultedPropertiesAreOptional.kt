/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.primaryConstructor
import org.springframework.stereotype.Component

/**
 * A Kotlin property is `required` in the OpenAPI document only when it's non-null **and** has no
 * default. springdoc marks every non-null property required, but one with a default (`channel:
 * CodeChannel = CodeChannel.EMAIL` in `OtpRequest`) may be left out of a request, as the server
 * fills it in, and the document shouldn't tell clients otherwise.
 *
 * Applied after the schema is resolved, so it holds for the live `/v3/api-docs` and the generated
 * `docs/openapi.json` alike. A defaulted property of a response is then optional too, though the
 * server always sends it: less precise, and harmless, as a client that doesn't count on it works
 * either way.
 */
@Component
class DefaultedPropertiesAreOptional : ModelConverter {
    private val defaulted = ConcurrentHashMap<Class<*>, Set<String>>()

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>,
    ): Schema<*>? {
        if (!chain.hasNext()) return null
        val resolved = chain.next().resolve(type, context, chain) ?: return null
        val raw = runCatching { Json.mapper().constructType(type.type).rawClass }.getOrNull()
        val optional = raw?.let { defaulted.computeIfAbsent(it, ::withDefaults) }.orEmpty()
        if (optional.isEmpty()) return resolved
        // An object is defined once and referred to; its definition is the one to change.
        val model =
            resolved.`$ref`?.let { context.definedModels[it.substringAfterLast('/')] } ?: resolved
        model.required?.let { required ->
            model.required = required.filterNot { it in optional }.ifEmpty { null }
        }
        return resolved
    }

    /**
     * The names of [type]'s primary-constructor parameters that have defaults; none if not Kotlin.
     */
    private fun withDefaults(type: Class<*>): Set<String> {
        if (!type.isAnnotationPresent(Metadata::class.java)) return emptySet()
        return runCatching {
            type.kotlin.primaryConstructor
                ?.parameters
                ?.filter { it.isOptional }
                ?.mapNotNull { it.name }
                ?.toSet()
        }
            .getOrNull()
            .orEmpty()
    }
}
