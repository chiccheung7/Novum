package com.novum.tv.data.settings

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
 * @param subtitleOffsetLevel 字幕高度档位：0 居中，1/2 上移，3/4 下移。
 * @param audioLanguage 音轨语言偏好（空串表示自动选最高规格）。
 * @param failedVideoCodecs 播放失败回写的视频编码列表（逗号分隔，协商时排除）。
 * @param skipIntro 是否自动跳过片头。
 * @param skipIntroSeconds 片头长度（秒），范围 15–600。
 * @param pinnedLineUrl 手动锁定的线路地址；空串表示未锁定，由测速选择线路。
 *   切线只影响媒体流域名，API 鉴权域保持不变。
 * @param pinnedLineName 锁定线路的显示名（`data[].name`），与 [pinnedLineUrl]
 *   成对持久化（接口可能不返回 name，回退域名短名展示）。
 * @param playbackSpeed 记忆的倍速档；1f 为正常速，取值限于 PLAYBACK_SPEEDS。
 * @param autoPlayNext 自动连播：片尾显示
 *   「下一集 10 秒后播放」倒计时，任意键取消，归零换集；季末天然不连播
 *   （nextEpisodeId 按同季分集推导，季末为 null 时播放结束回详情页）。
 */
data class AppSettings(
    val preferDirect: Boolean = true,
    val audioPassthrough: Boolean = true,
    val subtitleEnabledByDefault: Boolean = true,
    /** 首选字幕语言：默认简体中文；空串跟随服务器默认轨，zhdual 按轨道标题匹配。 */
    val subtitleLanguage: String = "chi",
    val subtitleSize: Int = 2,
    /** 字幕高度档位：0 居中，1/2 上移，3/4 下移。 */
    val subtitleOffsetLevel: Int = 0,
    val audioLanguage: String = "",
    /** 优先视频质量：空值使用服务端默认版本；dolby/hdr/sdr 匹配动态范围，bitrate 优先高码率。 */
    val versionStrategy: String = "",
    val skipIntro: Boolean = false,
    val skipIntroSeconds: Int = 90,
    /** ASS/SSA 字幕烧录保留样式；开启时协商声明 Encode 并使用转码。 */
    val assBurnIn: Boolean = false,
    /** 播放失败回写的视频编码列表（逗号分隔），DeviceProfile 协商时排除。 */
    val failedVideoCodecs: String = "",
    val pinnedLineUrl: String = "",
    val pinnedLineName: String = "",
    val playbackSpeed: Float = 1f,
    val autoPlayNext: Boolean = true,
)
