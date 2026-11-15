package com.novum.tv.testing

import com.novum.tv.core.common.feedback.ToastSink

/**
 * 记录式 Toast 假实现：单测里收集 show() 的文案，供断言「操作是否有反馈」。
 */
class RecordingToastSink : ToastSink {

    /** 依序记录的全部提示文案。 */
    val texts = mutableListOf<String>()

    override fun show(text: String) {
        texts += text
    }

    /** 清空记录，测试用例间互不污染。 */
    fun reset() = texts.clear()
}
