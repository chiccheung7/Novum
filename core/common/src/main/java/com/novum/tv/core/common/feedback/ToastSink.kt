package com.novum.tv.core.common.feedback

/**
 * 全局提示队列的最小抽象。
 *
 * 放在 core：ViewModel 层发消息只依赖这个接口，不必感知 Compose 实现；
 * 具体的 [com.novum.tv.ui.component.NovumToastController] 在 designsystem，
 * 由 app 层用 Hilt 绑定为单例。
 */
interface ToastSink {

    /**
     * 发出一条短提示，自动消失；新消息会替换旧消息。
     *
     * @param text 展示文案，建议一句话以内。
     */
    fun show(text: String)
}
