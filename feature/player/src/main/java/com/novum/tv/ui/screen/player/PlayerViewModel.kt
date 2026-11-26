package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.novum.tv.core.capability.CapabilityProvider
import com.novum.tv.core.data.di.ApplicationScope
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.DanmuRepository
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PlaybackRepository
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.data.settings.danmuConfig
import com.novum.tv.model.Chapter
import com.novum.tv.model.DanmuAnime
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuEpisode
import com.novum.tv.model.DanmuSize
import com.novum.tv.model.DanmuSpeed
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.navigation.PlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页 UI 状态。
 *
 * @param plan 播放计划三态。
 * @param title 顶部标题（剧集显示「剧名 · 集名」）。
 * @param subtitle 播放方式描述，在抽屉的画质 tab 展示。
 * @param subtitlesEnabled 设置里的「默认开启字幕」，决定外挂字幕是否默认选中。
 * @param subtitleSize 字幕字号档位（1 小 – 4 超大），驱动字幕层字号。
 * @param subtitleWeight 字幕粗细（0 细、1 标准、2 粗）。
 * @param chapters 章节列表，服务端未提供时为空。
 */
data class PlayerUiState(
    val plan: UiState<PlaybackPlan> = UiState.Loading,
    val title: String = "",
    /** 左上角 ClearLogo（标题位换透明底标题图）；无图为 null，界面回落文本标题。 */
    val logoUrl: String? = null,
    /** 分集名独立放在作品标题 / ClearLogo 下方。 */
    val episodeTitle: String? = null,
    val subtitle: String = "",
    val subtitlesEnabled: Boolean = true,
    val subtitleSize: Int = 2,
    val subtitleWeight: Int = 1,
    /** 字幕抬升档位（0=默认居中 1/2 往高 3/4 往低（五档双向，二次））。 */
    val subtitleOffsetLevel: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    /** 音频直通开关当前值（抽屉里展示与切换）。 */
    val audioPassthrough: Boolean = true,
    /** 下一集 id；电影 / 定位失败时为 null（播放结束浮钮）。 */
    val nextEpisodeId: String? = null,
    /** 当前季分集列表（抽屉「选集」页数据）；电影/定位失败为空。 */
    val episodes: List<Episode> = emptyList(),
    /** 当前播放的分集 id（抽屉选集页高亮当前集用）；电影为 null。 */
    val currentEpisodeId: String? = null,
    /** 影片/剧集简介（抽屉「简介」页兜底：电影只有它，分集缺简介时也用它）。 */
    val overview: String? = null,
    /** 记忆的倍速档：起播即应用、切换即持久化。 */
    val playbackSpeed: Float = 1f,
    /** 音频 offload 能力位：直通探测非空 → 渲染器工厂开 DSP offload。 */
    val offloadCapable: Boolean = false,
    /** 音轨语言偏好（设置，空串=自动选最高规格；起播经 ExoPlayer 语言偏好消费）。 */
    val preferredAudioLanguage: String = "",
    /** 字幕语言偏好（设置，空串=跟随媒体默认轨）。 */
    val preferredSubtitleLanguage: String = "",
    /** 条目级手动记忆（抽屉切轨/详情选版本的持久化），起播时由界面恢复。 */
    val rememberedPrefs: ItemPlaybackPrefsStore.ItemPrefs? = null,
    /** 优先视频质量策略（设置）：抽屉换集在「每次询问」档先弹版本层。 */
    val versionStrategy: String = "",
    /** 自动连播（设置，默认开）：片尾倒计时 10 秒归零换集。 */
    val autoPlayNext: Boolean = true,
    /** 弹幕会话（配置 + 加载状态 + 数据 + 偏移 + 手动搜索流程）。 */
    val danmu: DanmuSession = DanmuSession(),
) {

    /** 播放计划数据，非成功态为 null。 */
    val data: PlaybackPlan? get() = (plan as? UiState.Success)?.data
}

/**
 * 播放页 ViewModel。
 *
 * 只负责「取计划」和「上报进度」，播放器实例由界面持有：
 * 播放器是短生命周期、强绑定 Surface 的对象，放在 ViewModel 里反而会泄漏。
 *
 * @param playback 播放仓储。
 * @param media 媒体仓储，用于取标题与续播位置。
 * @param capability 设备能力：决定是否开启音频 offload。
 * @param logger 日志门面（偏好写入失败留痕）。
 * @param appScope 进程级作用域：只给「页面离场也必须送达」的停止上报用。
 * @param savedStateHandle 路由参数载体。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackRepository,
    private val media: MediaRepository,
    private val settings: SettingsRepository,
    private val danmuRepository: DanmuRepository,
    private val capability: CapabilityProvider,
    private val playbackPrefs: ItemPlaybackPrefsStore,
    private val logger: NovumLogger,
    @ApplicationContext private val appContext: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 当前播放条目 id（剧集为具体分集）。 */
    private val itemId: String = savedStateHandle.toRoute<PlayerRoute>().itemId

    /** 路由起播位置覆盖：详情页「从头播放」传 0；null 走默认续播/片头跳过规则。 */
    private val routeStartMs: Long? = savedStateHandle.toRoute<PlayerRoute>().startPositionMs
    /** 指定版本（详情页版本选择带入）。 */
    private val routeMediaSourceId: String? = savedStateHandle.toRoute<PlayerRoute>().mediaSourceId

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** 绑定进入播放页时的服务端/账号，离场后的排队上报不能改投新登录会话。 */
    private val reporter = playback.sessionReporter()

    /** 记录当前播放方式，退出时上报要用。 */
    private var method: PlayMethod = PlayMethod.DirectPlay

    /** PlaybackInfo 会话 id，进度上报原样回传；换线/强转码重取计划后同步更新。 */
    private var playSessionId: String? = null

    /** 当前计划的资源文件名（弹幕自动匹配锚点）。 */
    private var planFileName: String? = null

    /**
     * 本次播放生效的版本（路由显式 > 条目记忆）。换线/强转码重取计划时必须沿用：
     * 不传的话服务端会按默认/策略重新挑版本，用户在详情页选的「4K HDR」会在
     * 兜底重试时悄悄换成别的版本。
     */
    private var requestedMediaSourceId: String? = null

    /** 手动选定/记忆的弹幕分集（null = 自动匹配）。 */
    private var danmuEpisodeId: String? = null

    /** 手动搜索任务（与弹幕加载任务分开取消，互不干扰）。 */
    private var danmuSearchJob: Job? = null

    /**
     * 重入锁：load / 换线 / 强转码共用同一个计划任务。
     * 并发请求会让"先发出的慢响应"覆盖"后发出的新计划"（例如换线后又被旧直连结果顶回），
     * 取消旧任务 + 协程协作式取消保证最后发起的计划才是生效计划。
     */
    private var planJob: Job? = null

    /**
     * 弹幕任务锁：加载/重试/换匹配共用，换集或重开时取消旧任务
     * 防止旧分集的慢响应覆盖新状态。
     */
    private var danmuJob: Job? = null

    /**
     * 倍速档是否已经采纳过设置快照。
     *
     * 只有首次取计划时从设置读倍速；之后重取计划（直通切换、换线、强转码重载）
     * 一律沿用内存里的当前档。否则：用户在播放页调了倍速 → 设置写入尚未落盘（或
     * 快照仍是旧值）→ 切直通触发重载 → 倍速被回写成旧档，播放器按旧速度重建。
     */
    private var speedAdopted = false

    /** 首次读取后沿用当前粗细，重取计划不覆盖尚未落盘的调节值。 */
    private var subtitleWeightAdopted = false

    private fun replan(block: suspend CoroutineScope.() -> Unit) {
        planJob?.cancel()
        planJob = viewModelScope.launch(block = block)
    }

    /**
     * 倍速切换：立即持久化（跨片记忆）并同步 UiState。
     *
     * 播放器实例由界面持有——UI 层同时调 player.setPlaybackSpeed 生效
     * 这里只负责记忆与状态一致性（回退/重载后仍按记忆档恢复）。
     */
    fun setPlaybackSpeed(speed: Float) {
        _state.update { it.copy(playbackSpeed = speed) }
        viewModelScope.launch {
            // 原静默吞错——写失败不阻塞播放，但必须留痕
            runCatchingSuspend {
                settings.update { it.copy(playbackSpeed = speed) }
            }.onFailure { logger.w(TAG, "倍速偏好写入失败", it) }
        }
    }

    /**
     * 字幕字号切换（抽屉字幕 tab 加调节入口）：立即同步 UiState
     * （[com.novum.tv.ui.screen.player.SubtitleLayer] 响应式跟随）并持久化（跨片记忆）。
     */
    fun setSubtitleSize(level: Int) {
        val coerced = level.coerceIn(1, 4)
        _state.update { it.copy(subtitleSize = coerced) }
        viewModelScope.launch {
            runCatchingSuspend {
                settings.update { it.copy(subtitleSize = coerced) }
            }.onFailure { logger.w(TAG, "字幕字号偏好写入失败", it) }
        }
    }

    /** 字幕粗细立即更新渲染并持久化，跨片沿用。 */
    fun setSubtitleWeight(level: Int) {
        val coerced = level.coerceIn(0, 2)
        subtitleWeightAdopted = true
        _state.update { it.copy(subtitleWeight = coerced) }
        viewModelScope.launch {
            runCatchingSuspend {
                settings.update { it.copy(subtitleWeight = coerced) }
            }.onFailure { logger.w(TAG, "字幕粗细偏好写入失败", it) }
        }
    }

    /**
     * 字幕抬升档位切换（抽屉字幕 tab 调节字幕高度）：
     * 立即同步 UiState（SubtitleLayer 底部留白响应式跟随）并持久化（跨片记忆）。
     */
    fun setSubtitleOffsetLevel(level: Int) {
        val coerced = level.coerceIn(0, 4)
        _state.update { it.copy(subtitleOffsetLevel = coerced) }
        viewModelScope.launch {
            runCatchingSuspend {
                settings.update { it.copy(subtitleOffsetLevel = coerced) }
            }.onFailure { logger.w(TAG, "字幕高度偏好写入失败", it) }
        }
    }

    /**
     * 抽屉手动切轨记忆：按条目持久化，下次播放恢复。
     *
     * 只记改动的那类轨道（字幕/音轨互不覆盖）；「关闭字幕」记 disabled 位
     * 再选具体轨时自动清位。合并写由 [ItemPlaybackPrefsStore.update] 保证。
     */
    fun rememberManualTrack(
        isText: Boolean,
        label: String,
        language: String?,
        disabled: Boolean = false,
        rawLabel: String? = null,
        channels: Int? = null,
        formatId: String? = null,
    ) {
        val ref = ItemPlaybackPrefsStore.TrackRef(label = label, language = language, rawLabel = rawLabel, channels = channels, formatId = formatId)
        // 轨道变更会立即触发 onTracksChanged。先更新恢复策略的快照，
        // 否则监听器还拿着起播时的旧记忆，会把用户刚选的轨道切回去。
        _state.update { state ->
            val previous = state.rememberedPrefs ?: ItemPlaybackPrefsStore.ItemPrefs(itemId = itemId)
            state.copy(
                rememberedPrefs = if (isText) {
                    previous.copy(subtitleTrack = ref.takeUnless { disabled }, subtitleDisabled = disabled)
                } else {
                    previous.copy(audioTrack = ref)
                },
            )
        }
        viewModelScope.launch {
            runCatchingSuspend {
                playbackPrefs.update(
                    itemId = itemId,
                    subtitleTrack = if (isText && !disabled) ref else null,
                    audioTrack = if (!isText) ref else null,
                    subtitleDisabled = if (isText) disabled else null,
                    // 关闭字幕时把旧的手动轨一并清掉，下次开启回到语言偏好默认轨
                    clearSubtitleTrack = isText && disabled,
                )
            }.onFailure { logger.w(TAG, "轨道记忆写入失败", it) }
        }
    }

    /**
     * 设备是否具备音频直通能力。
     *
     * Media3 1.11 起 offload 由播放器按格式自动协商，没有开关可关；
     * 这个标记只用于信息面板展示与问题排查（例如「有画面没声」时先看这里）。
     */
    val audioPassthroughAvailable: Boolean =
        capability.capabilities.audioPassthrough.isNotEmpty()

    // ---- 弹幕（UHDNOW 原生能力；非 UHD 服务器在鉴权处失败并静默降级）----

    /**
     * 启动弹幕加载：手动记忆的分集 > 资源文件名自动匹配 > 提示手动搜索。
     *
     * @param fileName 当前版本的资源文件名（可能为 null：服务端未下发 Path/Name）。
     * @param manualEpisodeId 手动选定过的分集 id。
     */
    private fun startDanmu(fileName: String?, manualEpisodeId: String?) {
        danmuJob?.cancel()
        if (!_state.value.danmu.config.enabled) return
        when {
            manualEpisodeId != null -> danmuJob = viewModelScope.launch {
                loadDanmuComments(manualEpisodeId, manual = true)
            }

            fileName != null -> danmuJob = viewModelScope.launch {
                _state.update { it.copy(danmu = it.danmu.copy(load = DanmuLoad.Matching)) }
                when (val matched = danmuRepository.match(fileName)) {
                    is NovumResult.Success -> {
                        val match = matched.value
                        if (match == null) {
                            _state.update { it.copy(danmu = it.danmu.copy(load = DanmuLoad.Unmatched)) }
                        } else {
                            loadDanmuComments(match.episodeId, manual = false, match.title)
                        }
                    }

                    is NovumResult.Failure -> _state.update {
                        it.copy(danmu = it.danmu.copy(load = DanmuLoad.Failed(matched.error)))
                    }
                }
            }

            // 没有文件名锚点：自动匹配无从下手，直接提示手动搜索
            else -> _state.update { it.copy(danmu = it.danmu.copy(load = DanmuLoad.Unmatched)) }
        }
    }

    /** 拉取指定分集的弹幕并进入 Loaded 态。 */
    private suspend fun loadDanmuComments(episodeId: String, manual: Boolean, title: String? = null) {
        _state.update { it.copy(danmu = it.danmu.copy(load = DanmuLoad.Loading)) }
        when (val result = danmuRepository.comments(episodeId)) {
            is NovumResult.Success -> _state.update { state ->
                state.copy(
                    danmu = state.danmu.copy(
                        load = DanmuLoad.Loaded(episodeId, result.value.size, title, manual),
                        comments = result.value,
                    ),
                )
            }

            is NovumResult.Failure -> _state.update { state ->
                state.copy(danmu = state.danmu.copy(load = DanmuLoad.Failed(result.error)))
            }
        }
    }

    /**
     * 弹幕开关（抽屉）：立即生效并持久化为全局默认；开启时若尚未加载则启动加载，
     * 关闭时取消进行中的加载任务（否则抽屉会停在「正在匹配」）。
     */
    fun setDanmuEnabled(enabled: Boolean) {
        _state.update { it.copy(danmu = it.danmu.copy(config = it.danmu.config.copy(enabled = enabled))) }
        if (!enabled) {
            danmuJob?.cancel()
            danmuJob = null
            _state.update { it.copy(danmu = it.danmu.copy(load = DanmuLoad.Idle)) }
        }
        viewModelScope.launch {
            runCatchingSuspend { settings.update { it.copy(danmuEnabled = enabled) } }
                .onFailure { logger.w(TAG, "弹幕开关写入失败", it) }
        }
        if (enabled && _state.value.danmu.load == DanmuLoad.Idle) {
            startDanmu(planFileName, danmuEpisodeId)
        }
    }

    /**
     * 弹幕渲染参数调节（透明度/区域/字号/密度/速度）：立即生效并写回全局设置。
     */
    fun updateDanmuConfig(transform: (DanmuConfig) -> DanmuConfig) {
        val next = transform(_state.value.danmu.config)
        _state.update { it.copy(danmu = it.danmu.copy(config = next)) }
        viewModelScope.launch {
            runCatchingSuspend {
                settings.update { s ->
                    s.copy(
                        danmuOpacity = next.opacity,
                        danmuDensity = next.density,
                        danmuArea = next.area,
                        danmuSize = when (next.size) {
                            DanmuSize.Small -> 1
                            DanmuSize.Large -> 3
                            else -> 2
                        },
                        danmuSpeed = when (next.speed) {
                            DanmuSpeed.Slow -> 1
                            DanmuSpeed.Fast -> 3
                            else -> 2
                        },
                    )
                }
            }.onFailure { logger.w(TAG, "弹幕偏好写入失败", it) }
        }
    }

    /**
     * 弹幕时间偏移微调（±100ms 步进，范围 ±1 小时）：按条目持久化。
     *
     * @param deltaMs 偏移增量（正=弹幕延后）。
     */
    fun adjustDanmuOffset(deltaMs: Long) {
        val next = (_state.value.danmu.offsetMs + deltaMs).coerceIn(-DANMU_OFFSET_LIMIT_MS, DANMU_OFFSET_LIMIT_MS)
        _state.update { it.copy(danmu = it.danmu.copy(offsetMs = next)) }
        viewModelScope.launch {
            runCatchingSuspend { playbackPrefs.update(itemId = itemId, danmuOffsetMs = next) }
                .onFailure { logger.w(TAG, "弹幕偏移写入失败", it) }
        }
    }

    /** 重置弹幕偏移（回到 0，同样落盘记忆）。 */
    fun resetDanmuOffset() {
        _state.update { it.copy(danmu = it.danmu.copy(offsetMs = 0L)) }
        viewModelScope.launch {
            runCatchingSuspend { playbackPrefs.update(itemId = itemId, danmuOffsetMs = 0L) }
                .onFailure { logger.w(TAG, "弹幕偏移写入失败", it) }
        }
    }

    /** 弹幕重试（失败/未匹配后手动触发）。 */
    fun retryDanmu() {
        startDanmu(planFileName, danmuEpisodeId)
    }

    /** 手动搜索动画（搜索词为当前标题或用户输入）。 */
    fun searchDanmuAnime(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        danmuSearchJob?.cancel()
        danmuSearchJob = viewModelScope.launch {
            _state.update { it.copy(danmu = it.danmu.copy(search = DanmuSearch.Searching)) }
            when (val result = danmuRepository.searchAnime(trimmed)) {
                is NovumResult.Success -> _state.update { state ->
                    state.copy(danmu = state.danmu.copy(search = DanmuSearch.Results(trimmed, result.value)))
                }

                is NovumResult.Failure -> _state.update { state ->
                    state.copy(danmu = state.danmu.copy(search = DanmuSearch.Failed(result.error)))
                }
            }
        }
    }

    /** 展开动画的分集列表（手动搜索第二步）。 */
    fun openDanmuAnime(anime: DanmuAnime) {
        danmuSearchJob?.cancel()
        danmuSearchJob = viewModelScope.launch {
            _state.update { it.copy(danmu = it.danmu.copy(search = DanmuSearch.Searching)) }
            when (val result = danmuRepository.episodes(anime.bangumiId)) {
                is NovumResult.Success -> _state.update { state ->
                    state.copy(danmu = state.danmu.copy(search = DanmuSearch.Episodes(anime, result.value)))
                }

                is NovumResult.Failure -> _state.update { state ->
                    state.copy(danmu = state.danmu.copy(search = DanmuSearch.Failed(result.error)))
                }
            }
        }
    }

    /**
     * 选定手动匹配的分集：拉取弹幕并按条目记忆（下次播放同一集直接恢复）。
     */
    fun selectDanmuEpisode(episode: DanmuEpisode) {
        danmuEpisodeId = episode.episodeId
        viewModelScope.launch {
            runCatchingSuspend { playbackPrefs.update(itemId = itemId, danmuEpisodeId = episode.episodeId) }
                .onFailure { logger.w(TAG, "弹幕分集记忆写入失败", it) }
        }
        _state.update { it.copy(danmu = it.danmu.copy(search = DanmuSearch.Idle)) }
        danmuJob?.cancel()
        danmuJob = viewModelScope.launch { loadDanmuComments(episode.episodeId, manual = true, episode.title) }
    }

    /** 退出手动搜索（不改变当前匹配）。 */
    fun closeDanmuSearch() {
        _state.update { it.copy(danmu = it.danmu.copy(search = DanmuSearch.Idle)) }
    }

    /** 恢复自动匹配：清除手动分集记忆并重新按文件名匹配。 */
    fun useDanmuAutoMatch() {
        danmuEpisodeId = null
        viewModelScope.launch {
            runCatchingSuspend { playbackPrefs.update(itemId = itemId, clearDanmuEpisodeId = true) }
                .onFailure { logger.w(TAG, "弹幕分集记忆清除失败", it) }
        }
        _state.update { it.copy(danmu = it.danmu.copy(search = DanmuSearch.Idle)) }
        startDanmu(planFileName, null)
    }

    init {
        load()
    }

    /**
     * 首次加载或错误态重试。
     *
     * @param startOverrideMs 指定起播位置（进度毫秒）。直通切换后重载时传入
     * 当前播放位置，避免从片头/续播点重来；为空时按续播与片头跳过规则计算。
     * 优先级：重载显式指定 > 路由「从头播放」 > 默认规则。
     */
    fun load(startOverrideMs: Long? = null) {
        replan {
            _state.update { it.copy(plan = UiState.Loading) }
            // 先取详情：标题与续播位置都来自它；失败不阻塞播放
            val detail = (media.detail(itemId) as? NovumResult.Success)?.value
            val resumeMs = detail?.resumePositionMs ?: 0L
            val series = detail?.takeIf { it.type == "Episode" }?.seriesId?.let { id ->
                (media.detail(id) as? NovumResult.Success)?.value
            }
            val title = series?.title ?: detail?.title.orEmpty()
            val snapshot = runCatchingSuspend { settings.settings.first() }.getOrNull()
            val subtitlesEnabled = snapshot?.subtitleEnabledByDefault ?: true
            val subtitleSize = snapshot?.subtitleSize ?: 2
            val subtitleOffsetLevel = snapshot?.subtitleOffsetLevel ?: 0
            // 条目级手动记忆：上次手动选过的版本/字幕/音轨
            // 优先于语言偏好与画质策略；读取失败静默（记忆只是增强，不阻塞播放）
            val remembered = runCatchingSuspend { playbackPrefs.get(itemId) }.getOrNull()
            // 从头看且开启片头跳过时，直接起播在片头之后；续播位置优先，避免覆盖用户进度
            val startMs = startOverrideMs
                ?: routeStartMs
                ?: if (snapshot?.skipIntro == true && resumeMs < SKIP_INTRO_RESUME_THRESHOLD_MS) {
                    snapshot.skipIntroSeconds * 1000L
                } else {
                    resumeMs
                }
            // 倍速只在首次取计划时采纳设置快照；重取计划沿用内存当前档（见 speedAdopted）
            val speed = if (speedAdopted) _state.value.playbackSpeed else snapshot?.playbackSpeed ?: 1f

            // 路由显式版本 > 条目记忆的版本 > 画质策略（null = 交给服务端按策略挑）
            val versionId = routeMediaSourceId ?: remembered?.mediaSourceId
            requestedMediaSourceId = versionId
            when (
                val result = playback.plan(
                    itemId = itemId,
                    startPositionMs = startMs,
                    mediaSourceId = versionId,
                )
            ) {
                is NovumResult.Success -> {
                    method = result.value.method
                    playSessionId = result.value.playSessionId
                    planFileName = result.value.fileName
                    danmuEpisodeId = remembered?.danmuEpisodeId
                    speedAdopted = true
                    val subtitleWeight = if (subtitleWeightAdopted) {
                        _state.value.subtitleWeight
                    } else {
                        (snapshot?.subtitleWeight ?: 1).coerceIn(0, 2)
                    }
                    subtitleWeightAdopted = true
                    _state.update {
                        it.copy(
                            plan = UiState.Success(result.value),
                            title = title,
                            logoUrl = series?.logoUrl ?: detail?.logoUrl,
                            episodeTitle = detail?.takeIf { it.type == "Episode" }?.let {
                                drawerEpisodeLabel(it.seasonIndex ?: 1, it.index ?: 1, it.title)
                            },
                            overview = detail?.overview,
                            subtitle = result.value.method.label(appContext),
                            subtitlesEnabled = subtitlesEnabled,
                            subtitleSize = subtitleSize,
                            subtitleWeight = subtitleWeight,
                            subtitleOffsetLevel = subtitleOffsetLevel,
                            chapters = detail?.chapters.orEmpty(),
                            audioPassthrough = snapshot?.audioPassthrough ?: true,
                            playbackSpeed = speed,
                            offloadCapable = audioPassthroughAvailable,
                            preferredAudioLanguage = snapshot?.audioLanguage.orEmpty(),
                            preferredSubtitleLanguage = snapshot?.subtitleLanguage.orEmpty(),
                            rememberedPrefs = remembered,
                            versionStrategy = snapshot?.versionStrategy.orEmpty(),
                            autoPlayNext = snapshot?.autoPlayNext ?: true,
                            danmu = DanmuSession(
                                config = snapshot?.danmuConfig() ?: DanmuConfig(),
                                offsetMs = remembered?.danmuOffsetMs ?: 0L,
                            ),
                        )
                    }
                    // 弹幕跟随新计划启动：手动记忆的分集 > 文件名自动匹配
                    startDanmu(result.value.fileName, remembered?.danmuEpisodeId)
                }

                is NovumResult.Failure -> _state.update {
                    it.copy(plan = UiState.Error(result.error), title = title)
                }
            }

            // 上一集/下一集 + 抽屉选集页数据：失败静默（按钮禁用占位），
            // 绝不影响播放本身
            resolveEpisodeList(detail)
        }
    }

    /**
     * 定位同季的上一集/下一集，并把该季分集整表交给抽屉「选集」页
     *
     * 只对分集生效：取剧集季列表 → 找到当前季 → 该季分集按集号取邻居。
     * 任一步失败就保持 null/空表，控制条按钮禁用占位、抽屉选集页为空提示
     * （规范 无列表时不给假入口）。
     */
    private suspend fun resolveEpisodeList(detail: MediaDetail?) {
        val seriesId = detail?.seriesId
        val seasonIndex = detail?.seasonIndex
        if (detail?.type != "Episode" || seriesId == null || seasonIndex == null) return
        val seasons = (media.seasons(seriesId) as? NovumResult.Success)?.value ?: return
        val season = seasons.firstOrNull { it.index == seasonIndex } ?: return
        val episodes = (
            media.episodes(seriesId = seriesId, seasonId = season.id) as? NovumResult.Success
            )?.value ?: return
        val position = episodes.indexOfFirst { it.id == detail.id }
        if (position < 0) return
        _state.update {
            it.copy(
                nextEpisodeId = episodes.getOrNull(position + 1)?.id,
                episodes = episodes,
                currentEpisodeId = detail.id,
            )
        }
    }

    /**
     * 切换音频直通并用新 DeviceProfile 重新请求播放计划。
     *
     * 路径：写设置 → 重新请求 PlaybackInfo → 界面按新计划从 [resumeAtMs] 续播。
     * UI 按新直通设置重建播放器，让 AudioSink 的构造能力与新计划一致。
     *
     * @param enabled 是否开启直通。
     * @param resumeAtMs 切换前的播放位置，重载后从此处继续。
     */
    fun setAudioPassthrough(enabled: Boolean, resumeAtMs: Long) {
        viewModelScope.launch {
            // 写失败不阻塞重载（设置落盘异常不应让页面崩掉），但要留痕
            runCatchingSuspend { settings.update { it.copy(audioPassthrough = enabled) } }
                .onFailure { logger.w(TAG, "音频直通设置写入失败", it) }
            load(startOverrideMs = resumeAtMs)
        }
    }

    /**
     * 换线重试：排除当前线路，取下一名重新请求播放计划。
     *
     * @param positionMs 当前播放位置，换线后从此继续。
     */
    fun retryNextLine(positionMs: Long) {
        val currentLine = (_state.value.plan as? UiState.Success)?.data?.lineBaseUrl
        replan {
            _state.update { it.copy(plan = UiState.Loading) }
            when (val result = playback.plan(
                    itemId,
                    positionMs,
                    excludeLine = currentLine,
                    // 换线只换域名，不换版本：否则重试时悄悄回到默认版本
                    mediaSourceId = requestedMediaSourceId,
                )
            ) {
                is NovumResult.Success -> {
                    method = result.value.method
                    playSessionId = result.value.playSessionId
                    _state.update {
                        it.copy(plan = UiState.Success(result.value), subtitle = result.value.method.label(appContext))
                    }
                }

                is NovumResult.Failure -> _state.update {
                    it.copy(plan = UiState.Error(result.error))
                }
            }
        }
    }

    /**
     * 记录兜底切换后的播放方式。
     *
     * 直连失败改走转码时，后续进度上报必须新口径，否则服务端会话统计会失真。
     *
     * @param next 新的播放方式。
     */
    fun onMethodChanged(next: PlayMethod) {
        method = next
    }

    /**
     * 直连失败且没有兜底地址时，强制请求服务端转码。
     *
     * 用于「设备解不了该编码 / 双层 DV」这类服务端事先判断不出来的场景：
     * 重新发一次只声明转码能力的 PlaybackInfo，拿到转码流后从当前进度续播。
     *
     * @param positionMs 当前播放位置，转码起播后接着播。
     */
    fun retryWithTranscode(positionMs: Long) {
        replan {
            when (val result = playback.plan(
                    itemId,
                    positionMs,
                    forceTranscode = true,
                    // 用户选定的版本必须沿用：强转码是对**同一个版本**转码
                    mediaSourceId = requestedMediaSourceId,
                )
            ) {
                is NovumResult.Success -> {
                    method = result.value.method
                    playSessionId = result.value.playSessionId
                    _state.update {
                        it.copy(
                            plan = UiState.Success(result.value),
                            subtitle = result.value.method.label(appContext),
                        )
                    }
                }

                is NovumResult.Failure -> _state.update {
                    it.copy(plan = UiState.Error(result.error))
                }
            }
        }
    }

    /**
     * 上报开始播放。
     *
     * @param positionMs 真实起播位置：续播、跳过片头都可能不是 0，
     *   写死 0 会把服务端「继续观看」进度先打回开头。
     */
    fun onStarted(positionMs: Long) {
        val eventMethod = method
        val eventSessionId = playSessionId
        viewModelScope.launch { reporter.reportStarted(itemId, positionMs, eventMethod, eventSessionId) }
    }

    /**
     * 上报播放进度。
     *
     * @param positionMs 当前位置。
     */
    fun onProgress(positionMs: Long) {
        val eventMethod = method
        val eventSessionId = playSessionId
        viewModelScope.launch { reporter.reportProgress(itemId, positionMs, eventMethod, eventSessionId) }
    }

    /**
     * 上报停止播放。
     *
     * 换集/退出的同一帧内播放页就会被替换或销毁，`viewModelScope` 随之取消——
     * 挂在它上面的请求会在 `withContext(io)` 边界被中断，服务端收不到本集结束
     * 位置（该集永远停在"看到一半"，继续观看/NextUp 不前进）。因此停止上报走
     * 进程级 [appScope]：页面没了请求也要送达。
     *
     * @param positionMs 退出位置（片尾附近由界面传完整时长，见 PlayerScreen）。
     */
    fun onStopped(positionMs: Long) {
        // appScope 在 IO 线程执行前可能已经重取计划；事件属于调用瞬间的会话。
        val eventMethod = method
        val eventSessionId = playSessionId
        appScope.launch { reporter.reportStopped(itemId, positionMs, eventMethod, eventSessionId) }
    }
}

/** 续播位置低于该阈值视为「从头看」，此时才应用片头跳过。 */
private const val SKIP_INTRO_RESUME_THRESHOLD_MS = 30_000L

/** 弹幕偏移上限（±1 小时，协议口径）。 */
internal const val DANMU_OFFSET_LIMIT_MS = 3_600_000L

/** 日志标签（偏好/记忆写入失败留痕）。 */
private const val TAG = "PlayerVM"

/** 播放方式的中文描述（资源化，与其它文案同一出处）。 */
private fun PlayMethod.label(context: Context): String = when (this) {
    PlayMethod.DirectPlay -> context.getString(R.string.play_method_direct_play)
    PlayMethod.DirectStream -> context.getString(R.string.play_method_direct_stream)
    PlayMethod.Transcode -> context.getString(R.string.play_method_transcode)
}
