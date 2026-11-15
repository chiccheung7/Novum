package com.novum.tv.ui.screen.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.Chapter
import com.novum.tv.model.Episode
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumOptionRow
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.launch

/** 播放设置抽屉的分区。 */
internal enum class DrawerTab(
    /** tab 文案资源。 */
    val labelRes: Int,
) {
    /** 选集（抽屉内直接换集，不必退回详情页）。 */
    Episodes(R.string.detail_episodes),
    Subtitle(R.string.player_drawer_subtitle),
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
}

/**
 * 播放设置抽屉，按 tab 显示不同设置。
 *
 * 从右侧滑入，包含五个 tab（[DrawerTab]）：
 * - **选集**：当前季分集列表，OK 直接换集。
 * - **字幕**：字幕延迟、字号和字幕轨道列表。
 * - **音轨**：音频直通、延迟和音轨列表。
 * - **画质**：只读播放规格与实时状态。
 * - **章节**：章节列表，OK 跳转并关闭抽屉。
 *
 * 音频延迟只在音轨 tab 调节，字幕延迟只在字幕 tab 调节。
 *
 * 焦点约定：
 * - 打开时焦点落在当前 tab 芯片上；左右键移动焦点，OK 切换 tab。
 * - 内容行焦点不逃逸到控制条；上下键在行间移动。
 * - 内容行获焦后滚入可视区；Back 关闭并将焦点还给「播放设置」按钮。
 *
 * @param visible 是否可见。
 * @param initialTab 打开时定位的 tab（控制条字幕/音轨按钮直达对应分区）。
 * @param onTabsFocusChanged tab 行是否持有焦点的回调（父层据此放行/吞掉左右键）。
 * @param audioTracks 音轨列表。
 * @param textTracks 字幕列表（第一项为「关闭字幕」，selected 表示当前关闭状态）。
 * @param passthrough 音频直通当前值。
 * @param plan 播放计划（画质 tab 的规格来源）。
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
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerSettingsDrawer(
    visible: Boolean,
    initialTab: DrawerTab,
    onTabsFocusChanged: (Boolean) -> Unit,
    audioTracks: List<TrackOption>,
    textTracks: List<TrackOption>,
    passthrough: Boolean,
    plan: PlaybackPlan,
    subtitle: String,
    audioLabel: String,
    subtitleLabel: String,
    chapters: List<Chapter>,
    onSelectAudio: (TrackOption) -> Unit,
    onSelectText: (TrackOption) -> Unit,
    onTogglePassthrough: () -> Unit,
    onSelectChapter: (Chapter) -> Unit,
    /** 抽屉容器修饰符（右侧滑入层的对齐锚点等）。 */
    modifier: Modifier = Modifier,
    /** 播放实时状态（画质 tab「实时播放状态」分区）；null = 未提供，不渲染该分区。 */
    liveStats: PlayerLiveStats? = null,
    /** 当前季分集（选集 tab）；电影为空。 */
    episodes: List<Episode> = emptyList(),
    /** 当前播放分集 id（选集 tab 高亮）；电影为 null。 */
    currentEpisodeId: String? = null,
    /** 选集 tab 选中某集（由外部换集：替换当前播放页）。 */
    onSelectEpisode: (String) -> Unit = {},
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
    /** 字幕抬升档位（0=默认居中，1/2 上移，3/4 下移）。 */
    subtitleOffsetLevel: Int = 0,
    /** 字幕抬升切换回调（OK 循环档位，立即生效并持久化）。 */
    onSubtitleOffsetLevelChanged: (Int) -> Unit = {},
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
        val tabFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanosCompat()
            runCatching { tabFocus.requestFocus() }
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
                // 水平/上下内边距在 scroll 之外 = 固定框；内容首尾留白在 scroll 之内
                // （随内容滚）：标题不顶上边框、末行不贴底边框。
                // 「选集列表最上最下显示不全」：行焦点进视口时
                // bring-into-view 把它贴到滚动视口边缘（= 抽屉圆角边），看着像被切——
                // 视口再内缩 [LIST_EDGE_INSET]，行贴边时距抽屉边框仍有呼吸位
                .padding(horizontal = 24.dp)
                .padding(vertical = LIST_EDGE_INSET)
                .verticalScroll(rememberScrollState())
                .padding(top = 24.dp, bottom = 28.dp)
                // 抽屉是独立焦点组：内部导航由组内节点完成，不向控制条逃逸
                .focusGroup()
                // 四向困焦：列表翻到顶/底、tab 行到头时
                // 焦点搜索会逃出抽屉落到根节点（高亮凭空消失）——exit 一律取消
                // 边界处焦点停在原地；抽屉内芯片↔内容的上下移动不受影响
                .focusProperties { exit = { FocusRequester.Cancel } },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.player_drawer_title),
                style = NovumType.Section,
                color = NovumPalette.Paper,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // tab 行：选中 = 深底 + 短线；焦点 = 白底黑字（NovumChip 统一语义）。
            //      左右键交给焦点系统在芯片间移焦（容器已四向困焦，到头停住）
            //      OK 才经 onClick 切 tab 并更新内容（的预期模型）
            //      tabFocus 固定挂在 initialTab 芯片上——挂在 currentTab 上会在切
            //      tab 时改写聚焦节点的修饰链，焦点凭空消失 ----
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // 焦点上报给父层：焦点在 tab 行时根节点放行左右键（芯片间移焦），
                // 在内容行时根节点吞掉左右键（防逃逸到控制条）
                modifier = Modifier.onFocusChanged { onTabsFocusChanged(it.hasFocus) },
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
            Spacer(modifier = Modifier.height(4.dp))

            when (currentTab) {
                DrawerTab.Episodes -> EpisodesTab(
                    episodes = episodes,
                    currentEpisodeId = currentEpisodeId,
                    onSelectEpisode = onSelectEpisode,
                )

                DrawerTab.Subtitle -> SubtitleTab(
                    textTracks = textTracks,
                    subtitleDelayMs = subtitleDelayMs,
                    onSubtitleDelayChanged = onSubtitleDelayChanged,
                    subtitleSize = subtitleSize,
                    onSubtitleSizeChanged = onSubtitleSizeChanged,
                    subtitleOffsetLevel = subtitleOffsetLevel,
                    onSubtitleOffsetLevelChanged = onSubtitleOffsetLevelChanged,
                    onSelectText = onSelectText,
                )

                DrawerTab.Audio -> AudioTab(
                    audioTracks = audioTracks,
                    passthrough = passthrough,
                    audioDelayMs = audioDelayMs,
                    onAudioDelayChanged = onAudioDelayChanged,
                    onSelectAudio = onSelectAudio,
                    onTogglePassthrough = onTogglePassthrough,
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
    }
}

/**
 * 选集 tab：当前季分集列表，OK 换集。
 *
 * 行文与详情页选集卡同口径「SxxExx · 集标题」；当前集以选中态标记
 * 有进度的集在行尾显示剩余时长、已看完显示「已看完」。换集由外部走
 * `onOpenEpisode`（替换当前播放页，不叠加返回栈）。
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
        HintRow(stringResource(R.string.player_drawer_empty))
        return
    }
    SectionLabel(stringResource(R.string.detail_episodes))
    episodes.forEach { episode ->
        val runtime = episode.runtimeMinutes
        val progress = episode.progressRatio
        val value = when {
            episode.played -> stringResource(R.string.detail_episode_played)
            progress != null && runtime != null -> stringResource(
                R.string.detail_episode_remaining,
                ((runtime * (1f - progress)).toInt()).coerceAtLeast(1),
            )
            else -> null
        }
        DrawerRow(
            label = "S%02dE%02d".format(episode.seasonIndex, episode.index % 100) +
                " · " + episode.title,
            value = value,
            selected = episode.id == currentEpisodeId,
            onClick = { onSelectEpisode(episode.id) },
        )
    }
}

/** 字幕 tab：字幕延迟微调 + 字幕字号调节 + 字幕高度调节 + 轨道列表（首位「关闭字幕」）。 */
@Composable
private fun SubtitleTab(
    textTracks: List<TrackOption>,
    subtitleDelayMs: Long,
    onSubtitleDelayChanged: (Long) -> Unit,
    subtitleSize: Int,
    onSubtitleSizeChanged: (Int) -> Unit,
    subtitleOffsetLevel: Int,
    onSubtitleOffsetLevelChanged: (Int) -> Unit,
    onSelectText: (TrackOption) -> Unit,
) {
    SectionLabel(stringResource(R.string.player_drawer_subtitle))
    DrawerRow(
        label = stringResource(R.string.player_drawer_subtitle_delay),
        value = formatDelay(subtitleDelayMs),
        selected = subtitleDelayMs != 0L,
        onClick = { onSubtitleDelayChanged(if (subtitleDelayMs >= 2_000L) -2_000L else subtitleDelayMs + 100L) },
    )
    // 字幕字号：OK 循环 小→中→大→超大→小，与延迟微调同交互
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
    )
    // 字幕高度：五档以默认居中——OK 循环 默认→高一→高两→
    // 低一→低两→默认（负档在满屏画面触底钳制，黑边内可继续下沉）
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
        onClick = { onSubtitleOffsetLevelChanged(if (subtitleOffsetLevel >= 4) 0 else subtitleOffsetLevel + 1) },
    )
    textTracks.forEach { option ->
        DrawerRow(
            label = option.label,
            value = null,
            selected = option.selected,
            onClick = { onSelectText(option) },
        )
    }
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
) {
    SectionLabel(stringResource(R.string.player_drawer_passthrough))
    DrawerRow(
        label = stringResource(R.string.player_drawer_passthrough),
        value = if (passthrough) {
            stringResource(R.string.settings_state_on)
        } else {
            stringResource(R.string.settings_state_off)
        },
        selected = passthrough,
        onClick = onTogglePassthrough,
    )
    // 音频延迟（音画同步微调）：PCM 路径生效（直通 bitstream 不经处理器）
    DrawerRow(
        label = stringResource(R.string.player_drawer_audio_delay),
        value = formatDelay(audioDelayMs),
        selected = audioDelayMs != 0L,
        onClick = { onAudioDelayChanged(if (audioDelayMs >= 2_000L) -2_000L else audioDelayMs + 100L) },
    )
    SectionLabel(stringResource(R.string.player_drawer_audio))
    if (audioTracks.isEmpty()) {
        HintRow(stringResource(R.string.player_drawer_empty))
    } else {
        audioTracks.forEach { option ->
            DrawerRow(
                label = option.label,
                value = null,
                selected = option.selected,
                onClick = { onSelectAudio(option) },
            )
        }
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
    SectionLabel(stringResource(R.string.player_info_title))
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
        listOfNotNull(
            audioLabel.ifBlank { null },
            spec?.audioChannels?.let { String.format(channelsTemplate, it) },
        ).joinToString(" · ").ifBlank { null },
    )
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
        HintRow(stringResource(R.string.player_drawer_empty))
        return
    }
    SectionLabel(stringResource(R.string.player_info_chapters))
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
) {
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    NovumOptionRow(
        text = label,
        value = value,
        selected = selected,
        onClick = onClick,
        focusRequester = focusRequester,
        onFocused = { focused ->
            if (focused) scope.launch { bringIntoView.bringIntoView() }
        },
        modifier = Modifier.bringIntoViewRequester(bringIntoView),
    )
}

/** 分区小标题。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = NovumType.Caption,
        color = NovumPalette.PaperMuted,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp),
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

/** 延迟毫秒转为展示文案；0 显示「关」。 */
@Composable
private fun formatDelay(ms: Long): String = if (ms == 0L) {
    stringResource(R.string.player_delay_off)
} else {
    val signed = if (ms > 0) "+$ms" else "$ms"
    "${signed}ms"
}

/** 抽屉宽度（加宽：容纳「S01E03 · 分集标题」+ 行尾剩余时长不截断）。 */
private val DRAWER_WIDTH = 480.dp

/** 抽屉上下内收：不贴屏幕边缘（由 48 收到 32，列表更长）。 */
private val DRAWER_VERTICAL_INSET = 32.dp

/** 滚动视口上下内缩：行贴视口边时仍与抽屉边框留呼吸位（修「列表最上最下显示不全」）。 */
private val LIST_EDGE_INSET = 20.dp

/** 滑入滑出时长。 */
private const val DRAWER_ANIM_MS = 220
