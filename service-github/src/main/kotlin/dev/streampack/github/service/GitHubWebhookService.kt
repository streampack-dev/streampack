/* Joseph B. Ottinger (C)2026 */
package dev.streampack.github.service

import dev.streampack.github.entity.GitHubRepo
import dev.streampack.github.model.GitHubIssueEvent
import dev.streampack.github.model.GitHubPullRequestEvent
import dev.streampack.github.model.GitHubReleaseEvent
import dev.streampack.github.repository.GitHubSubscriptionRepository
import dev.streampack.polling.service.EgressNotifier
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Formats webhook events and emits notifications identical to polling output */
@Service
class GitHubWebhookService(
    private val subscriptionRepository: GitHubSubscriptionRepository,
    private val notifier: EgressNotifier,
) {

    private val logger = LoggerFactory.getLogger(GitHubWebhookService::class.java)

    fun handleIssue(repo: GitHubRepo, event: GitHubIssueEvent) {
        if (event.action != "opened") {
            logger.debug(
                "Ignoring GitHub issue webhook for {} with unsupported action {}",
                repo.fullName(),
                event.action,
            )
            return
        }
        val issue = event.issue
        val message =
            "[${repo.fullName()}] New issue #${issue.number}: ${issue.title} - ${issue.htmlUrl}"
        fanOut(repo, message)
    }

    fun handlePullRequest(repo: GitHubRepo, event: GitHubPullRequestEvent) {
        if (event.action != "opened") {
            logger.debug(
                "Ignoring GitHub pull request webhook for {} with unsupported action {}",
                repo.fullName(),
                event.action,
            )
            return
        }
        val pr = event.pullRequest
        val message = "[${repo.fullName()}] New PR #${pr.number}: ${pr.title} - ${pr.htmlUrl}"
        fanOut(repo, message)
    }

    fun handleRelease(repo: GitHubRepo, event: GitHubReleaseEvent) {
        if (event.action != "published") {
            logger.debug(
                "Ignoring GitHub release webhook for {} with unsupported action {}",
                repo.fullName(),
                event.action,
            )
            return
        }
        val release = event.release
        val message = "[${repo.fullName()}] New release ${release.tagName} - ${release.htmlUrl}"
        fanOut(repo, message)
    }

    fun handlePing(repo: GitHubRepo, zen: String?) {
        val suffix = if (zen.isNullOrBlank()) "" else " ($zen)"
        val message = "[${repo.fullName()}] Webhook ping received - setup verified.$suffix"
        fanOut(repo, message)
    }

    private fun fanOut(repo: GitHubRepo, message: String) {
        val subscriptions = subscriptionRepository.findByRepoAndActiveTrue(repo)
        if (subscriptions.isEmpty()) {
            logger.info(
                "No active GitHub subscriptions for {}; webhook notification not delivered",
                repo.fullName(),
            )
            return
        }
        logger.info(
            "Delivering GitHub webhook notification for {} to {} active subscription(s)",
            repo.fullName(),
            subscriptions.size,
        )
        subscriptions.forEach { subscription ->
            notifier.send(message, subscription.destinationUri)
        }
    }
}
