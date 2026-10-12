/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Reads an audio file's duration from its headers (#196), without a decoder or a new dependency:
 * - MP4: the movie header (`moov`/`mvhd`): its duration over its timescale.
 * - Ogg: the last page's granule position over the sample rate (48 kHz for Opus, less its pre-skip;
 *   the identification header's rate for Vorbis).
 * - MP3: a Xing/Info or VBRI header's frame count, as VBR encoders write; otherwise, for constant
 *   bitrate, the audio's size over the first frame's bitrate.
 *
 * Best effort: null when the file doesn't say, and never an exception for a malformed file. The
 * uploader's own duration, when given, wins over this.
 */
object AudioDuration {
    /** Whole seconds (rounded), or null when [path] doesn't say. */
    fun read(path: Path, format: AudioFormat): Int? =
        try {
            RandomAccessFile(path.toFile(), "r").use { file ->
                val seconds =
                    when (format) {
                        AudioFormat.MP4 -> mp4(file)
                        AudioFormat.OGG_OPUS,
                        AudioFormat.OGG_VORBIS -> ogg(file, format)
                        AudioFormat.MPEG -> mpeg(file)
                    }
                seconds?.takeIf { it.isFinite() && it > 0 && it < MAX_SECONDS }?.roundToInt()
            }
        } catch (_: Exception) {
            null
        }

    private fun mp4(file: RandomAccessFile): Double? {
        val moov = findBox(file, 0, file.length(), "moov") ?: return null
        val mvhd = findBox(file, moov.first, moov.second, "mvhd") ?: return null
        file.seek(mvhd.first)
        val version = file.readUnsignedByte()
        file.skipBytes(3)
        val (timescale, duration) =
            if (version == 1) {
                file.skipBytes(16)
                (file.readInt().toLong() and 0xFFFFFFFFL) to file.readLong()
            } else {
                file.skipBytes(8)
                (file.readInt().toLong() and 0xFFFFFFFFL) to
                    (file.readInt().toLong() and 0xFFFFFFFFL)
            }
        if (timescale <= 0 || duration <= 0) return null
        return duration.toDouble() / timescale
    }

    /** The content range (start, end) of the first box of [type] between [start] and [end]. */
    private fun findBox(
        file: RandomAccessFile,
        start: Long,
        end: Long,
        type: String,
    ): Pair<Long, Long>? {
        var position = start
        while (position + 8 <= end) {
            file.seek(position)
            var size = file.readInt().toLong() and 0xFFFFFFFFL
            val name = ByteArray(4).also { file.readFully(it) }.toString(Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                size = file.readLong()
                header = 16
            } else if (size == 0L) {
                size = end - position
            }
            if (size < header) return null
            if (name == type) return position + header to minOf(position + size, end)
            position += size
        }
        return null
    }

    private fun ogg(file: RandomAccessFile, format: AudioFormat): Double? {
        val head = ByteArray(minOf(file.length(), 512L).toInt())
        file.seek(0)
        file.readFully(head)
        val packet = 27 + (head[26].toInt() and 0xFF)
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val (rate, skip) =
            if (format == AudioFormat.OGG_OPUS) {
                48000L to (buffer.getShort(packet + 10).toLong() and 0xFFFF)
            } else {
                (buffer.getInt(packet + 12).toLong() and 0xFFFFFFFFL) to 0L
            }
        if (rate <= 0) return null
        val granule = lastGranule(file) ?: return null
        val samples = granule - skip
        return if (samples > 0) samples.toDouble() / rate else null
    }

    /** The granule position of the last Ogg page, found in the file's tail. */
    private fun lastGranule(file: RandomAccessFile): Long? {
        val length = file.length()
        val tailSize = minOf(length, OGG_TAIL).toInt()
        val tail = ByteArray(tailSize)
        file.seek(length - tailSize)
        file.readFully(tail)
        for (i in tailSize - 27 downTo 0) {
            if (AudioFormat.startsWith(tail, i, "OggS")) {
                val granule = ByteBuffer.wrap(tail, i + 6, 8).order(ByteOrder.LITTLE_ENDIAN).long
                if (granule > 0) return granule
            }
        }
        return null
    }

    private fun mpeg(file: RandomAccessFile): Double? {
        val length = file.length()
        var start = 0L
        val id3 = ByteArray(10)
        file.seek(0)
        if (length >= 10) file.readFully(id3)
        if (AudioFormat.startsWith(id3, 0, "ID3")) {
            val size =
                (id3[6].toInt() and 0x7F shl 21) or
                    (id3[7].toInt() and 0x7F shl 14) or
                    (id3[8].toInt() and 0x7F shl 7) or
                    (id3[9].toInt() and 0x7F)
            start = 10L + size + (if (id3[5].toInt() and 0x10 != 0) 10 else 0)
        }
        // The first frame is near the tag's end; padding may come between.
        val window = ByteArray(minOf(MPEG_SEARCH, (length - start).coerceAtLeast(0)).toInt())
        file.seek(start)
        file.readFully(window)
        val offset =
            (0 until window.size - 4).firstOrNull { MpegFrame.parse(window, it) != null }
                ?: return null
        val frame = MpegFrame.parse(window, offset)!!
        val frameStart = start + offset

        val header = ByteArray(minOf(256L, length - frameStart).toInt())
        file.seek(frameStart)
        file.readFully(header)
        frameCount(header, frame)?.let { frames ->
            return frames.toDouble() * frame.samplesPerFrame / frame.sampleRate
        }
        // Constant bitrate: the audio's bytes over the bitrate, less a trailing ID3v1 tag.
        var audioBytes = length - frameStart
        if (length >= 128) {
            val tag = ByteArray(3)
            file.seek(length - 128)
            file.readFully(tag)
            if (AudioFormat.startsWith(tag, 0, "TAG")) audioBytes -= 128
        }
        return audioBytes * 8.0 / (frame.bitrateKbps * 1000)
    }

    /** The frame count a Xing/Info or VBRI header in the first frame gives, if there is one. */
    private fun frameCount(header: ByteArray, frame: MpegFrame): Long? {
        val xing = frame.sideInfoEnd
        if (
            AudioFormat.startsWith(header, xing, "Xing") ||
                AudioFormat.startsWith(header, xing, "Info")
        ) {
            if (xing + 12 > header.size) return null
            val flags = ByteBuffer.wrap(header, xing + 4, 4).int
            if (flags and 0x1 == 0) return null
            return ByteBuffer.wrap(header, xing + 8, 4).int.toLong() and 0xFFFFFFFFL
        }
        val vbri = 4 + 32
        if (AudioFormat.startsWith(header, vbri, "VBRI") && vbri + 18 <= header.size) {
            return ByteBuffer.wrap(header, vbri + 14, 4).int.toLong() and 0xFFFFFFFFL
        }
        return null
    }

    private const val OGG_TAIL = 64L * 1024
    private const val MPEG_SEARCH = 64L * 1024
    private const val MAX_SECONDS = 7.0 * 24 * 3600
}
