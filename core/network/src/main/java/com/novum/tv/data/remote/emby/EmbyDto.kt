package com.novum.tv.data.remote.emby

import kotlinx.serialization.Serializable

/**
 * Emby 公共信息（无需鉴权）。
 *
 * @param ServerName 服务器展示名，登录后用于设置页与首页角标。
 * @param Version 服务端版本号。
 * @param Id 服务器唯一 id。
 */
@Serializable
data class PublicInfoDto(
    val ServerName: String? = null,
    val Version: String? = null,
    val Id: String? = null,
)

/**
 * 登录请求体。
 *
 * 注意：Emby 的密码字段名是 `Pw`，不是 `Password`；
 * 写成 `Password` 会被服务端忽略并始终返回 401。
 *
 * @param Username 用户名。
 * @param Pw 密码明文（HTTPS 下传输；HTTP 自建服务器场景由用户自行承担风险）。
 */
@Serializable
data class AuthRequestDto(
    val Username: String,
    val Pw: String,
)

/**
 * 登录响应。
 *
 * @param AccessToken 后续所有请求使用的 Token（`X-Emby-Token`）。
 * @param User 登录用户信息。
 */
@Serializable
data class AuthResultDto(
    val AccessToken: String? = null,
    val User: UserDto? = null,
)

/**
 * 用户信息。
 *
 * @param Id 用户 id，路径参数 `{uid}` 使用。
 * @param Name 用户名。
 */
@Serializable
data class UserDto(
    val Id: String? = null,
    val Name: String? = null,
)

/**
 * 查询结果包装。
 *
 * @param T 条目类型。
 * @param Items 当前页条目。
 * @param TotalRecordCount 总条数（用于分页与「共 N 部」）。
 */
@Serializable
data class QueryResultDto<T>(
    val Items: List<T> = emptyList(),
    val TotalRecordCount: Int = 0,
)

/**
 * 媒体条目（按 Emby 字段名映射，保持与接口一致，不做重命名）。
 *
 * 只声明当前使用的字段；Emby 返回的多余字段由 `ignoreUnknownKeys` 忽略。
 */
@Serializable
data class ItemDto(
    val Id: String? = null,
    val Name: String? = null,
    val Type: String? = null,
    val ProductionYear: Int? = null,
    val CommunityRating: Float? = null,
    val RunTimeTicks: Long? = null,
    val Overview: String? = null,
    val Genres: List<String> = emptyList(),
    val ImageTags: Map<String, String> = emptyMap(),
    val BackdropImageTags: List<String> = emptyList(),
    val SeriesId: String? = null,
    /** 分集所属季 id；比 `ParentIndexNumber` 更可靠（部分网关不下发季序号）。 */
    val SeasonId: String? = null,
    val SeriesName: String? = null,
    val SeriesPrimaryImageTag: String? = null,
    /** 所属剧集横版缩略图版本号。 */
    val SeriesThumbImageTag: String? = null,
    /** 分集的上层剧集缩略图版本号；分集自身没有 Primary 时，横卡用它的 ParentThumb，而不是竖版海报。 */
    val ParentThumbItemId: String? = null,
    val ParentThumbImageTag: String? = null,
    val IndexNumber: Int? = null,
    val ParentIndexNumber: Int? = null,
    val ChildCount: Int? = null,
    val RecursiveItemCount: Int? = null,
    val CollectionType: String? = null,
    val UserData: UserDataDto? = null,
    /** 内容分级，如 `PG-13`、`TV-MA`。 */
    val OfficialRating: String? = null,
    /** 宣传语。 */
    val Taglines: List<String> = emptyList(),
    /** 演职员（详情接口返回）。 */
    val People: List<PersonDto> = emptyList(),
    /** 出品公司。 */
    val Studios: List<NameIdDto> = emptyList(),
    /** 首播时间（ISO-8601），「高分精选」的时间口径。 */
    val PremiereDate: String? = null,
    /** 入库时间（ISO-8601），首播时间缺失时的兜底口径。 */
    val DateCreated: String? = null,
    /** 剧集最后添加新集的时间（ISO-8601），「精选推荐」判断近期更新的主口径。 */
    val DateLastMediaAdded: String? = null,

    /** 章节列表，需要请求 `Fields=Chapters` 才会返回。 */
    val Chapters: List<ChapterDto> = emptyList(),
    /** 媒体流摘要，需要请求 `Fields=MediaStreams` 才会返回（Hero 格式标签用）。 */
    val MediaStreams: List<MediaStreamDto> = emptyList(),
    /** 媒体源（多版本条目有多个，详情端点默认返回；版本选择用）。 */
    val MediaSources: List<MediaSourceDto> = emptyList(),
    /** 连载状态：`Continuing` / `Ended`。 */
    val Status: String? = null,
    /** 服务端内容标签，与题材 Genres 分开保留，供首页推荐筛选。 */
    val Tags: List<String> = emptyList(),
)

/**
 * 演职员。
 *
 * @param Name 姓名。
 * @param Role 饰演角色（导演为职位名）。
 * @param Type 类型：`Actor` / `Director` / `Writer` …
 * @param Id 人物 id，用于取头像。
 * @param PrimaryImageTag 头像版本号。
 */
@Serializable
data class PersonDto(
    val Name: String? = null,
    val Role: String? = null,
    val Type: String? = null,
    val Id: String? = null,
    /** 条目 `People[]` 里的头像版本号（`/Items` 口径）。 */
    val PrimaryImageTag: String? = null,
    /**
     * `/Persons` 端点返回的头像版本号放在 `ImageTags.Primary`
     * （`/Items` 用的是平铺的 [PrimaryImageTag]）。两个口径都要认，
     * 否则人物搜索结果全是无图占位，看起来像一串关键词而不是人。
     */
    val ImageTags: Map<String, String> = emptyMap(),
)

/**
 * 章节。
 *
 * @param Name 章节名，服务端可能只给「Chapter 1」。
 * @param StartPositionTicks 起始位置（ticks，1 tick = 100ns）。
 */
@Serializable
data class ChapterDto(
    val Name: String? = null,
    val StartPositionTicks: Long? = null,
)

/**
 * 只有名字与 id 的通用结构（出品公司、类型等）。
 *
 * @param Name 名称。
 * @param Id 唯一 id。
 */
@Serializable
data class NameIdDto(
    val Name: String? = null,
    val Id: String? = null,
)

/**
 * 用户对某条目的观看数据。
 *
 * @param PlaybackPositionTicks 续播位置（1 tick = 100ns）。
 * @param PlayCount 播放次数。
 * @param IsFavorite 是否收藏。
 * @param Played 是否已看完。
 * @param PlayedPercentage 已看百分比（0–100），服务端据此计算「继续观看」进度条；未玩过为空。
 */
@Serializable
data class UserDataDto(
    val PlaybackPositionTicks: Long? = null,
    val PlayCount: Int? = null,
    val IsFavorite: Boolean = false,
    val Played: Boolean = false,
    val PlayedPercentage: Double = 0.0,
    /** 未看完的子项数量（季 → 未看集数；服务端未返回时为 null）。 */
    val UnplayedItemCount: Int? = null,
    val Rating: Double? = null,
    val LastPlayedDate: String? = null,
    val Key: String? = null,
    val ItemId: String? = null,
    val ServerId: String? = null,
)

/** 更新请求中的零值与 false 均为必填字段，不能因 encodeDefaults=false 而被省略。 */
@Serializable
data class UserDataUpdateDto(
    val PlaybackPositionTicks: Long,
    val Played: Boolean,
    val PlayedPercentage: Double,
    val IsFavorite: Boolean,
    val PlayCount: Int? = null,
    val UnplayedItemCount: Int? = null,
    val Rating: Double? = null,
    val LastPlayedDate: String? = null,
    val Key: String? = null,
    val ItemId: String? = null,
    val ServerId: String? = null,
)
