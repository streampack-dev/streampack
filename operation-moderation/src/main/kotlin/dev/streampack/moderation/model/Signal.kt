/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.model

/** What raised a person's score (#150). Reports keep how often each was seen. */
enum class Signal {
    /** Swearing aimed at no one in particular. */
    PROFANITY,
    /** An insult aimed at no one in particular. */
    INSULT,
    /** Swearing or an insult aimed at someone: "you …", or alongside another person's name. */
    AIMED_HOSTILITY,
    SLUR,
    THREAT,
    /** An email address, phone number or street address posted. */
    PERSONAL_DETAILS,
    /** A link to a blocked host. */
    BLOCKED_LINK,
    /** The same line again. */
    REPETITION,
    /** Many lines in a few seconds. */
    FLOOD,
}
