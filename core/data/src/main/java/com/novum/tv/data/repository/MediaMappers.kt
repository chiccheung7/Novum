package com.novum.tv.data.repository

import com.novum.tv.data.remote.emby.EmbyImages
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.MediaSourceDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.model.CastMember
import com.novum.tv.model.Chapter
import com.novum.tv.model.Episode
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.MediaTrackOption
import com.novum.tv.model.MediaVersionOption
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.Season
import java.util.Locale

/**
 * DTO → 领域模型映射层。
 *
 * 只做「Emby DTO → 领域模型」的纯转换：Emby View/条目/季/集/人物/版本 →
 * 领域库/条目/季/集/演职员/版本，以及首页聚合条目（toHomeEntry）。
 */

/** Emby View → 领域媒体库。 */
internal fun ItemDto.toLibrary(server: String): MediaLibrary? {
    val id = Id ?: return null
    return MediaLibrary(
        id = id,
        name = Name.orEmpty(),
        kind = CollectionType,
        itemCount = ChildCount,
        imageUrl = ImageTags["Primary"]?.let { tag ->
            EmbyImages.primary(server = server, itemId = id, tag = tag)
        },
    )
}

/**
 * Emby 条目 → 领域条目。
 *
 * @param server 服务器地址，用于拼接海报。
 */
internal fun ItemDto.toMediaItem(server: String, strings: MediaStrings): MediaItem? {
    val id = Id ?: return null
    val primaryTag = ImageTags["Primary"]
    // 网格卡使用 Primary 竖版海报。
    return MediaItem(
        id = id,
        title = Name.orEmpty(),
        type = Type,
        year = ProductionYear,
        rating = CommunityRating,
        imageUrl = primaryTag?.let {
            EmbyImages.primary(server = server, itemId = id, tag = it)
        },
        subtitle = buildSubtitle(strings),
    )
}

/** 拼副标题：年份与类型标签；类型文案由 [MediaStrings] 提供。 */
private fun ItemDto.buildSubtitle(strings: MediaStrings): String? {
    val typeLabel = when (Type) {
        "Movie" -> strings.typeMovie()
        "Series" -> strings.typeSeries()
        "Episode" -> SeriesName ?: strings.typeEpisode()
        "BoxSet" -> strings.typeBoxSet()
        else -> null
    }
    val parts = listOfNotNull(ProductionYear?.toString(), typeLabel)
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/**
 * Hero/详情信息块共用格式标志：分辨率档 +
 * 视频动态范围 + 视频编码 + 音频 Profile + 音频编码。
 *
 * VideoRangeType 精细枚举（Emby 10.9+）：DOVI*（杜比视界）/ HDR10+ / HDR10 / HLG；
 * 老服务器回退 VideoRange（HDR / DOVI / HLG）；SDR 产标签，HLG 用自己的名字
 * （实底胶囊同排）。
 * **无视频流（剧集条目，流挂在分集上）不产任何标签**——else 判 SDR 只对
 * 「有视频流但普通画质」成立，避免为没有视频流的剧集条目生成 SDR 徽章。
 */
private class FormatBadges(
    val videoFormats: List<String>,
    val videoCodec: String?,
    val resolution: String?,
    val audioProfile: String?,
    val audioFormat: String?,
)

/** 从 MediaStreams 解析 [FormatBadges]；[toHomeEntry] 与 [toDetail] 共用。 */
private fun ItemDto.formatBadges(): FormatBadges {
    val videoStream = MediaStreams.firstOrNull { it.Type == "Video" }
    val videoFormatLabel = videoStream?.videoRangeLabel()
    val audioStream = MediaStreams.firstOrNull { it.Type == "Audio" }
    return FormatBadges(
        videoFormats = listOfNotNull(videoFormatLabel),
        videoCodec = videoStream?.Codec?.uppercase()?.takeIf { it.isNotBlank() },
        resolution = videoStream?.let {
            when {
                (it.Height ?: 0) >= 2160 -> "4K"
                (it.Height ?: 0) >= 720 -> "HD"
                else -> null
            }
        },
        audioProfile = audioStream
            ?.Profile?.takeIf { it.contains("Atmos", ignoreCase = true) }?.let { "Dolby Atmos" },
        audioFormat = audioStream
            ?.Codec?.uppercase()?.takeIf { it.isNotBlank() },
    )
}

/**
 * 视频流 → 动态范围展示名（[FormatBadges] 与版本选项两处共用；SDR/HLG 也产标签）。
 *
 * VideoRangeType 精细枚举（Emby 10.9+）：DOVI*（杜比视界）/ HDR10+ / HDR10 / HLG；
 * 老服务器回退 VideoRange（HDR / DOVI / HLG）。**else 判 SDR 只对「有流但普通
 * 画质」成立**——调用方必须先确认有视频流再调用（无流返回 null）。
 */
private fun MediaStreamDto.videoRangeLabel(): String? = when {
    VideoRangeType?.startsWith("DOVI") == true ||
        VideoRange?.contains("Dolby", ignoreCase = true) == true ||
        VideoRange == "DOVI" -> "Dolby Vision"
    VideoRangeType == "HDR10+" -> "HDR10+"
    // HLG 是 HDR 的一种（BBC/NHK 广播口径）；老服务器只在 VideoRange 里给 "HLG"
    VideoRangeType == "HLG" || VideoRange == "HLG" -> "HLG"
    VideoRangeType == "HDR10" ||
        VideoRange?.contains("HDR10", ignoreCase = true) == true ||
        VideoRange == "HDR" -> "HDR10"
    else -> "SDR"
}

/**
 * 单个 MediaSource → 版本选项（详情条目与分集两处共用）。
 *
 * 版本选项展示清晰度、动态范围和码率；名称及 SxxExx 前缀由界面拼接。
 * 服务端未提供视频流规格时回退到 [MediaVersionOption.label]。
 *
 * @param index 列表下标，版本名缺失时的兜底序号。
 */
private fun MediaSourceDto.toVersionOption(index: Int, strings: MediaStrings): MediaVersionOption? {
    val sourceId = Id ?: return null
    val video = MediaStreams.firstOrNull { it.Type == "Video" }
    val height = video?.Height ?: 0
    val resolution = when {
        height >= 2160 -> "4K"
        height >= 1300 -> "1440p"
        height >= 1000 -> "1080p"
        height >= 700 -> "720p"
        else -> null
    }
    return MediaVersionOption(
        sourceId = sourceId,
        label = Name?.takeIf { it.isNotBlank() } ?: strings.versionLabel(index + 1),
        resolution = resolution,
        dynamicRange = video?.videoRangeLabel(),
        bitrate = video?.BitRate?.takeIf { it > 0 }?.let(::formatBitrate),
    )
}

/** 视频码率 → 展示串（1 Mbps 以上给一位小数，否则 kbps）。 */
private fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> "${bps / 1_000} kbps"
    else -> "$bps bps"
}

    /** People[] → 演职员模型。 */
    internal fun ItemDto.toCastMembers(server: String, strings: MediaStrings): List<CastMember> =
        People.toCastMembers(server, strings)

    /** People[] → 演职员模型（详情/季级聚合共用）。 */
    internal fun List<PersonDto>.toCastMembers(server: String, strings: MediaStrings): List<CastMember> {
        // 同一个人可能以多个职位出现（导演 + 旁白 + 演员），Emby 的 People 会下发多条
        // **同 id** 记录；直接 map 会让列表里出现重复 key，LazyRow 抛
        // "Key ... was already used" 直接闪退。按人物 id（缺 id 时按姓名）去重，保留首次出现。
        val seen = LinkedHashSet<String>()
        return mapNotNull { person ->
            val key = person.Id?.takeIf { it.isNotBlank() }
                ?: person.Name?.trim()?.takeIf { it.isNotBlank() }?.lowercase()
                ?: return@mapNotNull null
            if (!seen.add(key)) return@mapNotNull null
            person.toCastMember(server, strings)
        }
    }

    /**
     * 多集 `People[]` 合并为季级演职员。
     *
     * 同一演员通常出现在整季各集，客串只出现在个别集：按**出现集数降序**排序，
     * 同频次保持首次出现顺序（主角在前）。按人物 id 去重，缺 id 时按姓名。
     */
    internal fun List<ItemDto>.toSeasonCast(server: String, strings: MediaStrings): List<CastMember> {
        val seen = LinkedHashMap<String, Pair<PersonDto, Int>>()
        forEach { dto ->
            for (person in dto.People) {
                val name = person.Name?.takeIf { it.isNotBlank() } ?: continue
                val key = person.Id?.takeIf { it.isNotBlank() } ?: name.lowercase()
                val existing = seen[key]
                seen[key] = if (existing == null) {
                    person to 1
                } else {
                    existing.first to (existing.second + 1)
                }
            }
        }
        return seen.values
            .sortedByDescending { (_, count) -> count }
            .mapNotNull { (person, _) -> person.toCastMember(server, strings) }
            .take(MAX_CAST)
    }

    /** 单个人物 → 演职员模型；姓名缺失（无法上屏）返回 null。 */
    private fun PersonDto.toCastMember(server: String, strings: MediaStrings): CastMember? {
        val name = Name?.takeIf { it.isNotBlank() } ?: return null
        return CastMember(
            name = name,
            role = Role?.takeIf { it.isNotBlank() } ?: Type.personTypeLabel(strings),
            imageUrl = Id?.let { personId ->
                EmbyImages.primary(
                    server = server,
                    itemId = personId,
                    tag = imageTag(),
                    maxWidth = 240,
                )
            },
            id = Id,
        )
    }

    /**
     * 头像版本号：`/Items` 的 `People[]` 用平铺的 `PrimaryImageTag`，
     * `/Persons` 端点则放在 `ImageTags.Primary`。两个口径都要认。
     */
    internal fun PersonDto.imageTag(): String? =
        PrimaryImageTag?.takeIf { it.isNotBlank() }
            ?: ImageTags[IMAGE_TAG_PRIMARY]?.takeIf { it.isNotBlank() }

    /**
     * 服务器人物结果 → UI 使用的人物引用。
     *
     * 缺 id（无法导航到人物页）或缺姓名（无法展示/匹配）时返回 null。
     *
     * @param server 服务器地址，用于拼接头像。
     */
    internal fun PersonDto.toPersonRef(server: String): PersonRef? {
        val personId = Id?.takeIf { it.isNotBlank() } ?: return null
        val name = Name?.takeIf { it.isNotBlank() } ?: return null
        return PersonRef(
            name = name,
            personId = personId,
            imageUrl = EmbyImages.primary(
                server = server,
                itemId = personId,
                tag = imageTag(),
                maxWidth = 240,
            ),
        )
    }

    /**
     * Emby 条目 → 详情模型。
     *
     * @param server 服务器地址，用于拼接图片。
     * @param fallbackId 服务端未返回 id 时的兜底值（即请求的 itemId）。
     */
    internal fun ItemDto.toDetail(server: String, fallbackId: String, strings: MediaStrings): MediaDetail {
    val itemId = Id ?: fallbackId
    val userData = UserData
    // 已看条目的续播位置归零，避免继续观看状态残留。
    val played = userData?.Played ?: false
    // 格式标志与首页 Hero 共用同一映射。
    val detailBadges = formatBadges()
    return MediaDetail(
        id = itemId,
        title = Name.orEmpty(),
        type = Type,
        year = ProductionYear,
        rating = CommunityRating,
        officialRating = OfficialRating,
        runtimeMinutes = RunTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() },
        overview = Overview?.takeIf { it.isNotBlank() },
        genres = Genres,
        posterUrl = ImageTags["Primary"]?.let {
            EmbyImages.primary(server = server, itemId = itemId, tag = it)
        },
        backdropUrl = BackdropImageTags.firstOrNull()?.let {
            EmbyImages.backdrop(server = server, itemId = itemId, tag = it)
        },
        logoUrl = ImageTags["Logo"]?.let { tag ->
            EmbyImages.logo(server = server, itemId = itemId, tag = tag)
        },
        videoFormats = detailBadges.videoFormats,
        videoCodec = detailBadges.videoCodec,
        resolution = detailBadges.resolution,
        audioProfile = detailBadges.audioProfile,
        audioFormat = detailBadges.audioFormat,
        isFavorite = userData?.IsFavorite ?: false,
        played = played,
        resumePositionMs = if (played) {
            0L
        } else {
            userData?.PlaybackPositionTicks?.let { it / TICKS_PER_MS } ?: 0L
        },
        childCount = ChildCount ?: RecursiveItemCount,
        unplayedCount = userData?.UnplayedItemCount,
        premiereDate = PremiereDate?.take(10),
        status = Status,
        cast = toCastMembers(server, strings).take(MAX_CAST),
        // 多个 MediaSource 提供版本选项，单版本不显示选择浮层。
        versions = MediaSources.mapIndexedNotNull { index, sourceDto ->
            sourceDto.toVersionOption(index, strings)
        }.distinctBy { it.sourceId },
        studios = Studios.mapNotNull { it.Name },
        // 展示所有字幕和音轨，优先使用 DisplayTitle；默认轨标记供偏好界面高亮。
        audioTracks = MediaStreams
            .filter { it.Type == "Audio" }
            .mapIndexed { index, stream ->
                MediaTrackOption(
                    label = stream.DisplayTitle?.takeIf { it.isNotBlank() }
                        ?: stream.Title?.takeIf { it.isNotBlank() }
                        ?: stream.Language?.takeIf { it.isNotBlank() }
                        ?: strings.audioTrackLabel(index + 1),
                    language = stream.Language?.takeIf { it.isNotBlank() },
                    default = stream.IsDefault,
                )
            }
            .take(16),
        subtitleTracks = MediaStreams
            .filter { it.Type == "Subtitle" }
            .mapIndexed { index, stream ->
                MediaTrackOption(
                    label = stream.DisplayTitle?.takeIf { it.isNotBlank() }
                        ?: stream.Title?.takeIf { it.isNotBlank() }
                        ?: stream.Language?.takeIf { it.isNotBlank() }
                        ?: strings.subtitleTrackLabel(index + 1),
                    language = stream.Language?.takeIf { it.isNotBlank() },
                    default = stream.IsDefault,
                )
            }
            .take(16),
        chapters = Chapters.mapIndexedNotNull { index, chapter ->
            val start = chapter.StartPositionTicks ?: return@mapIndexedNotNull null
            Chapter(
                name = chapter.Name?.takeIf { it.isNotBlank() } ?: strings.chapterLabel(index + 1),
                startMs = start / TICKS_PER_MS,
            )
        }.filter { it.startMs >= 0 },
        // 分集定位三件套：播放器的上一集/下一集按它们找同季邻居
        seriesId = SeriesId,
        seasonIndex = ParentIndexNumber,
        index = IndexNumber,
    )
}

/** Emby 人物类型的中文标签（经 [MediaStrings]）；未知类型返回 null，界面上就不显示职位。 */
private fun String?.personTypeLabel(strings: MediaStrings): String? = when (this) {
    "Actor" -> strings.roleActor()
    "Director" -> strings.roleDirector()
    "Writer" -> strings.roleWriter()
    "Producer" -> strings.roleProducer()
    "Composer" -> strings.roleComposer()
    "GuestStar" -> strings.roleGuestStar()
    else -> null
}

/** Emby 季 → 领域季。 */
internal fun ItemDto.toSeason(index: Int, strings: MediaStrings): Season? {
    val id = Id ?: return null
    val number = IndexNumber ?: (index + 1)
    return Season(
        id = id,
        name = Name?.takeIf { it.isNotBlank() } ?: strings.seasonLabel(number),
        index = number,
        episodeCount = ChildCount ?: RecursiveItemCount,
        // 未看集数来自季的 UserData（服务端支持才有）；季下拉默认季与元信息用它
        unplayedCount = UserData?.UnplayedItemCount,
    )
}

/**
 * Emby 分集 → 领域分集。
 *
 * @param server 服务器地址。
 * @param index 列表下标，服务端未给集号时作为兜底。
 */
internal fun ItemDto.toEpisode(server: String, index: Int, strings: MediaStrings): Episode? {
    val id = Id ?: return null
    val userData = UserData
    // 优先使用所属剧集的横版主视觉，依次回退到背景图、海报；缺失时使用分集图。
    val seriesWideUrl = SeriesId?.let { sid ->
        SeriesThumbImageTag?.let { tag ->
            EmbyImages.thumb(server = server, itemId = sid, tag = tag)
        // backdrop 已是非空 URL（服务端没有 Thumbs 时由它兜底），
        // 后面再挂 elvis 是死分支，Kotlin 也会提示「总是返回左操作数」
        } ?: EmbyImages.backdrop(server = server, itemId = sid, tag = null)
    }
    return Episode(
        id = id,
        title = Name?.takeIf { it.isNotBlank() } ?: strings.episodeLabel(index + 1),
        seasonIndex = ParentIndexNumber ?: 1,
        index = IndexNumber ?: (index + 1),
        played = userData?.Played ?: false,
        progressMs = userData?.PlaybackPositionTicks?.let { it / TICKS_PER_MS } ?: 0L,
        runtimeMinutes = RunTimeTicks?.let { (it / TICKS_PER_MINUTE).toInt() },
        imageUrl = ImageTags["Primary"]?.let {
            EmbyImages.primary(server = server, itemId = id, tag = it)
        },
        seriesWideUrl = seriesWideUrl,
        overview = Overview?.takeIf { it.isNotBlank() },
        // 集音轨格式：音频编码与 Profile 从首个片源的 MediaStreams 取。
        // 分集没有顶层 MediaStreams（EPISODE_FIELDS 只请求 MediaSources），故走片源口径。
        audioFormat = MediaSources.firstOrNull()?.MediaStreams
            ?.firstOrNull { it.Type == "Audio" }?.Codec
            ?.uppercase()?.takeIf { it.isNotBlank() },
        audioProfile = MediaSources.firstOrNull()?.MediaStreams
            ?.firstOrNull { it.Type == "Audio" }?.Profile
            ?.takeIf { it.contains("Atmos", ignoreCase = true) }?.let { "Dolby Atmos" },
        premiereDate = PremiereDate?.take(10)?.takeIf { it.isNotBlank() },
        // 单集多版本由详情页播放按钮的长按操作选择。
        versions = MediaSources.mapIndexedNotNull { index, sourceDto ->
            sourceDto.toVersionOption(index, strings)
        }.distinctBy { it.sourceId },
        seasonId = SeasonId,
    )
}

/** 1 tick = 100ns，即 10,000 ticks = 1ms。 */
private const val TICKS_PER_MS = 10_000L

/** 1 分钟 = 600,000,000 ticks。 */
private const val TICKS_PER_MINUTE = 600_000_000L

/** 详情页最多展示的演职员数量，避免超长列表拖慢首帧。 */
private const val MAX_CAST = 20

/**
 * Emby 条目 → 首页聚合条目。
 *
 * @param server 服务器地址，用于拼接图片。
 */
internal fun ItemDto.toHomeEntry(server: String, strings: MediaStrings): HomeEntry? {
    val id = Id ?: return null
    val userData = UserData
    val runtimeTicks = RunTimeTicks
    val positionTicks = userData?.PlaybackPositionTicks
    val progress = if (runtimeTicks != null && runtimeTicks > 0L && positionTicks != null && positionTicks > 0L) {
        (positionTicks.toFloat() / runtimeTicks).coerceIn(0f, 1f)
    } else {
        null
    }
    // 剩余分钟（继续观看卡文案）：有进度且时长已知才算
    val remainingMinutes = if (progress != null && runtimeTicks != null && positionTicks != null) {
        ((runtimeTicks - positionTicks).coerceAtLeast(0L) / TICKS_PER_MINUTE).toInt().takeIf { it > 0 }
    } else {
        null
    }
    // Hero 信息块格式标志与详情页共用 formatBadges()。
    val badges = formatBadges()
    val primaryTag = ImageTags["Primary"]
    // 16:9 卡片行优先使用横版缩略图，避免竖版海报裁切主体。
    val ownThumbTag = ImageTags["Thumb"]
    // 2:3 海报行（分库行/搜索/收藏）用 Primary 竖版海报
    val posterUrl = primaryTag?.let { tag ->
        EmbyImages.primary(server = server, itemId = id, tag = tag)
    }
    // 跨模块的属性无法智能转换，先落到局部变量
    val parentThumbId = ParentThumbItemId
    val parentThumbTag = ParentThumbImageTag
    // 图片回退顺序：Thumb → Backdrop → Primary → 父级图。
    val backdropTag = BackdropImageTags.firstOrNull()
    // 分集往往没有自己的海报，回落到所属剧集的横版缩略图
    val imageUrl = when {
        ownThumbTag != null -> EmbyImages.thumb(server = server, itemId = id, tag = ownThumbTag)
        backdropTag != null -> EmbyImages.backdrop(server = server, itemId = id, tag = backdropTag)
        primaryTag != null -> posterUrl
        parentThumbId != null && parentThumbTag != null -> EmbyImages.primary(
            server = server,
            itemId = parentThumbId,
            tag = parentThumbTag,
        )

        else -> null
    }
    // 继续观看行优先使用所属剧集的横版主视觉，缺失时回落到分集图片解析结果。
    val seriesWideUrl = SeriesId?.let { sid ->
        SeriesThumbImageTag?.let { tag ->
            EmbyImages.thumb(server = server, itemId = sid, tag = tag)
        // backdrop 已是非空 URL（服务端没有 Thumbs 时由它兜底），
        // 后面再挂 elvis 是死分支，Kotlin 也会提示「总是返回左操作数」
        } ?: EmbyImages.backdrop(server = server, itemId = sid, tag = null)
    }
    val isEpisode = Type == "Episode"
    return HomeEntry(
        id = id,
        title = when (Type) {
            "Episode" -> SeriesName?.takeIf { it.isNotBlank() } ?: Name.orEmpty()
            else -> Name.orEmpty()
        },
        subtitle = when (Type) {
            // 继续观看行第二行显示 `SxxExx · 分集标题`；标题仅为集号时不重复显示。
            "Episode" -> buildString {
                val epLabel = if (ParentIndexNumber != null && IndexNumber != null) {
                    "S%02dE%02d".format(ParentIndexNumber, IndexNumber)
                } else {
                    ""
                }
                if (epLabel.isNotEmpty()) append(epLabel)
                val rawName = Name?.takeIf { it.isNotBlank() }
                // 标题与序号重复（如 Name = "第3集" / "S01E03"）时不重复显示
                val isDuplicate = rawName != null && (
                    rawName.replace(" ", "").equals(epLabel, ignoreCase = true) ||
                        rawName.matches(Regex("^[Ss][0-9]+[Ee][0-9]+$")) ||
                        (IndexNumber != null && rawName.matches(Regex("^第[ ]*${IndexNumber}[ ]*集$")))
                    )
                if (rawName != null && !isDuplicate) {
                    if (epLabel.isNotEmpty()) append(" · ")
                    append(rawName)
                }
            }.takeIf { it.isNotBlank() } ?: buildSubtitle(strings)

            // 电影第二行显示发行年份和片长；片长缺失时回退到类型标签。
            "Movie" -> buildList {
                ProductionYear?.let { add(it.toString()) }
                RunTimeTicks?.takeIf { it > 0 }?.let {
                    add(strings.runtimeLabel((it / TICKS_PER_MINUTE).toInt()))
                }
            }.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: buildSubtitle(strings)

            else -> buildSubtitle(strings)
        },
        imageUrl = if (isEpisode) seriesWideUrl ?: imageUrl else imageUrl,
        posterUrl = if (isEpisode) seriesWideUrl ?: posterUrl else posterUrl,
        backdropUrl = BackdropImageTags.firstOrNull()?.let {
            EmbyImages.backdrop(server = server, itemId = id, tag = it)
        },
        overview = Overview?.takeIf { it.isNotBlank() },
        rating = CommunityRating,
        year = ProductionYear,
        type = Type,
        seriesId = SeriesId,
        progress = progress,
        remainingMinutes = remainingMinutes,
        isFavorite = userData?.IsFavorite ?: false,
        isPlayed = userData?.Played ?: false,
        officialRating = OfficialRating,
        runtimeMinutes = RunTimeTicks?.takeIf { it > 0 }?.let { (it / TICKS_PER_MINUTE).toInt() },
        genres = Genres.take(3),
        videoFormats = badges.videoFormats,
        videoCodec = badges.videoCodec,
        resolution = badges.resolution,
        audioProfile = badges.audioProfile,
        audioFormat = badges.audioFormat,
        // ClearLogo 是「有则更好」的资源：没有就返回 null，由界面回落到文本标题。
        // 分集条目通常没有 Logo 图，回退到所属剧集的 Logo。
        logoUrl = ImageTags["Logo"]?.let { tag ->
            EmbyImages.logo(server = server, itemId = id, tag = tag)
        } ?: SeriesId?.takeIf { Type == "Episode" }?.let { seriesId ->
            EmbyImages.logo(server = server, itemId = seriesId, tag = null)
        },
    )
}

/** `ImageTags` 里的头像键名。 */
private const val IMAGE_TAG_PRIMARY = "Primary"
