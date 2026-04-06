/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.UserResolutionService
import dev.streampack.discord.config.DiscordProperties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.react.MessageReactionAddEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.integration.support.MessageBuilder
import org.springframework.stereotype.Component

/** Manages the JDA connection lifecycle and dispatches Discord messages into the event gateway */
@Component
@ConditionalOnProperty("streampack.discord.enabled", havingValue = "true")
@EnableConfigurationProperties(DiscordProperties::class)
class DiscordAdapter(
    private val eventGateway: EventGateway,
    private val userResolutionService: UserResolutionService,
    private val channelControlService: ChannelControlService,
    private val properties: DiscordProperties,
) : ListenerAdapter(), InitializingBean, DisposableBean, ProtocolAdapter {
    override val protocol: Protocol = Protocol.DISCORD
    override val serviceName: String = "discord"
    override val signalCharacter: String
        get() = properties.signalCharacter

    private val logger = LoggerFactory.getLogger(DiscordAdapter::class.java)
    private lateinit var jda: JDA

    /** Tracks the last message per channel for reaction filtering */
    internal val lastMessageByChannel = ConcurrentHashMap<String, LastMessage>()

    override fun afterPropertiesSet() {
        if (properties.token.isBlank()) {
            logger.error("Discord token is blank, cannot connect")
            return
        }
        logger.info("Connecting to Discord")
        jda =
            JDABuilder.createDefault(properties.token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGE_REACTIONS)
                .addEventListeners(this)
                .build()
        jda.awaitReady()
        logger.info("Discord connection established")
    }

    override fun destroy() {
        if (::jda.isInitialized) {
            logger.info("Shutting down Discord connection")
            jda.shutdown()
        }
    }

    override fun onReady(event: ReadyEvent) {
        val selfUser = event.jda.selfUser
        logger.info("Discord bot ready as {}", selfUser.name)

        if (properties.applicationId.isNotBlank()) {
            logger.info(
                "Invite URL: https://discord.com/oauth2/authorize?client_id={}&scope=bot&permissions={}",
                properties.applicationId,
                properties.permissions,
            )
        }

        // Register channel control options for discovered guilds/channels
        for (guild in event.jda.guilds) {
            for (channel in guild.textChannels) {
                val uri =
                    Provenance(
                            protocol = Protocol.DISCORD,
                            serviceId = guild.id,
                            replyTo = "#${channel.name}",
                        )
                        .encode()
                channelControlService.getOrCreateOptions(uri)
            }
        }
    }

    // MessageUpdateEvent is intentionally not handled: edits should not cross the bridge
    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.author.isBot) return

        Thread.startVirtualThread {
            try {
                handleMessage(event)
            } catch (e: Exception) {
                logger.error("Error processing Discord message: {}", e.message)
            }
        }
    }

    private fun handleMessage(event: MessageReceivedEvent) {
        val rawText = event.message.contentRaw

        if (event.isFromGuild) {
            val guild = event.guild
            val channelName = "#${event.channel.name}"
            val user = userResolutionService.resolve(Protocol.DISCORD, guild.id, event.author.id)
            val provenance =
                Provenance(
                    protocol = Protocol.DISCORD,
                    serviceId = guild.id,
                    replyTo = channelName,
                    user = user,
                    metadata =
                        mapOf(
                            Provenance.BOT_NICK to event.jda.selfUser.name,
                            "guildName" to guild.name,
                        ),
                )

            val nick = event.member?.effectiveName ?: event.author.effectiveName
            val displayText = event.message.contentDisplay
            val addressedText = extractAddressedText(rawText, event)
            val isAddressed = addressedText != null
            dispatch(addressedText ?: rawText, provenance, isAddressed, nick, displayText)

            // Track last message for reaction relay filtering
            lastMessageByChannel[event.channel.id] = LastMessage(event.messageId, AtomicInteger(0))
        } else {
            // Direct message
            val user = userResolutionService.resolve(Protocol.DISCORD, "", event.author.id)
            val provenance =
                Provenance(
                    protocol = Protocol.DISCORD,
                    replyTo = event.author.id,
                    user = user,
                    metadata = mapOf(Provenance.BOT_NICK to event.jda.selfUser.name),
                )
            // DMs are always addressed
            val displayText = event.message.contentDisplay
            dispatch(rawText, provenance, addressed = true, event.author.effectiveName, displayText)
        }
    }

    override fun onMessageReactionAdd(event: MessageReactionAddEvent) {
        if (!event.isFromGuild) return
        if (event.userId == event.jda.selfUser.id) return
        if (event.user?.isBot == true) return

        Thread.startVirtualThread {
            try {
                handleReaction(event)
            } catch (e: Exception) {
                logger.error("Error processing Discord reaction: {}", e.message)
            }
        }
    }

    private fun handleReaction(event: MessageReactionAddEvent) {
        val channelKey = event.channel.id
        if (!shouldRelayReaction(channelKey, event.messageId)) return

        val guild = event.guild
        val channelName = "#${event.channel.name}"
        val nick = event.member?.effectiveName ?: event.user?.effectiveName ?: "unknown"
        val emojiName = event.emoji.name
        val provenance =
            Provenance(
                protocol = Protocol.DISCORD,
                serviceId = guild.id,
                replyTo = channelName,
                metadata =
                    mapOf(Provenance.BOT_NICK to event.jda.selfUser.name, "guildName" to guild.name),
            )

        val payload = "* $nick reacted with :$emojiName:"
        dispatch(payload, provenance, addressed = false, nick = nick, isAction = true)
    }

    /** Returns true if a reaction on this message in this channel should be relayed */
    internal fun shouldRelayReaction(channelKey: String, messageId: String): Boolean {
        val tracked = lastMessageByChannel[channelKey] ?: return false
        if (tracked.messageId != messageId) return false
        return tracked.reactionCount.incrementAndGet() <= MAX_REACTIONS_PER_MESSAGE
    }

    /** Detects whether a message is addressed to the bot via signal character or @mention */
    private fun extractAddressedText(raw: String, event: MessageReceivedEvent): String? {
        // Signal character prefix
        if (properties.signalCharacter.isNotEmpty() && raw.startsWith(properties.signalCharacter)) {
            val stripped = raw.removePrefix(properties.signalCharacter).trimStart()
            return stripped.ifEmpty { null }
        }

        // Bot @mention prefix
        val selfId = event.jda.selfUser.id
        val mentionPatterns = listOf("<@$selfId>", "<@!$selfId>")
        for (mention in mentionPatterns) {
            if (raw.startsWith(mention)) {
                val stripped = raw.removePrefix(mention).trimStart()
                return stripped.ifEmpty { null }
            }
        }

        return null
    }

    private fun dispatch(
        payload: String,
        provenance: Provenance,
        addressed: Boolean,
        nick: String? = null,
        displayText: String? = null,
        isAction: Boolean = false,
    ) {
        val builder =
            MessageBuilder.withPayload(payload as Any)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader(Provenance.ADDRESSED, addressed)
        if (nick != null) builder.setHeader("nick", nick)
        if (displayText != null) builder.setHeader("displayText", displayText)
        if (isAction) builder.setHeader(Provenance.IS_ACTION, true)
        eventGateway.send(builder.build())
    }

    override fun wouldTriggerIngress(text: String): Boolean {
        if (
            properties.signalCharacter.isNotEmpty() && text.startsWith(properties.signalCharacter)
        ) {
            return true
        }
        if (::jda.isInitialized) {
            val selfId = jda.selfUser.id
            val mentionPatterns = listOf("<@$selfId>", "<@!$selfId>")
            if (mentionPatterns.any { text.startsWith(it) }) return true
        }
        return false
    }

    override fun sendReply(provenance: Provenance, text: String) {
        val guildId = provenance.serviceId
        if (guildId != null) {
            sendToChannel(guildId, provenance.replyTo, text)
        } else {
            sendPrivateMessage(provenance.replyTo, text)
        }
    }

    /** Sends a text message to a guild channel */
    fun sendToChannel(guildId: String, channelName: String, text: String) {
        if (!::jda.isInitialized) {
            logger.warn("JDA not initialized, cannot send to {}/{}", guildId, channelName)
            return
        }
        val guild = jda.getGuildById(guildId)
        if (guild == null) {
            logger.warn("Guild '{}' not found", guildId)
            return
        }
        val cleanName = channelName.removePrefix("#")
        val channels = guild.getTextChannelsByName(cleanName, true)
        if (channels.isEmpty()) {
            logger.warn("Channel '{}' not found in guild '{}'", channelName, guild.name)
            return
        }
        channels.first().sendMessage(text).queue()
    }

    /** Sends a direct message to a user by their Discord user ID */
    fun sendPrivateMessage(userId: String, text: String) {
        if (!::jda.isInitialized) {
            logger.warn("JDA not initialized, cannot send DM to {}", userId)
            return
        }
        jda.openPrivateChannelById(userId).queue { channel -> channel.sendMessage(text).queue() }
    }

    companion object {
        const val MAX_REACTIONS_PER_MESSAGE = 5
    }
}

/** Tracks the last message in a channel for reaction relay filtering */
internal class LastMessage(
    val messageId: String,
    val reactionCount: AtomicInteger = AtomicInteger(0),
)
