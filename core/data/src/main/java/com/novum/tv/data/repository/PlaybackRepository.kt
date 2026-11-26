package com.novum.tv.data.repository

import com.novum.tv.core.result.NovumResult
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan

/**
 * 播放仓储：负责「拿到播放地址」与「回报播放进度」。
 *
 * 与播放器本身解耦，便于在 JVM 测试中验证地址选择策略与进度换算。
 */
interface PlaybackRepository : PlaybackReporter {

    /**
     * 为条目生成播放计划。
     *
     * 决策顺序：直连 → 转封装 → 转码 → 标准静态流兜底。
     *
     * @param itemId 条目 id（剧集时为具体分集 id）。
     * @param startPositionMs 续播起点，0 表示从头播放。
     * @param forceTranscode 强制服务端转码。
     * @param excludeLine 排除的线路 baseUrl：换线重试时传入刚失败的那条。
     * @param mediaSourceId 指定版本（详情页版本选择）；null = 按画质策略/服务端默认。
     */
    suspend fun plan(
        itemId: String,
        startPositionMs: Long = 0L,
        forceTranscode: Boolean = false,
        excludeLine: String? = null,
        mediaSourceId: String? = null,
    ): NovumResult<PlaybackPlan>

    /** Bind queued playback events to the account that owns this player. */
    fun sessionReporter(): PlaybackReporter = this
}

/** Playback events may outlive the screen, but must never move to another account. */
interface PlaybackReporter {

    /**
     * 上报「开始播放」。
     *
     * @param itemId 条目 id。
     * @param positionMs 起始位置。
     * @param method 播放方式。
     * @param playSessionId PlaybackInfo 会话 id；null = 会话未建立（静态流兜底）。
     */
    suspend fun reportStarted(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String? = null,
    )

    /**
     * 上报播放进度。
     *
     * @param itemId 条目 id。
     * @param positionMs 当前位置。
     * @param method 播放方式。
     * @param playSessionId PlaybackInfo 会话 id。
     */
    suspend fun reportProgress(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String? = null,
    )

    /**
     * 上报「停止播放」，服务端据此落库最终进度。
     *
     * @param itemId 条目 id。
     * @param positionMs 退出时的位置。
     * @param method 播放方式。
     * @param playSessionId PlaybackInfo 会话 id。
     */
    suspend fun reportStopped(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String? = null,
    )
}
