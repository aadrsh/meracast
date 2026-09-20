package com.meracast.miracast.wfd

import android.util.Log
import com.meracast.common.AppConstants
import com.meracast.common.WfdCapabilities

/**
 * Large pre‑computed map of known Wi‑Fi Display implementation deviations.
 *
 * Each profile encodes the quirks vendors ship and applies automatic
 * workarounds during RTSP negotiation and media streaming so that
 * MiracastHub remains interoperable with the widest variety of sinks
 * and sources without the user having to configure anything.
 *
 * Detection is done by OUI prefix of the MAC address and/or the
 * device name string patterns that vendors consistently use.
 */
object WfdCompatibilityProfiles {

    private const val TAG = "${AppConstants.TAG}.CompatProfile"

    // ── Quirk flags (bitmask) ──────────────────────────────────────────

    /** Needs an extra blank line after M1 response */
    const val QUIRK_EXTRA_CRLF_M1           = 1L shl 0
    /** Sends SETUP without wfd_video_formats – fill with a reasonable default */
    const val QUIRK_MISSING_VIDEO_FORMATS   = 1L shl 1
    /** Expects the legacy wfd_audio_codecs 4‑field format (no count prefix) */
    const val QUIRK_LEGACY_AUDIO_CODECS     = 1L shl 2
    /** Source sends TEARDOWN before media stream can start – ignore first TEARDOWN */
    const val QUIRK_PREMATURE_TEARDOWN      = 1L shl 3
    /** Requires IDR request via SET_PARAMETER before stream starts */
    const val QUIRK_NEEDS_IDR_REQUEST       = 1L shl 4
    /** Uses non‑standard RTSP port 555 instead of 7236 */
    const val QUIRK_ALT_RTSP_PORT           = 1L shl 5
    /** Does not send M4 (SETUP response) capabilities – assume full support */
    const val QUIRK_NO_M4_CAPS              = 1L shl 6
    /** Sink expects UDP packets with a 4‑byte size prefix before each TS packet */
    const val QUIRK_UDP_LENGTH_PREFIX       = 1L shl 7
    /** Requires H.264 level 4.2 minimum (1080p60) */
    const val QUIRK_REQUIRES_H264_LEVEL_42  = 1L shl 8
    /** Audio must use LPCM even when AAC is advertised */
    const val QUIRK_FORCE_LPCM_AUDIO        = 1L shl 9
    /** Delays between RTSP messages must be at least 200 ms */
    const val QUIRK_RTSP_DELAY_200MS        = 1L shl 10
    /** Sends the WFD IE sub‑elements in inverse order */
    const val QUIRK_INVERTED_WFD_IE         = 1L shl 11
    /** Uses carriage return only (no line feed) in RTSP */
    const val QUIRK_RTSP_CR_ONLY            = 1L shl 12

    // ── Profile data class ─────────────────────────────────────────────

    data class Profile(
        /** Human‑readable vendor / model label */
        val label: String,
        /** Bitmask of active quirks */
        val quirks: Long,
        /** Alternative RTSP port if QUIRK_ALT_RTSP_PORT is set */
        val altRtspPort: Int = 0,
        /** Preferred video resolution width (or 0 for default) */
        val preferredWidth: Int = 0,
        /** Preferred video resolution height */
        val preferredHeight: Int = 0,
        /** Preferred H.264 bitrate */
        val preferredBitrate: Int = 0,
        /** H.264 profile override (0 = auto) */
        val h264ProfileOverride: Int = 0,
        /** H.264 level override (0 = auto) */
        val h264LevelOverride: Int = 0
    )

    /** Fallback profile (no quirks) */
    val DEFAULT = Profile(label = "Generic WFD", quirks = 0L)

    // ── Profile registry ───────────────────────────────────────────────

    /**
     * Registry of vendor profiles keyed by OUI prefix (first 3 hex octets
     * of the MAC address as an integer) OR by device‑name substring.
     */
    private val ouiProfiles: Map<Int, Profile> = buildMap {
        // Samsung
        put(0x00_1E_4D, Profile("Samsung TV / Phone",
            QUIRK_LEGACY_AUDIO_CODECS or QUIRK_PREMATURE_TEARDOWN,
            preferredBitrate = 8_000_000))
        put(0x00_23_D4, Profile("Samsung (older)",
            QUIRK_LEGACY_AUDIO_CODECS or QUIRK_EXTRA_CRLF_M1))

        // LG
        put(0x00_1E_5E, Profile("LG Smart TV",
            QUIRK_NO_M4_CAPS or QUIRK_MISSING_VIDEO_FORMATS))
        put(0x00_1A_5C, Profile("LG (older)",
            QUIRK_MISSING_VIDEO_FORMATS or QUIRK_LEGACY_AUDIO_CODECS))

        // Sony
        put(0x00_1B_66, Profile("Sony Bravia",
            QUIRK_NO_M4_CAPS or QUIRK_NEEDS_IDR_REQUEST))
        put(0x00_24_BE, Profile("Sony (newer)",
            QUIRK_NO_M4_CAPS))

        // Microsoft (Xbox / Surface / Wireless Display Adapter)
        put(0x00_15_5D, Profile("Microsoft WDA",
            QUIRK_REQUIRES_H264_LEVEL_42 or QUIRK_RTSP_DELAY_200MS,
            preferredWidth = 1920, preferredHeight = 1080,
            h264LevelOverride = WfdCapabilities.H264_LEVEL_4_2))
        put(0x00_22_48, Profile("Microsoft Xbox",
            QUIRK_REQUIRES_H264_LEVEL_42 or QUIRK_RTSP_DELAY_200MS))

        // Amazon Fire TV
        put(0x00_1C_36, Profile("Amazon Fire TV",
            QUIRK_PREMATURE_TEARDOWN or QUIRK_NEEDS_IDR_REQUEST))

        // Roku
        put(0x00_1A_7D, Profile("Roku",
            QUIRK_MISSING_VIDEO_FORMATS or QUIRK_NO_M4_CAPS))

        // Intel based (many wireless dongles)
        put(0x00_1B_77, Profile("Intel WiDi / dongle",
            QUIRK_UDP_LENGTH_PREFIX or QUIRK_RTSP_DELAY_200MS))

        // Realtek (many low-cost dongles)
        put(0x00_E0_4C, Profile("Realtek dongle",
            QUIRK_UDP_LENGTH_PREFIX))
        put(0x00_1D_C4, Profile("Realtek (older)",
            QUIRK_UDP_LENGTH_PREFIX or QUIRK_LEGACY_AUDIO_CODECS))

        // Broadcom based (built into many smart TVs)
        put(0x00_10_18, Profile("Broadcom / TV chipset",
            QUIRK_INVERTED_WFD_IE))
    }

    /** Profiles keyed by device‑name substring (checked after OUI) */
    private val namePatternProfiles: List<Pair<Regex, Profile>> = listOf(
        // Samsung
        Regex("(?i)samsung|sm-|galaxy") to Profile("Samsung (pattern)",
            QUIRK_LEGACY_AUDIO_CODECS or QUIRK_PREMATURE_TEARDOWN),
        // LG
        Regex("(?i)lg_?tv|webos|lgedla") to Profile("LG TV (pattern)",
            QUIRK_NO_M4_CAPS or QUIRK_MISSING_VIDEO_FORMATS),
        // Sony
        Regex("(?i)sony|bravia|xperia") to Profile("Sony (pattern)",
            QUIRK_NO_M4_CAPS or QUIRK_NEEDS_IDR_REQUEST),
        // Microsoft
        Regex("(?i)microsoft|wireless.?display|xbox|surface") to Profile("Microsoft (pattern)",
            QUIRK_REQUIRES_H264_LEVEL_42 or QUIRK_RTSP_DELAY_200MS),
        // Amazon
        Regex("(?i)amazon|fire.?tv|a?ftt") to Profile("Amazon (pattern)",
            QUIRK_PREMATURE_TEARDOWN or QUIRK_NEEDS_IDR_REQUEST),
        // Roku
        Regex("(?i)roku") to Profile("Roku (pattern)",
            QUIRK_MISSING_VIDEO_FORMATS or QUIRK_NO_M4_CAPS),
        // Generic Miracast dongle
        Regex("(?i)miracast.?dongle|widi") to Profile("Dongle (pattern)",
            QUIRK_UDP_LENGTH_PREFIX or QUIRK_RTSP_DELAY_200MS),
        // Generic Android with WFD
        Regex("(?i)android.?tv|android.?display") to Profile("Android TV (pattern)",
            QUIRK_NO_M4_CAPS),
        // SmartThings / Samsung dongle
        Regex("(?i)smart.?things|samsung.?display") to Profile("SmartThings",
            QUIRK_LEGACY_AUDIO_CODECS)
    )

    // ── Detection ──────────────────────────────────────────────────────

    /**
     * Detect the best‑matching profile for a given peer device.
     *
     * Strategy:
     * 1. Try OUI from MAC address (most reliable).
     * 2. Fall back to device‑name regex patterns.
     * 3. If nothing matches return [DEFAULT].
     */
    fun detect(deviceAddress: String, deviceName: String): Profile {
        // Step 1 – OUI lookup
        val oui = try {
            // MAC is like "aa:bb:cc:dd:ee:ff" → grab first 3 octets
            val octets = deviceAddress.split(":").take(3)
            if (octets.size == 3) {
                (octets[0].toInt(16) shl 16) or
                        (octets[1].toInt(16) shl 8) or
                        octets[2].toInt(16)
            } else -1
        } catch (_: Exception) { -1 }

        if (oui >= 0) {
            ouiProfiles[oui]?.let {
                Log.d(TAG, "OUI match 0x${oui.toString(6)} → ${it.label}")
                return it
            }
        }

        // Step 2 – name patterns
        val name = deviceName.ifEmpty { deviceAddress }
        for ((pattern, profile) in namePatternProfiles) {
            if (pattern.containsMatchIn(name)) {
                Log.d(TAG, "Name pattern match '$name' → ${profile.label}")
                return profile
            }
        }

        // Step 3 – fallback
        Log.d(TAG, "No profile matched for '$name' (OUI=0x${if (oui<0) "?" else oui.toString(6)}), using DEFAULT")
        return DEFAULT
    }

    /** Convenience: detect from a [WfdDevice] */
    fun detect(device: WfdDevice): Profile =
        detect(device.deviceAddress, device.deviceName)

    // ── Quirk helpers ──────────────────────────────────────────────────

    fun hasQuirk(profile: Profile, quirk: Long): Boolean =
        (profile.quirks and quirk) != 0L

    /** Return the RTSP port the profile wants (7236 unless alt is specified). */
    fun rtspPort(profile: Profile): Int =
        if (hasQuirk(profile, QUIRK_ALT_RTSP_PORT)) profile.altRtspPort
        else 7236

    /** Return the preferred resolution (or (0,0) for default). */
    fun preferredResolution(profile: Profile): Pair<Int, Int> =
        Pair(profile.preferredWidth, profile.preferredHeight)

    /** H.264 level mask with any profile‑required overrides applied. */
    fun effectiveH264Level(profile: Profile): Int {
        if (profile.h264LevelOverride != 0) return profile.h264LevelOverride
        return (WfdCapabilities.H264_LEVEL_4_1 or WfdCapabilities.H264_LEVEL_4_0) and 0xFF
    }

    /** H.264 profile mask with any profile‑required overrides applied. */
    fun effectiveH264Profile(profile: Profile): Int {
        if (profile.h264ProfileOverride != 0) return profile.h264ProfileOverride
        return (WfdCapabilities.H264_PROFILE_HIGH or
                WfdCapabilities.H264_PROFILE_MAIN or
                WfdCapabilities.H264_PROFILE_BASELINE) and 0xFF
    }

    /** Audio codecs RTSP string, applying quirks. */
    fun effectiveAudioCodecs(profile: Profile): String {
        if (hasQuirk(profile, QUIRK_FORCE_LPCM_AUDIO)) {
            return "01 01 01 0032"  // LPCM only
        }
        if (hasQuirk(profile, QUIRK_LEGACY_AUDIO_CODECS)) {
            return "01 01 0032"     // legacy 4‑field format
        }
        return "02 01 01 0032 02 0096"  // standard LPCM + AAC
    }
}
