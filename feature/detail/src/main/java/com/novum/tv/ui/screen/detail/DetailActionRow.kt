package com.novum.tv.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.Season
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumIconButton
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/**
 * 操作行：立即播放/继续播放 + 从头播放 + **选季 ▾** +
 * 收藏 + 标为已看/未看。
 *
 * **播放按钮只有两态文案**——「立即播放 / 继续播放」，不显示集号。
 * 多版本时若优先视频质量策略为「每次询问」，播放点击先弹版本层；长按播放/从头播放可手动选版本。
 * 已看/未看按钮切换当前集状态；遥控器菜单键或长按 OK 呼出整季标记浮层。
 * 电影的状态操作作用于整个条目。
 * 剧级态与集级态共用同一操作行：
 *   播放目标/已看目标 = 当前集（选中集或默认目标集）。
 *
 * 文本按钮使用 [NovumType.ButtonLg]（24sp）、
 * [NovumDimens.ButtonHeight]（48dp）+ [NovumDimens.ButtonWidth]（160dp）——
 * 与首页双 CTA 同规；图标钮不变。
 *
 * @param data 详情数据。
 * @param state 页面状态（选季钮的未看圆点用）。
 * @param selectedSeason 当前选中季（剧集多季时显示在下拉钮上）。
 * @param episode 当前集（剧集 = 选中集或默认目标集；电影为 null）。
 * @param playFocus 播放按钮的焦点索取器。
 * @param onPlay 播放回调（参数 = 起播位置覆盖毫秒；null 走续播规则、0 从头播放）。
 * @param onPlayLongClick 播放按钮长按：手动选择本次播放版本（多版本才挂）；null = 无。
 * @param onReplayLongClick 从头播放按钮长按：同上。
 * @param onToggleFavorite 收藏切换。
 * @param onTogglePlayed 已看/未看切换（目标 = 当前集或整个条目）。
 * @param onMarkSeasonMenuOpen 呼出整季标记浮层（已看图标的菜单键/长按；电影无季不挂）
 *   参数 = 是否长按进入（长按的浮层要吞掉「松手」那一下 OK）。
 * @param onOpenSeasonMenu 呼出选季浮层。
 */
@Composable
internal fun ActionRow(
    data: MediaDetail,
    state: DetailUiState,
    selectedSeason: Season?,
    episode: Episode?,
    playFocus: FocusRequester,
    onPlay: (startPositionMs: Long?) -> Unit,
    onPlayLongClick: (() -> Unit)?,
    onReplayLongClick: (() -> Unit)?,
    onToggleFavorite: () -> Unit,
    onTogglePlayed: () -> Unit,
    onMarkSeasonMenuOpen: (byLongPress: Boolean) -> Unit,
    onOpenSeasonMenu: () -> Unit,
) {
    // 剧集分集尚未加载/加载失败时无可播目标：主按钮禁用占位（防误播剧集 id），
    // 已看按钮同理禁用——否则会退化成「整条剧集标记已看」这种批量语义
    val playable = !data.isSeries || episode != null
    // 是否已有观影进度（决定「继续播放」文案与「从头播放」按钮是否出现）
    val resume = (episode?.progressMs ?: data.resumePositionMs) > 0L
    val playedState = episode?.played ?: data.played
    val playLabel = when {
        // 剧集但分集尚未加载完时，不要先显示「立即播放」再闪成别的
        // （「进入页面闪一下播放两个字」）——先显示省略号占位
        data.isSeries && episode == null -> "…"
        resume -> stringResource(R.string.detail_resume)
        else -> stringResource(R.string.detail_play)
    }

    // 操作按钮成组：播放为文字主按钮
    // 从头播放/收藏/标为已看改**图标按钮**（语义靠 contentDescription 兜底）
    // 「从头播放」只在有进度时渲染（标为已看后进度清零、按钮消失、右侧左移）。
    // 每个按钮包 key：标为已看会让「从头播放」消失、Row 兄弟槽位重排
    // 不加 key 的话聚焦节点被当作新实例重建、焦点凭空丢失。
    // 收藏/已看请求期间**保持可聚焦**（enabled 不随 busy 翻转）——busy 时点击
    // 由 ViewModel 守卫吞掉；enabled=false 会让聚焦节点瞬间失焦、焦点甩出。
    // 间距 20dp：原 Gutter 12dp 在播放钮聚焦放大（1.10）
    // 时贴到旁边图标——对齐首页 Hero 双 CTA 的 20dp 口径。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ACTION_ROW_GAP),
    ) {
        key("play") {
            NovumButton(
                onClick = { onPlay(null) },
                onLongClick = onPlayLongClick,
                enabled = playable,
                modifier = Modifier
                    .width(NovumDimens.ButtonWidth)
                    .focusRequester(playFocus),
                centerContent = true,
            ) {
                Text(
                    text = playLabel,
                    style = NovumType.ButtonLg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (resume) {
            key("replay") {
                NovumIconButton(
                    icon = NovumIcons.Replay,
                    contentDescription = stringResource(R.string.detail_play_from_head),
                    onClick = { onPlay(0L) },
                    onLongClick = onReplayLongClick,
                )
            }
        }
        // 选季按钮；单季剧集不显示。
        if (data.isSeries && (state.seasons.size > 1) && (selectedSeason != null)) {
            key("season") {
                NovumButton(
                    onClick = onOpenSeasonMenu,
                    modifier = Modifier.width(NovumDimens.ButtonWidth),
                    centerContent = true,
                ) {
                    Text(
                        text = seasonDisplayName(selectedSeason),
                        style = NovumType.ButtonLg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 当前季有新集（未看 > 0）时在选季钮上加圆点标记
                    if ((selectedSeason.unplayedCount ?: 0) > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(NovumPalette.Paper),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = NovumIcons.ChevronDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        key("favorite") {
            NovumIconButton(
                // 已收藏显示红色实心红心；未收藏显示白色描边。
                tintOverride = if (data.isFavorite) NovumPalette.Danger else null,
                icon = if (data.isFavorite) NovumIcons.FavoriteFilled else NovumIcons.Favorite,
                contentDescription = stringResource(
                    if (data.isFavorite) R.string.detail_favorited else R.string.detail_favorite,
                ),
                onClick = onToggleFavorite,
                selected = data.isFavorite,
            )
        }
        key("played") {
            // 已看/未看使用空心对号/实心绿对号，与选集卡角标保持一致。
            // 点击切换当前集状态；遥控器菜单键或长按 OK
            // 呼出整季标记浮层（电影没有季，两者都不挂）
            NovumIconButton(
                // 已看态实心对号染 Success 绿（与收藏钮的已收藏红同一套 tint 范式），
                // 未看态空心对号常规纸白
                icon = if (playedState) NovumIcons.CheckFilled else NovumIcons.Check,
                tintOverride = if (playedState) NovumPalette.Success else null,
                contentDescription = stringResource(
                    if (playedState) R.string.detail_mark_unplayed else R.string.detail_mark_played,
                ),
                onClick = onTogglePlayed,
                onLongClick = if (data.isSeries) ({ onMarkSeasonMenuOpen(true) }) else null,
                enabled = playable,
                modifier = Modifier.onPreviewKeyEvent { event ->
            // 在 preview 阶段拦截 Menu；图标钮自身不消费该键：
                    // onKeyDown 类的后置拦截会被 clickable 的焦点容器吞掉）
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Menu && data.isSeries) {
                        onMarkSeasonMenuOpen(false)
                        true
                    } else {
                        false
                    }
                },
            )
        }
    }
}

/**
 * 详情元信息段（与首页 Hero 同款口径）：年份 · 类型 · 分级 ·
 * 分辨率 · 片长 · 评分，详情页在尾部追加季数与连载状态。
 *
 * 不展示「N 集」：服务端 ChildCount 对剧集不可靠，集数由选集面板呈现。
 *
 * 文本按钮使用 [NovumType.ButtonLg]（24sp）、
 * [NovumDimens.ButtonHeight]（48dp）+ [NovumDimens.ButtonWidth]（160dp）——
 * 与首页双 CTA 同规；图标钮不变。
 *
 * @param data 详情数据。
 * @param seasonCount 季数；仅剧集且大于 1 时展示。
 */
@Composable
internal fun detailMetaParts(data: MediaDetail, seasonCount: Int): List<String> {
    val typeLabel = when (data.type) {
        "Movie" -> stringResource(R.string.home_entry_movie)
        "Series" -> stringResource(R.string.home_entry_series)
        // 分集条目（罕见：深链直达单集）类型位给 SxxExx 集号
        "Episode" -> run {
            val s = data.seasonIndex
            val e = data.index
            if (s != null && e != null) "S%02dE%02d".format(s, e % 100) else null
        }
        else -> null
    }
    val status = when (data.status) {
        "Continuing" -> stringResource(R.string.detail_status_continuing)
        "Ended" -> stringResource(R.string.detail_status_ended)
        else -> null
    }
    val runtime = data.runtimeMinutes
        ?.takeIf { it > 0 }
        ?.let { stringResource(R.string.detail_minutes, it) }
    val seasons = seasonCount
        .takeIf { it > 1 }
        ?.let { stringResource(R.string.detail_seasons_count, it) }
    return listOfNotNull(
        data.year?.toString(),
        typeLabel,
        data.officialRating,
        data.resolution,
        runtime,
        // 评分展示与首页 Hero 一致，不带星号前缀。
        data.rating?.takeIf { it > 0f }?.let { String.format(java.util.Locale.US, "%.1f", it) },
        seasons,
        status,
    )
}


/** 操作行按钮/图标间距：对齐首页 Hero 双 CTA 的 20dp。 */
private val ACTION_ROW_GAP = 20.dp
