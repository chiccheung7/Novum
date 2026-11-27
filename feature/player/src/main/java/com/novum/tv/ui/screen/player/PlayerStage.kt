package com.novum.tv.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.Cue
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.player.seekStepMs
import com.novum.tv.core.player.audioLabel
import com.novum.tv.core.player.audioOutputLabel
import com.novum.tv.core.player.buildMediaItem
import com.novum.tv.feature.player.BuildConfig
import com.novum.tv.model.Chapter
import com.novum.tv.model.Episode
import com.novum.tv.model.displayLine
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.ui.component.NovumConfirmDialog
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumOptionListDialog
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.component.NovumNoScrollDeadZone
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumShapes

/**
 * 播放舞台：三层结构 + 按键状态机。
 *
 * 层级（从下到上，全部常驻）：
 * 1. `PlayerSurface` —— 视频画面
 * 2. 字幕层 —— 跟随控制条上移，避免被进度条压住
 * 3. `GradientScrim` —— 渐变遮罩，alpha 跟随控制条
 * 4. `PlayerControlOverlay` —— 顶栏 / 进度条 / 按钮行，alpha 显隐、不销毁
 * 5. 「下一集」浮钮 —— 临近结束自动浮出并落焦
 * 6. `NoticeBanner` / 信息面板 / 退出确认 —— 临时浮层。
 *
 * 按键仲裁顺序（关键，改之前先看 [resolvePlayerCommand]）：
 * 1. 退出确认打开 → 全部交给焦点系统
 * 2. 信息面板打开 → 自绘索引处理
 * 3. 控制条隐藏 → 父层接管（OK 播放暂停、左右 seek、上下呼出）
 * 4. 进度条聚焦 → 父层只截左右键做精细 seek，上下交给焦点系统
 * 5. 其余情况 → 全部交给 Compose 焦点系统。
 *
 * @param player 播放器实例。
 * @param plan 播放计划。
 * @param videoAspectRatio 视频显示宽高比（onVideoSizeChanged + SAR/DAR；null 回落满屏）。
 *   部分设备会忽略 Surface 的等比声明并拉伸解码帧，因此 Surface 按显示比例定型。
 * @param title 标题。
 * @param overview 影片/剧集简介（抽屉「简介」页；分集缺简介时兜底）。
 * @param subtitle 播放方式描述（控制条标题下不再常驻展示，仅抽屉·画质 tab 消费）。
 * @param subtitleSize 字幕字号档位。
 * @param subtitleOffsetLevel 字幕抬升档位（0=默认居中 1/2 往高 3/4 往低（五档双向））。
 * @param subtitlesEnabled 是否默认开启字幕。
 * @param chapters 章节列表。
 * @param nextEpisodeId 下一集 id（播放结束浮钮；null 不显示）。
 * @param onOpenEpisode 换集（下一集浮钮/抽屉选集/自动连播）：第二参数 = 本次播放版本
 *   null = 按画质策略/服务端默认；第三参数 `finished` = 本集是否已播完（自动连播、
 *   片尾浮钮、自然播完为 true；抽屉中途选集为 false），供收尾上报区分「看完」与
 *   「看一半换集」。
 * @param onExit 退出播放。
 * @param onMethodChanged 兜底切换后回调新的播放方式。
 * @param onRequestTranscode 请求服务端转码。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun PlayerStage(
    player: ExoPlayer,
    audioDelayMs: Long,
    onAudioDelayChanged: (Long) -> Unit,
    subtitleDelayMs: Long,
    onSubtitleDelayChanged: (Long) -> Unit,
    onRememberTrack: (TrackOption, isText: Boolean) -> Unit,
    plan: PlaybackPlan,
    videoAspectRatio: Float?,
    title: String,
    /** 左上角 ClearLogo（标题位换透明底标题图）；null 回落文本标题。 */
    logoUrl: String?,
    episodeTitle: String? = null,
    overview: String? = null,
    subtitle: String,
    subtitleSize: Int,
    /** 字幕字号切换回调（抽屉字幕 tab 的调节入口）。 */
    onSubtitleSizeChanged: (Int) -> Unit = {},
    /** 字幕粗细：0 细、1 标准、2 粗。 */
    subtitleWeight: Int = 1,
    onSubtitleWeightChanged: (Int) -> Unit = {},
    /** 字幕抬升切换回调（抽屉字幕 tab）。 */
    onSubtitleOffsetLevelChanged: (Int) -> Unit = {},
    subtitleOffsetLevel: Int = 0,
    subtitlesEnabled: Boolean,
    chapters: List<Chapter>,
    nextEpisodeId: String?,
    /**
     * 换集：第二参数 = 本次播放版本（MediaSource id），null = 按画质策略/服务端默认；
     * 第三参数 = 本集是否已播完（见 KDoc）。
     */
    onOpenEpisode: (itemId: String, mediaSourceId: String?, finished: Boolean) -> Unit,
    /** 当前季分集（抽屉「选集」页）；电影为空。 */
    episodes: List<Episode> = emptyList(),
    /** 当前播放分集 id（抽屉选集页高亮）；电影为 null。 */
    currentEpisodeId: String? = null,
    /** 优先视频质量策略（「每次询问」时抽屉换集先弹版本层）。 */
    versionStrategy: String = "",
    onExit: () -> Unit,
    onMethodChanged: (PlayMethod) -> Unit,
    onRequestTranscode: (Long) -> Unit,
    audioPassthrough: Boolean,
    /** 设备是否具备压缩音频直通能力（信息面板判定音频输出路径用）。 */
    passthroughCapable: Boolean = false,
    onSetPassthrough: (Boolean) -> Unit,
    onRetryNextLine: (Long) -> Unit,
    initialSpeed: Float = 1f,
    onSpeedChanged: (Float) -> Unit = {},
    /** 带宽估计（bps）：缓冲时展示网速。 */
    bandwidthEstimate: () -> Long = { 0L },
    /** 当前显示模式文本（画质 tab 实时分区；null = 拿不到 Display）。 */
    displayModeText: () -> String? = { null },
    /** 自动连播（设置，默认开）：片尾窗口倒计时 10 秒归零换集。 */
    autoPlayNext: Boolean = true,
    /** 弹幕会话（配置/加载状态/数据/偏移/搜索流程）。 */
    danmu: DanmuSession = DanmuSession(),
    /** 弹幕操作回调束（抽屉交互 → ViewModel）。 */
    danmuActions: DanmuActions? = null,
    /** 弹幕手动搜索的预填关键词（当前标题）。 */
    danmuSearchPrefill: String = "",
    onSelectServerSubtitle: (Int) -> Unit = {},
    subtitleSwitching: Boolean = false,
    subtitleSwitchError: String? = null,
    onSubtitleSwitchFailed: (PlaybackPlan) -> Boolean = { false },
    onRebuildServerStream: (Long) -> Unit = {},
) {
    var isPlaying by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(false) }
    var subtitleCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    val cueHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val cueScheduler = remember(cueHandler) { CueDelayScheduler(cueHandler) { subtitleCues = it } }
    var audioLabel by remember { mutableStateOf("") }
    // 音频输出路径文案（信息面板实时展示）；取好在协程里捕获，避免 Composable 上下文外调用
    val audioPcmText = stringResource(R.string.player_audio_out_pcm)
    val audioBitstreamText = stringResource(R.string.player_audio_out_bitstream)
    val audioPcmFallbackText = stringResource(R.string.player_audio_out_pcm_fallback)
    var subtitleLabel by remember { mutableStateOf("") }
    // 倍速：初始值来自设置记忆（跨片沿用），切档经回调持久化。
    // 仅把值写进 State 不够——播放器实例不会自己套用记忆档，必须显式 setPlaybackSpeed。
    var speed by remember(plan.url) { mutableFloatStateOf(initialSpeed) }
    LaunchedEffect(player, initialSpeed) {
        speed = initialSpeed
        player.setPlaybackSpeed(initialSpeed)
    }
    // 画面缩放模式：按片源重置——切集/换片回到默认「适应」
    var scaleMode by remember { mutableStateOf(VideoScaleMode.Fit) }
    var speedPickerVisible by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    // 控制层状态
    var controlsVisible by remember { mutableStateOf(true) }
    var peeked by remember { mutableStateOf(false) }
    var focusedItem by remember { mutableStateOf(ControlItem.PlayPause) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var confirmExit by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    // 播放设置抽屉（· M4；tab 化）
    // 文案在组合期解析（供回调里使用，避免在非组合上下文读资源）
    val reloadingNotice = stringResource(R.string.player_drawer_reloading)
    val subtitleOffLabel = stringResource(R.string.player_drawer_subtitle_off)
    // 兜底提示与轨道展示名的文案也在组合期解析（回调/非组合函数里直接用局部值）
    val fallbackNotice = stringResource(R.string.player_fallback_notice)
    val audioTrackFallback = stringResource(R.string.player_track_audio)
    val textTrackFallback = stringResource(R.string.player_track_subtitle)
    val channelsTemplate = stringResource(R.string.player_track_channels)
    // 画面比例钮的当前档位文案（组合期解析，随 scaleMode 切换重组）
    val scaleText = when (scaleMode) {
        VideoScaleMode.Fit -> stringResource(R.string.player_scale_fit)
        VideoScaleMode.Stretch -> stringResource(R.string.player_scale_stretch)
        VideoScaleMode.Crop -> stringResource(R.string.player_scale_crop)
    }
    var drawerVisible by remember { mutableStateOf(false) }
    // 控制条「字幕/音轨」按钮直达对应分区；「播放设置」保持上次所在 tab
    // 抽屉默认「选集」页（用户打开播放设置时最常见意图是换集）
    // 控制条的字幕/音轨按钮仍直达各自 tab
    var drawerTab by remember { mutableStateOf(DrawerTab.Episodes) }
    // 抽屉换集待选版本的集（多版本 + 每次询问）
    var versionPickEpisode by remember { mutableStateOf<Episode?>(null) }
    // 关抽屉的焦点交接 tick（Back 分层用，见下方 LaunchedEffect(backFocusTick)）
    var backFocusTick by remember { mutableIntStateOf(0) }
    // 抽屉 tab 芯片行是否持有焦点：决定根节点要不要吞左右键（见 onPreviewKeyEvent）
    var drawerTabsFocused by remember { mutableStateOf(false) }
    // 抽屉调节行（延迟/字号/高度）是否获焦：获焦时根节点放行左右键给无级调节
    var drawerAdjusting by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var textTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var activeBurnedInLabel by remember(plan.url) { mutableStateOf(plan.burnedInSubtitleLabel) }
    var activePlan by remember(plan) { mutableStateOf(plan) }

    // 兜底状态：一旦切过转码就不再切回，避免在两个地址之间反复横跳
    var fallbackUsed by remember(plan.url) { mutableStateOf(false) }
    var transcodeRequested by remember(plan.url) { mutableStateOf(false) }
    var playbackFailed by remember(plan.url) { mutableStateOf(false) }
    var playerErrorTick by remember { mutableIntStateOf(0) }
    var failedPlan by remember { mutableStateOf<PlaybackPlan?>(null) }

    // 播放时钟：只在这里写入，读取点下沉到进度条 / 章节名，避免整页每 500ms 重组
    val position = remember { mutableLongStateOf(plan.startPositionMs) }
    val duration = remember { mutableLongStateOf(0L) }
    val buffered = remember { mutableLongStateOf(0L) }
    // -1 表示尚无样本；0 表示当前采样区间没有网络字节。
    val bandwidthBps = remember { mutableLongStateOf(-1L) }
    val bandwidthEstimateLive by rememberUpdatedState(bandwidthEstimate)

    // 播放实时状态：listener 写画面尺寸/丢帧，轮询写显示
    // 模式/缓冲/带宽；只在抽屉·画质 tab 在场时被读取（常态零重组成本）
    val liveStats = remember { PlayerLiveStats() }

    // 临近结束「下一集」浮钮：最后 60 秒且确有下一集时浮出
    // 出现即落焦（OK 直接切集）；Back 收起后本集不再弹出（片尾仍会自动退出）。
    // 位置/时长是 500ms 高频 State，包 derivedStateOf——只在布尔翻转时才重组本层
    // 自动连播（默认开 / 10 秒 / 季末回详情）状态先行声明
    // （showNextEpisode 的让位条件引用 autoNextSecondsLeft）：季末语义天然满足
    // nextEpisodeId 由 ViewModel 按同季分集推导、季末为 null → 无倒计时 → 播放
    // 结束走既有退出链路回详情页。归零换集走 onOpenEpisode（与浮钮/抽屉选集同
    // 链路，替换当前播放页）；任意键取消且键照常生效。
    var autoNextCancelled by remember { mutableStateOf(false) }
    var autoNextFired by remember { mutableStateOf(false) }

    /** 倒计时剩余秒数（0 = 不在场）。由播放位置驱动——暂停即冻结，不偷跑。 */
    val autoNextSecondsLeft = remember { mutableIntStateOf(0) }
    // 采集闭包经 rememberUpdatedState 取最新参数（LaunchedEffect(plan.url) 不随参数重启）
    val autoNextEnabledLive by rememberUpdatedState(autoPlayNext)
    val nextEpisodeIdLive by rememberUpdatedState(nextEpisodeId)
    val chaptersLive by rememberUpdatedState(chapters)
    val playbackFailedLive by rememberUpdatedState(playbackFailed)
    val openEpisodeLive by rememberUpdatedState(onOpenEpisode)
    // 字幕延迟在抽屉里实时变化，listener 只随 player 注册，必须读最新值
    val subtitleDelayLive by rememberUpdatedState(subtitleDelayMs)
    var nextEpisodeDismissed by remember { mutableStateOf(false) }
    var nextEpisodeFocused by remember { mutableStateOf(false) }
    val nextEpisodeRequester = remember { FocusRequester() }
    val showNextEpisode by remember(nextEpisodeId) {
        derivedStateOf {
            nextEpisodeId != null && !nextEpisodeDismissed && duration.longValue > 0 &&
                duration.longValue - position.longValue in 1..NEXT_EPISODE_WINDOW_MS &&
                // 自动连播倒计时在场时让位（同槽位；取消后倒计时退场即恢复）
                autoNextSecondsLeft.intValue == 0
        }
    }

    // 「跳过片头/片尾」浮钮：由章节名识别（片头/intro/opening
    // 片尾/credits/ending）。片头：位于第 1 章且时长 > 阈值时，进入后浮出
    // 片尾：进入末章时浮出。与「下一集」互斥（片尾优先让位下一集）。
    val skipTarget = remember(chapters, duration) {
        derivedStateOf { computeSkipTarget(chapters, position.longValue, duration.longValue) }
    }
    var skipDismissed by remember(chapters) { mutableStateOf(false) }
    var skipFocused by remember { mutableStateOf(false) }
    val skipRequester = remember { FocusRequester() }
    val showSkip by remember(chapters) {
        derivedStateOf { skipTarget.value != null && !skipDismissed }
    }

    val rootRequester = remember { FocusRequester() }
    val progressRequester = remember { FocusRequester() }
    val requesters = remember {
        ControlItem.entries.associateWith { FocusRequester() }
    }

    // 轨道列表：以播放器运行时 Tracks 为权威（含服务端转码后实际可切换的轨道）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val audio = buildTrackOptions(
                    tracks, C.TRACK_TYPE_AUDIO, audioTrackFallback, textTrackFallback, channelsTemplate,
                )
                val text = buildTrackOptions(
                    tracks, C.TRACK_TYPE_TEXT, audioTrackFallback, textTrackFallback, channelsTemplate,
                )
                // 字幕列表首位是「关闭字幕」：没有任何字幕轨道被选中时它高亮
                val textOffSelected = text.none { it.selected }
                textTracks = listOf(
                    TrackOption(id = TEXT_OFF_ID, label = subtitleOffLabel, selected = textOffSelected),
                ) + text
                audioTracks = audio
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    /**
     * 切到服务端转码并保留进度。
     *
     * @param positionMs 切换瞬间的播放位置。
     */
    fun switchToFallback(positionMs: Long) {
        if (onSubtitleSwitchFailed(player.activePlaybackPlan() ?: plan)) return
        when (resolvePlaybackRecovery(plan, fallbackUsed, transcodeRequested)) {
            PlaybackRecoveryAction.RequestTranscode -> {
                transcodeRequested = true
                onRequestTranscode(positionMs)
            }

            PlaybackRecoveryAction.ShowFailure -> {
                playbackFailed = true
                controlsVisible = false
            }

            PlaybackRecoveryAction.UseFallback -> {
                fallbackUsed = true
                activeBurnedInLabel = plan.fallbackBurnedInSubtitleLabel
                activePlan = plan.copy(
                    url = requireNotNull(plan.fallbackUrl), method = plan.fallbackMethod ?: PlayMethod.Transcode,
                    burnedInSubtitleLabel = activeBurnedInLabel,
                    burnedInSubtitleIndex = plan.fallbackBurnedInSubtitleIndex,
                    serverSubtitleSelection = plan.serverSubtitleSelection || activeBurnedInLabel != null,
                    serverSubtitleStreamIndex = plan.fallbackBurnedInSubtitleIndex ?: plan.serverSubtitleStreamIndex,
                    streamStartPositionMs = plan.fallbackStreamStartPositionMs,
                    fallbackUrl = null, fallbackMethod = null,
                )
                player.setMediaItem(
                    buildMediaItem(activePlan, subtitlesEnabled),
                    localPlaybackPosition(activePlan, positionMs),
                )
                player.prepare()
                // setMediaItem/prepare 保留播放意图，错误恢复不能解除用户主动暂停。
                onMethodChanged(plan.fallbackMethod ?: PlayMethod.Transcode)
                notice = fallbackNotice
            }
        }
    }

    // 自动退出回调用 rememberUpdatedState 兜底：listener 闭包捕获首帧实例，
    // onExit 若随重组变化会调到旧实现
    val exitCallback by rememberUpdatedState(onExit)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                cueScheduler.cancel()
                subtitleCues = emptyList()
                subtitleLabel = currentSubtitleLabel(player)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 暂停时不显示缓冲动画：暂停状态下的 BUFFERING 是正常现象，转圈会让人以为卡住
                buffering = playbackState == Player.STATE_BUFFERING && player.playWhenReady
                if (playbackState == Player.STATE_ENDED) {
                    val nextId = nextEpisodeIdLive
                    // 片尾切换点贴近结尾时，位置轮询可能还没采到切换点就 ENDED；
                    // 自动连播条件仍成立则在这里换集，否则走既有退出上报链路。
                    if (!autoNextFired && autoNextEnabledLive && !autoNextCancelled &&
                        !playbackFailedLive && nextId != null
                    ) {
                        autoNextFired = true
                        autoNextSecondsLeft.intValue = 0
                        // 自然播完/片尾换集：本集已看完
                        openEpisodeLive(nextId, null, true)
                    } else if (!autoNextFired) {
                        exitCallback()
                    }
                    // autoNextFired 为 true 时换集已发起，不能再把新页 Pop 掉
                }
            }

            override fun onCues(cueGroup: CueGroup) {
                // 字幕延迟微调：撤销上一条未执行任务，避免旧 cue 覆盖新 cue
                cueScheduler.submit(cueGroup.cues, subtitleDelayLive)
                // 字幕是外挂文件，出问题时（轨已选中但屏幕没字）没有别的观测点，
                // 这条日志是排查「字幕轨通、渲染不通」的唯一线索；Release 自动静默。
                if (BuildConfig.DEBUG && cueGroup.cues.isNotEmpty()) {
                    android.util.Log.d(CUE_LOG_TAG, "cues=${cueGroup.cues.size}, bitmap=${cueGroup.cues.count { it.bitmap != null }}")
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                audioLabel = player.audioLabel()
                subtitleLabel = currentSubtitleLabel(player)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // 留痕：错误码是排查"缓冲慢→报错"的唯一线索（2001 系=网络/HTTP，
                // 3001 系=解码）。没有这行，实机只能看到"播放失败"四个字。
                android.util.Log.e(
                    "NovumPlayer",
                    "player error ${error.errorCodeName}(${error.errorCode}): ${error.message}",
                    error,
                )
                // 只记录错误次数，真正切换放到 LaunchedEffect：listener 里读不到最新的兜底状态。
                // 播放器回调不保证在主线程，Compose State 统一投递到主线程再写。
                val eventPlan = player.activePlaybackPlan()
                cueHandler.post {
                    if (eventPlan === player.activePlaybackPlan()) {
                        failedPlan = eventPlan
                        playerErrorTick++
                    }
                }
            }

            // 实时状态的画面尺寸（画质 tab）：与 PlayerScreen 里算显示比例的
            // 监听并存——那边要 SAR/DAR，这边只要原始宽高
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    cueHandler.post {
                        liveStats.videoWidth = videoSize.width
                        liveStats.videoHeight = videoSize.height
                    }
                }
            }
        }
        // 实时状态的丢帧数（画质 tab）：丢帧回调在 AnalyticsListener 上
        // （Player.Listener 无此口径），按回调增量累计
        val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                // AnalyticsListener 可能在播放器线程回调，State 写入投递到主线程
                cueHandler.post { liveStats.droppedFrames += droppedFrames }
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        onDispose {
            cueScheduler.cancel()
            // 丢帧/尺寸/错误等回调投递到主线程的写 State 任务随页面一起清掉，
            // 避免已退出播放页后仍有积压任务写 Compose State
            cueHandler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
        }
    }

    // 播放器报错即尝试兜底
    LaunchedEffect(playerErrorTick) {
        if (playerErrorTick > 0 && failedPlan === player.activePlaybackPlan()) {
            switchToFallback(player.mediaPositionMs())
        }
    }

    // 起播超时：直连地址不动声色地卡住时，等满 8 秒主动切转码，比让用户盯着黑屏好
    LaunchedEffect(player, plan.url) {
        observePlaybackStartupTimeout(player, PLAYBACK_START_TIMEOUT_MS) {
            // 已经切过兜底/转码/失败时不再重复触发：fallback 流自身缓冲慢是正常现象，
            // 旧定时器到点误判会把正在加载的 fallback 顶掉，无谓再走一次转码。
            if (!fallbackUsed && !transcodeRequested && !playbackFailed) {
                switchToFallback(player.mediaPositionMs())
            }
        }
    }

    // 位置与时长轮询：Compose 不订阅播放器内部时钟，用 500ms 采样换来稳定的重组频率
    LaunchedEffect(player) {
        while (isActive) {
            position.longValue = player.mediaPositionMs()
            duration.longValue = player.mediaDurationMs()
            buffered.longValue = player.mediaBufferedPositionMs()
            bandwidthBps.longValue = bandwidthEstimateLive()
            // 实时状态（画质 tab）：显示模式切换异步生效，500ms 轮询正好捕捉落地
            liveStats.bufferedPercentage = player.bufferedPercentage
            liveStats.bufferedAheadSeconds =
                ((buffered.longValue - position.longValue).coerceAtLeast(0L)) / 1_000L
            liveStats.displayMode = displayModeText()
            // 音频输出路径（信息面板实时展示）：当前音轨编码 + 直通开关 + 设备能力
            liveStats.audioOutput = audioOutputLabel(
                codec = player.audioFormat?.let { it.sampleMimeType ?: it.codecs },
                passthrough = audioPassthrough,
                deviceSupports = passthroughCapable,
                pcmText = audioPcmText,
                bitstreamText = audioBitstreamText,
                pcmFallbackText = audioPcmFallbackText,
            )
            delay(POSITION_TICK_MS)
        }
    }

    // 自动连播触发：位置越过切换点即换集。snapshotFlow 订阅位置/时长
    // 状态驱动——暂停即冻结（不偷跑），快进跳出窗口即退场；回调经
    // rememberUpdatedState 读最新值，闭包不持旧参数
    LaunchedEffect(plan.url) {
        snapshotFlow { position.longValue to duration.longValue }
            .collect { (pos, dur) ->
                if (autoNextFired || !autoNextEnabledLive || autoNextCancelled || playbackFailedLive) {
                    autoNextSecondsLeft.intValue = 0
                    return@collect
                }
                val nextId = nextEpisodeIdLive
                val switchAt = computeAutoNextSwitchAt(chaptersLive, dur)
                if (nextId == null || switchAt == null) {
                    autoNextSecondsLeft.intValue = 0
                    return@collect
                }
                val windowStart = (switchAt - AUTO_NEXT_COUNTDOWN_MS).coerceAtLeast(0L)
                when {
                    // 归零换集：与「下一集」浮钮/抽屉选集同链路（替换当前播放页）
                    pos >= switchAt -> {
                        autoNextFired = true
                        autoNextSecondsLeft.intValue = 0
                        // 倒计时归零 = 按「看完」收尾
                        openEpisodeLive(nextId, null, true)
                    }

                    pos >= windowStart -> autoNextSecondsLeft.intValue =
                        (((switchAt - pos) + 999) / 1000).toInt().coerceIn(1, AUTO_NEXT_COUNTDOWN_S)

                    else -> autoNextSecondsLeft.intValue = 0
                }
            }
    }

    // 兜底提示自动消失
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        delay(NOTICE_DURATION_MS)
        notice = null
    }

    // 控制条透明度：显隐只改 alpha，节点不销毁
    val controlsAlpha = animateFloatAsState(
        targetValue = if (controlsVisible || peeked) 1f else 0f,
        animationSpec = tween(CONTROLS_FADE_MS),
        label = "playerControlsAlpha",
    )

    // 自动隐藏：有交互就重置计时；焦点停在进度条上时不收起（用户正在调进度）
    LaunchedEffect(controlsVisible, peeked, interactionTick, focusedItem) {
        if (!controlsVisible && !peeked) return@LaunchedEffect
        if (controlsVisible && focusedItem == ControlItem.Progress) return@LaunchedEffect
        delay(if (controlsVisible) CONTROLS_HIDE_MS else CONTROLS_PEEK_MS)
        controlsVisible = false
        peeked = false
    }

    // 焦点跟随显隐：显示时落到当前项，隐藏时把焦点收回根节点（避免停在看不见的按钮上）。
    // 确认框关闭后也要靠这里把焦点交还给控制条，否则焦点会停在已被移除的按钮上。
    LaunchedEffect(controlsVisible, focusedItem, confirmExit) {
        // 抽屉打开期间焦点由抽屉自管（tab 芯片），这里绝不接管——否则自动隐藏
        // 计时一到就会把焦点从抽屉拽到根节点（「弹出后焦点被抢走」）
        if (drawerVisible) return@LaunchedEffect
        if (playbackFailed) return@LaunchedEffect
        // 确认框打开时不抢焦点：确认框自己的初始焦点机制负责
        if (confirmExit) return@LaunchedEffect
        // 等一帧，确保 focusProperties 的 canFocus 已生效
        withFrameNanos { }
        if (controlsVisible) {
            val requester = if (focusedItem == ControlItem.Progress) {
                progressRequester
            } else {
                requesters[focusedItem] ?: requesters.getValue(ControlItem.PlayPause)
            }
            runCatching { requester.requestFocus() }
        } else {
            runCatching { rootRequester.requestFocus() }
        }
    }

    // 关抽屉的焦点交接（Back 专用）：等一帧后把焦点交还「播放设置」按钮。
    // 独立于上面的焦点跟随——OK 路径开抽屉时 focusedItem 本就是 Settings，
    // 那边的状态键不会变化，必须用独立 tick 强制重跑。
    LaunchedEffect(backFocusTick) {
        if (backFocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requesters.getValue(ControlItem.Settings).requestFocus() }
    }

    // 「下一集」浮钮出现即落焦：仅在控制条/抽屉/确认框
    // 都不在场时接手——用户正操作控制条时不动焦点（控制条上本就有下一集钮）。
    // 必须声明在「焦点跟随显隐」之后：控制条隐藏瞬间两边都要落焦，后声明者
    // 后恢复执行，浮钮落焦胜出
    LaunchedEffect(showNextEpisode, controlsVisible, peeked, drawerVisible, confirmExit) {
        if (!showNextEpisode) return@LaunchedEffect
        if (controlsVisible || peeked || drawerVisible || confirmExit) return@LaunchedEffect
        withFrameNanos { }
        runCatching { nextEpisodeRequester.requestFocus() }
    }

    // 「跳过片头/片尾」浮钮落焦：优先级高于下一集（片尾时跳过更有意义）
    LaunchedEffect(showSkip, controlsVisible, peeked, drawerVisible, confirmExit) {
        if (!showSkip) return@LaunchedEffect
        if (controlsVisible || peeked || drawerVisible || confirmExit) return@LaunchedEffect
        withFrameNanos { }
        runCatching { skipRequester.requestFocus() }
    }

    /**
     * 粗调 seek：隐藏态左右键使用，10 秒起、按住 30 秒。
     *
     * @param deltaMs 相对位移，正数前进。
     */
    fun seekBy(deltaMs: Long) {
        val total = duration.longValue
        val target = (player.mediaPositionMs() + deltaMs).coerceAtLeast(0L)
        val clamped = if (total > 0) target.coerceAtMost(total) else target
        player.seekToMediaPosition(clamped, onRebuildServerStream)
        // 立刻回写显示位置，等下一次 500ms 轮询会有明显延迟感
        position.longValue = clamped
    }

    /**
     * 精细 seek：进度条聚焦时使用，先改 UI 再节流落地，保证「按一下动一格」。
     *
     * @param deltaMs 相对位移。
     */
    fun scrubBy(deltaMs: Long) {
        val total = duration.longValue
        val base = scrubTarget ?: player.mediaPositionMs()
        val target = (base + deltaMs).coerceAtLeast(0L)
        scrubTarget = if (total > 0) target.coerceAtMost(total) else target
        interactionTick++
    }

    // 精细 seek 的节流落地：连续按键只发一次 seekTo，停手后把显示权交回播放器
    LaunchedEffect(scrubTarget) {
        val target = scrubTarget ?: return@LaunchedEffect
        delay(SCRUB_THROTTLE_MS)
        player.seekToMediaPosition(target, onRebuildServerStream)
        position.longValue = target
        delay(SCRUB_SETTLE_MS)
        if (scrubTarget == target) scrubTarget = null
    }

    /** 隐藏态按 OK / 左右键：控制条亮一下但不抢焦点。 */
    fun peekControls() {
        peeked = true
        interactionTick++
    }

    /** 呼出控制条并把焦点放到播放键。 */
    fun showControls() {
        controlsVisible = true
        peeked = false
        focusedItem = ControlItem.PlayPause
        interactionTick++
    }

    /**
     * 打开播放设置抽屉：
     * 控制条**立即收起**——它留在屏上会照常走 5 秒自动隐藏，计时一到
     * 焦点跟随副作用就把焦点从抽屉拽到根节点（表现为「抽屉弹出后焦点被抢走」）。
     * 抽屉自己管理焦点（tab 芯片），控制条收起后 canFocus=false 不可能抢焦。
     */
    fun openDrawer() {
        drawerVisible = true
        controlsVisible = false
        peeked = false
    }

    /** 激活控制条上的某个控件（按钮行扩展）。 */
    fun activate(item: ControlItem) {
        when (item) {
            ControlItem.PlayPause -> {
                if (player.isPlaying) player.pause() else player.play()
            }

            ControlItem.SeekBack -> seekBy(-SEEK_BUTTON_MS)
            ControlItem.SeekFwd -> seekBy(SEEK_BUTTON_MS)

            ControlItem.Speed -> speedPickerVisible = true

            // 画面比例：OK 循环 适应 → 拉伸 → 裁剪
            ControlItem.Scale -> {
                scaleMode = scaleMode.next()
            }

            ControlItem.Subtitles -> {
                drawerTab = DrawerTab.Subtitle
                openDrawer()
            }

            ControlItem.Audio -> {
                drawerTab = DrawerTab.Audio
                openDrawer()
            }

            ControlItem.Settings -> {
                drawerTab = DrawerTab.Episodes
                openDrawer()
            }

            // 进度条本身没有「点击动作」，OK 键留给播放暂停会误触，直接忽略
            ControlItem.Progress -> Unit
        }
        interactionTick++
    }

    // 返回键**直接在按键管线里消费并分层**，不走 BackHandler（
    // 「第一下返回只丢焦点、第二下才生效」——OnBackPressedDispatcher 在焦点交接
    // 场景下的触发时序不稳定，管线消费保证每一发到达应用的返回键都先经过这里）。
    // 分层：抽屉开 → 关抽屉 + 焦点回「播放设置」；控制条可见 → 收起控制条
    // 都没有 → 弹退出确认。确认框打开时按键在它自己的窗口里，不经过这里。
    // 焦点交接不能同步 requestFocus（抽屉打开期间控制条按钮 canFocus 还是旧值
    // 同步调用会静默失败、焦点落到根节点失去高亮）——只改状态
    // 由 backFocusTick 副作用等一帧后落焦点（见下方 LaunchedEffect）。
    fun handleBackKey(): Boolean {
        when {
            skipFocused -> {
                skipDismissed = true
                runCatching { rootRequester.requestFocus() }
            }

            // 浮钮聚焦时 Back = 收起浮钮（本集不再弹出；片尾仍有自动退出兜底），
            // 不弹退出确认
            nextEpisodeFocused -> {
                nextEpisodeDismissed = true
                runCatching { rootRequester.requestFocus() }
            }

            drawerVisible -> {
                drawerVisible = false
                controlsVisible = true
                focusedItem = ControlItem.Settings
                backFocusTick++
            }

            controlsVisible -> {
                controlsVisible = false
                peeked = false
            }

            else -> confirmExit = true
        }
        return true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(rootRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // 自动连播倒计时：任意键取消且键照常生效——放在管线
                // 最前，返回键也先取消再走确认退出（倒计时不会在确认框在场时悄悄触发）
                if (autoNextSecondsLeft.intValue > 0) autoNextCancelled = true

                // 确认框打开：按键全部交给它的焦点系统（左右切按钮、OK 激活）
                if (confirmExit) return@onPreviewKeyEvent false

                // 「下一集」浮钮持有焦点（临近结束自动落焦）：OK 交给按钮激活，
                // 根节点绝不能在这里消费成播放暂停；方向键交还根节点后按隐藏态
                // 语义继续（Up/Down 呼出控制条、左右 seek）
                if (nextEpisodeFocused) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                            return@onPreviewKeyEvent false

                        Key.DirectionUp, Key.DirectionDown -> {
                            runCatching { rootRequester.requestFocus() }
                            showControls()
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionLeft -> {
                            runCatching { rootRequester.requestFocus() }
                            seekBy(-seekStepMs(event.nativeKeyEvent.repeatCount))
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionRight -> {
                            runCatching { rootRequester.requestFocus() }
                            seekBy(seekStepMs(event.nativeKeyEvent.repeatCount))
                            return@onPreviewKeyEvent true
                        }

                        else -> {}
                    }
                }

                // 返回键：管线内分层消费（见 handleBackKey 注释）
                if (event.key == Key.Back) return@onPreviewKeyEvent handleBackKey()

                // 设置抽屉打开：内容行的左右键在此消费（不逃逸到控制条）
                // 焦点在 tab 芯片行上时必须放行——tab 行自己处理左右切 tab
                // 被根节点吞掉就永远切不动
                if (drawerVisible) {
                    return@onPreviewKeyEvent when (event.key) {
                        // 调节行获焦时放行左右键给抽屉内的无级调节（onStep 消费）；
                        // tab 行获焦时也放行（芯片间移焦）；其余内容行吞掉防逃逸控制条
                        Key.DirectionLeft, Key.DirectionRight -> !drawerTabsFocused && !drawerAdjusting
                        else -> false
                    }
                }

                val command = resolvePlayerCommand(
                    key = event.key,
                    repeatCount = event.nativeKeyEvent.repeatCount,
                    controlsVisible = controlsVisible,
                    progressFocused = focusedItem == ControlItem.Progress,
                    confirmVisible = confirmExit,
                    overlayActionFocused = skipFocused || playbackFailed,
                )

                // 可见态下任何按键都重置自动隐藏计时（包括交给焦点系统处理的那些）
                if (command == null) {
                    if (controlsVisible) interactionTick++
                    return@onPreviewKeyEvent false
                }

                when (command) {
                    PlayerCommand.TogglePlay -> {
                        if (player.isPlaying) player.pause() else player.play()
                        peekControls()
                    }

                    is PlayerCommand.SeekBy -> {
                        seekBy(command.deltaMs)
                        peekControls()
                    }

                    PlayerCommand.ShowControls -> showControls()
                    PlayerCommand.OpenSettings -> openDrawer()

                    is PlayerCommand.FineSeek -> scrubBy(command.deltaMs)
                }
                true
            },
    ) {
        // 使用 SurfaceView 保留 Dolby Vision/HDR 元数据。
        //
        // 缩放模式：
        // 默认（自适应）：按显示比例等比呈现，短边填满、留黑边——忠实还原画幅
        // 拉伸（全屏拉伸）：Surface 按比例定型，两轴分别放大强行铺满整屏
        //   允许变形（解码器按 SCALE_TO_FIT 在 Surface 内先行等比留边，故必须
        //   用 graphicsLayer 缩放而非只改 View 尺寸）
        // 裁剪（全屏裁剪）：等比定型后均匀放大铺满，出界部分居中裁掉
        // 比例未知（null）：一律铺满（无比例可依据）。
        // OK 循环即时改修饰符 = 无缝预览（控制条不收起，画面当场形变）。
        //
        // 源画幅与屏幕一致时，三种模式的几何结果相同；非 16:9 源才会显示差异。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // 视频画布底：纯黑是播放语义（非皮肤令牌），非 16:9 片源留边即黑边
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            val boxAspect = maxWidth / maxHeight
            val videoAspect = videoAspectRatio
            val surfaceModifier = when {
                videoAspect == null -> Modifier.fillMaxSize()
                scaleMode == VideoScaleMode.Stretch -> Modifier
                    .aspectRatio(videoAspect)
                    .graphicsLayer {
                        // 画面比例与容器比例之差转成两轴分别放大：短边方向拉伸填满
                        scaleX = maxOf(1f, boxAspect / videoAspect)
                        scaleY = maxOf(1f, videoAspect / boxAspect)
                    }
                scaleMode == VideoScaleMode.Fit -> Modifier.aspectRatio(videoAspect)
                else -> {
                    // 裁剪：取「画面比例/容器比例」与倒数中较大者，保证短边铺满
                    val zoom = maxOf(videoAspect / boxAspect, boxAspect / videoAspect)
                    Modifier
                        .aspectRatio(videoAspect)
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                        }
                }
            }
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = surfaceModifier,
            )
        }

        // 弹幕层：视频之上、字幕与控制层之下（默认上半屏，不与字幕抢位）
        DanmuOverlay(
            comments = danmu.comments,
            config = danmu.config,
            offsetMs = danmu.offsetMs,
            player = player,
            modifier = Modifier.fillMaxSize(),
        )

        // 字幕落位：宽银幕影片时放进**下黑边区域**
        // 且与画面下缘保持距离（不紧贴黑边上部）——按视频比例反算黑边高度
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val bottomBarHeight = bottomLetterboxHeight(maxWidth.value, maxHeight.value, videoAspectRatio, scaleMode).dp
            SubtitleLayer(
                cues = subtitleCues,
                sizeLevel = subtitleSize,
                weightLevel = subtitleWeight,
                offsetLevel = subtitleOffsetLevel,
                controlsShown = controlsVisible || peeked,
                bottomBarHeight = bottomBarHeight,
                videoAspectRatio = videoAspectRatio,
                scaleMode = scaleMode,
                modifier = Modifier.fillMaxSize(),
            )
        }

        BufferingIndicator(
            visible = buffering,
            // 网速只在有估计值时显示（起播首轮/本地无数据时不挂空文案）
            // 缓冲网速按网络传输习惯用 MB/s（÷8 字节口径）
            // 抽屉画质 tab 的规格行仍用 Mbps 码率口径（formatBitrate）
            speedText = bandwidthBps.longValue.takeIf { it >= 0L }?.let(::formatSpeed),
            modifier = Modifier.align(Alignment.Center),
        )

        // 渐变遮罩：单独一层，只跟随控制条 alpha
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = controlsAlpha.value }
                .background(ControlScrimBrush),
        )

        PlayerControlOverlay(
            title = title,
            logoUrl = logoUrl,
            episodeTitle = episodeTitle,
            networkSpeed = bandwidthBps,
            chapters = chapters,
            isPlaying = isPlaying,
            interactive = controlsVisible && !confirmExit,
            focusedItem = focusedItem,
            speedText = speedLabel(speed),
            scaleText = scaleText,
            alpha = controlsAlpha,
            position = position,
            duration = duration,
            buffered = buffered,
            scrubTarget = scrubTarget,
            requesters = requesters,
            progressRequester = progressRequester,
            onFocusedItemChange = { focusedItem = it },
            onActivate = ::activate,
        )

        // 临近结束时显示「下一集」浮钮；控制条呼出时浮钮让位。
        AnimatedVisibility(
            visible = showNextEpisode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            NovumButton(
                // 浮钮只在片尾 60 秒内出现：点了就是「看完换下一集」
                onClick = { nextEpisodeId?.let { onOpenEpisode(it, null, true) } },
                modifier = Modifier
                    .padding(
                        end = NovumDimens.SafeHorizontal,
                        bottom = NEXT_EPISODE_BOTTOM_PAD,
                    )
                    .focusRequester(nextEpisodeRequester)
                    .onFocusChanged { nextEpisodeFocused = it.isFocused },
            ) {
                Text(
                    text = stringResource(R.string.player_control_next),
                    style = NovumType.ButtonLg,
                )
                Icon(
                    imageVector = NovumIcons.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 「跳过片头/片尾」浮钮与下一集按钮纵向错开。
        AnimatedVisibility(
            visible = showSkip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            val target = skipTarget.value
            NovumButton(
                onClick = {
                    target?.let { player.seekToMediaPosition(it.seekToMs, onRebuildServerStream) }
                    skipDismissed = true
                    runCatching { rootRequester.requestFocus() }
                },
                modifier = Modifier
                    .padding(
                        end = NovumDimens.SafeHorizontal,
                        bottom = SKIP_BUTTON_BOTTOM_PAD,
                    )
                    .focusRequester(skipRequester)
                    .onFocusChanged { skipFocused = it.isFocused },
            ) {
                Text(
                    text = stringResource(
                        if (target?.isIntro == true) R.string.player_skip_intro
                        else R.string.player_skip_credits,
                    ),
                    style = NovumType.ButtonLg,
                )
                Icon(
                    imageVector = NovumIcons.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // 自动连播倒计时：占「下一集」浮钮同槽位（浮钮在场时让位）。
        // 纯信息层不抢焦——任意键取消且键照常生效（根节点按键管线最前统一处理）
        // 秒数由播放位置驱动，暂停即冻结
        AnimatedVisibility(
            visible = autoNextSecondsLeft.intValue > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd),
        ) {
            Column(
                modifier = Modifier
                    .padding(
                        end = NovumDimens.SafeHorizontal,
                        bottom = NEXT_EPISODE_BOTTOM_PAD,
                    )
                    .clip(NovumShapes.Pill)
                    .background(NovumPalette.Scrim)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        R.string.player_autonext_countdown,
                        autoNextSecondsLeft.intValue,
                    ),
                    style = NovumType.ButtonLg,
                    color = NovumPalette.Paper,
                )
                Text(
                    text = stringResource(R.string.player_autonext_cancel_hint),
                    style = NovumType.Caption,
                    color = NovumPalette.PaperMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        NoticeBanner(
            notice = notice,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // 播放设置抽屉：右侧滑入，焦点独立成组；起为唯一设置面，tab 化
        // 纵向补滚不吃全局 16dp 死区（「选集列表移到最上/最下
        // 文字显示不全」根治——根因 = 根级死区吞掉极端行的收尾补滚）
        NovumNoScrollDeadZone {
            PlayerSettingsDrawer(
                visible = drawerVisible,
                initialTab = drawerTab,
                onTabsFocusChanged = { drawerTabsFocused = it },
                onAdjustFocusChanged = { drawerAdjusting = it },
                audioTracks = audioTracks,
                textTracks = textTracks,
                passthrough = audioPassthrough,
                plan = activePlan,
                title = title,
                subtitle = subtitle,
                overview = overview,
                audioLabel = audioLabel,
                subtitleLabel = subtitleLabel,
                liveStats = liveStats,
                chapters = chapters,
                audioDelayMs = audioDelayMs,
                onAudioDelayChanged = onAudioDelayChanged,
                subtitleDelayMs = subtitleDelayMs,
                onSubtitleDelayChanged = onSubtitleDelayChanged,
                subtitleSize = subtitleSize,
                onSubtitleSizeChanged = onSubtitleSizeChanged,
                subtitleWeight = subtitleWeight,
                onSubtitleWeightChanged = onSubtitleWeightChanged,
                subtitleOffsetLevel = subtitleOffsetLevel,
                onSubtitleOffsetLevelChanged = onSubtitleOffsetLevelChanged,
                danmu = danmu,
                danmuActions = danmuActions,
                danmuSearchPrefill = danmuSearchPrefill,
                onSelectAudio = { option ->
                    selectManualTrack(player, option, isText = false) { onRememberTrack(option, false) }
                },
                onSelectText = { option ->
                    if (activeBurnedInLabel == null) {
                        selectManualTrack(player, option, isText = true) { onRememberTrack(option, true) }
                    }
                },
                onSelectServerSubtitle = onSelectServerSubtitle,
                subtitleSwitching = subtitleSwitching,
                subtitleSwitchError = subtitleSwitchError,
                onTogglePassthrough = {
                    drawerVisible = false
                    onSetPassthrough(!audioPassthrough)
                    notice = reloadingNotice
                },
                currentPosition = scrubTarget ?: position.longValue,
                onSelectChapter = { chapter ->
                    player.seekToMediaPosition(chapter.startMs, onRebuildServerStream)
                    position.longValue = chapter.startMs
                    drawerVisible = false
                    controlsVisible = true
                },
                episodes = episodes,
                currentEpisodeId = currentEpisodeId,
                // 选集中的某一集：关抽屉后**直接换集播放**——
                // 多版本 + 「优先视频质量 = 每次询问」时先弹版本层，选完按该版本起播
                // 单版本/其他策略档直接换集（不绕回详情页）
                onSelectEpisode = { episodeId ->
                    drawerVisible = false
                    // 点当前集本身不换集（避免无谓重载）
                    if (episodeId != currentEpisodeId) {
                        val target = episodes.firstOrNull { it.id == episodeId }
                        if (target != null &&
                            target.versions.size > 1 &&
                            versionStrategy.equals(ASK_STRATEGY, ignoreCase = true)
                        ) {
                            versionPickEpisode = target
                        } else {
                            onOpenEpisode(episodeId, null, false)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // 抽屉选集换版本的浮层（多版本 + 每次询问）：选完按该版本换集起播
        versionPickEpisode?.let { episode ->
            val prefix = title.ifBlank { episode.title }
            NovumOptionListDialog(
                title = stringResource(R.string.detail_pref_dialog_version),
                options = episode.versions.map { it.displayLine(titlePrefix = prefix) },
                selectedIndex = 0,
                onSelect = { index ->
                    val version = episode.versions.getOrNull(index)
                    versionPickEpisode = null
                    onOpenEpisode(episode.id, version?.sourceId, false)
                },
                onDismiss = { versionPickEpisode = null },
            )
        }

        if (speedPickerVisible) {
            NovumOptionListDialog(
                title = stringResource(R.string.player_info_speed),
                options = PLAYBACK_SPEEDS.map(::speedLabel),
                selectedIndex = PLAYBACK_SPEEDS.indexOf(speed).takeIf { it >= 0 },
                awaitKeyRelease = true,
                onSelect = { index ->
                    PLAYBACK_SPEEDS.getOrNull(index)?.let { selectedSpeed ->
                        speed = selectedSpeed
                        player.setPlaybackSpeed(selectedSpeed)
                        onSpeedChanged(selectedSpeed)
                    }
                    speedPickerVisible = false
                },
                onDismiss = { speedPickerVisible = false },
            )
        }

        if (confirmExit) {
            NovumConfirmDialog(
                title = stringResource(R.string.player_exit_title),
                message = stringResource(R.string.player_exit_message),
                confirmText = stringResource(R.string.player_exit_confirm),
                cancelText = stringResource(R.string.action_cancel),
                onConfirm = onExit,
                onCancel = { confirmExit = false },
                // 退出确认背后是视频画面本身，压太黑浪费
                scrimAlpha = 0.45f,
            )
        }

        if (playbackFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NovumPalette.Scrim.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.player_error_title),
                        style = NovumType.Section,
                        color = NovumPalette.Paper,
                    )
                    Text(
                        text = stringResource(R.string.player_error_hint),
                        style = NovumType.Caption,
                        color = NovumPalette.PaperMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    val retryFocus = remember { FocusRequester() }
                    LaunchedEffect(plan.url) {
                        withFrameNanos { }
                        runCatching { retryFocus.requestFocus() }
                    }
                    NovumButton(
                        onClick = {
                            playbackFailed = false
                            onRetryNextLine(player.mediaPositionMs())
                        },
                        modifier = Modifier.focusRequester(retryFocus),
                    ) {
                        Text(
                            text = stringResource(R.string.player_retry_next_line),
                            style = NovumType.ButtonLg,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 字幕延迟调度：新 cue 到达时先撤销上一条未执行的延迟任务，
 * 延迟归零时立即显示且不再排任务，避免旧字幕覆盖新字幕。
 */
internal class CueDelayScheduler(
    private val handler: android.os.Handler,
    private val onCues: (List<Cue>) -> Unit,
) {
    private var pending: Runnable? = null

    fun submit(cues: List<Cue>, delayMs: Long) {
        pending?.let(handler::removeCallbacks)
        pending = null
        if (delayMs > 0) {
            val runnable = Runnable {
                pending = null
                onCues(cues)
            }
            pending = runnable
            handler.postDelayed(runnable, delayMs)
        } else {
            onCues(cues)
        }
    }

    fun cancel() {
        pending?.let(handler::removeCallbacks)
        pending = null
    }
}

/** 进度采样间隔。 */
private const val POSITION_TICK_MS = 500L

/** 控制条无操作后自动隐藏时间。 */
private const val CONTROLS_HIDE_MS = 5_000L

/** 隐藏态「亮一下」的停留时间。 */
private const val CONTROLS_PEEK_MS = 2_500L

/** 控制条显隐动画时长。 */
private const val CONTROLS_FADE_MS = 200

/** 精细 seek 的合并窗口：连续按键只发一次 seekTo。 */
private const val SCRUB_THROTTLE_MS = 120L

/** seek 落地后把显示权交回播放器的等待时间。 */
private const val SCRUB_SETTLE_MS = 400L

/** 起播超时：超过该时间仍未 READY 就尝试兜底地址。 */
private const val PLAYBACK_START_TIMEOUT_MS = 8_000L

/** 控制条快进/后退按钮的步长。 */
private const val SEEK_BUTTON_MS = 10_000L

/** 临近结束浮出「下一集」按钮的窗口：最后 60 秒。 */
private const val NEXT_EPISODE_WINDOW_MS = 60_000L

/** 「下一集」浮钮的底部让位：坐进控制条上方的空档，控制条呼出时不与按钮行重叠。 */
private val NEXT_EPISODE_BOTTOM_PAD = 140.dp

/** 可选倍速档位。 */
private val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** 「优先视频质量」策略值：每次询问（抽屉换集先弹版本选择层）。 */
private const val ASK_STRATEGY = "ask"
