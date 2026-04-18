/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.model.Consumed
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.service.TypedOperation
import dev.streampack.rss.model.RecordRssEntryAccessRequest
import dev.streampack.rss.service.RssAggregatorService
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Records usage for an RSS entry without producing any caller-visible result. */
@Component
class RecordRssEntryAccessOperation(private val rssAggregatorService: RssAggregatorService) :
    TypedOperation<RecordRssEntryAccessRequest>(RecordRssEntryAccessRequest::class) {

    override val operationGroup: String = "rss"

    override fun handle(
        payload: RecordRssEntryAccessRequest,
        message: Message<*>,
    ): OperationOutcome {
        rssAggregatorService.recordAccess(payload.id)
        return Consumed("recorded rss access ${payload.id}")
    }
}
