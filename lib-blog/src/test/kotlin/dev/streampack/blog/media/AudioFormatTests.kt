/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import dev.streampack.test.AudioFixtures
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Telling audio by its first bytes, and reading its duration from its headers (#196). */
class AudioFormatTests {
    @TempDir lateinit var dir: Path

    private fun head(bytes: ByteArray) = bytes.copyOf(minOf(bytes.size, AudioFormat.HEAD_BYTES))

    @Test
    fun `sniffs each accepted format`() {
        assertEquals(AudioFormat.MPEG, AudioFormat.sniff(head(AudioFixtures.mp3())))
        assertEquals(AudioFormat.MPEG, AudioFormat.sniff(head(AudioFixtures.bareMp3())))
        assertEquals(AudioFormat.MP4, AudioFormat.sniff(head(AudioFixtures.m4a())))
        assertEquals(AudioFormat.OGG_OPUS, AudioFormat.sniff(head(AudioFixtures.oggOpus())))
        assertEquals(AudioFormat.OGG_VORBIS, AudioFormat.sniff(head(AudioFixtures.oggVorbis())))
    }

    @Test
    fun `refuses what isn't accepted audio`() {
        assertNull(AudioFormat.sniff(AudioFixtures.png()))
        assertNull(AudioFormat.sniff("just some text, not audio at all".toByteArray()))
        assertNull(AudioFormat.sniff(ByteArray(0)))
        // A lone frame sync followed by junk isn't an MP3.
        assertNull(
            AudioFormat.sniff(
                byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0) + ByteArray(600) { 7 },
            ),
        )
        // Ogg, but neither Opus nor Vorbis.
        val ogg = AudioFixtures.oggOpus()
        "OpusHeed".forEachIndexed { i, c -> ogg[28 + i] = c.code.toByte() }
        assertNull(AudioFormat.sniff(head(ogg)))
    }

    @Test
    fun `Ogg Opus is stored as audio-ogg unless sent as audio-opus`() {
        assertEquals("audio/ogg", AudioFormat.OGG_OPUS.storedType("audio/ogg"))
        assertEquals("audio/ogg", AudioFormat.OGG_OPUS.storedType(""))
        assertEquals("audio/opus", AudioFormat.OGG_OPUS.storedType("audio/opus"))
        assertEquals("audio/mpeg", AudioFormat.MPEG.storedType("audio/mp3"))
    }

    @Test
    fun `normalises declared types`() {
        assertEquals("audio/mpeg", AudioFormat.normalise("Audio/MPEG; charset=binary"))
        assertEquals("", AudioFormat.normalise(null))
    }

    @Test
    fun `reads durations from headers`() {
        // 100 frames of 417 bytes at 128 kbps: 2.6 seconds.
        assertEquals(3, duration(AudioFixtures.mp3(frames = 100), AudioFormat.MPEG))
        // A Xing header's frame count wins over the size: 1000 * 1152 / 44100 = 26.1 seconds.
        assertEquals(26, duration(AudioFixtures.xingMp3(claimedFrames = 1000), AudioFormat.MPEG))
        assertEquals(5, duration(AudioFixtures.oggOpus(seconds = 5), AudioFormat.OGG_OPUS))
        assertEquals(4, duration(AudioFixtures.oggVorbis(seconds = 4), AudioFormat.OGG_VORBIS))
        assertEquals(7, duration(AudioFixtures.m4a(seconds = 7), AudioFormat.MP4))
    }

    @Test
    fun `a file that doesn't say has no duration`() {
        assertNull(duration(AudioFixtures.png(), AudioFormat.MP4))
        assertNull(duration(AudioFixtures.png(), AudioFormat.MPEG))
        assertNull(duration("OggS".toByteArray(), AudioFormat.OGG_OPUS))
        assertNull(duration(ByteArray(0), AudioFormat.MPEG))
    }

    private fun duration(bytes: ByteArray, format: AudioFormat): Int? {
        val file = Files.createTempFile(dir, "audio", ".bin")
        Files.write(file, bytes)
        return AudioDuration.read(file, format)
    }
}
