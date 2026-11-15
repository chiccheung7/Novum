package com.novum.tv.data.text

/**
 * 数据层展示文案提供者。
 *
 * 为什么是接口而不是直接引 designsystem 资源：core:data 若依赖 core:designsystem
 * 会把整套 Compose 工具链拖进数据层（层级方向允许但代价失当）；而接口定义在
 * core:data、实现（资源解析）在 app 层经 Hilt 注入，模块依赖零新增，仓储也保持
 * 可测（测试注入 Fake 返回固定文案）。
 *
 * 收纳范围 = 数据层产出的**用户可见**文案（类型名「Movie→电影」、人物职位、
 * 「版本 N / 音轨 N / 章节 N / 第 N 季 / 第 N 集」兜底名）。两类刻意不进接口：
 * 日志诊断文案（不面向用户）与服务端数据判别词表（逻辑而非文案）。
 */
interface MediaStrings {

    /** 条目类型展示名：副标题「年份 · 类型」用（[com.novum.tv.data.repository]）。 */
    fun typeMovie(): String

    /** 见 [typeMovie]。 */
    fun typeSeries(): String

    /** 分集副标题兜底（服务端未回剧集名时）。 */
    fun typeEpisode(): String

    /** 见 [typeMovie]。 */
    fun typeBoxSet(): String

    /** 版本名兜底：「版本 N」（服务端版本名为空时）。 */
    fun versionLabel(number: Int): String

    /** 音轨名兜底：「音轨 N」。 */
    fun audioTrackLabel(number: Int): String

    /** 字幕轨名兜底：「字幕 N」。 */
    fun subtitleTrackLabel(number: Int): String

    /** 章节名兜底：「章节 N」。 */
    fun chapterLabel(number: Int): String

    /** 人物职位（服务端 Role 为空时的兜底展示）。 */
    fun roleActor(): String

    /** 见 [roleActor]。 */
    fun roleDirector(): String

    /** 见 [roleActor]。 */
    fun roleWriter(): String

    /** 见 [roleActor]。 */
    fun roleProducer(): String

    /** 见 [roleActor]。 */
    fun roleComposer(): String

    /** 见 [roleActor]。 */
    fun roleGuestStar(): String

    /** 季名兜底：「第 N 季」（服务端季名为空时）。 */
    fun seasonLabel(number: Int): String

    /** 分集标题兜底：「第 N 集」（服务端集名为空时）。 */
    fun episodeLabel(number: Int): String

    /**
     * 片长展示：「N 分钟 / N 小时 / N 小时 M 分钟」，与 Hero 信息块使用相同文案。
     */
    fun runtimeLabel(minutes: Int): String
}
