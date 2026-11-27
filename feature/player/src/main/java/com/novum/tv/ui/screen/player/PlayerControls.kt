package com.novum.tv.ui.screen.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.model.Chapter
import com.novum.tv.ui.component.NovumImage
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumType

/**
 * 控制层（三层结构中的最上层）。
 *
 * 关键约定：**这个组合永远存在，只改 alpha，不销毁节点**。
 * 用 `if (visible)` 条件组合的话，每次显隐都会重建整棵子树
 * 遥控器上表现为「刚呼出控制条时第一下按键丢失」——因为焦点节点还没挂上。
 *
 * 背景渐变遮罩单独一层（见 [PlayerStage]），这里只管内容。
 *
 * 标题位优先显示 ClearLogo，没有时回落到文本标题；下方显示当前章节。
 *
 * @param title 作品标题（无 ClearLogo 时的文本回落）。
 * @param logoUrl 左上角 ClearLogo；null 时渲染 [title] 文本。
 * @param chapters 章节列表（标题下角标「· 第 x 章」）。
 * @param isPlaying 是否正在播放。
 * @param interactive 是否可聚焦（控制条隐藏或弹层打开时为 false）。
 * @param focusedItem 当前焦点项。
 * @param speedText 倍速钮当前档位文案。
 * @param scaleText 画面比例钮当前档位文案。
 * @param alpha 控制层透明度，绘制期读取。
 * @param position 播放位置（State，避免外层重组）。
 * @param duration 总时长。
 * @param buffered 已缓冲位置。
 * @param scrubTarget 未落地的 seek 目标。
 * @param requesters 各控件的焦点索取器。
 * @param progressRequester 进度条的焦点索取器。
 * @param onFocusedItemChange 焦点项变化回调。
 * @param onActivate 激活某个控件。
 */
@Composable
internal fun PlayerControlOverlay(
    title: String,
    /** 左上角 ClearLogo（标题位换透明底标题图）；null 回落文本标题。 */
    logoUrl: String?,
    episodeTitle: String? = null,
    networkSpeed: State<Long>,
    chapters: List<Chapter>,
    isPlaying: Boolean,
    interactive: Boolean,
    focusedItem: ControlItem,
    speedText: String,
    scaleText: String,
    alpha: State<Float>,
    position: State<Long>,
    duration: State<Long>,
    buffered: State<Long>,
    scrubTarget: Long?,
    requesters: Map<ControlItem, FocusRequester>,
    progressRequester: FocusRequester,
    onFocusedItemChange: (ControlItem) -> Unit,
    onActivate: (ControlItem) -> Unit,
) {
    var logoLoaded by remember(logoUrl) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 绘制期读动画值：显隐逐帧只重绘，不重组整棵控制条
            .graphicsLayer { this.alpha = alpha.value },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                // 外边距全站统一：水平 68dp 对齐线、垂直安全区 27dp
                .padding(
                    horizontal = NovumDimens.SafeHorizontal,
                    vertical = NovumDimens.SafeVertical,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 标题位：有 ClearLogo 用透明底标题图，无图回落文本标题
            if (logoUrl != null) {
                Box(modifier = Modifier.height(PLAYER_LOGO_HEIGHT).fillMaxWidth(PLAYER_LOGO_MAX_WIDTH_RATIO)) {
                    if (!logoLoaded) PlayerTextTitle(title)
                NovumImage(
                    url = logoUrl,
                    // 标题语义兜底给读屏；无障碍名仍是作品标题
                    contentDescription = title.ifBlank { null },
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.fillMaxSize(),
                    onLoadState = { logoLoaded = it },
                )
                }
            } else {
                PlayerTextTitle(title)
            }
            episodeTitle?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = NovumType.Body, color = NovumPalette.Paper,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.62f))
            }
            // 标题下显示当前章节指示。
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChapterIndicator(chapters = chapters, position = position)
            }
        }

        NetworkSpeedLabel(networkSpeed, Modifier.align(Alignment.TopEnd)
            .padding(horizontal = NovumDimens.SafeHorizontal, vertical = NovumDimens.SafeVertical))

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = NovumDimens.SafeHorizontal,
                    end = NovumDimens.SafeHorizontal,
                    bottom = NovumDimens.SafeVertical,
                ),
        ) {
            PlayerProgressBar(
                position = position,
                duration = duration,
                buffered = buffered,
                scrubTarget = scrubTarget,
                interactive = interactive,
                focused = focusedItem == ControlItem.Progress,
                requester = progressRequester,
                chapters = chapters,
                onFocusChanged = { focused ->
                    if (focused) onFocusedItemChange(ControlItem.Progress)
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            ActionRow(
                isPlaying = isPlaying,
                interactive = interactive,
                speedText = speedText,
                scaleText = scaleText,
                requesters = requesters,
                onFocusedItemChange = onFocusedItemChange,
                onActivate = onActivate,
            )
        }
    }
}

@Composable
private fun PlayerTextTitle(title: String) {
    Text(text = title.ifBlank { stringResource(R.string.player_unknown_title) },
        style = NovumType.Section.copy(fontFamily = FontFamily.Default), color = NovumPalette.Paper,
        maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Keep half-second speed samples out of the controls' recomposition scope. */
@Composable
private fun NetworkSpeedLabel(speed: State<Long>, modifier: Modifier = Modifier) {
    val value = speed.value
    if (value < 0L) return
    Text(text = formatSpeed(value), style = NovumType.Caption, color = NovumPalette.Paper, modifier = modifier)
}

/** 顶部章节提示：「· 第 3 章 夜航」。 */
@Composable
private fun ChapterIndicator(chapters: List<Chapter>, position: State<Long>) {
    if (chapters.isEmpty()) return
    val name = chapters.currentChapterName(position.value) ?: return
    Text(
        text = "  ·  $name",
        style = NovumType.Caption,
        color = NovumPalette.PaperMuted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 按钮行：全部**纯图标**——
 * 后退10s / 播放暂停 / 快进10s / 倍速 / 画面比例 / 播放设置。
 * 文案语义走 contentDescription 兜底；声明顺序即左右焦点序。
 * 左右焦点移动由 Compose 焦点系统处理。
 */
@Composable
private fun ActionRow(
    isPlaying: Boolean,
    interactive: Boolean,
    speedText: String,
    scaleText: String,
    requesters: Map<ControlItem, FocusRequester>,
    onFocusedItemChange: (ControlItem) -> Unit,
    onActivate: (ControlItem) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ControlChip(
            icon = NovumIcons.Rewind,
            label = stringResource(R.string.player_control_rewind),
            interactive = interactive,
            requester = requesters.getValue(ControlItem.SeekBack),
            onFocused = { onFocusedItemChange(ControlItem.SeekBack) },
            onActivate = { onActivate(ControlItem.SeekBack) },
        )
        PlayPauseButton(
            isPlaying = isPlaying,
            interactive = interactive,
            requester = requesters.getValue(ControlItem.PlayPause),
            onFocused = { onFocusedItemChange(ControlItem.PlayPause) },
            onActivate = { onActivate(ControlItem.PlayPause) },
        )
        ControlChip(
            icon = NovumIcons.FastForward,
            label = stringResource(R.string.player_control_forward),
            interactive = interactive,
            requester = requesters.getValue(ControlItem.SeekFwd),
            onFocused = { onFocusedItemChange(ControlItem.SeekFwd) },
            onActivate = { onActivate(ControlItem.SeekFwd) },
        )
        ControlChip(
            icon = NovumIcons.Speed,
            label = stringResource(R.string.player_info_speed),
            valueText = speedText,
            interactive = interactive,
            requester = requesters.getValue(ControlItem.Speed),
            onFocused = { onFocusedItemChange(ControlItem.Speed) },
            onActivate = { onActivate(ControlItem.Speed) },
        )
        ControlChip(
            icon = NovumIcons.Aspect,
            label = stringResource(R.string.player_control_scale),
            valueText = scaleText,
            interactive = interactive,
            requester = requesters.getValue(ControlItem.Scale),
            onFocused = { onFocusedItemChange(ControlItem.Scale) },
            onActivate = { onActivate(ControlItem.Scale) },
        )
        ControlChip(
            icon = NovumIcons.Settings,
            label = stringResource(R.string.player_control_settings),
            interactive = interactive,
            requester = requesters.getValue(ControlItem.Settings),
            onFocused = { onFocusedItemChange(ControlItem.Settings) },
            onActivate = { onActivate(ControlItem.Settings) },
        )
    }
}

/** 主播放键：56dp 圆形，焦点态白底黑图标。 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    interactive: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onActivate: () -> Unit,
) {
    // 高亮以「节点真实焦点」为准，而不是父层记录的 focusedItem：
    // 控制条隐藏时焦点的回收顺序不受我们控制，用父层状态画高亮会出现
    // 「控制条都藏了，某个按钮还白着」的假焦点。
    var isFocused by remember { mutableStateOf(false) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .focusRequester(requester)
            .focusProperties { canFocus = interactive }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(CircleShape)
            .background(if (isFocused) NovumPalette.Paper else NovumPalette.GlassVideo)
            .border(
                width = 1.dp,
                color = if (isFocused) Color.Transparent else NovumPalette.ControlOutline,
                shape = CircleShape,
            )
            .clickable(onClick = onActivate),
    ) {
        Icon(
            imageVector = if (isPlaying) NovumIcons.Pause else NovumIcons.Play,
            contentDescription = stringResource(
                if (isPlaying) R.string.player_control_pause else R.string.player_control_play,
            ),
            tint = if (isFocused) NovumPalette.Ink else NovumPalette.Paper,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * 次级控制按钮：纯图标，语义走 [label] 作 contentDescription
 * 聚焦白底反色。[valueText] 非空时在图标旁显示当前值（倍速钮显示当前档位
 * 切换要有可感知的区分）。
 */
@Composable
private fun ControlChip(
    icon: ImageVector,
    label: String,
    interactive: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onActivate: () -> Unit,
    valueText: String? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .focusRequester(requester)
            .focusProperties { canFocus = interactive }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(NovumShapes.Pill)
            .background(if (isFocused) NovumPalette.Paper else NovumPalette.GlassVideoSoft)
            .border(
                width = 1.dp,
                color = if (isFocused) Color.Transparent else NovumPalette.ControlOutline,
                shape = NovumShapes.Pill,
            )
            .clickable(onClick = onActivate)
            .padding(14.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isFocused) NovumPalette.Ink else NovumPalette.Paper,
            modifier = Modifier.size(20.dp),
        )
        if (valueText != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = valueText,
                style = NovumType.Label,
                color = if (isFocused) NovumPalette.Ink else NovumPalette.Paper,
                maxLines = 1,
            )
        }
    }
}

/** 控制条渐变遮罩：纯黑视频上仍要保证文字可读，同时中间留空不压画面。 */
internal val ControlScrimBrush: Brush = Brush.verticalGradient(
    0f to Color.Black.copy(alpha = 0.66f),
    0.30f to Color.Transparent,
    0.58f to Color.Transparent,
    1f to Color.Black.copy(alpha = 0.82f),
)

/** 播放页左上角 ClearLogo 高度：标题图在黑场遮罩上直出，比文本标题矮但更醒目。 */
private val PLAYER_LOGO_HEIGHT = 52.dp

/** ClearLogo 宽度上限（占屏比）：Fit 缩放下横版 logo 居左不满宽，超宽 logo 不吞屏。 */
private const val PLAYER_LOGO_MAX_WIDTH_RATIO = 0.42f
