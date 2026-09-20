package com.meracast.miracast.rtp

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.ui.DebugLogStore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * RTP sender — wraps raw data in RTP packets and sends over UDP.
 *
 * Replaces the existing raw-UDP TS streaming for better loss detection,
 * sequencing, and timestamp-based A/V sync.
 *
 * Usage:
 *   val sender = RtpSender()
 *   sender.start(sinkAddress, sinkPort)
 *   sender.sendVideo(h264Nal, ptsUs, isKeyFrame)
 *   sender.sendAudio(aacFrame, ptsUs)
 *   sender.stop()
 */
class RtpSender {

    companion object {
        private const val TAG = "${AppConstants.TAG}.RtpSend"
        private const val MTU = 1400    // safe UDP payload size
    }

    private var socket: DatagramSocket? = null
    private var sinkAddr: InetAddress? = null
    private var sinkPort: Int = 19000
    private var videoSeq = 0
    private var audioSeq = 0
    private var isActive = false

    /**
     * Prepare the sender.  Call before sending any packets.
     */
    fun start(address: InetAddress, port: Int) {
        sinkAddr = address
        sinkPort = port
        socket = DatagramSocket()
        isActive = true
        videoSeq = 0
        audioSeq = 0
        DebugLogStore.log("RTP: Sender started → $address:$port")
    }

    /**
     * Send an H.264 NAL unit as one or more RTP packets.
     *
     * @param nalData  The complete H.264 NAL unit
     * @param ptsUs    Presentation timestamp in microseconds
     * @param keyFrame Whether this is a key frame (IDR)
     */
    fun sendVideo(nalData: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!isActive) return

        val ts90kHz = (ptsUs * RtpPacket.CLOCK_RATE_VIDEO / 1_000_000L) and 0xFFFFFFFFL
        val isSingle = nalData.size <= MTU

        if (isSingle) {
            // Single NAL unit packet (STAP not needed for small NALs)
            val pkt = RtpPacket(
                marker = keyFrame,
                payloadType = RtpPacket.PT_H264,
                sequenceNumber = videoSeq++,
                timestamp = ts90kHz,
                ssrc = RtpPacket.DEFAULT_SSRC,
                payload = nalData
            )
            sendPacket(pkt)
        } else {
            // Fragmentation Unit (FU-A) for large NALs
            val nalHeader = nalData[0].toInt() and 0xFF
            val nri = nalHeader and 0x60
            val nalType = nalHeader and 0x1F
            // FU indicator byte
            val fuIndicator = (0x1C) or (nri shr 5)  // FU-A type = 28
            val payload = nalData.copyOfRange(1, nalData.size)  // remove NAL header
            var offset = 0
            var fragSeq = 0

            while (offset < payload.size) {
                val fragSize = minOf(MTU - 2, payload.size - offset)
                val start = offset == 0
                val end = (offset + fragSize) >= payload.size
                val fuHeader = (if (start) 0x80 else 0) or (if (end) 0x40 else 0) or nalType
                val fragPayload = ByteArray(2 + fragSize)
                fragPayload[0] = fuIndicator.toByte()
                fragPayload[1] = fuHeader.toByte()
                System.arraycopy(payload, offset, fragPayload, 2, fragSize)

                val pkt = RtpPacket(
                    marker = (start && keyFrame) || end,
                    payloadType = RtpPacket.PT_H264,
                    sequenceNumber = videoSeq++,
                    timestamp = ts90kHz,
                    ssrc = RtpPacket.DEFAULT_SSRC,
                    payload = fragPayload
                )
                sendPacket(pkt)
                offset += fragSize
                fragSeq++
            }
        }
    }

    /**
     * Send an AAC audio frame as an RTP packet.
     *
     * @param aacFrame  AAC frame data (ADTS or raw)
     * @param ptsUs     Presentation timestamp in microseconds
     */
    fun sendAudio(aacFrame: ByteArray, ptsUs: Long) {
        if (!isActive) return

        val ts48kHz = (ptsUs * RtpPacket.CLOCK_RATE_AUDIO / 1_000_000L) and 0xFFFFFFFFL
        val pkt = RtpPacket(
            marker = false,
            payloadType = RtpPacket.PT_AAC,
            sequenceNumber = audioSeq++,
            timestamp = ts48kHz,
            ssrc = RtpPacket.DEFAULT_SSRC,
            payload = aacFrame
        )
        sendPacket(pkt)
    }

    private fun sendPacket(pkt: RtpPacket) {
        try {
            val bytes = pkt.toBytes()
            val dp = DatagramPacket(bytes, bytes.size, sinkAddr, sinkPort)
            socket?.send(dp)
        } catch (e: Exception) {
            Log.w(TAG, "Send error: ${e.message}")
        }
    }

    fun stop() {
        isActive = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        DebugLogStore.log("RTP: Sender stopped")
    }
}
