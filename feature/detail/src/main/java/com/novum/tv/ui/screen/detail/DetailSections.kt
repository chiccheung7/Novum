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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import androidx.tv.material3.Icon
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.CastMember
import com.novum.tv.model.Episode
import com.novum.tv.model.Season
import com.novum.tv.ui.component.LANDSCAPE_ASPECT
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumCard
import com.novum.tv.ui.component.NovumFocusScrollScope
import com.novum.tv.ui.component.NovumAvatar
import com.novum.tv.ui.component.rememberMediaCardWidth
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
 * 分集卡保留简短简介，按一次 OK 直接起播；卡行下不重复整段分集信息。
 * 卡文显示集号与标题，以及分集简介；卡图左上角显示剧集 clearlogo。
 * 右上角以绿色对勾标记已看；已看卡片压暗，焦点由卡片描边表达。
 *
 * @param episodes 当前季的分集。
 * @param episodesEmpty 是否无分集（且未失败）。
 * @param episodesFailed 加载失败（内联重试）。
 * @param firstUnplayedId 「下一集」/切季首焦目标 id。
 * @param anchorEpisodeId 进场定位用的当前集 id；分集到达后滚动到该卡。null 或不在列表时不滚。
 * @param selectedEpisodeId 当前预览集 id；用于当前集的焦点索取。
 * @param selectedSeason 当前所选季；多季剧集在选集标题旁提供切季入口。
 * @param onOpenSeasonMenu 打开季选择菜单。
 * @param logoUrl 剧集 clearlogo（卡图左上角；同剧所有集共用一张）；null 不渲染。
 * @param episodeFocus 切季后落到第一未看集的索取器。
 * @param contextFocus 当前选中集的焦点索取器。
 * @param onCardClick 点击或确认分集卡时直接起播该集。
 * @param onFocusSelect 焦点落到卡上时选择该集。
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
    selectedSeason: Season? = null,
    onOpenSeasonMenu: () -> Unit = {},
    logoUrl: String?,
    episodeFocus: FocusRequester,
    contextFocus: FocusRequester,
    onCardClick: (String) -> Unit,
    onFocusSelect: (String) -> Unit = {},
    onRetry: () -> Unit,
    upFocus: FocusRequester? = null,
) {
    val cardWidth = rememberMediaCardWidth(LANDSCAPE_ASPECT)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = NovumDimens.SafeHorizontal,
                    end = NovumDimens.SafeHorizontal,
                    bottom = NovumDimens.TitleToRow,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter),
        ) {
            Text(
                text = stringResource(R.string.detail_episodes),
                style = NovumType.Section,
                color = NovumPalette.Paper,
                modifier = Modifier.weight(1f),
            )
            if (selectedSeason != null) {
                NovumButton(onClick = onOpenSeasonMenu) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = seasonDisplayName(selectedSeason),
                            style = NovumType.ButtonLg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        selectedSeason.unplayedCount?.takeIf { it > 0 }?.let { count ->
                            Text(
                                text = stringResource(R.string.detail_season_unplayed, count),
                                style = NovumType.Caption,
                                maxLines = 1,
                            )
                        }
                        Icon(
                            imageVector = NovumIcons.ChevronDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
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
        // 分集晚到时按位置补滚；观看进度更新不能把用户滚回进场集。
        // scrollToItem 会将锚点贴到视口物理左缘；再补回 SafeHorizontal，使其停在静止对齐线上。
        val alignPx = with(LocalDensity.current) { NovumDimens.SafeHorizontal.roundToPx() }
        val anchorIndex = episodes.indexOfFirst { it.id == anchorEpisodeId }
        LaunchedEffect(anchorEpisodeId, anchorIndex) {
            if (anchorIndex >= 0) {
                runCatching {
                    stripListState.scrollToItem(anchorIndex)
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
                // 选集横卡与首页续播、分类页共用视口宽度和行内间距。
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
                        width = cardWidth,
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
                            // 当前选中集挂焦点索取器。
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
 * **卡图右上角**眼睛表示未看，绿色实心对勾表示已看。
 * **聚焦**由卡片标准描边表示；有进度的集显示进度条与剩余时长。
 *   已看分集仅剧照降亮度，集标题与简介保留可读性。
 */
@Composable
private fun EpisodeCard(
    episode: Episode,
    width: Dp,
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
        title = "S%02dE%02d".format(episode.seasonIndex, episode.index) + " · " + episode.title,
        // 分集简介（卡文行 2，两行省略）
        subtitle = episode.overview,
        width = width,
        // 未看眼睛、已看绿勾：形状和颜色均区分状态。
        badge = null,
        badgeIcon = if (episode.played) NovumIcons.CheckFilled else NovumIcons.Eye,
        badgeIconTint = if (episode.played) NovumPalette.Success else null,
        badgeIconContentDescription = stringResource(
            if (episode.played) R.string.detail_episode_played else R.string.detail_episode_unplayed,
        ),
        // 优先使用分集自己的 Primary 图（每集不同的剧照/截图，便于区分选集）；
        // 缺失时回落到剧集级 16:9 主视觉。
        imageUrl = episode.imageUrl ?: episode.seriesWideUrl,
        logoUrl = logoUrl,
        // 未看完的集在卡底画进度条（与首页继续观看行同款 3dp 白条）
        progress = progress,
        progressText = remainingText,
        subtitleMaxLines = EPISODE_OVERVIEW_LINES,
        // 简介保持可读字号，但亮度低于集标题；已看仅压暗剧照，不压暗文字。
        subtitleStyle = NovumType.Caption,
        subtitleColor = NovumPalette.PaperFaint,
        imageAlpha = if (episode.played) 0.55f else 1f,
        // 焦点由标准卡片描边表达；选中集不再额外叠加白色竖条。
        glowing = false,
        onClick = onClick,
        modifier = modifier,
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
