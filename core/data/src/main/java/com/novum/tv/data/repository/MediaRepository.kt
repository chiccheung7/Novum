package com.novum.tv.data.repository

import com.novum.tv.core.result.NovumResult
import com.novum.tv.model.CastMember
import com.novum.tv.model.Episode
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season

/**
 * 媒体库浏览仓储。
 *
 * 只负责「读」：媒体库列表、分页条目、最新入库。
 * 收藏 / 用户数据等写操作放到后续的 UserDataRepository。
 */
interface MediaRepository {

    /**
     * 当前用户可见的媒体库列表。
     *
     * @return 成功时按服务端顺序返回；未登录或网络失败时返回错误。
     */
    suspend fun libraries(): NovumResult<List<MediaLibrary>>

    /**
     * 分页读取某个媒体库（或全库）的条目。
     *
     * @param parentId 媒体库 id；为空表示全库递归。
     * @param startIndex 起始下标。
     * @param limit 单页条数。
     * @param sortBy 排序字段：`DateCreated` / `PremiereDate` / `CommunityRating`。
     * @param ascending 是否升序。
     * @param filters 服务端过滤条件，如 `IsFavorite`（收藏列表）。
     * @param searchTerm 搜索关键词；为空表示不搜索。
     * @param includeItemTypes 类型筛选（`Movie` / `Series`）；为空表示不过滤。
     */
    suspend fun items(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String = "DateCreated",
        ascending: Boolean = false,
        filters: String? = null,
        searchTerm: String? = null,
        includeItemTypes: String? = null,
    ): NovumResult<MediaPage>

    /**
     * 读取单条目详情。
     *
     * @param itemId 条目 id。
     */
    suspend fun detail(itemId: String): NovumResult<MediaDetail>

    /**
     * 读取人物的全部作品（详情页演职员头像点击 → 人物作品页）。
     *
     * 提供默认实现（空结果）：测试 Fake 只覆盖用到的路径，不必逐个补桩。
     *
     * @param personId 人物条目 id（详情 People[].Id）。
     */
    suspend fun personWorks(personId: String): NovumResult<List<MediaItem>> =
        NovumResult.Success(emptyList())

    /**
     * 按名字搜索人物（演员/导演）。
     *
     * 服务端 `Persons` 端点为主（覆盖未浏览过的演员），本地索引为兜底。
     * 默认空结果：测试 Fake 不必补桩。
     *
     * @param query 关键词。
     * @param limit 返回条数上限。
     */
    suspend fun searchPersons(query: String, limit: Int = 20): List<PersonRef> = emptyList()

    /**
     * 预热搜索目录（搜索页打开时调用）。
     *
     * 服务端 `SearchTerm` 对中文只认从头匹配，本地目录负责任意位置匹配；
     * 目录是懒建的，预热只是把等待挪到用户打字之前。
     */
    suspend fun warmSearchCatalog() = Unit

    /**
     * 读取剧集的全部季。
     *
     * @param seriesId 剧集 id。
     */
    suspend fun seasons(seriesId: String): NovumResult<List<Season>>

    /**
     * 读取某季的分集。
     *
     * @param seriesId 剧集 id。
     * @param seasonId 季 id；为空表示全部季。
     */
    suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>>

    /**
     * 读取指定剧集的「下一集」（Emby NextUp 语义，详情页默认定位）。
     *
     * 一次查询覆盖三种默认态：没看过→第一集；看到一半→该集；追平后服务端
     * 上新（新一季/新一集）→新集。全部看完且无新内容时返回 null。
     *
     * @param seriesId 剧集 id。
     */
    suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?>

    /**
     * 读取某季的演职员（按该季各集 People 合并，主创优先）。
     *
     * 剧集条目级的 `People` 是全剧口径，分季客串/换角无法体现；详情页切季时
     * 用它替换演职员行。
     *
     * 默认返回空列表：测试 Fake 不覆盖该路径时，页面回落到剧集级演职员。
     *
     * @param seriesId 剧集 id。
     * @param seasonId 季 id。
     */
    suspend fun seasonCast(seriesId: String, seasonId: String): NovumResult<List<CastMember>> =
        NovumResult.Success(emptyList())

    /**
     * 收藏 / 取消收藏。
     *
     * @param itemId 条目 id。
     * @param favorite true 收藏，false 取消。
     * @return 服务端确认后的收藏状态。
     */
    suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean>

    /**
     * 标为已看 / 未看。
     *
     * @param itemId 条目 id。
     * @param played true 标为已看，false 标为未看。
     * @return 服务端确认后的已看状态。
     */
    suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean>

    /**
     * 读取首页聚合数据。
     *
     * 五个区块（Hero / 继续观看 / 下一集 / 各库最新 / 高分精选）一次并发拉取，
     * 单个区块失败只让该区块为空，不影响整页。
     *
     * @return 成功时返回 [HomeFeed]；未登录返回 `Unauthorized`。
     */
    suspend fun feed(): NovumResult<HomeFeed>
}
