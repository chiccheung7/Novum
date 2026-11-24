package com.novum.tv.ui.screen.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.common.feedback.ToastSink
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.SessionStore
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 字幕字号档位 → 文案资源。Toast 与行内值共用，保证两处说法一致。
 */
internal fun Int.labelRes(): Int = when (this) {
    1 -> R.string.subtitle_size_small
    3 -> R.string.subtitle_size_large
    4 -> R.string.subtitle_size_xlarge
    else -> R.string.subtitle_size_medium
}

/**
 * 设置页 ViewModel。
 *
 * 职责：
 * - 把 [SettingsRepository] 的 `Flow<AppSettings>` 转为页面可直接消费的 [UiState]；
 * - 提供语义化的事件方法（toggle/cycle），页面不接触 `copy` 细节；
 * - 每次变更经 [ToastSink] 给出即时反馈；
 * - 读取失败时通过 [reload] 重新订阅，而不是让页面重启；
 * - 退出登录：清会话后回调，由页面导航回登录页。
 *
 * @param settingsRepository 设置仓储，生产环境由 Hilt 注入 DataStore 实现。
 * @param authRepository 账号仓储，用于退出登录。
 * @param sessionStore 会话存储，用于展示当前连接信息。
 * @param toast 全局提示队列。
 * @param appContext 应用上下文，仅用于取字符串资源。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val sessionStore: SessionStore,
    private val toast: ToastSink,
    private val logger: NovumLogger,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val reloadTrigger = MutableSharedFlow<Unit>(replay = 1).apply { tryEmit(Unit) }

    /**
     * 设置页状态。
     *
     * 采用 `WhileSubscribed(5s)`：页面退到后台 5 秒后停止订阅，
     * 避免 TV 待机时仍在监听磁盘。
     */
    val uiState: StateFlow<UiState<AppSettings>> = reloadTrigger
        .flatMapLatest {
            settingsRepository.settings
                .map<AppSettings, UiState<AppSettings>> { UiState.Success(it) }
                .catch { throwable -> emit(UiState.Error(NovumError.Unknown(throwable))) }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    private val _sessionLabel = MutableStateFlow("")
    val sessionLabel: StateFlow<String> = _sessionLabel.asStateFlow()

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    /**
     * 档位循环的同步游标：DataStore 回发可能慢于遥控器连续按键，
     * 只读 StateFlow 快照会让两次点击算出同一个目标档。写成功后清空。
     */
    private var subtitleSizePending: Int? = null
    private var skipIntroSecondsPending: Int? = null

    init {
        loadSessionLabel()
    }

    /** 重新订阅设置流；用于错误态的「重试」。 */
    fun reload() {
        reloadTrigger.tryEmit(Unit)
    }

    /** 切换「优先直连播放」。 */
    fun setPreferDirect(enabled: Boolean) {
        update { it.copy(preferDirect = enabled) }
        toastPref(R.string.settings_prefer_direct, enabled)
    }

    /** 切换「音频直通」。 */
    fun setAudioPassthrough(enabled: Boolean) {
        update { it.copy(audioPassthrough = enabled) }
        toastPref(R.string.settings_audio_passthrough, enabled)
    }

    /** 字幕语言偏好，下一次起播生效。 */
    fun setSubtitleLanguage(language: String) {
        update { it.copy(subtitleLanguage = language) }
    }

    /** 音轨语言偏好。 */
    fun setAudioLanguage(language: String) {
        update { it.copy(audioLanguage = language) }
    }

    /** 版本画质策略（highest/fhd/空=跟随默认）。 */
    fun setVersionStrategy(strategy: String) {
        update { it.copy(versionStrategy = strategy) }
    }

    /** ASS 字幕烧录保样式（开启 = 协商声明 Encode 走转码）。 */
    fun setAssBurnIn(enabled: Boolean) {
        update { it.copy(assBurnIn = enabled) }
    }

    fun setSubtitleEnabled(enabled: Boolean) {
        update { it.copy(subtitleEnabledByDefault = enabled) }
        toastPref(R.string.settings_subtitle_default, enabled)
    }

    /** 字幕字号循环切换：小 → 中 → 大 → 超大 → 小。 */
    fun cycleSubtitleSize() {
        // 同步游标 + DataStore 事务内写绝对档：连续快按不会都基于同一旧快照
        val current = subtitleSizePending ?: currentSettings().subtitleSize
        val next = if (current >= MAX_SUBTITLE_SIZE) MIN_SUBTITLE_SIZE else current + 1
        subtitleSizePending = next
        update(
            onSuccess = { if (subtitleSizePending == next) subtitleSizePending = null },
            transform = { it.copy(subtitleSize = next) },
        )
        toastCycled(appContext.getString(R.string.settings_subtitle_size), next.labelRes())
    }

    /** 切换「自动跳过片头」；播放页据此把起播位置挪到片头之后。 */
    fun setSkipIntro(enabled: Boolean) {
        update { it.copy(skipIntro = enabled) }
        toastPref(R.string.settings_skip_intro, enabled)
    }

    /** 自动连播开关：默认开启，片尾倒计时 10 秒，任意键取消。 */
    fun setAutoPlayNext(enabled: Boolean) {
        update { it.copy(autoPlayNext = enabled) }
        toastPref(R.string.settings_autoplay_next, enabled)
    }

    /** 片头时长循环切换：30 → 60 → 90 → 120 → 30 秒。 */
    fun cycleSkipIntroSeconds() {
        val current = skipIntroSecondsPending ?: currentSettings().skipIntroSeconds
        val next = SKIP_INTRO_STEPS.firstOrNull { it > current } ?: SKIP_INTRO_STEPS.first()
        skipIntroSecondsPending = next
        update(
            onSuccess = { if (skipIntroSecondsPending == next) skipIntroSecondsPending = null },
            transform = { it.copy(skipIntroSeconds = next) },
        )
        toastCycled(
            appContext.getString(R.string.settings_skip_intro_seconds),
            appContext.getString(R.string.settings_seconds, next),
        )
    }

    /** 弹幕开关（UHDNOW 原生能力；播放抽屉的即时调节也写回同一存储）。 */
    fun setDanmuEnabled(enabled: Boolean) {
        update { it.copy(danmuEnabled = enabled) }
        toastPref(R.string.danmu_enabled, enabled)
    }

    /** 弹幕显示区域：上半屏 ↔ 全屏。 */
    fun cycleDanmuArea() {
        val before = currentSettings().danmuAreaFull
        update { it.copy(danmuAreaFull = !it.danmuAreaFull) }
        toastCycled(
            appContext.getString(R.string.danmu_area),
            appContext.getString(if (before) R.string.danmu_area_half else R.string.danmu_area_full),
        )
    }

    /** 弹幕字号循环：小 → 中 → 大 → 小。 */
    fun cycleDanmuSize() {
        val next = currentSettings().danmuSize % 3 + 1
        update { it.copy(danmuSize = next) }
        toastCycled(
            appContext.getString(R.string.settings_subtitle_size),
            appContext.getString(
                when (next) {
                    1 -> R.string.subtitle_size_small
                    3 -> R.string.subtitle_size_large
                    else -> R.string.subtitle_size_medium
                },
            ),
        )
    }

    /** 弹幕滚动速度循环：慢 → 标准 → 快 → 慢。 */
    fun cycleDanmuSpeed() {
        val next = currentSettings().danmuSpeed % 3 + 1
        update { it.copy(danmuSpeed = next) }
        toastCycled(
            appContext.getString(R.string.danmu_speed),
            appContext.getString(
                when (next) {
                    1 -> R.string.danmu_speed_slow
                    3 -> R.string.danmu_speed_fast
                    else -> R.string.danmu_speed_normal
                },
            ),
        )
    }

    /** 弹幕透明度循环：10% → … → 100% → 10%（步进 10%）。 */
    fun cycleDanmuOpacity() {
        val next = if (currentSettings().danmuOpacity >= 1f) 0.1f else currentSettings().danmuOpacity + 0.1f
        val quantized = (next * 10).toInt() / 10f
        update { it.copy(danmuOpacity = quantized) }
        toastCycled(appContext.getString(R.string.danmu_opacity), "${(quantized * 100).toInt()}%")
    }

    /** 弹幕密度循环：10% → … → 100% → 10%（步进 10%）。 */
    fun cycleDanmuDensity() {
        val next = if (currentSettings().danmuDensity >= 1f) 0.1f else currentSettings().danmuDensity + 0.1f
        val quantized = (next * 10).toInt() / 10f
        update { it.copy(danmuDensity = quantized) }
        toastCycled(appContext.getString(R.string.danmu_density), "${(quantized * 100).toInt()}%")
    }

    /**
     * 退出登录：只有本地会话真正清除后才导航回登录页。
     *
     * 清理失败却跳走会让用户以为已退出，下次启动又用磁盘上的旧 token 自动登录。
     *
     * @param onDone 会话清理成功后的导航回调。
     */
    fun logout(onDone: () -> Unit) {
        if (_loggingOut.value) return
        _loggingOut.value = true
        viewModelScope.launch {
            val result = runCatching { authRepository.logout() }
            _loggingOut.value = false
            result.onFailure {
                logger.w(TAG, "退出登录清理失败", it)
                toast.show(appContext.getString(R.string.settings_logout_failed))
            }.onSuccess { onDone() }
        }
    }

    /** 读取账号区展示的用户名。 */
    private fun loadSessionLabel() {
        viewModelScope.launch {
            val session = runCatching { sessionStore.load() }.getOrNull() ?: return@launch
            _sessionLabel.value = session.userName
        }
    }

    /** 读取当前设置快照；页面未就绪时返回默认值。 */
    private fun currentSettings(): AppSettings =
        (uiState.value as? UiState.Success)?.data ?: AppSettings()

    /** 统一的写回入口：所有设置变更都走 DataStore 事务。 */
    private fun update(
        onSuccess: (() -> Unit)? = null,
        transform: (AppSettings) -> AppSettings,
    ) {
        viewModelScope.launch {
            runCatching { settingsRepository.update(transform) }
                .onSuccess { onSuccess?.invoke() }
                .onFailure { logger.w(TAG, "设置写入失败", it) }
        }
    }

    /** 开关类设置的 Toast：文案行内标签 + 开/关状态。 */
    private fun toastPref(labelRes: Int, enabled: Boolean) {
        val template = if (enabled) R.string.toast_pref_on else R.string.toast_pref_off
        toast.show(appContext.getString(template, appContext.getString(labelRes)))
    }

    /** 循环类设置的 Toast：文案行内标签 + 新档位名。 */
    private fun toastCycled(label: String, valueRes: Int) {
        toast.show(appContext.getString(R.string.toast_cycled, label, appContext.getString(valueRes)))
    }

    /** 循环类设置的 Toast 重载：新档位已是字符串时使用。 */
    private fun toastCycled(label: String, value: String) {
        toast.show(appContext.getString(R.string.toast_cycled, label, value))
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val MIN_SUBTITLE_SIZE = 1
        const val MAX_SUBTITLE_SIZE = 4

        /** 退出登录日志标签。 */
        const val TAG = "SettingsVM"

        /** 片头时长可选档位（秒）。 */
        val SKIP_INTRO_STEPS = listOf(30, 60, 90, 120)
    }
}
