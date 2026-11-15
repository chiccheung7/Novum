package com.novum.tv.navigation

import kotlinx.serialization.Serializable

/**
 * 详情页路由。
 *
 * 定义在 feature:detail 内部：页面自己的入参不应该由 :app 代为持有
 * 否则 feature 无法独立编译，也就失去了拆模块的意义。
 *
 * @param itemId Emby 条目 id。
 * @param focusCurrentEpisode 继续观看入口直达集级语境：进场
 *   直接呈现 NextUp 当前集的集级态（背景/简介/播放都是该集），返回键先回剧级态。
 *   仅首页继续观看行传 true；无 NextUp 提示时静默回落剧级态。
 */
@Serializable
data class DetailRoute(
    val itemId: String,
    val focusCurrentEpisode: Boolean = false,
)
