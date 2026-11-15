package com.novum.tv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 基于 Preferences DataStore 的设置实现。
 *
 * 设计取舍：不用 SharedPreferences —— DataStore 天然异步、不会在主线程做磁盘 IO，
 * 也不会出现 TV 低端盒子上 `apply()` 卡顿导致的掉帧。
 *
 * @param dataStore 由 Hilt 提供的进程级单例。
 */
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data
        .catch { throwable ->
            // 磁盘损坏时退回默认值，避免整个应用因读取设置失败而无法启动
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toAppSettings() }
        .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toAppSettings()).sanitized()
            preferences[SettingsKeys.PreferDirect] = next.preferDirect
            preferences[SettingsKeys.AudioPassthrough] = next.audioPassthrough
            preferences[SettingsKeys.SubtitleEnabled] = next.subtitleEnabledByDefault
            preferences[SettingsKeys.SubtitleLanguage] = next.subtitleLanguage
            preferences[SettingsKeys.SubtitleSize] = next.subtitleSize
            preferences[SettingsKeys.SubtitleOffsetLevel] = next.subtitleOffsetLevel
            preferences[SettingsKeys.AudioLanguage] = next.audioLanguage
            preferences[SettingsKeys.VersionStrategy] = next.versionStrategy
            preferences[SettingsKeys.AssBurnIn] = next.assBurnIn
            preferences[SettingsKeys.FailedVideoCodecs] = next.failedVideoCodecs
            preferences[SettingsKeys.SkipIntro] = next.skipIntro
            preferences[SettingsKeys.SkipIntroSeconds] = next.skipIntroSeconds
            preferences[SettingsKeys.PinnedLineUrl] = next.pinnedLineUrl
            preferences[SettingsKeys.PinnedLineName] = next.pinnedLineName
            preferences[SettingsKeys.PlaybackSpeed] = next.playbackSpeed
        }
    }
}

/** DataStore 键位；命名保持稳定，升级时不要随意改动，否则用户设置会丢失。 */
private object SettingsKeys {
    val PreferDirect = booleanPreferencesKey("prefer_direct")
    val AudioPassthrough = booleanPreferencesKey("audio_passthrough")
    val SubtitleEnabled = booleanPreferencesKey("subtitle_enabled")
    val SubtitleLanguage = stringPreferencesKey("subtitle_language")
    val SubtitleSize = intPreferencesKey("subtitle_size")
    val SubtitleOffsetLevel = intPreferencesKey("subtitle_offset_level")
    val AudioLanguage = stringPreferencesKey("audio_language")
    val VersionStrategy = stringPreferencesKey("version_strategy")
    val AssBurnIn = booleanPreferencesKey("ass_burn_in")
    val FailedVideoCodecs = stringPreferencesKey("failed_video_codecs")
    val SkipIntro = booleanPreferencesKey("skip_intro")
    val SkipIntroSeconds = intPreferencesKey("skip_intro_seconds")
    val PinnedLineUrl = stringPreferencesKey("pinned_line_url")
    val PinnedLineName = stringPreferencesKey("pinned_line_name")
    val PlaybackSpeed = floatPreferencesKey("playback_speed")
}

/** 把持久化层数据映射为不可变设置快照，缺失键回落到默认值。 */
private fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    return AppSettings(
        preferDirect = this[SettingsKeys.PreferDirect] ?: defaults.preferDirect,
        audioPassthrough = this[SettingsKeys.AudioPassthrough] ?: defaults.audioPassthrough,
        subtitleEnabledByDefault = this[SettingsKeys.SubtitleEnabled]
            ?: defaults.subtitleEnabledByDefault,
        subtitleLanguage = this[SettingsKeys.SubtitleLanguage] ?: defaults.subtitleLanguage,
        subtitleSize = this[SettingsKeys.SubtitleSize] ?: defaults.subtitleSize,
        subtitleOffsetLevel = this[SettingsKeys.SubtitleOffsetLevel] ?: defaults.subtitleOffsetLevel,
        audioLanguage = this[SettingsKeys.AudioLanguage] ?: defaults.audioLanguage,
        versionStrategy = this[SettingsKeys.VersionStrategy] ?: defaults.versionStrategy,
        assBurnIn = this[SettingsKeys.AssBurnIn] ?: defaults.assBurnIn,
        failedVideoCodecs = this[SettingsKeys.FailedVideoCodecs] ?: defaults.failedVideoCodecs,
        skipIntro = this[SettingsKeys.SkipIntro] ?: defaults.skipIntro,
        skipIntroSeconds = this[SettingsKeys.SkipIntroSeconds] ?: defaults.skipIntroSeconds,
        pinnedLineUrl = this[SettingsKeys.PinnedLineUrl] ?: defaults.pinnedLineUrl,
        pinnedLineName = this[SettingsKeys.PinnedLineName] ?: defaults.pinnedLineName,
        playbackSpeed = this[SettingsKeys.PlaybackSpeed] ?: defaults.playbackSpeed,
    )
}

/** 把设置项约束到合法区间，防止越界值写入磁盘。 */
private fun AppSettings.sanitized(): AppSettings = copy(
    subtitleSize = subtitleSize.coerceIn(1, 4),
    subtitleOffsetLevel = subtitleOffsetLevel.coerceIn(0, 4),
    skipIntroSeconds = skipIntroSeconds.coerceIn(15, 600),
)
