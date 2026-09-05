/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import dev.streampack.core.integration.TickListener
import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.format.ForgeEventFormatter
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.secret.ForgeTokenResolver
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.forge.store.ForgeStore
import dev.streampack.polling.service.EgressNotifier
import jakarta.annotation.PostConstruct
import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.annotation.Transactional

/**
 * Tick-driven polling over [ForgeStore]: detects new issues, change requests, and releases above
 * the stored cursors and notifies subscribers through the egress channel.
 */
@Transactional
abstract class AbstractForgePollingService<P : ForgeProject, S : ForgeSubscription>(
    protected val kind: ForgeKind,
    protected val store: ForgeStore<P, S>,
    protected val client: ForgeClient,
    private val egressNotifier: EgressNotifier,
    private val pollInterval: Duration,
    private val secretLookup: SecretLookup,
) : TickListener {
    private val logger = LoggerFactory.getLogger(javaClass)
    private lateinit var lastPollTime: Instant

    /** Delay first poll by 30 seconds so protocol adapters can finish connecting */
    @PostConstruct
    fun initLastPollTime() {
        lastPollTime = Instant.now().minus(pollInterval).plusSeconds(30)
    }

    override fun onTick(now: Instant) {
        if (Duration.between(lastPollTime, now) >= pollInterval) {
            lastPollTime = now
            pollAll()
        }
    }

    open fun pollAll() {
        val projects = store.findActiveProjects(DeliveryMode.POLLING)
        logger.debug("Polling {} active {} projects", projects.size, kind.displayName)
        for (project in projects) {
            try {
                pollProject(projectId(project))
            } catch (e: Exception) {
                logger.warn(
                    "Failed to poll {} project {}: {}",
                    kind.displayName,
                    project.displayName,
                    e.message,
                )
            }
        }
    }

    /** The store's identifier for [project], as accepted by [ForgeStore.findProjectById]. */
    protected abstract fun projectId(project: P): String

    /** Poll a single project by store id, detecting new issues, change requests, and releases */
    open fun pollProject(projectId: String) {
        val project = store.findProjectById(projectId) ?: return
        val path = project.displayName
        val token = ForgeTokenResolver.resolve(project.token, secretLookup)
        if (project.token != null && token == null) {
            logger.warn(
                "Token for {} references environment variable {} which is not set; polling unauthenticated",
                path,
                project.token?.envKeyOrNull(),
            )
        }
        logger.info(
            "Polling project {} (since issue {}, {} {})",
            path,
            project.highestIssueNumber,
            kind.changeRequestNoun,
            project.highestChangeRequestNumber,
        )

        val newIssues = client.fetchIssuesSince(path, token, project.highestIssueNumber)
        val newChangeRequests =
            client.fetchChangeRequestsSince(path, token, project.highestChangeRequestNumber)
        val allReleases = client.fetchReleases(path, token)

        val knownTags =
            if (allReleases.isNotEmpty()) {
                store.knownReleaseTags(project, allReleases.map { it.tag })
            } else {
                emptySet()
            }
        val newReleases = allReleases.filter { it.tag !in knownTags }

        val highestIssue =
            maxOf(project.highestIssueNumber, newIssues.maxOfOrNull { it.number } ?: 0)
        val highestChangeRequest =
            maxOf(
                project.highestChangeRequestNumber,
                newChangeRequests.maxOfOrNull { it.number } ?: 0,
            )
        val updated =
            store.updateCursors(project, highestIssue, highestChangeRequest, Instant.now())
        newReleases.forEach { store.saveRelease(updated, it) }

        if (newIssues.isEmpty() && newChangeRequests.isEmpty() && newReleases.isEmpty()) {
            return
        }

        logger.info(
            "Found {} new issues, {} new {}s, {} new releases for {}",
            newIssues.size,
            newChangeRequests.size,
            kind.changeRequestNoun,
            newReleases.size,
            path,
        )

        val subscriptions = store.findActiveSubscriptions(updated)
        if (subscriptions.isEmpty()) return

        val events =
            newIssues.map { ForgeEvent.IssueOpened(it) } +
                newChangeRequests.map { ForgeEvent.ChangeRequestOpened(it) } +
                newReleases.map { ForgeEvent.ReleasePublished(it) }
        for (subscription in subscriptions) {
            for (event in events) {
                egressNotifier.send(
                    ForgeEventFormatter.format(kind, path, event),
                    subscription.destinationUri,
                )
            }
        }
    }
}
