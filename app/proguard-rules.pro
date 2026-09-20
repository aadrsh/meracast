# ProGuard rules for MiracastHub

# Keep all Miracast engine classes
-keep class com.meracast.miracast.** { *; }
-keep class com.meracast.common.** { *; }

# Keep RTSP and RTP classes
-keep class com.meracast.miracast.source.** { *; }
-keep class com.meracast.miracast.sink.** { *; }
-keep class com.meracast.miracast.rtp.** { *; }
-keep class com.meracast.miracast.wfd.** { *; }

# Keep Android classes used by reflection
-keep class android.media.MediaCodec { *; }
-keep class android.media.MediaFormat { *; }
-keep class android.media.projection.MediaProjection { *; }
-keep class android.net.wifi.p2p.** { *; }
