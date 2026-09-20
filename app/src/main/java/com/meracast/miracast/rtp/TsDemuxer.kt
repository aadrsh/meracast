package com.meracast.miracast.rtp

import android.util.Log
import com.meracast.common.AppConstants
import java.nio.ByteBuffer

/**
 * MPEG2-TS Demuxer for Wi-Fi Display (Miracast).
 *
 * Receives 188-byte MPEG2-TS packets and extracts:
 * - H.264 video NAL units (from PES packets on video PID)
 * - AAC audio frames (from PES packets on audio PID)
 *
 * Processes PAT/PMT to discover stream PIDs, then demuxes PES payloads.
 */
class TsDemuxer {

    companion object {
        private const val TAG = "${AppConstants.TAG}.TSDemux"
        private const val TS_PACKET_SIZE = 188
        private const val SYNC_BYTE: Byte = 0x47

        // Default PIDs (fallback before PMT parsed)
        private const val PID_PAT = 0x0000
        private const val DEFAULT_VIDEO_PID = 0x1011
        private const val DEFAULT_AUDIO_PID = 0x1100

        // Table IDs
        private const val TABLE_ID_PAT = 0x00
        private const val TABLE_ID_PMT = 0x02

        // Stream types
        private const val STREAM_H264 = 0x1B
        private const val STREAM_AAC = 0x0F

        // PES stream IDs
        private const val PES_VIDEO_START = 0xE0
        private const val PES_AUDIO_START = 0xC0
    }

    // Demuxer state
    private var videoPid = DEFAULT_VIDEO_PID
    private var audioPid = DEFAULT_AUDIO_PID
    private var patParsed = false
    private var pmtParsed = false
    private var pmtPid = -1  // Discovered from PAT, -1 = unknown

    // PES reassembly buffers per PID
    private val pesBuffers = mutableMapOf<Int, ByteArrayBuilder>()
    private var currentPesPacket: ByteArrayBuilder? = null
    private var currentPesStreamId: Int = 0
    private var pesPacketLength: Int = 0
    private var pesHeaderLength: Int = 0
    private var pesBytesCollected: Int = 0
    private var expectingPesHeader: Boolean = true

    // Callbacks for demuxed data
    var onVideoNal: ((ByteArray, Boolean, Long) -> Unit)? = null
    var onAudioFrame: ((ByteArray, Long) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    init {
        pesBuffers[PID_PAT] = ByteArrayBuilder()
        pesBuffers[DEFAULT_VIDEO_PID] = ByteArrayBuilder()
        pesBuffers[DEFAULT_AUDIO_PID] = ByteArrayBuilder()
    }

    /**
     * Feed a 188-byte MPEG2-TS packet for processing.
     *
     * @param packet Exactly 188 bytes of TS data
     */
    fun feedPacket(packet: ByteArray) {
        if (packet.size < TS_PACKET_SIZE) {
            Log.w(TAG, "Invalid packet size: ${packet.size}")
            return
        }

        if (packet[0] != SYNC_BYTE.toByte()) {
            // Try to resync
            onError?.invoke("Lost TS sync")
            return
        }

        val buf = ByteBuffer.wrap(packet)

        // Read TS header
        val sync = buf.get().toInt() and 0xFF  // 0x47
        val byte2 = buf.get().toInt() and 0xFF
        val byte3 = buf.get().toInt() and 0xFF
        val byte4 = buf.get().toInt() and 0xFF

        val transportError = (byte2 and 0x80) != 0
        val payloadStart = (byte2 and 0x40) != 0
        val pid = ((byte2 and 0x1F) shl 8) or byte3
        val adaptationControl = (byte4 shr 4) and 0x03
        val continuityCounter = byte4 and 0x0F

        if (transportError) return

        // Skip adaptation field if present
        var payloadOffset = 4
        if (adaptationControl == 0x02 || adaptationControl == 0x03) {
            val adaptLen = buf.get().toInt() and 0xFF
            payloadOffset += 1 + adaptLen
            buf.position(payloadOffset)
        }

        val payloadLen = TS_PACKET_SIZE - payloadOffset
        if (payloadLen <= 0) return

        // Read payload
        val payload = ByteArray(payloadLen)
        buf.get(payload)

        // Route based on PID
        when (pid) {
            PID_PAT -> processPat(payload, payloadStart)
            pmtPid -> processPmt(payload, payloadStart)       // dynamic PMT PID
            videoPid, audioPid -> processPesPacket(pid, payload, payloadStart)
            DEFAULT_AUDIO_PID, DEFAULT_VIDEO_PID -> processPesPacket(pid, payload, payloadStart)
            else -> {
                // Unknown PID – could be PMT before PAT was parsed
                if (!pmtParsed && pmtPid == -1) {
                    // Try treating it as PMT (heuristic: first non-PAT/PES table)
                }
            }
        }
    }

    /**
     * Process a PAT (Program Association Table).
     */
    private fun processPat(payload: ByteArray, payloadStart: Boolean) {
        if (!payloadStart) return

        try {
            var offset = 0

            // Skip pointer field if present
            if (payload[offset].toInt() and 0xFF == 0x00) {
                offset++
            } else {
                val pointer = payload[offset].toInt() and 0xFF
                offset += pointer + 1
                if (offset >= payload.size) return
            }

            // table_id
            val tableId = payload[offset].toInt() and 0xFF
            offset++

            if (tableId != TABLE_ID_PAT) return

            // section_length (skip 2 bytes)
            offset += 2
            // transport_stream_id
            offset += 2
            // version + current_next
            offset++
            // section_number + last_section_number
            offset += 2

            // Read programs until CRC
            while (offset < payload.size - 4) {  // -4 for CRC
                val progNum = ((payload[offset].toInt() and 0xFF) shl 8) or
                        (payload[offset + 1].toInt() and 0xFF)
                if (progNum == 0) {
                    offset += 4
                    continue  // NIT
                }
                val pmtPid = ((payload[offset + 2].toInt() and 0x1F) shl 8) or
                        (payload[offset + 3].toInt() and 0xFF)
                offset += 4

                Log.d(TAG, "PAT: program $progNum -> PMT PID 0x${pmtPid.toString(16)}")
                this.pmtPid = pmtPid  // Store so feedPacket routes PMT packets
                patParsed = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing PAT", e)
        }
    }

    /**
     * Process a PMT (Program Map Table) section.
     *
     * Extracts the elementary-stream PIDs for H.264 video and AAC audio
     * so that subsequent TS packets are routed to the correct streams.
     */
    private fun processPmt(payload: ByteArray, payloadStart: Boolean) {
        if (!payloadStart) return  // Only parse on payload unit start
        try {
            var offset = 0

            // Skip pointer field
            if (offset < payload.size && payload[offset].toInt() and 0xFF == 0) offset++
            else return

            if (offset + 3 > payload.size) return
            val tableId = payload[offset].toInt() and 0xFF; offset++
            if (tableId != TABLE_ID_PMT) return

            // section_length (skip)
            offset += 2
            if (offset + 4 > payload.size) return
            // program_number
            offset += 2
            // version + current_next
            offset++
            // section_number + last_section_number
            offset += 2
            if (offset + 2 > payload.size) return
            // PCR PID
            offset += 2
            if (offset + 2 > payload.size) return
            // program_info_length
            val infoLen = ((payload[offset].toInt() and 0x0F) shl 8) or
                    (payload[offset + 1].toInt() and 0xFF)
            offset += 2 + infoLen  // skip descriptors

            // Parse elementary streams until CRC (last 4 bytes)
            while (offset + 4 <= payload.size - 4) {
                val streamType = payload[offset].toInt() and 0xFF; offset++
                if (offset + 2 > payload.size) break
                val elemPid = ((payload[offset].toInt() and 0x1F) shl 8) or
                        (payload[offset + 1].toInt() and 0xFF); offset++

                if (offset + 2 > payload.size) break
                val esInfoLen = ((payload[offset].toInt() and 0x0F) shl 8) or
                        (payload[offset + 1].toInt() and 0xFF); offset++

                // Skip ES info descriptors
                offset += esInfoLen

                when (streamType) {
                    STREAM_H264 -> {
                        videoPid = elemPid
                        Log.d(TAG, "PMT: video PID→0x${videoPid.toString(16)}")
                    }
                    STREAM_AAC -> {
                        audioPid = elemPid
                        Log.d(TAG, "PMT: audio PID→0x${audioPid.toString(16)}")
                    }
                }
            }

            pmtParsed = true
            Log.d(TAG, "PMT parsed: video=0x${videoPid.toString(16)} audio=0x${audioPid.toString(16)}")
        } catch (e: Exception) {
            Log.w(TAG, "PMT parse error", e)
        }
    }

    /**
     * Process payload data for PES packets.
     */
    private fun processPesPacket(pid: Int, payload: ByteArray, payloadStart: Boolean) {
        try {
            // Handle PES packet assembly
            if (payloadStart) {
                // This is the start of a new PES packet
                // Flush any previous incomplete PES packet
                flushPesPacket(pid)

                // Parse PES header
                var offset = 0

                // Check for PES start code prefix (0x00 0x00 0x01)
                if (offset + 3 <= payload.size &&
                    payload[offset] == 0x00.toByte() &&
                    payload[offset + 1] == 0x00.toByte() &&
                    payload[offset + 2] == 0x01.toByte()
                ) {
                    offset += 3

                    val streamId = payload[offset].toInt() and 0xFF
                    offset++
                    currentPesStreamId = streamId

                    // PES packet length
                    val pesLen = ((payload[offset].toInt() and 0xFF) shl 8) or
                            (payload[offset + 1].toInt() and 0xFF)
                    offset += 2

                    if (pesLen == 0) {
                        pesPacketLength = Int.MAX_VALUE  // Unbounded
                    } else {
                        pesPacketLength = pesLen
                    }

                    // Skip PES header data
                    val flags1 = payload[offset].toInt() and 0xFF
                    offset++

                    val flags2 = payload[offset].toInt() and 0xFF
                    offset++

                    val headerDataLen = payload[offset].toInt() and 0xFF
                    offset++

                    // Skip header data
                    offset += headerDataLen

                    // Extract PTS if present
                    var pts: Long = 0
                    if ((flags2 and 0x80) != 0) {
                        // PTS present
                        if (offset + 5 <= payload.size) {
                            pts = readPts(payload, offset)
                            offset += 5
                        }
                    }

                    // Remaining payload is PES packet data
                    val remaining = payload.size - offset
                    if (remaining > 0) {
                        val data = ByteArray(remaining)
                        System.arraycopy(payload, offset, data, 0, remaining)

                        deliverPesData(streamId, data, pts)
                    }
                }
            } else {
                // Continuation of a PES packet
                val streamId = currentPesStreamId
                deliverPesData(streamId, payload, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error processing PES on PID 0x${pid.toString(16)}", e)
            currentPesPacket = null
            expectingPesHeader = true
        }
    }

    /**
     * Deliver PES payload data to the appropriate handler.
     */
    private fun deliverPesData(streamId: Int, data: ByteArray, pts: Long) {
        when {
            streamId in 0xE0..0xEF -> {
                // Video stream
                // H.264 NAL units start with 0x00 0x00 0x01 or 0x00 0x00 0x00 0x01
                val isKeyFrame = detectKeyFrame(data)
                onVideoNal?.invoke(data, isKeyFrame, pts)
            }
            streamId in 0xC0..0xDF -> {
                // Audio stream
                onAudioFrame?.invoke(data, pts)
            }
        }
    }

    /**
     * Flush any pending PES packet (incomplete).
     */
    private fun flushPesPacket(pid: Int) {
        currentPesPacket = null
        currentPesStreamId = 0
        pesBytesCollected = 0
    }

    /**
     * Detect if H.264 data contains a key frame (IDR).
     * Checks for NAL unit type 5 (IDR) or 7 (SPS).
     */
    private fun detectKeyFrame(data: ByteArray): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0x00.toByte() && data[i + 1] == 0x00.toByte()) {
                val nalType: Int
                if (data[i + 2] == 0x00.toByte() && data[i + 3] == 0x01.toByte()) {
                    // 4-byte start code
                    if (i + 4 < data.size) {
                        nalType = data[i + 4].toInt() and 0x1F
                        if (nalType == 5) return true // IDR
                    }
                    i += 4
                } else if (data[i + 2] == 0x01.toByte()) {
                    // 3-byte start code
                    if (i + 3 < data.size) {
                        nalType = data[i + 3].toInt() and 0x1F
                        if (nalType == 5) return true // IDR
                    }
                    i += 3
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        return false
    }

    /**
     * Read PTS from the 5-byte PTS field.
     */
    private fun readPts(data: ByteArray, offset: Int): Long {
        if (offset + 5 > data.size) return 0

        val b0 = data[offset].toLong() and 0xFF
        val b1 = data[offset + 1].toLong() and 0xFF
        val b2 = data[offset + 2].toLong() and 0xFF
        val b3 = data[offset + 3].toLong() and 0xFF
        val b4 = data[offset + 4].toLong() and 0xFF

        return ((b0 shr 1) and 0x07) shl 30 or
                ((b1 shl 8) or b2 shr 1) and 0x3FFF shl 15 or
                ((b3 shl 8) or b4 shr 1) and 0x7FFF
    }

    /**
     * Reset the demuxer state for a new streaming session.
     */
    fun reset() {
        videoPid = DEFAULT_VIDEO_PID
        audioPid = DEFAULT_AUDIO_PID
        pmtPid = -1
        patParsed = false
        pmtParsed = false
        pesBuffers.values.forEach { it.reset() }
        currentPesPacket = null
        expectingPesHeader = true
        pesBytesCollected = 0
    }

    /**
     * Simple byte array builder for reassembly.
     */
    private class ByteArrayBuilder {
        private val chunks = mutableListOf<ByteArray>()
        private var totalSize = 0

        fun append(data: ByteArray) {
            chunks.add(data)
            totalSize += data.size
        }

        fun toByteArray(): ByteArray {
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, result, offset, chunk.size)
                offset += chunk.size
            }
            return result
        }

        fun size(): Int = totalSize

        fun reset() {
            chunks.clear()
            totalSize = 0
        }
    }
}
