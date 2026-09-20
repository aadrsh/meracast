package com.meracast.miracast.rtp

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.ui.DebugLogStore
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * RTP receiver — receives RTP packets and delivers them with
 * a jitter buffer, sequence ordering, and loss detection.
 *
 * Delivers video NALs and audio frames to callbacks, similar to
 * the existing raw-UDP receiver but with better robustness.
 */
class RtpReceiver {

    companion object {
        private const val TAG = "${AppConstants.TAG}.RtpRecv"
        private const val BUFFER_SIZE = 65536
        private const val JITTER_CAPACITY = 200  // max packets in jitter buffer
        private const val MAX_SEQ_DELTA = 500    // max gap before declaring loss
    }

    private var socket: DatagramSocket? = null
    private var isReceiving = false
    private var isActive = false

    // Jitter buffer — maps sequence number to packet
    private val jitterBuffer = sortedMapOf<Int, RtpPacket>()
    private var lastDeliveredSeq = -1
    private var totalReceived = 0L
    private var totalLost = 0L
    private var totalDuplicates = 0L

    // NAL reassembly state (FU-A fragments)
    private var pendingFragments = mutableListOf<ByteArray>()
    private var pendingNalType = -1
    private var pendingLastSeq = -1

    // Callbacks
    var onVideoNal: ((nalData: ByteArray, keyFrame: Boolean, ptsUs: Long) -> Unit)? = null
    var onAudioFrame: ((frameData: ByteArray, ptsUs: Long) -> Unit)? = null
    var onStats: ((received: Long, lost: Long, duplicates: Long) -> Unit)? = null

    /**
     * Start receiving on the given port.
     */
    fun start(port: Int) {
        if (isActive) return
        isActive = true
        jitterBuffer.clear()
        pendingFragments.clear()
        lastDeliveredSeq = -1
        totalReceived = 0
        totalLost = 0
        totalDuplicates = 0

        try {
            socket = DatagramSocket(port)
            socket?.soTimeout = 30000
            DebugLogStore.log("RTP: Receiver started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind receiver port $port", e)
            DebugLogStore.log("RTP: Bind failed — ${e.message}")
            isActive = false
        }
    }

    /**
     * Poll for incoming packets.  Call this in a loop from a coroutine.
     * Returns true if at least one packet was processed.
     */
    fun poll(): Boolean {
        val sock = socket ?: return false
        var processed = false

        try {
            // Read all available packets
            while (true) {
                val buf = ByteArray(BUFFER_SIZE)
                val pkt = DatagramPacket(buf, buf.size)
                sock.receive(pkt)

                val rtp = RtpPacket.fromBytes(buf, 0, pkt.length)
                if (rtp != null) {
                    totalReceived++
                    insertToJitterBuffer(rtp)
                    processed = true
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            // Timeout is normal — flush any available packets
        } catch (e: Exception) {
            if (isActive) {
                Log.w(TAG, "Receive error: ${e.message}")
            }
        }

        // Deliver packets from jitter buffer
        deliverReadyPackets()

        // Report stats periodically
        if (totalReceived % 100 == 0L) {
            onStats?.invoke(totalReceived, totalLost, totalDuplicates)
        }

        return processed
    }

    private fun insertToJitterBuffer(pkt: RtpPacket) {
        val seq = pkt.sequenceNumber

        // Handle sequence number wrapping (16-bit)
        val actualSeq = if (lastDeliveredSeq >= 0 && seq < lastDeliveredSeq - 10000) {
            seq + 65536
        } else seq

        // Check for duplicate
        if (jitterBuffer.containsKey(actualSeq) ||
            (lastDeliveredSeq >= 0 && actualSeq <= lastDeliveredSeq)) {
            totalDuplicates++
            return
        }

        // Detect loss
        if (lastDeliveredSeq >= 0 && actualSeq > lastDeliveredSeq + 1) {
            val lost = actualSeq - lastDeliveredSeq - 1
            totalLost += lost
        }

        // Add to jitter buffer
        jitterBuffer[actualSeq] = pkt

        // Trim if buffer gets too large
        while (jitterBuffer.size > JITTER_CAPACITY) {
            val first = jitterBuffer.firstEntry()
            deliver(first.key, first.value)
        }
    }

    private fun deliverReadyPackets() {
        // Deliver contiguous packets from the buffer
        while (true) {
            val next = lastDeliveredSeq + 1
            val pkt = jitterBuffer[next] ?: break
            deliver(next, pkt)
        }
    }

    private fun deliver(seq: Int, pkt: RtpPacket) {
        lastDeliveredSeq = seq
        jitterBuffer.remove(seq)

        val ptsUs = pkt.timestamp * 1_000_000L /
                if (pkt.payloadType == RtpPacket.PT_H264) RtpPacket.CLOCK_RATE_VIDEO
                else RtpPacket.CLOCK_RATE_AUDIO

        when (pkt.payloadType) {
            RtpPacket.PT_H264 -> handleH264(pkt, ptsUs)
            RtpPacket.PT_AAC -> onAudioFrame?.invoke(pkt.payload, ptsUs)
        }
    }

    private fun handleH264(pkt: RtpPacket, ptsUs: Long) {
        val payload = pkt.payload
        if (payload.isEmpty()) return

        val firstByte = payload[0].toInt() and 0xFF

        if ((firstByte and 0x1F) == 28) {
            // FU-A fragmentation unit
            handleFuA(payload, pkt, ptsUs)
        } else {
            // Single NAL unit
            onVideoNal?.invoke(payload, pkt.marker, ptsUs)
        }
    }

    private fun handleFuA(payload: ByteArray, pkt: RtpPacket, ptsUs: Long) {
        val fuIndicator = payload[0].toInt() and 0xFF
        val fuHeader = payload[1].toInt() and 0xFF
        val start = (fuHeader and 0x80) != 0
        val end = (fuHeader and 0x40) != 0
        val nalType = fuHeader and 0x1F

        if (start) {
            // Start of new fragmented NAL
            pendingFragments.clear()
            pendingNalType = nalType
            // Reconstruct NAL header from FU indicator and header
            val nri = (fuIndicator and 0x60)
            val nalHeader = byteArrayOf((nri or nalType).toByte())
            pendingFragments.add(nalHeader)
        }

        // Add fragment payload (skip the 2-byte FU indicator/header)
        pendingFragments.add(payload.copyOfRange(2, payload.size))
        pendingLastSeq = pkt.sequenceNumber

        if (end) {
            // Reassemble complete NAL
            val totalSize = pendingFragments.sumOf { it.size }
            val nal = ByteArray(totalSize)
            var offset = 0
            for (frag in pendingFragments) {
                System.arraycopy(frag, 0, nal, offset, frag.size)
                offset += frag.size
            }
            onVideoNal?.invoke(nal, pkt.marker, ptsUs)
            pendingFragments.clear()
            pendingNalType = -1
        }
    }

    fun stop() {
        isActive = false
        jitterBuffer.clear()
        pendingFragments.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        DebugLogStore.log("RTP: Receiver stopped (received=$totalReceived, lost=$totalLost, dupes=$totalDuplicates)")
    }
}
