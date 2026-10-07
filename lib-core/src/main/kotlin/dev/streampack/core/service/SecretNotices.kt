/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Tells someone, privately, that a secret they posted was kept out of the log (#148).
 *
 * The message was already seen by everyone in the channel: scrubbing protects the record, not the
 * moment, so the note says to revoke it. It goes to the sender's direct conversation through their
 * protocol's [SenderNotifier], never to the channel, where it would only draw attention to what was
 * pasted; and at most once per person per [SecretScrubbingProperties.noticeInterval], so a paste of
 * a dozen lines is one note, not twelve.
 */
@Component
class SecretNotices(
    notifiers: List<SenderNotifier>,
    private val channelNames: List<ChannelNameProvider>,
    properties: SecretScrubbingProperties,
    private val clock: Clock,
    private val executor: Executor,
) {
    /* Delivery is a network call (Slack and Mattermost post synchronously), so it's kept off the
    thread that's logging the message */
    @Autowired
    constructor(
        notifiers: List<SenderNotifier>,
        channelNames: List<ChannelNameProvider>,
        properties: SecretScrubbingProperties,
    ) : this(
        notifiers,
        channelNames,
        properties,
        Clock.systemUTC(),
        Executor { Thread.startVirtualThread(it) },
    )

    private val logger = LoggerFactory.getLogger(SecretNotices::class.java)
    private val notifiers = notifiers.associateBy { it.protocol }
    private val interval: Duration = properties.noticeInterval
    private val lastNotice = ConcurrentHashMap<String, Instant>()

    /**
     * Tells [senderId] that [kinds] were removed from what they said in [provenance]'s channel.
     * Returns true if a note is on its way; false for a protocol with no notifier, or a person told
     * within the interval.
     */
    fun notify(provenance: Provenance, senderId: String, kinds: List<SecretPattern>): Boolean {
        if (kinds.isEmpty()) return false
        val notifier = notifiers[provenance.protocol] ?: return false
        val key = "${provenance.protocol}/${provenance.serviceId.orEmpty()}/$senderId"
        val now = clock.instant()
        var due = false
        lastNotice.compute(key) { _, previous ->
            if (previous == null || Duration.between(previous, now) >= interval) {
                due = true
                now
            } else previous
        }
        if (!due) return false
        executor.execute {
            runCatching {
                val text = message(kinds, channelLabel(provenance))
                if (!notifier.notifySender(provenance, senderId, text)) {
                    logger.warn("Could not tell {} about a scrubbed secret", key)
                }
            }
                .onFailure {
                    logger.warn("Could not tell {} about a scrubbed secret: {}", key, it.message)
                }
        }
        return true
    }

    /** The channel as people know it: `#java`, not a Slack or Mattermost channel id */
    internal fun channelLabel(provenance: Provenance): String {
        val name =
            when (provenance.protocol) {
                Protocol.IRC -> provenance.replyTo
                else ->
                    channelNames
                        .firstOrNull { it.protocol == provenance.protocol }
                        ?.name(provenance) ?: provenance.metadata["channelName"] as? String
            }
        return when {
            name.isNullOrBlank() -> "the channel"
            name.first() in "#&+!" -> name
            else -> "#$name"
        }
    }

    companion object {
        fun message(kinds: List<SecretPattern>, channel: String): String {
            val what = kinds.map { it.description }.distinct()
            val described =
                if (what.size == 1) what.single()
                else what.dropLast(1).joinToString(", ") + " and " + what.last()
            return "I've removed what looked like $described from the log of $channel. " +
                "If it was real, revoke it now: it was visible in the channel."
        }
    }
}
