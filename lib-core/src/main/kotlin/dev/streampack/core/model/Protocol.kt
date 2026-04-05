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
}
