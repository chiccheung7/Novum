package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.network.toNovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.failureOf
import com.novum.tv.core.result.successOf
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.MediaSourceDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.SessionSnapshot
import com.novum.tv.data.remote.emby.UserDataUpdateDto
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.model.CastMember
import com.novum.tv.model.Chapter
import com.novum.tv.model.Episode
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.MediaTrackOption
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 媒体库浏览仓储默认实现。
 *
 * 依赖 [SessionHolder] 提供服务器与用户 id：未登录时直接返回 `Unauthorized`，
 * 不发起任何网络请求。
 *
 * @param clients Emby API 提供者。
 * @param sessionHolder 当前会话（服务器、用户、Token）。
 * @param dispatchers 调度器，网络与映射统一切到 IO。
 */
class DefaultMediaRepository @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val dispatchers: DispatcherProvider,
    private val strings: MediaStrings,
    private val logger: NovumLogger,
) : MediaRepository {

    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = withSession { owner ->
        val server = owner.server
        val userId = owner.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withSession failureOf(NovumError.Unauthorized())
        }
        safeCall {
            clients.api(server, owner)
                .views(uid = userId)
                .Items
                .mapNotNull { dto -> dto.toLibrary(server) }
        }
    }

    override suspend fun items(
        parentId: String?,
        startIndex: Int,
        limit: Int,
        sortBy: String,
        ascending: Boolean,
        filters: String?,
        searchTerm: String?,
        includeItemTypes: String?,
    ): NovumResult<MediaPage> = withSession { owner ->
        val server = owner.server
        val userId = owner.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withSession failureOf(NovumError.Unauthorized())
        }
        safeCall {
            val response = clients.api(server, owner).items(
                uid = userId,
                parentId = parentId,
                startIndex = startIndex,
                limit = limit,
                sortBy = sortBy,
                sortOrder = if (ascending) "Ascending" else "Descending",
                filters = filters,
                searchTerm = searchTerm,
                includeItemTypes = includeItemTypes,
                fields = EmbyApi.DEFAULT_FIELDS,
            )
            MediaPage(
                items = response.Items.mapNotNull { dto -> dto.toMediaItem(server, strings) },
                total = response.TotalRecordCount,
            )
        }
    }

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> = withSession { owner ->
        val server = owner.server
        val userId = owner.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withSession failureOf(NovumError.Unauthorized())
        }
        safeCall {
            val detail = clients.api(server, owner)
                .detail(uid = userId, itemId = itemId)
                .toDetail(server = server, fallbackId = itemId, strings = strings)
            detail
        }
    }

    /** 人物全部作品：按 PersonIds 过滤，并按首播日期和年份倒序。 */
    override suspend fun personWorks(personId: String): NovumResult<List<MediaItem>> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server, owner)
                val works = LinkedHashMap<String, MediaItem>()
                var startIndex = 0
                while (true) {
                    val page = api.items(
                        uid = userId,
                        personIds = personId,
                        recursive = true,
                        includeItemTypes = "Movie,Series",
                        sortBy = "PremiereDate,ProductionYear",
                        sortOrder = "Descending",
                        startIndex = startIndex,
                        limit = PERSON_WORKS_PAGE_SIZE,
                    )
                    if (page.Items.isEmpty()) break
                    val mapped = page.Items.mapNotNull { dto -> dto.toMediaItem(server, strings) }
                    // Some gateways ignore StartIndex. A repeated page must not loop forever.
                    if (mapped.none { it.id !in works }) break
                    mapped.forEach { item -> works.putIfAbsent(item.id, item) }
                    startIndex += page.Items.size
                    if (page.TotalRecordCount > 0 && startIndex >= page.TotalRecordCount) break
                    if (page.TotalRecordCount <= 0 && page.Items.size < PERSON_WORKS_PAGE_SIZE) break
                }
                works.values.toList()
            }
        }

    /** 人物搜索只映射服务器 `/Persons` 响应，不查询本地索引或派生本地候选。 */
    override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> {
        val owner = sessionHolder.capture()
        val people = withContext(dispatchers.io) {
            val server = owner.server
            val userId = owner.userId
            val keyword = query.trim()
            if (sessionHolder.capture() !== owner || server.isBlank() || userId.isBlank() || keyword.isEmpty()) {
                return@withContext emptyList()
            }
            safeCall {
                clients.api(server, owner)
                    .persons(userId = userId, searchTerm = keyword, limit = limit)
                    .Items
                    // 有的网关把 /Persons 路由到了普通条目搜索：按 Type 过滤，
                    // 只有确凿是 Person 的才采纳（缺 Type 的按惯例视为人物）
                    .mapNotNull { dto ->
                        val type = dto.Type?.trim()
                        if (type != null && !type.equals("Person", ignoreCase = true)) {
                            null
                        } else {
                            dto.toPersonRef(server)
                        }
                    }
            }.let { result ->
                when (result) {
                    is NovumResult.Success -> result.value.take(limit)
                    is NovumResult.Failure -> {
                        logger.w(TAG, "服务器人物搜索失败：$keyword")
                        emptyList()
                    }
                }
            }
        }
        return if (sessionHolder.capture() === owner) people else emptyList()
    }

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                clients.api(server, owner)
                    .seasons(seriesId = seriesId, userId = userId)
                    .Items
                    .mapIndexedNotNull { index, dto -> dto.toSeason(index, strings) }
                    .sortedBy { it.index }
            }
        }

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                clients.api(server, owner)
                    .episodes(seriesId = seriesId, userId = userId, seasonId = seasonId)
                    .Items
                    .mapIndexedNotNull { index, dto -> dto.toEpisode(server, index, strings) }
                    // 服务端偶尔把同一集下发两次（多版本/虚拟集）：不去重会让
                    // 选集列表出现重复 key（LazyRow 直接崩溃），也会让集号跳号
                    .distinctBy { it.id }
                    // 全季口径（seasonId = null）时先按季再按集：只按集号会把各季同号集交错
                    .sortedWith(compareBy({ it.seasonIndex }, { it.index }))
            }
        }

    /**
     * 季级演职员：取该季分集的 `People[]` 合并。
     *
     * 剧集条目只挂全剧演职员，季级换角/客串只能从分集拿。按出现集数排序，
     * 主角（每集都在）在前、客串在后；请求失败由页面回落剧集级演职员。
     */
    override suspend fun seasonCast(seriesId: String, seasonId: String): NovumResult<List<CastMember>> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val cast = clients.api(server, owner)
                    .episodes(
                        seriesId = seriesId,
                        userId = userId,
                        seasonId = seasonId,
                        // 长剧集不必整季拉取：前若干集已覆盖常驻主创与主要客串
                        limit = CAST_SAMPLE_EPISODES,
                        fields = "${EmbyApi.EPISODE_FIELDS},People",
                    )
                    .Items
                    .toSeasonCast(server, strings)
                cast
            }
        }

    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server, owner)
                // 单剧集 NextUp——详情页默认定位的「当前集」。字段用
                // 分集口径（带 MediaSources），让焦点集的多版本信息开箱即用；
                // 全部看完且无上新时服务端返回空列表 → null（页面回落剧集级）。
                //
                // 部分 Emby 兼容网关会忽略 SeriesId，直接返回全库 NextUp。
                // 必须在客户端按 SeriesId 再过滤，否则详情页会拿别的剧集的分集当
                // 定位锚点，默认季随之错到第一季（提示集不在本剧任何一季里）。
                // 取一批而不是 1 条：网关忽略 SeriesId 时，本剧的那条会排在这批
                // 里面，limit=1 就只能拿到别剧的分集然后整体失败。
                val candidates = api.nextUp(
                    uid = userId,
                    limit = NEXTUP_HINT_LIMIT,
                    fields = EmbyApi.EPISODE_FIELDS,
                    seriesId = seriesId,
                ).Items
                val next = candidates.firstOrNull { dto -> dto.SeriesId == seriesId }
                    // 极简网关可能完全不下发 SeriesId（无从核对）：只有整批都缺该
                    // 字段时才采信首条，避免把「有 SeriesId 但都不是本剧」当本剧
                    ?: candidates
                        .takeIf { list -> list.isNotEmpty() && list.all { it.SeriesId == null } }
                        ?.first()
                // NextUp 被网关忽略/为空时，回落到该剧最近的续播分集（同样按
                // SeriesId 过滤，防止 ParentId 也被忽略）。仍无进度则返回 null。
                val hint = next ?: api.resume(
                    uid = userId,
                    limit = RESUME_HINT_LIMIT,
                    parentId = seriesId,
                ).Items.firstOrNull { dto -> dto.SeriesId == seriesId }
                hint?.let { dto -> dto.toEpisode(server, index = 0, strings = strings) }
            }
        }

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server, owner)
                val data = if (favorite) {
                    api.addFavorite(uid = userId, itemId = itemId)
                } else {
                    api.removeFavorite(uid = userId, itemId = itemId)
                }
                data.IsFavorite
            }
        }

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
        withSession { owner ->
            val server = owner.server
            val userId = owner.userId
            if (server.isBlank() || userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(server, owner)
                val data = if (played) {
                    api.markPlayed(uid = userId, itemId = itemId)
                } else {
                    api.markUnplayed(uid = userId, itemId = itemId)
                }
                data.Played
            }
        }

    override suspend fun setPlayedAndResetProgress(itemId: String, played: Boolean): NovumResult<Boolean> =
        withSession { owner ->
            if (owner.server.isBlank() || owner.userId.isBlank()) {
                return@withSession failureOf(NovumError.Unauthorized())
            }
            safeCall {
                val api = clients.api(owner.server, owner)
                val data = if (played) api.markPlayed(owner.userId, itemId) else api.markUnplayed(owner.userId, itemId)
                api.updateUserData(
                    uid = owner.userId,
                    itemId = itemId,
                    body = UserDataUpdateDto(
                        PlaybackPositionTicks = 0L,
                        Played = data.Played,
                        PlayedPercentage = 0.0,
                        IsFavorite = data.IsFavorite,
                        PlayCount = data.PlayCount,
                        UnplayedItemCount = data.UnplayedItemCount,
                        Rating = data.Rating,
                        LastPlayedDate = data.LastPlayedDate,
                        Key = data.Key,
                        ItemId = data.ItemId,
                        ServerId = data.ServerId,
                    ),
                )
                data.Played
            }
        }

    override suspend fun feed(): NovumResult<HomeFeed> = withSession { owner ->
        val server = owner.server
        val userId = owner.userId
        if (server.isBlank() || userId.isBlank()) {
            return@withSession failureOf(NovumError.Unauthorized())
        }
        safeCall {
            val api = clients.api(server, owner)
            // 影片/剧集库按原顺序展示；全库追新可能是混合库，按名称识别。
            val libraries = api.views(uid = userId).Items
                .mapNotNull { dto -> dto.toLibrary(server) }
                .filter { it.kind == "movies" || it.kind == "tvshows" || it.isRecentUpdatesSource }
            val recentLibraryId = libraries.firstOrNull { it.isRecentUpdatesSource }?.id
            coroutineScope {
                // 各区块并发拉取，单个区块失败只让该区块为空，不拖垮整页
                val resumeItems = asyncOrNull { api.resume(uid = userId).Items }
                val nextUpItems = asyncOrNull { api.nextUp(uid = userId).Items }
                val latestPerLibrary = libraries.map { library ->
                    asyncOrNull {
                        library.id to api.latest(
                            uid = userId,
                            parentId = library.id,
                            limit = if (library.id == recentLibraryId) HERO_CANDIDATE_LIMIT else HOME_LATEST_ROW_LIMIT,
                        )
                    }
                }

                // 各库最新：保留原始 DTO（Hero 需要背景图标签），再映射成行数据
                val rawLatest = latestPerLibrary.mapNotNull { deferred ->
                    val (libraryId, items) = deferred.await() ?: return@mapNotNull null
                    val library = libraries.firstOrNull { it.id == libraryId } ?: return@mapNotNull null
                    library to items
                }
                val latest = rawLatest.mapNotNull { (library, items) ->
                    val entries = items.take(HOME_LATEST_ROW_LIMIT).mapNotNull { dto -> dto.toHomeEntry(server, strings) }
                    if (entries.isEmpty()) {
                        null
                    } else {
                        HomeLatestRow(
                            libraryId = library.id,
                            libraryName = if (library.id == recentLibraryId) strings.recentBroadcastsTitle() else library.name,
                            items = entries,
                        )
                    }
                }.let { rows ->
                    rows.filter { it.libraryId == recentLibraryId } + rows.filter { it.libraryId != recentLibraryId }
                }

                val resumeDtoItems = resumeItems.await().orEmpty()
                // 正在续播的条目不占用 Hero 推荐位，避免重复展示。
                val resumeIds = resumeDtoItems.mapNotNull { dto -> dto.Id }.toSet()
                // 继续观看行合并 NextUp：播放中的集保留在行内，看完或追平的剧集由 NextUp 递补。
                // 同一剧集只保留一张卡：
                // 在看的优先，NextUp 条目按分集 id + 剧集 id 双重去重后追加行尾。
                val resumeSeriesIds = resumeDtoItems.mapNotNull { dto -> dto.SeriesId }.toSet()
                val nextUpEntries = nextUpItems.await().orEmpty()
                    .filter { dto -> dto.isUnfinishedForResume() }
                    .filter { dto -> dto.Id !in resumeIds && dto.SeriesId !in resumeSeriesIds }
                    // 同剧集的多个 NextUp 只留首个；SeriesId 缺失时按分集 id 去重，
                    // 不能把所有无剧集归属的条目折叠成一张卡。
                    .distinctBy { dto -> dto.SeriesId ?: dto.Id }
                val mergedResume = (resumeDtoItems.map { it to false } + nextUpEntries.map { it to true })
                    .distinctBy { (dto, _) -> dto.SeriesId ?: dto.Id }
                    .take(HOME_ROW_RESUME_LIMIT)
                // Hero 从全库追新的较大候选池取样；源库不可用时保持为空。
                val recentItems = rawLatest.firstOrNull { (library, _) -> library.id == recentLibraryId }
                    ?.second.orEmpty()
                val hero = heroRotationPool(recentItems.filter { dto -> dto.Id !in resumeIds })
                    // /Items/Latest 不含 MediaStreams 和 OfficialRating；按 Ids 回捞完整字段，
                    // 请求失败时保留原 DTO。
                    .let { pool ->
                        val poolIds = pool.mapNotNull { it.Id }
                        val fullItems = if (poolIds.isEmpty()) {
                            null
                        } else {
                            asyncOrNull {
                                api.items(
                                    uid = userId,
                                    ids = poolIds.joinToString(","),
                                    limit = poolIds.size,
                                ).Items
                            }.await().orEmpty()
                        }
                        val fullById = fullItems?.associateBy { it.Id }
                        pool.mapNotNull { dto ->
                            val full = fullById?.get(dto.Id)
                            // 部分 Latest 接口不含分类；完整字段新发现的动画标签也要排除。
                            if (full?.hasAnimationLabel() == true) return@mapNotNull null
                            val enriched = full?.copy(
                                BackdropImageTags = full.BackdropImageTags
                                    .takeIf { it.firstOrNull()?.isNotBlank() == true } ?: dto.BackdropImageTags,
                            ) ?: dto
                            enriched.toHomeEntry(server, strings)
                        }
                    }

                HomeFeed(
                    hero = hero,
                    resume = mergedResume
                        .mapNotNull { (dto, isNextUp) ->
                            dto.toHomeEntry(server, strings)?.copy(isNextUp = isNextUp)
                        }
                        .toRow(HOME_ROW_RESUME),
                    // 首页按媒体库目录构建分库内容行。
                    libraries = libraries,
                    nextUp = null,
                    latest = latest,
                )
            }
        }
    }

    /** Capture before IO dispatch so a queued action cannot silently move to a different account. */
    private suspend fun <T> withSession(
        block: suspend (SessionSnapshot) -> NovumResult<T>,
    ): NovumResult<T> {
        val owner = sessionHolder.capture()
        val result = withContext(dispatchers.io) {
            if (sessionHolder.capture() !== owner) {
                failureOf(NovumError.Unauthorized())
            } else {
                block(owner)
            }
        }
        return if (sessionHolder.capture() === owner) result else failureOf(NovumError.Unauthorized())
    }

    /** 统一异常映射，保证仓储不向 UI 抛异常。 */
    private suspend fun <T> safeCall(block: suspend () -> T): NovumResult<T> = try {
        successOf(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        failureOf(throwable.toNovumError())
    }
}

/**
 * NextUp 条目是否适合放进「继续观看」行。
 *
 * NextUp 服务端偶尔会把已看完/已追平的剧集再次返回；这类条目没有可续播进度，
 * 放进继续观看行会出现「点进去没有进度」的空壳卡。UserData 缺失时不过滤，
 * 避免服务端精简字段导致所有 NextUp 被误杀。
 */
private fun com.novum.tv.data.remote.emby.ItemDto.isUnfinishedForResume(): Boolean {
    val data = UserData ?: return true
    return !data.Played && data.PlayedPercentage < 100.0
}

/** 日志标签。 */
private const val TAG = "MediaRepo"

/** 季级演职员取样集数：足够覆盖常驻主创与主要客串，又不至于整季大请求。 */
private const val CAST_SAMPLE_EPISODES = 24

private const val PERSON_WORKS_PAGE_SIZE = 60

/** NextUp 为空时回查续播列表的条数上限。 */
private const val RESUME_HINT_LIMIT = 12

/**
 * 单剧集 NextUp 的取样条数：网关忽略 `SeriesId` 时用来在客户端捞出本剧那条，
 * 太小会漏、太大浪费，8 条足够覆盖若干在追剧集。
 */
private const val NEXTUP_HINT_LIMIT = 8
