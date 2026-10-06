package tv.anstelle.core.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.audio.AudioCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import tv.anstelle.core.common.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads what this device decodes, once per process: the first call walks `MediaCodecList`
 * (tens of milliseconds), later calls return the cached snapshot. Call it off the main thread.
 */
@Singleton
@OptIn(UnstableApi::class)
class CapabilityProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val capabilities: PlaybackCapabilities by lazy { probe() }

    private fun probe(): PlaybackCapabilities {
        val started = System.nanoTime()
        val video = HashMap<String, VideoSupport>()
        val audio = HashSet<String>()
        var concurrent = false

        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
                VideoNames[mime.lowercase()]?.let { name ->
                    val videoCaps = caps.videoCapabilities ?: return@let
                    val support = VideoSupport(
                        maxWidth = videoCaps.supportedWidths.upper,
                        maxHeight = videoCaps.supportedHeights.upper,
                        maxBitDepth = if (caps.profileLevels.any { it.profile in TenBitProfiles.getOrDefault(mime.lowercase(), emptySet()) }) 10 else 8,
                    )
                    video[name] = video[name]?.let { max(it, support) } ?: support
                    if (mime.equals(MimeTypes.VIDEO_H264, ignoreCase = true) && isHardware(info) && caps.maxSupportedInstances >= 2) {
                        concurrent = true
                    }
                }
                AudioNames[mime.lowercase()]?.let { audio += it }
            }
        }

        if (FfmpegLibrary.isAvailable()) {
            for ((mime, name) in AudioNames) if (FfmpegLibrary.supportsFormat(mime)) audio += name
        }
        val passthrough = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
        for ((encoding, name) in PassthroughNames) if (passthrough.supportsEncoding(encoding)) audio += name

        val result = PlaybackCapabilities(video, audio, concurrent)
        AppLog.i(TAG, "Decoders in ${(System.nanoTime() - started) / 1_000_000} ms: video $video, audio ${audio.sorted()}, two video decoders: $concurrent")
        return result
    }

    private fun max(a: VideoSupport, b: VideoSupport) =
        VideoSupport(maxOf(a.maxWidth, b.maxWidth), maxOf(a.maxHeight, b.maxHeight), maxOf(a.maxBitDepth, b.maxBitDepth))

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")

    // The profile constants are plain ints; reading them on older Android versions is harmless.
    @SuppressLint("InlinedApi")
    private companion object {
        const val TAG = "Capabilities"

        /** Decoder MIME types → Jellyfin video codec names. */
        val VideoNames = mapOf(
            "video/avc" to "h264",
            "video/hevc" to "hevc",
            "video/av01" to "av1",
            "video/x-vnd.on2.vp9" to "vp9",
            "video/x-vnd.on2.vp8" to "vp8",
            "video/mpeg2" to "mpeg2video",
            "video/mp4v-es" to "mpeg4",
            "video/3gpp" to "h263",
            "video/wvc1" to "vc1",
        )

        val TenBitProfiles = mapOf(
            "video/hevc" to setOf(
                CodecProfileLevel.HEVCProfileMain10,
                CodecProfileLevel.HEVCProfileMain10HDR10,
                CodecProfileLevel.HEVCProfileMain10HDR10Plus,
            ),
            "video/x-vnd.on2.vp9" to setOf(
                CodecProfileLevel.VP9Profile2,
                CodecProfileLevel.VP9Profile2HDR,
                CodecProfileLevel.VP9Profile2HDR10Plus,
            ),
            "video/av01" to setOf(
                CodecProfileLevel.AV1ProfileMain10,
                CodecProfileLevel.AV1ProfileMain10HDR10,
                CodecProfileLevel.AV1ProfileMain10HDR10Plus,
            ),
            "video/avc" to setOf(CodecProfileLevel.AVCProfileHigh10),
        )

        /** Audio MIME types → Jellyfin audio codec names. */
        val AudioNames = mapOf(
            MimeTypes.AUDIO_AAC to "aac",
            MimeTypes.AUDIO_MPEG to "mp3",
            MimeTypes.AUDIO_MPEG_L2 to "mp2",
            MimeTypes.AUDIO_AC3 to "ac3",
            MimeTypes.AUDIO_E_AC3 to "eac3",
            MimeTypes.AUDIO_E_AC3_JOC to "eac3",
            MimeTypes.AUDIO_DTS to "dts",
            MimeTypes.AUDIO_DTS_HD to "dts",
            MimeTypes.AUDIO_TRUEHD to "truehd",
            MimeTypes.AUDIO_FLAC to "flac",
            MimeTypes.AUDIO_OPUS to "opus",
            MimeTypes.AUDIO_VORBIS to "vorbis",
            MimeTypes.AUDIO_ALAC to "alac",
            MimeTypes.AUDIO_RAW to "pcm",
        )

        val PassthroughNames = mapOf(
            C.ENCODING_AC3 to "ac3",
            C.ENCODING_E_AC3 to "eac3",
            C.ENCODING_DTS to "dts",
            C.ENCODING_DTS_HD to "dts",
            C.ENCODING_DOLBY_TRUEHD to "truehd",
        )
    }
}
