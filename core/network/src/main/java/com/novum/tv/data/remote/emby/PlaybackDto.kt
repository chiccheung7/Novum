package com.novum.tv.data.remote.emby

import kotlinx.serialization.Serializable

/**
 * 播放信息请求体。
 *
 * @param DeviceProfile 客户端解码能力描述；服务端据此决定直连、转封装或转码。
 */
@Serializable
data class PlaybackInfoRequestDto(
    val DeviceProfile: DeviceProfileDto = DeviceProfileDto(),
)

/**
 * 设备能力描述。
 *
 * 只声明当前播放器真正支持的能力：声明得越准确，服务端越倾向于给直连地址，
 * 少一次转码就少一层 CPU 与画质损失。
 *
 * @param MaxStreamingBitrate 码率上限（bps）。
 * @param DirectPlayProfiles 可直连的封装/编码组合。
 * @param TranscodingProfiles 需要转码时希望的输出格式（HLS + h264 + aac）。
 * @param SubtitleProfiles 字幕支持方式：外挂优先，避免烧录进画面。
 */
@Serializable
data class DeviceProfileDto(
    val MaxStreamingBitrate: Long = 40_000_000,
    val DirectPlayProfiles: List<DirectPlayProfileDto> = listOf(
        DirectPlayProfileDto(
            Container = "mkv,mp4,m4v,ts,m2ts,avi,webm",
            Type = "Video",
            VideoCodec = "h264,hevc,vp9,av1,mpeg4",
            AudioCodec = "aac,ac3,eac3,mp3,flac,opus,dts",
        ),
    ),
    val TranscodingProfiles: List<TranscodingProfileDto> = listOf(
        TranscodingProfileDto(
            Container = "ts",
            Type = "Video",
            VideoCodec = "h264",
            AudioCodec = "aac",
            Protocol = "hls",
        ),
    ),
    val SubtitleProfiles: List<SubtitleProfileDto> = listOf(
        SubtitleProfileDto(Format = "srt", Method = "External"),
        SubtitleProfileDto(Format = "subrip", Method = "External"),
        // ASS/SSA：ExoPlayer 样式支持很弱——默认外挂（样式打折），设置开启
        // ASS/SSA 需要保留样式时声明 Encode，交由服务端转码。
        SubtitleProfileDto(Format = "ass", Method = "External"),
        SubtitleProfileDto(Format = "ssa", Method = "External"),
        // 图形字幕（PGS/VobSub）ExoPlayer 不渲染（media3 已知缺口）——声明
        // Encode 让服务端在转码会话中烧录进画面。
        SubtitleProfileDto(Format = "pgssub", Method = "Encode"),
        SubtitleProfileDto(Format = "vobsub", Method = "Encode"),
    ),
) {
    companion object {
        /**
         * 按设备真实能力生成描述。
         *
         * 编码列表来自 CapabilityProvider 的探测结果，不在这里硬编码机型：
         * 声明得越准，服务端越敢给直连地址；声明过头会导致「选了杜比音轨却没声」。
         *
         * @param audioCodecs 可用的音频编码，逗号分隔（已按直通开关收敛）。
         * @param videoCodecs 可用的视频编码，逗号分隔。
         * @param allowDirectPlay 是否声明直连能力。置 false 时 DirectPlayProfiles 为空，
         * 服务端只能返回转码地址——用于「设备解不了」时强制转码。
         */
        fun forCapabilities(
            audioCodecs: String,
            videoCodecs: String,
            allowDirectPlay: Boolean = true,
            assBurnIn: Boolean = false,
        ): DeviceProfileDto {
            // ASS/SSA 开启烧录时声明 Encode，否则使用外挂字幕。
            val assMethod = if (assBurnIn) "Encode" else "External"
            return DeviceProfileDto(
                DirectPlayProfiles = if (allowDirectPlay) {
                    listOf(
                        DirectPlayProfileDto(
                            Container = "mkv,mp4,m4v,ts,m2ts,avi,webm",
                            Type = "Video",
                            VideoCodec = videoCodecs,
                            AudioCodec = audioCodecs,
                        ),
                    )
                } else {
                    emptyList()
                },
                SubtitleProfiles = listOf(
                    SubtitleProfileDto(Format = "srt", Method = "External"),
                    SubtitleProfileDto(Format = "subrip", Method = "External"),
                    SubtitleProfileDto(Format = "ass", Method = assMethod),
                    SubtitleProfileDto(Format = "ssa", Method = assMethod),
                    SubtitleProfileDto(Format = "pgssub", Method = "Encode"),
                    SubtitleProfileDto(Format = "vobsub", Method = "Encode"),
                ),
            )
        }
    }
}

/** 直连能力声明。 */
@Serializable
data class DirectPlayProfileDto(
    val Container: String,
    val Type: String,
    val VideoCodec: String,
    val AudioCodec: String,
)

/** 转码输出偏好。 */
@Serializable
data class TranscodingProfileDto(
    val Container: String,
    val Type: String,
    val VideoCodec: String,
    val AudioCodec: String,
    val Protocol: String,
)

/** 字幕支持方式。 */
@Serializable
data class SubtitleProfileDto(
    val Format: String,
    val Method: String,
)

/**
 * 播放信息响应。
 *
 * @param MediaSources 可播放的媒体源，通常一个条目对应多个版本。
 * @param PlaySessionId 播放会话 id，进度上报时服务端用它归并会话。
 */
@Serializable
data class PlaybackInfoDto(
    val MediaSources: List<MediaSourceDto> = emptyList(),
    val PlaySessionId: String? = null,
)

/**
 * 媒体源。
 *
 * 注意：`DirectStreamUrl` / `TranscodingUrl` 由**服务端**下发，可能是托管商自定义路径
 * （UHDNOW 给的是 `/play/video/...`），不能假设为 Emby 标准的 `/Videos/{id}/stream`。
 *
 * @param Id 媒体源 id。
 * @param Container 封装格式。
 * @param SupportsDirectPlay 是否支持原样直连。
 * @param SupportsDirectStream 是否支持转封装直连。
 * @param SupportsTranscoding 是否需要转码。
 * @param DirectStreamUrl 直连地址（相对路径，带 api_key）。
 * @param TranscodingUrl 转码地址（相对路径）。
 * @param MediaStreams 音视频与字幕轨。
 * @param RunTimeTicks 时长（ticks）。
 */
@Serializable
data class MediaSourceDto(
    val Id: String? = null,
    /** 版本名（多版本条目服务端下发，如「4K HDR」「1080p」）；可能为空。 */
    val Name: String? = null,
    val Container: String? = null,
    val Protocol: String? = null,
    val SupportsDirectPlay: Boolean = false,
    val SupportsDirectStream: Boolean = false,
    val SupportsTranscoding: Boolean = false,
    val DirectStreamUrl: String? = null,
    val TranscodingUrl: String? = null,
    val MediaStreams: List<MediaStreamDto> = emptyList(),
    val RunTimeTicks: Long? = null,
    /** 文件大小（字节），多版本「文件大小优先」策略用；可能为空。 */
    val Size: Long? = null,
    /** 文件路径；服务端可能不下发。 */
    val Path: String? = null,
)

/**
 * 单条媒体轨。
 *
 * @param Type `Video` / `Audio` / `Subtitle`。
 * @param Index 轨序号，切换轨道时使用。
 * @param Codec 编码，如 `h264` / `aac` / `subrip`。
 * @param Language 语言标签。
 * @param DisplayTitle 服务端给的展示名。
 * @param IsDefault 是否默认轨。
 * @param IsExternal 是否外挂字幕。
 * @param DeliveryUrl 外挂字幕地址。
 * @param Width 视频宽度（像素）。
 * @param Height 视频高度（像素）。
 * @param BitRate 码率（bps）。
 * @param FrameRate 帧率。
 * @param VideoRange HDR 标识（`HDR` / `SDR`）。
 * @param VideoRangeType 精细 HDR 类型（`HDR10` / `HLG` / `DOVI` / `DOVIWithHDR10` …）。
 * @param Channels 声道数。
 * @param Profile 编码 Profile（`Dolby Atmos` 等）。
 */
@Serializable
data class MediaStreamDto(
    val Type: String? = null,
    val Index: Int? = null,
    val Codec: String? = null,
    val Language: String? = null,
    val DisplayTitle: String? = null,
    /** 轨道名（部分服务端用 Title 承载「Hong Kong/Forced/SDH」等版本信息）。 */
    val Title: String? = null,
    val IsDefault: Boolean = false,
    val IsExternal: Boolean = false,
    val DeliveryUrl: String? = null,
    val Width: Int? = null,
    val Height: Int? = null,
    val BitRate: Long? = null,
    val FrameRate: Float? = null,
    val VideoRange: String? = null,
    val VideoRangeType: String? = null,
    val Channels: Int? = null,
    /** 编码 Profile（`Dolby Atmos` 等），Hero 杜比全景声标志用。 */
    val Profile: String? = null,
)

/**
 * 播放进度上报体。
 *
 * @param ItemId 条目 id。
 * @param PositionTicks 当前位置（ticks）。
 * @param IsPaused 是否暂停。
 * @param CanSeek 是否允许拖动。
 * @param PlayMethod `DirectPlay` / `DirectStream` / `Transcode`。
 * @param PlaySessionId PlaybackInfo 返回的会话 id，服务端据此把进度归并到本次播放。
 */
@Serializable
data class PlaybackProgressDto(
    val ItemId: String,
    val PositionTicks: Long,
    val IsPaused: Boolean = false,
    val CanSeek: Boolean = true,
    val PlayMethod: String = "DirectPlay",
    val PlaySessionId: String? = null,
)
