package com.meracast.miracast.wfd

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdCapabilities
import com.meracast.ui.DebugLogStore
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Handles WFD (Wi-Fi Display) capability negotiation.
 *
 * Parses and generates WFD Information Element (IE) payloads
 * used in P2P service discovery and RTSP capability exchange.
 */
object WfdCapabilityNegotiator {

    private const val TAG = "${AppConstants.TAG}.WFDNeg"

    // WFD IE subelement IDs
    private const val SUBELEMENT_DEVICE_INFO = 0x00
    private const val SUBELEMENT_AUDIO_CODECS = 0x01
    private const val SUBELEMENT_VIDEO_CODECS = 0x02
    private const val SUBELEMENT_RTP_PORTS = 0x06
    private const val SUBELEMENT_LOCAL_IP = 0x07

    // WFD Device Information flags
    private const val FLAG_SOURCE = 0x01
    private const val FLAG_PRIMARY_SINK = 0x02
    private const val FLAG_SECONDARY_SINK = 0x04
    private const val FLAG_DUAL_ROLE = FLAG_SOURCE or FLAG_PRIMARY_SINK

    /** Convert a byte array to a colon-separated hex string for logging. */
    private fun bytesToHex(data: ByteArray): String =
        data.joinToString(":") { String.format("%02X", it) }

    /**
     * Generate WFD Device Information subelement.
     * @param deviceType 0=source, 1=primary sink, 2=dual role
     * @param sessionAvailable 1 if session is available
     * @param maxSessionCount max concurrent sessions (1-5)
     */
    fun createDeviceInfoSubelement(
        deviceType: Int = WfdCapabilities.DEVICE_TYPE_DUAL_ROLE,
        sessionAvailable: Int = 1,
        maxSessionCount: Int = 1
    ): ByteArray {
        val flags = when (deviceType) {
            WfdCapabilities.DEVICE_TYPE_SOURCE -> FLAG_SOURCE
            WfdCapabilities.DEVICE_TYPE_PRIMARY_SINK -> FLAG_PRIMARY_SINK
            WfdCapabilities.DEVICE_TYPE_SECONDARY_SINK -> FLAG_SECONDARY_SINK
            else -> FLAG_DUAL_ROLE  // Dual role
        }

        val buf = ByteBuffer.allocate(6).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            // Subelement ID
            putShort(SUBELEMENT_DEVICE_INFO.toShort())
            // Subelement length (2 bytes of payload)
            putShort(2)
            // Payload: 2 bytes
            putShort(((flags and 0x0F) or ((sessionAvailable and 0x01) shl 4) or ((maxSessionCount and 0x07) shl 5)).toShort())
        }

        val result = buf.array()
        Log.d(TAG, "WFD IE Device Info bytes: ${bytesToHex(result)} (flags=$flags, sessionAvail=$sessionAvailable, maxSession=$maxSessionCount)")
        DebugLogStore.log("WFD: Device Info IE = ${bytesToHex(result)}")
        DebugLogStore.log("WFD:   Device type = ${when (deviceType) { 0->"Source";1->"PrimarySink";2->"SecondarySink";3->"DualRole";else->"$deviceType" }}")
        DebugLogStore.log("WFD:   Session = ${if (sessionAvailable>0)"available" else "unavailable"}, max=$maxSessionCount concurrent")
        return result
    }

    /**
     * Generate WFD Audio Codec subelement.
     * Prioritizes AAC, then LPCM.
     * @param aacSupported true if AAC encoding is available
     * @param lpcmSupported true if LPCM is available
     * @param ac3Supported true if AC3 is available
     */
    fun createAudioCodecsSubelement(
        aacSupported: Boolean = true,
        lpcmSupported: Boolean = true,
        ac3Supported: Boolean = false
    ): ByteArray {
        val stream = ByteArrayOutputStream()

        // Subelement header
        writeShort(stream, SUBELEMENT_AUDIO_CODECS)

        // Count of audio codec structures
        val codecCount = listOf(aacSupported, lpcmSupported, ac3Supported).count { it }
        writeShort(stream, 3 + (codecCount * 6))  // length = 3 bytes header + N * 6 bytes per codec

        // Reserved byte
        stream.write(0)

        // Number of codecs following
        stream.write(codecCount)

        // Codec structures (6 bytes each): type (1), mode (1), latency (4)
        if (lpcmSupported) {
            stream.write(0x01)    // LPCM
            stream.write(0x01)    // 16-bit, 48kHz
            writeInt(stream, 50) // max latency
        }
        if (aacSupported) {
            stream.write(0x02)    // AAC
            stream.write(0x01)    // AAC LC
            writeInt(stream, 150) // max latency (ms)
        }
        if (ac3Supported) {
            stream.write(0x04)    // AC3
            stream.write(0x01)    // mode
            writeInt(stream, 150)
        }

        val audioBytes = stream.toByteArray()
        Log.d(TAG, "WFD IE Audio Codecs bytes: ${bytesToHex(audioBytes)} (lpcm=$lpcmSupported, aac=$aacSupported, ac3=$ac3Supported)")
        DebugLogStore.log("WFD: Audio Codecs IE = ${bytesToHex(audioBytes)}")
        DebugLogStore.log("WFD:   Codecs: ${listOfNotNull("LPCM".takeIf { lpcmSupported }, "AAC".takeIf { aacSupported }, "AC3".takeIf { ac3Supported }).joinToString(", ")}")
        return audioBytes
    }

    /**
     * Generate WFD Video Codec subelement for H.264.
     * @param profiles bitmask of supported H.264 profiles
     * @param levels bitmask of supported H.264 levels
     * @param maxHRes maximum horizontal resolution
     * @param maxVRes maximum vertical resolution
     */
    fun createVideoCodecsSubelement(
        profiles: Int = WfdCapabilities.H264_PROFILE_HIGH or
                WfdCapabilities.H264_PROFILE_MAIN or
                WfdCapabilities.H264_PROFILE_BASELINE,
        levels: Int = WfdCapabilities.H264_LEVEL_4_1 or
                WfdCapabilities.H264_LEVEL_3_1,
        maxHRes: Int = 1920,
        maxVRes: Int = 1080,
        maxFps: Int = 30
    ): ByteArray {
        val stream = ByteArrayOutputStream()

        // Subelement header
        writeShort(stream, SUBELEMENT_VIDEO_CODECS)

        // Length: 3 (header) + 1 (reserved) + 1 (codec count) + N * 14 (per codec entry)
        val codecBytes = 14
        writeShort(stream, 4 + codecBytes)  // only H.264

        // Reserved
        stream.write(0x00)

        // Number of codecs
        stream.write(0x01)  // only H.264

        // H.264 codec structure (14 bytes)
        stream.write(0x01)     // Codec type: H.264
        stream.write(profiles) // Profiles bitmask
        stream.write(levels)   // Levels bitmask

        // Custom rates support (4 bytes) - ceiling, reserved, reserved, reserved
        writeInt(stream, (maxFps and 0x7F) or (1 shl 7)) // ceiling 30fps + supports export

        // Maximum H resolution (2 bytes)
        writeShort(stream, maxHRes / 16)

        // Maximum V resolution (2 bytes)
        writeShort(stream, maxVRes / 16)

        // Reserved (2 bytes)
        writeShort(stream, 0x0000)

        val videoBytes = stream.toByteArray()
        Log.d(TAG, "WFD IE Video Codecs bytes: ${bytesToHex(videoBytes)} (profiles=$profiles, levels=$levels, ${maxHRes}x${maxVRes}@${maxFps}fps)")
        DebugLogStore.log("WFD: Video Codecs IE = ${bytesToHex(videoBytes)}")
        val profileNames = listOfNotNull(
            "Baseline".takeIf { profiles and WfdCapabilities.H264_PROFILE_BASELINE != 0 },
            "Main".takeIf { profiles and WfdCapabilities.H264_PROFILE_MAIN != 0 },
            "High".takeIf { profiles and WfdCapabilities.H264_PROFILE_HIGH != 0 }
        ).joinToString("/")
        DebugLogStore.log("WFD:   H.264: $profileNames, ${maxHRes}x${maxVRes}@${maxFps}fps")
        return videoBytes
    }

    /**
     * Generate the "wfd_video_formats" RTSP header value from our capabilities.
     * Format: none count profile level ceiling_4bytes resolution_4bytes
     */
    fun createVideoFormatsRtspHeader(
        nativeProfiles: Int = WfdCapabilities.H264_PROFILE_HIGH,
        nativeLevels: Int = WfdCapabilities.H264_LEVEL_4_1 or WfdCapabilities.H264_LEVEL_4_0,
        maxWidth: Int = 1920,
        maxHeight: Int = 1080,
        fps: Int = 30
    ): String {
        // WFD video formats string:
        val sb = StringBuilder()

        // Number of native video formats (we support 1: H.264)
        sb.append("01")

        // H264 profile (1 byte as hex): merge supported profiles
        sb.append(String.format(" %02x", nativeProfiles))

        // H264 level (1 byte as hex): merge supported levels
        sb.append(String.format(" %02x", nativeLevels))

        // Ceiling: (Native | Export) flag + FPS
        val ceilingValue = (1 shl 7) or // export supported
                (fps and 0x7F)          // max fps
        sb.append(String.format(" %04x", ceilingValue))

        // Resolution: width/16 << 8 | height/16
        val resValue = ((maxWidth / 16) shl 8) or (maxHeight / 16)
        sb.append(String.format(" %04x", resValue))

        Log.d(TAG, "Video formats header: $sb")
        return sb.toString()
    }

    /**
     * Create "wfd_audio_codecs" RTSP header value.
     * Format: count type mode latency
     */
    fun createAudioCodecsRtspHeader(
        aacSupported: Boolean = true,
        lpcmSupported: Boolean = true
    ): String {
        val sb = StringBuilder()
        val codecs = mutableListOf<String>()

        if (lpcmSupported) {
            // LPCM: type=1, mode=1 (16-bit 48kHz), latency=50ms
            codecs.add("01 01 0032")
        }
        if (aacSupported) {
            // AAC: type=2, mode=1 (AAC LC), latency=150ms
            codecs.add("02 01 0096")
        }

        sb.append(codecs.size.toString().padStart(2, '0'))

        if (codecs.isNotEmpty()) {
            sb.append(" ")
            sb.append(codecs.joinToString(" "))
        }

        Log.d(TAG, "Audio codecs header: $sb")
        return sb.toString()
    }

    /**
     * Create "wfd_client_rtp_ports" header.
     * Format: port_number mode (1 byte)
     */
    fun createRtpPortsHeader(port: Int = 19000): String {
        return "$port 01" // mode 01 = UDP
    }

    /**
     * Parse the WFD device info from a subelement byte array.
     */
    fun parseDeviceInfo(data: ByteArray): DeviceInfoResult? {
        return try {
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val subelementId = buf.getShort()
            val subelementLen = buf.getShort()

            if (subelementId != SUBELEMENT_DEVICE_INFO.toShort() || subelementLen < 2) {
                return null
            }

            val payload = buf.getShort()
            val flags = payload.toInt() and 0x0F
            val sessionAvailable = (payload.toInt() shr 4) and 0x01
            val maxSessionCount = (payload.toInt() shr 5) and 0x07

            val deviceType = when {
                flags == FLAG_SOURCE -> "Source"
                flags == FLAG_PRIMARY_SINK -> "PrimarySink"
                flags == FLAG_SECONDARY_SINK -> "SecondarySink"
                flags == FLAG_DUAL_ROLE -> "DualRole"
                else -> "Unknown($flags)"
            }

            DeviceInfoResult(deviceType, sessionAvailable == 1, maxSessionCount.toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse device info", e)
            null
        }
    }

    data class DeviceInfoResult(
        val deviceType: String,
        val sessionAvailable: Boolean,
        val maxSessionCount: Int
    )

    // Helper writers
    private fun writeShort(stream: ByteArrayOutputStream, value: Int) {
        stream.write((value shr 8) and 0xFF)
        stream.write(value and 0xFF)
    }

    private fun writeInt(stream: ByteArrayOutputStream, value: Int) {
        stream.write((value shr 24) and 0xFF)
        stream.write((value shr 16) and 0xFF)
        stream.write((value shr 8) and 0xFF)
        stream.write(value and 0xFF)
    }
}
