/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.listDirectoryEntries
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Media storage on disk (#196), in a temporary directory. */
class FilesystemMediaStorageTests {
    @TempDir lateinit var dir: Path

    private fun storage(path: Path = dir.resolve("media")) =
        FilesystemMediaStorage(MediaProperties(dir = path.toString()))

    @Test
    fun `stores, opens and deletes by a generated key`() {
        val storage = storage()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }

        val stored = storage.store(ByteArrayInputStream(bytes), 1_000_000)

        assertTrue(storage.available)
        assertTrue(Regex("""[0-9a-f]{2}/[0-9a-f-]{36}""").matches(stored.key), stored.key)
        assertEquals(bytes.size.toLong(), stored.size)
        assertEquals(
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
            stored.sha256,
        )
        val resource = storage.open(stored.key)!!
        assertEquals(bytes.size.toLong(), resource.contentLength())
        assertArrayEquals(bytes, resource.inputStream.use { it.readAllBytes() })

        assertTrue(storage.delete(stored.key))
        assertNull(storage.open(stored.key))
        assertFalse(storage.delete(stored.key))
    }

    @Test
    fun `refuses content over the limit and keeps nothing`() {
        val storage = storage()

        assertThrows<MediaStorage.TooLargeException> {
            storage.store(ByteArrayInputStream(ByteArray(2048)), 1024)
        }

        val leftovers =
            Files.walk(dir.resolve("media")).use { paths ->
                paths.filter(Files::isRegularFile).count()
            }
        assertEquals(0, leftovers)
    }

    @Test
    fun `keys it didn't make reach nothing`() {
        val storage = storage()
        Files.writeString(dir.resolve("secret.txt"), "outside")
        val stored = storage.store(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 10)

        for (key in
            listOf("../secret.txt", "/etc/passwd", "ab/../../secret.txt", stored.key + "/..", "")) {
            assertNull(storage.open(key), key)
            assertFalse(storage.delete(key), key)
        }
        assertTrue(Files.exists(dir.resolve("secret.txt")))
    }

    @Test
    fun `is off with no directory`() {
        val storage = FilesystemMediaStorage(MediaProperties(dir = " "))

        assertFalse(storage.available)
        assertThrows<MediaStorage.NotConfiguredException> {
            storage.store(ByteArrayInputStream(byteArrayOf(1)), 10)
        }
        assertNull(storage.open("ab/0190a1b2-0000-7000-8000-000000000000"))
        assertFalse(storage.delete("ab/0190a1b2-0000-7000-8000-000000000000"))
    }

    @Test
    fun `a directory that can't be made stops startup, naming it`() {
        val file = Files.writeString(dir.resolve("not-a-directory"), "x")

        val e = assertThrows<IllegalStateException> { storage(file.resolve("media")) }

        assertTrue(e.message!!.contains("streampack.media.dir"), e.message)
    }

    @Test
    fun `a directory that can't be written stops startup`() {
        val readOnly = Files.createDirectory(dir.resolve("read-only"))
        assertTrue(readOnly.toFile().setWritable(false))
        try {
            val e = assertThrows<IllegalStateException> { storage(readOnly) }
            assertTrue(e.message!!.contains("isn't a writable directory"), e.message)
        } finally {
            readOnly.toFile().setWritable(true)
        }
    }

    @Test
    fun `files are spread over subdirectories`() {
        val storage = storage()
        repeat(5) { storage.store(ByteArrayInputStream(byteArrayOf(it.toByte())), 10) }

        val top = dir.resolve("media").listDirectoryEntries()
        assertTrue(
            top.all { Files.isDirectory(it) && it.fileName.toString().length == 2 },
            top.toString(),
        )
    }
}
