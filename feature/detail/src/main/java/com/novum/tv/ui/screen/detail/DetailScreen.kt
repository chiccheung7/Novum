package com.novum.tv.ui.screen.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.MediaDetail
import com.novum.tv.ui.component.NovumBrowseScaffold
import com.novum.tv.ui.component.NovumImage
import com.novum.tv.ui.component.NovumMediaInfoBlock
import com.novum.tv.ui.component.NovumSkeletonDetail
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.component.backdropRequestSize
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 详情页。
 *
 * 分区顺序：首屏信息区（标题→元信息→简介→类型→操作行）→ 选集 → 演职员。
 * 不展示相似推荐区；简介只在首屏显示。
 *
 * 结构：挂共享骨架 [NovumBrowseScaffold]（首焦重试、行下标自动分配）。
 * 操作行包含播放/从头播放、收藏与已看状态按钮；多季选择器位于选集标题旁。
 * 主 CTA 在焦点序列最前；背景全出血单层渲染 + 双向遮罩。
 *
 * 同包分区件：[ActionRow]（操作行/元信息）、[EpisodeStrip]（选集行/分集卡）、[CastRow]（演职员）。
 * [VersionDialog]/[SeasonMenuDialog]/[SeasonMarkDialog]（浮层，DetailDialogs.kt）。
 *
 * 剧集详情页信息块始终展示剧集内容（标题/元信息/简介）。
 * 有 ClearLogo 时用 logo 替代文字标题；分集卡保留简短简介，按一次 OK 起播；
 * 图标与背景随当前集更新，标题简介保持剧集内容。多版本可用每次询问或长按选择。
 * 已看/未看默认修改当前集；菜单键或长按呼出整季标记。
 *
 * @param onBack 返回主页。
 * @param onPlay 播放回调：参数为要播放的条目 id（剧集时为分集 id）、起播位置
 *   覆盖毫秒（null 走默认续播规则，0 表示从头播放）与本次播放的版本 id
 *  （null = 按优先视频质量策略/服务端默认）。
 * @param onOpenPerson 打开人物作品页（演职员头像跳转用）。
 * @param modifier 外部修饰符。
 * @param viewModel 详情页 ViewModel，路由参数由 SavedStateHandle 提供。
 */
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onPlay: (itemId: String, startPositionMs: Long?, mediaSourceId: String?) -> Unit,
    onOpenPerson: (personId: String, personName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 详情页返回直接交给导航层回主页，不先清空当前集选择。
    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NovumPalette.Ink),
    ) {
        when (val detailState = state.detail) {
            UiState.Loading -> NovumSkeletonDetail()

            is UiState.Error -> NovumStateView(
                state = detailState,
                onRetry = viewModel::load,
                modifier = Modifier.align(Alignment.Center),
            )

            else -> {
                val data = state.data
                if (data != null) {
                    DetailBody(
                        data = data,
                        state = state,
                        onPlay = onPlay,
                        onSelectEpisode = viewModel::selectEpisode,
                        onOpenPerson = onOpenPerson,
                        onToggleFavorite = viewModel::toggleFavorite,
                        onConsumeFavoriteFailure = viewModel::consumeFavoriteFailure,
                        onTogglePlayed = viewModel::togglePlayed,
                        onConsumePlayedFailure = viewModel::consumePlayedFailure,
                        onMarkSeasonPlayed = viewModel::markSeasonPlayed,
                        onSelectSeason = viewModel::selectSeason,
                        onRetryEpisodes = viewModel::retryEpisodes,
                        onRememberVersion = viewModel::rememberVersion,
                    )
                }
            }
        }
    }
}

/**
 * 详情主内容区（挂 [NovumBrowseScaffold]）。
 *
 * 首屏信息区与首页 Hero 使用相同的信息布局：
 * ClearLogo → 大文字标题 → 元信息 → 题材标签 → 格式标志 → 简介 →
 * 主 CTA 操作行（按钮保留详情页设计，不用首页大号双 CTA）。
 * 分区顺序：信息区 → 选集 → 演职员，行间距由骨架
 * spacedBy(RowGap) 统一供给。
 *
 * 信息块始终展示剧集标题、元信息与简介。
 * 有 ClearLogo 时用 logo 替代文字标题；分集标题与简介落到选集卡下方。
 * 焦点卡同步选中集；按一次 OK 即起播，操作行图标与背景随当前集变化，
 * 标题简介不动。背景优先当前集的图，
 * 缺少分集图时回落到剧集图。
 *
 * 焦点路径：播放 → 从头播放 → 收藏 → 标为已看 → 分集行（季选择在行标题处）。
 * 简介与演职员不可聚焦。
 *
 * 电影页操作行以下没有可聚焦内容，上下键用于翻页滚动。
 * 剧集页仍交给焦点系统去选集。翻页滚动是页面知识，留在本层。
 */
@Composable
private fun DetailBody(
    data: MediaDetail,
    state: DetailUiState,
    onPlay: (itemId: String, startPositionMs: Long?, mediaSourceId: String?) -> Unit,
    onSelectEpisode: (String) -> Unit,
    onOpenPerson: (personId: String, personName: String) -> Unit,
    onToggleFavorite: () -> Unit,
    onConsumeFavoriteFailure: () -> Unit,
    /** 参数 = 单集 id（剧集标记当前集）；null = 整个条目（电影）。 */
    onTogglePlayed: (String?) -> Unit,
    onConsumePlayedFailure: () -> Unit,
    onMarkSeasonPlayed: (Boolean) -> Unit,
    onSelectSeason: (String) -> Unit,
    onRetryEpisodes: () -> Unit,
    onRememberVersion: (String) -> Unit,
) {
    // 首焦落播放按钮；抓焦交给骨架内的 [NovumInitialFocus]，等待焦点目标挂载。
    val playFocus = remember { FocusRequester() }

    // 当前集：手动选中的集优先，否则使用默认播放目标集。
    // 操作行图标、版本候选与页面背景都跟着它；电影为 null
    val currentEpisode = state.currentEpisode
    // 当前选中卡的焦点索取器。
    val contextEpisodeFocus = remember { FocusRequester() }

    // 从继续观看入口直达当前集时，将首焦交给播放按钮；等待一帧让按钮进入焦点树。
    // 用户在页面内选集时 fromEntry=false，焦点保持在选集卡上。
    LaunchedEffect(state.episodeContextId, state.episodeContextFromEntry) {
        if (state.episodeContextId != null && state.episodeContextFromEntry) {
            withFrameNanos { }
            runCatching { playFocus.requestFocus() }
        }
    }

    // 选季后将焦点落到新季第一未看集。
    // 只在**用户主动切季**时武装，首次加载的分集不能抢走播放按钮的首焦
    var focusEpisodeAfterSeasonSwitch by remember { mutableStateOf(false) }
    val seasonSwitched = { seasonId: String ->
        focusEpisodeAfterSeasonSwitch = true
        onSelectSeason(seasonId)
    }
    val episodeFocus = remember { FocusRequester() }
    // 选季浮层状态。
    var seasonMenuOpen by remember { mutableStateOf(false) }
    val selectedSeason = state.seasons.firstOrNull { it.id == state.selectedSeasonId }
    // 整季标记菜单由已看/未看图标的菜单键或长按 OK 呼出。长按进入时吞掉松手事件，
    // 避免直接触发首行「标记整季为已看」。
    var seasonMarkMenuOpen by remember { mutableStateOf(false) }
    var seasonMarkByLongPress by remember { mutableStateOf(false) }

    // 版本选择浮层：优先视频质量为「每次询问」时点击播放先弹出；长按播放或从头播放可手动选版本。
    // 携带待起播位置（null 续播规则 / 0 从头），选中即按该版本起播。
    // 长按进入时吞掉松手事件，避免直接选择首项。
    var versionPickAtMs by remember { mutableStateOf<Long?>(null) }
    var versionPickOpen by remember { mutableStateOf(false) }
    var versionPickByLongPress by remember { mutableStateOf(false) }
    var versionPickTargetId by remember { mutableStateOf(data.id) }
    var versionPickOptions by remember { mutableStateOf<List<MediaVersionOption>>(emptyList()) }
    var versionPickTitlePrefix by remember { mutableStateOf(data.title) }
    val openVersionPick: (Episode?, Boolean, Long?) -> Unit = { episode, byLongPress, startMs ->
        versionPickAtMs = startMs
        versionPickByLongPress = byLongPress
        versionPickTargetId = episode?.id ?: data.id
        versionPickOptions = episode?.versions ?: data.versions
        versionPickTitlePrefix = episode?.let { ep ->
            "${data.title} · S%02dE%02d".format(ep.seasonIndex, ep.index)
        } ?: data.title
        versionPickOpen = true
    }
    // 播放点击：多版本 +「每次询问」→ 先弹版本层；否则按设置档/服务端默认直接播。
    // 目标集显式传入，分集卡一次确认即可起播，且不会在焦点状态异步更新时误播旧集。
    val requestPlay: (Episode?, Long?, Boolean) -> Unit = { episode, startMs, byLongPress ->
        val targetId = episode?.id ?: data.id
        val candidates = episode?.versions ?: data.versions
        if (
            candidates.size > 1 &&
            (byLongPress || state.versionStrategy.equals(ASK_STRATEGY, ignoreCase = true))
        ) {
            openVersionPick(episode, byLongPress, startMs)
        } else {
            onPlay(targetId, startMs, null)
        }
    }
    val actionRequestPlay: (Long?) -> Unit = { startMs -> requestPlay(currentEpisode, startMs, false) }
    // 长按时为多版本播放提供一次性选择入口。
    val longPressPick: ((Long?) -> Unit)? = if ((currentEpisode?.versions ?: data.versions).size > 1) {
        { startMs -> requestPlay(currentEpisode, startMs, true) }
    } else {
        null
    }

    val listState = rememberLazyListState()
    // 电影页翻页前的焦点探测
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    // 分集双列行：切季后焦点要落到第一未看集（无则第一集）
    val firstUnplayedId = remember(state.episodes) {
        (state.episodes.firstOrNull { !it.played } ?: state.episodes.firstOrNull())?.id
    }
    val scrollStepPx = with(LocalDensity.current) { DETAIL_SCROLL_STEP.roundToPx() }
    // 翻页配速摘除（全站无配速统一），repeat 逐发放行即时位移
    // 「简介/演职员」本身不可聚焦。方向键语义（焦点优先 + 死路兜底）：
    // 先问焦点系统能否移动，能移就交给它（不消费）——电影页下方无焦点目标
    // 上下键实际走翻页滚动；剧集页行间移动由焦点系统完成。
    // 探测失败 ≠ 死路——焦点在页面下方时，上方行（操作行/分集行）可能
    // 已被 LazyColumn 释放，此时 moveFocus 与系统兜底是同一套几何搜索、双双失败
    // = 按键无反应。改为落入翻页滚动，把目标行重新组合出来，下一按探测即可命中。

    // 选季完成（新分集到达）后把焦点交给第一未看集
    LaunchedEffect(focusEpisodeAfterSeasonSwitch, state.episodes) {
        if (focusEpisodeAfterSeasonSwitch && state.episodes.isNotEmpty()) {
            focusEpisodeAfterSeasonSwitch = false
            runCatching { episodeFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onPreviewKeyEvent false
                }
                when (event.key) {
                    // 焦点优先：先 moveFocus 探测；成功则消费（防双动作）。
                    // 失败时统一翻页滚动兜底。
                    Key.DirectionDown -> {
                        // moveFocus 返回 Boolean、不抛——去掉无效 runCatching
                        val moved = focusManager.moveFocus(FocusDirection.Down)
                        if (moved) return@onPreviewKeyEvent true
                        if (!listState.canScrollForward) return@onPreviewKeyEvent false
                        scope.launch { listState.scrollBy(scrollStepPx.toFloat()) }
                        true
                    }

                    Key.DirectionUp -> {
                        val moved = focusManager.moveFocus(FocusDirection.Up)
                        if (moved) return@onPreviewKeyEvent true
                        if (!listState.canScrollBackward) return@onPreviewKeyEvent false
                        scope.launch { listState.scrollBy(-scrollStepPx.toFloat()) }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // 背景：全出血一层渲染 + 双向遮罩——左侧压暗保证信息区可读
        // 纵向渐变让滚动内容渐进融进页面底色。
        // 背景恒剧集级（选中分集时的背景也统一剧集图——
        // 分集背景/截图不再上背景层；横版主视觉缺失回落剧集海报）
        val backdrop = data.backdropUrl ?: data.posterUrl
        if (backdrop != null) {
            NovumImage(
                url = backdrop,
                contentDescription = data.title,
                // 与首页 Hero 预取共用同一解码尺寸 → 缓存 key 一致，进详情直接
                // 命中首页已缓存的图且尺寸稳定，避免翻页重组反复解码大图。
                requestSize = backdropRequestSize(
                    windowSize = LocalWindowInfo.current.containerSize,
                    density = LocalDensity.current,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(NovumPalette.Ink))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to NovumPalette.Ink.copy(alpha = 0.55f),
                        0.42f to NovumPalette.Ink.copy(alpha = 0.35f),
                        0.72f to NovumPalette.Ink.copy(alpha = 0.2f),
                        1f to NovumPalette.Ink.copy(alpha = 0.08f),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to NovumPalette.Ink.copy(alpha = 0.32f),
                        0.45f to NovumPalette.Ink.copy(alpha = 0.45f),
                        1f to NovumPalette.Ink.copy(alpha = 0.92f),
                    ),
                ),
        )

        NovumBrowseScaffold(
            listState = listState,
            // 全出血背景档：顶部留白 0（背景从 y=0 起），留白由信息区自管
            firstFocus = playFocus,
            focusEpoch = data.id,
            backgroundColor = Color.Transparent,
        ) {
            // 首屏信息区与首页 Hero 共用 ClearLogo 和信息块，详情操作按钮保持独立布局。
            row(key = "info") {
                Column(
                    modifier = Modifier
                        .fillParentMaxWidth()
                        .padding(
                            start = NovumDimens.SafeHorizontal,
                            end = NovumDimens.SafeHorizontal,
                            // 顶部 54dp（对照主流：详情页标题区起始
                            // ≈10% 视口高——原 SafeVertical 27dp 仅 3.75%，明显偏顶）
                            top = DETAIL_INFO_TOP_INSET,
                            bottom = NovumDimens.SafeVertical,
                        ),
                ) {
                    // 信息块本体 = designsystem 共享 NovumMediaInfoBlock（首页
                    // Hero 同款）：**ClearLogo（有则替代文字标题）** → 元信息
                    // （灰字圆点）→ 题材标签 → 格式标志 → 简介。
                    // **恒为剧集内容**——分集标题/简介落到选集卡下方，本块不随
                    // 当前集切换；logo 与首页同排版（由块内渲染，不再页面自绘）
                    NovumMediaInfoBlock(
                        title = data.title,
                        logoUrl = data.logoUrl,
                        titleMaxLines = 2,
                        metaParts = detailMetaParts(
                            data = data,
                            seasonCount = if (data.isSeries) state.seasons.size else 0,
                            watchedEpisodeCount = state.watchedEpisodeCount,
                            totalEpisodeCount = state.totalEpisodeCount,
                        ),
                        genreTags = data.genres.take(GENRE_TAGS_COUNT),
                        // 动态范围（杜比视界/HDR10/…）+ 音频格式徽章（音轨编码）。
                        // 视频编码仍不上屏（信息噪音），但用户明确要求看到音轨格式。
                        formatBadges = buildList {
                            addAll(data.videoFormats)
                            data.audioProfile?.let { add(it) }
                            data.audioFormat?.let(::audioBadgeLabel)?.let { add(it) }
                        },
                        overview = data.overview,
                    )
                    Spacer(modifier = Modifier.height(26.dp))
                    Box {
                        // 单一操作行：播放/已看目标 = 当前集
                        // （选中集或默认目标集），电影 = 条目自身
                        ActionRow(
                            data = data,
                            episode = currentEpisode,
                            playFocus = playFocus,
                            onPlay = actionRequestPlay,
                            // 长按时选择本次播放版本；从头播放以 0 为起播位置。
                            onPlayLongClick = longPressPick?.let { open -> { open(null) } },
                            onReplayLongClick = longPressPick?.let { open -> { open(0L) } },
                            onToggleFavorite = onToggleFavorite,
                            onTogglePlayed = { onTogglePlayed(currentEpisode?.id) },
                            onMarkSeasonMenuOpen = { byLongPress ->
                                seasonMarkByLongPress = byLongPress
                                seasonMarkMenuOpen = true
                            },
                        )
                    }

                    // 收藏 / 已看失败：页内联提示（范式：错误不用 Toast），3 秒后自动消失
                    val inlineFailure = when {
                        state.favoriteFailed -> stringResource(R.string.detail_favorite_failed)
                        state.playedFailed -> stringResource(R.string.detail_played_failed)
                        else -> null
                    }
                    if (inlineFailure != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = inlineFailure,
                            style = NovumType.Caption,
                            color = NovumPalette.Danger,
                        )
                        LaunchedEffect(state.favoriteFailed, state.playedFailed) {
                            delay(FAVORITE_FAILED_NOTICE_MS)
                            onConsumeFavoriteFailure()
                            onConsumePlayedFailure()
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // 选集（剧集专用）。集简介只在播放页抽屉展示，详情信息区不再重复。
            if (data.isSeries) {
                row(key = "episodes") {
                    EpisodeStrip(
                        episodes = state.episodes,
                        episodesEmpty = state.episodes.isEmpty() && !state.episodesFailed,
                        episodesFailed = state.episodesFailed,
                        firstUnplayedId = firstUnplayedId,
                        // 锚点：NextUp 当前集优先（服务端确定的「正在看」）；
                        // 无 NextUp 提示时才回落到首个有进度的集。
                        // 反过来的话，早期留下的一点进度会把行首抢到旧集。
                        anchorEpisodeId = state.currentEpisodeId
                            ?: state.episodes.firstOrNull { it.progressRatio != null }?.id,
                        // 已选集或默认播放集显当前描边，焦点浏览时同步预览信息。
                        selectedEpisodeId = currentEpisode?.id,
                        // 剧集 clearlogo 挂每张卡图左上角（同剧共用一张）
                        logoUrl = data.logoUrl,
                        episodeFocus = episodeFocus,
                        contextFocus = contextEpisodeFocus,
                        // 焦点落到卡上即刷新该集信息；OK 一次直接起播卡片对应集。
                        onFocusSelect = onSelectEpisode,
                        selectedSeason = selectedSeason.takeIf { state.seasons.size > 1 },
                        onOpenSeasonMenu = { seasonMenuOpen = true },
                        onCardClick = { episodeId ->
                            state.episodes.firstOrNull { it.id == episodeId }?.let { episode ->
                                requestPlay(episode, null, false)
                            }
                        },
                        onRetry = onRetryEpisodes,
                        upFocus = playFocus,
                    )
                }
            }

            // 演职员：当前季优先（分季换角/客串），季级无数据回落剧集级；电影恒条目级。
            val cast = state.seasonCast?.takeIf { it.isNotEmpty() } ?: data.cast
            if (cast.isNotEmpty()) {
                row(key = "cast") {
                    CastRow(cast = cast, onOpenPerson = onOpenPerson)
                }
            }
        }

        // 选季浮层（从操作行的选季按钮呼出）
        if (seasonMenuOpen && state.seasons.isNotEmpty()) {
            SeasonMenuDialog(
                seasons = state.seasons,
                selectedSeasonId = state.selectedSeasonId,
                onSelect = { seasonId ->
                    seasonMenuOpen = false
                    seasonSwitched(seasonId)
                },
                onDismiss = { seasonMenuOpen = false },
            )
        }

        // 整季标记浮层由已看/未看图标的菜单键或长按 OK 呼出。
        if (seasonMarkMenuOpen && state.episodes.isNotEmpty()) {
            SeasonMarkDialog(
                onMarkWatched = {
                    seasonMarkMenuOpen = false
                    onMarkSeasonPlayed(true)
                },
                onMarkUnwatched = {
                    seasonMarkMenuOpen = false
                    onMarkSeasonPlayed(false)
                },
                onDismiss = { seasonMarkMenuOpen = false },
                // 长按进入：吞掉「松手」那一下 OK（否则直接标记整季）
                awaitKeyRelease = seasonMarkByLongPress,
            )
        }

        // 版本选择浮层由「每次询问」策略或长按播放/从头播放呼出。
        // 选中后按该版本起播，并写入条目级记忆：下次直接播放会沿用该版本。
        if (versionPickOpen && versionPickOptions.size > 1) {
            VersionDialog(
                versions = versionPickOptions,
                titlePrefix = versionPickTitlePrefix,
                // 高亮上一次记忆的版本；没有记忆时 VersionDialog 回落首项
                selectedId = state.rememberedMediaSourceId,
                onSelect = { sourceId ->
                    versionPickOpen = false
                    onRememberVersion(sourceId)
                    onPlay(versionPickTargetId, versionPickAtMs, sourceId)
                },
                onDismiss = { versionPickOpen = false },
                // 长按进入：吞掉「松手」那一下 OK（直接选中播放）
                awaitKeyRelease = versionPickByLongPress,
            )
        }
    }
}

/** 题材标签数：与首页 Hero 同款取前 2。 */
private const val GENRE_TAGS_COUNT = 2

/** 收藏失败内联提示的展示时长。 */
private const val FAVORITE_FAILED_NOTICE_MS = 3_000L

/** 「优先视频质量」策略值：每次询问（多版本时播放前弹版本选择层）。 */
private const val ASK_STRATEGY = "ask"

/** 详情页翻页步长：约半屏，保证每按一次都能看到新的整段内容。 */
private val DETAIL_SCROLL_STEP = 460.dp


/** 详情信息区顶部留白（主流详情页标题区起始 ≈10% 视口高的保守档）。 */
private val DETAIL_INFO_TOP_INSET = 54.dp

/**
 * 音轨编码徽章展示名：把 Emby 的裸 Codec 值规范成易读标签。
 * 未知编码原样大写返回，避免吞掉信息。
 */
private fun audioBadgeLabel(codec: String): String = when (codec.trim().uppercase()) {
    "TRUEHD" -> "TrueHD"
    "EAC3", "EC-3" -> "E-AC3"
    "AC3" -> "AC-3"
    "DTS" -> "DTS"
    "DTS-HD", "DTSHD" -> "DTS-HD"
    "DTSX" -> "DTS:X"
    "FLAC" -> "FLAC"
    "AAC" -> "AAC"
    "MP3" -> "MP3"
    "OPUS" -> "Opus"
    else -> codec.trim().uppercase()
}
