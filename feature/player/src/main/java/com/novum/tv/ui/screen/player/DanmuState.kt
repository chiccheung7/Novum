package com.novum.tv.ui.screen.player

import com.novum.tv.core.error.NovumError
import com.novum.tv.model.DanmuAnime
import com.novum.tv.model.DanmuComment
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuEpisode

/**
 * 弹幕会话状态：渲染配置 + 加载状态 + 弹幕数据 + 偏移 + 手动搜索流程。
 *
 * 弹幕数据（[comments]）只在换分集/换匹配时整体替换，调节参数走 [config]——
 * 状态对象持有列表引用，配置变更不会复制 20 万条数据。
 */
data class DanmuSession(
    val config: DanmuConfig = DanmuConfig(),
    val load: DanmuLoad = DanmuLoad.Idle,
    val comments: List<DanmuComment> = emptyList(),
    /** 时间偏移（毫秒，正=弹幕延后）：渲染时叠加到每条弹幕的时间上。 */
    val offsetMs: Long = 0L,
    val search: DanmuSearch = DanmuSearch.Idle,
)

/** 弹幕加载流程状态。 */
 sealed interface DanmuLoad {

    /** 弹幕关（或尚未开始加载）。 */
    data object Idle : DanmuLoad

    /** 正在自动匹配。 */
    data object Matching : DanmuLoad

    /** 自动匹配失败：资源文件名没匹配到分集，提示可手动搜索。 */
    data object Unmatched : DanmuLoad

    /** 匹配成功，正在拉取弹幕。 */
    data object Loading : DanmuLoad

    /** 弹幕就绪。
     *
     * @param episodeId 生效的弹幕分集 id（分集列表高亮当前项）。
     * @param count 生效弹幕条数（密度采样前）。
     * @param episodeTitle 匹配到的分集标题（展示用；手动选择时来自用户点选）。
     * @param manual 是否手动选择（区别于文件名自动匹配）。
     */
    data class Loaded(
        val episodeId: String,
        val count: Int,
        val episodeTitle: String?,
        val manual: Boolean,
    ) : DanmuLoad

    /** 弹幕服务不可用/请求失败（鉴权失败=该服务器没有弹幕能力）。 */
    data class Failed(val error: NovumError) : DanmuLoad
}

/** 手动搜索流程状态（搜索动画 → 选动画 → 选分集）。 */
 sealed interface DanmuSearch {

    /** 未进入搜索。 */
    data object Idle : DanmuSearch

    /** 搜索中。 */
    data object Searching : DanmuSearch

    /** 搜索结果（动画列表）。 */
    data class Results(val keyword: String, val animes: List<DanmuAnime>) : DanmuSearch

    /** 选定动画，展示分集列表。 */
    data class Episodes(val anime: DanmuAnime, val episodes: List<DanmuEpisode>) : DanmuSearch

    /** 搜索/取分集失败。 */
    data class Failed(val error: NovumError) : DanmuSearch
}

/**
 * 弹幕操作回调束：把 ViewModel 的弹幕函数打包成一个参数传给 Stage/抽屉，
 * 避免逐个散传（开关/调节/偏移/重试/手动搜索共十来个入口）。
 */
data class DanmuActions(
    val setEnabled: (Boolean) -> Unit,
    val updateConfig: ((DanmuConfig) -> DanmuConfig) -> Unit,
    val adjustOffset: (Long) -> Unit,
    val resetOffset: () -> Unit,
    val retry: () -> Unit,
    val searchAnime: (String) -> Unit,
    val openAnime: (DanmuAnime) -> Unit,
    val selectEpisode: (DanmuEpisode) -> Unit,
    val closeSearch: () -> Unit,
    val useAutoMatch: () -> Unit,
)
