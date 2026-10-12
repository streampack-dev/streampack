/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component

/**
 * [MediaStorage] in a directory ([MediaProperties.dir]). An entry's key is `ab/<uuid>`, generated
 * here: two hex characters of a fresh UUID name a subdirectory, so no one directory grows huge.
 * Keys from anywhere else are checked against that shape before they touch the disk, so no key can
 * reach outside the directory.
 *
 * Content is written to a temporary file in the directory and moved into place once complete, so a
 * reader never sees half a file. With a blank directory the storage is unavailable; a directory
 * that is set but can't be created or written stops startup.
 */
@Component
class FilesystemMediaStorage(properties: MediaProperties) : MediaStorage {
    private val logger = LoggerFactory.getLogger(FilesystemMediaStorage::class.java)
    private val root: Path? = properties.dir.trim().takeIf { it.isNotEmpty() }?.let(::prepare)

    override val available: Boolean
        get() = root != null

    override fun store(content: InputStream, maxBytes: Long): MediaStorage.StoredMedia {
        val root = root ?: throw MediaStorage.NotConfiguredException()
        val id = UUID.randomUUID().toString()
        val key = "${id.substring(0, 2)}/$id"
        val target = root.resolve(key)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(root, ".upload-", ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            Files.newOutputStream(temp).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = content.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > maxBytes) throw MediaStorage.TooLargeException(maxBytes)
                    digest.update(buffer, 0, read)
                    out.write(buffer, 0, read)
                }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            return MediaStorage.StoredMedia(key, size, HexFormat.of().formatHex(digest.digest()))
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun open(key: String): Resource? {
        val path = pathOf(key) ?: return null
        return if (Files.isRegularFile(path)) FileSystemResource(path) else null
    }

    override fun delete(key: String): Boolean {
        val path = pathOf(key) ?: return false
        return try {
            Files.deleteIfExists(path)
        } catch (e: java.io.IOException) {
            logger.warn("Couldn't delete media entry {}: {}", key, e.message)
            false
        }
    }

    /** The file for [key], or null when media is off or the key isn't one this storage made. */
    private fun pathOf(key: String): Path? {
        val root = root ?: return null
        if (!KEY.matches(key)) return null
        val path = root.resolve(key).normalize()
        return if (path.startsWith(root)) path else null
    }

    private fun prepare(dir: String): Path {
        val path = Path.of(dir).toAbsolutePath().normalize()
        try {
            Files.createDirectories(path)
        } catch (e: Exception) {
            throw IllegalStateException(
                "streampack.media.dir ($dir) can't be created: ${e.message}. Mount a writable " +
                    "volume there, or leave STREAMPACK_MEDIA_DIR blank to turn media off.",
                e,
            )
        }
        check(Files.isDirectory(path) && Files.isWritable(path)) {
            "streampack.media.dir ($dir) isn't a writable directory. Mount a writable volume " +
                "there, or leave STREAMPACK_MEDIA_DIR blank to turn media off."
        }
        logger.info("Media storage is on")
        return path
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private val KEY =
            Regex("""[0-9a-f]{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")
    }
}
