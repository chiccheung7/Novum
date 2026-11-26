package com.novum.tv.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.error.NovumError
import com.novum.tv.model.Chapter
import com.novum.tv.model.DanmuArea
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuSize
import com.novum.tv.model.DanmuSpeed
import com.novum.tv.model.Episode
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.ui.common.userMessage
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumOptionRow
import com.novum.tv.ui.component.NovumSwitchRow
import com.novum.tv.ui.component.NovumTextField
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 播放设置抽屉的分区。 */
internal enum class DrawerTab(
    /** tab 文案资源。 */
    val labelRes: Int,
) {
    /** 选集（抽屉内直接换集，不必退回详情页）。 */
    Episodes(R.string.detail_episodes),

    /** 当前集简介（独立一页，不与选集挤在一起）。 */
    Overview(R.string.player_drawer_overview),

    Subtitle(R.string.player_drawer_subtitle),

    /** 弹幕（UHDNOW 原生能力；开关/渲染参数/匹配与手动搜索）。 */
    Danmaku(R.string.player_drawer_danmaku),

    Audio(R.string.player_drawer_audio),
    Quality(R.string.player_drawer_quality),
    Chapter(R.string.player_info_chapters),
}

/**
 * 一条可切换的轨道选项。
 *
 * @param id Media3 `TrackGroup.id` 与轨道索引拼成的稳定标识。
 * @param label 展示名（音轨语言/声道，字幕语言/名称）。
 * @param selected 是否为当前启用项。
 * @param language ISO 语言码（条目记忆定位轨道用；关闭字幕项为 null）。
 */
internal data class TrackOption(
    val id: String,
    val label: String,
    val selected: Boolean,
    val language: String? = null,
    /** 音轨编码展示名（如「AC-3」「TrueHD」）；字幕轨为 null。 */
    val codec: String? = null,
    val formatId: String? = null,
    val supported: Boolean = true,
    /** 容器原始 Format.label（空串 = 容器没给标题）；条目记忆按它定位轨道。 */
    val rawLabel: String = "",
    /** 音轨声道数（>0 时用于记忆定位同语言的多条轨）；未知/字幕为 null。 */
    val channels: Int? = null,
)

/**
 * 播放实时状态（画质 tab「实时播放状态」分区的数据载体）。
 *
 * State 字段由 PlayerStage 的播放器 listener（画面尺寸/丢帧）与 500ms 轮询
 * （显示模式/缓冲/带宽）写入，抽屉画质 tab 读取——读取方只在抽屉打开且画质
 * tab 在场时组合，常态下零重组成本。
 */
internal class PlayerLiveStats {
    /** 当前显示模式（物理分辨率 @ 刷新率；null = 拿不到 Display）。 */
    var displayMode by mutableStateOf<String?>(null)

    /** 解码画面宽（onVideoSizeChanged；0 = 未上报）。 */
    var videoWidth by mutableIntStateOf(0)

    /** 解码画面高。 */
    var videoHeight by mutableIntStateOf(0)

    /** 本场累计丢帧数（onDroppedVideoFrames 累加）。 */
    var droppedFrames by mutableIntStateOf(0)

    /** 缓冲健康：已缓冲百分比（0–100）。 */
    var bufferedPercentage by mutableIntStateOf(0)

    /** 缓冲健康：前方已缓冲秒数。 */
    var bufferedAheadSeconds by mutableLongStateOf(0L)

    /** 音频输出路径文案（实时）：如「PCM 解码」「bitstream 直通」；null = 拿不到。 */
    var audioOutput by mutableStateOf<String?>(null)
}

/**
 * 播放设置抽屉，按 tab 显示不同设置。
 *
 * 从右侧滑入，包含七个 tab（[DrawerTab]）：
 * - **选集**：当前季分集列表，OK 直接换集。
 * - **简介**：当前集（电影为整部）的完整简介，独立成页可滚动。
 * - **字幕**：字幕延迟、字号和字幕轨道列表。
 * - **音轨**：音频直通、延迟和音轨列表。
 * - **画质**：只读播放规格与实时状态。
 * - **章节**：章节列表，OK 跳转并关闭抽屉。
 *
 * 结构：**固定头（标题 + tab） + 独立滚动内容区**。之前标题、tab 与内容共用一个
 * 滚动容器，往下翻几行就切不了 tab；内容区还有一层自己的滚动与滚动条/边渐隐，
 * 只读页（画质/简介）没有可聚焦行时按上下键整页翻。
 *
 * 焦点约定：
 * - 打开时焦点落在当前 tab 芯片上；左右键移动焦点，OK 切换 tab。
 * - 内容行焦点不逃逸到控制条；上下键在行间移动，无可移动目标时滚动内容。
 * - 内容行获焦后滚入可视区；Back 关闭并将焦点还给「播放设置」按钮。
 *
 * @param visible 是否可见。
 * @param initialTab 打开时定位的 tab（控制条字幕/音轨按钮直达对应分区）。
 * @param onTabsFocusChanged tab 行是否持有焦点的回调（父层据此放行/吞掉左右键）。
 * @param audioTracks 音轨列表。
 * @param textTracks 字幕列表（第一项为「关闭字幕」，selected 表示当前关闭状态）。
 * @param passthrough 音频直通当前值。
 * @param plan 播放计划（画质 tab 的规格来源）。
 * @param title 影片/剧集标题（简介 tab 页首；分集标题优先）。
 * @param subtitle 播放方式描述（画质 tab 的规格行）。
 * @param audioLabel 当前音轨。
 * @param subtitleLabel 当前字幕。
 * @param chapters 章节列表。
 * @param episodes 当前季分集列表（选集 tab）。
 * @param currentEpisodeId 当前播放的分集 id（选集 tab 高亮）。
 * @param onSelectEpisode 选集 tab 选中某集（外部换集：替换当前播放页）。
 * @param onSelectAudio 选择音轨。
 * @param onSelectText 选择字幕。
 * @param onTogglePassthrough 切换直通。
 * @param onSelectChapter 跳转章节。
 * @param modifier 外部修饰符。
 * @param liveStats 播放实时状态；null 时不渲染实时状态行。
 * @param currentPosition 当前播放位置（章节 tab 高亮「当前章」用）。
 * @param overview 影片/剧集简介（简介 tab 在分集无简介时兜底；电影用）。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerSettingsDrawer(
    visible: Boolean,
    initialTab: DrawerTab,
    onTabsFocusChanged: (Boolean) -> Unit,
    /** 抽屉容器修饰符（右侧滑入层的对齐锚点等）。 */
    modifier: Modifier = Modifier,
    /** 调节行（延迟/字号/高度）是否持有焦点的回调（父层据此放行左右键给无级调节）。 */
    onAdjustFocusChanged: (Boolean) -> Unit = {},
    audioTracks: List<TrackOption>,
    textTracks: List<TrackOption>,
    passthrough: Boolean,
    plan: PlaybackPlan,
    title: String = "",
    subtitle: String,
    audioLabel: String,
    subtitleLabel: String,
    chapters: List<Chapter>,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectText: (TrackOption) -> Unit,
    onTogglePassthrough: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    /** 播放实时状态（画质 tab「实时播放状态」分区）；null = 未提供，不渲染该分区。 */
    liveStats: PlayerLiveStats? = null,
    /** 当前季分集（选集 tab）；电影为空。 */
    episodes: List<Episode> = emptyList(),
    /** 当前播放分集 id（选集 tab 高亮）；电影为 null。 */
    currentEpisodeId: String? = null,
    /** 选集 tab 选中某集（由外部换集：替换当前播放页）。 */
    onSelectEpisode: (String) -> Unit = {},
    /** 影片/剧集简介（简介 tab 兜底；电影只有它）。 */
    overview: String? = null,
    /** 音频延迟毫秒（音画同步微调；直通 bitstream 路径不生效）。 */
    audioDelayMs: Long = 0L,
    /** 音频延迟 ±100ms 步进回调。 */
    onAudioDelayChanged: (Long) -> Unit = {},
    /** 字幕延迟毫秒。 */
    subtitleDelayMs: Long = 0L,
    /** 字幕延迟 ±100ms 步进回调。 */
    onSubtitleDelayChanged: (Long) -> Unit = {},
    /** 字幕字号档位（1 小 – 4 超大，抽屉字幕 tab 加调节入口）。 */
    subtitleSize: Int = 2,
    /** 字幕字号切换回调（OK 循环档位，立即生效并持久化）。 */
    onSubtitleSizeChanged: (Int) -> Unit = {},
    subtitleWeight: Int = 1,
    onSubtitleWeightChanged: (Int) -> Unit = {},
    /** 字幕抬升档位（0=默认居中，1/2 上移，3/4 下移）。 */
    subtitleOffsetLevel: Int = 0,
    /** 字幕抬升切换回调（OK 循环档位，立即生效并持久化）。 */
    onSubtitleOffsetLevelChanged: (Int) -> Unit = {},
    /** 弹幕会话（配置/加载状态/数据/偏移/搜索流程）。 */
    danmu: DanmuSession = DanmuSession(),
    /** 弹幕操作回调束（null = 弹幕交互不可用，tab 只展示状态）。 */
    danmuActions: DanmuActions? = null,
    /** 弹幕手动搜索的预填关键词（当前标题）。 */
    danmuSearchPrefill: String = "",
    currentPosition: Long = 0L,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(DRAWER_ANIM_MS)) { it },
        exit = slideOutHorizontally(tween(DRAWER_ANIM_MS)) { it },
        modifier = modifier,
    ) {
        // 抽屉常驻组合（AnimatedVisibility 只改显隐），remember 保持上次所在 tab
        var currentTab by remember { mutableStateOf(initialTab) }
        // 调节行获焦计数：多个调节行间焦点转移时 +1/-1 抵消，避免 false 闪烁
        // 导致父层误吞左右键。>0 = 有调节行获焦 → 父层放行左右键给无级调节。
        var adjustFocusCount by remember { mutableIntStateOf(0) }
        fun onAdjustFocus(focused: Boolean) {
            adjustFocusCount = (adjustFocusCount + if (focused) 1 else -1).coerceAtLeast(0)
            onAdjustFocusChanged(adjustFocusCount > 0)
        }
        val tabFocus = remember { FocusRequester() }
        val tabRowScroll = rememberScrollState()
        // 内容区独立滚动：标题与 tab 固定在上方，翻到列表底部也能直接切 tab
        val contentScroll = rememberScrollState()
        val focusManager = LocalFocusManager.current
        val drawerScope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            withFrameNanosCompat()
            runCatching { tabFocus.requestFocus() }
        }
        // 切 tab 内容整页换掉：滚动位置必须归零，否则从长列表底部切到短内容会停在半截
        LaunchedEffect(currentTab) {
            contentScroll.scrollTo(0)
        }

        /**
         * 上下键：优先交给焦点系统（内容行之间移动，获焦行自带 bring-into-view）；
         * 没有可移动的焦点目标（只读页/列表到头）才整页滚动。
         */
        fun verticalKey(direction: FocusDirection): Boolean {
            if (focusManager.moveFocus(direction)) return true
            val down = direction == FocusDirection.Down
            if (down && !contentScroll.canScrollForward) return false
            if (!down && !contentScroll.canScrollBackward) return false
            // 按视口高度的 85% 翻页：一屏多一点，读起来不会"跳过头"
            val viewport = contentScroll.viewportSize.takeIf { it > 0 } ?: return false
            val step = viewport * DRAWER_SCROLL_PAGE_RATIO * if (down) 1f else -1f
            drawerScope.launch {
                contentScroll.animateScrollBy(step, tween(DRAWER_SCROLL_ANIM_MS))
            }
            return true
        }

        Column(
            modifier = Modifier
                .width(DRAWER_WIDTH)
                .fillMaxHeight()
                // 上下内收：抽屉不贴屏幕上下边缘
                .padding(vertical = DRAWER_VERTICAL_INSET)
                .clip(NovumShapes.Large)
                // 深色磨砂玻璃面板，保证白色文字的对比度。
                .background(NovumPalette.GlassDeep)
                .border(
                    width = 1.dp,
                    color = NovumPalette.Border,
                    shape = NovumShapes.Large,
                )
                // 四向困焦：列表翻到顶/底、tab 行到头时
                // 焦点搜索会逃出抽屉落到根节点（高亮凭空消失）——exit 一律取消
                // 边界处焦点停在原地；抽屉内芯片↔内容的上下移动不受影响
                // properties 必须在组 target 前，否则会困住子 tab 组，连内容区也进不去。
                // 退出动画期间节点仍在；关闭后要允许父层把焦点交还控制条。
                .focusProperties { onExit = { if (visible) cancelFocusChange() } }
                // 抽屉是独立焦点组：内部导航由组内节点完成，不向控制条逃逸
                .focusGroup()
                // 上下键：焦点优先、翻页兜底（只读页没有可聚焦行，靠这条才翻得动）
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> verticalKey(FocusDirection.Down)
                        Key.DirectionUp -> verticalKey(FocusDirection.Up)
                        else -> false
                    }
                },
        ) {
            // ---- 固定头：标题 + tab（不随内容滚动）----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_drawer_title),
                    style = NovumType.Section,
                    color = NovumPalette.Paper,
                )

                // 抽屉内侧宽度不足以容纳七个 tab。两侧预留固定的方向提示位，
                // 不压住标签和焦点框；左右键移焦时 horizontalScroll 会跟随焦点滚动。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusGroup()
                        .onFocusChanged { onTabsFocusChanged(it.hasFocus) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TabOverflowHint(
                        visible = hasMoreTabsBefore(tabRowScroll.value, tabRowScroll.maxValue),
                        text = "‹",
                        description = stringResource(R.string.player_drawer_tabs_more_left),
                        modifier = Modifier.width(TAB_OVERFLOW_HINT_WIDTH),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(tabRowScroll),
                    ) {
                        DrawerTab.entries.forEach { tab ->
                            NovumChip(
                                label = stringResource(tab.labelRes),
                                selected = tab == currentTab,
                                onClick = { currentTab = tab },
                                focusRequester = tabFocus.takeIf { tab == initialTab },
                            )
                        }
                    }
                    TabOverflowHint(
                        visible = hasMoreTabsAfter(tabRowScroll.value, tabRowScroll.maxValue),
                        text = "›",
                        description = stringResource(R.string.player_drawer_tabs_more_right),
                        modifier = Modifier.width(TAB_OVERFLOW_HINT_WIDTH),
                    )
                }
            }

            // 固定头与滚动内容之间的分隔线（内缩，避开面板圆角）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .height(1.dp)
                    .background(NovumPalette.Border.copy(alpha = 0.6f)),
            )

            // ---- 滚动内容区 ----
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(contentScroll)
                        .padding(horizontal = 24.dp)
                        // 首行留一点空隙（各页不再重复页签名小标题）、末行不贴面板底边
                        .padding(top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (currentTab) {
                        DrawerTab.Episodes -> EpisodesTab(
                            episodes = episodes,
                            currentEpisodeId = currentEpisodeId,
                            onSelectEpisode = onSelectEpisode,
                        )

                        DrawerTab.Overview -> OverviewTab(
                            title = title,
                            episode = episodes.firstOrNull { it.id == currentEpisodeId },
                            overview = overview,
                        )

                        DrawerTab.Subtitle -> SubtitleTab(
                            burnedInLabel = plan.burnedInSubtitleLabel,
                            textTracks = textTracks,
                            subtitleDelayMs = subtitleDelayMs,
                            onSubtitleDelayChanged = onSubtitleDelayChanged,
                            subtitleSize = subtitleSize,
                            onSubtitleSizeChanged = onSubtitleSizeChanged,
                            subtitleWeight = subtitleWeight,
                            onSubtitleWeightChanged = onSubtitleWeightChanged,
                            subtitleOffsetLevel = subtitleOffsetLevel,
                            onSubtitleOffsetLevelChanged = onSubtitleOffsetLevelChanged,
                            onSelectText = onSelectText,
                            onAdjustFocus = ::onAdjustFocus,
                        )

                        DrawerTab.Danmaku -> DanmakuTab(
                            danmu = danmu,
                            actions = danmuActions,
                            prefill = danmuSearchPrefill,
                            onAdjustFocus = ::onAdjustFocus,
                        )

                        DrawerTab.Audio -> AudioTab(
                            audioTracks = audioTracks,
                            passthrough = passthrough,
                            audioDelayMs = audioDelayMs,
                            onAudioDelayChanged = onAudioDelayChanged,
                            onSelectAudio = onSelectAudio,
                            onTogglePassthrough = onTogglePassthrough,
                            onAdjustFocus = ::onAdjustFocus,
                        )

                        DrawerTab.Quality -> QualityTab(
                            plan = plan,
                            subtitle = subtitle,
                            audioLabel = audioLabel,
                            subtitleLabel = subtitleLabel,
                            liveStats = liveStats,
                        )

                        DrawerTab.Chapter -> ChapterTab(
                            chapters = chapters,
                            currentPosition = currentPosition,
                            onSelectChapter = onSelectChapter,
                        )
                    }
                }

                // 上下边缘渐隐：提示"上面/下面还有内容"，只读页尤其需要
                if (contentScroll.canScrollBackward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .height(DRAWER_EDGE_FADE)
                            .background(
                                Brush.verticalGradient(
                                    listOf(NovumPalette.GlassDeep, Color.Transparent),
                                ),
                            ),
                    )
                }
                if (contentScroll.canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(DRAWER_EDGE_FADE)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, NovumPalette.GlassDeep),
                                ),
                            ),
                    )
                }

                DrawerScrollIndicator(
                    state = contentScroll,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/**
 * 选集 tab：当前季分集列表，OK 换集。
 *
 * 行文与详情页选集卡同口径「SxxExx · 集标题」；当前集以选中态标记
 * 有进度的集在行尾显示剩余时长、已看完显示「已看完」。换集由外部走
 * `onOpenEpisode`（替换当前播放页，不叠加返回栈）。
 *
 * 集简介已独立成「简介」页，这里只留列表——长简介不再把选集挤到屏幕外。
 *
 * @param episodes 当前季分集；空 = 电影/分集列表不可用，给空态提示。
 * @param currentEpisodeId 当前播放分集 id。
 * @param onSelectEpisode 选中某集（外部换集）。
 */
@Composable
private fun EpisodesTab(
    episodes: List<Episode>,
    currentEpisodeId: String?,
    onSelectEpisode: (String) -> Unit,
) {
    if (episodes.isEmpty()) {
        HintRow(stringResource(R.string.player_drawer_no_episodes))
        return
    }
    // 页签本身就叫「选集」，页内不再重复一遍小标题
    episodes.forEach { episode ->
        val runtime = episode.runtimeMinutes
        val progress = episode.progressRatio
        val value = when {
            episode.played -> null
            progress != null && runtime != null -> stringResource(
                R.string.detail_episode_remaining,
                ((runtime * (1f - progress)).toInt()).coerceAtLeast(1),
            )
            else -> null
        }
        DrawerRow(
            label = drawerEpisodeLabel(episode.seasonIndex, episode.index, episode.title),
            value = value,
            selected = episode.id == currentEpisodeId,
            played = episode.played,
            onClick = { onSelectEpisode(episode.id) },
        )
    }
}

/**
 * 简介 tab：当前集（电影为整部影片）的完整简介。
 *
 * 简介是"读"的内容而不是"选"的内容，独立成页后可以尽情滚动，
 * 不再挤压选集列表；分集没有简介时回落到剧集/影片简介。
 *
 * @param title 影片/剧集标题（无分集时作页首标题）。
 * @param episode 当前播放分集；电影为 null。
 * @param overview 影片/剧集简介（兜底）。
 */
@Composable
private fun OverviewTab(
    title: String,
    episode: Episode?,
    overview: String?,
) {
    // 集号单独进元信息行：原来标题里再拼一次 "SxxExx · 第N集 标题"，
    // Emby 的 Name 本身常带「第N集」，看起来就是同一串编号写了两遍
    val episodeLabel = episode?.let { episodeCode(it.seasonIndex, it.index) }
    val heading = episode?.let { stripEpisodePrefix(it.title) }?.takeIf { it.isNotBlank() }
        ?: title
    val meta = listOfNotNull(
        episodeLabel,
        episode?.runtimeMinutes?.let { stringResource(R.string.detail_minutes, it) },
        episode?.premiereDate?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    if (heading.isNotBlank()) {
        Text(
            text = heading,
            style = NovumType.BodyStrong,
            color = NovumPalette.Paper,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    if (meta.isNotBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = meta,
            style = NovumType.Caption,
            color = NovumPalette.PaperMuted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
    val rawBody = episode?.overview?.takeIf { it.isNotBlank() }
        ?: overview?.takeIf { it.isNotBlank() }
    val body = drawerOverviewBody(heading, rawBody)
    Spacer(modifier = Modifier.height(14.dp))
    Text(
        text = body ?: stringResource(R.string.player_drawer_no_overview),
        style = NovumType.Synopsis,
        color = NovumPalette.PaperFaint,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** 集名前缀：`第1集` / `S01E01` / `EP01` 及其后的分隔符；简介页标题只留纯集名。 */
private val EPISODE_PREFIX = Regex(
    // 中文用 \uXXXX 写：UI 源码禁止出现中文字面量（UiCopyContractTest 会拦），
    // 这是正则而不是用户文案。
    """^\s*(?:\u7B2C\s*[0-9\u4E00\u4E8C\u4E09\u56DB\u4E94\u516D\u4E03\u516B\u4E5D\u5341\u767E]+\s*[\u96C6\u8BDD\u8A71\u56DE]|[Ss]\d{1,2}\s*[Ee]\d{1,3}|[Ee][Pp]?\d{1,4})\s*[\u00B7:\uFF1A\-\u2014]?\s*""",
)

/** 去掉集名里的编号前缀（编号已经在元信息行单独展示）。 */
internal fun stripEpisodePrefix(title: String): String =
    title.replace(EPISODE_PREFIX, "").trim()

/** 两位是最小补零宽度，不截断第 100 集及之后的真实集号。 */
internal fun episodeCode(seasonIndex: Int, episodeIndex: Int): String =
    "S%02dE%02d".format(seasonIndex, episodeIndex)

/** 集号只展示一次；源标题若只有编号，列表直接显示标准集号。 */
internal fun drawerEpisodeLabel(seasonIndex: Int, episodeIndex: Int, title: String): String {
    val name = stripEpisodePrefix(title)
    return listOf(episodeCode(seasonIndex, episodeIndex), name).filter { it.isNotBlank() }.joinToString(" · ")
}

/** 字幕 tab：字幕延迟微调 + 字幕字号调节 + 字幕高度调节 + 轨道列表（首位「关闭字幕」）。 */
@Composable
private fun SubtitleTab(
    burnedInLabel: String?,
    textTracks: List<TrackOption>,
    subtitleDelayMs: Long,
    onSubtitleDelayChanged: (Long) -> Unit,
    subtitleSize: Int,
    onSubtitleSizeChanged: (Int) -> Unit,
    subtitleWeight: Int,
    onSubtitleWeightChanged: (Int) -> Unit,
    subtitleOffsetLevel: Int,
    onSubtitleOffsetLevelChanged: (Int) -> Unit,
    onSelectText: (TrackOption) -> Unit,
    onAdjustFocus: (Boolean) -> Unit,
) {
    if (burnedInLabel != null) {
        HintRow(stringResource(R.string.player_subtitle_burned_in))
        DrawerRow(label = burnedInLabel, value = null, selected = true, enabled = false, onClick = {})
        return
    }
    // 页签就叫「字幕」，页内不再重复小标题。
    // 「关闭字幕」原来是轨道列表首行、常亮一块选中底框，看着像个去不掉的框。
    // 布尔语义统一交给开关：开 = 选第一条字幕轨，关 = 选中「关闭字幕」。
    val offOption = textTracks.firstOrNull { it.id == TEXT_OFF_ID }
    val tracks = textTracks.filter { it.id != TEXT_OFF_ID }
    if (tracks.isEmpty()) {
        HintRow(stringResource(R.string.player_drawer_no_subtitle))
        return
    }
    NovumSwitchRow(
        text = stringResource(R.string.player_drawer_subtitle_switch),
        checked = tracks.any { it.selected },
        onCheckedChange = { enabled ->
            (if (enabled) tracks.firstOrNull { it.supported } else offOption)?.let(onSelectText)
        },
        consumeAtBoundary = true,
    )
    // 延迟/字号/高度：左右键无级调节（OK 保留原循环作为兼容）
    DrawerRow(
        label = stringResource(R.string.player_drawer_subtitle_delay),
        value = formatDelay(subtitleDelayMs),
        selected = subtitleDelayMs != 0L,
        // 字幕延迟只能「推后」：ExoPlayer 按时间送达 cue，提前显示拿不到未来的 cue，
        // 负值在 CueDelayScheduler 里等价于 0——档位下限钳在 0，不给用户一个无效旋钮
        onClick = { onSubtitleDelayChanged(if (subtitleDelayMs >= 2_000L) 0L else subtitleDelayMs + 100L) },
        onStep = { dir -> onSubtitleDelayChanged((subtitleDelayMs + dir * 100L).coerceIn(0L, 2_000L)) },
        onAdjustFocus = onAdjustFocus,
        adjustmentFraction = subtitleDelayMs / 2_000f,
    )
    DrawerRow(
        label = stringResource(R.string.settings_subtitle_size),
        value = stringResource(
            when (subtitleSize) {
                1 -> R.string.subtitle_size_small
                3 -> R.string.subtitle_size_large
                4 -> R.string.subtitle_size_xlarge
                else -> R.string.subtitle_size_medium
            },
        ),
        selected = subtitleSize != 2,
        onClick = { onSubtitleSizeChanged(if (subtitleSize >= 4) 1 else subtitleSize + 1) },
        onStep = { dir -> onSubtitleSizeChanged((subtitleSize + dir).coerceIn(1, 4)) },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 4,
        segmentLevel = subtitleSize - 1,
    )
    DrawerRow(
        label = stringResource(R.string.settings_subtitle_weight),
        value = stringResource(
            when (subtitleWeight) {
                0 -> R.string.subtitle_weight_thin
                2 -> R.string.subtitle_weight_bold
                else -> R.string.subtitle_weight_normal
            },
        ),
        selected = false,
        onClick = { onSubtitleWeightChanged((subtitleWeight + 1) % 3) },
        segmentCount = 3,
        segmentLevel = subtitleWeight,
    )
    // 保留存储值，按实际高度排列控制条：低 → 默认 → 高。
    val heightLevels = listOf(4, 3, 0, 1, 2)
    val heightIndex = heightLevels.indexOf(subtitleOffsetLevel).coerceAtLeast(0)
    DrawerRow(
        label = stringResource(R.string.player_drawer_subtitle_height),
        value = stringResource(
            when (subtitleOffsetLevel) {
                1 -> R.string.subtitle_height_up1
                2 -> R.string.subtitle_height_up2
                3 -> R.string.subtitle_height_down1
                4 -> R.string.subtitle_height_down2
                else -> R.string.subtitle_height_default
            },
        ),
        selected = subtitleOffsetLevel != 0,
        onClick = { onSubtitleOffsetLevelChanged(heightLevels[(heightIndex + 1) % heightLevels.size]) },
        onStep = { dir -> onSubtitleOffsetLevelChanged(heightLevels[(heightIndex + dir).coerceIn(0, 4)]) },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 5,
        segmentLevel = heightIndex,
        adjustmentCenterFraction = 0.5f,
    )
    tracks.forEach { option ->
        DrawerRow(
            label = option.label,
            value = null,
            selected = option.selected,
            onClick = { onSelectText(option) },
            // 只有一条字幕轨时没有可切换的对象：行保留（让用户知道当前用哪条），
            // 但不进焦点链、不显得可操作
            enabled = option.supported && (tracks.size > 1 || !option.selected),
        )
    }
}

/**
 * 弹幕 tab：开关 + 渲染参数调节（区域/透明度/字号/密度/速度/偏移）+
 * 匹配状态 + 手动搜索（搜索片名 → 选动画 → 选分集）。
 *
 * 弹幕关闭时只显示开关行；搜索区只在弹幕开启且加载流程已启动后出现。
 */
@Composable
private fun DanmakuTab(
    danmu: DanmuSession,
    actions: DanmuActions?,
    prefill: String,
    onAdjustFocus: (Boolean) -> Unit,
) {
    val config = danmu.config
    // 页签就叫「弹幕」，页内不再重复小标题
    NovumSwitchRow(
        text = stringResource(R.string.danmu_enabled),
        checked = config.enabled,
        onCheckedChange = { enabled -> actions?.setEnabled(enabled) },
        consumeAtBoundary = true,
    )
    if (!config.enabled) return

    // 三档区域用 OK 循环；仅顶部占上四分之一屏。
    DrawerRow(
        label = stringResource(R.string.danmu_area),
        value = stringResource(
            when (config.area) {
                DanmuArea.Top -> R.string.danmu_area_top
                DanmuArea.Half -> R.string.danmu_area_half
                DanmuArea.Full -> R.string.danmu_area_full
            },
        ),
        selected = false,
        onClick = { actions?.updateConfig { it.copy(area = DanmuArea.entries[(it.area.ordinal + 1) % 3]) } },
        segmentCount = 3,
        segmentLevel = config.area.ordinal,
    )
    // 透明度：10%–100%，步进 10%
    DrawerRow(
        label = stringResource(R.string.danmu_opacity),
        value = "${(config.opacity * 100).roundToInt()}%",
        selected = config.opacity != DanmuConfig().opacity,
        // 十分位整数推进：浮点 +0.1 会漂成 0.7999999，显示与写回都要先取整
        onClick = {
            actions?.updateConfig {
                it.copy(opacity = percentValue(cyclePercentLevel(it.opacity)))
            }
        },
        onStep = { dir ->
            actions?.updateConfig {
                it.copy(opacity = percentValue(stepPercentLevel(it.opacity, dir)))
            }
        },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 10,
        segmentLevel = (config.opacity * 10).roundToInt() - 1,
    )
    // 字号：小 / 中 / 大（复用字幕字号文案）
    DrawerRow(
        label = stringResource(R.string.danmu_size),
        value = stringResource(
            when (config.size) {
                DanmuSize.Small -> R.string.subtitle_size_small
                DanmuSize.Large -> R.string.subtitle_size_large
                else -> R.string.subtitle_size_medium
            },
        ),
        selected = config.size != DanmuConfig().size,
        onClick = { actions?.updateConfig { it.copy(size = it.size.next()) } },
        onStep = { dir -> actions?.updateConfig { it.copy(size = it.size.stepped(dir)) } },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 3,
        segmentLevel = config.size.ordinal,
    )
    // 密度：10%–100%，步进 10%
    DrawerRow(
        label = stringResource(R.string.danmu_density),
        value = "${(config.density * 100).roundToInt()}%",
        selected = config.density != DanmuConfig().density,
        onClick = {
            actions?.updateConfig {
                it.copy(density = percentValue(cyclePercentLevel(it.density)))
            }
        },
        onStep = { dir ->
            actions?.updateConfig {
                it.copy(density = percentValue(stepPercentLevel(it.density, dir)))
            }
        },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 10,
        segmentLevel = (config.density * 10).roundToInt() - 1,
    )
    // 滚动速度：慢 / 标准 / 快
    DrawerRow(
        label = stringResource(R.string.danmu_speed),
        value = stringResource(
            when (config.speed) {
                DanmuSpeed.Slow -> R.string.danmu_speed_slow
                DanmuSpeed.Fast -> R.string.danmu_speed_fast
                else -> R.string.danmu_speed_normal
            },
        ),
        selected = config.speed != DanmuConfig().speed,
        onClick = { actions?.updateConfig { it.copy(speed = it.speed.next()) } },
        onStep = { dir -> actions?.updateConfig { it.copy(speed = it.speed.stepped(dir)) } },
        onAdjustFocus = onAdjustFocus,
        segmentCount = 3,
        segmentLevel = config.speed.ordinal,
    )
    // 时间偏移：±0.1 秒步进，控制条中线表示零偏移。
    DrawerRow(
        label = stringResource(R.string.danmu_offset),
        value = formatDanmuOffset(danmu.offsetMs),
        selected = danmu.offsetMs != 0L,
        onClick = { actions?.adjustOffset(DANMU_OFFSET_STEP_MS) },
        onStep = { dir -> actions?.adjustOffset(dir * DANMU_OFFSET_STEP_MS) },
        onAdjustFocus = onAdjustFocus,
        adjustmentFraction = 0.5f + danmu.offsetMs.toFloat() / (2 * DANMU_OFFSET_LIMIT_MS),
        adjustmentCenterFraction = 0.5f,
    )
    if (danmu.offsetMs != 0L) {
        DrawerRow(
            label = stringResource(R.string.danmu_offset_reset),
            value = null,
            selected = false,
            onClick = { actions?.resetOffset() },
        )
    }

    // ---- 匹配状态 ----
    SectionLabel(stringResource(R.string.danmu_match_section))
    when (val load = danmu.load) {
        is DanmuLoad.Loaded -> {
            val suffix = if (load.manual) {
                stringResource(R.string.danmu_status_manual_suffix)
            } else {
                ""
            }
            HintRow(
                stringResource(R.string.danmu_status_count, load.count) +
                    (load.episodeTitle?.let { " · $it" } ?: "") + suffix,
            )
            if (load.manual) {
                DrawerRow(
                    label = stringResource(R.string.danmu_auto_match),
                    value = null,
                    selected = false,
                    onClick = { actions?.useAutoMatch() },
                )
            }
        }

        DanmuLoad.Matching -> HintRow(stringResource(R.string.danmu_status_matching))
        DanmuLoad.Loading -> HintRow(stringResource(R.string.danmu_status_loading))
        DanmuLoad.Unmatched -> HintRow(stringResource(R.string.danmu_status_unmatched))
        is DanmuLoad.Failed -> {
            // 鉴权失败 = 这台服务器没有弹幕能力（非 UHDNOW），给一句可理解的说明，
            // 而不是把 401 原文摆给用户看
            HintRow(
                if (load.error is NovumError.Unauthorized) {
                    stringResource(R.string.danmu_unavailable)
                } else {
                    load.error.userMessage()
                },
            )
            DrawerRow(
                label = stringResource(R.string.danmu_retry),
                value = null,
                selected = false,
                onClick = { actions?.retry() },
            )
        }

        DanmuLoad.Idle -> Unit
    }

    // ---- 手动搜索：默认收起。开关打开后才显示搜索框 ----
    // 抽屉本来就长，搜索区常驻会让每次看匹配状态都要滚过一整块表单
    var manualSearch by remember { mutableStateOf(false) }
    NovumSwitchRow(
        text = stringResource(R.string.danmu_search_section),
        checked = manualSearch,
        onCheckedChange = { manualSearch = it },
        consumeAtBoundary = true,
    )
    if (!manualSearch) return
    when (val search = danmu.search) {
        DanmuSearch.Searching -> HintRow(stringResource(R.string.danmu_search_running))

        is DanmuSearch.Results -> {
            if (search.animes.isEmpty()) {
                HintRow(stringResource(R.string.danmu_search_empty))
            }
            search.animes.forEach { anime ->
                DrawerRow(
                    label = anime.title ?: anime.bangumiId,
                    value = null,
                    selected = false,
                    onClick = { actions?.openAnime(anime) },
                )
            }
        }

        is DanmuSearch.Episodes -> {
            DrawerRow(
                label = stringResource(R.string.danmu_search_close),
                value = null,
                selected = false,
                onClick = { actions?.closeSearch() },
            )
            if (search.episodes.isEmpty()) {
                HintRow(stringResource(R.string.danmu_episodes_empty))
            }
            search.episodes.forEach { episode ->
                val active = (danmu.load as? DanmuLoad.Loaded)?.episodeId == episode.episodeId
                DrawerRow(
                    label = episode.title ?: episode.episodeId,
                    value = null,
                    selected = active,
                    onClick = { actions?.selectEpisode(episode) },
                )
            }
        }

        is DanmuSearch.Failed -> {
            HintRow(search.error.userMessage())
            // 搜索失败时只能退回（重试需要重新输入关键词），文案不能写「重试」
            DrawerRow(
                label = stringResource(R.string.danmu_search_close),
                value = null,
                selected = false,
                onClick = { actions?.closeSearch() },
            )
        }

        DanmuSearch.Idle -> {
            var keyword by remember(prefill) { mutableStateOf(prefill) }
            NovumTextField(
                value = keyword,
                onValueChange = { keyword = it },
                label = stringResource(R.string.danmu_search_label),
                imeAction = ImeAction.Search,
                keyboardActions = KeyboardActions(onSearch = { actions?.searchAnime(keyword) }),
            )
            DrawerRow(
                label = stringResource(R.string.danmu_search_action),
                value = null,
                selected = false,
                onClick = { actions?.searchAnime(keyword) },
            )
        }
    }
}

/** 百分比档位（10 档）：把可能带浮点误差的当前值归一到 1..10。 */
private fun percentLevel(value: Float): Int = (value * 10f).roundToInt().coerceIn(1, 10)

/** 档位值还原成 0.1–1.0 的配置值。 */
private fun percentValue(level: Int): Float = level / 10f

/** OK：到顶回绕到最低档。 */
private fun cyclePercentLevel(value: Float): Int =
    percentLevel(value).let { if (it >= 10) 1 else it + 1 }

/** 左右键：到顶/到底钳住（方向感明确，不绕回）。 */
private fun stepPercentLevel(value: Float, dir: Int): Int =
    (percentLevel(value) + dir).coerceIn(1, 10)

/** 档位循环推进（OK）：小 → 中 → 大 → 小。 */
private fun DanmuSize.next(): DanmuSize =
    DanmuSize.entries[(ordinal + 1) % DanmuSize.entries.size]

/** 档位按方向步进（方向键）：两端钳住，不绕回。 */
private fun DanmuSize.stepped(dir: Int): DanmuSize =
    DanmuSize.entries[(ordinal + dir).coerceIn(0, DanmuSize.entries.lastIndex)]

/** 档位循环推进（OK）：慢 → 标准 → 快 → 慢。 */
private fun DanmuSpeed.next(): DanmuSpeed =
    DanmuSpeed.entries[(ordinal + 1) % DanmuSpeed.entries.size]

/** 档位按方向步进（方向键）：两端钳住，不绕回。 */
private fun DanmuSpeed.stepped(dir: Int): DanmuSpeed =
    DanmuSpeed.entries[(ordinal + dir).coerceIn(0, DanmuSpeed.entries.lastIndex)]

/** 弹幕偏移步进（±0.1 秒）。 */
internal const val DANMU_OFFSET_STEP_MS = 100L

/** 偏移按秒保留一位小数，零偏移同样显示数值。 */
private fun formatDanmuOffset(ms: Long): String {
    if (ms == 0L) return "0.0s"
    val seconds = ms / 1000.0
    return String.format(java.util.Locale.ROOT, "%+.1fs", seconds)
}

/** 音轨 tab：直通开关 + 音频延迟微调 + 音轨列表。 */
@Composable
private fun AudioTab(
    audioTracks: List<TrackOption>,
    passthrough: Boolean,
    audioDelayMs: Long,
    onAudioDelayChanged: (Long) -> Unit,
    onSelectAudio: (TrackOption) -> Unit,
    onTogglePassthrough: () -> Unit,
    onAdjustFocus: (Boolean) -> Unit,
) {
    if (audioTracks.isEmpty()) {
        HintRow(stringResource(R.string.player_drawer_no_audio_operations))
        return
    }
    // 页签已是「音轨」；「音频直通」这块的小标题也与开关自己的文案重复
    NovumSwitchRow(
        text = stringResource(R.string.player_drawer_passthrough),
        checked = passthrough,
        onCheckedChange = { onTogglePassthrough() },
        consumeAtBoundary = true,
    )
    // 音频延迟（音画同步微调）：PCM 路径生效（直通 bitstream 不经处理器）
    // 左右键无级 ±100ms（OK 保留循环兼容）；是否生效见「画质」tab 的「音频输出」行
    DrawerRow(
        label = stringResource(R.string.player_drawer_audio_delay),
        value = formatDelay(audioDelayMs),
        selected = audioDelayMs != 0L,
        onClick = { onAudioDelayChanged(if (audioDelayMs >= 2_000L) -2_000L else audioDelayMs + 100L) },
        onStep = { dir -> onAudioDelayChanged((audioDelayMs + dir * 100L).coerceIn(-2_000L, 2_000L)) },
        onAdjustFocus = onAdjustFocus,
        adjustmentFraction = (audioDelayMs + 2_000L) / 4_000f,
        adjustmentCenterFraction = 0.5f,
    )
    // 单音轨没有切换对象：保留当前格式作只读信息。
    val single = audioTracks.size == 1
    audioTracks.forEach { option ->
        DrawerRow(
            label = option.label,
            value = option.codec,
            selected = option.selected,
            onClick = { onSelectAudio(option) },
            enabled = option.supported && !single,
        )
    }
}

/**
 * 画质 tab：只读展示片源规格与实时播放状态。
 *
 * 画面尺寸优先使用解码器上报值，缺失时回落到服务端声明；显示模式和实时缓冲信息
 * 由播放器状态提供。
 *
 * @param liveStats 播放实时状态；null = 调用方未提供，实时行不渲染。
 */
@Composable
private fun QualityTab(
    plan: PlaybackPlan,
    subtitle: String,
    audioLabel: String,
    subtitleLabel: String,
    liveStats: PlayerLiveStats?,
) {
    // 页签就叫「信息」，页内不再重复小标题
    val spec = plan.videoSpec
    val channelsTemplate = stringResource(R.string.player_track_channels)
    // 画面尺寸优先使用解码器上报值，缺失时回落到服务端声明分辨率。
    val liveSize = liveStats
        ?.takeIf { it.videoWidth > 0 && it.videoHeight > 0 }
        ?.let { "${it.videoWidth}×${it.videoHeight}" }
    InfoLine(R.string.player_stats_video_size, liveSize ?: spec?.resolution)
    liveStats?.displayMode?.let { InfoLine(R.string.player_stats_display_mode, it) }
    InfoLine(
        R.string.player_info_video,
        listOfNotNull(
            spec?.codec?.uppercase(),
            spec?.hdr ?: SPEC_SDR,
            spec?.frameRate?.let { "%.2f fps".format(it) },
        ).joinToString(" · ").ifBlank { null },
    )
    InfoLine(R.string.player_info_bitrate, spec?.bitrate?.let(::formatBitrate))
    InfoLine(R.string.player_info_container, spec?.container?.uppercase())
    InfoLine(R.string.player_info_method, subtitle.ifBlank { null })
    InfoLine(
        R.string.player_info_audio,
        drawerAudioSummary(audioLabel, spec?.audioChannels, channelsTemplate),
    )
    // 音频输出路径：PCM 解码 / bitstream 直通——「音画延迟是否生效」就看这一行
    // （延迟处理器只挂在 PCM 路径，bitstream 直通不经过，属预期行为）
    InfoLine(R.string.player_info_audio_output, liveStats?.audioOutput)
    InfoLine(R.string.player_info_subtitle, subtitleLabel.ifBlank { null })
    liveStats?.let { stats ->
        InfoLine(
            R.string.player_stats_dropped,
            stringResource(R.string.player_stats_dropped_fmt, stats.droppedFrames),
        )
        InfoLine(
            R.string.player_stats_buffer,
            stringResource(
                R.string.player_stats_buffer_fmt,
                stats.bufferedPercentage,
                stats.bufferedAheadSeconds,
            ),
        )
    }
}

/** 章节 tab：OK 跳转该章并关闭抽屉。 */
@Composable
private fun ChapterTab(
    chapters: List<Chapter>,
    currentPosition: Long,
    onSelectChapter: (Chapter) -> Unit,
) {
    if (chapters.isEmpty()) {
        HintRow(stringResource(R.string.player_drawer_no_chapters))
        return
    }
    val chapterTemplate = stringResource(R.string.player_info_chapter_default)
    // 当前章 = 起点不晚于播放位置的最后一章（拖动预览跟随 scrubTarget 由调用方传入）
    val current = chapters.lastOrNull { it.startMs <= currentPosition }
    chapters.forEachIndexed { index, chapter ->
        DrawerRow(
            label = chapter.name ?: String.format(chapterTemplate, index + 1),
            value = formatTime(chapter.startMs),
            selected = chapter === current,
            onClick = { onSelectChapter(chapter) },
        )
    }
}

/**
 * 右侧滚动指示条：只读页（画质/简介）没有焦点行，用户需要一个"还有多长"的参照。
 *
 * 轨道高 = 内容区高，滑块高按「视口 / 内容」比例，位置按滚动进度。
 * 内容装得下（maxValue == 0）时整条不渲染。
 */
@Composable
private fun DrawerScrollIndicator(
    state: ScrollState,
    modifier: Modifier = Modifier,
) {
    val viewport = state.viewportSize
    val max = state.maxValue
    if (viewport <= 0 || max <= 0) return
    val thumbFraction = (viewport.toFloat() / (viewport + max)).coerceIn(0.08f, 1f)
    val travel = viewport * (1f - thumbFraction)
    Box(
        modifier = modifier
            .padding(end = 6.dp)
            .width(3.dp)
            .fillMaxHeight()
            .clip(NovumShapes.Pill)
            .background(NovumPalette.ScrollbarTrack),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(thumbFraction)
                // 滚动值在布局阶段读取：不参与重组（@FrequentlyChangingValue）
                .offset {
                    val latestMax = state.maxValue
                    val fraction = if (latestMax > 0) state.value.toFloat() / latestMax else 0f
                    IntOffset(0, (fraction * travel).roundToInt())
                }
                .clip(NovumShapes.Pill)
                .background(NovumPalette.ScrollbarThumb),
        )
    }
}

/** 只读规格行：左标签右值，值缺失显示「未知」。 */
@Composable
private fun InfoLine(labelRes: Int, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = stringResource(labelRes), style = NovumType.Caption, color = NovumPalette.PaperMuted)
        Text(
            text = value?.takeIf { it.isNotBlank() } ?: stringResource(R.string.player_info_unknown),
            style = NovumType.Caption,
            color = NovumPalette.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 没有 HDR 标记时按 SDR 展示。 */
private const val SPEC_SDR = "SDR"

/** 抽屉内的设置行复用 [NovumOptionRow]。获焦时显式滚入可视区：`clickable` 不会像 `focusable` 那样自动
 * bring-into-view，长列表（章节/多字幕）下翻时焦点会落在看不见的行上
 * 因此使用 [BringIntoViewRequester]。
 */
@Composable
private fun DrawerRow(
    label: String,
    value: String?,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    /**
     * 左右键无级调节：非 null 时该行获焦会拦截左右键，按 [onStep] 步进
     * （`@OptIn(ExperimentalComposeUiApi)`），并上报焦点给父层放行左右键。
     * 传 `(-1/1)` 表示减/加一档。
     */
    onStep: ((Int) -> Unit)? = null,
    /** 调节行获焦状态回调（父层据此决定是否吞掉左右键）。 */
    onAdjustFocus: ((Boolean) -> Unit)? = null,
    /** 有限档位时传总档数：行尾画档位条（连续值不要传）。 */
    segmentCount: Int? = null,
    /** 档位条当前档（0 起）。 */
    segmentLevel: Int = 0,
    /** 连续数值的控制条位置；有限档位自动由档位数和当前档换算。 */
    adjustmentFraction: Float? = null,
    adjustmentCenterFraction: Float? = null,
    /** 是否可操作；false = 灰字、不聚焦（没有选择余地的行）。 */
    enabled: Boolean = true,
    played: Boolean = false,
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val cycleOnly = segmentCount != null && segmentCount <= 3
    val usesBar = !cycleOnly && (adjustmentFraction != null || segmentCount != null)
    val step = onStep.takeIf { usesBar }
    val modifier = Modifier
        .bringIntoViewRequester(bringIntoView)
        .then(
            if (step != null) {
                Modifier.onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { step(-1); true }
                        Key.DirectionRight -> { step(1); true }
                        else -> false
                    }
                }
            } else Modifier,
        )
    NovumOptionRow(
        text = label,
        value = value,
        selected = selected && !cycleOnly,
        played = played,
        onClick = onClick,
        focusRequester = focusRequester,
        // 调节项以控制条表达当前值；整行持有焦点，方向键直接调节。
        adjustable = usesBar,
        onFocused = { focused ->
            if (focused) scope.launch { bringIntoView.bringIntoView() }
            if (step != null) onAdjustFocus?.invoke(focused)
        },
        modifier = modifier,
        segmentCount = segmentCount.takeIf { usesBar },
        segmentLevel = segmentLevel,
        enabled = enabled,
        adjustmentFraction = if (!usesBar) null else adjustmentFraction ?: segmentCount?.takeIf { it > 3 }?.let {
            segmentLevel.toFloat() / (it - 1)
        },
        adjustmentCenterFraction = adjustmentCenterFraction,
    )
}

/**
 * 分区小标题（26sp Medium）。
 *
 * 与首页 Section（32sp SemiBold）区分：面板内分区比页面级区块小一档，
 * 但仍明显大于 24sp 正文，滚起来能一眼看出分组。
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = NovumType.BodyStrong,
        color = NovumPalette.Paper,
        modifier = Modifier.padding(top = 18.dp, bottom = 6.dp, start = 4.dp),
    )
}

/** 空列表提示。 */
@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        style = NovumType.Caption,
        color = NovumPalette.PaperMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 等一帧再抢焦点，确保 AnimatedVisibility 内容已进入组合。 */
private suspend fun withFrameNanosCompat() {
    androidx.compose.runtime.withFrameNanos { }
}

/** 头部横向标签的纯滚动边界判断，单测覆盖初始/中段/末端。 */
internal fun hasMoreTabsBefore(scrollValue: Int, maxValue: Int): Boolean = maxValue > 0 && scrollValue > 0

internal fun hasMoreTabsAfter(scrollValue: Int, maxValue: Int): Boolean =
    maxValue > 0 && scrollValue < maxValue

/** 固定宽度的滚动方向提示；不参与焦点链，也不覆盖标签可视区域。 */
@Composable
private fun TabOverflowHint(
    visible: Boolean,
    text: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(52.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Text(
                text = text,
                style = NovumType.Title,
                color = NovumPalette.PaperMuted,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

/** 延迟毫秒转为展示文案，零延迟同样显示数值。 */
private fun formatDelay(ms: Long): String = if (ms == 0L) {
    "0ms"
} else {
    val signed = if (ms > 0) "+$ms" else "$ms"
    "${signed}ms"
}

/** 抽屉宽度：1280dp 设计画布的 43.75%——字阶放大后仍保证「S01E03 · 分集标题」+ 时长不截断。 */
private val DRAWER_WIDTH = 560.dp

/** 抽屉上下内收：不贴屏幕边缘（由 48 收到 32，列表更长）。 */
private val DRAWER_VERTICAL_INSET = 32.dp

/** 内容区上下边缘渐隐高度。 */
private val DRAWER_EDGE_FADE = 18.dp

/** 标签栏两侧提示位；给芯片留出独立可见区域。 */
private val TAB_OVERFLOW_HINT_WIDTH = 24.dp

/** 只读页整页翻的比例：一屏 85%，留 15% 上下文。 */
private const val DRAWER_SCROLL_PAGE_RATIO = 0.85f

/** 翻页动画时长。 */
private const val DRAWER_SCROLL_ANIM_MS = 180

/** 滑入滑出时长。 */
private const val DRAWER_ANIM_MS = 220
