/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * What a logged line is (#174): something someone said ([MESSAGE], `/me` actions included), or a
 * channel event an adapter logged: someone joining, leaving, quitting, changing nick, or setting
 * the topic.
 */
enum class MessageKind {
    MESSAGE,
    JOIN,
    PART,
    QUIT,
    NICK,
    TOPIC;

    /** Whether this is an event rather than something said */
    val isEvent: Boolean
        get() = this != MESSAGE

    companion object {
        /** The event kinds: everything but [MESSAGE] */
        val EVENTS: Set<MessageKind> = entries.filter { it.isEvent }.toSet()
    }
}
