package com.novum.tv.ui.screen.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.state.UiState
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumNoScrollDeadZone
import com.novum.tv.ui.component.NovumContentScaffold
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.LinePickerViewModel
import com.novum.tv.ui.component.LocalNovumToast
import com.novum.tv.ui.component.NovumLinePickerDialog
import com.novum.tv.ui.component.NovumOptionListDialog
import com.novum.tv.ui.component.NovumOptionRow
import com.novum.tv.ui.component.NovumStepperRow
import com.novum.tv.ui.component.NovumSwitchRow
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置分区。 */
enum class SettingsSection(val labelRes: Int) {
    Account(R.string.settings_group_account),
    Appearance(R.string.settings_group_appearance),
    Playback(R.string.settings_group_playback),
    Subtitle(R.string.settings_group_subtitle),
    Data(R.string.settings_group_data),
}

/**
 * 设置页：左侧分区目录，右侧展示所选分区的选项。
 *
 * 左栏确定键只切换分区，焦点保持在左栏；右方向键进入右栏。
 * 右栏 Back 先回左栏，左栏 Back 返回上一页。
 *
 * @param onBack 返回上一页。
 * @param onLoggedOut 退出登录后的导航回调。
 * @param modifier 外部修饰符。
 * @param viewModel 设置页 ViewModel。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state: UiState<AppSettings> by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionLabel by viewModel.sessionLabel.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()

    // 播放线路选择弹窗。
    val linePickerViewModel: LinePickerViewModel = hiltViewModel()
    var linePickerOpen by rememberSaveable { mutableStateOf(false) }
    val pickerState by linePickerViewModel.pickerState.collectAsStateWithLifecycle()
    val pinnedLine by linePickerViewModel.pinned.collectAsStateWithLifecycle()

    var selected by rememberSaveable { mutableStateOf(SettingsSection.Account.name) }
    // 升级后 savedState 可能残留已删除/重命名的分区名，不能直接 valueOf 崩页面
    val selectedSection = runCatching { SettingsSection.valueOf(selected) }
        .getOrDefault(SettingsSection.Account)
    val leftSelectedRowFocus = remember { FocusRequester() }

    // 初始抓焦：左目录选中行——顶栏切页进入时焦点落内容区，不滞留顶栏
    NovumInitialFocus(leftSelectedRowFocus)

    // Back 分层：右栏先回左栏，左栏才退出设置页。
    // 不再依赖右栏 Column 的 onFocusChanged（那会让整栏重组闪烁）——
    // 用 moveFocus(Left) 探测：焦点在右栏时该方向有可聚焦目标，左栏时没有
    val focusManager = LocalFocusManager.current
    BackHandler {
        // moveFocus 返回是否成功移动焦点，用于判断是否仍在右栏。
        val movedToLeft = focusManager.moveFocus(FocusDirection.Left)
        if (!movedToLeft) onBack()
    }

    // 本页 Back 返回上一页。
    NovumContentScaffold(modifier = modifier) {
        if (linePickerOpen) {
            NovumLinePickerDialog(
                lines = pickerState.lines,
                pinnedUrl = pinnedLine.url.takeIf { it.isNotBlank() },
                onSelectLine = { option ->
                    linePickerViewModel.select(option)
                    linePickerOpen = false
                },
                loading = pickerState.lines == null && !pickerState.failed,
                failed = pickerState.failed,
                onDismiss = { linePickerOpen = false },
            )
        }
        when (val current = state) {
            UiState.Loading -> {
                NovumStateView(state = UiState.Loading, onRetry = {})
            }

            is UiState.Empty -> {
                NovumStateView(state = current, onRetry = {})
            }

            is UiState.Error -> {
                NovumStateView(state = current, onRetry = viewModel::reload)
            }

            is UiState.Success -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    // 左目录关闭滚动死区，保留将首尾项完整滚入视口所需的小修正。
                    NovumNoScrollDeadZone {
                    Column(
                        modifier = Modifier
                            .width(200.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SettingsSection.entries.forEach { section ->
                            SettingsMenuRow(
                                label = stringResource(section.labelRes),
                                selected = section == selectedSection,
                                focusRequester = leftSelectedRowFocus.takeIf { section == selectedSection },
                                // 切换分区后焦点保持在左栏；右方向键进入详情。
                                onClick = { selected = section.name },
                            )
                        }
                    }
                    }

                    // ---- 右详情 ----
                    // 不在右栏 Column 上观察焦点，避免整栏重组并重置 SettingRow 的焦点状态。
                    // Back 分层通过焦点树直接判断（见 BackHandler）。
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Text(
                            text = stringResource(selectedSection.labelRes),
                            style = NovumType.Title,
                            color = NovumPalette.Paper,
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                        SectionContent(
                            section = selectedSection,
                            settings = current.data,
                            viewModel = viewModel,
                            sessionLabel = sessionLabel,
                            loggingOut = loggingOut,
                            onLoggedOut = onLoggedOut,
                            pinnedLineLabel = pinnedLine.displayLabel,
                            onOpenLinePicker = {
                                linePickerViewModel.refresh()
                                linePickerOpen = true
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 左目录行复用 [NovumOptionRow] 的焦点与选中样式。 */
@Composable
private fun SettingsMenuRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NovumOptionRow(
        text = label,
        selected = selected,
        onClick = onClick,
        focusRequester = focusRequester,
        modifier = modifier,
        // 设置页文字恒 100% 不透明（用户要求）
        alwaysOpaque = true,
    )
}

/** 偏好浮层种类（字幕/音轨共用浮层结构；画质独立选项）。 */
private enum class LangPrefKind { Subtitle, Audio, Quality }

/** 偏好选项：资源标签与持久化值。 */
private data class PrefOption(val value: String, val labelRes: Int)

/**
 * 首选字幕语言选项，值遵循 Emby Language。
 * 默认使用服务端默认轨道；zhdual 没有 ISO 码，播放侧按轨道标题匹配。
 */
private val SUBTITLE_LANG_OPTIONS = listOf(
    PrefOption("", R.string.settings_option_default),
    PrefOption("chi", R.string.settings_option_zh_cn),
    PrefOption("zht", R.string.settings_option_zh_tw),
    PrefOption("zhdual", R.string.settings_option_zh_dual),
    PrefOption("eng", R.string.settings_option_english),
    PrefOption("jpn", R.string.settings_option_japanese),
    PrefOption("kor", R.string.settings_option_korean),
)

/** 首选音频语言选项（无双语项——音频不存在「中英双语」轨型）。 */
private val AUDIO_LANG_OPTIONS = listOf(
    PrefOption("", R.string.settings_option_default),
    PrefOption("chi", R.string.settings_option_zh_cn),
    PrefOption("zht", R.string.settings_option_zh_tw),
    PrefOption("eng", R.string.settings_option_english),
    PrefOption("jpn", R.string.settings_option_japanese),
    PrefOption("kor", R.string.settings_option_korean),
)

/**
 * 优先视频质量选项，值写入 AppSettings.versionStrategy。
 * 默认取服务端首选版本；杜比视界/HDR/SDR 优先匹配对应片源；码率优先取高码率版本。
 * 每次询问会在播放前打开版本层，长按播放按钮也可手动选择。
 */
private val QUALITY_OPTIONS = listOf(
    PrefOption("", R.string.settings_option_default),
    PrefOption("dolby", R.string.settings_option_quality_dolby),
    PrefOption("hdr", R.string.settings_option_quality_hdr),
    PrefOption("sdr", R.string.settings_option_quality_sdr),
    PrefOption("bitrate", R.string.settings_option_quality_bitrate),
    PrefOption("ask", R.string.settings_option_quality_ask),
)

/** 字幕语言偏好展示名：命中选项用其标签，未设置显示「默认」。 */
@Composable
private fun langLabel(language: String): String =
    SUBTITLE_LANG_OPTIONS.firstOrNull { it.value == language }?.let { stringResource(it.labelRes) }
        ?: stringResource(R.string.settings_lang_default)

/** 画质策略展示名：命中选项用其标签，未设置显示「默认」。 */
@Composable
private fun strategyLabel(strategy: String): String =
    QUALITY_OPTIONS.firstOrNull { it.value == strategy }?.let { stringResource(it.labelRes) }
        ?: stringResource(R.string.settings_lang_default)

/** 通用选项偏好浮层（NovumOptionRow 统一语义；初始焦点落当前生效项）。 */
@Composable
private fun OptionPrefDialog(
    title: String,
    options: List<PrefOption>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 复用通用单选浮层：标题/行距/选中标记/初始焦点只有一处实现，
    // 各页浮层不会再各自长成不同样子
    val labels = options.map { stringResource(it.labelRes) }
    NovumOptionListDialog(
        title = title,
        options = labels,
        selectedIndex = options.indexOfFirst { it.value == current }.takeIf { it >= 0 },
        onSelect = { index -> onSelect(options[index].value) },
        onDismiss = onDismiss,
    )
}

/**
 * 右栏分区内容：按分区归位既有设置项。
 *
 * 用可滚动列而非 LazyColumn：项数很少且需要「整页焦点顺序稳定」，
 * LazyColumn 的回收会让焦点在滚动时丢失。
 */
@Composable
private fun SectionContent(
    section: SettingsSection,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    sessionLabel: String,
    loggingOut: Boolean,
    onLoggedOut: () -> Unit,
    pinnedLineLabel: String,
    onOpenLinePicker: () -> Unit,
) {
    val scrollState = rememberScrollState()

        // 语言与画质偏好浮层。
        var langPrefDialog by remember { mutableStateOf<LangPrefKind?>(null) }
        langPrefDialog?.let { kind ->
            val prefTitle: String = when (kind) {
                LangPrefKind.Subtitle -> stringResource(R.string.settings_pref_subtitle)
                LangPrefKind.Audio -> stringResource(R.string.settings_pref_audio)
                LangPrefKind.Quality -> stringResource(R.string.settings_pref_quality)
            }
            val prefOptions: List<PrefOption> = when (kind) {
                LangPrefKind.Subtitle -> SUBTITLE_LANG_OPTIONS
                LangPrefKind.Audio -> AUDIO_LANG_OPTIONS
                LangPrefKind.Quality -> QUALITY_OPTIONS
            }
            val prefCurrent: String = when (kind) {
                LangPrefKind.Subtitle -> settings.subtitleLanguage
                LangPrefKind.Audio -> settings.audioLanguage
                LangPrefKind.Quality -> settings.versionStrategy
            }
            OptionPrefDialog(
                title = prefTitle,
                options = prefOptions,
                current = prefCurrent,
                onSelect = { value ->
                    when (kind) {
                        LangPrefKind.Subtitle -> viewModel.setSubtitleLanguage(value)
                        LangPrefKind.Audio -> viewModel.setAudioLanguage(value)
                        LangPrefKind.Quality -> viewModel.setVersionStrategy(value)
                    }
                    langPrefDialog = null
                },
                onDismiss = { langPrefDialog = null },
            )
        }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // CompositionLocal 只能在组合期读取，协程里用这个局部引用发消息
    val toastSink = LocalNovumToast.current
    var cacheLabel by remember { mutableStateOf("…") }

    /** 重新读取图片缓存占用（磁盘缓存为主，内存缓存随进程释放）。 */
    fun refreshCacheLabel() {
        scope.launch {
            cacheLabel = withContext(Dispatchers.IO) {
                val loader = SingletonImageLoader.get(context)
                formatBytes(loader.diskCache?.size ?: 0L)
            }
        }
    }

    LaunchedEffect(section) {
        if (section == SettingsSection.Data) refreshCacheLabel()
    }

    // 设置页没有缩放卡片，死区只会吞掉"把最后一行滚完整"的小修正：
    // 作用域内关闭死区，顶/底行不再停在半截
    NovumNoScrollDeadZone {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (section) {
                SettingsSection.Account -> {
                    InfoRow(
                        label = stringResource(R.string.settings_connection),
                        value = sessionLabel.ifBlank { stringResource(R.string.settings_connection_unknown) },
                    )
                    SettingRow(
                        label = stringResource(R.string.settings_logout),
                        value = if (loggingOut) {
                            stringResource(R.string.settings_logging_out)
                        } else {
                            stringResource(R.string.settings_logout_action)
                        },
                        onAction = { viewModel.logout(onLoggedOut) },
                    )
                }

                SettingsSection.Appearance -> {
                    // 整体缩放置换（视口层订阅同一设置流，改完整屏立即重算）：
                    // 不同电视上报的画布差异极大，归一化只保证"不比设计画布更局促"，
                    // 最终疏密仍由观看距离决定，所以留一档给用户。
                    NovumStepperRow(
                        label = stringResource(R.string.settings_ui_scale),
                        value = "${(settings.uiScale * 100).roundToInt()}%",
                        level = UI_SCALE_STEPS.indexOfFirst { it >= settings.uiScale - 0.001f }
                            .coerceAtLeast(0),
                        levelCount = UI_SCALE_STEPS.size,
                        onLevelChange = viewModel::setUiScaleLevel,
                    )
                    HintText(stringResource(R.string.settings_ui_scale_hint))
                }

                SettingsSection.Playback -> {
                    // 播放线路置顶；锁定时展示线路名，未锁定时自动选取延迟最低的线路。
                    SettingRow(
                        label = stringResource(R.string.settings_playback_line),
                        value = pinnedLineLabel.ifBlank {
                            stringResource(R.string.settings_playback_line_auto)
                        },
                        onAction = onOpenLinePicker,
                    )
                    SettingRow(
                        label = stringResource(R.string.settings_pref_quality),
                        value = strategyLabel(settings.versionStrategy),
                        onAction = { langPrefDialog = LangPrefKind.Quality },
                    )
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_prefer_direct),
                        checked = settings.preferDirect,
                        onCheckedChange = viewModel::setPreferDirect,
                    )
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_audio_passthrough),
                        checked = settings.audioPassthrough,
                        onCheckedChange = viewModel::setAudioPassthrough,
                    )
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_skip_intro),
                        checked = settings.skipIntro,
                        onCheckedChange = viewModel::setSkipIntro,
                    )
                    if (settings.skipIntro) {
                        NovumStepperRow(
                            label = stringResource(R.string.settings_skip_intro_seconds),
                            value = stringResource(R.string.settings_seconds, settings.skipIntroSeconds),
                            level = SKIP_INTRO_STEPS.indexOf(settings.skipIntroSeconds).coerceAtLeast(0),
                            levelCount = SKIP_INTRO_STEPS.size,
                            onLevelChange = viewModel::setSkipIntroSecondsLevel,
                        )
                    }
                    // 自动连播默认开启，片尾倒计时 10 秒，任意键取消。
                    // 季末 nextEpisodeId 为 null，播放结束返回详情页。
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_autoplay_next),
                        checked = settings.autoPlayNext,
                        onCheckedChange = viewModel::setAutoPlayNext,
                    )
                    // 弹幕（UHDNOW 原生能力）：设置页只给全局默认值，
                    // 播放抽屉可对当前会话即时微调（写回同一存储，下次播放沿用）。
                    NovumSwitchRow(
                        text = stringResource(R.string.danmu_enabled),
                        checked = settings.danmuEnabled,
                        onCheckedChange = viewModel::setDanmuEnabled,
                    )
                    if (settings.danmuEnabled) {
                        NovumStepperRow(
                            label = stringResource(R.string.danmu_area),
                            value = stringResource(
                                if (settings.danmuAreaFull) R.string.danmu_area_full else R.string.danmu_area_half,
                            ),
                            level = if (settings.danmuAreaFull) 1 else 0,
                            levelCount = 2,
                            onLevelChange = viewModel::setDanmuAreaLevel,
                        )
                        NovumStepperRow(
                            label = stringResource(R.string.danmu_size),
                            value = stringResource(
                                when (settings.danmuSize) {
                                    1 -> R.string.subtitle_size_small
                                    3 -> R.string.subtitle_size_large
                                    else -> R.string.subtitle_size_medium
                                },
                            ),
                            level = settings.danmuSize - 1,
                            levelCount = 3,
                            onLevelChange = viewModel::setDanmuSizeLevel,
                        )
                        NovumStepperRow(
                            label = stringResource(R.string.danmu_speed),
                            value = stringResource(
                                when (settings.danmuSpeed) {
                                    1 -> R.string.danmu_speed_slow
                                    3 -> R.string.danmu_speed_fast
                                    else -> R.string.danmu_speed_normal
                                },
                            ),
                            level = settings.danmuSpeed - 1,
                            levelCount = 3,
                            onLevelChange = viewModel::setDanmuSpeedLevel,
                        )
                        NovumStepperRow(
                            label = stringResource(R.string.danmu_opacity),
                            value = "${(settings.danmuOpacity * 100).roundToInt()}%",
                            level = (settings.danmuOpacity * 10).roundToInt() - 1,
                            levelCount = 10,
                            onLevelChange = viewModel::setDanmuOpacityLevel,
                        )
                        NovumStepperRow(
                            label = stringResource(R.string.danmu_density),
                            value = "${(settings.danmuDensity * 100).roundToInt()}%",
                            level = (settings.danmuDensity * 10).roundToInt() - 1,
                            levelCount = 10,
                            onLevelChange = viewModel::setDanmuDensityLevel,
                        )
                    }
                }

                SettingsSection.Subtitle -> {
                    // 字幕和音轨的语言、字号及烧录选项集中在此分区。
                    SettingRow(
                        label = stringResource(R.string.settings_pref_subtitle),
                        value = langLabel(settings.subtitleLanguage),
                        onAction = { langPrefDialog = LangPrefKind.Subtitle },
                    )
                    SettingRow(
                        label = stringResource(R.string.settings_pref_audio),
                        value = langLabel(settings.audioLanguage),
                        onAction = { langPrefDialog = LangPrefKind.Audio },
                    )
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_subtitle_default),
                        checked = settings.subtitleEnabledByDefault,
                        onCheckedChange = viewModel::setSubtitleEnabled,
                    )
                    NovumStepperRow(
                        label = stringResource(R.string.settings_subtitle_size),
                        value = stringResource(settings.subtitleSize.labelRes()),
                        level = settings.subtitleSize - 1,
                        levelCount = 4,
                        onLevelChange = viewModel::setSubtitleSizeLevel,
                    )
                    NovumSwitchRow(
                        text = stringResource(R.string.settings_subtitle_ass_burn_in),
                        checked = settings.assBurnIn,
                        onCheckedChange = viewModel::setAssBurnIn,
                    )
                }

                SettingsSection.Data -> {
                    // 文案在组合期解析，配置变化会自动重读；回调里只做展示
                    val clearCacheToast = stringResource(R.string.toast_cache_cleared)
                    SettingRow(
                        label = stringResource(R.string.settings_clear_cache),
                        value = cacheLabel,
                        onAction = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    val loader = SingletonImageLoader.get(context)
                                    loader.memoryCache?.clear()
                                    loader.diskCache?.clear()
                                }
                                refreshCacheLabel()
                                toastSink.show(clearCacheToast)
                            }
                        },
                    )
                }

            }
        }
    }
}

/**
 * 单行设置（动作/枚举类）：左侧文案，右侧显示当前值，点击进入下一态或打开选择层。
 *
 * 布尔项一律用 [NovumSwitchRow]（形状可扫视），这里只承载"点一下换一档/开一层"的行。
 */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 右栏整行可聚焦，焦点态白底黑字，与左栏使用一致的交互语义。
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            // onFocusChanged 必须位于 clickable 外侧，才能接收从焦点目标向外冒泡的事件。
            .onFocusChanged { focused = it.isFocused }
            .clip(NovumShapes.Card)
            // 动作行不是"选项"：焦点只描白边，不整行反白
            .border(
                width = 2.dp,
                color = if (focused) NovumPalette.Paper else Color.Transparent,
                shape = NovumShapes.Card,
            )
            .clickable(onClick = onAction)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        // 设置页文字恒定 100% 不透明，焦点靠描边表达
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = NovumType.Body,
            color = NovumPalette.Paper,
        )
        Text(
            text = value,
            // 行尾值比标签小一档：设置页此前和标签同字号同色，读起来像两段并列正文
            style = NovumType.Caption,
            color = NovumPalette.Paper,
        )
    }
}

/** 只读信息行：左侧文案，右侧值不可聚焦。水平 16dp 与 SettingRow 标签左对齐。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 只读行同样恒 100% 不透明（与可聚焦行一致）
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = NovumType.Body,
            color = NovumPalette.Paper,
        )
        Text(
            text = value,
            style = NovumType.Body,
            color = NovumPalette.Paper,
        )
    }
}

/** 只读说明文字（不可聚焦，比可操作行低一档、不参与焦点导航）。 */
@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = NovumType.Caption,
        color = NovumPalette.PaperMuted,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 字节数转可读文案（GB / MB / KB）。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes / 1024} KB"
    else -> "0 KB"
}
