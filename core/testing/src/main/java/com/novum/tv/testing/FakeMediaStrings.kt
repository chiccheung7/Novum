package com.novum.tv.testing

import com.novum.tv.data.text.MediaStrings

/**
 * [MediaStrings] 的测试替身：返回固定文案，
 * 仓储测试断言（如「导演」职位兜底）不需要 Robolectric 就能跑。
 */
object FakeMediaStrings : MediaStrings {
    override fun typeMovie(): String = "电影"
    override fun typeSeries(): String = "剧集"
    override fun typeEpisode(): String = "分集"
    override fun typeBoxSet(): String = "合集"
    override fun versionLabel(number: Int): String = "版本 $number"
    override fun audioTrackLabel(number: Int): String = "音轨 $number"
    override fun subtitleTrackLabel(number: Int): String = "字幕 $number"
    override fun chapterLabel(number: Int): String = "章节 $number"
    override fun roleActor(): String = "演员"
    override fun roleDirector(): String = "导演"
    override fun roleWriter(): String = "编剧"
    override fun roleProducer(): String = "制片"
    override fun roleComposer(): String = "作曲"
    override fun roleGuestStar(): String = "客串"
    override fun seasonLabel(number: Int): String = "第 $number 季"
    override fun episodeLabel(number: Int): String = "第 $number 集"
    override fun runtimeLabel(minutes: Int): String = when {
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0 -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    }

    override fun danmuRateLimited(): String = "弹幕请求较多，请稍后重试"
}
