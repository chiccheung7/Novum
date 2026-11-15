package com.novum.tv.navigation

import kotlinx.serialization.Serializable

/**
 * 播放页路由。
 *
 * 定义在 feature:player 内部，与页面同生命周期。
 *
 * @param itemId 要播放的条目 id（剧集为具体分集 id）。
 * @param startPositionMs 起播位置覆盖（毫秒）；null 走默认续播/片头跳过规则，
 *   0 表示从头播放（详情页「从头播放」按钮专用，不覆盖后续直通切换重载）。
 */
@Serializable
data class PlayerRoute(
    val itemId: String,
    val startPositionMs: Long? = null,
    /** 指定版本（详情页版本选择）；null = 按画质策略/服务端默认。 */
    val mediaSourceId: String? = null,
)
