package com.novum.tv.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.common.feedback.ToastSink
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一条全局提示消息。
 *
 * @param id 消息唯一 id，宿主用它做自动消失与「新替旧」判定。
 * @param text 展示文案。
 */
data class NovumToast(val id: Long, val text: String)

/**
 * 全局 Toast 控制器：新消息替换旧消息并重置计时，避免提示堆叠。
 *
 * @param id 消息唯一 id。
 * @param text 展示文案。
 * @param durationMillis 展示时长。
 */
class NovumToastController : ToastSink {

    private val _current = MutableStateFlow<NovumToast?>(null)

    /** 当前展示中的消息；null 表示无。 */
    val current: StateFlow<NovumToast?> = _current.asStateFlow()

    /**
     * 发出一条提示，展示 [TOAST_DURATION_MS] 毫秒后自动消失。
     *
     * @param text 展示文案，建议一句话以内。
     */
    override fun show(text: String) {
        // nanoTime 保证「同文案连续触发」也会换 id，从而重置计时
        _current.value = NovumToast(id = System.nanoTime(), text = text)
    }

    /**
     * 关闭指定 id 的消息；若它已被新消息替换则忽略（避免误关新消息）。
     */
    fun dismiss(id: Long) {
        if (_current.value?.id == id) _current.value = null
    }
}

/**
 * 全局 Toast 控制器的 CompositionLocal。
 *
 * UI 层用 `LocalNovumToast.current.show("...")` 发消息；未提供时抛错，
 * 避免静默丢消息。
 */
val LocalNovumToast = staticCompositionLocalOf<NovumToastController> {
    error("NovumToastController 未提供")
}

/**
 * 全局 Toast 宿主：只负责渲染，不参与触摸与焦点。挂在 NavHost 外，高于页面内容、低于 Dialog 和系统弹窗。
 *
 * @param controller Toast 控制器。
 * @param modifier 外部修饰符。
 */
@Composable
fun NovumToastHost(
    controller: NovumToastController,
    modifier: Modifier = Modifier,
) {
    // 生命周期感知：页面退到后台时停止收集，避免后台仍触发重组与计时
    val toast by controller.current.collectAsStateWithLifecycle()

    // 淡出动画期间 toast 已经是 null，锁住最后一次文案防止闪空壳
    var lastText by remember { mutableStateOf("") }
    LaunchedEffect(toast) {
        toast?.let { lastText = it.text }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn(tween(ENTER_MS)) +
                slideInVertically(tween(ENTER_MS)) { it / TOAST_SLIDE_DIVISOR },
            exit = fadeOut(tween(EXIT_MS)),
        ) {
            Box(
                modifier = Modifier
                    .clip(NovumShapes.Card)
                    .background(NovumPalette.PanelOverlay)
                    .padding(horizontal = 22.dp, vertical = 12.dp),
            ) {
                Text(
                    text = toast?.text ?: lastText,
                    style = NovumType.Body,
                    color = NovumPalette.Paper,
                )
            }
        }
    }

    // 自动消失：新消息进来会重启这个 effect，从而重置 2s 计时
    LaunchedEffect(toast?.id) {
        val current = toast ?: return@LaunchedEffect
        delay(TOAST_DURATION_MS)
        controller.dismiss(current.id)
    }
}

/** Toast 展示时长（毫秒）。 */
private const val TOAST_DURATION_MS = 2_500L

/** 入场动画时长。 */
private const val ENTER_MS = 160

/** Toast 退场动画时长（毫秒）。 */
private const val EXIT_MS = 240

/** 上滑入场的位移比例。 */
private const val TOAST_SLIDE_DIVISOR = 3
