package com.meracast.miracast.rtp

import java.nio.ByteBuffer

/**
 * RTP packet (RFC 3550) — minimal header + payload.
 *
 * Used to upgrade screen mirroring from raw UDP to sequenced,
 * timestamped RTP packets with loss detection.
 *
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                           timestamp                           |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |           synchronization source (SSRC) identifier            |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |            contributing source (CSRC) identifiers             |
 *  |                               ....                            |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                   payload (variable length)                    |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */

data class RtpPacket(
    val version: Int = 2,
    val padding: Boolean = false,
    val extension: Boolean = false,
    val csrcCount: Int = 0,
    val marker: Boolean = false,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    /** Serialize to raw bytes for network transport. */
    fun toBytes(): ByteArray {
        val headerSize = 12 + csrcCount * 4
        val buf = ByteBuffer.allocate(headerSize + payload.size)

        // First byte: V(2)|P(1)|X(1)|CC(4)
        val firstByte = (version shl 6) or
                (if (padding) 1 shl 5 else 0) or
                (if (extension) 1 shl 4 else 0) or
                (csrcCount and 0x0F)
        buf.put(firstByte.toByte())

        // Second byte: M(1)|PT(7)
        val secondByte = (if (marker) 1 shl 7 else 0) or (payloadType and 0x7F)
        buf.put(secondByte.toByte())

        // Sequence number (16 bits)
        buf.putShort((sequenceNumber and 0xFFFF).toShort())

        // Timestamp (32 bits)
        buf.putInt((timestamp and 0xFFFFFFFFL).toInt())

        // SSRC (32 bits)
        buf.putInt((ssrc and 0xFFFFFFFFL).toInt())

        // Payload
        buf.put(payload)

        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RtpPacket) return false
        return sequenceNumber == other.sequenceNumber &&
                timestamp == other.timestamp &&
                payloadType == other.payloadType
    }

    override fun hashCode(): Int = sequenceNumber

    companion object {
        /** H.264 payload (RFC 3984) */
        const val PT_H264 = 96

        /** AAC audio payload */
        const val PT_AAC = 97

        /** OPUS audio payload */
        const val PT_OPUS = 98

        /** MPEG2-TS payload (existing mode) */
        const val PT_MP2T = 33

        /** Default SSRC for this app */
        const val DEFAULT_SSRC = 0x4D434153L  // "MCAS" in ASCII

        /** Clock rate for video RTP timestamp (90kHz) */
        const val CLOCK_RATE_VIDEO = 90000

        /** Clock rate for audio RTP timestamp */
        const val CLOCK_RATE_AUDIO = 48000

        /**
         * Parse an RTP packet from raw bytes.
         * Returns null if the header is too short or invalid.
         */
        fun fromBytes(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): RtpPacket? {
            if (length < 12) return null

            val buf = ByteBuffer.wrap(data, offset, length)

            val firstByte = buf.get().toInt() and 0xFF
            val version = (firstByte shr 6) and 0x03
            val padding = (firstByte and 0x20) != 0
            val extension = (firstByte and 0x10) != 0
            val csrcCount = firstByte and 0x0F

            val secondByte = buf.get().toInt() and 0xFF
            val marker = (secondByte and 0x80) != 0
            val payloadType = secondByte and 0x7F

            val sequenceNumber = buf.getShort().toInt() and 0xFFFF
            val timestamp = buf.getInt().toLong() and 0xFFFFFFFFL
            val ssrc = buf.getInt().toLong() and 0xFFFFFFFFL

            // Skip CSRC identifiers
            val headerSize = 12 + csrcCount * 4
            if (extension) {
                // Skip extension header (4 bytes: profile + length) + extension data
                if (length < headerSize + 4) return null
                buf.position(headerSize)
                val extLen = buf.getShort().toInt() and 0xFFFF
                buf.position(headerSize + 4 + extLen * 4)
            }

            val payloadStart = if (extension) headerSize + 4 + (buf.getShort(buf.position() - 2).toInt() and 0xFFFF) * 4
                               else headerSize
            val payloadLen = length - payloadStart
            if (payloadLen < 0) return null

            val payload = ByteArray(payloadLen)
            System.arraycopy(data, offset + payloadStart, payload, 0, payloadLen)

            return RtpPacket(
                version = version,
                padding = padding,
                extension = extension,
                csrcCount = csrcCount,
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber,
                timestamp = timestamp,
                ssrc = ssrc,
                payload = payload
            )
        }
    }
}
