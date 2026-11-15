package com.novum.tv.ui.screen.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.novum.tv.core.capability.CapabilityProvider
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PlaybackRepository
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.model.Chapter
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
 * @param chapters 章节列表，服务端未提供时为空。
 */
data class PlayerUiState(
    val plan: UiState<PlaybackPlan> = UiState.Loading,
    val title: String = "",
    /** 左上角 ClearLogo（标题位换透明底标题图）；无图为 null，界面回落文本标题。 */
    val logoUrl: String? = null,
    val subtitle: String = "",
    val subtitlesEnabled: Boolean = true,
    val subtitleSize: Int = 2,
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
 * @param savedStateHandle 路由参数载体。
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playback: PlaybackRepository,
    private val media: MediaRepository,
    private val settings: SettingsRepository,
    private val capability: CapabilityProvider,
    private val playbackPrefs: ItemPlaybackPrefsStore,
    private val logger: NovumLogger,
    @ApplicationContext private val appContext: Context,
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

    /** 记录当前播放方式，退出时上报要用。 */
    private var method: PlayMethod = PlayMethod.DirectPlay

    /**
     * 重入锁：load / 换线 / 强转码共用同一个计划任务。
     * 并发请求会让"先发出的慢响应"覆盖"后发出的新计划"（例如换线后又被旧直连结果顶回），
     * 取消旧任务 + 协程协作式取消保证最后发起的计划才是生效计划。
     */
    private var planJob: Job? = null

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
            runCatching {
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
            runCatching {
                settings.update { it.copy(subtitleSize = coerced) }
            }.onFailure { logger.w(TAG, "字幕字号偏好写入失败", it) }
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
            runCatching {
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
    fun rememberManualTrack(isText: Boolean, label: String, language: String?, disabled: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                playbackPrefs.update(
                    itemId = itemId,
                    subtitleTrack = if (isText && !disabled) {
                        ItemPlaybackPrefsStore.TrackRef(label, language)
                    } else {
                        null
                    },
                    audioTrack = if (!isText) ItemPlaybackPrefsStore.TrackRef(label, language) else null,
                    subtitleDisabled = if (isText) disabled else null,
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
            val title = detail?.title.orEmpty()
            val snapshot = runCatching { settings.settings.first() }.getOrNull()
            val subtitlesEnabled = snapshot?.subtitleEnabledByDefault ?: true
            val subtitleSize = snapshot?.subtitleSize ?: 2
            val subtitleOffsetLevel = snapshot?.subtitleOffsetLevel ?: 0
            // 条目级手动记忆：上次手动选过的版本/字幕/音轨
            // 优先于语言偏好与画质策略；读取失败静默（记忆只是增强，不阻塞播放）
            val remembered = runCatching { playbackPrefs.get(itemId) }.getOrNull()
            // 从头看且开启片头跳过时，直接起播在片头之后；续播位置优先，避免覆盖用户进度
            val startMs = startOverrideMs
                ?: routeStartMs
                ?: if (snapshot?.skipIntro == true && resumeMs < SKIP_INTRO_RESUME_THRESHOLD_MS) {
                    snapshot.skipIntroSeconds * 1000L
                } else {
                    resumeMs
                }

            when (
                val result = playback.plan(
                    itemId = itemId,
                    startPositionMs = startMs,
                    // 条目记忆的版本 > 路由显式版本 > 画质策略
                    mediaSourceId = routeMediaSourceId ?: remembered?.mediaSourceId,
                )
            ) {
                is NovumResult.Success -> {
                    method = result.value.method
                    _state.update {
                        it.copy(
                            plan = UiState.Success(result.value),
                            title = title,
                            logoUrl = detail?.logoUrl,
                            subtitle = result.value.method.label(appContext),
                            subtitlesEnabled = subtitlesEnabled,
                            subtitleSize = subtitleSize,
                            subtitleOffsetLevel = subtitleOffsetLevel,
                            chapters = detail?.chapters.orEmpty(),
                            audioPassthrough = snapshot?.audioPassthrough ?: true,
                            playbackSpeed = snapshot?.playbackSpeed ?: 1f,
                            offloadCapable = audioPassthroughAvailable,
                            preferredAudioLanguage = snapshot?.audioLanguage.orEmpty(),
                            preferredSubtitleLanguage = snapshot?.subtitleLanguage.orEmpty(),
                            rememberedPrefs = remembered,
                            versionStrategy = snapshot?.versionStrategy.orEmpty(),
                            autoPlayNext = snapshot?.autoPlayNext ?: true,
                        )
                    }
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
     * 不做运行时重建 AudioSink（Media3 1.11 已移除该能力，重建播放器代价更大）。
     *
     * @param enabled 是否开启直通。
     * @param resumeAtMs 切换前的播放位置，重载后从此处继续。
     */
    fun setAudioPassthrough(enabled: Boolean, resumeAtMs: Long) {
        viewModelScope.launch {
            settings.update { it.copy(audioPassthrough = enabled) }
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
            when (val result = playback.plan(itemId, positionMs, excludeLine = currentLine)) {
                is NovumResult.Success -> {
                    method = result.value.method
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
            when (val result = playback.plan(itemId, positionMs, forceTranscode = true)) {
                is NovumResult.Success -> {
                    method = result.value.method
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

    /** 上报开始播放。 */
    fun onStarted() {
        viewModelScope.launch { playback.reportStarted(itemId, 0L, method) }
    }

    /**
     * 上报播放进度。
     *
     * @param positionMs 当前位置。
     */
    fun onProgress(positionMs: Long) {
        viewModelScope.launch { playback.reportProgress(itemId, positionMs, method) }
    }

    /**
     * 上报停止播放。
     *
     * @param positionMs 退出位置。
     */
    fun onStopped(positionMs: Long) {
        viewModelScope.launch { playback.reportStopped(itemId, positionMs, method) }
    }
}

/** 续播位置低于该阈值视为「从头看」，此时才应用片头跳过。 */
private const val SKIP_INTRO_RESUME_THRESHOLD_MS = 30_000L

/** 日志标签（偏好/记忆写入失败留痕）。 */
private const val TAG = "PlayerVM"

/** 播放方式的中文描述（资源化，与其它文案同一出处）。 */
private fun PlayMethod.label(context: Context): String = when (this) {
    PlayMethod.DirectPlay -> context.getString(R.string.play_method_direct_play)
    PlayMethod.DirectStream -> context.getString(R.string.play_method_direct_stream)
    PlayMethod.Transcode -> context.getString(R.string.play_method_transcode)
}
