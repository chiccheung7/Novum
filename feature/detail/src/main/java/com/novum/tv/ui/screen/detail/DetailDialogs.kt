package com.novum.tv.ui.screen.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.novum.tv.ui.component.NovumOptionListDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.displayLine
import com.novum.tv.model.Season
import com.novum.tv.ui.component.NovumOptionRow
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.novumGlassPanel
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.launch

/**
 * 详情页浮层集合：
 * 版本选择 / 选季 / 整季标记，共用的 Dialog 独立窗口模式与行组件。
 */

/**
 * 版本选择浮层（多版本条目/分集）：序号列表用 designsystem 的
 * [NovumOptionListDialog]（与播放页抽屉换版本的浮层同源）。
 *
 * 行文 = 「电影名/剧名 · SxxExx · 清晰度 ·
 * 动态范围 · 码率」——前缀（[titlePrefix]）由页面按当前条目拼，规格三段取自
 * [MediaVersionOption.specs]；服务端没给规格时退回版本名 label。
 *
 * @param versions 候选版本。
 * @param titlePrefix 条目前缀（电影名/剧名，分集再带 SxxExx）。
 * @param selectedId 当前生效版本的 sourceId（高亮 + 初始焦点）。
 * @param onSelect 选中回调（携带 sourceId）。
 * @param onDismiss 关闭回调。
 * @param awaitKeyRelease 长按呼出时传 true：吞掉「松手」那一下 OK（
 *   「松开长按就直接选中播放了」）。
 */
@Composable
internal fun VersionDialog(
    versions: List<MediaVersionOption>,
    titlePrefix: String,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    awaitKeyRelease: Boolean = false,
) {
    val lines = versions.map { it.displayLine(titlePrefix = titlePrefix) }
    val selectedIndex = versions.indexOfFirst { it.sourceId == selectedId }
        .takeIf { it >= 0 }
        ?: 0
    NovumOptionListDialog(
        title = stringResource(R.string.detail_pref_dialog_version),
        options = lines,
        selectedIndex = selectedIndex,
        onSelect = { index -> versions.getOrNull(index)?.let { onSelect(it.sourceId) } },
        onDismiss = onDismiss,
        awaitKeyRelease = awaitKeyRelease,
    )
}

/**
 * 季选择浮层：屏幕居中对话框，焦点被困在菜单内，Back 关闭归还焦点。
 *
 * 用 `Dialog` 独立窗口而非页面叠层：焦点天然被困（F4，与用户菜单同一模式）。
 * 初始焦点落在当前选中季；选中季以强调色「✓」标记。
 */
@Composable
internal fun SeasonMenuDialog(
    seasons: List<Season>,
    selectedSeasonId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedRowFocus = remember { FocusRequester() }
    val focusSeasonId = selectedSeasonId?.takeIf { id -> seasons.any { it.id == id } }
        ?: seasons.firstOrNull()?.id
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (focusSeasonId != null) {
            NovumInitialFocus(selectedRowFocus, key = focusSeasonId)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NovumPalette.Scrim.copy(alpha = MENU_SCRIM_ALPHA))
                .padding(vertical = NovumDimens.SafeVertical),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(SEASON_MENU_WIDTH)
                    .novumGlassPanel()
                    .padding(vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.detail_select_season),
                    style = NovumType.BodyStrong,
                    color = NovumPalette.Paper,
                    modifier = Modifier.padding(
                        horizontal = NovumDimens.OverlayPaddingHorizontal,
                        vertical = NovumDimens.OverlayPaddingVertical,
                    ),
                )
                seasons.forEach { season ->
                    SeasonMenuRow(
                        season = season,
                        selected = season.id == selectedSeasonId,
                        focusRequester = selectedRowFocus.takeIf { season.id == focusSeasonId },
                        onClick = { onSelect(season.id) },
                    )
                }
            }
        }
    }
}

/**
 * 整季标记浮层：已看/未看图标的菜单键或长按 OK 呼出，提供本季标为已看/未看两项。
 * Dialog 独立窗口困焦点，初始焦点落第一行；选择后由
 * [DetailViewModel.markSeasonPlayed] 逐集请求。
 * 弹层宽 360dp、行距 14dp。
 *
 * @param awaitKeyRelease 长按呼出时传 true：先吞掉松手时的 OK，避免直接触发首行。
 */
@Composable
internal fun SeasonMarkDialog(
    onMarkWatched: () -> Unit,
    onMarkUnwatched: () -> Unit,
    onDismiss: () -> Unit,
    awaitKeyRelease: Boolean = false,
) {
    val firstRowFocus = remember { FocusRequester() }
    var armed by remember { mutableStateOf(!awaitKeyRelease) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (armed) {
            NovumInitialFocus(firstRowFocus)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NovumPalette.Scrim.copy(alpha = MENU_SCRIM_ALPHA))
                // 收尾吞键：见 [NovumOptionListDialog] 同款说明
                .onPreviewKeyEvent { event ->
                    if (!armed && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                        if (event.type == KeyEventType.KeyUp) armed = true
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(SEASON_MENU_WIDTH)
                    .novumGlassPanel()
                    .padding(vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.season_mark_title),
                    style = NovumType.BodyStrong,
                    color = NovumPalette.Paper,
                    modifier = Modifier.padding(
                        horizontal = NovumDimens.OverlayPaddingHorizontal,
                        vertical = NovumDimens.OverlayPaddingVertical,
                    ),
                )
                SeasonMarkRow(
                    label = stringResource(R.string.mark_season_watched),
                    focusRequester = firstRowFocus,
                    onClick = onMarkWatched,
                )
                SeasonMarkRow(
                    label = stringResource(R.string.mark_season_unwatched),
                    onClick = onMarkUnwatched,
                )
            }
        }
    }
}

/** 整季标记行：样式统一走 [NovumOptionRow]（两条动作无选中态，焦点白底跟随）。 */
@Composable
private fun SeasonMarkRow(
    label: String,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    NovumOptionRow(
        text = label,
        selected = false,
        onClick = onClick,
        focusRequester = focusRequester,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

/** 选季行复用 [NovumOptionRow] 的选中态与焦点态样式。 */
@Composable
private fun SeasonMenuRow(
    season: Season,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    // 行尾显示未看集数，便于识别包含未看内容的季。
    val unplayed = season.unplayedCount?.takeIf { it > 0 }
    val bringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    NovumOptionRow(
        text = seasonDisplayName(season),
        selected = selected,
        value = unplayed?.let { stringResource(R.string.detail_season_unplayed, it) },
        onClick = onClick,
        focusRequester = focusRequester,
        onFocused = { focused ->
            if (focused) scope.launch { bringIntoView.bringIntoView() }
        },
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .bringIntoViewRequester(bringIntoView),
    )
}

/** 季展示名统一为「第 x 季」；0 为特别篇。 */
@Composable
internal fun seasonDisplayName(season: Season): String = if (season.index == 0) {
    stringResource(R.string.season_special)
} else {
    stringResource(R.string.season_name, season.index)
}

/** 季选择浮层宽度（规范：420u ≈ 280dp）。 */
private val SEASON_MENU_WIDTH = 360.dp

/** 浮层遮罩（与用户菜单/确认框同一档：轻于确认框、重于普通浮层）。 */
private const val MENU_SCRIM_ALPHA = 0.55f
