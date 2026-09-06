/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.service

import dev.streampack.forge.ForgeKind
import dev.streampack.forge.client.ForgeClient
import dev.streampack.forge.format.ForgeEventFormatter
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.forge.model.ForgeInstance
import dev.streampack.forge.model.ForgeProject
import dev.streampack.forge.model.ForgeSubscription
import dev.streampack.forge.model.PipelineOutcome
import dev.streampack.forge.pipeline.PipelineSettlementGate
import dev.streampack.forge.secret.ForgeTokenResolver
import dev.streampack.forge.secret.SecretLookup
import dev.streampack.forge.store.ForgeStore
import dev.streampack.forge.subscription.PipelineTarget
import dev.streampack.forge.subscription.SubscriptionEvents
import dev.streampack.polling.schedule.DueBatchPollingService
import dev.streampack.polling.schedule.PollResult
import dev.streampack.polling.schedule.PollSchedule
import dev.streampack.polling.service.EgressNotifier
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Due-batch polling over [ForgeStore] (issue #69): each tick takes the oldest-due polling-mode
 * projects, detects new issues, change requests, releases, and settled pipelines above the stored
 * cursors, notifies subscribers through the egress channel, and schedules the next poll. A project
 * whose API calls fail is backed off rather than retried every tick.
 *
 * Each project is polled in its own transaction, so one failure rolls back only that project.
 */
abstract class AbstractForgePollingService<
    I : ForgeInstance,
    P : ForgeProject,
    S : ForgeSubscription,
>(
    protected val kind: ForgeKind,
    protected val store: ForgeStore<I, P, S>,
    protected val client: ForgeClient,
    private val egressNotifier: EgressNotifier,
    private val schedule: ForgePollingSchedule,
    private val secretLookup: SecretLookup,
    transactionManager: PlatformTransactionManager,
) : DueBatchPollingService<P>(schedule.schedulerInterval, schedule.batchSize) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val transactions = TransactionTemplate(transactionManager)
    private val settlementGate =
        PipelineSettlementGate<P>(
            status = { project, id -> store.pipelineStatus(project, id) },
            record = { project, id, outcome -> store.recordPipeline(project, id, outcome) },
        )

    override fun findDue(now: Instant, limit: Int): List<P> = store.findDueProjects(now, limit)

    override fun poll(source: P): PollResult<P> {
        transactions.execute { pollProject(projectId(source)) }
        val refreshed = store.findProjectById(projectId(source)) ?: source
        return PollResult.Success(refreshed)
    }

    override fun scheduleAfterSuccess(source: P, now: Instant) {
        store.schedulePoll(source, PollSchedule.afterSuccess(now, schedule.pollInterval), 0)
    }

    override fun scheduleAfterFailure(source: P, now: Instant, reason: String?) {
        val failures = source.pollFailures + 1
        store.schedulePoll(
            source,
            PollSchedule.afterFailure(now, schedule.pollInterval, failures, schedule.maxBackoff),
            failures,
        )
    }

    override fun describe(source: P): String = "${kind.displayName} project ${source.displayName}"

    /** The store's identifier for [project], as accepted by [ForgeStore.findProjectById]. */
    protected abstract fun projectId(project: P): String

    /** Poll a single project by store id, detecting new issues, change requests, and releases */
    open fun pollProject(projectId: String) {
        val project = store.findProjectById(projectId) ?: return
        val path = project.displayName
        val tokenRef = project.effectiveToken
        val token = ForgeTokenResolver.resolve(tokenRef, secretLookup)
        if (tokenRef != null && token == null) {
            logger.warn(
                "Token for {} references environment variable {} which is not set; polling unauthenticated",
                path,
                tokenRef.envKeyOrNull(),
            )
        }
        val instance = project.instance
        logger.info(
            "Polling project {} (since issue {}, {} {})",
            path,
            project.highestIssueNumber,
            kind.changeRequestNoun,
            project.highestChangeRequestNumber,
        )

        val newIssues =
            client.fetchIssuesSince(instance, project.path, token, project.highestIssueNumber)
        val newChangeRequests =
            client.fetchChangeRequestsSince(
                instance,
                project.path,
                token,
                project.highestChangeRequestNumber,
            )
        val allReleases = client.fetchReleases(instance, project.path, token)

        val knownTags =
            if (allReleases.isNotEmpty()) {
                store.knownReleaseTags(project, allReleases.map { it.tag })
            } else {
                emptySet()
            }
        val newReleases = allReleases.filter { it.tag !in knownTags }

        val subscriptions = store.findActiveSubscriptions(project)
        val pipelineEvents = pollPipelines(project, token, subscriptions)

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

        val events =
            newIssues.map { ForgeEvent.IssueOpened(it) } +
                newChangeRequests.map { ForgeEvent.ChangeRequestOpened(it) } +
                newReleases.map { ForgeEvent.ReleasePublished(it) } +
                pipelineEvents
        if (events.isEmpty()) return

        logger.info(
            "Found {} new issues, {} new {}s, {} new releases, {} settled pipelines for {}",
            newIssues.size,
            newChangeRequests.size,
            kind.changeRequestNoun,
            newReleases.size,
            pipelineEvents.size,
            path,
        )
        if (subscriptions.isEmpty()) return

        for (subscription in subscriptions) {
            for (event in events) {
                if (!SubscriptionEvents.wants(subscription.events, event)) continue
                egressNotifier.send(
                    ForgeEventFormatter.format(kind, path, event),
                    subscription.destinationUri,
                )
            }
        }
    }

    /**
     * Settled pipelines worth reporting since the last poll, or nothing when no subscription asked
     * for pipelines, so projects nobody opted in for cost no extra API calls. Runs the settlement
     * gate on every sighting (including in-progress ones) so retries are noticed.
     */
    private fun pollPipelines(
        project: P,
        token: String?,
        subscriptions: List<S>,
    ): List<ForgeEvent> {
        val filters = subscriptions.flatMap { SubscriptionEvents.pipelineFilters(it.events) }
        if (filters.isEmpty()) return emptyList()
        val since = project.lastPolledAt ?: Instant.now()
        val instance = project.instance
        val pipelines = client.fetchPipelinesSince(instance, project.path, token, since)
        val wantsDefaultBranch = filters.any { it.target == PipelineTarget.DefaultBranch }
        val defaultBranch =
            if (wantsDefaultBranch) client.fetchDefaultBranch(instance, project.path, token)
            else null

        val events = mutableListOf<ForgeEvent>()
        for (pipeline in pipelines.sortedBy { it.updatedAt }) {
            /* Untouched since the last poll: nothing new, and nothing to record */
            if (!pipeline.updatedAt.isAfter(since)) continue
            if (!settlementGate.shouldNotify(project, pipeline)) continue
            val candidate =
                ForgeEvent.PipelineSettled(pipeline, emptyList(), pipeline.ref == defaultBranch)
            if (filters.none { it.matches(candidate) }) continue
            val failedJobs =
                if (pipeline.outcome == PipelineOutcome.NEEDS_ATTENTION) {
                    client.fetchFailedJobs(instance, project.path, token, pipeline.id)
                } else {
                    emptyList()
                }
            events += candidate.copy(failedJobs = failedJobs)
        }
        return events
    }
}
