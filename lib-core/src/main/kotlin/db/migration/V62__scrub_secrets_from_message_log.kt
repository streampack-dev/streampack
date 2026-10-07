/* Joseph B. Ottinger (C)2026 */
@file:Suppress("ClassName")

package db.migration

import dev.streampack.core.service.SecretScrubber
import java.sql.Connection
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.slf4j.LoggerFactory

/**
 * Scrubs credentials out of what the message log already holds (#148), with the same patterns that
 * keep new ones out. Direct rows too: they're kept, so they shouldn't keep secrets either.
 *
 * A migration rather than an admin command or a startup task, as the earlier cleanups (V18, V45)
 * were: it runs exactly once on every database, in order, in a transaction, and nobody has to
 * remember to run it. It's written in Kotlin rather than SQL because the generic assignments are
 * only scrubbed when their value looks random, an entropy test SQL can't do, and because then there
 * is one list of patterns, not two to keep in step. Only the built-in patterns apply here; a
 * deployment's extra ones are configuration, which a migration doesn't see.
 */
class V62__scrub_secrets_from_message_log : BaseJavaMigration() {

    override fun migrate(context: Context) {
        val changed = scrub(context.connection)
        LoggerFactory.getLogger(javaClass)
            .info("Scrubbed secrets from {} message log rows", changed)
    }

    companion object {
        private const val BATCH_SIZE = 500

        /** Rewrites every row whose content holds a secret; returns how many were rewritten */
        fun scrub(connection: Connection): Int {
            var changed = 0
            connection.prepareStatement("UPDATE message_log SET content = ? WHERE id = ?").use {
                update ->
                // A cursor, so a large log is read a page at a time rather than all at once
                connection.prepareStatement("SELECT id, content FROM message_log").use { select ->
                    select.fetchSize = BATCH_SIZE
                    select.executeQuery().use { rows ->
                        var pending = 0
                        while (rows.next()) {
                            val content = rows.getString("content") ?: continue
                            val result = SecretScrubber.scrub(content)
                            if (!result.scrubbed) continue
                            update.setString(1, result.text)
                            update.setObject(2, rows.getObject("id"))
                            update.addBatch()
                            changed++
                            if (++pending == BATCH_SIZE) {
                                update.executeBatch()
                                pending = 0
                            }
                        }
                        if (pending > 0) update.executeBatch()
                    }
                }
            }
            return changed
        }
    }
}
