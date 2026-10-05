/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.webconsole

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The admin web console's limits (#115): `streampack.webconsole.*`. Starting values, to be tuned
 * against use.
 */
@ConfigurationProperties(prefix = "streampack.webconsole")
data class WebConsoleProperties(
    /** How often each stream gets a heartbeat, and its credential and authority are rechecked. */
    val heartbeat: Duration = Duration.ofSeconds(15),
    /** The longest a stream stays open before the client must reconnect (and re-authenticate). */
    val maxStreamAge: Duration = Duration.ofHours(1),
    /** Open streams an admin may have at once (windows, tabs). */
    val maxStreamsPerUser: Int = 4,
    /** Stream openings an admin may make a minute, reconnections included. */
    val streamOpensPerMinute: Int = 20,
    /** Events queued for one stream before it's judged too slow and closed. */
    val queueEvents: Int = 100,
    /** Bytes queued for one stream before it's judged too slow and closed. */
    val queueBytes: Int = 1024 * 1024,
    /** The largest single event; larger output is reported as too large, never truncated. */
    val maxEventBytes: Int = 128 * 1024,
    /** Commands an admin may submit a minute, across all their windows and credentials. */
    val commandsPerMinute: Int = 30,
    /** The longest command line, in characters. */
    val maxLine: Int = 4096,
    /** The largest request body, in bytes: room for a full line, escaped. */
    val maxBody: Int = 32 * 1024,
)
