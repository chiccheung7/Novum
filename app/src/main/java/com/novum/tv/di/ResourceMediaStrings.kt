package com.novum.tv.di

import android.content.Context
import com.novum.tv.core.designsystem.R
import com.novum.tv.data.text.MediaStrings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MediaStrings] 的生产实现：从 designsystem strings.xml 解析展示文案。
 * app 同时依赖 core:data 的接口与 core:designsystem 的资源，负责二者之间的桥接。
 */
@Singleton
class ResourceMediaStrings @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaStrings {

    override fun recentBroadcastsTitle(): String = context.getString(R.string.home_row_recent_broadcasts)

    override fun typeMovie(): String = context.getString(R.string.media_type_movie)

    override fun typeSeries(): String = context.getString(R.string.media_type_series)

    override fun typeEpisode(): String = context.getString(R.string.media_type_episode)

    override fun typeBoxSet(): String = context.getString(R.string.media_type_boxset)

    override fun versionLabel(number: Int): String =
        context.getString(R.string.media_version_n, number)

    override fun audioTrackLabel(number: Int): String =
        context.getString(R.string.media_audio_track_n, number)

    override fun subtitleTrackLabel(number: Int): String =
        context.getString(R.string.media_subtitle_track_n, number)

    override fun chapterLabel(number: Int): String =
        context.getString(R.string.media_chapter_n, number)

    override fun roleActor(): String = context.getString(R.string.media_role_actor)

    override fun roleDirector(): String = context.getString(R.string.media_role_director)

    override fun roleWriter(): String = context.getString(R.string.media_role_writer)

    override fun roleProducer(): String = context.getString(R.string.media_role_producer)

    override fun roleComposer(): String = context.getString(R.string.media_role_composer)

    override fun roleGuestStar(): String = context.getString(R.string.media_role_guest_star)

    override fun seasonLabel(number: Int): String =
        context.getString(R.string.media_season_n, number)

    override fun episodeLabel(number: Int): String =
        context.getString(R.string.media_episode_n, number)

    override fun runtimeLabel(minutes: Int): String = when {
        minutes < 60 -> context.getString(R.string.media_runtime_minutes, minutes)
        minutes % 60 == 0 -> context.getString(R.string.media_runtime_hours, minutes / 60)
        else -> context.getString(
            R.string.media_runtime_hours_minutes,
            minutes / 60,
            minutes % 60,
        )
    }

    override fun danmuRateLimited(): String = context.getString(R.string.danmu_rate_limited)
}
