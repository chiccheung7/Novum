package com.novum.tv.data.remote.emby

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Emby REST 接口（服务端 4.9.x）。
 *
 * 约定：
 * - 鉴权头 `X-Emby-Token` 与 `X-Emby-Authorization` 由 [EmbyClientFactory] 的拦截器统一注入；
 * - 所有方法都是 `suspend`，异常由 [com.novum.tv.data.repository.AuthRepository] 统一映射为领域错误；
 * - 字段名保持 Emby 原始大小写（`PascalCase`），避免序列化适配成本。
 */
interface EmbyApi {

    /**
     * 读取服务器公开信息，用于校验地址可达性并获取服务器名。
     *
     * 该接口不需要 Token，是登录前探测地址是否正确的首选。
     */
    @GET("System/Info/Public")
    suspend fun publicInfo(): PublicInfoDto

    /**
     * 用户名密码登录。
     *
     * @param body 用户名与密码（密码字段名必须是 `Pw`）。
     */
    @POST("Users/AuthenticateByName")
    suspend fun authenticate(@Body body: AuthRequestDto): AuthResultDto

    /**
     * 当前用户可见的媒体库列表。
     *
     * @param uid 用户 id。
     * @param fields 额外字段；显式请求 `ChildCount` 可一次性拿到每个库的条目数，
     * `ImageTags` 用于拼接库封面地址。
     */
    @GET("Users/{uid}/Views")
    suspend fun views(
        @Path("uid") uid: String,
        @Query("Fields") fields: String = "ChildCount,CollectionType,ImageTags",
    ): QueryResultDto<ItemDto>

    /**
     * 服务器多线路探测（服务端扩展接口，非 Emby 标准）。
     *
     * 返回结构因服务端实现而异，这里不建 DTO：由 [com.novum.tv.data.line.LineProbe]
     * 递归提取其中的 URL。接口不存在或未部署时抛异常，调用方退化为单线路。
     */
    @GET("System/Ext/ServerDomains")
    suspend fun serverDomains(): kotlinx.serialization.json.JsonElement

    /**
     * 通用条目查询（媒体库浏览、分类网格、搜索共用）。
     *
     * @param uid 用户 id。
     * @param parentId 限定媒体库；为空表示全库递归。
     * @param includeItemTypes 限定类型，如 `Movie,Series`。
     * @param searchTerm 搜索词。
     * @param sortBy 排序字段，如 `DateCreated`、`CommunityRating`、`PremiereDate`。
     * @param sortOrder 排序方向。
     * @param startIndex 分页起点。
     * @param limit 单页条数。
     * @param maxOfficialRating 内容分级上限（家长控制）。
     * @param genres 类型过滤。
     * @param filters 过滤条件，如 `IsNotFolder`（只要可播放的影片/剧）。
     * @param minPremiereDate 首播时间下限（`yyyy-MM-dd`），「精选推荐」的电影发行口径。
     * @param minDateCreated 入库时间下限（`yyyy-MM-dd`），「精选推荐」的近期更新口径。
     * @param fields 请求字段集。
     */
    @GET("Users/{uid}/Items")
    suspend fun items(
        @Path("uid") uid: String,
        @Query("ParentId") parentId: String? = null,
        @Query("Ids") ids: String? = null,
        @Query("PersonIds") personIds: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("IncludeItemTypes") includeItemTypes: String? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("SortBy") sortBy: String = "DateCreated,SortName",
        @Query("SortOrder") sortOrder: String = "Descending",
        @Query("StartIndex") startIndex: Int = 0,
        @Query("Limit") limit: Int = 60,
        @Query("MaxOfficialRating") maxOfficialRating: String? = null,
        @Query("Genres") genres: String? = null,
        @Query("Filters") filters: String? = null,
        @Query("MinPremiereDate") minPremiereDate: String? = null,
        @Query("MinDateCreated") minDateCreated: String? = null,
        @Query("Fields") fields: String = DEFAULT_FIELDS,
    ): QueryResultDto<ItemDto>

    /**
     * 继续观看（未看完的条目）。
     *
     * @param uid 用户 id。
     * @param limit 条数上限。
     * @param parentId 限定在某个条目下（单剧集定位用；服务端不支持时由调用方再过滤）。
     */
    @GET("Users/{uid}/Items/Resume")
    suspend fun resume(
        @Path("uid") uid: String,
        @Query("Limit") limit: Int = 12,
        @Query("ParentId") parentId: String? = null,
        @Query("MediaTypes") mediaTypes: String = "Video",
        @Query("Fields") fields: String = DEFAULT_FIELDS,
    ): QueryResultDto<ItemDto>

    /**
     * 下一集（正在追的剧集的下一条待看分集）。
     *
     * NextUp 语义天然覆盖三种默认定位：没看过→第一集；看到一半→该集；
     * 追平后服务端上新→新一集。
     *
     * @param uid 用户 id。
     * @param limit 条数上限。
     * @param seriesId 限定单剧集（详情页默认定位用）；null = 全部在追剧集。
     */
    @GET("Shows/NextUp")
    suspend fun nextUp(
        @Query("UserId") uid: String,
        @Query("Limit") limit: Int = 14,
        @Query("Fields") fields: String = DEFAULT_FIELDS,
        @Query("SeriesId") seriesId: String? = null,
    ): QueryResultDto<ItemDto>

    /**
     * 指定媒体库的最新入库条目。
     *
     * 注意：必须是用户级路径，设备级 `/Items/Latest` 在部分服务端实现上会 404。
     *
     * @param uid 用户 id。
     * @param parentId 媒体库 id。
     * @param limit 条数上限。
     */
    @GET("Users/{uid}/Items/Latest")
    suspend fun latest(
        @Path("uid") uid: String,
        @Query("ParentId") parentId: String,
        @Query("Limit") limit: Int = 18,
        @Query("Fields") fields: String = DEFAULT_FIELDS,
    ): List<ItemDto>

    /**
     * 单条目详情（含简介、演职员、出品公司、分级）。
     *
     * @param uid 用户 id。
     * @param itemId 条目 id。
     * @param fields 请求字段集。
     */
    @GET("Users/{uid}/Items/{itemId}")
    suspend fun detail(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
        @Query("Fields") fields: String = DETAIL_FIELDS,
    ): ItemDto

    /**
     * 人物（演员/导演）搜索：服务端 `Persons` 端点。
     *
     * 直接按名字查询服务端人物，覆盖本地已浏览内容之外的结果。
     *
     * @param userId 用户 id。
     * @param searchTerm 关键词。
     * @param limit 返回条数上限。
     */
    @GET("Persons")
    suspend fun persons(
        @Query("userId") userId: String? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("Limit") limit: Int = 20,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio",
    ): QueryResultDto<PersonDto>

    /**
     * 剧集的季列表。
     *
     * @param seriesId 剧集（Series）id。
     * @param userId 用户 id，用于带回「已看/进度」等用户数据。
     */
    @GET("Shows/{seriesId}/Seasons")
    suspend fun seasons(
        @Path("seriesId") seriesId: String,
        @Query("userId") userId: String,
        @Query("Fields") fields: String = SEASON_FIELDS,
    ): QueryResultDto<ItemDto>

    /**
     * 剧集的分集列表。
     *
     * @param seriesId 剧集 id。
     * @param userId 用户 id。
     * @param seasonId 季 id；为空表示全部季。
     * @param limit 条数上限；为空表示服务端默认（返回整季）。
     * @param fields 请求字段集；按季取演员时追加 `People`。
     */
    @GET("Shows/{seriesId}/Episodes")
    suspend fun episodes(
        @Path("seriesId") seriesId: String,
        @Query("userId") userId: String,
        @Query("SeasonId") seasonId: String? = null,
        @Query("Limit") limit: Int? = null,
        @Query("Fields") fields: String = EPISODE_FIELDS,
    ): QueryResultDto<ItemDto>

    /**
     * 收藏条目。
     *
     * @param uid 用户 id。
     * @param itemId 条目 id。
     * @return 更新后的用户数据。
     */
    @POST("Users/{uid}/FavoriteItems/{itemId}")
    suspend fun addFavorite(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
    ): UserDataDto

    /**
     * 取消收藏。
     *
     * @param uid 用户 id。
     * @param itemId 条目 id。
     * @return 更新后的用户数据。
     */
    @DELETE("Users/{uid}/FavoriteItems/{itemId}")
    suspend fun removeFavorite(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
    ): UserDataDto

    /**
     * 标为已看。
     *
     * @param uid 用户 id。
     * @param itemId 条目 id。
     * @return 更新后的用户数据。
     */
    @POST("Users/{uid}/PlayedItems/{itemId}")
    suspend fun markPlayed(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
    ): UserDataDto

    /**
     * 标为未看。
     *
     * @param uid 用户 id。
     * @param itemId 条目 id。
     * @return 更新后的用户数据。
     */
    @DELETE("Users/{uid}/PlayedItems/{itemId}")
    suspend fun markUnplayed(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
    ): UserDataDto

    /** 更新完整用户数据；批量标记时用标记响应复制并清除续播位置，保留收藏、次数等字段。 */
    @POST("Users/{uid}/Items/{itemId}/UserData")
    suspend fun updateUserData(
        @Path("uid") uid: String,
        @Path("itemId") itemId: String,
        @Body body: UserDataUpdateDto,
    )

    /**
     * 取播放信息：服务端据此下发直连或转码地址。
     *
     * @param itemId 条目 id。
     * @param userId 用户 id。
     * @param body 设备能力描述。
     */
    @POST("Items/{itemId}/PlaybackInfo")
    suspend fun playbackInfo(
        @Path("itemId") itemId: String,
        @Query("UserId") userId: String,
        @Body body: PlaybackInfoRequestDto = PlaybackInfoRequestDto(),
    ): PlaybackInfoDto

    /**
     * 开始播放上报（服务端据此在「继续观看」里记录条目）。
     *
     * @param body 进度体。
     */
    @POST("Sessions/Playing")
    suspend fun reportPlaybackStarted(@Body body: PlaybackProgressDto)

    /**
     * 播放中定时上报。
     *
     * @param body 进度体。
     */
    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: PlaybackProgressDto)

    /**
     * 停止播放上报（退出播放页时调用）。
     *
     * @param body 进度体。
     */
    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: PlaybackProgressDto)

    /** 与 Emby 交互时统一请求的字段集合，避免不同调用点字段不一致。 */
    companion object {
        /**
         * 通用列表字段。
         *
         * `SeasonId` 显式列出：详情页默认季定位优先按分集所属季 id 匹配
         * （NextUp/Resume 提示集都走这里），严格按 `Fields` 裁剪的服务端不列
         * 就不下发，会让定位退化成按季序号猜、错误落到第一季。
         */
        const val DEFAULT_FIELDS =
            "PrimaryImageAspectRatio,ProductionYear,CommunityRating,RunTimeTicks,UserData,Genres,Tags," +
                "Overview,ImageTags,BackdropImageTags,ChildCount,PremiereDate,SeriesId,SeriesName," +
                "SeriesPrimaryImageTag,SeriesThumbImageTag,IndexNumber,ParentIndexNumber,SeasonId," +
                "DateCreated,DateLastMediaAdded,MediaStreams,OfficialRating"

        /** 详情页字段在通用字段上追加演职员、出品公司、分级与宣传语。 */
        const val DETAIL_FIELDS =
            "$DEFAULT_FIELDS,People,Studios,OfficialRating,Taglines,Status,Chapters,MediaSources"

        /** 季列表字段。 */
        const val SEASON_FIELDS = "ChildCount,RecursiveItemCount,UserData,ImageTags,PremiereDate"

        /** 分集列表字段（`SeasonId` 同 [DEFAULT_FIELDS] 的理由必须显式请求）。 */
        const val EPISODE_FIELDS =
            "Overview,RunTimeTicks,UserData,ImageTags,PremiereDate,IndexNumber,ParentIndexNumber,SeasonId," +
                "MediaSources,SeriesId,SeriesPrimaryImageTag,SeriesThumbImageTag"
    }
}
