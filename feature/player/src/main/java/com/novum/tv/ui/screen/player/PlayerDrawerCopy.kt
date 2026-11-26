package com.novum.tv.ui.screen.player

/**
 * 简介页只移除明确独立的重复标题，保留以相同字词开头的正常正文。
 * 标题后的换行或冒号是明确边界；空白结果交回调用方展示空态。
 */
internal fun drawerOverviewBody(heading: String, rawBody: String?): String? {
    val body = rawBody?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val title = heading.trim().takeIf { it.isNotBlank() } ?: return body
    if (body == title) return null
    if (!body.startsWith(title)) return body

    val remainder = body.substring(title.length).trimStart(' ', '\t')
    val separator = remainder.firstOrNull()
    return if (separator in listOf('\n', '\r', '\u2028', '\u2029', ':', '：')) {
        remainder.substring(1).trim().takeIf { it.isNotBlank() }
    } else {
        body
    }
}

/** 当前音轨名优先；它可能已包含声道，不再重复拼接片源声明的声道数。 */
internal fun drawerAudioSummary(
    audioLabel: String,
    channels: Int?,
    channelsTemplate: String,
): String? = audioLabel.trim().takeIf { it.isNotBlank() }
    ?: channels?.takeIf { it > 0 }?.let { String.format(channelsTemplate, it) }
