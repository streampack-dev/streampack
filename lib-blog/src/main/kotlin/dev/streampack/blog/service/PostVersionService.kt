/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import com.rometools.rome.feed.synd.SyndEnclosure
import com.rometools.rome.feed.synd.SyndEnclosureImpl
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostVersion
import dev.streampack.blog.media.AudioDuration
import dev.streampack.blog.media.AudioFormat
import dev.streampack.blog.media.MediaProperties
import dev.streampack.blog.media.MediaStorage
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.model.PostVersionView
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostVersionRepository
import dev.streampack.core.config.StreampackProperties
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import java.io.BufferedInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * A post's versions (#196): attaching, replacing and removing them, and what the post API, the
 * feeds and `/media/…` know of them.
 *
 * An upload is checked by its first bytes, not the type it claims, stored under a key the storage
 * makes, and then recorded; a replacement records the new file in the same row, so its address
 * stays the same, and the old file is removed once the row points away from it. While media isn't
 * configured, nothing is listed or served, and attaching refuses.
 */
@Service
class PostVersionService(
    private val postRepository: PostRepository,
    private val versionRepository: PostVersionRepository,
    private val userRepository: UserRepository,
    private val storage: MediaStorage,
    private val mediaProperties: MediaProperties,
    private val streampackProperties: StreampackProperties,
) {
    private val logger = LoggerFactory.getLogger(PostVersionService::class.java)

    /** Whether media is configured, so versions can be stored and served. */
    val available: Boolean
        get() = storage.available

    /** What an upload carries besides its bytes. */
    data class Upload(
        val content: InputStream,
        /** The Content-Type the client gave the file, if any. */
        val declaredType: String?,
        /** The size the client gave, checked before reading; the bytes are counted too. */
        val declaredSize: Long,
        /** The length in seconds, from the uploader; read from the file when null. */
        val durationSeconds: Int? = null,
        val note: String? = null,
    )

    /** An attached version, and whether it replaced one. */
    data class Attached(val version: PostVersionView, val replaced: Boolean)

    /** A version ready to serve: its row, its bytes, and whether anyone may read it. */
    data class Served(val version: PostVersion, val resource: Resource, val public: Boolean)

    /** No such post (or it's deleted), or it has no such version. */
    class NotFoundException(message: String) : RuntimeException(message)

    /** A bad kind, note, duration or empty file. */
    class InvalidException(message: String) : RuntimeException(message)

    /** The file isn't audio of an accepted type, or isn't what it claims to be. */
    class UnsupportedTypeException(message: String) : RuntimeException(message)

    /** Another upload for the same version won. */
    class ConflictException(message: String) : RuntimeException(message)

    /** The largest file an upload may carry, in bytes. */
    val maxBytes: Long
        get() = mediaProperties.maxSize.toBytes()

    /**
     * Attaches [upload] to the post [postId] as its [kind] version, replacing any it had, made by
     * [maker]. Throws [MediaStorage.NotConfiguredException], [MediaStorage.TooLargeException],
     * [NotFoundException], [InvalidException], [UnsupportedTypeException] or [ConflictException].
     */
    fun attach(postId: UUID, kind: String, upload: Upload, maker: UserPrincipal): Attached {
        if (!storage.available) throw MediaStorage.NotConfiguredException()
        if (kind !in PostVersion.KINDS) {
            throw InvalidException("Only ${PostVersion.KINDS.joinToString()} versions are accepted")
        }
        val note = checkNote(upload.note)
        val duration = upload.durationSeconds
        if (duration != null && (duration < 1 || duration > MAX_DURATION_SECONDS)) {
            throw InvalidException("The duration is 1 to $MAX_DURATION_SECONDS seconds")
        }
        if (upload.declaredSize > maxBytes) throw MediaStorage.TooLargeException(maxBytes)
        val post =
            postRepository.findActiveById(postId) ?: throw NotFoundException("Post not found")

        val content = BufferedInputStream(upload.content, AudioFormat.HEAD_BYTES * 2)
        content.mark(AudioFormat.HEAD_BYTES)
        val head = content.readNBytes(AudioFormat.HEAD_BYTES)
        content.reset()
        if (head.isEmpty()) throw InvalidException("The file is empty")
        val format = checkFormat(head, upload.declaredType)
        val declared = AudioFormat.normalise(upload.declaredType)

        val stored = storage.store(content, maxBytes)
        val existing: PostVersion?
        val saved: PostVersion
        try {
            val seconds = duration ?: readDuration(stored.key, format)
            val now = Instant.now()
            existing = findVersion(postId, kind, null)
            val makerUser = userRepository.findById(maker.id).orElse(null)
            val values =
                (existing ?: PostVersion(post = post, kind = kind, createdAt = now)).copy(
                    mediaType = format.storedType(declared),
                    size = stored.size,
                    durationSeconds = seconds,
                    note = note,
                    maker = makerUser,
                    makerModel = null,
                    sourceHash = sourceHash(post),
                    contentHash = stored.sha256,
                    storageKey = stored.key,
                    replacedAt = if (existing != null) now else null,
                    updatedAt = now,
                )
            saved = versionRepository.saveAndFlush(values).copy(maker = makerUser)
        } catch (e: DataIntegrityViolationException) {
            storage.delete(stored.key)
            throw ConflictException("Another upload of this version is in progress; try again")
        } catch (e: Exception) {
            storage.delete(stored.key)
            throw e
        }
        // The row points at the new file now; the old one has nothing referring to it.
        existing?.let { storage.delete(it.storageKey) }
        logger.info(
            "{} the {} version of post {} ({} bytes, {})",
            if (existing != null) "Replaced" else "Attached",
            kind,
            postId,
            stored.size,
            saved.mediaType,
        )
        return Attached(view(saved, post), existing != null)
    }

    /** Removes the post's [kind] version and its file; [NotFoundException] if it has none. */
    fun remove(postId: UUID, kind: String) {
        postRepository.findActiveById(postId) ?: throw NotFoundException("Post not found")
        val version =
            findVersion(postId, kind, null)
                ?: throw NotFoundException("The post has no $kind version")
        versionRepository.deleteById(version.id)
        storage.delete(version.storageKey)
        logger.info("Removed the {} version of post {}", kind, postId)
    }

    /** The versions of [post] as the post API shows them; none while media isn't configured. */
    fun views(post: Post): List<PostVersionView> {
        if (!storage.available) return emptyList()
        return versionRepository.findByPost(post.id).map { view(it, post) }
    }

    /**
     * The kinds of version each of [postIds] has, sorted, for post lists (#200): one query for the
     * whole list. Posts without versions are left out; none while media is off, as with [views].
     */
    fun kindsFor(postIds: Collection<UUID>): Map<UUID, List<String>> {
        if (!storage.available || postIds.isEmpty()) return emptyMap()
        return versionRepository
            .findKindsByPosts(postIds)
            .groupBy({ it.postId }, { it.kind })
            .mapValues { (_, kinds) -> kinds.distinct().sorted() }
    }

    /**
     * The audio version (no variant) of each of [postIds] that has one; none while media is off.
     */
    fun audioByPost(postIds: Collection<UUID>): Map<UUID, PostVersion> {
        if (!storage.available || postIds.isEmpty()) return emptyMap()
        return versionRepository
            .findByPostsAndKind(postIds, PostVersion.KIND_AUDIO)
            .filter { it.variant == null }
            .associateBy { it.post.id }
    }

    /**
     * The audio versions of posts anonymous readers can see, newest post first, with their posts
     * and authors; none while media is off.
     */
    fun publishedAudio(now: Instant = Instant.now()): List<PostVersion> {
        if (!storage.available) return emptyList()
        return versionRepository.findPublishedByKind(PostVersion.KIND_AUDIO, now)
    }

    /**
     * The version to serve at `/media/posts/{postId}/{kind}[/{variant}]`, if [viewer] may read it:
     * anyone, when the post is published and not deleted; an admin, when it isn't published yet.
     */
    fun servable(postId: UUID, kind: String, variant: String?, viewer: UserPrincipal?): Served? {
        if (!storage.available) return null
        val version =
            versionRepository.findByPostAndKind(postId, kind).firstOrNull {
                it.variant == variant
            } ?: return null
        val post = version.post
        if (post.deleted) return null
        val published = isPublished(post, Instant.now())
        if (!published && (viewer == null || viewer.role < Role.ADMIN)) return null
        val resource = storage.open(version.storageKey) ?: return null
        return Served(version, resource, published)
    }

    /** The version's address: on the API's own origin (`BASE_URL`), stable across replacements. */
    fun url(version: PostVersion): String = url(version.post.id, version.kind, version.variant)

    /** A feed enclosure for [version]: its address, length and type (#196). */
    fun enclosure(version: PostVersion): SyndEnclosure =
        SyndEnclosureImpl().apply {
            url = url(version)
            length = version.size
            type = version.mediaType
        }

    fun url(postId: UUID, kind: String, variant: String?): String =
        "${streampackProperties.baseUrl.trimEnd('/')}/media/posts/$postId/$kind" +
            (variant?.let { "/$it" } ?: "")

    /**
     * Deletes, once the surrounding transaction commits, the files of the versions of every post by
     * [authorId]: for a purge that hard-deletes those posts (their rows go by cascade).
     */
    fun deleteFilesOfPostsByAuthor(authorId: UUID) {
        val keys = versionRepository.findStorageKeysByPostAuthor(authorId)
        if (keys.isEmpty()) return
        val delete = { keys.forEach { storage.delete(it) } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = delete()
                }
            )
        } else {
            delete()
        }
    }

    private fun view(version: PostVersion, post: Post): PostVersionView =
        PostVersionView(
            kind = version.kind,
            variant = version.variant,
            mediaType = version.mediaType,
            size = version.size,
            duration = version.durationSeconds,
            url = url(post.id, version.kind, version.variant),
            note = version.note,
            maker = version.maker?.displayName,
            current = version.sourceHash == sourceHash(post),
            createdAt = version.createdAt,
            replacedAt = version.replacedAt,
        )

    private fun findVersion(postId: UUID, kind: String, variant: String?): PostVersion? =
        versionRepository.findByPostAndKind(postId, kind).firstOrNull { it.variant == variant }

    private fun checkNote(note: String?): String? {
        val trimmed = note?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (trimmed.length > MAX_NOTE_LENGTH) {
            throw InvalidException("The note is at most $MAX_NOTE_LENGTH characters")
        }
        if (trimmed.any { it.isISOControl() }) {
            throw InvalidException("The note can't hold control characters")
        }
        return trimmed
    }

    private fun checkFormat(head: ByteArray, declaredType: String?): AudioFormat {
        val declared = AudioFormat.normalise(declaredType)
        val accepted = AudioFormat.entries.flatMap { it.accepts }.toSet()
        if (declared !in AudioFormat.UNSPECIFIED && declared !in accepted) {
            throw UnsupportedTypeException(
                "$declared isn't accepted; upload ${AudioFormat.ACCEPTED.joinToString()}"
            )
        }
        val format =
            AudioFormat.sniff(head)
                ?: throw UnsupportedTypeException(
                    "The file isn't audio of an accepted type " +
                        "(${AudioFormat.ACCEPTED.joinToString()})"
                )
        if (declared !in AudioFormat.UNSPECIFIED && declared !in format.accepts) {
            throw UnsupportedTypeException(
                "The file was sent as $declared but its content is ${format.mediaType}"
            )
        }
        return format
    }

    private fun readDuration(key: String, format: AudioFormat): Int? {
        val resource = storage.open(key) ?: return null
        if (!resource.isFile) return null
        return AudioDuration.read(resource.file.toPath(), format)
    }

    private fun isPublished(post: Post, now: Instant): Boolean =
        post.status == PostStatus.APPROVED && post.publishedAt?.isAfter(now) == false

    companion object {
        const val MAX_NOTE_LENGTH = 500
        const val MAX_DURATION_SECONDS = 7 * 24 * 3600

        /** The SHA-256 of [post]'s markdown, in hex: what a version records as its source. */
        fun sourceHash(post: Post): String =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(post.markdownSource.toByteArray(Charsets.UTF_8))
                )
    }
}
