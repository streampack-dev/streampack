/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.TranslatingOperation
import dev.streampack.karma.model.KarmaQueryRequest
import dev.streampack.karma.service.KarmaService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles addressed karma queries: "karma foo" or a typed KarmaQueryRequest */
@Component
class GetKarmaOperation(private val karmaService: KarmaService) :
    TranslatingOperation<KarmaQueryRequest>(KarmaQueryRequest::class) {

    override val priority: Int = 50
    override val addressed: Boolean = true
    override val operationGroup: String = "karma"

    override fun translate(payload: String, message: Message<*>): KarmaQueryRequest? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("karma ")) return null
        val subject = trimmed.removePrefix("karma ").trim()
        if (subject.isBlank()) return null
        return KarmaQueryRequest(subject)
    }

    override fun handle(payload: KarmaQueryRequest, message: Message<*>): OperationOutcome {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick = message.headers["nick"] as? String ?: provenance?.user?.username
        val selfQuery = senderNick != null && senderNick.equals(payload.subject, ignoreCase = true)

        return if (karmaService.hasKarma(payload.subject)) {
            val karma = karmaService.getKarma(payload.subject)
            val karmaExpression =
                if (karma == 0) {
                    "neutral karma"
                } else {
                    "karma of $karma"
                }
            if (selfQuery) {
                OperationResult.Success("${payload.subject}, you have $karmaExpression.")
            } else {
                OperationResult.Success("${payload.subject} has $karmaExpression.")
            }
        } else {
            if (selfQuery) {
                OperationResult.Success("${payload.subject}, you have no karma data.")
            } else {
                OperationResult.Success("${payload.subject} has no karma data.")
            }
        }
    }
}
