package com.meracast.miracast.rtp

import android.util.Log
import com.meracast.common.AppConstants
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * MPEG2-TS Muxer for Wi-Fi Display (Miracast).
 *
 * Encapsulates H.264 NAL units and AAC audio frames into MPEG2-TS packets
 * as required by the Wi-Fi Display specification.
 *
 * TS Packet Structure (188 bytes):
 * - Sync byte: 0x47
 * - Transport error indicator: 1 bit
 * - Payload unit start indicator: 1 bit
 * - Transport priority: 1 bit
 * - PID: 13 bits
 * - Transport scrambling control: 2 bits
 * - Adaptation field control: 2 bits
 * - Continuity counter: 4 bits
 * - Payload: 184 bytes (or less with adaptation field)
 */
class TsMuxer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.TSMux"
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE: Byte = 0x47

        // PIDs
        private const val PID_PAT = 0x0000
        private const val PID_PMT = 0x1000
        private const val PID_VIDEO = 0x1011
        private const val PID_AUDIO = 0x1100

        // Stream types
        private const val STREAM_TYPE_H264 = 0x1B  // H.264 video
        private const val STREAM_TYPE_AAC = 0x0F   // AAC audio (ISO 13818-7)

        // Table IDs
        private const val TABLE_ID_PAT = 0x00
        private const val TABLE_ID_PMT = 0x02

        // PES stream IDs
        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0
    }

    // Continuity counters per PID
    private val continuityCounters = mutableMapOf<Int, AtomicInteger>()
    private val programClockReference: Long = 0L
    private var pcrBase: Long = 0L

    private val tsBuffer = ByteBuffer.allocate(TS_PACKET_SIZE)
    private val patInterval = 50  // Send PAT every 50 TS packets
    private var packetCount = 0

    init {
        continuityCounters[PID_PAT] = AtomicInteger(0)
        continuityCounters[PID_PMT] = AtomicInteger(0)
        continuityCounters[PID_VIDEO] = AtomicInteger(0)
        continuityCounters[PID_AUDIO] = AtomicInteger(0)
    }

    /**
     * Wrap H.264 NAL units into MPEG2-TS packets.
     * Forms PES packets, then splits into 188-byte TS packets.
     *
     * @param nalUnit Raw H.264 NAL unit data (with 00 00 01 start code)
     * @param isKeyFrame true if this is an IDR/key frame
     * @param pts Presentation timestamp (in 90kHz ticks)
     * @param dts Decode timestamp (in 90kHz ticks)
     * @return List of 188-byte TS packet byte arrays
     */
    fun wrapH264(
        nalUnit: ByteArray,
        isKeyFrame: Boolean,
        pts: Long,
        dts: Long
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        // Create PES packet from NAL unit
        val pesPacket = createPesPacket(
            streamId = STREAM_ID_VIDEO,
            data = nalUnit,
            pts = pts,
            dts = dts,
            isKeyFrame = isKeyFrame
        )

        // Split PES into TS packets
        packets.addAll(
            splitIntoTransportPackets(
                pesPacket,
                PID_VIDEO,
                payloadUnitStart = true
            )
        )

        // Add PAT/PMT periodically
        packetCount += packets.size
        if (packetCount >= patInterval) {
            packetCount = 0
            // Insert PAT
            packets.addAll(createPat())
            // Insert PMT
            packets.addAll(createPmt())
        }

        return packets
    }

    /**
     * Wrap AAC audio frame into MPEG2-TS packets.
     *
     * @param aacFrame Raw AAC ADTS frame
     * @param pts Presentation timestamp (in 90kHz ticks)
     * @return List of 188-byte TS packet byte arrays
     */
    fun wrapAac(aacFrame: ByteArray, pts: Long): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        val pesPacket = createPesPacket(
            streamId = STREAM_ID_AUDIO,
            data = aacFrame,
            pts = pts,
            dts = pts,
            isKeyFrame = false
        )

        packets.addAll(
            splitIntoTransportPackets(
                pesPacket,
                PID_AUDIO,
                payloadUnitStart = true
            )
        )

        return packets
    }

    /**
     * Create PAT (Program Association Table) packets.
     */
    private fun createPat(): List<ByteArray> {
        val sectionData = ByteBuffer.allocate(16)
        sectionData.put(0x00.toByte())   // table_id = 0x00
        sectionData.put(0xB0.toByte())   // section_syntax_indicator=1, private=0, reserved=0x03, section_length_hi
        sectionData.put(0x0D.toByte())   // section_length_lo (13 bytes following)
        sectionData.put(0x00.toByte())   // transport_stream_id
        sectionData.put(0x01.toByte())
        sectionData.put(0xC1.toByte())   // version_number=0, current_next=1
        sectionData.put(0x00.toByte())   // section_number
        sectionData.put(0x00.toByte())   // last_section_number
        // Program 1 -> PMT PID 0x1000
        sectionData.put(0x00.toByte())   // program_number
        sectionData.put(0x01.toByte())
        sectionData.put(0x10.toByte())   // PMT PID hi
        sectionData.put(0x00.toByte())   // PMT PID lo
        // CRC32 (simplified - fixed value for same content)
        sectionData.put(0x00.toByte())
        sectionData.put(0x00.toByte())
        sectionData.put(0x00.toByte())
        sectionData.put(0x00.toByte())

        sectionData.flip()
        val data = ByteArray(sectionData.remaining())
        sectionData.get(data)

        return splitIntoTransportPackets(data, PID_PAT, payloadUnitStart = true)
    }

    /**
     * Create PMT (Program Map Table) packets.
     */
    private fun createPmt(): List<ByteArray> {
        // PMT section
        val sectionLen = 4 + 5 + 5 + 4  // header + video stream info + audio stream info + CRC
        val buf = ByteBuffer.allocate(sectionLen + 3)  // +3 for pointer field and table_id header

        // Pointer field
        buf.put(0x00.toByte())
        // table_id
        buf.put(TABLE_ID_PMT.toByte())
        // section_length
        buf.put((0xB0 or ((sectionLen shr 8) and 0x0F)).toByte())
        buf.put((sectionLen and 0xFF).toByte())
        // program_number
        buf.put(0x00.toByte())
        buf.put(0x01.toByte())
        // reserved + version + current_next
        buf.put(0xC1.toByte())
        // section_number + last_section_number
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        // PCR PID (video PID)
        buf.put(0x10.toByte())
        buf.put(PID_VIDEO.toByte())
        // program_info_length = 0
        buf.put(0xF0.toByte())
        buf.put(0x00.toByte())

        // Video stream entry
        buf.put(STREAM_TYPE_H264.toByte())
        buf.put(0x10.toByte())  // elementary PID hi
        buf.put(PID_VIDEO.toByte())  // elementary PID lo
        buf.put(0xF0.toByte())  // ES info length hi
        buf.put(0x00.toByte())  // ES info length lo

        // Audio stream entry
        buf.put(STREAM_TYPE_AAC.toByte())
        buf.put(0x11.toByte())  // elementary PID hi
        buf.put(PID_AUDIO.toByte())  // elementary PID lo
        buf.put(0xF0.toByte())  // ES info length hi
        buf.put(0x00.toByte())  // ES info length lo

        // CRC32 (placeholder)
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())

        buf.flip()
        val data = ByteArray(buf.remaining())
        buf.get(data)

        return splitIntoTransportPackets(data, PID_PMT, payloadUnitStart = true)
    }

    /**
     * Create a PES (Packetized Elementary Stream) packet.
     */
    private fun createPesPacket(
        streamId: Int,
        data: ByteArray,
        pts: Long,
        dts: Long,
        isKeyFrame: Boolean
    ): ByteArray {
        // PES header: 14 bytes + optional stuffing
        val pesHeaderLen = 14
        val totalLen = pesHeaderLen + data.size

        val buf = ByteBuffer.allocate(totalLen)

        // PES start code prefix
        buf.put(0x00.toByte())
        buf.put(0x00.toByte())
        buf.put(0x01.toByte())
        // Stream ID
        buf.put(streamId.toByte())
        // PES packet length (0 = unspecified, but we provide it)
        val packetLength = data.size + 8  // 3 header + 5 PTS/DTS
        buf.put(((packetLength shr 8) and 0xFF).toByte())
        buf.put((packetLength and 0xFF).toByte())

        // PES header data
        // marker bits + scrambling + priority + alignment + copyright + original + 7 bits
        val flags1 = 0x80 or // marker
                (if (isKeyFrame) 0x40 else 0x00) or // data alignment indicator
                0x00 // copyright bit, original bit
        buf.put(flags1.toByte())

        // PTS/DTS flags
        val flags2 = 0xC0.toByte()  // PTS + DTS present
        buf.put(flags2)

        // PES header length (remaining header bytes after this)
        buf.put(0x0A.toByte())  // 10 bytes for PTS(5) + DTS(5)

        // PTS: 5 bytes (33 bits + markers)
        writePts(buf, pts)

        // DTS: 5 bytes
        writePts(buf, dts)

        // PES payload
        buf.put(data)

        buf.flip()
        val result = ByteArray(buf.remaining())
        buf.get(result)
        return result
    }

    /**
     * Write PTS (Presentation Time Stamp) in 5-byte format.
     * Format: 4-bit prefix | 3 bits | 1 marker | 15 bits | 1 marker | 15 bits | 1 marker
     */
    private fun writePts(buf: ByteBuffer, pts: Long) {
        val p = pts and 0x1FFFFFFFFL  // 33 bits

        val byte0 = ((0x20 or ((p shr 30).toInt() and 0x07) or 0x01) and 0xFF).toByte()
        val byte1 = ((p shr 22).toInt() and 0xFF).toByte()
        val byte2 = (0x01 or ((p shr 15).toInt() and 0x7E)).toByte()
        val byte3 = ((p shr 7).toInt() and 0xFF).toByte()
        val byte4 = (0x01 or ((p shl 1).toInt() and 0x7E)).toByte()

        buf.put(byte0)
        buf.put(byte1)
        buf.put(byte2)
        buf.put(byte3)
        buf.put(byte4)
    }

    /**
     * Split payload data into 188-byte TS transport packets.
     */
    private fun splitIntoTransportPackets(
        payload: ByteArray,
        pid: Int,
        payloadUnitStart: Boolean
    ): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val cc = continuityCounters[pid] ?: AtomicInteger(0)
        var offset = 0
        var firstPacket = true

        while (offset < payload.size) {
            tsBuffer.clear()
            tsBuffer.limit(TS_PACKET_SIZE)

            // TS header (4 bytes)
            val payloadStart = firstPacket && payloadUnitStart
            val adaptationFieldNeeded = false  // We don't need adaptation field for most packets

            // Byte 1: sync byte
            tsBuffer.put(SYNC_BYTE)

            // Byte 2: transport_error(1) | payload_start(1) | transport_priority(1) | PID hi(5)
            val byte2 = (if (payloadStart) 0x40 else 0x00) or ((pid shr 8) and 0x1F)
            tsBuffer.put(byte2.toByte())

            // Byte 3: PID lo
            tsBuffer.put((pid and 0xFF).toByte())

            // Byte 4: scrambling(2) | adaptation(2) | continuity(4)
            val adaptationFieldControl = if (adaptationFieldNeeded) 0x03 else 0x01 // 01 = payload only, 11 = both
            tsBuffer.put(((adaptationFieldControl shl 4) or (cc.get() and 0x0F)).toByte())

            // Calculate payload capacity
            var payloadCapacity = TS_PACKET_SIZE - 4  // header
            if (adaptationFieldNeeded) {
                payloadCapacity -= 2  // minimal adaptation field (1 byte length + 1 byte flags)
                tsBuffer.put(0x01.toByte())  // adaptation field length = 1
                tsBuffer.put(0x00.toByte())  // flags = none
            }

            // PES header start: add a pointer field if this isn't the first TS packet
            if (firstPacket && payloadUnitStart) {
                // No pointer needed on first packet with PUSI
            } else if (payloadUnitStart && !firstPacket) {
                // This shouldn't normally happen with our simple approach
            }

            // Fill payload
            val remaining = payload.size - offset
            val chunkSize = minOf(payloadCapacity, remaining)
            tsBuffer.put(payload, offset, chunkSize)
            offset += chunkSize

            // Pad if needed
            val written = tsBuffer.position()
            while (tsBuffer.position() < TS_PACKET_SIZE) {
                tsBuffer.put(0xFF.toByte())
            }

            tsBuffer.flip()
            val packet = ByteArray(TS_PACKET_SIZE)
            tsBuffer.get(packet)
            packets.add(packet)

            cc.incrementAndGet()
            firstPacket = false
        }

        return packets
    }

    /**
     * Generate PCR (Program Clock Reference) timestamp.
     * PCR is based on 27MHz clock, expressed as base (33 bits at 90kHz) + extension (9 bits at 27MHz).
     */
    fun generatePcr(timestamp90kHz: Long): ByteArray {
        val pcrBase = timestamp90kHz and 0x1FFFFFFFFL
        val pcrExt = 0L  // Simplified: no extension

        val buf = ByteBuffer.allocate(6)
        // PCR base (33 bits) + reserved (6 bits) + PCR extension (9 bits)
        val byte0 = ((pcrBase shr 25).toInt() and 0xFF).toByte()
        val byte1 = ((pcrBase shr 17).toInt() and 0xFF).toByte()
        val byte2 = ((pcrBase shr 9).toInt() and 0xFF).toByte()
        val byte3 = ((pcrBase shr 1).toInt() and 0xFF).toByte()
        val byte4 = ((((pcrBase shl 7).toInt() and 0x80) or 0x7E) and 0xFF).toByte()  // reserved
        val byte5 = (((pcrExt.toInt() and 0x01) or 0xFE) and 0xFF).toByte()  // reserved

        buf.put(byte0)
        buf.put(byte1)
        buf.put(byte2)
        buf.put(byte3)
        buf.put(byte4)
        buf.put(byte5)

        buf.flip()
        val result = ByteArray(buf.remaining())
        buf.get(result)
        return result
    }

    /**
     * Get the current sequence number for a PID.
     */
    fun getContinuityCounter(pid: Int): Int {
        return continuityCounters[pid]?.get() ?: 0
    }

    /**
     * Reset the muxer state for a new streaming session.
     */
    fun reset() {
        continuityCounters.values.forEach { it.set(0) }
        packetCount = 0
    }
}
