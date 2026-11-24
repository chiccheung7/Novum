package com.novum.tv.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val dataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { File(temporaryFolder.root, "novum_settings.preferences_pb") },
        )
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun defaultsMatchLegacyBehaviour() = runTest {
        val repository = createRepository()

        val settings = repository.settings.first()

        assertTrue("优先直连默认开启", settings.preferDirect)
        assertTrue("音频直通默认开启", settings.audioPassthrough)
        assertTrue("字幕默认开启", settings.subtitleEnabledByDefault)
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
    fun danmuDefaultsFollowOfficialPlayer() = runTest {
        val repository = createRepository()

        val settings = repository.settings.first()

        assertFalse("弹幕默认关（对齐官方网页播放器）", settings.danmuEnabled)
        assertEquals("透明度默认 0.7", 0.7f, settings.danmuOpacity)
        assertEquals("密度默认 0.7", 0.7f, settings.danmuDensity)
        assertFalse("默认上半屏", settings.danmuAreaFull)
        assertEquals("默认中号字", 2, settings.danmuSize)
        assertEquals("默认标准速", 2, settings.danmuSpeed)
    }

    @Test
    fun persistsDanmuValues() = runTest {
        val repository = createRepository()

        repository.update { it.copy(danmuEnabled = true, danmuAreaFull = true, danmuSize = 3, danmuSpeed = 1) }

        val settings = repository.settings.first()
        assertTrue(settings.danmuEnabled)
        assertTrue(settings.danmuAreaFull)
        assertEquals(3, settings.danmuSize)
        assertEquals(1, settings.danmuSpeed)
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
}
