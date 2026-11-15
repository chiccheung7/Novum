package com.novum.tv.ui.screen.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import java.util.Locale

/**
 * 恢复条目记忆的字幕轨（手动改过的选项按条目记忆）：
 * 「关闭字幕」记忆 → 关文本轨；具体轨记忆 → 按「标题+语言」定位钉选。
 *
 * @return 字幕是否由记忆接管（接管则语言偏好/zhdual 不再介入）。
 */
internal fun applyRememberedSubtitle(
    player: ExoPlayer,
    remembered: ItemPlaybackPrefsStore.ItemPrefs?,
): Boolean {
    if (remembered == null) return false
    if (remembered.subtitleDisabled) {
        if (C.TRACK_TYPE_TEXT !in player.trackSelectionParameters.disabledTrackTypes) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        return true
    }
    val ref = remembered.subtitleTrack ?: return false
    val applied = overrideTrackByRef(player, C.TRACK_TYPE_TEXT, ref)
    if (applied) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }
    return applied
}

/** 恢复条目记忆的音轨；无记忆或轨不匹配（换版本后轨道变了）时不动。 */
internal fun applyRememberedAudio(player: ExoPlayer, remembered: ItemPlaybackPrefsStore.ItemPrefs?) {
    remembered?.audioTrack?.let { overrideTrackByRef(player, C.TRACK_TYPE_AUDIO, it) }
}

/**
 * 按「标题+语言」记忆定位轨道组并钉选（与手动切轨同机制）。
 *
 * 目标组已选中时直接返回 true（防 onTracksChanged 回写参数造成事件循环）；
 * 找不到匹配组返回 false（回落语言偏好路径）。
 */
private fun overrideTrackByRef(
    player: ExoPlayer,
    type: Int,
    ref: ItemPlaybackPrefsStore.TrackRef,
): Boolean {
    val target = player.currentTracks.groups.firstOrNull { group ->
        group.type == type && trackMatchesRef(group.getTrackFormat(0), ref)
    } ?: return false
    if (target.isTrackSelected(0)) return true
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        .build()
    return true
}

/** 记忆命中判定：标题相等为主（跨会话稳定锚），语言码做次级校验。 */
private fun trackMatchesRef(
    format: Format,
    ref: ItemPlaybackPrefsStore.TrackRef,
): Boolean = format.label == ref.label && (ref.language == null || format.language == ref.language)

/**
 * 中英双语（zhdual）字幕钉选：语言码层面无 ISO 对应，ExoPlayer 原生
 * setPreferredTextLanguage 匹配不上——轨道就绪时按轨标题关键词选组，
 * 无命中回落简体中文（chi），再无则不动（保持 ExoPlayer 默认行为）。
 *
 * 用 [TrackSelectionOverride] 钉住目标组（与手动切轨同机制）；目标组已
 * 选中时直接返回，防 onTracksChanged 回写参数造成的事件循环。
 */
internal fun applyDualSubtitlePreference(player: ExoPlayer) {
    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val target = groups.firstOrNull { group ->
        val format = group.getTrackFormat(0)
        DUAL_SUBTITLE_TITLE_KEYWORDS.any { format.label?.contains(it) == true }
    } ?: groups.firstOrNull { group -> group.getTrackFormat(0).language == "chi" } ?: return
    if (target.isTrackSelected(0)) return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setOverrideForType(TrackSelectionOverride(target.mediaTrackGroup, 0))
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
}

/**
 * 应用一条轨道选择。
 *
 * @param player 播放器。
 * @param option 目标轨道；[TEXT_OFF_ID] 表示关闭字幕。
 * @param isText true=字幕，false=音轨。
 */
internal fun selectTrack(player: ExoPlayer, option: TrackOption, isText: Boolean) {
    val params = player.trackSelectionParameters.buildUpon()
    if (option.id == TEXT_OFF_ID) {
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        player.trackSelectionParameters = params.build()
        return
    }
    val parts = option.id.split(":")
    val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return
    val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return
    val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
    params.setOverrideForType(
        TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
    )
    if (isText) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    player.trackSelectionParameters = params.build()
}

/**
 * 轨道展示名：优先容器 label → 语言本地名 → 类型兜底；多声道音轨补声道数。
 * 兜底文案与声道模板由调用方在组合期解析后传入（本函数不在组合上下文）。
 */
internal fun buildTrackLabel(
    format: Format,
    type: Int,
    audioFallback: String,
    textFallback: String,
    channelsTemplate: String,
): String {
    val base = format.label?.takeIf { it.isNotBlank() }
        ?: format.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            // 原 runCatching 包 Locale.forLanguageTag（纯内存、非法
            // tag 返回 und，不抛）——无效防御，等价重写
            ?.let { Locale.forLanguageTag(it).displayLanguage }
        ?: if (type == C.TRACK_TYPE_AUDIO) audioFallback else textFallback
    return if (type == C.TRACK_TYPE_AUDIO && format.channelCount > 2) {
        "$base · ${String.format(channelsTemplate, format.channelCount)}"
    } else {
        base
    }
}

/** 双语轨标题关键词（服务端 DisplayTitle 常见写法）。 */
private val DUAL_SUBTITLE_TITLE_KEYWORDS = listOf("双语", "中英", "简英")

/** 字幕「关闭」项的稳定 id。 */
internal const val TEXT_OFF_ID = "text:off"
