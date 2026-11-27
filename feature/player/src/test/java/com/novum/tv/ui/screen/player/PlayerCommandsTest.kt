package com.novum.tv.ui.screen.player

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放页按键状态机测试。
 *
 * 这段逻辑是遥控器体验的核心：它决定「什么时候父层接管按键、什么时候交给焦点系统」。
 * 一旦写错，方向键可能失灵或焦点停滞，因此覆盖各类状态组合。
 */
class PlayerCommandsTest {

    private fun resolve(
        key: Key,
        repeatCount: Int = 0,
        controlsVisible: Boolean = false,
        progressFocused: Boolean = false,
        confirmVisible: Boolean = false,
        overlayActionFocused: Boolean = false,
    ): PlayerCommand? = resolvePlayerCommand(
        key = key,
        repeatCount = repeatCount,
        controlsVisible = controlsVisible,
        progressFocused = progressFocused,
        confirmVisible = confirmVisible,
        overlayActionFocused = overlayActionFocused,
    )

    @Test
    fun `隐藏态确定键切换播放`() {
        assertEquals(PlayerCommand.TogglePlay, resolve(Key.DirectionCenter))
        assertEquals(PlayerCommand.TogglePlay, resolve(Key.Enter))
    }

    @Test
    fun `跳过或重试按钮的确定键不能被隐藏控制条当成播放暂停`() {
        assertNull(resolve(Key.DirectionCenter, overlayActionFocused = true))
        assertNull(resolve(Key.Enter, overlayActionFocused = true))
        assertNull(resolve(Key.NumPadEnter, overlayActionFocused = true))
        assertEquals(
            PlayerCommand.ShowControls,
            resolve(Key.DirectionUp, overlayActionFocused = true),
        )
    }

    @Test
    fun `隐藏态左右键粗调十秒`() {
        assertEquals(PlayerCommand.SeekBy(-10_000L), resolve(Key.DirectionLeft))
        assertEquals(PlayerCommand.SeekBy(10_000L), resolve(Key.DirectionRight))
    }

    @Test
    fun `隐藏态长按左右键加速到三十秒`() {
        assertEquals(PlayerCommand.SeekBy(30_000L), resolve(Key.DirectionRight, repeatCount = 4))
    }

    @Test
    fun `专用快进快退键在控制条显示和隐藏时都生效`() {
        for (visible in listOf(false, true)) {
            for (progress in listOf(false, true)) {
                assertEquals(PlayerCommand.SeekBy(-10_000L),
                    resolve(Key.MediaRewind, controlsVisible = visible, progressFocused = progress))
                assertEquals(PlayerCommand.SeekBy(30_000L),
                    resolve(Key.MediaFastForward, repeatCount = 4,
                        controlsVisible = visible, progressFocused = progress))
            }
        }
    }

    @Test
    fun `确认弹层打开时专用快进快退键不会改动播放位置`() {
        assertNull(resolve(Key.MediaRewind, confirmVisible = true))
        assertNull(resolve(Key.MediaFastForward, confirmVisible = true))
    }

    @Test
    fun `隐藏态上下键呼出控制条`() {
        assertEquals(PlayerCommand.ShowControls, resolve(Key.DirectionUp))
        assertEquals(PlayerCommand.ShowControls, resolve(Key.DirectionDown))
    }

    @Test
    fun `隐藏态菜单键打开播放设置抽屉`() {
        assertEquals(PlayerCommand.OpenSettings, resolve(Key.Menu))
    }

    @Test
    fun `显示态且焦点在按钮上时方向键交给焦点系统`() {
        assertNull(resolve(Key.DirectionLeft, controlsVisible = true))
        assertNull(resolve(Key.DirectionRight, controlsVisible = true))
        assertNull(resolve(Key.DirectionUp, controlsVisible = true))
        assertNull(resolve(Key.DirectionDown, controlsVisible = true))
        // 确定键必须放行，否则焦点项永远点不动
        assertNull(resolve(Key.DirectionCenter, controlsVisible = true))
    }

    @Test
    fun `显示态进度条聚焦时左右键精细 seek`() {
        assertEquals(
            PlayerCommand.FineSeek(-5_000L),
            resolve(Key.DirectionLeft, controlsVisible = true, progressFocused = true),
        )
        assertEquals(
            PlayerCommand.FineSeek(5_000L),
            resolve(Key.DirectionRight, controlsVisible = true, progressFocused = true),
        )
    }

    @Test
    fun `显示态进度条聚焦时长按精细 seek 提速`() {
        assertEquals(
            PlayerCommand.FineSeek(10_000L),
            resolve(Key.DirectionRight, repeatCount = 4, controlsVisible = true, progressFocused = true),
        )
    }

    @Test
    fun `显示态进度条聚焦时上下键仍然交给焦点系统`() {
        assertNull(resolve(Key.DirectionUp, controlsVisible = true, progressFocused = true))
        assertNull(resolve(Key.DirectionDown, controlsVisible = true, progressFocused = true))
    }

    @Test
    fun `退出确认打开时父层不接管任何按键`() {
        assertNull(resolve(Key.DirectionLeft, confirmVisible = true))
        assertNull(resolve(Key.DirectionCenter, confirmVisible = true))
        assertNull(resolve(Key.DirectionUp, confirmVisible = true))
    }

    @Test
    fun `精细 seek 步长单击五秒长按十秒`() {
        assertEquals(5_000L, fineSeekStepMs(0))
        assertEquals(5_000L, fineSeekStepMs(3))
        assertEquals(10_000L, fineSeekStepMs(4))
    }

    @Test
    fun `显示态菜单键仍可打开播放设置抽屉`() {
        assertEquals(PlayerCommand.OpenSettings, resolve(Key.Menu, controlsVisible = true))
        assertEquals(
            PlayerCommand.OpenSettings,
            resolve(Key.Menu, controlsVisible = true, progressFocused = true),
        )
    }

    @Test
    fun `未定义的按键不产生动作`() {
        assertNull(resolve(Key.MediaPlayPause))
        assertNull(resolve(Key.MediaPlayPause, controlsVisible = true, progressFocused = true))
    }

    @Test
    fun `画面比例档位循环 适应 拉伸 裁剪`() {
        assertEquals(VideoScaleMode.Stretch, VideoScaleMode.Fit.next())
        assertEquals(VideoScaleMode.Crop, VideoScaleMode.Stretch.next())
        assertEquals(VideoScaleMode.Fit, VideoScaleMode.Crop.next())
    }
}
