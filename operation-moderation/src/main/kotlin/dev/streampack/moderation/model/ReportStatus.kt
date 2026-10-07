/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.model

/** Where a report stands. Only an admin moves it out of [OPEN]. */
enum class ReportStatus {
    /** Waiting for an admin. */
    OPEN,
    /** An admin judged it not abuse. */
    DISMISSED,
    /** An admin hid or purged lines from it. */
    ACTIONED,
}
