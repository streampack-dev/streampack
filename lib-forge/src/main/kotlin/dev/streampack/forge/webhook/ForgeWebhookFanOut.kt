/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.webhook

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.format.ForgeEventFormatter
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.store.ForgeStore
import dev.streampack.polling.service.EgressNotifier
import org.slf4j.LoggerFactory

/** Formats a webhook-derived event and delivers it to every active subscription of the project. */
open class ForgeWebhookFanOut<I : ForgeInstance, P : ForgeProject, S : ForgeSubscription>(
    private val kind: ForgeKind,
    private val store: ForgeStore<I, P, S>,
    private val notifier: EgressNotifier,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    open fun deliver(project: P, event: ForgeEvent) {
        val subscriptions = store.findActiveSubscriptions(project)
        if (subscriptions.isEmpty()) {
            logger.info(
                "No active {} subscriptions for {}; webhook notification not delivered",
                kind.displayName,
                project.displayName,
            )
            return
        }
        logger.info(
            "Delivering {} webhook notification for {} to {} active subscription(s)",
            kind.displayName,
            project.displayName,
            subscriptions.size,
        )
        val message = ForgeEventFormatter.format(kind, project.displayName, event)
        subscriptions.forEach { notifier.send(message, it.destinationUri) }
    }
}
