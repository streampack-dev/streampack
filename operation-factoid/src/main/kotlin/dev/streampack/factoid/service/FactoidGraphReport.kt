/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.factoid.entity.FactoidAttribute
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.model.FactoidCatalog
import dev.streampack.factoid.model.FactoidCatalogEntry
import dev.streampack.factoid.model.FactoidMatcher
import dev.streampack.factoid.operation.FactoidLine
import dev.streampack.factoid.operation.line
import dev.streampack.factoid.operation.summarize
import dev.streampack.factoid.repository.FactoidAttributeRepository
import dev.streampack.factoid.repository.FactoidRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Where the knowledge base's graph could be better (#133): factoids as nodes, see-also as edges,
 * kept in shape by hand. Advisory: it reports, never edits.
 *
 * - [parentsNotLinked]: a factoid whose text names one that lists it in see-also, without linking
 *   back (`karaf` says OSGi, `osgi` lists karaf, karaf links nothing): a hub written top-down.
 * - [mentionsNotLinked]: the rest of the factoids a factoid's text names without linking them; a
 *   longer and looser list, where general terms (`xml`, `ide`) turn up often.
 * - [seeAlsoToNothing]: see-also naming factoids that don't exist.
 * - [linesThatDontFit]: factoids whose one-line answer leaves parts out (#131), or is cut off even
 *   so, its text alone being past the limit.
 */
data class FactoidGraphReport(
    val parentsNotLinked: List<UnlinkedMentions>,
    val mentionsNotLinked: List<UnlinkedMentions>,
    val seeAlsoToNothing: List<SeeAlsoToNothing>,
    val linesThatDontFit: List<LineThatDoesntFit>,
) {
    /** Factoids [selector]'s text names but doesn't link. */
    data class UnlinkedMentions(val selector: String, val mentions: List<String>)

    data class SeeAlsoToNothing(val selector: String, val missing: List<String>)

    /**
     * A factoid's line: [length] said whole, [saidLength] as it's said, the parts [dropped] to fit,
     * and whether it's [cutOff] even so.
     */
    data class LineThatDoesntFit(
        val selector: String,
        val length: Int,
        val saidLength: Int,
        val dropped: List<String>,
        val cutOff: Boolean,
    )
}

@Service
class FactoidGraphReportService(
    private val factoidRepository: FactoidRepository,
    private val attributeRepository: FactoidAttributeRepository,
    @Value("\${streampack.factoid.line-length:300}") private val target: Int = 300,
    @Value("\${streampack.blog.factoid-mentions.stopwords:#{null}}") stopwords: List<String>?,
) {
    private val stopwords = stopwords ?: FactoidMatcher.DEFAULT_STOPWORDS

    fun report(): FactoidGraphReport {
        val bySelector: Map<String, List<FactoidAttribute>> =
            attributeRepository.findAllWithFactoid().groupBy { it.factoid.selector }
        val selectors = factoidRepository.findAll().map { it.selector }.sortedBy { it.lowercase() }
        val known = selectors.map { it.lowercase() }.toSet()
        fun value(selector: String, type: FactoidAttributeType) =
            bySelector[selector].orEmpty().firstOrNull { it.attributeType == type }?.attributeValue
        fun seeAlso(selector: String): List<String> =
            value(selector, FactoidAttributeType.SEEALSO)
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val linksOf = selectors.associateWith { s -> seeAlso(s).map { it.lowercase() }.toSet() }
        val catalog =
            FactoidCatalog(
                selectors.map { FactoidCatalogEntry(it, value(it, FactoidAttributeType.TEXT)) }
            )
        val matcher = FactoidMatcher(catalog, stopwords)

        val parents = mutableListOf<FactoidGraphReport.UnlinkedMentions>()
        val others = mutableListOf<FactoidGraphReport.UnlinkedMentions>()
        for (selector in selectors) {
            val text =
                value(selector, FactoidAttributeType.TEXT)?.removePrefix("<reply>") ?: continue
            val links = linksOf.getValue(selector)
            val unlinked =
                matcher
                    .find(text)
                    .map { it.selector }
                    .distinct()
                    .filter { !it.equals(selector, ignoreCase = true) && it.lowercase() !in links }
            val (up, rest) =
                unlinked.partition { target ->
                    selector.lowercase() in linksOf[catalog.find(target)?.selector].orEmpty()
                }
            if (up.isNotEmpty()) parents += FactoidGraphReport.UnlinkedMentions(selector, up)
            if (rest.isNotEmpty()) others += FactoidGraphReport.UnlinkedMentions(selector, rest)
        }

        val dangling = selectors.mapNotNull { selector ->
            seeAlso(selector)
                .filter { it.lowercase() !in known }
                .takeIf { it.isNotEmpty() }
                ?.let { FactoidGraphReport.SeeAlsoToNothing(selector, it) }
        }

        val lines = selectors.mapNotNull { selector ->
            val attributes = bySelector[selector].orEmpty()
            if (attributes.none { it.attributeType == FactoidAttributeType.TEXT }) {
                return@mapNotNull null
            }
            val whole = runCatching { attributes.summarize(selector, "") }.getOrNull()
            val said = runCatching { attributes.line(selector, target) }.getOrNull()
            if (whole == null || said == null) return@mapNotNull null
            val cutOff = said.length > FactoidLine.LIMIT
            if (said.dropped.isEmpty() && !cutOff) return@mapNotNull null
            FactoidGraphReport.LineThatDoesntFit(
                selector = selector,
                length = whole.length,
                saidLength = said.length,
                dropped = said.dropped.map { it.name.lowercase() },
                cutOff = cutOff,
            )
        }

        return FactoidGraphReport(parents, others, dangling, lines)
    }
}
