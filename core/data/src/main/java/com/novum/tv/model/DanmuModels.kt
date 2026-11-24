package com.novum.tv.model

/**
 * 弹幕显示区域。
 *
 * @param fraction 画面高度占比（弹幕只落在该区域内）。
 */
enum class DanmuArea(val fraction: Float) {
    /** 上半屏（默认，不挡字幕）。 */
    Half(0.5f),

    /** 全屏。 */
    Full(1f),
}

/** 弹幕字号档位。 */
enum class DanmuSize(val scale: Float) {
    Small(0.85f),
    Medium(1f),
    Large(1.25f),
}

/** 弹幕滚动速度档位（滚动弹幕横穿画面的时长倍率，越小越快）。 */
enum class DanmuSpeed(val durationScale: Float) {
    Slow(1.3f),
    Normal(1f),
    Fast(0.75f),
}

/**
 * 弹幕渲染配置（设置页全局默认 + 播放抽屉即时调节，改动即持久化）。
 *
 * 默认值对齐 UHDNOW 官方网页播放器：默认关、透明度 0.7、上半屏、密度 0.7。
 *
 * @param enabled 弹幕开关。
 * @param area 显示区域。
 * @param opacity 整体不透明度（0.1–1）。
 * @param size 字号档位。
 * @param density 显示密度（0–1，按比例采样丢弃超出部分）。
 * @param speed 滚动速度档位。
 */
data class DanmuConfig(
    val enabled: Boolean = false,
    val area: DanmuArea = DanmuArea.Half,
    val opacity: Float = 0.7f,
    val size: DanmuSize = DanmuSize.Medium,
    val density: Float = 0.7f,
    val speed: DanmuSpeed = DanmuSpeed.Normal,
) {
    init {
        require(opacity in 0.1f..1f) { "opacity out of range: $opacity" }
        require(density in 0f..1f) { "density out of range: $density" }
    }
}

/**
 * 单条弹幕（dandanplay v2 comment 格式解析后的领域对象）。
 *
 * @param text 弹幕文本（已清洗控制字符、截断到 160 字）。
 * @param timeMs 出现时间（毫秒，相对正片起点；已应用偏移校正前的原始值）。
 * @param mode 弹幕类型：1=滚动 4=底部 5=顶部（服务端只下发这三种）。
 * @param color 文本颜色（ARGB）。
 */
data class DanmuComment(
    val text: String,
    val timeMs: Long,
    val mode: Int,
    val color: Long = 0xFFFFFFFFL,
)

/** 弹幕自动匹配结果（按资源文件名匹配）。 */
data class DanmuMatch(
    val episodeId: String,
    val title: String?,
    val animeTitle: String?,
)

/** 手动搜索的动画条目。 */
data class DanmuAnime(
    val bangumiId: String,
    val title: String?,
)

/** 动画的分集条目（手动搜索选定动画后列出）。 */
data class DanmuEpisode(
    val episodeId: String,
    val title: String?,
)
