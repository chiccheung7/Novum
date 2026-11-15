package com.novum.tv.ui.screen.player

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.Window
import android.view.WindowManager
import com.novum.tv.feature.player.BuildConfig
import kotlin.math.abs

/**
 * 显示模式切换器：按片源规格把窗口的
 * [WindowManager.LayoutParams.preferredDisplayModeId] 指到能 1:1 容纳片源的最小
 * 显示模式——4K 面板以 1080p 输出时，4K 片源得以原生 4K 输出而不是被电视内部
 * 放大；播放会话离场还原系统默认偏好（[PREFERRED_DEFAULT] = 0）。
 *
 * 当前输出已能容纳片源时不切换；只处理输出分辨率不足的场景，不单独匹配刷新率。
 *
 * 换集时新旧播放页可能短暂共存，旧页的 dispose 可能晚于新页起播。
 * 代际计数确保只有最新会话可以恢复系统偏好；输出已足够时也会清理旧会话设置。
 */
internal object DisplayModeSwitcher {

    /** 最新会话的代际；0 = 进程内尚未有过会话。 */
    private var generation = 0

    /** 各会话记下的自己的代际（[endSession] 比对，旧代际放弃还原）。 */
    private var ownedGeneration = 0

    /** 进程首次会话前窗口的系统原始偏好（在任何写操作之前捕获，只记一次）。 */
    private var originalModeId = PREFERRED_DEFAULT

    /** 进程首次会话时的系统默认显示模式（「回落后是否仍够」的判定基准）。 */
    private var defaultMode: ModeCandidate? = null

    /** 基准（原始偏好 + 默认模式）是否已捕获。 */
    private var captured = false

    /**
     * 起播会话：按片源规格声明显示模式诉求并接管还原权。
     *
     * 三个分支：① 输出不足 → 切到能容纳的最小模式；② 输出已够且窗口挂着上一
     * 会话残留的更高偏好、回落默认后依然够 → 回落；③ 否则不动窗口。
     * 规格缺失（服务端未下发）时只接管还原权，不动窗口。
     *
     * @param activity 宿主 Activity（单 Activity 架构，窗口唯一）。
     * @param sourceWidth 片源宽（px）。
     * @param sourceHeight 片源高（px）。
     * @param sourceFps 片源帧率（候选面积相同取刷新率最近者；null 锚定当前刷新率）。
     * @return 切到的目标模式；未切（输出已够/无需回落/规格缺失）为 null。
     */
    fun beginSession(
        activity: Activity,
        sourceWidth: Int?,
        sourceHeight: Int?,
        sourceFps: Float?,
    ): ModeCandidate? {
        generation++
        ownedGeneration = generation
        val display = currentDisplay(activity)
        // 基准必须在任何写偏好之前捕获（此时偏好必是系统默认值）
        if (!captured) {
            captured = true
            originalModeId = activity.window.attributes.preferredDisplayModeId
            defaultMode = display?.mode?.toCandidate()
        }
        if (sourceWidth == null || sourceHeight == null || sourceWidth <= 0 || sourceHeight <= 0) return null
        if (display == null) return null
        val current = display.mode
        val window = activity.window
        if (current.physicalWidth >= sourceWidth && current.physicalHeight >= sourceHeight) {
            // 输出已够（保守策略）：唯一要处理的是上一会话的偏好泄漏
            if (shouldRestorePreference(
                    currentPreference = window.attributes.preferredDisplayModeId,
                    originalPreference = originalModeId,
                    defaultMode = defaultMode,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                )
            ) {
                setPreference(window, originalModeId)
                if (BuildConfig.DEBUG) {
                    Log.i(LOG_TAG, "fall back to default preference (source ${sourceWidth}x${sourceHeight})")
                }
            }
            return null
        }
        val target = matchDisplayMode(
            current = current.toCandidate(),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            sourceFps = sourceFps,
            candidates = display.supportedModes.map { it.toCandidate() },
        ) ?: return null
        setPreference(window, target.modeId)
        if (BuildConfig.DEBUG) {
            Log.i(
                LOG_TAG,
                "switch to ${target.width}x${target.height}@${"%.1f".format(target.refreshRate)}Hz " +
                    "(source ${sourceWidth}x${sourceHeight} ${sourceFps ?: "-"}fps, " +
                    "was ${current.physicalWidth}x${current.physicalHeight})",
            )
        }
        return target
    }

    /**
     * 结束会话：还原系统默认偏好。
     *
     * 只在「自己是最新一代会话」时生效——换集替换导航期旧页离场晚于新页起播，
     * 代际落后的还原直接放弃，避免打回新页刚切好的模式。
     */
    fun endSession(activity: Activity) {
        if (ownedGeneration != generation) return
        setPreference(activity.window, originalModeId)
    }

    /** 当前显示模式文本（画质 tab 实时分区）：`3840×2160 @ 60.0 Hz`；拿不到 Display 为 null。 */
    fun currentModeText(activity: Activity): String? {
        val mode = currentDisplay(activity)?.mode ?: return null
        return modeText(mode.physicalWidth, mode.physicalHeight, mode.refreshRate)
    }

    /** 当前 Display：API 30+ 使用 [Activity.getDisplay]，API 24–29 使用 [DisplayManager]。 */
    private fun currentDisplay(activity: Activity): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        }

    /** 写窗口显示模式偏好（与现状相同则不动，避免无谓的窗口属性提交）。 */
    private fun setPreference(window: Window, modeId: Int) {
        val attrs = window.attributes
        if (attrs.preferredDisplayModeId == modeId) return
        attrs.preferredDisplayModeId = modeId
        window.attributes = attrs
    }

    /** 系统默认偏好值 = 0（无偏好；框架未提供公开常量，还原目标）。 */
    private const val PREFERRED_DEFAULT = 0

    private const val LOG_TAG = "NovumDisplayMode"
}

/**
 * 显示模式的可测视图：[Display.Mode] 是框架类、无公开构造器，单测无法构造——
 * 决策逻辑抽成纯函数（[matchDisplayMode]/[shouldRestorePreference]）以此承载。
 */
internal data class ModeCandidate(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/** [Display.Mode] → [ModeCandidate]。 */
private fun Display.Mode.toCandidate(): ModeCandidate =
    ModeCandidate(modeId = modeId, width = physicalWidth, height = physicalHeight, refreshRate = refreshRate)

/** 显示模式文本：`3840×2160 @ 60.0 Hz`。 */
internal fun modeText(width: Int, height: Int, refreshRate: Float): String =
    "$width×$height @ %.1f Hz".format(refreshRate)

/**
 * 显示模式匹配（纯函数）：当前输出已能 1:1 容纳片源时不切（保守策略），
 * 不够才在候选里挑——能容纳的**最小面积**优先（不虚耗面板带宽），面积相同
 * 取刷新率离片源帧率最近者（帧率未知锚定当前刷新率，避免无据漂移）。
 *
 * @param current 当前输出模式。
 * @param sourceWidth 片源宽（px）。
 * @param sourceHeight 片源高（px）。
 * @param sourceFps 片源帧率；null = 未知。
 * @param candidates 显示支持的候选模式。
 * @return 目标模式；无需/无法切换为 null。
 */
internal fun matchDisplayMode(
    current: ModeCandidate,
    sourceWidth: Int,
    sourceHeight: Int,
    sourceFps: Float?,
    candidates: List<ModeCandidate>,
): ModeCandidate? {
    if (current.width >= sourceWidth && current.height >= sourceHeight) return null
    val anchorFps = sourceFps ?: current.refreshRate
    return candidates.asSequence()
        .filter { it.width >= sourceWidth && it.height >= sourceHeight }
        .minWithOrNull(
            compareBy<ModeCandidate> { it.width.toLong() * it.height }
                .thenBy { abs(it.refreshRate - anchorFps) },
        )
}

/**
 * 偏好泄漏回落判定（纯函数）：当前输出已够容纳片源时，窗口挂着的**非默认**
 * 偏好（上一会话残留）是否应回落——回落后系统默认模式仍能容纳片源才回落，
 * 否则保留（4K 片源靠残留偏好撑着，清空会被打回 1080p）。
 */
internal fun shouldRestorePreference(
    currentPreference: Int,
    originalPreference: Int,
    defaultMode: ModeCandidate?,
    sourceWidth: Int,
    sourceHeight: Int,
): Boolean =
    currentPreference != originalPreference &&
        defaultMode != null &&
        defaultMode.width >= sourceWidth &&
        defaultMode.height >= sourceHeight
