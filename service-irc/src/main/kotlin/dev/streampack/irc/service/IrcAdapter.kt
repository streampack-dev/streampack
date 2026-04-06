/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.LoggingRequest
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.core.service.UserResolutionService
import dev.streampack.irc.repository.IrcChannelRepository
import dev.streampack.irc.repository.IrcNetworkRepository
import net.engio.mbassy.listener.Handler
import org.kitteh.irc.client.library.Client
import org.kitteh.irc.client.library.element.mode.ModeStatus
import org.kitteh.irc.client.library.event.channel.ChannelCtcpEvent
import org.kitteh.irc.client.library.event.channel.ChannelJoinEvent
import org.kitteh.irc.client.library.event.channel.ChannelMessageEvent
import org.kitteh.irc.client.library.event.channel.ChannelModeEvent
import org.kitteh.irc.client.library.event.channel.ChannelPartEvent
import org.kitteh.irc.client.library.event.channel.ChannelTopicEvent
import org.kitteh.irc.client.library.event.channel.RequestedChannelJoinCompleteEvent
import org.kitteh.irc.client.library.event.client.ClientNegotiationCompleteEvent
import org.kitteh.irc.client.library.event.user.PrivateCtcpQueryEvent
import org.kitteh.irc.client.library.event.user.PrivateMessageEvent
import org.kitteh.irc.client.library.event.user.UserNickChangeEvent
import org.kitteh.irc.client.library.event.user.UserQuitEvent
import org.slf4j.LoggerFactory
import org.springframework.messaging.support.MessageBuilder

/**
 * Manages a single Kitteh IRC client for one network. Not a Spring bean -- created dynamically by
 * IrcConnectionManager.
 */
class IrcAdapter(
    val networkName: String,
    private val eventGateway: EventGateway,
    private val userResolutionService: UserResolutionService,
    private val channelControlService: ChannelControlService,
    private val stateService: ProvenanceStateService,
    private val networkRepository: IrcNetworkRepository,
    private val channelRepository: IrcChannelRepository,
    private val client: Client,
    override val signalCharacter: String,
    private val identity: String,
) : ProtocolAdapter {
    override val protocol: Protocol = Protocol.IRC
    override val serviceName: String = networkName
    private val logger = LoggerFactory.getLogger(IrcAdapter::class.java)

    init {
        client.eventManager.registerEventListener(this)
    }

    fun connect() {
        client.connect()
    }

    fun disconnect() {
        client.shutdown("Disconnecting")
    }

    fun joinChannel(channelName: String) {
        client.addChannel(channelName)
    }

    fun leaveChannel(channelName: String) {
        client.removeChannel(channelName)
    }

    /** Sends a message to the given target (channel or nick) via the IRC client */
    fun sendMessage(target: String, text: String) {
        client.sendMessage(target, text)
    }

    override fun wouldTriggerIngress(text: String): Boolean {
        if (signalCharacter.isNotEmpty() && text.startsWith(signalCharacter)) return true
        val nick = client.nick.lowercase()
        val lowerText = text.lowercase()
        for (separator in listOf(": ", ", ")) {
            if (lowerText.startsWith("$nick$separator")) return true
        }
        return false
    }

    override fun sendReply(provenance: Provenance, text: String) {
        splitForIrc(text).forEach { chunk -> sendMessage(provenance.replyTo, chunk) }
    }

    /** Returns channels the client has joined */
    fun getJoinedChannels(): Set<String> = client.channels.map { it.name }.toSet()

    @Handler
    fun onConnected(event: ClientNegotiationCompleteEvent) {
        logger.info("Connected to network '{}'", networkName)
        val network = networkRepository.findByNameAndDeletedFalse(networkName) ?: return
        val channels = channelRepository.findByNetworkAndDeletedFalse(network)
        for (channel in channels) {
            val options = channelControlService.getOptions(channel.provenanceUri())
            if (options?.autojoin == true) {
                logger.info("Auto-joining {} on {}", channel.name, networkName)
                client.addChannel(channel.name)
            }
        }
    }

    /** Auto-deops the bot when it receives +o, unless allow-ops is enabled for the channel */
    @Handler
    fun onChannelMode(event: ChannelModeEvent) {
        val opChanges = event.statusList.getByMode('o')
        for (change in opChanges) {
            if (
                change.action == ModeStatus.Action.ADD &&
                    change.parameter.orElse(null) == client.nick
            ) {
                val channelName = event.channel.name
                val provenanceUri =
                    Provenance(
                            protocol = Protocol.IRC,
                            serviceId = networkName,
                            replyTo = channelName,
                        )
                        .encode()
                val state = stateService.getState(provenanceUri, ALLOW_OPS_KEY)
                if (state == null) {
                    stateService.setState(provenanceUri, ALLOW_OPS_KEY, mapOf("enabled" to false))
                }
                val allowOps = state?.get("enabled") as? Boolean ?: false
                if (!allowOps) {
                    client.sendRawLine("MODE $channelName -o ${client.nick}")
                    logger.info("Auto-deopping in {} on {}", channelName, networkName)
                }
            }
        }
    }

    @Handler
    fun onChannelMessage(event: ChannelMessageEvent) {
        if (event.actor.nick == client.nick) return
        Thread.startVirtualThread {
            try {
                val channelName = event.channel.name
                val nick = event.actor.nick
                val host = event.actor.host
                val ident = event.actor.userString.removePrefix("~")
                val user = userResolutionService.resolve(Protocol.IRC, networkName, "$ident@$host")
                val provenance =
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = networkName,
                        replyTo = channelName,
                        user = user,
                        metadata = mapOf(Provenance.BOT_NICK to client.nick),
                    )

                val strippedText = extractAddressedText(event.message)
                val isAddressed = strippedText != null
                dispatch(strippedText ?: event.message, provenance, isAddressed, nick, host, ident)
            } catch (e: Exception) {
                logger.error("Error processing channel message on {}: {}", networkName, e.message)
            }
        }
    }

    /** Sends payload through the EventGateway as fire-and-forget; results arrive via egress */
    private fun dispatch(
        payload: String,
        provenance: Provenance,
        addressed: Boolean,
        nick: String? = null,
        host: String? = null,
        ident: String? = null,
    ) {
        val builder =
            MessageBuilder.withPayload(payload)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader(Provenance.ADDRESSED, addressed)
        if (nick != null) builder.setHeader("nick", nick)
        if (host != null) builder.setHeader("host", host)
        if (ident != null) builder.setHeader("ident", ident)
        eventGateway.send(builder.build())
    }

    /**
     * Detects whether a message is explicitly addressed to the bot. Returns the stripped payload
     * (no signal char or nick prefix) if addressed, or null if the message is not addressed.
     */
    private fun extractAddressedText(raw: String): String? {
        return stripAddressedText(raw, signalCharacter, client.nick)
    }

    @Handler
    fun onPrivateCtcpQuery(event: PrivateCtcpQueryEvent) {
        if (event.message.equals("VERSION", ignoreCase = true)) {
            event.setReply("VERSION $identity")
        }
    }

    @Handler
    fun onPrivateMessage(event: PrivateMessageEvent) {
        Thread.startVirtualThread {
            try {
                val nick = event.actor.nick
                val host = event.actor.host
                val ident = event.actor.userString.removePrefix("~")
                val user = userResolutionService.resolve(Protocol.IRC, networkName, "$ident@$host")
                val provenance =
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = networkName,
                        replyTo = nick,
                        user = user,
                        metadata = mapOf(Provenance.BOT_NICK to client.nick),
                    )
                val normalized = extractAddressedText(event.message) ?: event.message
                dispatch(normalized, provenance, addressed = true, nick, host, ident)
            } catch (e: Exception) {
                logger.error("Error processing private message on {}: {}", networkName, e.message)
            }
        }
    }

    @Handler
    fun onChannelAction(event: ChannelCtcpEvent) {
        if (!event.message.startsWith("ACTION ")) return
        val action = event.message.removePrefix("ACTION ").removeSuffix("\u0001")
        val nick = event.actor.nick
        if (nick == client.nick) return
        Thread.startVirtualThread {
            try {
                val channelName = event.channel.name
                val host = event.actor.host
                val ident = event.actor.userString.removePrefix("~")
                val user = userResolutionService.resolve(Protocol.IRC, networkName, "$ident@$host")
                val provenance =
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = networkName,
                        replyTo = channelName,
                        user = user,
                        metadata = mapOf(Provenance.BOT_NICK to client.nick),
                    )
                val payload = "* $nick $action"
                val builder =
                    MessageBuilder.withPayload(payload)
                        .setHeader(Provenance.HEADER, provenance)
                        .setHeader(Provenance.ADDRESSED, false)
                        .setHeader(Provenance.IS_ACTION, true)
                        .setHeader("nick", nick)
                        .setHeader("host", host)
                        .setHeader("ident", ident)
                eventGateway.send(builder.build())
            } catch (e: Exception) {
                logger.error("Error processing channel action on {}: {}", networkName, e.message)
            }
        }
    }

    @Handler
    fun onChannelJoined(event: RequestedChannelJoinCompleteEvent) {
        logger.info("Joined {} on {}", event.channel.name, networkName)
    }

    @Handler
    fun onUserJoin(event: ChannelJoinEvent) {
        dispatchLoggingEvent(
            event.channel.name,
            "* ${event.actor.nick} joined ${event.channel.name}",
        )
    }

    @Handler
    fun onChannelTopic(event: ChannelTopicEvent) {
        val setter = event.newTopic.setter.map { it.name }.orElse("someone")
        val newTopic = event.newTopic.value.orElse("")
        dispatchLoggingEvent(event.channel.name, "* $setter changed the topic to: $newTopic")
    }

    @Handler
    fun onUserPart(event: ChannelPartEvent) {
        val reason = event.message.let { if (it.isNotEmpty()) " ($it)" else "" }
        dispatchLoggingEvent(
            event.channel.name,
            "* ${event.actor.nick} left ${event.channel.name}$reason",
        )
    }

    @Handler
    fun onNickChange(event: UserNickChangeEvent) {
        dispatchLoggingEvent("*", "* ${event.actor.nick} is now known as ${event.newUser.nick}")
    }

    @Handler
    fun onUserQuit(event: UserQuitEvent) {
        val reason = event.message.let { if (it.isNotEmpty()) " ($it)" else "" }
        dispatchLoggingEvent("*", "* ${event.actor.nick} quit$reason")
    }

    companion object {
        const val ALLOW_OPS_KEY = "irc-allow-ops"
        private const val MAX_IRC_MESSAGE_LENGTH = 400
        private const val MAX_IRC_REPLY_LINES = 4
        private const val TRUNCATION_SUFFIX = " [...more]"

        /** Collapses multiline content to first line and truncates to fit IRC message limits */
        fun truncateForIrc(text: String): String {
            val lines = text.split("\n")
            val firstLine = lines.firstOrNull().orEmpty()
            val hasMoreLines = lines.size > 1

            if (hasMoreLines) {
                val maxFirstLine = MAX_IRC_MESSAGE_LENGTH - TRUNCATION_SUFFIX.length
                return if (firstLine.length > maxFirstLine) {
                    firstLine.substring(0, maxFirstLine) + TRUNCATION_SUFFIX
                } else {
                    firstLine + TRUNCATION_SUFFIX
                }
            }

            if (firstLine.length > MAX_IRC_MESSAGE_LENGTH) {
                return firstLine.substring(0, MAX_IRC_MESSAGE_LENGTH - TRUNCATION_SUFFIX.length) +
                    TRUNCATION_SUFFIX
            }
            return firstLine
        }

        /** Splits text into bounded IRC-safe lines, adding a suffix if content is omitted. */
        fun splitForIrc(text: String, maxLines: Int = MAX_IRC_REPLY_LINES): List<String> {
            if (maxLines <= 0) return emptyList()
            val allChunks = mutableListOf<String>()

            for (line in text.split("\n")) {
                if (line.isEmpty()) continue
                allChunks.addAll(wrapIrcLine(line))
            }

            if (allChunks.isEmpty()) return listOf("")
            if (allChunks.size <= maxLines) return allChunks

            val visible = allChunks.take(maxLines).toMutableList()
            visible[visible.lastIndex] = withMoreSuffix(visible.last())
            return visible
        }

        private fun wrapIrcLine(line: String): List<String> {
            if (line.length <= MAX_IRC_MESSAGE_LENGTH) return listOf(line)
            val chunks = mutableListOf<String>()
            var remaining = line.trimStart()
            while (remaining.isNotEmpty()) {
                if (remaining.length <= MAX_IRC_MESSAGE_LENGTH) {
                    chunks.add(remaining)
                    break
                }

                val boundary = remaining.lastIndexOf(' ', MAX_IRC_MESSAGE_LENGTH)
                if (boundary <= 0) {
                    chunks.add(remaining.substring(0, MAX_IRC_MESSAGE_LENGTH))
                    remaining = remaining.substring(MAX_IRC_MESSAGE_LENGTH)
                } else {
                    chunks.add(remaining.substring(0, boundary))
                    remaining = remaining.substring(boundary + 1)
                }
                remaining = remaining.trimStart()
            }
            return chunks
        }

        private fun withMoreSuffix(text: String): String {
            val maxBaseLength = MAX_IRC_MESSAGE_LENGTH - TRUNCATION_SUFFIX.length
            return if (text.length > maxBaseLength) {
                text.take(maxBaseLength) + TRUNCATION_SUFFIX
            } else {
                text + TRUNCATION_SUFFIX
            }
        }

        internal fun stripAddressedText(
            raw: String,
            signalCharacter: String,
            botNick: String,
        ): String? {
            if (signalCharacter.isNotEmpty() && raw.startsWith(signalCharacter)) {
                val stripped = raw.removePrefix(signalCharacter).trimStart()
                return stripped.ifEmpty { null }
            }

            val nick = botNick.lowercase()
            val lowerRaw = raw.lowercase()
            for (separator in listOf(": ", ", ")) {
                val prefix = "$nick$separator"
                if (lowerRaw.startsWith(prefix)) {
                    val stripped = raw.substring(prefix.length).trimStart()
                    return stripped.ifEmpty { null }
                }
            }

            return null
        }
    }

    /** Dispatches a metadata event as a LoggingRequest through ingress for logging only */
    private fun dispatchLoggingEvent(channelName: String, content: String) {
        Thread.startVirtualThread {
            try {
                val provenance =
                    Provenance(
                        protocol = Protocol.IRC,
                        serviceId = networkName,
                        replyTo = channelName,
                        metadata = mapOf(Provenance.BOT_NICK to client.nick),
                    )
                val message =
                    MessageBuilder.withPayload(LoggingRequest(content) as Any)
                        .setHeader(Provenance.HEADER, provenance)
                        .build()
                eventGateway.send(message)
            } catch (e: Exception) {
                logger.error("Error dispatching logging event on {}: {}", networkName, e.message)
            }
        }
    }
}
