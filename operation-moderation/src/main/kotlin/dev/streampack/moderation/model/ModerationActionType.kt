/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.model

/** What an admin did. Every one is recorded with who did it and when. */
enum class ModerationActionType {
    /** Lines taken out of public view, kept. */
    HIDE,
    /** Hidden lines put back. */
    UNHIDE,
    /** Lines deleted for good. */
    PURGE,
    /** A report closed as not abuse. */
    DISMISS,
}
