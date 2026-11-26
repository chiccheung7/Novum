package com.novum.tv.ui.screen.player

import androidx.compose.ui.input.key.Key
import com.novum.tv.core.player.seekStepMs

/**
 * 控制条上可获得焦点的控件，声明顺序即左右移动顺序。
 *
 * [Progress] 单独放在最前：它不在按钮行里，但同样参与焦点与自动隐藏的判定
 * （焦点停在进度条上时控制条不自动收起，否则用户刚对准就被藏起来）。
 *
 * 按钮依次为快退、播放/暂停、快进、倍速、画面比例和播放设置。
 * 字幕与音轨在设置抽屉中选择，换集通过抽屉选集页或片尾下一集按钮完成。
 */
internal enum class ControlItem {
    Progress,
    SeekBack,
    PlayPause,
    SeekFwd,
    Speed,
    Scale,
    Subtitles,
    Audio,
    Settings,
}

/**
 * 画面缩放模式：自适应、全屏拉伸或全屏裁剪。
 *
 * [Fit]（自适应）：等比呈现，短边填满、黑边补齐——忠实还原片源画幅
 *   默认档（钮上文案「默认」）
 * [Stretch]（全屏拉伸）：不保比例强行铺满整屏，允许变形
 * [Crop]（全屏裁剪）：等比放大铺满整屏、居中裁掉出界部分。
 *
 * 片源画幅与屏幕一致时，三种模式的几何结果相同；差异只在非 16:9 片源上可见。
 */
internal enum class VideoScaleMode {
    Fit,
    Stretch,
    Crop,
}

/** 循环切换到下一档缩放模式。 */
internal fun VideoScaleMode.next(): VideoScaleMode =
    when (this) {
        VideoScaleMode.Fit -> VideoScaleMode.Stretch
        VideoScaleMode.Stretch -> VideoScaleMode.Crop
        VideoScaleMode.Crop -> VideoScaleMode.Fit
    }

/** 播放页按键解析出的语义动作。 */
internal sealed interface PlayerCommand {

    /** 播放 / 暂停切换。 */
    data object TogglePlay : PlayerCommand

    /** 快进 / 快退（隐藏态，粗粒度）。 */
    data class SeekBy(val deltaMs: Long) : PlayerCommand

    /** 呼出控制条并把焦点落在播放键上。 */
    data object ShowControls : PlayerCommand

    /** 打开播放设置抽屉（Menu 键的唯一入口）。 */
    data object OpenSettings : PlayerCommand

    /** 进度条聚焦时的精细 seek。 */
    data class FineSeek(val deltaMs: Long) : PlayerCommand
}

/**
 * 把一次按键翻译成播放页动作。
 *
 * 设计原则：**只在「控制条隐藏」与「进度条聚焦」两种状态下接管按键**，
 * 其余情况一律返回 null，让 Compose 焦点系统自己处理左右上下移动。
 * 这样既保住了遥控器上的原生焦点导航，又不会出现「父层把所有方向键都吃掉、
 * 焦点永远停在原地」的经典 TV bug。
 *
 * @param key 按下的键。
 * @param repeatCount 系统按键重复次数，首次按下为 0。
 * @param controlsVisible 控制条是否处于可交互的显示态。
 * @param progressFocused 焦点是否在进度条上。
 * @param confirmVisible 退出确认是否打开。
 * @param overlayActionFocused 跳过/错误重试按钮是否接管确定键。
 */
internal fun resolvePlayerCommand(
    key: Key,
    repeatCount: Int,
    controlsVisible: Boolean,
    progressFocused: Boolean,
    confirmVisible: Boolean,
    overlayActionFocused: Boolean = false,
): PlayerCommand? {
    // 弹层打开时按键交给弹层自己（确认框是独立 Dialog 窗口），父层不介入
    if (confirmVisible) return null
    // 控制条隐藏时，浮钮仍是可点击控件，不能把它的 OK 当成播放/暂停。
    if (overlayActionFocused &&
        (key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter)
    ) return null

    if (!controlsVisible) {
        return when (key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> PlayerCommand.TogglePlay
            Key.DirectionLeft -> PlayerCommand.SeekBy(-seekStepMs(repeatCount))
            Key.DirectionRight -> PlayerCommand.SeekBy(seekStepMs(repeatCount))
            Key.DirectionUp, Key.DirectionDown -> PlayerCommand.ShowControls
            Key.Menu -> PlayerCommand.OpenSettings
            else -> null
        }
    }

    // 显示态：只有进度条聚焦时才需要父层截走左右键做精细 seek
    if (progressFocused) {
        return when (key) {
            Key.DirectionLeft -> PlayerCommand.FineSeek(-fineSeekStepMs(repeatCount))
            Key.DirectionRight -> PlayerCommand.FineSeek(fineSeekStepMs(repeatCount))
            Key.Menu -> PlayerCommand.OpenSettings
            else -> null
        }
    }

    return if (key == Key.Menu) PlayerCommand.OpenSettings else null
}

/** 精细 seek 单击步长：5 秒，比粗调更可控。 */
private const val FINE_SEEK_MS = 5_000L

/** 精细 seek 长按步长。 */
private const val FINE_SEEK_FAST_MS = 10_000L

/** 按住多少次后进入快速精细 seek。 */
private const val FINE_SEEK_FAST_REPEAT = 3

/**
 * 精细 seek 的步长：单击 5 秒，按住超过 3 次后升到 10 秒。
 *
 * @param repeatCount 系统按键重复次数。
 */
internal fun fineSeekStepMs(repeatCount: Int): Long =
    if (repeatCount > FINE_SEEK_FAST_REPEAT) FINE_SEEK_FAST_MS else FINE_SEEK_MS
