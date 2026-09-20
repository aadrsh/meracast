package com.meracast.common

/** Well-known Wi-Fi Display ports */
object WfdPorts {
    /** RTSP control port on sink side (WFD specification) */
    const val RTSP_PORT = 7236

    /** Default UDP port range start for MPEG2-TS media stream */
    const val UDP_PORT_START = 19000

    /** Default RTP port range end */
    const val UDP_PORT_END = 19010
}

/** WFD (Wi-Fi Display) capability constants */
object WfdCapabilities {
    // WFD Device Information Element subelements
    const val WFD_DEVICE_INFO = 0x00
    const val WFD_AUDIO_CODECS = 0x01
    const val WFD_VIDEO_CODECS = 0x02
    const val WFD_3D_VIDEO_FORMATS = 0x03
    const val WFD_CONTENT_PROTECTION = 0x04
    const val WFD_COUPLED_SINK = 0x05
    const val WFD_EXTENDED_CAPABILITY = 0x06
    const val WFD_LOCAL_IP_ADDRESS = 0x07
    const val WFD_AUXILIARY_STREAMS = 0x08
    const val WFD_CONNECTED_SINK_INFO = 0x09
    const val WFD_SESSION_AVAILABILITY_INFO = 0x0A
    const val WFD_MAX_SUBELEMENT = 0x0B

    // Device types
    const val DEVICE_TYPE_SOURCE = 0
    const val DEVICE_TYPE_PRIMARY_SINK = 1
    const val DEVICE_TYPE_SECONDARY_SINK = 2
    const val DEVICE_TYPE_DUAL_ROLE = 3

    // Audio codec types (WFD spec)
    const val AUDIO_LPCM = 0x00000001
    const val AUDIO_AAC = 0x00000002
    const val AUDIO_AC3 = 0x00000004

    // Video codec types
    const val VIDEO_H264 = 0x00000001

    // H.264 profiles
    const val H264_PROFILE_BASELINE = 0x01
    const val H264_PROFILE_MAIN = 0x02
    const val H264_PROFILE_HIGH = 0x04

    // H.264 levels (multiplier for max bitrate calc)
    const val H264_LEVEL_3_1 = 0x08   // 1080p @ 30fps approx
    const val H264_LEVEL_4_0 = 0x10   // 1080p @ 30fps
    const val H264_LEVEL_4_1 = 0x20   // 1080p @ 24fps
    const val H264_LEVEL_4_2 = 0x40   // 1080p @ 60fps
}

/** WFD RTSP header field names */
object WfdHeaders {
    const val WFD_AUDIO_CODECS = "wfd_audio_codecs"
    const val WFD_VIDEO_FORMATS = "wfd_video_formats"
    const val WFD_3D_FORMATS = "wfd_3d_video_formats"
    const val WFD_CONTENT_PROTECTION = "wfd_content_protection"
    const val WFD_DISPLAY_EDID = "wfd_display_edid"
    const val WFD_CLIENT_RTP_PORTS = "wfd_client_rtp_ports"
    const val WFD_PRESENTATION_URL = "wfd_presentation_url"
    const val WFD_TRIGGER_METHOD = "wfd_trigger_method"
    const val WFD_CURRENT_SESSION_ID = "wfd_current_session_id"
    const val WFD_IDR_REQUEST = "wfd_idr_request"
    const val WFD_ROUTED_DRIVER_ADDR = "wfd_routed_driver_addr"
    const val WFD_UIBBC_CONFIG = "wfd_uibbc_config"
    const val WFD_STANDBY_RESUME_CAPABILITY = "wfd_standby_resume_capability"
    const val CSEQ = "CSeq"
    const val SESSION = "Session"
    const val TRANSPORT = "Transport"
    const val USER_AGENT = "User-Agent"
}

/** Application constants */
object AppConstants {
    const val TAG = "MiracastHub"
    const val NOTIFICATION_ID_SOURCE = 1001
    const val CHANNEL_ID_SOURCE = "miracast_source"
    const val REQUEST_CODE_SCREEN_CAPTURE = 1000
    const val DEFAULT_WIDTH = 1280
    const val DEFAULT_HEIGHT = 720
    const val DEFAULT_FPS = 30
    const val DEFAULT_BITRATE = 5_000_000  // 5 Mbps
}
