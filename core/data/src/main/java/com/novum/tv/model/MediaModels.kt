package com.novum.tv.model

/**
 * 媒体库（Emby 的 View）。
 *
 * @param id 媒体库 id，作为条目查询的 ParentId。
 * @param name 展示名，如「华语电影」。
 * @param kind Emby 的 CollectionType（movies / tvshows / …）。
 * @param itemCount 条目数，可能为空。
 * @param imageUrl 库封面（Primary 图），管理员未设置封面时为空。
 */
data class MediaLibrary(
    val id: String,
    val name: String,
    val kind: String?,
    val itemCount: Int?,
    val imageUrl: String?,
)

/**
 * 可展示的媒体条目。
 *
 * 只保留 UI 需要的字段，避免把 Emby DTO 泄漏到界面层。
 *
 * @param id 条目 id。
 * @param title 标题。
 * @param type Emby 类型（Movie / Series / Episode …）。
 * @param year 年份。
 * @param rating 社区评分（0–10）。
 * @param imageUrl 海报地址，可能为空。
 * @param subtitle 副标题（年份 / 集数 / 时长），由映射层拼好。
 */
data class MediaItem(
    val id: String,
    val title: String,
    val type: String?,
    val year: Int?,
    val rating: Float?,
    val imageUrl: String?,
    val subtitle: String?,
)

/**
 * 分页结果。
 *
 * @param items 当前页条目。
 * @param total 服务端总条数。
 */
data class MediaPage(
    val items: List<MediaItem>,
    val total: Int,
)

/**
 * 首页聚合条目。
 *
 * 比 [MediaItem] 多三块信息：Hero 需要的背景图与简介、继续观看需要的进度比例、
 * 分集跳转需要的剧集 id。各内容行复用同一结构，避免每个区块一套模型。
 *
 * @param id 条目 id（分集为分集 id）。
 * @param title 主标题（分集用所属剧集名，更符合「继续看剧」的心智）。
 * @param subtitle 副标题（年份 · 类型，分集为 SxxExx + 集标题）。
 * @param imageUrl 竖版海报或横版缩略图。
 * @param backdropUrl 横版背景图，Hero 与无海报兜底用。
 * @param overview 简介，Hero 用。
 * @param rating 社区评分（0–10）。
 * @param year 年份。
 * @param type Emby 类型（Movie / Series / Episode）。
 * @param seriesId 所属剧集 id；分集点击时跳详情用，非分集为 null。
 * @param progress 观看进度（0f–1f）；null 表示无进度信息。
 * @param logoUrl 透明底标题图（ClearLogo），服务端没有该图时为 null，界面退回文本标题。
 * @param isFavorite 服务端收藏状态；首页 Hero 的收藏按钮乐观切换用。
 * @param isPlayed 已看状态（UserData.Played）；快捷菜单「标为已看/未看」当前态与乐观切换用。
 */
data class HomeEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** 16:9 横版卡图（服务端 Thumb 优先，缺失回落 Primary）。 */
    val imageUrl: String? = null,
    /** 2:3 竖版海报（服务端 Primary），海报行专用。 */
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val rating: Float? = null,
    val year: Int? = null,
    val type: String? = null,
    val seriesId: String? = null,
    val progress: Float? = null,
    /** 剩余观看分钟数（继续观看卡文案）；null = 无进度或时长未知。 */
    val remainingMinutes: Int? = null,
    /** 透明底标题图（ClearLogo），服务端没有该图时为 null，界面退回文本标题。 */
    val logoUrl: String? = null,
    /** 内容分级（PG-13 / TV-MA …），Hero 信息块标签用。 */
    val officialRating: String? = null,
    /** 片长（分钟），Hero 元信息行用；剧集/缺省为 null。 */
    val runtimeMinutes: Int? = null,
    /** 题材标签（动作/科幻…前 3 个），Hero 标签行用。 */
    val genres: List<String> = emptyList(),
    /** 视频格式标签（杜比视界 / HDR10 / HDR10+ / SDR），全谱产标签。 */
    val videoFormats: List<String> = emptyList(),
    /** 音频格式标签（首个音频轨编解码，EAC3 / TRUEHD / DTS …）。 */
    val audioFormat: String? = null,
    /** 视频编码（HEVC / H264 / AV1），Hero 格式标志行用。 */
    val videoCodec: String? = null,
    /** 分辨率档位（4K / HD），Hero 格式标志行用；SD 不产标签。 */
    val resolution: String? = null,
    /** 音频编码 Profile（`Dolby Atmos` → 杜比全景声），Hero 格式标志行用。 */
    val audioProfile: String? = null,
    val isFavorite: Boolean = false,
    val isPlayed: Boolean = false,
)

/** 首页内容行标识：继续观看。 */
const val HOME_ROW_RESUME = "resume"

/** 首页内容行标识：下一集。 */
const val HOME_ROW_NEXT_UP = "next_up"

/**
 * 首页横向内容区块。
 *
 * @param id 区块标识（[HOME_ROW_RESUME] / [HOME_ROW_NEXT_UP]），界面据此取标题文案。
 * @param items 条目列表，为空的区块不应下发到界面。
 */
data class HomeRow(
    val id: String,
    val items: List<HomeEntry>,
)

/**
 * 各媒体库的最新入库行。
 *
 * @param libraryId 媒体库 id。
 * @param libraryName 媒体库名，作为行标题。
 * @param items 最新条目。
 */
data class HomeLatestRow(
    val libraryId: String,
    val libraryName: String,
    val items: List<HomeEntry>,
)

/**
 * 首页聚合数据。
 *
 * @param hero Hero 候选：近期上映电影与近期上新剧集，
 *  三级兜底保证服务器正常时必非空；继续观看条目不进 Hero 池）。
 * @param resume 继续观看；无未看完内容时为 null。
 * @param libraries 首页分库内容行所需的媒体库目录，只含影片/剧集类库。
 * @param nextUp 下一集；无在追剧集时为 null。
 * @param latest 各库最新入库。
 */
data class HomeFeed(
    val hero: List<HomeEntry>,
    val resume: HomeRow?,
    val libraries: List<MediaLibrary> = emptyList(),
    val nextUp: HomeRow?,
    val latest: List<HomeLatestRow>,
) {
    /** 是否整页没有任何可展示内容，用于空态判断。 */
    val isEmpty: Boolean
        get() = hero.isEmpty() && resume == null && nextUp == null && latest.isEmpty()
}
