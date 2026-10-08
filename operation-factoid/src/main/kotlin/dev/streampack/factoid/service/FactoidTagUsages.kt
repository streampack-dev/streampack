/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.taxonomy.TagNames
import dev.streampack.taxonomy.TagUsages
import java.time.Instant
import org.springframework.stereotype.Component

/**
 * Factoids' tags for the vocabulary (#140). Their tags are comma-separated lists, not rows, so the
 * vocabulary reads what's in use here, and an alias or split rewrites each list that carries the
 * old tag: it's replaced where it stood by the new tags, without repeats; the rest of the list is
 * kept as stored. An admin's vocabulary change applies to locked factoids too.
 */
@Component
class FactoidTagUsages(private val attributes: FactoidAttributeRepository) : TagUsages {
    override val kind: String = "factoids"

    override fun namesInUse(): Set<String> = attributes.findTagCounts().map { it.name }.toSet()

    override fun retag(from: String, to: List<String>, actor: String): Int {
        var changed = 0
        for (attribute in attributes.findAllOfTypeWithFactoid(FactoidAttributeType.TAGS)) {
            val stored = attribute.attributeValue.orEmpty().split(',').map { it.trim() }
            if (stored.none { TagNames.stored(it) == from }) continue
            val rewritten =
                stored
                    .flatMap { if (TagNames.stored(it) == from) to else listOf(it) }
                    .filter { it.isNotEmpty() }
                    .distinctBy { TagNames.stored(it) }
            attributes.save(
                attribute.copy(
                    attributeValue = rewritten.joinToString(","),
                    updatedBy = actor,
                    updatedAt = Instant.now(),
                )
            )
            changed++
        }
        return changed
    }
}
