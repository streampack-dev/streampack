/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProtocolAdapter
import dev.streampack.core.service.UserResolutionService
import dev.streampack.mattermost.model.MattermostChannelRef
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.integration.support.MessageBuilder
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * Runtime Mattermost adapter.
 *
 * It uses the REST API for discovery and message delivery, and uses the server WebSocket endpoint
 * for real-time message intake.
 */
class MattermostAdapter(
    private val serverName: String,
    baseUrl: String,
    private val token: String,
    initialSignalCharacter: String,
    private val reconnectDelay: Duration,
    private val eventGateway: EventGateway,
    private val userResolutionService: UserResolutionService,
    private val restClientBuilder: RestClient.Builder,
    /** Runs after every successful socket authentication, including reconnects */
    private val onConnected: (MattermostAdapter) -> Unit = {},
) : ProtocolAdapter {
    override val protocol: Protocol = Protocol.MATTERMOST
    override val serviceName: String = serverName

    /**
     * Changed at runtime by `mattermost signal`; read on every message, so no reconnect is needed
     */
    @Volatile override var signalCharacter: String = initialSignalCharacter

    private val logger = LoggerFactory.getLogger(MattermostAdapter::class.java)
    private val mapper: JsonMapper = JsonMapper.builder().findAndAddModules().build()
    private val normalizedBaseUrl = baseUrl.trimEnd('/')
    private val restClient =
        restClientBuilder
            .baseUrl(normalizedBaseUrl)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build()
    private val httpClient = HttpClient.newHttpClient()
    private val seq = AtomicInteger(1)
    private val connected = AtomicBoolean(false)
    private val explicitDisconnect = AtomicBoolean(false)
    private val eventBuffer = StringBuilder()
    private val reconnectAttempts = AtomicInteger(0)

    /* Bounded memory of dispatched post ids, oldest evicted first; guarded by synchronized */
    private val recentPostIds =
        object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) =
                size > RECENT_POST_LIMIT
        }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var selfUser: MattermostUserView? = null

    fun connect() {
        explicitDisconnect.set(false)
        identify()
        logger.info(
            "Connecting Mattermost adapter '{}' as user '{}'",
            serverName,
            selfUser?.username ?: "unknown",
        )
        socket =
            httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(websocketUrl()), Listener())
                .join()
    }

    /** Reads the connected account from `/users/me`; the socket is not opened. */
    internal fun identify() {
        selfUser = fetchCurrentUser()
    }

    /** Handles one complete WebSocket text frame; malformed frames are logged and dropped. */
    internal fun handleFrame(text: String) {
        runCatching { handleSocketMessage(text) }
            .onFailure {
                logger.warn(
                    "Failed to process Mattermost websocket event on '{}': {}",
                    serverName,
                    it.message,
                )
            }
    }

    /**
     * Keep trying to connect in the background until it works or [disconnect] is called, backing
     * off between attempts. Used after a failed [connect] and after the socket drops.
     */
    fun retryLater() = scheduleReconnect()

    fun disconnect() {
        explicitDisconnect.set(true)
        connected.set(false)
        socket?.sendClose(WebSocket.NORMAL_CLOSURE, "disconnect")
        socket = null
    }

    fun isConnected(): Boolean = connected.get()

    /** Adds the connected account to a channel it can join on its own (public channels). */
    fun joinChannel(channelId: String): Boolean {
        val self = selfUser?.id ?: return false
        return runCatching {
                restClient
                    .post()
                    .uri("/api/v4/channels/{channelId}/members", channelId)
                    .body(mapOf("user_id" to self))
                    .retrieve()
                    .toBodilessEntity()
                true
            }
            .getOrElse {
                logger.warn(
                    "Could not join channel {} on '{}': {}",
                    channelId,
                    serverName,
                    it.message,
                )
                false
            }
    }

    /** Removes the connected account from a channel, so the server stops sending its posts. */
    fun leaveChannel(channelId: String): Boolean {
        val self = selfUser?.id ?: return false
        return runCatching {
                restClient
                    .delete()
                    .uri("/api/v4/channels/{channelId}/members/{userId}", channelId, self)
                    .retrieve()
                    .toBodilessEntity()
                true
            }
            .getOrElse {
                logger.warn(
                    "Could not leave channel {} on '{}': {}",
                    channelId,
                    serverName,
                    it.message,
                )
                false
            }
    }

    override fun wouldTriggerIngress(text: String): Boolean {
        if (signalCharacter.isNotEmpty() && text.startsWith(signalCharacter)) return true
        val username = selfUser?.username ?: return false
        return text.startsWith("@$username ")
    }

    override fun sendReply(provenance: Provenance, text: String) {
        try {
            restClient
                .post()
                .uri("/api/v4/posts")
                .body(MattermostCreatePostRequest(channelId = provenance.replyTo, message = text))
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            logger.error(
                "Failed to post to {} on '{}': {}",
                provenance.replyTo,
                serverName,
                e.message,
            )
        }
    }

    fun resolveChannel(query: String): MattermostChannelRef? {
        val cleaned = query.removePrefix("#").trim()
        if (cleaned.isBlank()) return null

        if (looksLikeChannelId(cleaned)) {
            findChannelById(cleaned)?.let {
                return it
            }
        }

        val channels = listChannels(cleaned)
        val exactMatches =
            channels.filter {
                it.name.equals(cleaned, ignoreCase = true) ||
                    it.displayName.equals(query, ignoreCase = true) ||
                    "#${it.name}".equals(query, ignoreCase = true)
            }

        return when {
            exactMatches.size == 1 -> exactMatches.first()
            exactMatches.size > 1 ->
                throw IllegalArgumentException(
                    "Ambiguous channel '$query': ${exactMatches.joinToString { it.summary() }}"
                )
            channels.size == 1 -> channels.first()
            else -> null
        }
    }

    fun listChannels(searchTerm: String?): List<MattermostChannelRef> {
        val teams = currentTeams()
        val term = searchTerm?.trim().orEmpty()
        val seen = LinkedHashMap<String, MattermostChannelRef>()

        for (team in teams) {
            val channels =
                if (term.isBlank()) {
                    restClient
                        .get()
                        .uri("/api/v4/users/me/teams/{teamId}/channels", team.id)
                        .retrieve()
                        .body(Array<MattermostChannelView>::class.java)
                        ?.toList()
                        .orEmpty()
                } else {
                    restClient
                        .post()
                        .uri("/api/v4/teams/{teamId}/channels/search", team.id)
                        .body(MattermostChannelSearchRequest(term))
                        .retrieve()
                        .body(Array<MattermostChannelView>::class.java)
                        ?.toList()
                        .orEmpty()
                }

            channels.forEach { channel ->
                seen.putIfAbsent(
                    channel.id,
                    MattermostChannelRef(
                        id = channel.id,
                        name = channel.name,
                        displayName = channel.displayName,
                        teamId = channel.teamId,
                        teamName = team.displayName ?: team.name,
                        type = channel.type,
                    ),
                )
            }
        }

        return seen.values.sortedBy { it.teamName.orEmpty() + "/" + it.name }
    }

    private fun fetchCurrentUser(): MattermostUserView =
        restClient.get().uri("/api/v4/users/me").retrieve().body(MattermostUserView::class.java)
            ?: error("Mattermost /users/me returned no body for '$serverName'")

    private fun currentTeams(): List<MattermostTeamView> =
        restClient
            .get()
            .uri("/api/v4/users/me/teams")
            .retrieve()
            .body(Array<MattermostTeamView>::class.java)
            ?.toList()
            .orEmpty()

    private fun findChannelById(channelId: String): MattermostChannelRef? =
        runCatching {
                restClient
                    .get()
                    .uri("/api/v4/channels/{channelId}", channelId)
                    .retrieve()
                    .body(MattermostChannelView::class.java)
            }
            .getOrNull()
            ?.let {
                MattermostChannelRef(
                    id = it.id,
                    name = it.name,
                    displayName = it.displayName,
                    teamId = it.teamId,
                    type = it.type,
                )
            }

    private fun websocketUrl(): String {
        val wsBase =
            when {
                normalizedBaseUrl.startsWith("https://") ->
                    "wss://" + normalizedBaseUrl.removePrefix("https://")
                normalizedBaseUrl.startsWith("http://") ->
                    "ws://" + normalizedBaseUrl.removePrefix("http://")
                else -> normalizedBaseUrl
            }
        return "$wsBase/api/v4/websocket?X-Consistent-Hash=$serverName"
    }

    private fun looksLikeChannelId(value: String): Boolean = value.matches(Regex("[a-z0-9]{20,32}"))

    private fun dispatch(
        payload: String,
        provenance: Provenance,
        addressed: Boolean,
        nick: String? = null,
    ) {
        val builder =
            MessageBuilder.withPayload(payload as Any)
                .setHeader(Provenance.HEADER, provenance)
                .setHeader(Provenance.ADDRESSED, addressed)
        if (nick != null) builder.setHeader("nick", nick)
        eventGateway.send(builder.build())
    }

    private fun handleSocketMessage(message: String) {
        val root = mapper.readTree(message)

        if (root.path("status").asString("") == "OK") {
            connected.set(true)
            reconnectAttempts.set(0)
            Thread.startVirtualThread {
                runCatching { onConnected(this) }
                    .onFailure {
                        logger.warn("Post-connect hook failed for '{}': {}", serverName, it.message)
                    }
            }
            return
        }

        when (root.path("event").asString("")) {
            "hello" -> logger.info("Mattermost websocket connected for '{}'", serverName)
            "posted" -> handlePostedEvent(root)
            else -> {}
        }
    }

    private fun handlePostedEvent(root: JsonNode) {
        val data = root.path("data")
        val postJson = data.path("post").asString("")
        if (postJson.isBlank()) return

        val post = mapper.readValue<MattermostPostView>(postJson)
        if (post.id.isBlank() || alreadySeen(post.id)) return
        if (post.userId == selfUser?.id) return
        if (post.type.isNotBlank()) return

        val channelType = data.path("channel_type").asString("")
        val message = post.message.trim()
        if (message.isBlank()) return

        val user = userResolutionService.resolve(Protocol.MATTERMOST, serverName, post.userId)
        val provenance =
            Provenance(
                protocol = Protocol.MATTERMOST,
                serviceId = serverName,
                replyTo = post.channelId,
                user = user,
                metadata =
                    buildMap<String, Any> {
                        selfUser?.username?.let { put(Provenance.BOT_NICK, it) }
                        data
                            .path("channel_name")
                            .asString("")
                            .takeIf { it.isNotBlank() }
                            ?.let { put("channelName", it) }
                        data
                            .path("team_id")
                            .asString("")
                            .takeIf { it.isNotBlank() }
                            ?.let { put("teamId", it) }
                        channelType.takeIf { it.isNotBlank() }?.let { put("channelType", it) }
                    },
            )

        val addressedText = extractAddressedText(message)
        val addressed = channelType == "D" || addressedText != null
        val nick = data.path("sender_name").asString("").ifBlank { null }
        dispatch(addressedText ?: message, provenance, addressed, nick)
        /* Remembered only once dispatch succeeded, so a redelivery after a failure is processed */
        markSeen(post.id)
    }

    private fun alreadySeen(postId: String): Boolean =
        synchronized(recentPostIds) { recentPostIds.containsKey(postId) }

    private fun markSeen(postId: String) {
        synchronized(recentPostIds) { recentPostIds[postId] = true }
    }

    private fun extractAddressedText(raw: String): String? {
        if (signalCharacter.isNotEmpty() && raw.startsWith(signalCharacter)) {
            val stripped = raw.removePrefix(signalCharacter).trimStart()
            return stripped.ifEmpty { null }
        }

        val username = selfUser?.username ?: return null
        val mention = "@$username"
        if (raw.startsWith(mention)) {
            val stripped = raw.removePrefix(mention).trimStart(' ', ':', ',')
            return stripped.ifEmpty { null }
        }
        return null
    }

    private fun scheduleReconnect() {
        if (explicitDisconnect.get() || reconnectDelay.isZero || reconnectDelay.isNegative) return
        val attempt = reconnectAttempts.incrementAndGet()
        val delay = ReconnectBackoff.delay(attempt, reconnectDelay, MAX_RECONNECT_DELAY)
        logger.info("Reconnecting Mattermost '{}' in {} (attempt {})", serverName, delay, attempt)
        Thread.startVirtualThread {
            runCatching { Thread.sleep(delay.toMillis()) }
            if (explicitDisconnect.get()) return@startVirtualThread
            runCatching { connect() }
                .onFailure {
                    logger.warn("Mattermost reconnect failed for '{}': {}", serverName, it.message)
                    scheduleReconnect()
                }
        }
    }

    private inner class Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            val authMessage =
                mapper.writeValueAsString(
                    mapOf(
                        "seq" to seq.getAndIncrement(),
                        "action" to "authentication_challenge",
                        "data" to mapOf("token" to token),
                    )
                )
            webSocket.sendText(authMessage, true)
            webSocket.request(1)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean,
        ): CompletableFuture<*> {
            eventBuffer.append(data)
            if (last) {
                val payload = eventBuffer.toString()
                eventBuffer.setLength(0)
                handleFrame(payload)
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            connected.set(false)
            logger.warn(
                "Mattermost websocket error on '{}': {}",
                serverName,
                error.message ?: error.javaClass.simpleName,
            )
            scheduleReconnect()
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String,
        ): CompletableFuture<*> {
            connected.set(false)
            logger.info(
                "Mattermost websocket closed for '{}': {} {}",
                serverName,
                statusCode,
                reason,
            )
            scheduleReconnect()
            return CompletableFuture.completedFuture(null)
        }
    }

    companion object {
        const val RECENT_POST_LIMIT: Int = 2048
        val MAX_RECONNECT_DELAY: Duration = Duration.ofMinutes(5)
    }
}
