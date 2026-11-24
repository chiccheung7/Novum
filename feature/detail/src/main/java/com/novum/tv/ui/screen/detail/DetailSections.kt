package com.novum.tv.ui.screen.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.CastMember
import com.novum.tv.model.Episode
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumCard
import com.novum.tv.ui.component.NovumFocusScrollScope
import com.novum.tv.ui.component.NovumAvatar
import com.novum.tv.ui.component.tvFocusScale
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 详情页滚动区段集合：
 * 选集行（含分集卡）与演职员行（含头像）。
 */

/**
 * 剧集区：选集标题与横向单行分集卡，使用与首页内容行一致的遥控器导航方式。
 *
 * 加载失败时显示内联重试；本季无分集时显示明确提示，焦点留在本区域。
 *
 * 首次点击卡片切换当前集，再次按 OK 起播。
 * 卡文显示集号与标题，以及分集简介；卡图左上角显示剧集 clearlogo。
 * 右上角显示已看/未看对号；已看卡片压暗，当前选中态由卡片描边表达。
 *
 * @param episodes 当前季的分集。
 * @param episodesEmpty 是否无分集（且未失败）。
 * @param episodesFailed 加载失败（内联重试）。
 * @param firstUnplayedId 「下一集」/切季首焦目标 id。
 * @param anchorEpisodeId 进场定位用的当前集 id；分集到达后滚动到该卡。null 或不在列表时不滚。
 * @param selectedEpisodeId 当前选中集 id：该卡显示「已选中」角标
 *   （非彩色，压过「下一集」角标——正在查看比下一集更重要）。
 * @param logoUrl 剧集 clearlogo（卡图左上角；同剧所有集共用一张）；null 不渲染。
 * @param episodeFocus 切季后落到第一未看集的索取器。
 * @param contextFocus 选中集的索取器：返回键清选中时把焦点还给这张卡。
 * @param onCardClick 点击分集卡时切换当前集；已选中卡再次点击时起播。
 * @param onFocusSelect 焦点落到卡上时选择该集；页面负责压制 Back 后回焦产生的再次选择。
 * @param onRetry 失败重试。
 */
@Composable
internal fun EpisodeStrip(
    episodes: List<Episode>,
    episodesEmpty: Boolean,
    episodesFailed: Boolean,
    firstUnplayedId: String?,
    anchorEpisodeId: String?,
    selectedEpisodeId: String?,
    logoUrl: String?,
    episodeFocus: FocusRequester,
    contextFocus: FocusRequester,
    onCardClick: (String) -> Unit,
    onFocusSelect: (String) -> Unit = {},
    onRetry: () -> Unit,
    upFocus: FocusRequester? = null,
) {
    Column {
        Text(
            text = stringResource(R.string.detail_episodes),
            style = NovumType.Section,
            color = NovumPalette.Paper,
            // 分集卡聚焦放大（1.1x）上缘逼近标题——按行标题口径留 TitleToRow
            modifier = Modifier.padding(
                start = NovumDimens.SafeHorizontal,
                bottom = NovumDimens.TitleToRow,
            ),
        )
        if (episodesEmpty) {
            Text(
                text = stringResource(R.string.detail_no_episodes),
                style = NovumType.Caption,
                color = NovumPalette.PaperMuted,
                modifier = Modifier.padding(start = NovumDimens.SafeHorizontal, top = 8.dp),
            )
        } else if (episodesFailed) {
            Row(
                modifier = Modifier.padding(start = NovumDimens.SafeHorizontal, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
            ) {
                Text(
                    text = stringResource(R.string.detail_episodes_failed),
                    style = NovumType.Caption,
                    color = NovumPalette.PaperMuted,
                )
                NovumButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.state_retry), style = NovumType.ButtonLg)
                }
            }
        }
        if (episodes.isEmpty()) return@Column
        // 选集行使用全宽视口；contentPadding 与首页行共用 68dp 静止对齐线。
        // 滚动规格将该对齐线作为边距下限，使首尾卡聚焦后仍与标题对齐。
        val stripPadding = PaddingValues(
            start = NovumDimens.SafeHorizontal,
            end = NovumDimens.SafeHorizontal,
            top = NovumDimens.FocusBleed,
            bottom = NovumDimens.FocusBleed,
        )
        val stripListState = rememberLazyListState()
        // 进场定位「当前集」——滚到该卡；锚点为 null（切季/全部看完）不滚。
        // 键含 episodes：分集晚到（锚点先设）时到达后补滚。
        // scrollToItem 会将锚点贴到视口物理左缘；再补回 SafeHorizontal，使其停在静止对齐线上。
        val alignPx = with(LocalDensity.current) { NovumDimens.SafeHorizontal.roundToPx() }
        LaunchedEffect(anchorEpisodeId, episodes) {
            val index = episodes.indexOfFirst { it.id == anchorEpisodeId }
            if (index >= 0) {
                runCatching {
                    stripListState.scrollToItem(index)
                    stripListState.scrollBy(-alignPx.toFloat())
                }
            }
        }
        NovumFocusScrollScope(
            focusMargin = NovumDimens.FocusBleed,
            contentPadding = stripPadding,
        ) {
            LazyRow(
                state = stripListState,
                // 选集卡使用 320dp 标准卡，行内间距与首页行相同。
                horizontalArrangement = Arrangement.spacedBy(NovumDimens.CardGap),
                contentPadding = stripPadding,
                modifier = Modifier
                    .fillMaxWidth()
                    // 分集行向上显式指向操作行（跨信息区几何搜索可能失败）
                    .then(
                        if (upFocus != null) {
                            Modifier.focusProperties { up = upFocus }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                items(items = episodes, key = { it.id }) { episode ->
                    val selected = episode.id == selectedEpisodeId
                    EpisodeCard(
                        episode = episode,
                        selected = selected,
                        logoUrl = logoUrl,
                        // 页面根据当前选中集决定切换语境或起播。
                        onClick = { onCardClick(episode.id) },
                        // 焦点落到卡上即刷新该集信息，图标与背景随当前集更新。
                        modifier = Modifier
                            .onFocusChanged { if (it.isFocused) onFocusSelect(episode.id) }
                            .then(
                                if (episode.id == firstUnplayedId) {
                                    Modifier.focusRequester(episodeFocus)
                                } else {
                                    Modifier
                                },
                            )
                            // 选中集挂回焦索取器：返回键清选中时焦点落回
                            .then(
                                if (selected) {
                                    Modifier.focusRequester(contextFocus)
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 分集卡使用 [NovumCard] 的标准卡样式。
 *
 * 四处：
 * **卡文两行** = 「S01E03 · 集标题」/ 分集简介（**两行**省略）——剧集详情页
 *   信息块恒为剧集内容后，分集标题与简介只在卡下方展示
 * **卡图左上角**挂剧集 clearlogo（同剧共用），图上的文字标题不叠加
 * **卡图右上角**显示已看/未看对号（未看为空心，已看为实心绿）；状态角标恒显。
 * **已选中**由图区 Accent 描边框表示；有进度的集显示进度条与剩余时长。
 *   已看分集整卡降亮度。
 */
@Composable
private fun EpisodeCard(
    episode: Episode,
    selected: Boolean,
    logoUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val runtime = episode.runtimeMinutes
    val progress = episode.progressRatio
    // 剩余时长（有进度才有）：卡右下角小字，与进度条同一处表达「看到哪了」
    val remainingText = if (progress != null && runtime != null) {
        stringResource(
            R.string.detail_episode_remaining,
            ((runtime * (1f - progress)).toInt()).coerceAtLeast(1),
        )
    } else {
        null
    }
    NovumCard(
        // 集号 + 集标题（卡文行 1）；元信息（时长/状态）改由角标与进度表达
        title = "S%02dE%02d".format(episode.seasonIndex, episode.index % 100) + " · " + episode.title,
        // 分集简介（卡文行 2，两行省略）
        subtitle = episode.overview,
        // 角标表示已看/未看状态：未看为空心对号，已看为染 Success 绿的实心对号。
        badge = null,
        badgeIcon = if (episode.played) NovumIcons.CheckFilled else NovumIcons.Check,
        badgeIconTint = if (episode.played) NovumPalette.Success else null,
        // 优先使用分集自己的 Primary 图（每集不同的剧照/截图，便于区分选集）；
        // 缺失时回落到剧集级 16:9 主视觉。
        imageUrl = episode.imageUrl ?: episode.seriesWideUrl,
        logoUrl = logoUrl,
        // 未看完的集在卡底画进度条（与首页继续观看行同款 3dp 白条）
        progress = progress,
        progressText = remainingText,
        subtitleMaxLines = EPISODE_OVERVIEW_LINES,
        // 已选中时显示图区描边框。
        glowing = selected,
        onClick = onClick,
        modifier = modifier
            // 已看完整卡压暗（沿用原语义）；看了一部分不压暗（进度条已表达状态）
            .graphicsLayer { if (episode.played) alpha = 0.45f },
    )
}

/** 演职员横向列表（头像可聚焦点击 → 人物作品页）。 */
@Composable
internal fun CastRow(
    cast: List<CastMember>,
    onOpenPerson: (personId: String, personName: String) -> Unit,
) {
    val listState = rememberLazyListState()
    // 切季换人后回到行首：沿用上一季的滚动位置会从半截开始展示新演员
    LaunchedEffect(cast) {
        runCatching { listState.scrollToItem(0) }
    }
    Column {
        Text(
            text = stringResource(R.string.detail_cast),
            // 与「选集」区块标题同级同权（Section + Paper）
            style = NovumType.Section,
            color = NovumPalette.Paper,
            // 详情页是整屏背景铺底的页面，没有外层 SafeArea，横向安全区由各区块自己负责
            modifier = Modifier.padding(start = NovumDimens.SafeHorizontal),
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 头像本身不可聚焦，但契约要求页面内所有 LazyRow 统一接入滚动 scope：
        // 空专注时它是惰性的，不会改变滚动行为（FocusContractTest，家规）
        NovumFocusScrollScope {
            LazyRow(
                state = listState,
                // 夹住式边距（Google TV 风格）：视口内缩 46dp，切边恒定不贴物理边
                // 首卡 = 68 - 10（FocusBleed）+ 10 = 68dp，与标题左界对齐
                modifier = Modifier.padding(horizontal = NovumDimens.SafeHorizontal - NovumDimens.FocusBleed),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(
                    start = NovumDimens.FocusBleed,
                    end = NovumDimens.FocusBleed,
                    top = NovumDimens.FocusBleed,
                    bottom = NovumDimens.FocusBleed,
                ),
            ) {
                // 同名同角色的群众演员很常见，必须用 id 作 key，否则重复 key 会让 LazyRow 崩溃
                items(items = cast, key = { it.id ?: (it.name + it.role.orEmpty()) }) { member ->
                    CastAvatar(member = member, onOpenPerson = onOpenPerson)
                }
            }
        }
    }
}

/** 单个演职员：圆形头像 + 姓名 + 角色；有人物 id 时可点击跳作品页。 */
@Composable
private fun CastAvatar(
    member: CastMember,
    onOpenPerson: (personId: String, personName: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(CAST_ITEM_WIDTH)
            // 有人物 id 时可点击；tvFocusScale 提供聚焦反馈。
            .tvFocusScale()
            .clickable(enabled = member.id != null) {
                member.id?.let { id -> onOpenPerson(id, member.name) }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 无图/加载中由 NovumAvatar 画"首字 + 低饱和底色"占位：
        // 一排演员里缺图的那几个不再是一块空白，加载完成再淡入真图
        NovumAvatar(
            url = member.imageUrl,
            name = member.name,
            size = CAST_AVATAR_SIZE,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = member.name,
            style = NovumType.Label,
            color = NovumPalette.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val role = member.role
        if (!role.isNullOrBlank()) {
            Text(
                text = role,
                style = NovumType.Label,
                color = NovumPalette.PaperMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分集简介在卡文里的行数（一行太少，给两行）。 */
private const val EPISODE_OVERVIEW_LINES = 2

/** 演职员头像尺寸。 */
private val CAST_AVATAR_SIZE = 80.dp

/** 演职员单项宽度。 */
private val CAST_ITEM_WIDTH = 104.dp
