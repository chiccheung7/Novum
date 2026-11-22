package com.novum.tv.ui.screen.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import android.content.Context
import com.novum.tv.core.common.feedback.ToastSink
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.Season
import com.novum.tv.navigation.DetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 详情页 UI 状态。
 *
 * @param detail 条目详情三态。
 * @param seasons 剧集的季列表；电影为空。
 * @param episodes 当前选中季的分集；电影为空。
 * @param selectedSeasonId 当前选中的季 id。
 * @param favoriteBusy 收藏请求进行中，用于禁用按钮防连点。
 * @param favoriteFailed 收藏失败需要页内提示一次（范式：错误不用 Toast，用内联提示）。
 * @param playedBusy 标为已看请求进行中。
 * @param playedFailed 标为已看失败需要页内提示一次。
 * @param episodesFailed 季/分集加载失败：剧集区给出内联重试入口，否则播放按钮会永久禁用且无处恢复。
 */
data class DetailUiState(
    val detail: UiState<MediaDetail> = UiState.Loading,
    val seasons: List<Season> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val selectedSeasonId: String? = null,
    val favoriteBusy: Boolean = false,
    val favoriteFailed: Boolean = false,
    val playedBusy: Boolean = false,
    val playedFailed: Boolean = false,
    val episodesFailed: Boolean = false,

    /**
     * 优先视频质量策略（设置页 `AppSettings.versionStrategy`，加
     * 「每次询问」档）：`ask` = 播放按钮 OK 时先弹版本选择；其余档由播放层按
     * 策略挑版本。空串 = 默认档。
     */
    val versionStrategy: String = "",

    /**
     * 「当前集」id（默认定位）：ViewModel 按服务端 NextUp 给出——没看过
     * →第一集；看到一半→该集；追平后上新→新集。页面据此初始化信息块显示并
     * 把选集行滚动到该集。用户手动切季后清空（定位只服务进场），null = 无提示
     * （全部看完且无上新 / NextUp 失败），信息块回落剧集级。
     */
    val currentEpisodeId: String? = null,

    /**
     * 选集卡选中集 id（「点卡只切当前集」）：非 null 时播放/
     * 从头播放/已看三个图标与页面背景跟随该集。**不再切换标题与简介**——
     * 剧集详情页信息块恒为剧集内容，分集标题与简介在选集卡下方。
     * null = 未手动选中（图标按默认播放目标集渲染）。进入路径：分集卡 OK 选中
     * （[selectEpisode]）或继续观看入口直达（路由 focusCurrentEpisode + NextUp
     * 锚点）；返回键清空（[clearEpisodeContext]，焦点还给该卡）。切季/整页重载清空。
     */
    val episodeContextId: String? = null,

    /**
     * 集级语境是否来自进场直达（继续观看入口）：true 时页面把首焦交给播放
     * 按钮；用户 OK 选中（[selectEpisode]）后置 false——选中走「留卡」规则，
     * 焦点保持在分集卡上不被抢走。
     */
    val episodeContextFromEntry: Boolean = false,
) {

    /** 详情数据，非成功态为 null。 */
    val data: MediaDetail? get() = (detail as? UiState.Success)?.data

    /**
     * 播放目标条目 id。
     *
     * 电影直接播自身；剧集优先播「未看完且未开始」的第一集，其次第一集。
     * 没有可播内容时返回 null，界面禁用播放按钮。
     */
    val playTargetId: String?
        get() {
            val item = data ?: return null
            if (!item.isSeries) return item.id
            val next = episodes.firstOrNull { !it.played } ?: episodes.firstOrNull()
            return next?.id
        }

    /** 默认播放目标分集（剧集时：第一个未看完的，其次第一集）；电影为 null。 */
    val playTargetEpisode: Episode?
        get() {
            val item = data ?: return null
            if (!item.isSeries) return null
            return episodes.firstOrNull { !it.played } ?: episodes.firstOrNull()
        }

    /**
     * 「当前集」：手动选中的集优先 → 进场 NextUp 锚点 → 默认播放目标集。
     *
     * 锚点优先是已看标记稳定性的关键：剧级态把当前集标为已看后，若仍从
     * `episodes.firstOrNull { !it.played }` 实时推算，当前集会立刻跳到下一集，
     * 造成操作行图标/信息区"跳集"。锚点（[currentEpisodeId]）由进场 NextUp
     * 一次性确定、切季才清空，标记已看不会改它，故当前集保持稳定。
     */
    val currentEpisode: Episode?
        get() = episodeContextId?.let { id -> episodes.firstOrNull { it.id == id } }
            ?: currentEpisodeId?.let { id -> episodes.firstOrNull { it.id == id } }
            ?: playTargetEpisode

    /**
     * 全剧已看聚合（"已看 x/y 集"）。
     *
     * 只统计每一季都给出 `episodeCount` 与 `unplayedCount` 的完整数据，
     * 任一季缺字段即返回 null（界面不显示，不编造分母）。调用方据此在剧集
     * 信息区展示聚合进度；x = 总集数 − 未看集数之和，y = 总集数。
     */
    val watchedEpisodeCount: Int?
        get() = if (seasons.isEmpty() || seasons.any { it.episodeCount == null || it.unplayedCount == null }) {
            null
        } else {
            seasons.sumOf { (it.episodeCount ?: 0) - (it.unplayedCount ?: 0) }.coerceAtLeast(0)
        }

    /** 全剧总集数（与 [watchedEpisodeCount] 同口径；缺字段为 null）。 */
    val totalEpisodeCount: Int?
        get() = if (seasons.isEmpty() || seasons.any { it.episodeCount == null }) {
            null
        } else {
            seasons.sumOf { it.episodeCount ?: 0 }
        }

    /** 当前集的多版本选项（电影/剧集条目则看条目级）；单版本为空。 */
    val currentVersions: List<MediaVersionOption>
        get() {
            val item = data ?: return emptyList()
            return currentEpisode?.versions ?: item.versions
        }
}

/**
 * 详情页 ViewModel。
 *
 * 加载策略：
 * 1. 先取条目详情并展示
 * 2. 若是剧集，再取季列表并自动选中第一季、加载该季分集
 * 3. 任一失败只影响对应区域，详情已展示时不会整页回退到错误态。
 *
 * @param repository 媒体库仓储。
 * @param toast 全局提示队列，收藏结果经它反馈。
 * @param settings 设置仓储：读「优先视频质量」策略（`ask` = 播放前弹版本选择）。
 * @param logger 日志门面（设置流失败留痕）。
 * @param appContext 应用上下文，仅用于取字符串资源。
 * @param savedStateHandle 路由参数载体，用于取 [DetailRoute.itemId]。
 */
@HiltViewModel
class DetailViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val toast: ToastSink,
    private val settings: SettingsRepository,
    private val logger: NovumLogger,
    @ApplicationContext private val appContext: Context,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 当前详情条目 id。 */
    val itemId: String = savedStateHandle.toRoute<DetailRoute>().itemId

    /** 继续观看入口直达集级语境（路由参数，仅进场定位用，见 [loadEpisodes]）。 */
    private val entryFocusCurrentEpisode: Boolean =
        savedStateHandle.toRoute<DetailRoute>().focusCurrentEpisode

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        load()
        // 优先视频质量策略：设置页改动即时反映到播放按钮行为（「每次询问」档
        // 需要页面在点击时就知道），失败不影响页面（保持默认档）
        viewModelScope.launch {
            // 设置流异常通过 catch 记录，失败时继续使用默认策略。
            settings.settings
                .catch { logger.w(TAG, "设置流读取失败（画质策略保持默认）", it) }
                .collect { snapshot ->
                    _state.update { it.copy(versionStrategy = snapshot.versionStrategy) }
                }
        }
    }

    /** 首次加载或错误态重试。 */
    fun load() {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    detail = UiState.Loading,
                    seasons = emptyList(),
                    episodes = emptyList(),
                    // 整页重载回剧级态：集级语境指向的分集随重载失效
                    episodeContextId = null,
                    episodeContextFromEntry = false,
                )
            }
            when (val result = repository.detail(itemId)) {
                is NovumResult.Success -> {
                    _state.update { it.copy(detail = UiState.Success(result.value)) }
                    if (result.value.isSeries) loadSeasons()
                }

                is NovumResult.Failure -> {
                    _state.update { it.copy(detail = UiState.Error(result.error)) }
                }
            }
        }
    }

    /**
     * 切换季。
     *
     * @param seasonId 目标季 id。
     */
    fun selectSeason(seasonId: String) {
        if (_state.value.selectedSeasonId == seasonId) return
        // 手动切季清空当前集定位——它只服务进场，换季后由切季首焦
        // （第一未看集）接管，旧季的集 id 留着会让信息块指向已不存在的卡
        _state.update {
            it.copy(
                selectedSeasonId = seasonId,
                episodes = emptyList(),
                episodesFailed = false,
                currentEpisodeId = null,
                // 集级语境只可能指向旧季分集，一并清空回剧级态
                episodeContextId = null,
                episodeContextFromEntry = false,
            )
        }
        viewModelScope.launch { loadEpisodes(seasonId) }
    }

    /**
     * 分集卡 OK 选中（「点卡只切当前集」）：当前集切为该集
     * 播放/从头播放/已看图标与页面背景随之变化；**标题与简介不变**（剧集详情页
     * 信息块恒为剧集内容，分集标题/简介在选集卡下方）。选中走「留卡」规则：
     * [DetailUiState.episodeContextFromEntry] 置 false，页面不抢焦点。
     *
     * @param episodeId 选中的分集 id。
     */
    fun selectEpisode(episodeId: String) {
        _state.update {
            it.copy(episodeContextId = episodeId, episodeContextFromEntry = false)
        }
    }

    /** 返回键清空当前集选中（图标回落默认播放目标集，焦点由页面还给那张卡）。 */
    fun clearEpisodeContext() {
        _state.update {
            it.copy(episodeContextId = null, episodeContextFromEntry = false)
        }
    }

    /**
     * 剧集区内联重试：季列表失败重拉整条链，仅分集失败只重拉当前季。
     */
    fun retryEpisodes() {
        if (_state.value.data?.isSeries != true) return
        viewModelScope.launch {
            _state.update { it.copy(episodesFailed = false) }
            val snapshot = _state.value
            val seasonId = snapshot.selectedSeasonId
            if (snapshot.seasons.isEmpty()) {
                loadSeasons()
            } else if (seasonId != null) {
                loadEpisodes(seasonId)
            }
        }
    }

    /** 收藏失败提示已被消费，避免重组后重复显示。 */
    fun consumeFavoriteFailure() {
        _state.update { it.copy(favoriteFailed = false) }
    }

    /** 标为已看失败提示已被消费，避免重组后重复显示。 */
    fun consumePlayedFailure() {
        _state.update { it.copy(playedFailed = false) }
    }

    /**
     * 整季批量标记：对本季各集分别发起请求，遇错误时报告部分失败。
     *
     * 只打**本季的分集**，绝不打剧集（series）本身——`PlayedItems` 对 series 会
     * 服务端级联全部季。剧集本身的已看/续播
     * 态标记完成后重拉详情，由服务端按全部季聚合（标完本季全剧看完时 series.Played
     * 自然翻 true），失败保持本季乐观态、下次进页刷新。
     *
     * @param played true 整季标为已看，false 整季标为未看。
     */
    fun markSeasonPlayed(played: Boolean) {
        val episodes = _state.value.episodes
        if (episodes.isEmpty() || _state.value.playedBusy) return
        viewModelScope.launch {
            _state.update { it.copy(playedBusy = true, playedFailed = false) }
            var failures = 0
            // 记录真正成功的集 id：失败的集绝不能本地标成目标态——
            // 否则「部分失败」提示说了失败，界面却显示全标，与服务端不一致。
            val succeeded = mutableSetOf<String>()
            // 并发 6 路请求（串行逐集太慢）；succeeded/failures 全部在 Main 协程
            // 内读写（仓储内部自行切 IO），无并发竞争。
            val semaphore = Semaphore(6)
            coroutineScope {
                episodes.map { episode ->
                    async {
                        if (episode.played == played) {
                            // 本来就处于目标态，无需请求，也算"已到位"
                            succeeded += episode.id
                            return@async
                        }
                        semaphore.withPermit {
                            when (repository.setPlayed(episode.id, played)) {
                                is NovumResult.Success -> succeeded += episode.id
                                else -> failures += 1
                            }
                        }
                    }
                }.awaitAll()
            }
            // 本季未看数按"实际到位的集数"重算（全部到位 → 0 或全集数），
            // 让"已看 x/y 集"与批量标记结果一致。
            fun applyResult(current: DetailUiState, detail: UiState<MediaDetail>): DetailUiState {
                val seasonIndex = episodes.firstOrNull()?.seasonIndex
                val total = episodes.size
                val newUnplayed = if (played) total - succeeded.size else succeeded.size
                val seasons = if (seasonIndex == null) {
                    current.seasons
                } else {
                    current.seasons.map { s ->
                        if (s.index == seasonIndex) s.copy(unplayedCount = newUnplayed) else s
                    }
                }
                return current.copy(
                    playedBusy = false,
                    episodes = current.episodes.map { ep ->
                        if (ep.id in succeeded) {
                            ep.copy(played = played, progressMs = if (played) 0L else ep.progressMs)
                        } else {
                            ep
                        }
                    },
                    seasons = seasons,
                    detail = detail,
                )
            }
            // 剧集级态不本地猜（只标了本季）：重拉详情拿服务端聚合结果
            when (val fresh = repository.detail(itemId)) {
                is NovumResult.Success -> _state.update { current ->
                    if (current.data == null) {
                        current.copy(playedBusy = false)
                    } else {
                        applyResult(current, UiState.Success(fresh.value))
                    }
                }

                else -> _state.update { current ->
                    // 拉取失败：保持本季乐观态与既有剧集级态（下次进页自然刷新）
                    applyResult(current, current.detail)
                }
            }
            toast.show(
                appContext.getString(
                    when {
                        failures > 0 -> R.string.toast_mark_season_partial
                        played -> R.string.toast_mark_season_watched
                        else -> R.string.toast_mark_season_unwatched
                    },
                    failures,
                ),
            )
        }
    }

    /** 收藏 / 取消收藏，成功经全局 Toast 反馈，失败走页内联。 */
    fun toggleFavorite() {
        val item = _state.value.data ?: return
        if (_state.value.favoriteBusy) return
        val target = !item.isFavorite
        viewModelScope.launch {
            _state.update { it.copy(favoriteBusy = true, favoriteFailed = false) }
            when (val result = repository.setFavorite(item.id, target)) {
                is NovumResult.Success -> {
                    _state.update { current ->
                        val data = current.data
                        current.copy(
                            favoriteBusy = false,
                            detail = if (data == null) {
                                current.detail
                            } else {
                                UiState.Success(data.copy(isFavorite = result.value))
                            },
                        )
                    }
                    toast.show(
                        appContext.getString(
                            if (result.value) R.string.toast_favorite_added else R.string.toast_favorite_removed,
                        ),
                    )
                }

                is NovumResult.Failure -> {
                    // 错误走页内联提示，不占用 Toast
                    _state.update { it.copy(favoriteBusy = false, favoriteFailed = true) }
                }
            }
        }
    }

    /**
     * 切换已看/未看（支持单集）。
     *
     * @param episodeId 非空 = 标记该集（焦点在选集卡时）；null = 标记整个条目
     *   （电影或焦点不在分集上）。
     */
    fun togglePlayed(episodeId: String? = null) {
        val item = _state.value.data ?: return
        if (_state.value.playedBusy) return
        // 剧集没有「整个条目」级标记，避免更新整部剧的所有季；选集未加载完时不动作。
        if (episodeId == null && item.isSeries) return
        // 目标集不存在（剧集分集尚未到达/已失效）时静默不动作，避免打错 id。
        val targetEpisode = episodeId?.let { id ->
            _state.value.episodes.firstOrNull { it.id == id } ?: return
        }
        // 目标状态：单集看该集当前状态，否则看条目状态
        val currentPlayed = targetEpisode?.played ?: item.played
        val targetId = episodeId ?: item.id
        val target = !currentPlayed
        viewModelScope.launch {
            _state.update { it.copy(playedBusy = true, playedFailed = false) }
            when (val result = repository.setPlayed(targetId, target)) {
                is NovumResult.Success -> {
                    _state.update { current ->
                        if (targetEpisode != null) {
                            // 单集：更新该集，并同步其所在季的未看计数——
                            // 否则「已看 x/y 集」在标记后不变（剧集已看标记的核心 bug）。
                            current.copy(
                                playedBusy = false,
                                episodes = current.episodes.map { ep ->
                                    if (ep.id == episodeId) {
                                        ep.copy(
                                            played = result.value,
                                            // 标已看进度清零；标未看保留原进度（服务端 PlaybackPosition 不变）
                                            progressMs = if (result.value) 0L else ep.progressMs,
                                        )
                                    } else {
                                        ep
                                    }
                                },
                                seasons = if (result.value != targetEpisode.played) {
                                    adjustSeasonUnplayed(
                                        current.seasons,
                                        targetEpisode.seasonIndex,
                                        if (result.value) -1 else 1,
                                    )
                                } else {
                                    current.seasons
                                },
                            )
                        } else {
                            val data = current.data
                            current.copy(
                                playedBusy = false,
                                detail = if (data == null) {
                                    current.detail
                                } else {
                                    UiState.Success(
                                        data.copy(
                                            played = result.value,
                                            // 已看 = 进度清零：播放按钮切「播放」，从头播放消失
                                            resumePositionMs = if (result.value) 0L else data.resumePositionMs,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    // 剧集单集标记：重拉详情拿服务端聚合的剧集级 Played，更新信息区
                    // 的剧集已看态（只取 played 字段，不覆盖本地刚更新的其它状态）。
                    if (item.isSeries && targetEpisode != null) refreshSeriesPlayedFlag()
                    toast.show(
                        appContext.getString(
                            if (result.value) R.string.detail_played_toast else R.string.detail_unplayed_toast,
                        ),
                    )
                }

                is NovumResult.Failure -> {
                    _state.update { it.copy(playedBusy = false, playedFailed = true) }
                }
            }
        }
    }

    /**
     * 重拉详情，仅把服务端聚合的剧集级 `played` 写回当前详情。
     *
     * 单集标记后 series.Played 由服务端按全部季聚合（本季全看完不一定翻
     * true，要看所有季），客户端不本地猜；拉取失败则保持现状，下次进页刷新。
     */
    private suspend fun refreshSeriesPlayedFlag() {
        when (val fresh = repository.detail(itemId)) {
            is NovumResult.Success -> _state.update { current ->
                val data = current.data
                if (data == null) {
                    current
                } else {
                    current.copy(detail = UiState.Success(data.copy(played = fresh.value.played)))
                }
            }
            else -> Unit
        }
    }

    /**
     * 增量调整某季的未看计数（单集标记用），clamp 到 [0, episodeCount]。
     *
     * 服务端不在标记响应里回传季计数，重拉季列表又太重；本地按 ±1 维护，
     * 与「本季未看」角标、"已看 x/y 集"聚合保持即时一致。缺字段的季不动。
     */
    private fun adjustSeasonUnplayed(seasons: List<Season>, seasonIndex: Int, delta: Int): List<Season> =
        seasons.map { season ->
            val unplayed = season.unplayedCount
            if (season.index == seasonIndex && unplayed != null) {
                val max = season.episodeCount ?: Int.MAX_VALUE
                season.copy(unplayedCount = (unplayed + delta).coerceIn(0, max))
            } else {
                season
            }
        }

    private suspend fun loadSeasons() {
        // 与季列表并行取该剧集的 NextUp 提示——它天然覆盖三种默认定位
        // （没看过→第一集；看到一半→该集；追平后上新→新集）。失败/为空按无
        // 提示处理，默认季与页面回落到原有逻辑，不因单点查询失败而变样。
        coroutineScope {
            val hintDeferred = async { repository.seriesNextUp(itemId) }
            when (val result = repository.seasons(itemId)) {
                is NovumResult.Success -> {
                    val hint = when (val r = hintDeferred.await()) {
                        is NovumResult.Success -> r.value
                        is NovumResult.Failure -> null
                    }
                    val seasons = result.value
                    // 默认季：优先 NextUp 集所在季（用户需求「进入
                    // 详情页显示当前集/新一集的状态」）；数据缺失时退回第一个
                    // 还有未看的；再退回第一常规季（特别篇 index 0 不当默认）
                    val default = hint?.let { h ->
                        seasons.firstOrNull { it.index == h.seasonIndex }
                    }
                        ?: seasons.firstOrNull { (it.unplayedCount ?: 0) > 0 }
                        ?: seasons.firstOrNull { it.index > 0 }
                        ?: seasons.firstOrNull()
                    _state.update { it.copy(seasons = seasons, selectedSeasonId = default?.id) }
                    if (default != null) loadEpisodes(default.id, nextUpHint = hint)
                }

                is NovumResult.Failure -> {
                    hintDeferred.cancel()
                    // 季列表失败不覆盖详情，但必须标记失败：episodes 为空会让播放按钮禁用，
                    // 界面靠 episodesFailed 提供内联重试，否则用户只能退出重进
                    _state.update { it.copy(seasons = emptyList(), episodesFailed = true) }
                }
            }
        }
    }

    private suspend fun loadEpisodes(seasonId: String, nextUpHint: Episode? = null) {
        when (val result = repository.episodes(seriesId = itemId, seasonId = seasonId)) {
            is NovumResult.Success -> _state.update {
                // 提示集就在本季 → 记为「当前集」，页面据此初始化信息块
                // 并把选集行滚动到该集；提示集不在（NextUp 陈旧/跨季竞态）则不设。
                // 无提示的重试路径保留既有定位不动。
                val anchor = nextUpHint
                    ?.takeIf { h -> result.value.any { it.id == h.id } }
                    ?.id
                val keepCurrent = nextUpHint == null
                // 继续观看入口直达：进场有锚点即直接落集级
                // 语境，fromEntry 标记让页面把首焦交给播放按钮；无锚点静默
                // 回落剧级态
                val entryContext = anchor?.takeIf { entryFocusCurrentEpisode }
                it.copy(
                    episodes = result.value,
                    episodesFailed = false,
                    currentEpisodeId = if (keepCurrent) it.currentEpisodeId else anchor,
                    episodeContextId = entryContext ?: it.episodeContextId,
                    episodeContextFromEntry = entryContext != null,
                )
            }

            is NovumResult.Failure -> _state.update {
                it.copy(episodes = emptyList(), episodesFailed = true)
            }
        }
    }
}

/** 日志标签（设置流失败留痕）。 */
private const val TAG = "DetailVM"
