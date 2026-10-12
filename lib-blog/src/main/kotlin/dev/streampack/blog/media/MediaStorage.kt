/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import java.io.InputStream
import org.springframework.core.io.Resource

/**
 * Where media bytes live (#196): store, open and delete, by a key the storage generates. Callers
 * never choose keys, and no key or path is ever shown to a client. On disk for now
 * ([FilesystemMediaStorage]); the interface leaves room for another store.
 */
interface MediaStorage {
    /** Whether media is configured; when it isn't, [store] refuses and [open] finds nothing. */
    val available: Boolean

    /**
     * Copies [content] into a new entry and answers its key, size and SHA-256. Refuses with
     * [TooLargeException], keeping nothing, once more than [maxBytes] have been read.
     */
    fun store(content: InputStream, maxBytes: Long): StoredMedia

    /** The entry under [key] as a readable resource (for ranges), or null if there's none. */
    fun open(key: String): Resource?

    /** Removes the entry under [key]; true if there was one. */
    fun delete(key: String): Boolean

    /** What [store] kept: its [key], [size] in bytes and the [sha256] of its bytes, in hex. */
    data class StoredMedia(val key: String, val size: Long, val sha256: String)

    /** Media isn't configured (`STREAMPACK_MEDIA_DIR` is blank). */
    class NotConfiguredException :
        IllegalStateException("Media storage isn't configured (STREAMPACK_MEDIA_DIR)")

    /** The content was larger than the limit. */
    class TooLargeException(val maxBytes: Long) :
        IllegalArgumentException("The file is larger than the limit of $maxBytes bytes")
}
