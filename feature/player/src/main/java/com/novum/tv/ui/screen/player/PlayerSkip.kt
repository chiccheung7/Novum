package com.novum.tv.ui.screen.player

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.novum.tv.model.Chapter

/**
 * 根据章节名识别片头或片尾跳过目标。
 *
 * **片头**：第 1 章名含「片头/intro/opening」且时长 ≥ [SKIP_MIN_CHAPTER_MS]
 *   位置落在该章内 → 跳到第 2 章起点
 * **片尾**：末章名含「片尾/credits/ending/outro」且时长 ≥ [SKIP_MIN_CHAPTER_MS]
 *   位置落在末章内 → 跳到片尾（即直接结束本集，交由自动下一集）。
 *
 * @param chapters 章节列表（服务端 Chapters）。
 * @param positionMs 当前播放位置。
 * @param durationMs 总时长（0 = 未知，不启用）。
 */
internal fun computeSkipTarget(
    chapters: List<Chapter>,
    positionMs: Long,
    durationMs: Long,
): SkipTarget? {
    if (chapters.size < 2 || durationMs <= 0L) return null
    val nextStart = { index: Int -> chapters.getOrNull(index + 1)?.startMs ?: durationMs }

    // 片头：第 1 章
    val first = chapters.first()
    val firstEnd = nextStart(0)
    if (firstEnd - first.startMs >= SKIP_MIN_CHAPTER_MS &&
        positionMs in first.startMs until firstEnd &&
        isIntroName(first.name)
    ) {
        return SkipTarget(seekToMs = firstEnd, isIntro = true)
    }

    // 片尾：末章
    val last = chapters.last()
    if (durationMs - last.startMs >= SKIP_MIN_CHAPTER_MS &&
        positionMs >= last.startMs &&
        isCreditsName(last.name)
    ) {
        return SkipTarget(seekToMs = durationMs, isIntro = false)
    }
    return null
}

/** 片头章节名判定（中英双语，服务端章节命名不统一）。 */
private fun isIntroName(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return listOf("片头", "opening", "intro", "オープニング").any { n.contains(it) }
}

/** 片尾章节名判定。 */
private fun isCreditsName(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return listOf("片尾", "credits", "ending", "outro", "エンディング").any { n.contains(it) }
}

/** 跳过目标：跳到哪、是片头还是片尾。 */
internal data class SkipTarget(val seekToMs: Long, val isIntro: Boolean)

/**
 * 自动连播切换点（口径，纯函数便于单测）：
 *
 * 末章名为片尾 → 切换点 = 末章起点 + 10 秒（倒计时从片尾章起点起跑
 *   归零即换集——剪掉大段片尾，贴近结尾时收在结尾处）
 * 无片尾章 → 兜底切换点 = 结尾前 5 秒（倒计时在结尾前 15 秒起跑——
 *   几乎不剪内容，只保证 10 秒倒计时看得全）
 * 时长未知 → null（本集不启用自动连播窗口）。
 *
 * @param chapters 章节列表（服务端 Chapters）。
 * @param durationMs 总时长（0 = 未知）。
 * @return 切换点位置；null = 不启用。
 */
internal fun computeAutoNextSwitchAt(chapters: List<Chapter>, durationMs: Long): Long? {
    if (durationMs <= 0L) return null
    val creditsStart = chapters.lastOrNull()?.takeIf { isCreditsName(it.name) }?.startMs
    val afterCredits = creditsStart?.plus(AUTO_NEXT_COUNTDOWN_MS)
    return when {
        afterCredits != null -> afterCredits.coerceAtMost(durationMs)
        else -> (durationMs - AUTO_NEXT_FALLBACK_TAIL_MS).takeIf { it > 0L }
    }
}

/** 可跳章节的最小时长（短于 20 秒的「片头」多是误标，不提供跳过）。 */
private const val SKIP_MIN_CHAPTER_MS = 20_000L

/** 自动连播倒计时长度（10 秒）。 */
internal const val AUTO_NEXT_COUNTDOWN_MS = 10_000L

/** 自动连播倒计时秒数上限（展示取整用）。 */
internal const val AUTO_NEXT_COUNTDOWN_S = 10

/** 自动连播兜底：无片尾章时切换点距结尾的留量（几乎不剪内容）。 */
internal const val AUTO_NEXT_FALLBACK_TAIL_MS = 5_000L

/** 「跳过」浮钮距底边（与下一集浮钮错开一档）。 */
internal val SKIP_BUTTON_BOTTOM_PAD = 96.dp
