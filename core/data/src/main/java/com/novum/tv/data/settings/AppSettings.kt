package com.novum.tv.data.settings

import com.novum.tv.model.DanmuArea
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuSize
import com.novum.tv.model.DanmuSpeed

/**
 * 应用设置快照（不可变）。
 *
 * 默认值决定首次安装后的表现。
 *
 * @param preferDirect 优先直连播放：为 true 时不请求服务端转码。
 * @param audioPassthrough 音频直通：交给电视/功放解码杜比与 DTS。
 * @param subtitleEnabledByDefault 播放时自动开启字幕。
 * @param subtitleLanguage 字幕语言（BCP-47，空串表示跟随媒体默认轨）。
 * @param subtitleSize 字幕字号档位，1=小 2=中 3=大 4=超大。
 * @param subtitleWeight 字幕粗细，0=细 1=标准 2=粗。
 * @param subtitleOffsetLevel 字幕高度档位：0 居中，1/2 上移，3/4 下移。
 * @param audioLanguage 音轨语言偏好（空串表示自动选最高规格）。
 * @param failedVideoCodecs 播放失败回写的视频编码列表（逗号分隔，协商时排除）。
 * @param skipIntro 是否自动跳过片头。
 * @param skipIntroSeconds 片头长度（秒），设置页档位 30 / 60 / 90 / 120。
 * @param pinnedLineUrl 手动锁定的线路地址；空串表示未锁定，由测速选择线路。
 *   切线只影响媒体流域名，API 鉴权域保持不变。
 * @param pinnedLineName 锁定线路的显示名（`data[].name`），与 [pinnedLineUrl]
 *   成对持久化（接口可能不返回 name，回退域名短名展示）。
 * @param pinnedLineAccountKey 锁线所属服务器/用户的稳定摘要；旧版无归属锁线须重新选择。
 * @param pinnedLineCleartextConfirmed 当前账号已明确确认该固定线路可使用明文 HTTP。
 * @param playbackSpeed 记忆的倍速档；1f 为正常速，取值限于 PLAYBACK_SPEEDS。
 * @param autoPlayNext 自动连播：片尾显示
 *   「下一集 10 秒后播放」倒计时，任意键取消，归零换集；季末天然不连播
 *   （nextEpisodeId 按同季分集推导，季末为 null 时播放结束回详情页）。
 * @param danmuEnabled 弹幕开关（UHDNOW 原生能力）：全局默认，播放抽屉即时调节写回。
 * @param danmuOpacity 弹幕不透明度（0.1–1）。
 * @param danmuDensity 弹幕密度（0.1–1，超出部分按比例丢弃）。
 * @param danmuArea 弹幕显示区域：顶部四分之一屏、上半屏或全屏。
 * @param danmuSize 弹幕字号档位：1=小 2=中 3=大。
 * @param danmuSpeed 弹幕滚动速度档位：1=慢 2=标准 3=快。
 * @param uiScale 界面大小档位（1 = 按 1280×720dp 设计画布原样）；>1 元素更大。
 */
data class AppSettings(
    val preferDirect: Boolean = true,
    val audioPassthrough: Boolean = true,
    val subtitleEnabledByDefault: Boolean = true,
    /** 首选字幕语言：默认简体中文；空串跟随服务器默认轨，zhdual 按轨道标题匹配。 */
    val subtitleLanguage: String = "chi",
    val subtitleSize: Int = 2,
    val subtitleWeight: Int = 1,
    /** 字幕高度档位：0 居中，1/2 上移，3/4 下移。 */
    val subtitleOffsetLevel: Int = 0,
    val audioLanguage: String = "",
    /** 优先视频质量：空值使用服务端默认版本；dolby/hdr/sdr 匹配动态范围，bitrate 优先高码率。 */
    val versionStrategy: String = "",
    val skipIntro: Boolean = false,
    val skipIntroSeconds: Int = 90,
    /** ASS/SSA 字幕烧录保留样式；开启时协商声明 Encode 并使用转码。 */
    val assBurnIn: Boolean = true,
    /** 播放失败回写的视频编码列表（逗号分隔），DeviceProfile 协商时排除。 */
    val failedVideoCodecs: String = "",
    val pinnedLineUrl: String = "",
    val pinnedLineName: String = "",
    val pinnedLineAccountKey: String = "",
    val pinnedLineCleartextConfirmed: Boolean = false,
    val playbackSpeed: Float = 1f,
    val autoPlayNext: Boolean = true,
    /** 弹幕开关：默认关（对齐官方网页播放器）；抽屉开/关即时写回。 */
    val danmuEnabled: Boolean = false,
    /** 弹幕不透明度（0.1–1），默认 0.7。 */
    val danmuOpacity: Float = 0.7f,
    /** 弹幕密度（0.1–1），默认 0.7。 */
    val danmuDensity: Float = 0.7f,
    /** 弹幕显示区域，默认上半屏。 */
    val danmuArea: DanmuArea = DanmuArea.Half,
    /**
     * 弹幕字号档位：1=小 2=中 3=大。
     *
     * 档位倍率已在 [com.novum.tv.model.DanmuSize] 里整体上移一档——默认的「中」
     * 就是过去的最大字号（25dp），实机 3 米距离下才够看。
     */
    val danmuSize: Int = 2,
    /** 弹幕滚动速度档位：1=慢 2=标准 3=快。 */
    val danmuSpeed: Int = 2,
    /**
     * 界面大小：整体缩放置换到 Viewport（1 = 标准，0.85 = 更紧凑，1.3 = 更大）。
     *
     * 电视上报的画布差异极大，归一化只能保证「不比设计画布更局促」；
     * 同一套比例在不同尺寸/观看距离的电视上仍需微调，这一档由用户决定。
     */
    val uiScale: Float = 1f,
)

/** 锁线只属于原服务器/用户；无归属的旧设置不能自动绑定到下次登录的账号。 */
fun AppSettings.pinnedLineUrlFor(accountKey: String?): String? = pinnedLineUrl.takeIf {
    it.isNotBlank() && !accountKey.isNullOrBlank() && pinnedLineAccountKey == accountKey
}

/** HTTP 固定线路只在其账号归属匹配且有显式确认位时可用于流量授权。 */
fun AppSettings.pinnedCleartextLineFor(accountKey: String?): String? =
    pinnedLineUrlFor(accountKey)?.takeIf { pinnedLineCleartextConfirmed && it.startsWith("http://") }

/** 把设置快照里的弹幕原始字段组装成渲染配置（档位 → 领域枚举）。 */
fun AppSettings.danmuConfig(): DanmuConfig = DanmuConfig(
    enabled = danmuEnabled,
    area = danmuArea,
    opacity = danmuOpacity,
    size = when (danmuSize) {
        1 -> DanmuSize.Small
        3 -> DanmuSize.Large
        else -> DanmuSize.Medium
    },
    density = danmuDensity,
    speed = when (danmuSpeed) {
        1 -> DanmuSpeed.Slow
        3 -> DanmuSpeed.Fast
        else -> DanmuSpeed.Normal
    },
)
