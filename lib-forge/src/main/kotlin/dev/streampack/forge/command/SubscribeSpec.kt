/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.command

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.subscription.SubscriptionFilters

/**
 * The part of a subscribe line after the command, once `on <host>` is split off: the project path,
 * then event selectors and pipeline filters (#267). Shared by every forge's subscribe command.
 */
sealed interface SubscribeSpec {
    /** The project at [path], with the [filters] the line asked for */
    data class Parsed(val path: String, val filters: SubscriptionFilters) : SubscribeSpec

    /** The line named no project */
    data object NoProject : SubscribeSpec

    /** [token] is neither an event selector nor a pipeline filter */
    data class Unknown(val token: String) : SubscribeSpec

    companion object {
        /** Reads `<path> [selector…]` */
        fun parse(spec: String): SubscribeSpec {
            val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return NoProject
            return when (val parsed = SubscriptionFilters.parse(tokens.drop(1))) {
                is SubscriptionFilters.Parsed.Filters -> Parsed(tokens.first(), parsed.filters)
                is SubscriptionFilters.Parsed.Unknown -> Unknown(parsed.token)
            }
        }

        /** The reply to an unknown selector, listing [forge]'s selectors in its own words */
        fun unknownSelector(forge: ForgeKind, token: String): String =
            "Unknown selector '$token'. Selectors: ${SubscriptionFilters.selectorHelp(forge)}"
    }
}
