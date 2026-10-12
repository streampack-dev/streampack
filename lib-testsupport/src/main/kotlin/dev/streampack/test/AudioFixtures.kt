/* Joseph B. Ottinger (C)2026 */
package dev.streampack.test

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small, synthetic audio files for media tests (#196): only the headers a sniffer and a duration
 * reader look at, around silence. None of them would play well, and none needs to.
 */
object AudioFixtures {
    /** An MPEG-1 Layer III frame header: 128 kbps, 44.1 kHz, stereo, no padding (417 bytes). */
    private val MP3_FRAME_HEADER = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00)
    const val MP3_FRAME_LENGTH = 417

    /**
     * A constant-bitrate MP3 of [frames] frames after an empty ID3v2 tag: its duration is its
     * audio's size over 128 kbps (`frames * 417 * 8 / 128000` seconds).
     */
    fun mp3(frames: Int = 100, fill: Byte = 0): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                'I'.code.toByte(),
                'D'.code.toByte(),
                '3'.code.toByte(),
                4,
                0,
                0,
                0,
                0,
                0,
                0,
            )
        )
        repeat(frames) { out.write(frame(fill)) }
        return out.toByteArray()
    }

    /** An MP3 with no ID3 tag, starting straight on a frame. */
    fun bareMp3(frames: Int = 20): ByteArray {
        val out = ByteArrayOutputStream()
        repeat(frames) { out.write(frame(0)) }
        return out.toByteArray()
    }

    /**
     * A VBR-style MP3 whose first frame holds a Xing header claiming [claimedFrames] frames: its
     * duration is `claimedFrames * 1152 / 44100` seconds, whatever its size.
     */
    fun xingMp3(claimedFrames: Int, frames: Int = 10): ByteArray {
        val first = frame(0)
        // MPEG-1 stereo: side information is 32 bytes after the 4-byte header.
        val xing = 4 + 32
        "Xing".forEachIndexed { i, c -> first[xing + i] = c.code.toByte() }
        ByteBuffer.wrap(first, xing + 4, 8).putInt(1).putInt(claimedFrames)
        val out = ByteArrayOutputStream()
        out.write(first)
        repeat(frames - 1) { out.write(frame(0)) }
        return out.toByteArray()
    }

    private fun frame(fill: Byte): ByteArray {
        val frame = ByteArray(MP3_FRAME_LENGTH) { fill }
        MP3_FRAME_HEADER.copyInto(frame)
        return frame
    }

    /**
     * An Ogg Opus stream of [seconds] seconds: an `OpusHead` page with a 312-sample pre-skip, some
     * padding, and a last page whose granule position is `seconds * 48000 + 312`.
     */
    fun oggOpus(seconds: Int = 5, padding: Int = 200): ByteArray {
        val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
        head.put("OpusHead".toByteArray())
        head.put(1).put(2).putShort(312).putInt(48000).putShort(0).put(0)
        val out = ByteArrayOutputStream()
        out.write(oggPage(granule = 0, sequence = 0, headerType = 2, packet = head.array()))
        out.write(
            oggPage(
                granule = 0,
                sequence = 1,
                headerType = 0,
                packet = ByteArray(padding.coerceAtMost(254)),
            )
        )
        out.write(
            oggPage(
                granule = seconds * 48000L + 312,
                sequence = 2,
                headerType = 4,
                packet = ByteArray(100),
            )
        )
        return out.toByteArray()
    }

    /** An Ogg Vorbis stream of [seconds] seconds at 44.1 kHz. */
    fun oggVorbis(seconds: Int = 4): ByteArray {
        val id = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
        id.put(1).put("vorbis".toByteArray()).putInt(0).put(2).putInt(44100)
        val out = ByteArrayOutputStream()
        out.write(oggPage(granule = 0, sequence = 0, headerType = 2, packet = id.array()))
        out.write(
            oggPage(
                granule = seconds * 44100L,
                sequence = 1,
                headerType = 4,
                packet = ByteArray(50),
            )
        )
        return out.toByteArray()
    }

    private fun oggPage(
        granule: Long,
        sequence: Int,
        headerType: Int,
        packet: ByteArray,
    ): ByteArray {
        require(packet.size < 255)
        val page = ByteBuffer.allocate(27 + 1 + packet.size).order(ByteOrder.LITTLE_ENDIAN)
        page.put("OggS".toByteArray()).put(0).put(headerType.toByte())
        page.putLong(granule).putInt(0x1234).putInt(sequence).putInt(0)
        page.put(1).put(packet.size.toByte())
        page.put(packet)
        return page.array()
    }

    /**
     * An M4A: an `ftyp` box, an `mdat` of [padding] bytes, and a `moov` holding an `mvhd` whose
     * duration is [seconds] at a timescale of 1000.
     */
    fun m4a(seconds: Int = 7, padding: Int = 3000): ByteArray {
        val out = ByteArrayOutputStream()
        val ftyp = ByteBuffer.allocate(20)
        ftyp.putInt(20).put("ftyp".toByteArray()).put("M4A ".toByteArray()).putInt(0)
        ftyp.put("M4A ".toByteArray())
        out.write(ftyp.array())
        val mdat = ByteBuffer.allocate(8 + padding)
        mdat.putInt(8 + padding).put("mdat".toByteArray())
        out.write(mdat.array())
        val mvhd = ByteBuffer.allocate(108)
        mvhd.putInt(108).put("mvhd".toByteArray()).putInt(0).putInt(0).putInt(0)
        mvhd.putInt(1000).putInt(seconds * 1000)
        val moov = ByteBuffer.allocate(8 + 108)
        moov.putInt(8 + 108).put("moov".toByteArray()).put(mvhd.array())
        out.write(moov.array())
        return out.toByteArray()
    }

    /** A PNG's signature and some bytes: not audio. */
    fun png(): ByteArray =
        byteArrayOf(
            0x89.toByte(),
            'P'.code.toByte(),
            'N'.code.toByte(),
            'G'.code.toByte(),
            13,
            10,
            26,
            10,
        ) + ByteArray(200)
}
