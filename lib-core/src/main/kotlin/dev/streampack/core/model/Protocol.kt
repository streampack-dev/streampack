/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

enum class Protocol(val traits: Set<ProtocolTrait> = emptySet()) {
    CONSOLE(setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL)),
    DISCORD(
        setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL, ProtocolTrait.ADDRESSABLE)
    ),
    SLACK(setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL, ProtocolTrait.ADDRESSABLE)),
    IRC(setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL, ProtocolTrait.ADDRESSABLE)),
    HTTP(emptySet()),
    MAILTO(setOf(ProtocolTrait.TEXT_BASED)),
    MATTERMOST(
        setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL, ProtocolTrait.ADDRESSABLE)
    ),
    RSS(emptySet()),
    /**
     * The admin web console (#115): commands typed in a browser, answered over a stream. Its own
     * protocol, so the stdin console's subscriber never prints a browser's output, and so logs,
     * bridges and the public log browser can tell it apart.
     */
    WEBCONSOLE(setOf(ProtocolTrait.TEXT_BASED, ProtocolTrait.CONVERSATIONAL)),
}
