package com.novum.tv.model

/**
 * 播放方式。
 *
 * 与 Emby 的 `PlayMethod` 取值一一对应，进度上报时会原样传给服务端。
 */
enum class PlayMethod {
    /** 原样直连：不转封装也不转码，画质最好、服务端零开销。 */
    DirectPlay,

    /** 转封装直连：换容器不换编码。 */
    DirectStream,

    /** 服务端转码：当前设备解不了时兜底。 */
    Transcode,
}

/**
 * 可切换的媒体轨（音轨 / 字幕）。
 *
 * @param index 轨序号，对应 ExoPlayer 的 track index。
 * @param label 展示名，如「国语 · aac」。
 * @param language 语言标签。
 */
data class TrackOption(
    val index: Int,
    val label: String,
    val language: String?,
)

/**
 * 版本选项（同一影片的多清晰度版本，详情页选择 + 画质偏好的目标）。
 *
 * 这里只承载版本自身的规格字段；条目名与 SxxExx 前缀由界面按当前条目拼接。
 *
 * 版本选项展示清晰度、动态范围与码率；规格缺失时由 label 提供兜底。
 *
 * @param sourceId MediaSource id（plan 按 id 定位版本）。
 * @param label 服务端版本名（如「4K HDR」）；规格三段全缺时作为展示兜底。
 * @param resolution 清晰度档（4K / 1440p / 1080p / 720p）；无视频流时 null。
 * @param dynamicRange 动态范围（杜比视界 / HDR10+ / HDR10 / SDR）；无视频流时 null。
 * @param bitrate 视频码率展示串（如「45.2 Mbps」）；服务端未下发时 null。
 */
data class MediaVersionOption(
    val sourceId: String,
    val label: String? = null,
    val resolution: String? = null,
    val dynamicRange: String? = null,
    val bitrate: String? = null,
) {
    /** 规格三段（清晰度 · 动态范围 · 码率），全缺为空列表。 */
    val specs: List<String> get() = listOfNotNull(resolution, dynamicRange, bitrate)
}

/**
 * 版本行展示文案：`电影名/剧名 · SxxExx（有则）·
 * 清晰度 · 动态范围 · 码率`。规格三段全缺时退回服务端版本名（保证不出现空行）。
 *
 * 详情页版本浮层与播放页抽屉换版本共用同一格式（放数据层是为了两端同源，
 * 前缀由界面按当前条目拼——数据层拿不到干净的剧名/集号语境）。
 *
 * @param titlePrefix 前缀（电影名/剧名，分集再带 SxxExx）。
 */
fun MediaVersionOption.displayLine(titlePrefix: String): String {
    val parts = specs.ifEmpty { listOfNotNull(label) }
    return (listOf(titlePrefix) + parts).joinToString(" · ")
}

/**
 * 外挂字幕源。
 *
 * Emby 的 ASS/SRT 字幕是**独立文件**（`DeliveryUrl`），播放器不会自动加载，
 * 必须作为 `MediaItem.SubtitleConfiguration` 显式挂到媒体上，否则播放器里连字幕轨都没有。
 *
 * @param id 轨 id，用 MIME + 语言 + 下标拼成稳定值。
 * @param url 字幕文件绝对地址（已带鉴权参数）。
 * @param language BCP-47 语言标签。
 * @param label 展示名，如「中文 & 韩文 (ASS)」。
 * @param mimeType 字幕 MIME，决定用哪个解码器解析。
 */
data class SubtitleSource(
    val id: String,
    val url: String,
    val language: String?,
    val label: String?,
    val mimeType: String,
)

/**
 * 视频规格快照，用于播放器「信息」面板。
 *
 * 字段全部来自 `PlaybackInfo.MediaSources[].MediaStreams`，缺失时保持 null，
 * 界面按「未知」展示而不是编造数值。
 *
 * @param width 画面宽度（像素）。
 * @param height 画面高度（像素）。
 * @param codec 视频编码，如 `hevc`。
 * @param bitrate 码率（bps）。
 * @param frameRate 帧率。
 * @param hdr HDR 类型，如 `HDR10` / `DolbyVision`；SDR 为 null。
 * @param container 封装格式。
 * @param audioCodec 当前音轨编码。
 * @param audioChannels 声道数。
 */
data class VideoSpec(
    val width: Int? = null,
    val height: Int? = null,
    val codec: String? = null,
    val bitrate: Long? = null,
    val frameRate: Float? = null,
    val hdr: String? = null,
    val container: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
) {
    /** 分辨率展示，如 `3840×2160`；缺失时返回 null。 */
    val resolution: String?
        get() = if (width != null && height != null) "$width×$height" else null
}

/**
 * 播放计划：一次 `PlaybackInfo` 请求的最终决策结果。
 *
 * @param url 绝对播放地址（已带上服务端要求的鉴权参数）。
 * @param method 播放方式，用于进度上报与界面提示。
 * @param audioTracks 可切换的音轨。
 * @param subtitleTracks 可切换的字幕轨（服务端声明的轨信息，仅用于展示）。
 * @param subtitles 外挂字幕源，需要挂到播放器上才会出现字幕轨。
 * @param durationMs 服务端声明的时长（毫秒），为空时以播放器解析为准。
 * @param startPositionMs 续播起点（毫秒）。
 * @param videoSpec 视频规格，供播放器信息面板展示。
 * @param fallbackUrl 直连失败时改用的转码地址；没有可用的转码地址时为 null。
 * @param fallbackMethod 兜底地址对应的播放方式。
 */
data class PlaybackPlan(
    val url: String,
    val method: PlayMethod,
    val audioTracks: List<TrackOption>,
    val subtitleTracks: List<TrackOption>,
    val subtitles: List<SubtitleSource>,
    val durationMs: Long?,
    val startPositionMs: Long,
    val videoSpec: VideoSpec? = null,
    val fallbackUrl: String? = null,
    val fallbackMethod: PlayMethod? = null,
    /** 实际使用的线路（host + 延迟），信息面板展示与问题排查用。 */
    val lineLabel: String? = null,
    /** 实际使用的线路 baseUrl，换线重试时用于排除。 */
    val lineBaseUrl: String? = null,
)
