package com.novum.tv.ui.screen.player

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.novum.tv.core.player.OffloadRenderersFactory
import com.novum.tv.core.player.buildMediaItem
import com.novum.tv.core.state.UiState
import com.novum.tv.data.remote.emby.EmbyClientFactory
import com.novum.tv.feature.player.BuildConfig
import com.novum.tv.ui.component.NovumStateView
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import androidx.compose.foundation.layout.height

/**
 * 播放页入口：准备播放器实例、拉取播放计划、渲染播放舞台。
 *
 * 播放器实例由界面持有而不是 ViewModel：它强绑定 Surface 与音频焦点
 * 放进 ViewModel 会在页面销毁后继续持有解码器，属于典型泄漏。
 *
 * @param onBack 退出播放。
 * @param onOpenEpisode 换集（下一集浮钮/抽屉选集/自动连播）；第二参数为本次播放版本
 *   （null = 按画质策略/服务端默认），第三参数为本集是否已播完（收尾上报用）；
 *   替换当前播放页避免回退栈堆积。
 * @param modifier 外部修饰符。
 * @param viewModel 播放页 ViewModel。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onOpenEpisode: (itemId: String, mediaSourceId: String?, finished: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 音画同步微调：音频/字幕延迟毫秒（正=推迟），会话级
    var audioDelayMs by remember { mutableLongStateOf(0L) }
    var subtitleDelayMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // 播放数据与字幕都必须走和 API 相同的网络出口：
    // 网关按 UA / 来源站校验，ExoPlayer 默认栈会拿到 403，表现就是「字幕轨选中却没字」。
    // offload 能力位进 remember key：能力位就绪即重建播放器
    // （load 前是默认 false，首帧计划到达时已带真实值——实际只构一次，不闪）
    // 带宽计（缓冲时显示网速）：注入自建实例才能读到估计值
    // （ExoPlayer.Builder 默认内部建的不外露）；Stage 轮询 bitrateEstimate 展示
    val bandwidthMeter = remember { DefaultBandwidthMeter.Builder(context).build() }
    val player = remember(state.offloadCapable, bandwidthMeter) {
        val okHttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", EmbyClientFactory.BROWSER_USER_AGENT)
                        .build(),
                )
            }
            .build()
        val dataSourceFactory = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttp))
        ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            // 音频 offload 适配（试验性）：直通能力非空时
            // 自定义 sink 开 DSP offload；初始化失败由框架自动回落 PCM
            .setRenderersFactory(
                OffloadRenderersFactory(
                    context = context,
                    offloadCapable = state.offloadCapable,
                    passthrough = state.audioPassthrough,
                    audioDelayMs = { audioDelayMs },
                ),
            )
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()
            .apply {
                // 直通出声双保险之二：必须声明媒体/电影用途，否则多声道仍可能被系统解成 PCM
                // （服务端 DeviceProfile 声明是第一重，见 CapabilityProvider）
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true,
                )
                // 画面比例：满屏 Surface + 驱动等比
                // 缩放（信箱式留黑边），不做缩放方式切换入口
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playWhenReady = true
            }
    }
    // release 跟随 player 身份：remember key 变化重建播放器时旧实例先释放（不泄漏 Surface）
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // 字幕/音轨语言偏好（详情页默认偏好 → 设置 → 起播生效）。
    // ExoPlayer 原生语言匹配（Emby Language 为 ISO 639-2，与偏好同口径）
    // 播放中手动切轨仍走 TrackSelectionOverride，二者不冲突。
    // zhdual（中英双语）无 ISO 码：语言匹配跳过，改由 onTracksChanged 按轨
    // 标题钉选（见 applyDualSubtitlePreference）；偏好变更时清掉旧 override
    // 防止上一档的钉选压住新语言匹配。
    val subtitleLanguagePref by rememberUpdatedState(state.preferredSubtitleLanguage)
    // 条目级手动记忆：优先级高于语言偏好——上次手动选过
    // 的轨道起播即恢复；rememberUpdatedState 防 listener 闭包读到旧值
    val rememberedPrefs by rememberUpdatedState(state.rememberedPrefs)
    LaunchedEffect(player, state.preferredAudioLanguage, state.preferredSubtitleLanguage) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(state.preferredAudioLanguage.ifBlank { null })
            .setPreferredTextLanguage(
                state.preferredSubtitleLanguage
                    .takeUnless { it.isBlank() || it == DUAL_SUBTITLE_LANGUAGE },
            )
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
    }

    // 视频宽高比：来自轨道格式（带旋转修正），决定画面呈现尺寸；未知时回落满屏
    var videoAspectRatio by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // 比例只用于字幕黑边定位（画面呈现交给驱动等比缩放，见 PlayerStage）。
            // 来源用 onVideoSizeChanged（解码器上报帧尺寸 + SAR/DAR），
            // onTracksChanged 的 format 可能还没带尺寸。
            override fun onTracksChanged(tracks: Tracks) {
                // 条目记忆优先（手动改过的字幕/音轨起播即恢复），未命中回落语言偏好
                val subtitleRemembered = applyRememberedSubtitle(player, rememberedPrefs)
                if (!subtitleRemembered && subtitleLanguagePref == DUAL_SUBTITLE_LANGUAGE) {
                    applyDualSubtitlePreference(player)
                }
                applyRememberedAudio(player, rememberedPrefs)
                if (!BuildConfig.DEBUG) return
                tracks.groups
                    .firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
                    ?.getTrackFormat(0)
                    ?.let { format ->
                        android.util.Log.i(
                            VIDEO_LOG_TAG,
                            "tracks: w=${format.width} h=${format.height} " +
                                "par=${format.pixelWidthHeightRatio} rot=${format.rotationDegrees} " +
                                "codecs=${format.codecs} fps=${format.frameRate}",
                        )
                    }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width.takeIf { it > 0 } ?: return
                val height = videoSize.height.takeIf { it > 0 } ?: return
                val displayWidth = width * videoSize.pixelWidthHeightRatio
                videoAspectRatio = if (videoSize.unappliedRotationDegrees == 90 ||
                    videoSize.unappliedRotationDegrees == 270
                ) {
                    height / displayWidth
                } else {
                    displayWidth / height
                }.coerceIn(0.5f, 4f)
                // 画面比例排查：解码器实际上报值全量落日志
                // 供 logcat 拉取定位《信号》变形问题；Release 自动静默
                if (BuildConfig.DEBUG) {
                    android.util.Log.i(
                        VIDEO_LOG_TAG,
                        "size=${videoSize.width}x${videoSize.height} " +
                            "par=${videoSize.pixelWidthHeightRatio} " +
                            "rot=${videoSize.unappliedRotationDegrees} " +
                            "aspect=$videoAspectRatio",
                    )
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val plan = state.data
    val subtitlesEnabled = state.subtitlesEnabled

    // 显示模式切换：起播黑场期按片源规格把窗口指到能 1:1
    // 容纳片源的显示模式（4K 片源不再被 1080p 输出封顶），播放会话离场还原。
    // 本副作用必须排在 prepare 之前——同一黑场期完成切换，首帧即目标模式
    val activity = context as? Activity
    DisposableEffect(activity) {
        // 还原挂播放页离场；换集替换导航期旧页还原晚于新页起播，由
        // DisplayModeSwitcher 的代际计数让位（详见其文档）
        onDispose { activity?.let(DisplayModeSwitcher::endSession) }
    }
    LaunchedEffect(plan, activity) {
        if (plan == null || activity == null) return@LaunchedEffect
        val spec = plan.videoSpec
        DisplayModeSwitcher.beginSession(activity, spec?.width, spec?.height, spec?.frameRate)
    }

    LaunchedEffect(plan, subtitlesEnabled) {
        if (plan != null) {
            if (BuildConfig.DEBUG) {
                android.util.Log.d(CUE_LOG_TAG, "prepare: subs=${plan.subtitles.size} method=${plan.method}")
            }
            player.setMediaItem(
                buildMediaItem(plan, subtitlesEnabled),
                plan.startPositionMs,
            )
            player.prepare()
            viewModel.onStarted(plan.startPositionMs)
        }
    }
    // 播放中定时回写进度：5 秒一次，异常退出时最多丢 5 秒
    LaunchedEffect(plan) {
        if (plan == null) return@LaunchedEffect
        while (isActive) {
            delay(PROGRESS_REPORT_INTERVAL_MS)
            if (player.isPlaying) viewModel.onProgress(player.currentPosition)
        }
    }

    /**
     * 收尾位置：决定服务端怎么记账。
     *
     * - `finished = true`（自动连播、片尾浮钮、自然播完）→ 上报完整时长：本集
     *   判为已看，NextUp 前进到下一集；
     * - `finished = false` 但已在该集最后 5% → 同样按看完上报（片尾处手动退出
     *   多半就是看完了，按 92% 上报会一直留在「看到一半」）；
     * - 其余 → 实际位置，避免把半集误标成已看。
     */
    val stopPosition: (finished: Boolean) -> Long = { finished ->
        val duration = player.duration
        val position = player.currentPosition
        when {
            finished -> if (duration > 0) duration else position
            duration > 0 && position >= duration - duration / 20 -> duration
            else -> position
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 视频画布底：纯黑是播放语义（非皮肤令牌），OLED 下与全站底色一致
            .background(Color.Black),
    ) {
        when (val planState = state.plan) {
            // 起播阶段就是黑场 + 一个低透明度圆弧，不放骨架屏：
            // 播放页没有「列表结构」可占位，骨架反而像加载失败
            UiState.Loading -> BufferingIndicator(
                visible = true,
                modifier = Modifier.align(Alignment.Center),
            )

            is UiState.Error -> NovumStateView(
                state = planState,
                onRetry = viewModel::load,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> if (plan != null) {
                PlayerStage(
                    player = player,
                    audioDelayMs = audioDelayMs,
                    onAudioDelayChanged = { audioDelayMs = it },
                    subtitleDelayMs = subtitleDelayMs,
                    onSubtitleDelayChanged = { subtitleDelayMs = it },
                    onRememberTrack = { option, isText ->
                        // 「关闭字幕」走 disabled 位；具体轨记「标题+语言」
                        if (option.id == TEXT_OFF_ID) {
                            viewModel.rememberManualTrack(isText = true, label = "", language = null, disabled = true)
                        } else {
                            viewModel.rememberManualTrack(isText, option.label, option.language)
                        }
                    },
                    plan = plan,
                    videoAspectRatio = videoAspectRatio,
                    title = state.title,
                    logoUrl = state.logoUrl,
                    overview = state.overview,
                    subtitle = state.subtitle,
                    subtitleSize = state.subtitleSize,
                    onSubtitleSizeChanged = viewModel::setSubtitleSize,
                    subtitleOffsetLevel = state.subtitleOffsetLevel,
                    onSubtitleOffsetLevelChanged = viewModel::setSubtitleOffsetLevel,
                    subtitlesEnabled = subtitlesEnabled,
                    chapters = state.chapters,
                    nextEpisodeId = state.nextEpisodeId,
                    onOpenEpisode = { nextId, sourceId, finished ->
                        // 换集 = 本集结束：必须先上报停止再替换播放页，否则服务端
                        // 收不到结束位置（该集停在"看到一半"，NextUp 不前进）
                        viewModel.onStopped(stopPosition(finished))
                        onOpenEpisode(nextId, sourceId, finished)
                    },
                    episodes = state.episodes,
                    currentEpisodeId = state.currentEpisodeId,
                    versionStrategy = state.versionStrategy,
                    onExit = {
                        viewModel.onStopped(stopPosition(false))
                        onBack()
                    },
                    onMethodChanged = viewModel::onMethodChanged,
                    onRequestTranscode = viewModel::retryWithTranscode,
                    audioPassthrough = state.audioPassthrough,
                    passthroughCapable = state.offloadCapable,
                    onSetPassthrough = { enabled ->
                        viewModel.setAudioPassthrough(enabled, player.currentPosition)
                    },
                    onRetryNextLine = { positionMs -> viewModel.retryNextLine(positionMs) },
                    initialSpeed = state.playbackSpeed,
                    onSpeedChanged = { speed -> viewModel.setPlaybackSpeed(speed) },
                    bandwidthEstimate = { bandwidthMeter.bitrateEstimate },
                    displayModeText = { activity?.let(DisplayModeSwitcher::currentModeText) },
                    autoPlayNext = state.autoPlayNext,
                )
            }
        }
    }
}

/** 字幕日志标签。 */
internal const val CUE_LOG_TAG = "NovumPlayer"

/** 视频尺寸日志标签（画面比例排查用）。 */
private const val VIDEO_LOG_TAG = "NovumVideo"

/** 进度回写间隔。 */
private const val PROGRESS_REPORT_INTERVAL_MS = 5_000L

/** 中英双语偏好的内部值（无 ISO 639 对应，播放侧按轨标题匹配）。 */
private const val DUAL_SUBTITLE_LANGUAGE = "zhdual"
