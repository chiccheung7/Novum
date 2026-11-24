package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.line.LineProbe
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.ui.component.LineOption
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 线路选择 ViewModel。
 *
 * 打开弹窗时拉取 /System/Ext/ServerDomains 并测速。选择线路后持久化 pinnedLineUrl/Name，
 * 失效 LineProbe 缓存，下一次起播使用该媒体流域名；API 鉴权域不变，无需重新登录。
 */
@HiltViewModel
class LinePickerViewModel @Inject constructor(
    private val lineProbe: LineProbe,
    private val settings: SettingsRepository,
) : ViewModel() {

    /** 弹窗打开即拉列表；null = 未打开。 */
    private val _lines = MutableStateFlow<List<LineOption>?>(null)

    /** 拉取失败（官方口径：404/超时是常态，静默沿用当前地址）。 */
    private val _failed = MutableStateFlow(false)

    /** 界面状态：列表（null=加载中）+ 失败标记。 */
    val pickerState: StateFlow<LinePickerState> =
        combine(_lines, _failed) { lines, failed ->
            LinePickerState(lines = lines, failed = failed)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LinePickerState())

    /** 当前锁定的线路（url/name 成对；url 空 = 自动测速模式）。 */
    val pinned: StateFlow<PinnedLine> = settings.settings
        .map { PinnedLine(url = it.pinnedLineUrl, name = it.pinnedLineName) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PinnedLine())

    /** 在途测速任务：快速反复开关弹窗时只保留最后一次结果。 */
    private var refreshJob: Job? = null

    /** 打开弹窗时拉取线路列表（带测速；重复打开重新拉，量小不缓存 UI 态）。 */
    fun refresh() {
        refreshJob?.cancel()
        _failed.value = false
        _lines.value = null
        refreshJob = viewModelScope.launch {
            // 只吞真正的探测失败（404/超时是常态）；取消必须原样抛出，否则被取消的
            // 旧任务会继续往下走，把 _failed 置 true 盖掉新一次刷新正在加载的状态
            val probed = try {
                lineProbe.lines()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (probed == null || probed.isEmpty()) {
                // 单线路（接口未部署/退化）也视为失败态：列表里只有当前服务器，
                // 没有「选择」可言，不如明确告知沿用当前地址
                _failed.value = true
            } else {
                _lines.value = probed
                    // 按主机名和非默认端口归一化去重，忽略默认端口与协议头差异。
                    // 同组优先保留服务端命名的线路，再取延迟较低者。
                    .groupBy { lineKey(it.baseUrl) }
                    .map { (_, group) ->
                        group.minWithOrNull(
                            compareBy({ it.name.isNullOrBlank() }, { it.latencyMs }),
                        ) ?: group.first()
                    }
                    .sortedBy { it.latencyMs }
                    .map { line ->
                        LineOption(
                            baseUrl = line.baseUrl,
                            displayName = line.displayName,
                            shortName = line.shortName,
                            latencyMs = line.latencyMs,
                        )
                    }
            }
        }
    }

    /**
     * 选中线路：null = 切回自动测速（清空锁定）。
     *
     * 写设置 + 失效 LineProbe 缓存——下一次起播立即按新策略出流。
     */
    fun select(option: LineOption?) {
        viewModelScope.launch {
            settings.update {
                it.copy(
                    pinnedLineUrl = option?.baseUrl.orEmpty(),
                    pinnedLineName = option?.displayName.orEmpty(),
                )
            }
            lineProbe.invalidate()
        }
    }
}

/** 线路弹窗状态：列表为 null 表示加载中；failed=true 显示失败提示。 */
data class LinePickerState(
    val lines: List<LineOption>? = null,
    val failed: Boolean = false,
) {
    /** 弹窗是否处于可交互的列表态。 */
    val showList: Boolean get() = lines != null && !failed
}

/** 当前锁定线路（持久化快照的 UI 投影）。 */
data class PinnedLine(
    val url: String = "",
    val name: String = "",
) {
    /** 是否处于手动锁线模式。 */
    val isPinned: Boolean get() = url.isNotBlank()

    /** 设置行/菜单行的展示值：锁线显示线路名，否则「自动」。 */
    val displayLabel: String get() = name.takeIf { it.isNotBlank() } ?: url.takeIf { it.isNotBlank() } ?: ""
}

/**
 * 线路去重键：去协议头/尾斜杠 + 剥默认端口（:443/:80）+ 小写——
 * 「中国大陆优化」与 smart.uhdnow.com（:443）这类同址别名归一成同键。
 */
private fun lineKey(url: String): String {
    val host = url.substringAfter("://").trimEnd('/').lowercase()
    return when {
        host.endsWith(":443") -> host.removeSuffix(":443")
        host.endsWith(":80") -> host.removeSuffix(":80")
        else -> host
    }
}
