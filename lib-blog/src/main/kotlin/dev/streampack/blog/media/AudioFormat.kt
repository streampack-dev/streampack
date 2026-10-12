/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

/**
 * The audio formats uploads accept (#196), told apart by their first bytes rather than the type a
 * client claims. [mediaType] is what's stored and served; [accepts] are the declared types that
 * agree with it (a client may also declare nothing, or `application/octet-stream`).
 */
enum class AudioFormat(val mediaType: String, val accepts: Set<String>) {
    /** MP3: an ID3v2 tag, or MPEG audio frames. */
    MPEG("audio/mpeg", setOf("audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg")),

    /** AAC and the like in an MP4 container (`.m4a`): an `ftyp` box first. */
    MP4("audio/mp4", setOf("audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac")),

    /** Vorbis in Ogg. */
    OGG_VORBIS("audio/ogg", setOf("audio/ogg", "application/ogg", "audio/vorbis")),

    /** Opus in Ogg; stored as `audio/ogg` unless the uploader said `audio/opus`. */
    OGG_OPUS("audio/ogg", setOf("audio/ogg", "application/ogg", "audio/opus"));

    /** The type to store for an upload declared as [declared] (already normalised). */
    fun storedType(declared: String?): String =
        if (this == OGG_OPUS && declared == "audio/opus") "audio/opus" else mediaType

    companion object {
        /** How many leading bytes [sniff] wants; fewer is fine for a short file. */
        const val HEAD_BYTES = 4096

        /** Declared types that say nothing, and so leave the decision to the bytes. */
        val UNSPECIFIED = setOf("", "application/octet-stream", "binary/octet-stream")

        /** The declared types an upload may carry, for messages. */
        val ACCEPTED = listOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/opus")

        /** A Content-Type without parameters, in lower case. */
        fun normalise(contentType: String?): String =
            contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()

        /** The format [head] (a file's first bytes) starts like, or null if none accepted. */
        fun sniff(head: ByteArray): AudioFormat? =
            when {
                startsWith(head, 0, "ID3") && head.size >= 10 -> MPEG
                head.size >= 12 && startsWith(head, 4, "ftyp") -> MP4
                startsWith(head, 0, "OggS") -> sniffOgg(head)
                isMpegStream(head) -> MPEG
                else -> null
            }

        /** An Ogg stream's first packet names its codec: Opus or Vorbis are accepted. */
        private fun sniffOgg(head: ByteArray): AudioFormat? {
            if (head.size < 27) return null
            val segments = head[26].toInt() and 0xFF
            val packet = 27 + segments
            return when {
                startsWith(head, packet, "OpusHead") -> OGG_OPUS
                packet + 7 <= head.size &&
                    head[packet].toInt() == 1 &&
                    startsWith(head, packet + 1, "vorbis") -> OGG_VORBIS
                else -> null
            }
        }

        /**
         * Two MPEG audio frame headers in a row: a lone frame sync is too easily found by chance.
         */
        private fun isMpegStream(head: ByteArray): Boolean {
            val first = MpegFrame.parse(head, 0) ?: return false
            if (first.length <= 0) return false
            val next = first.length
            // A file of exactly one frame has no second header to check.
            if (next >= head.size) return head.size >= first.length
            return MpegFrame.parse(head, next) != null
        }

        internal fun startsWith(bytes: ByteArray, offset: Int, text: String): Boolean {
            if (offset < 0 || offset + text.length > bytes.size) return false
            return text.indices.all { bytes[offset + it] == text[it].code.toByte() }
        }
    }
}

/** An MPEG audio (MP3) frame header: what's needed to step frames and work out a duration. */
internal data class MpegFrame(
    /** 1 for MPEG-1, 2 for MPEG-2, 25 for MPEG-2.5. */
    val version: Int,
    /** 1, 2 or 3. */
    val layer: Int,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val padding: Boolean,
    val mono: Boolean,
) {
    /** Samples in one frame. */
    val samplesPerFrame: Int
        get() =
            when (layer) {
                1 -> 384
                2 -> 1152
                else -> if (version == 1) 1152 else 576
            }

    /** Bytes in the frame, header included. */
    val length: Int
        get() =
            if (layer == 1) (12 * bitrateKbps * 1000 / sampleRate + (if (padding) 1 else 0)) * 4
            else samplesPerFrame / 8 * bitrateKbps * 1000 / sampleRate + (if (padding) 1 else 0)

    /** Where a Xing/Info header would start, counted from the frame's first byte. */
    val sideInfoEnd: Int
        get() =
            4 +
                when {
                    version == 1 && !mono -> 32
                    version == 1 -> 17
                    !mono -> 17
                    else -> 9
                }

    companion object {
        private val BITRATES_V1 =
            arrayOf(
                intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
                intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
                intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
            )
        private val BITRATES_V2 =
            arrayOf(
                intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
                intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
                intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
            )
        private val SAMPLE_RATES = intArrayOf(44100, 48000, 32000)

        /** The frame header at [offset] in [bytes], or null if there isn't a valid one. */
        fun parse(bytes: ByteArray, offset: Int): MpegFrame? {
            if (offset < 0 || offset + 4 > bytes.size) return null
            val b1 = bytes[offset].toInt() and 0xFF
            val b2 = bytes[offset + 1].toInt() and 0xFF
            val b3 = bytes[offset + 2].toInt() and 0xFF
            val b4 = bytes[offset + 3].toInt() and 0xFF
            if (b1 != 0xFF || (b2 and 0xE0) != 0xE0) return null
            val version =
                when ((b2 shr 3) and 0x3) {
                    0 -> 25
                    2 -> 2
                    3 -> 1
                    else -> return null
                }
            val layer =
                when ((b2 shr 1) and 0x3) {
                    1 -> 3
                    2 -> 2
                    3 -> 1
                    else -> return null
                }
            val bitrateIndex = (b3 shr 4) and 0xF
            val rateIndex = (b3 shr 2) and 0x3
            if (bitrateIndex == 0 || bitrateIndex == 0xF || rateIndex == 3) return null
            val table = if (version == 1) BITRATES_V1 else BITRATES_V2
            val bitrate = table[layer - 1][bitrateIndex]
            val sampleRate =
                SAMPLE_RATES[rateIndex] /
                    when (version) {
                        1 -> 1
                        2 -> 2
                        else -> 4
                    }
            return MpegFrame(
                version = version,
                layer = layer,
                bitrateKbps = bitrate,
                sampleRate = sampleRate,
                padding = (b3 shr 1) and 0x1 == 1,
                mono = (b4 shr 6) and 0x3 == 3,
            )
        }
    }
}
