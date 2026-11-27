package com.novum.tv.ui.screen.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.core.state.ExitConfirmGate
import com.novum.tv.core.state.UiState
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.ui.component.LocalNovumToast
import com.novum.tv.ui.component.NovumBrowseScaffold
import com.novum.tv.ui.component.NovumNavTab
import com.novum.tv.ui.component.NovumTopBar
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 首页：应用第一屏。
 *
 * 首页由顶栏、Hero、继续观看行和各媒体库内容行组成，内容置于全屏背景之上。
 * 顶栏随列表滚动；内容与关键控件使用 [NovumDimens.SafeHorizontal] 对齐。
 *
 * 交互约定：
 * - 进入页面首焦落在 Hero 播放 CTA；无 Hero 时回落到继续观看卡或首个分库卡。
 * - 顶栏随列表滚动；从内容区按上键可自然回到顶栏。
 * - 从详情或播放页返回时，静默刷新继续观看和收藏状态。
 * - 内容区按 Back 滚回顶部并聚焦 Hero 播放 CTA；焦点已在 CTA（或无 Hero）时触发二次退出。
 * - 卡片点击进入详情；Hero CTA 直接播放电影，剧集进入详情。
 *
 * 同包分区件：[FullScreenBackdrop]（全屏背景，HomeBackdrop.kt）、[HeroSection]（信息块与双 CTA，HomeHero.kt）、
 * [HomeSectionRow]/[HomeLatestSection]/[EntryCard]（内容行族，HomeSections.kt）。
 *
 * @param onOpenDetail 打开条目详情页。
 * @param onPlay 直接起播回调（Hero 播放 CTA，电影条目）。
 * @param onOpenSeriesResume 继续观看行的剧集卡直达集级语境：
 *  参数为剧集 id，导航层带 focusCurrentEpisode=true 进详情页；电影条目仍走
 *  [onOpenDetail]。null 时继续观看行整体回落 [onOpenDetail]（预览/测试用）。
 * @param onNavigateTab 顶栏页签导航回调。
 * @param onOpenLibrary 打开某个媒体库的网格页（「查看全部」），参数为库 id 与名称。
 * @param viewModel 页面 ViewModel。
 * @param modifier 外部修饰符。
 */
@Composable
fun HomeScreen(
    onOpenDetail: (String) -> Unit,
    onPlay: (String) -> Unit,
    onNavigateTab: (NovumNavTab) -> Unit,
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenSeriesResume: ((String) -> Unit)? = null,
    onReauthenticate: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val feedState by viewModel.feed.collectAsStateWithLifecycle()
    val focusEpoch by viewModel.focusEpoch.collectAsStateWithLifecycle()
    val reauthenticating by viewModel.reauthenticating.collectAsStateWithLifecycle()
    val reauthenticationError by viewModel.reauthenticationError.collectAsStateWithLifecycle()
    // Back 二次退出要 finish Activity（组合期取一次，非读资源）
    val context = LocalContext.current

    // 回到首页时向服务器静默确认：在详情页标为已看/
    // 收藏后回到首页，继续观看栏/收藏态要立刻与服务器对齐；不进 Loading 态
    val lifecycleOwner = LocalLifecycleOwner.current
    var isHomeResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    isHomeResumed = true
                    viewModel.refresh()
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> isHomeResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val toastSink = LocalNovumToast.current
    val exitGate = remember { ExitConfirmGate() }
    // 文案在组合期解析，回调里不再读资源
    val exitHint = stringResource(R.string.home_exit_confirm)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 首焦落点由页面提升持有：Back 分层与骨架首焦共用同一条 requester 链
    val firstFocus = remember { FocusRequester() }
    var heroPlayHasFocus by remember { mutableStateOf(false) }

    // 首页 Back 分层：内容区先「回顶 + 焦点回 Hero 播放 CTA」，焦点已在播放
    // CTA（或无 Hero）才走二次退出——误按一次返回不会直接丢会话。
    BackHandler {
        val heroAvailable = when (val state = feedState) {
            is UiState.Success -> state.data.hero.isNotEmpty()
            else -> false
        }
        if (heroPlayHasFocus || !heroAvailable) {
            if (exitGate.onBack(SystemClock.uptimeMillis())) {
                context.findActivity()?.finish()
            } else {
                toastSink.show(exitHint)
            }
        } else {
            scope.launch {
                // 回顶手感：从列表深处 animateScrollToItem 会跨大量
                // 未组合 item，动画漫长且易被中途取消。
                // 改为「两段式」：先瞬时跳到接近顶部（保留一屏距离），再播一段
                // 短动画落位——总时长可控、Hero 行必然重新组合。
                val total = listState.layoutInfo.totalItemsCount
                if (total > BACK_TO_TOP_INSTANT_THRESHOLD) {
                    listState.scrollToItem(BACK_TO_TOP_INSTANT_TARGET)
                }
                runCatchingSuspend { listState.animateScrollToItem(0) }
                repeat(FOCUS_REQUEST_FRAMES) {
                    if (runCatching { firstFocus.requestFocus() }.getOrDefault(false)) return@launch
                    withFrameNanos { }
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(NovumPalette.Ink)) {
        when (val state = feedState) {
            is UiState.Success -> HomeContent(
                feed = state.data,
                focusEpoch = focusEpoch,
                onOpenDetail = onOpenDetail,
                onOpenSeriesResume = onOpenSeriesResume,
                onPlay = onPlay,
                onOpenLibrary = onOpenLibrary,
                onNavigateTab = onNavigateTab,
                firstFocus = firstFocus,
                isPageVisible = isHomeResumed,
                onHeroPlayFocusChanged = { heroPlayHasFocus = it },
                listState = listState,
            )

            // 首屏用骨架屏而不是「正在加载…」：结构先到位，图片到达时不跳版
            UiState.Loading -> HomeSkeleton()

            else -> HomeStateView(
                state = state,
                onRetry = viewModel::load,
                onReauthenticate = { viewModel.reauthenticate(onReauthenticate) },
                reauthenticating = reauthenticating,
                reauthenticationError = reauthenticationError,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * 首页主体内容。
 *
 * 公开以便 :app 的单元测试直接注入假数据渲染，绕开 ViewModel 与 Hilt
 * 业务代码不应直接调用它，页面入口是 [HomeScreen]。
 *
 * 使用 [NovumBrowseScaffold] 处理首焦、行下标和顶部留白。
 *
 * 顶栏是列表 item 0；列表透明并叠放在全屏 [FullScreenBackdrop] 上。
 *
 * @param feed 聚合数据。
 * @param focusEpoch 首焦重锚版本号：load 成功后递增，驱动骨架重试首焦。
 * @param onOpenDetail 打开详情页（卡行/继续观看/详情 CTA 统一走这里）。
 * @param onOpenSeriesResume 继续观看行剧集卡直达集级语境（[HomeScreen] 同名参数）。
 * @param onPlay 直接起播（Hero 播放 CTA，电影条目）。
 * @param onOpenLibrary 打开媒体库网格（「查看全部」）。
 * @param onNavigateTab 顶栏页签导航回调。
 * @param firstFocus 首焦/回锚焦点索取器（由 [HomeScreen] 持有传入）：骨架首焦
 *  Hero 播放 CTA（有 Hero 时）、继续观看/首个分库行首卡（无 Hero 兜底）共用。
 * @param onHeroPlayFocusChanged Hero 播放 CTA 焦点变化回调（Back 分层状态）。
 * @param listState 外部注入的列表状态；首页 Back 处理需要将列表滚回顶部
 *  由 [HomeScreen] 持有并传入，单测直接渲染时用默认值。
 * @param isPageVisible 页面处于前台时允许 Hero 空闲轮播。
 */
@Composable
fun HomeContent(
    feed: HomeFeed,
    focusEpoch: Int = 0,
    onOpenDetail: (String) -> Unit,
    onOpenSeriesResume: ((String) -> Unit)? = null,
    onPlay: (String) -> Unit,
    onOpenLibrary: (libraryId: String, libraryName: String) -> Unit,
    onNavigateTab: (NovumNavTab) -> Unit = {},
    firstFocus: FocusRequester = remember { FocusRequester() },
    onHeroPlayFocusChanged: ((Boolean) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    heroRotateIdleMs: Long = HERO_ROTATE_IDLE_MS,
    isPageVisible: Boolean = true,
) {
    val context = LocalContext.current

    // 预取后续 Hero 候选背景图；预取与显示共用解码尺寸以命中内存缓存。
    val density = LocalDensity.current
    // 预取尺寸取窗口实际像素：Configuration.screenWidthDp 在分屏/折叠态会失真（lint 亦禁）
    val windowSize = LocalWindowInfo.current.containerSize
    val heroPixelSize = heroBackdropPixelSize(windowSize, density)
    LaunchedEffect(feed.hero.map { it.id }) {
        val loader = SingletonImageLoader.get(context)
        feed.hero.drop(1).take(HERO_PRELOAD_COUNT).forEach { entry ->
            val url = entry.backdropUrl ?: return@forEach
            runCatching {
                loader.enqueue(
                    ImageRequest.Builder(context)
                        .data(url)
                        .size(heroPixelSize.width, heroPixelSize.height)
                        .build(),
                )
            }
        }
    }

    // 当前展示作品同时驱动背景和 Hero 信息块。
    // 状态不随 feed 实例重建：ON_RESUME 静默刷新返回新 HomeFeed 时，
    // 用户已轮播到的作品必须保留，否则返回首页会突然跳回第一张。
    var displayEntry by remember { mutableStateOf(feed.hero.firstOrNull()) }
    var heroRotateIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(feed) {
        val heroes = feed.hero
        if (heroes.isEmpty()) {
            displayEntry = null
            heroRotateIndex = 0
            return@LaunchedEffect
        }
        // 优先按 id 找回当前作品；找不到（该作品下线）才回落到当前轮播下标。
        // 找回后必须把**轮播下标**对齐到它的新位置：静默刷新可能插入/重排作品，
        // 下标留在旧位置会让下一次自动轮播跳到无关作品、或原地重复同一部。
        val matchedIndex = displayEntry
            ?.let { current -> heroes.indexOfFirst { it.id == current.id } }
            ?: -1
        heroRotateIndex = if (matchedIndex >= 0) {
            matchedIndex
        } else {
            heroRotateIndex.coerceIn(0, heroes.lastIndex)
        }
        displayEntry = heroes[heroRotateIndex]
    }

    // 用户操作信号：任何按键 +1（轮播计时重置；DPAD 导航本身就是按键事件）
    var interactionTick by remember { mutableIntStateOf(0) }

    // 页面可见且无遥控操作时轮播；CTA 焦点不参与计时，离开首页则由生命周期暂停。
    // 任意按键都会重启静默计时，避免用户刚操作就切换海报。
    LaunchedEffect(feed.hero, interactionTick, isPageVisible) {
        if (!isPageVisible || feed.hero.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(heroRotateIdleMs)
            heroRotateIndex = (heroRotateIndex + 1) % feed.hero.size
            displayEntry = feed.hero[heroRotateIndex]
        }
    }

    // 背景视差、变暗和溶解由 Hero 行位移驱动。
    val backdropDensity = LocalDensity.current
    val backdropDriftPx = remember(backdropDensity) { with(backdropDensity) { BACKDROP_DRIFT_DP.toPx() } }
    // 与列表滚动位置一起恢复几何；详情返回时列表可能已经滚到 Hero 中段，不能等到绝对顶部才显示背景。
    val heroRestTopPx = rememberSaveable(backdropDensity.density) { mutableFloatStateOf(Float.NaN) }
    val heroRestBottomPx = rememberSaveable(backdropDensity.density) { mutableFloatStateOf(Float.NaN) }
    val defaultHeroTop = with(backdropDensity) {
        (NovumDimens.SafeVertical + NovumDimens.TopBarHeight + NovumDimens.RowGap).toPx()
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val hero = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == HERO_ROW_KEY }
            hero?.let {
                recoverHeroRestBounds(
                    heroOffset = it.offset,
                    heroHeight = it.size,
                    firstVisibleIndex = listState.firstVisibleItemIndex,
                    firstVisibleScrollOffset = listState.firstVisibleItemScrollOffset,
                    savedRestTop = heroRestTopPx.floatValue,
                    defaultRestTop = defaultHeroTop,
                )
            }
        }.collect { hero ->
            if (hero != null) {
                heroRestTopPx.floatValue = hero.first
                heroRestBottomPx.floatValue = hero.second
            }
        }
    }
    // 派生背景联动进度，绘制层和可见性判断共用该状态。
    // 视差死区：16dp——横向移焦点的纵向微抖远小于此值
    // 而正常纵向滚动一行的行程是它的 10 倍以上，软斜坡平移不损观感
    val backdropDeadZonePx = remember(backdropDensity) {
        with(backdropDensity) { BACKDROP_DEAD_ZONE_DP.toPx() }
    }
    val backdropProgressState = remember(listState, backdropDeadZonePx) {
        derivedStateOf {
            backdropProgress(
                listState = listState,
                heroRestTopPx = heroRestTopPx.floatValue,
                heroRestBottomPx = heroRestBottomPx.floatValue,
                backdropDeadZonePx = backdropDeadZonePx,
            )
        }
    }
    val backdropGone by remember(backdropProgressState) {
        derivedStateOf {
            backdropProgressState.value.let { (f, g) -> f >= 1f && g >= 1f }
        }
    }

    // 首焦落点：Hero 播放按钮 → 继续观看第一卡 → 第一个分类行首卡；
    // 静默刷新不递增 focusEpoch，避免网络返回时抢走用户焦点。
    val fallbackToResume = feed.hero.isEmpty()
    val fallbackToLatest = feed.hero.isEmpty() && feed.resume == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) interactionTick++
                false // 不消费：只做操作侦测
            },
    ) {
        // ---- 背景层：Hero 存在即常驻，自管理视差/变暗/溶解；滚深处整组
        //      alpha=0 时停止渲染（回顶自动恢复）----
        if (!backdropGone && displayEntry != null) {
            FullScreenBackdrop(
                entry = displayEntry,
                progress = backdropProgressState,
                driftPx = backdropDriftPx,
            )
        }

        NovumBrowseScaffold(
            listState = listState,
            // 顶栏是列表 item 0（随页滚动），列表本身透明叠在全屏背景上
            topInset = NovumDimens.SafeVertical,
            firstFocus = firstFocus,
            focusEpoch = focusEpoch,
            enter = true,
            backgroundColor = Color.Transparent,
        ) {
            // 顶栏行（item 0）：随列表滚动；背后即全屏背景（顶部遮罩兜可读性）。
            // 顶栏只留右侧图标行，ClearLogo 下移进 Hero 信息块
            row(key = "nav") {
                val topBarScope = rememberCoroutineScope()
                Box(
                    modifier = Modifier
                        .padding(horizontal = NovumDimens.SafeHorizontal)
                        // 焦点进顶栏即整页回顶：bring-into-view 只满足
                        // 边距就停——顶栏贴着视口顶但列表没到 0，安全区露不出
                        // 来。聚焦顶栏表示明确的
                        // 「回最上方」意图，直接整页滚回列表原点
                        .onFocusChanged { state ->
                            if (state.hasFocus) {
                                topBarScope.launch {
                                    runCatchingSuspend { listState.animateScrollToItem(0) }
                                }
                            }
                        },
                ) {
                    // ClearLogo 显示在 Hero 信息块顶部；顶栏仅保留右侧图标行。
                    NovumTopBar(
                        current = NovumNavTab.Home,
                        onNavigateTab = onNavigateTab,
                    )
                }
            }
            if (feed.hero.isNotEmpty()) {
                row(key = HERO_ROW_KEY) {
                    // Hero 是首屏内容；顶部留白由 [HERO_ZONE_TOP_OFFSET] 控制。
                    Box(
                        modifier = Modifier
                            .padding(top = HERO_ZONE_TOP_OFFSET),
                    ) {
                        HeroSection(
                            entry = displayEntry,
                            onPlay = onPlay,
                            onDetail = onOpenDetail,
                            firstFocus = firstFocus,
                            onPlayFocused = onHeroPlayFocusChanged,
                        )
                    }
                }
            }
            feed.resume?.let { resumeRow ->
                row(key = resumeRow.id) {
                    HomeSectionRow(
                        row = resumeRow,
                        showProgress = true,
                        showRating = false,
                        firstFocus = firstFocus.takeIf { fallbackToResume },
                        // 继续观看的意图是「回到那一集」：剧集卡直达集级语境
                        // 电影条目照旧走普通详情
                        onEntryClick = { entry ->
                            val seriesId = entry.seriesId
                            if (seriesId != null && onOpenSeriesResume != null) {
                                onOpenSeriesResume(seriesId)
                            } else {
                                onOpenDetail(seriesId ?: entry.id)
                            }
                        },
                    )
                }
            }
            rows(
                count = feed.latest.size,
                key = { index -> "latest_${feed.latest[index].libraryId}" },
                contentType = { "library-row" },
            ) { _, index ->
                HomeLatestSection(
                    row = feed.latest[index],
                    onOpenDetail = onOpenDetail,
                    onOpenLibrary = onOpenLibrary,
                    firstFocus = firstFocus.takeIf { fallbackToLatest && index == 0 },
                )
            }
        }
    }
}

/**
 * Hero 候选预加载张数（跳过首张，它本来就可见）。
 * 沉浸列下背景图随焦点逐张消费：预取聚焦卡的相邻几张，左右扫卡零等待。
 */
private const val HERO_PRELOAD_COUNT = 4

/**
 * Back 回锚的索焦重试帧数：滚回顶部后 Hero 行需要一两帧才重新组合，
 * requester 尚无节点时索焦会抛错——按帧重试兜住组合时序。
 */
private const val FOCUS_REQUEST_FRAMES = 3

/**
 * Hero 自动轮播：首页处于前台且无操作 [HERO_ROTATE_IDLE_MS] 后推进下一部作品。
 * 任意按键重置计时，焦点位置不限制轮播；离开首页暂停，返回后重新计时。
 */
private const val HERO_ROTATE_IDLE_MS = 6_000L

/** 回顶时超过该行数就先瞬时跳近（避免长距离动画），再短动画落位。 */
private const val BACK_TO_TOP_INSTANT_THRESHOLD = 4

/** 瞬时跳到的目标行（留 1 行给动画）。 */
private const val BACK_TO_TOP_INSTANT_TARGET = 1

/** Hero 区相对顶栏的顶部留白。 */
private val HERO_ZONE_TOP_OFFSET = 24.dp

/** 设计预览：1280×720dp 对应1920×1080 @240dpi（tv 三米观看基准）。 */
@Preview(name = "TV 1080p", widthDp = 1280, heightDp = 720)
@Composable
private fun HomeScreenPreview() {
    val entry = HomeEntry(
        id = "1",
        title = "顺流逆流",
        subtitle = "2000 · 电影",
        imageUrl = null,
        backdropUrl = null,
        overview = "两名守卫护送要人穿越城市，各怀心思却不得不并肩作战。",
        rating = 7.8f,
        year = 2000,
        type = "Movie",
    )
    val repeatEntry = { index: Int ->
        entry.copy(id = "$index", title = "条目 $index")
    }
    val feed = HomeFeed(
        hero = List(6) { repeatEntry(it) },
        resume = HomeRow(
            id = HOME_ROW_RESUME,
            items = List(6) { repeatEntry(10 + it).copy(progress = 0.35f + it * 0.1f) },
        ),
        nextUp = null,
        latest = listOf(
            HomeLatestRow(libraryId = "l1", libraryName = "华语电影", items = List(8) { repeatEntry(30 + it) }),
        ),
    )
    NovumTheme {
        // 预览按首页真实结构：内容（含顶栏行）全部在列表流里
        HomeContent(
            feed = feed,
            onOpenDetail = {},
            onPlay = {},
            onOpenLibrary = { _, _ -> },
            onNavigateTab = {},
        )
    }
}

/**
 * 从 Compose 的 Context 里取出宿主 Activity（ContextWrapper 可能套了多层）。
 *
 * 用 findByActivity 而不是强转：Compose 拿到的 Context 通常是 ContextThemeWrapper，
 * 直接 `as Activity` 会抛 ClassCastException。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
