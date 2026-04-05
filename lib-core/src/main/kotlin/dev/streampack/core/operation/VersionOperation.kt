/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.model.OperationOutcome
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.service.TypedOperation
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.info.BuildProperties
import org.springframework.boot.info.GitProperties
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Reports build identity: commit, branch, and build time. */
@Component
class VersionOperation(
    @Autowired(required = false) private val gitProperties: GitProperties?,
    @Autowired(required = false) private val buildProperties: BuildProperties?,
    @Value("\${spring.application.name:}") private val applicationName: String,
) : TypedOperation<String>(String::class) {

    override val operationGroup: String = "version"

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        return payload.trim().equals("version", ignoreCase = true)
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome {
        return OperationResult.Success(buildVersionString())
    }

    /** Assemble a human-readable version string from available build metadata */
    fun buildVersionString(): String {
        val parts = mutableListOf<String>()

        val name = applicationName.ifBlank { buildProperties?.name ?: "streampack" }
        val version = buildProperties?.version
        parts.add(if (version != null) "$name $version" else name)

        val commit = gitProperties?.shortCommitId
        val branch = gitProperties?.branch
        if (commit != null) {
            parts.add(if (branch != null) "$commit ($branch)" else commit)
        }

        val buildTime = buildProperties?.time ?: gitProperties?.commitTime
        if (buildTime != null) {
            val formatted = BUILD_TIME_FORMAT.format(buildTime)
            parts.add("Built $formatted")
        }

        if (parts.size == 1 && version == null) {
            parts.add("development build")
        }

        return parts.joinToString(" | ")
    }

    companion object {
        private val BUILD_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault())
    }
}
