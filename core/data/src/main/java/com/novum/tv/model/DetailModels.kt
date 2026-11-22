package com.novum.tv.model

/**
 * 详情页用的完整条目信息。
 *
 * 与 [MediaItem]（列表用）分开：列表只需要 6 个字段，详情还需要简介、演职员、
 * 分级、续播位置等，混在一起会让列表映射承担无用字段的解析成本。
 *
 * @param id 条目 id。
 * @param title 标题。
 * @param type Emby 类型（`Movie` / `Series` / `Episode`）。
 * @param year 年份。
 * @param rating 社区评分（0–10），可能为空。
 * @param officialRating 内容分级，如 `PG-13`。
 * @param runtimeMinutes 时长（分钟），剧集为单集时长。
 * @param overview 简介。
 * @param genres 类型标签。
 * @param posterUrl 竖版海报。
 * @param backdropUrl 横版背景图。
 * @param isFavorite 是否已收藏。
 * @param played 是否看完。
 * @param resumePositionMs 续播位置（毫秒）。
 * @param childCount 子项数量（剧集为集数）。
 * @param premiereDate 首播日期（ISO-8601 前 10 位）。
 * @param status 连载状态：`Continuing` / `Ended`。
 * @param cast 演职员。
 * @param studios 出品公司。
 * @param chapters 章节（片头、正片、彩蛋等），服务端未提供时为空。
 * @param seriesId 所属剧集 id（仅分集有，播放器上一集/下一集定位用）。
 * @param seasonIndex 季序号（仅分集有）。
 * @param index 集序号（仅分集有）。
 */
data class MediaDetail(
    val id: String,
    val title: String,
    val type: String?,
    val year: Int?,
    val rating: Float?,
    val officialRating: String?,
    val runtimeMinutes: Int?,
    val overview: String?,
    val genres: List<String>,
    val posterUrl: String?,
    val backdropUrl: String?,
    /** 透明底标题图（ClearLogo），显示在详情信息区或首页 Hero。 */
    val logoUrl: String? = null,
    /** 视频格式标签（Dolby Vision/HDR10+/HDR10/SDR），与首页 Hero 同款；无视频流（剧集）为空。 */
    val videoFormats: List<String> = emptyList(),
    /** 视频编码（HEVC / H264 / AV1），与首页 Hero 同款。 */
    val videoCodec: String? = null,
    /** 分辨率档位（4K / HD），与首页 Hero 同款；SD 不产标签。 */
    val resolution: String? = null,
    /** 音频编码 Profile（`Dolby Atmos` → 杜比全景声），与首页 Hero 同款。 */
    val audioProfile: String? = null,
    /** 音频编码（TRUEHD / EAC3 / DTS …），与首页 Hero 同款。 */
    val audioFormat: String? = null,
    val isFavorite: Boolean,
    val played: Boolean,
    val resumePositionMs: Long,
    val childCount: Int?,
    val premiereDate: String?,
    val status: String?,
    val cast: List<CastMember>,
    val studios: List<String>,
    val chapters: List<Chapter> = emptyList(),
    val seriesId: String? = null,
    val seasonIndex: Int? = null,
    val index: Int? = null,
    /** 多清晰度版本选项；单版本时为空。 */
    val versions: List<MediaVersionOption> = emptyList(),
    /** 音轨选项；label 为展示名，language 为轨道语言码。 */
    val audioTracks: List<MediaTrackOption> = emptyList(),
    /** 字幕选项（详情页默认偏好选择用）；不含「关闭」——关闭由播放器字幕开关承担。 */
    val subtitleTracks: List<MediaTrackOption> = emptyList(),
) {
    /** 是否剧集（需要加载季与分集）。 */
    val isSeries: Boolean get() = type == "Series"
}

/**
 * 详情侧的轨道选项（音轨/字幕的展示名 + 语言码）。
 *
 * 与播放器的 [TrackOption]（ExoPlayer 轨序号口径）区分：这里是**服务端
 * MediaStreams 口径**，用于跨会话的语言偏好，不含播放期轨序号。
 *
 * @param label 展示名（DisplayTitle 优先，缺语言码时回退「轨道 N」）。
 * @param language 语言码（Emby MediaStreams.Language，如 `chi`/`eng`）；可能为空——
 *  空语言码的轨不能作为语言偏好目标。
 */
data class MediaTrackOption(
    val label: String,
    val language: String?,
    /** 服务端默认轨（IsDefault）；偏好未设置时偏好浮层高亮它。 */
    val default: Boolean = false,
)

/**
 * 章节。
 *
 * @param name 章节名，服务端可能只给「Chapter 1」。
 * @param startMs 起始位置（毫秒）。
 */
data class Chapter(
    val name: String?,
    val startMs: Long,
)

/**
 * 演职员条目。
 *
 * @param name 姓名。
 * @param role 饰演角色；导演/编剧则为职位名。
 * @param imageUrl 头像地址，可能为空。
 * @param id 人物条目 id（Emby People[].Id），点击头像跳作品页用；可能为空。
 */
data class CastMember(
    val name: String,
    val role: String?,
    val imageUrl: String?,
    val id: String? = null,
)

/**
 * 剧集的季。
 *
 * @param id 季 id。
 * @param name 展示名，如「第 1 季」。
 * @param index 季序号（0 为特别篇）。
 * @param episodeCount 集数。
 * @param unplayedCount 未看集数；服务端未返回时为 null（默认季回退逻辑用）。
 */
data class Season(
    val id: String,
    val name: String,
    val index: Int,
    val episodeCount: Int?,
    val unplayedCount: Int? = null,
)

/**
 * 分集。
 *
 * @param id 分集 id（播放时使用）。
 * @param title 分集标题。
 * @param seasonIndex 季序号，用于显示 SxxExx。
 * @param index 集序号。
 * @param played 是否已看完。
 * @param progressMs 已观看位置（毫秒），0 表示未开始。
 * @param runtimeMinutes 时长（分钟）。
 * @param imageUrl 缩略图（分集自己的 Primary 图，多为视频截图）。
 * @param seriesWideUrl 所属剧集的 16:9 主视觉（按 Thumb → Backdrop → 海报顺序取图；缺失回落
 *   [imageUrl]）。
 * @param overview 分集简介。
 */
data class Episode(
    val id: String,
    val title: String,
    val seasonIndex: Int,
    val index: Int,
    val played: Boolean,
    val progressMs: Long,
    val runtimeMinutes: Int?,
    val imageUrl: String?,
    val seriesWideUrl: String? = null,
    val overview: String?,
    /** 该集音频编码（`EAC3`/`TRUEHD`/`DTS`…），供「当前集信息区」格式徽章；无音频流为 null。 */
    val audioFormat: String? = null,
    /** 该集音频 Profile（`Dolby Atmos` 等）；无则 null。 */
    val audioProfile: String? = null,
    /** 该集的首播日期（ISO-8601 前 10 位）；无则 null。 */
    val premiereDate: String? = null,
    /** 该集的多版本选项；单版本为空。 */
    val versions: List<MediaVersionOption> = emptyList(),
) {
    /**
     * 观看进度比例（0f–1f）：未开始 / 已看完 → null（不画进度条）。
     *
     * 由 `progressMs / (runtimeMinutes × 60_000)` 算出；两者任一缺失即为 null。
     */
    val progressRatio: Float?
        get() {
            if (played || progressMs <= 0L) return null
            val totalMs = (runtimeMinutes ?: return null) * 60_000L
            if (totalMs <= 0L) return null
            return (progressMs.toFloat() / totalMs).coerceIn(0f, 1f)
        }
}
