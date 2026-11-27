package com.novum.tv.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novum.tv.model.DanmuArea
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置仓储的持久化行为测试。
 *
 * 使用真实 DataStore（临时文件）+ Robolectric，验证默认值、写回与越界收敛，
 * 而不是只测内存假实现。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun CoroutineScope.createRepository(): DataStoreSettingsRepository {
        return DataStoreSettingsRepository(createDataStore())
    }

    private fun CoroutineScope.createDataStore(): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        scope = this,
        produceFile = { File(temporaryFolder.root, "novum_settings.preferences_pb") },
    )

    @Test
    fun defaultsMatchLegacyBehaviour() = runTest {
        val repository = createRepository()

        val settings = repository.settings.first()

        assertTrue("优先直连默认开启", settings.preferDirect)
        assertTrue("音频直通默认开启", settings.audioPassthrough)
        assertTrue("字幕默认开启", settings.subtitleEnabledByDefault)
        assertTrue("未保存设置时 ASS 字幕烧录默认开启", settings.assBurnIn)
        assertEquals("片头默认 90 秒", 90, settings.skipIntroSeconds)
    }

    @Test
    fun persistsUpdatedValues() = runTest {
        val repository = createRepository()

        repository.update { it.copy(preferDirect = false, audioPassthrough = false) }

        val settings = repository.settings.first()
        assertFalse(settings.preferDirect)
        assertFalse(settings.audioPassthrough)
    }

    @Test
    fun savedAssBurnInOptOutSurvivesReloadAndUnrelatedWrites() = runTest {
        val dataStore = createDataStore()
        val key = booleanPreferencesKey("ass_burn_in")
        dataStore.edit { it[key] = false }
        val repository = DataStoreSettingsRepository(dataStore)
        assertFalse("An existing false must not be replaced by the enabled default", repository.settings.first().assBurnIn)

        repository.update { it.copy(audioPassthrough = false) }

        assertFalse(DataStoreSettingsRepository(dataStore).settings.first().assBurnIn)
        assertEquals(false, dataStore.data.first()[key])
        repository.update { it.copy(assBurnIn = true) }
        assertTrue(DataStoreSettingsRepository(dataStore).settings.first().assBurnIn)
        repository.update { it.copy(assBurnIn = false) }
        assertFalse(DataStoreSettingsRepository(dataStore).settings.first().assBurnIn)
    }

    @Test
    fun persistsAutoPlayNextToggle() = runTest {
        val repository = createRepository()
        assertTrue("自动连播默认开启", repository.settings.first().autoPlayNext)

        repository.update { it.copy(autoPlayNext = false) }

        assertFalse(
            "关闭自动连播后必须真的写盘，否则每次启动都会回落到默认 true",
            repository.settings.first().autoPlayNext,
        )
    }

    @Test
    fun clampsOutOfRangeValues() = runTest {
        val repository = createRepository()

        repository.update { it.copy(subtitleSize = 99, skipIntroSeconds = 1) }

        val settings = repository.settings.first()
        assertEquals("字幕字号上限 4", 4, settings.subtitleSize)
        assertEquals("片头下限 15 秒", 15, settings.skipIntroSeconds)
    }

    @Test
    fun subtitleWeightPersistsAndLegacyOrInvalidValuesStayUsable() = runTest {
        val dataStore = createDataStore()
        val repository = DataStoreSettingsRepository(dataStore)
        assertEquals(1, repository.settings.first().subtitleWeight)

        repository.update { it.copy(subtitleWeight = 0) }
        assertEquals(0, DataStoreSettingsRepository(dataStore).settings.first().subtitleWeight)
        repository.update { it.copy(subtitleWeight = 2) }
        assertEquals(2, DataStoreSettingsRepository(dataStore).settings.first().subtitleWeight)

        repository.update { it.copy(subtitleWeight = 99) }
        assertEquals(2, repository.settings.first().subtitleWeight)
        dataStore.edit { it[intPreferencesKey("subtitle_weight")] = -10 }
        assertEquals(0, repository.settings.first().subtitleWeight)
    }

    @Test
    fun danmuDefaultsFollowOfficialPlayer() = runTest {
        val repository = createRepository()

        val settings = repository.settings.first()

        assertFalse("弹幕默认关（对齐官方网页播放器）", settings.danmuEnabled)
        assertEquals("透明度默认 0.7", 0.7f, settings.danmuOpacity)
        assertEquals("密度默认 0.7", 0.7f, settings.danmuDensity)
        assertEquals("默认上半屏", DanmuArea.Half, settings.danmuArea)
        assertEquals("默认中号字", 2, settings.danmuSize)
        assertEquals("默认标准速", 2, settings.danmuSpeed)
    }

    @Test
    fun persistsDanmuValues() = runTest {
        val repository = createRepository()

        repository.update { it.copy(danmuEnabled = true, danmuArea = DanmuArea.Full, danmuSize = 3, danmuSpeed = 1) }

        val settings = repository.settings.first()
        assertTrue(settings.danmuEnabled)
        assertEquals(DanmuArea.Full, settings.danmuArea)
        assertEquals(3, settings.danmuSize)
        assertEquals(1, settings.danmuSpeed)
    }

    @Test
    fun legacyDanmuRegionSurvivesAnUnrelatedSettingsWrite() = runTest {
        val dataStore = createDataStore()
        val repository = DataStoreSettingsRepository(dataStore)
        val legacyArea = booleanPreferencesKey("danmu_area_full")
        dataStore.edit { it[legacyArea] = false }
        assertEquals(DanmuArea.Half, repository.settings.first().danmuConfig().area)

        dataStore.edit { it[legacyArea] = true }
        assertEquals(DanmuArea.Full, repository.settings.first().danmuConfig().area)
        repository.update { it.copy(audioPassthrough = false) }

        assertEquals(DanmuArea.Full, repository.settings.first().danmuArea)
        assertNull(dataStore.data.first()[legacyArea])
        assertEquals("Full", dataStore.data.first()[stringPreferencesKey("danmu_area")])
    }

    @Test
    fun topRegionPersistsWithoutFallingBackToTheLegacyFullScreenValue() = runTest {
        val dataStore = createDataStore()
        val repository = DataStoreSettingsRepository(dataStore)
        repository.update { it.copy(danmuArea = DanmuArea.Top) }
        dataStore.edit { it[booleanPreferencesKey("danmu_area_full")] = true }

        assertEquals(DanmuArea.Top, repository.settings.first().danmuArea)
        assertEquals(0.25f, repository.settings.first().danmuConfig().area.fraction)
        repository.update { it.copy(danmuEnabled = true) }
        assertEquals(DanmuArea.Top, repository.settings.first().danmuConfig().area)
    }

    @Test
    fun clampsDanmuOutOfRangeValues() = runTest {
        val repository = createRepository()

        repository.update { it.copy(danmuOpacity = 5f, danmuDensity = 0f, danmuSize = 9, danmuSpeed = 0) }

        val settings = repository.settings.first()
        assertEquals("透明度上限 1", 1f, settings.danmuOpacity)
        assertEquals("密度下限 0.1", 0.1f, settings.danmuDensity)
        assertEquals("字号档位收敛 1–3", 3, settings.danmuSize)
        assertEquals("速度档位收敛 1–3", 1, settings.danmuSpeed)
    }

    @Test
    fun nonFiniteDanmuUpdatesFallBackToUsableDefaults() = runTest {
        val repository = createRepository()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            repository.update { it.copy(danmuOpacity = invalid, danmuDensity = invalid) }

            val settings = repository.settings.first()
            assertEquals(AppSettings().danmuOpacity, settings.danmuOpacity)
            assertEquals(AppSettings().danmuDensity, settings.danmuDensity)
            assertEquals(settings.danmuOpacity, settings.danmuConfig().opacity)
            assertEquals(settings.danmuDensity, settings.danmuConfig().density)
        }
    }

    @Test
    fun invalidStoredSettingsAreSanitizedBeforeReachingThePlayer() = runTest {
        val dataStore = createDataStore()
        dataStore.edit {
            it[floatPreferencesKey("danmu_opacity")] = Float.NaN
            it[floatPreferencesKey("danmu_density")] = 5f
            it[floatPreferencesKey("ui_scale")] = Float.POSITIVE_INFINITY
            it[intPreferencesKey("subtitle_size")] = 99
            it[intPreferencesKey("subtitle_offset_level")] = -1
            it[intPreferencesKey("skip_intro_seconds")] = 0
            it[intPreferencesKey("danmu_size")] = 0
            it[intPreferencesKey("danmu_speed")] = 99
            it[booleanPreferencesKey("prefer_direct")] = false
        }

        val settings = DataStoreSettingsRepository(dataStore).settings.first()

        assertEquals(AppSettings().danmuOpacity, settings.danmuConfig().opacity)
        assertEquals(1f, settings.danmuConfig().density)
        assertEquals(1f, settings.uiScale)
        assertEquals(4, settings.subtitleSize)
        assertEquals(0, settings.subtitleOffsetLevel)
        assertEquals(15, settings.skipIntroSeconds)
        assertEquals(1, settings.danmuSize)
        assertEquals(3, settings.danmuSpeed)
        assertFalse(settings.preferDirect)
    }

    @Test
    fun persistsPinnedLineOwnerWithoutChangingOtherSettings() = runTest {
        val repository = createRepository()
        repository.update {
            it.copy(preferDirect = false, subtitleSize = 3, playbackSpeed = 1.5f, danmuEnabled = true)
        }
        val previous = repository.settings.first()

        repository.update {
            it.copy(
                pinnedLineUrl = "https://line-a.example",
                pinnedLineName = "线路 A",
                pinnedLineAccountKey = "account-a-hash",
            )
        }

        val settings = repository.settings.first()
        assertEquals(
            previous.copy(
                pinnedLineUrl = "https://line-a.example",
                pinnedLineName = "线路 A",
                pinnedLineAccountKey = "account-a-hash",
            ),
            settings,
        )
        assertEquals("https://line-a.example", settings.pinnedLineUrlFor("account-a-hash"))
        assertNull(settings.pinnedLineUrlFor("account-b-hash"))
    }

    @Test
    fun legacyPinWithoutOwnerIsNotAssignedToTheNextAccount() = runTest {
        val dataStore = createDataStore()
        dataStore.edit {
            it[stringPreferencesKey("pinned_line_url")] = "https://legacy-line.example"
            it[stringPreferencesKey("pinned_line_name")] = "旧线路"
        }
        val repository = DataStoreSettingsRepository(dataStore)
        val legacy = repository.settings.first()
        assertEquals("https://legacy-line.example", legacy.pinnedLineUrl)
        assertEquals("旧线路", legacy.pinnedLineName)
        assertEquals("", legacy.pinnedLineAccountKey)
        assertNull(legacy.pinnedLineUrlFor("account-a-hash"))

        repository.update { it.copy(subtitleSize = 4) }

        val updated = repository.settings.first()
        assertEquals(legacy.copy(subtitleSize = 4), updated)
        assertNull(updated.pinnedLineUrlFor("account-b-hash"))
    }
}
