package com.novum.tv.ui.screen.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.core.player.subtitleLabel
import com.novum.tv.model.PlaybackPlan
import java.util.Locale

/** Reset the TEXT gate for every plan: a previous burned-in stream must not mute a direct retry. */
internal fun applySubtitleEnabledState(
    player: Player,
    burnedIn: Boolean,
    remembered: ItemPlaybackPrefsStore.ItemPrefs?,
    enabledByDefault: Boolean,
): Boolean {
    val disabled = burnedIn || remembered?.subtitleDisabled == true ||
        (!enabledByDefault && remembered?.subtitleTrack == null)
    if ((C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes) != disabled) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled).build()
    }
    return !disabled
}

/** The media item's tag follows actual playback, including fallback and same-player plan replacement. */
internal fun currentSubtitleLabel(player: Player): String {
    val plan = player.currentMediaItem?.localConfiguration?.tag as? PlaybackPlan
    return if (plan?.serverSubtitleSelection == true && plan.serverSubtitleStreamIndex == -1) "关闭"
        else plan?.burnedInSubtitleLabel ?: player.subtitleLabel()
}

internal fun canSelectTrack(player: Player, option: TrackOption, isText: Boolean): Boolean {
    if (!option.supported) return false
    if (option.id == TEXT_OFF_ID) return isText
    val parts = option.id.split(':')
    val groupIndex = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return false
    val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return false
    return group.type == (if (isText) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO) &&
        trackIndex in 0 until group.length && group.isTrackSupported(trackIndex)
}

/** Validate before updating the recovery snapshot; stale/unsupported rows must not replace memory. */
internal fun selectManualTrack(
    player: Player, option: TrackOption, isText: Boolean, remember: () -> Unit,
): Boolean {
    if (!canSelectTrack(player, option, isText)) return false
    remember()
    selectTrack(player, option, isText)
    return true
}

/**
 * 恢复条目记忆的字幕轨（手动改过的选项按条目记忆）：
 * 「关闭字幕」记忆 → 关文本轨；具体轨记忆 → 按「标题+语言」定位钉选。
 *
 * @return 字幕是否由记忆接管（接管则语言偏好/zhdual 不再介入）。
 */
internal fun applyRememberedSubtitle(
    player: Player,
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
internal fun applyRememberedAudio(player: Player, remembered: ItemPlaybackPrefsStore.ItemPrefs?) {
    remembered?.audioTrack?.let { overrideTrackByRef(player, C.TRACK_TYPE_AUDIO, it) }
}

/**
 * 按「标题+语言」记忆定位轨道组并钉选（与手动切轨同机制）。
 *
 * 目标组已选中时直接返回 true（防 onTracksChanged 回写参数造成事件循环）；
 * 找不到匹配组返回 false（回落语言偏好路径）。
 */
private fun overrideTrackByRef(
    player: Player,
    type: Int,
    ref: ItemPlaybackPrefsStore.TrackRef,
): Boolean {
    val groups = player.currentTracks.groups.filter { it.type == type }
    // Same-language tracks often have no title. Prefer the persisted format id before the
    // legacy label/language fallback, otherwise a saved Simplified track can restore Traditional.
    val target = ref.formatId?.let { id -> groups.firstMatchingTrack { it.id == id && trackMatchesRef(it, ref) } }
        ?: groups.firstMatchingTrack { trackMatchesRef(it, ref) } ?: return false
    if (target.group.isTrackSelected(target.index)) return true
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(type)
        .setOverrideForType(TrackSelectionOverride(target.group.mediaTrackGroup, target.index))
        .build()
    return true
}

/**
 * 记忆命中判定：容器原始标题（[ItemPlaybackPrefsStore.TrackRef.rawLabel]）相等为主，
 * 语言码与声道数做次级校验。
 *
 * 新记录比对 `Format.label`（容器没给标题时两边都是空串，改由语言/声道定位）——
 * 内嵌音/字幕轨通常没有 Format.label，旧实现拿「展示名 == Format.label」比对，
 * 这类轨的记忆永远恢复不了；旧记录（无 rawLabel）仍按展示名比对，行为与此前一致。
 */
internal fun trackMatchesRef(
    format: Format,
    ref: ItemPlaybackPrefsStore.TrackRef,
): Boolean {
    val labelMatches = if (ref.rawLabel != null) {
        format.label.orEmpty() == ref.rawLabel
    } else {
        format.label == ref.label
    }
    val languageMatches = ref.language == null || format.language == ref.language
    val channelsMatch = ref.channels == null || format.channelCount == ref.channels
    return labelMatches && languageMatches && channelsMatch
}

/**
 * 中英双语（zhdual）字幕钉选：语言码层面无 ISO 对应，ExoPlayer 原生
 * setPreferredTextLanguage 匹配不上——轨道就绪时按轨标题关键词选组，
 * 无命中回落简体中文（chi），再无则不动（保持 ExoPlayer 默认行为）。
 *
 * 用 [TrackSelectionOverride] 钉住目标组（与手动切轨同机制）；目标组已
 * 选中时直接返回，防 onTracksChanged 回写参数造成的事件循环。
 */
internal fun applyDualSubtitlePreference(player: Player) {
    applySubtitleLanguagePreference(player, "zhdual")
}

/** Chinese scripts must be matched explicitly; ISO chi/zh alone cannot distinguish 简/繁. */
internal fun applySubtitleLanguagePreference(player: Player, preferredLanguage: String) {
    if (preferredLanguage.isBlank()) return
    var target: TrackTarget? = null
    var bestScore = 0
    player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
        for (index in 0 until group.length) {
            if (!group.isTrackSupported(index)) continue
            val score = subtitleLanguageScore(group.getTrackFormat(index), preferredLanguage)
            if (score > bestScore) {
                bestScore = score
                target = TrackTarget(group, index)
            }
        }
    }
    val chosen = target ?: return
    if (chosen.group.isTrackSelected(chosen.index)) return
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setOverrideForType(TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.index))
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
}

internal fun subtitleLanguageTag(preference: String): String? = when (preference.lowercase()) {
    "", "zhdual" -> null
    "chi", "zho", "zhs", "chs", "zh", "zh-cn", "zh-hans" -> "zh-Hans"
    "zht", "cht", "zh-tw", "zh-hant" -> "zh-Hant"
    else -> preference
}

internal fun subtitleLanguageScore(format: Format, preference: String): Int {
    val language = format.language.orEmpty().lowercase()
    val label = format.label.orEmpty().lowercase()
    val traditional = language in setOf("zht", "cht", "zh-tw", "zh-hk", "zh-hant") ||
        listOf("繁", "traditional", "hant", "cht").any(label::contains)
    val simplified = language in setOf("zhs", "chs", "zh-cn", "zh-hans") ||
        listOf("简", "簡", "simplified", "hans", "chs").any(label::contains)
    val dual = DUAL_SUBTITLE_TITLE_KEYWORDS.any(label::contains)
    val chinese = traditional || simplified || dual || language in setOf("zh", "chi", "zho")
    return when (subtitleLanguageTag(preference)) {
        "zh-Hans" -> when { traditional && !simplified -> 0; simplified -> 400; chinese -> 200; else -> 0 }
        "zh-Hant" -> when { simplified && !traditional -> 0; traditional -> 400; chinese -> 200; else -> 0 }
        null -> if (preference == "zhdual") when { dual -> 500; simplified -> 300; chinese -> 100; else -> 0 } else 0
        else -> if (Locale.forLanguageTag(language).language == Locale.forLanguageTag(preference).language) 300 else 0
    }
}

/**
 * 应用一条轨道选择。
 *
 * @param player 播放器。
 * @param option 目标轨道；[TEXT_OFF_ID] 表示关闭字幕。
 * @param isText true=字幕，false=音轨。
 */
internal fun selectTrack(player: Player, option: TrackOption, isText: Boolean) {
    if (!canSelectTrack(player, option, isText)) return
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
    val expectedType = if (isText) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    if (group.type != expectedType || trackIndex !in 0 until group.length || !group.isTrackSupported(trackIndex)) return
    params.setOverrideForType(
        TrackSelectionOverride(group.mediaTrackGroup, trackIndex),
    )
    if (isText) params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
    player.trackSelectionParameters = params.build()
}

private data class TrackTarget(val group: Tracks.Group, val index: Int)

/** 自适应轨道组可以包含多个格式，选项与记忆都必须定位到组内实际索引。 */
private fun List<Tracks.Group>.firstMatchingTrack(predicate: (Format) -> Boolean): TrackTarget? {
    for (group in this) {
        for (index in 0 until group.length) {
            if (group.isTrackSupported(index) && predicate(group.getTrackFormat(index))) return TrackTarget(group, index)
        }
    }
    return null
}

internal fun buildTrackOptions(
    tracks: Tracks,
    type: Int,
    audioFallback: String,
    textFallback: String,
    channelsTemplate: String,
): List<TrackOption> = buildList {
    tracks.groups.forEachIndexed { groupIndex, group ->
        if (group.type != type) return@forEachIndexed
        for (trackIndex in 0 until group.length) {
            val format = group.getTrackFormat(trackIndex)
            add(
                TrackOption(
                    id = "$groupIndex:$trackIndex",
                    label = buildTrackLabel(format, type, audioFallback, textFallback, channelsTemplate),
                    selected = group.isTrackSelected(trackIndex),
                    language = format.language,
                    rawLabel = format.label.orEmpty(),
                    formatId = format.id,
                    supported = group.isTrackSupported(trackIndex),
                    channels = if (type == C.TRACK_TYPE_AUDIO) format.channelCount.takeIf { it > 0 } else null,
                    codec = if (type == C.TRACK_TYPE_AUDIO) audioCodecLabel(format) else null,
                ),
            )
        }
    }
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

/**
 * 音轨编码展示名：从 Media3 [Format] 的 codec/MIME 提取并规范化。
 * 与详情页音频徽章同口径；无编码信息时返回 null（不占用行尾位置）。
 */
internal fun audioCodecLabel(format: Format): String? {
    val mime = format.sampleMimeType ?: format.codecs
    val raw = mime
        ?.substringAfterLast('/', missingDelimiterValue = mime)
        ?.substringBefore(',')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return when (raw.uppercase()) {
        "TRUEHD", "MLP" -> "TrueHD"
        "EAC3", "EC-3" -> "E-AC3"
        "AC3" -> "AC-3"
        "DTS-HD", "DTSHD" -> "DTS-HD"
        "DTSX" -> "DTS:X"
        "VORBIS" -> "Vorbis"
        "OPUS" -> "Opus"
        else -> raw.uppercase()
    }
}

/** 双语轨标题关键词（服务端 DisplayTitle 常见写法）。 */
private val DUAL_SUBTITLE_TITLE_KEYWORDS = listOf("双语", "中英", "简英")

/** 字幕「关闭」项的稳定 id。 */
internal const val TEXT_OFF_ID = "text:off"
