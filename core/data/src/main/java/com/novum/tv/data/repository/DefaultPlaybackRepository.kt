package com.novum.tv.data.repository

import com.novum.tv.core.capability.CapabilityProvider
import com.novum.tv.core.capability.DeviceCapabilities
import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.network.toNovumError
import com.novum.tv.core.network.ServerAddress
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.failureOf
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.core.result.successOf
import androidx.media3.common.MimeTypes
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.DeviceProfileDto
import com.novum.tv.data.remote.emby.MediaSourceDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.PlaybackInfoRequestDto
import com.novum.tv.data.remote.emby.PlaybackProgressDto
import com.novum.tv.data.line.LineProbe
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.remote.emby.SessionSnapshot
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.data.settings.pinnedLineUrlFor
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.model.SubtitleSource
import com.novum.tv.model.TrackOption
import com.novum.tv.model.VideoSpec
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 播放仓储默认实现。
 *
 * 地址选择策略（顺序即优先级）：
 * 1. `SupportsDirectPlay` 且服务端给了 `DirectStreamUrl` → 直连，画质与性能最优；
 * 2. `DirectStreamUrl` 存在（转封装）→ 仍优于转码；
 * 3. `TranscodingUrl` 存在 → 服务端转码；
 * 4. 以上皆无 → 回落到 Emby 标准静态流 `/Videos/{id}/stream?Static=true`。
 *
 * 之所以必须用服务端下发的地址：托管商可能改写路径（UHDNOW 给的是 `/play/video/...`），
 * 硬编码标准路径会 404。
 *
 * @param clients Emby API 提供者。
 * @param sessionHolder 当前会话，提供服务器地址、用户 id 与 Token。
 * @param settings 设置仓储：`preferDirect` 决定是否优先直连、避免服务端转码。
 * @param capability 设备能力：决定声明给服务端的音视频编码。
 * @param dispatchers 调度器。
 * @param logger 日志门面。
 */
class DefaultPlaybackRepository @Inject constructor(
    private val clients: EmbyApiProvider,
    private val sessionHolder: SessionHolder,
    private val settings: SettingsRepository,
    private val capability: CapabilityProvider,
    private val dispatchers: DispatcherProvider,
    private val logger: NovumLogger,
    private val lineProbe: LineProbe,
    private val strings: MediaStrings,
) : PlaybackRepository {

    override suspend fun plan(
        itemId: String,
        startPositionMs: Long,
        forceTranscode: Boolean,
        excludeLine: String?,
        mediaSourceId: String?,
        subtitleStreamIndex: Int?,
    ): NovumResult<PlaybackPlan> {
        val owner = sessionHolder.capture()
        return withContext(dispatchers.io) {
            val server = owner.server
            if (!isCurrent(owner)) {
                return@withContext failureOf(NovumError.Unauthorized())
            }
            val explicitSubtitle = subtitleStreamIndex != null
            if (explicitSubtitle && (subtitleStreamIndex < -1 || mediaSourceId.isNullOrBlank())) {
                return@withContext failureOf(NovumError.Server(400))
            }
            val forceSubtitleBurnIn = subtitleStreamIndex != null && subtitleStreamIndex >= 0
            val requireTranscode = forceTranscode || forceSubtitleBurnIn
            // A seek recovery needs a new stream at the target, not the original unseekable static URL.
            val requiresPositionedTranscode = forceTranscode && startPositionMs > 0L
            val snapshot = runCatchingSuspend { settings.settings.first() }.getOrNull()
            if (!isCurrent(owner)) return@withContext failureOf(NovumError.Unauthorized())
            val preferDirect = snapshot?.preferDirect ?: true
            // 媒体流域名选择（优先级）：手动锁定线路 > 测速最快线路 > 当前服务器；
            // API 调用仍用原服务器地址（鉴权域一致）——官方文档明确客户端内切线
            // 无需重登（docs.uhdnow.com/partner/emby-ext-domains）
            val bestLine = runCatchingSuspend {
                lineProbe.lines(
                    excludeBaseUrl = excludeLine,
                    pinnedLine = snapshot?.pinnedLineUrlFor(owner.playbackPrefsNamespace()),
                ).firstOrNull()
            }.getOrNull()
            if (!isCurrent(owner)) return@withContext failureOf(NovumError.Unauthorized())
            val mediaServer = bestLine?.baseUrl ?: server
            val lineLabel = bestLine?.let { "${it.displayName} · ${it.latencyMs}ms" }
            val audioPassthrough = snapshot?.audioPassthrough ?: true
            try {
                val info = requestPlan(
                    owner = owner,
                    itemId = itemId,
                    audioPassthrough = audioPassthrough,
                    forceTranscode = requireTranscode,
                    assBurnIn = snapshot?.assBurnIn ?: false,
                    mediaSourceId = mediaSourceId,
                    subtitleStreamIndex = subtitleStreamIndex,
                    startPositionMs = if (explicitSubtitle || requireTranscode) startPositionMs.coerceAtLeast(0L) else null,
                    forceSubtitleBurnIn = forceSubtitleBurnIn,
                    failedVideoCodecs = snapshot?.failedVideoCodecs
                        ?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet(),
                )
                if (!isCurrent(owner)) return@withContext failureOf(NovumError.Unauthorized())
                // 版本定位：显式 sourceId（详情页选择）> 画质策略（设置）> 服务端默认
                val requestedSource = mediaSourceId?.let { id ->
                    info.MediaSources.firstOrNull { it.Id == id }
                }
                val source = if (explicitSubtitle) requestedSource else
                    requestedSource ?: pickVersionByStrategy(info.MediaSources, snapshot?.versionStrategy)
                if (source == null) {
                    if (explicitSubtitle || requiresPositionedTranscode) {
                        return@withContext failureOf(NovumError.Server(502))
                    }
                    successOf(fallbackPlan(server, itemId, startPositionMs, owner.tokenForUrl(server)))
                } else {
                    if (forceSubtitleBurnIn && source.MediaStreams.none {
                            it.Type == "Subtitle" && it.Index == subtitleStreamIndex
                        }) {
                        return@withContext failureOf(NovumError.Server(502))
                    }
                    if (requiresPositionedTranscode && source.TranscodingUrl.toAbsoluteUrl(mediaServer) == null) {
                        return@withContext failureOf(NovumError.Server(502))
                    }
                    // 服务端按我们声明的能力判断，但它不知道「双层 DV 解不了」这类细节；
                    // 一旦片源超出本机能力，立刻换成「只声明转码」的请求，让服务端给转码流。
                    val unsupported = if (requireTranscode) null else source.unsupportedReasonForLog(capability.capabilities)
                    if (unsupported != null) {
                        logger.w(TAG, "片源超出设备能力（$unsupported），改请求服务端转码", null)
                        // 失败回写只针对「编码缺硬解」：Dolby Vision 是刻意转码保色彩，
                        // 它的 hevc 容器层编码并没有失败——若也写进 failedVideoCodecs，
                        // 之后所有 HEVC 片源都会被排除直连、一律转码
                        val videoCodec = source.unsupportedVideoCodec(capability.capabilities)
                        if (videoCodec != null) {
                            settings.update { s ->
                                val existing = s.failedVideoCodecs.split(",").filter { it.isNotBlank() }.toMutableSet()
                                existing += videoCodec
                                s.copy(failedVideoCodecs = existing.joinToString(","))
                            }
                        }
                        // 递归必须透传 excludeLine：换线重试进到这里时若丢掉参数，
                        // 会绕回刚失败的线路再失败一次
                        if (!isCurrent(owner)) return@withContext failureOf(NovumError.Unauthorized())
                        return@withContext plan(
                            itemId,
                            startPositionMs,
                            forceTranscode = true,
                            excludeLine = excludeLine,
                            mediaSourceId = mediaSourceId,
                            subtitleStreamIndex = subtitleStreamIndex,
                        )
                    }
                    val urls = if (explicitSubtitle) {
                        resolveSubtitleUrls(mediaServer, source, preferDirect, requireTranscode, subtitleStreamIndex)
                            ?: return@withContext failureOf(NovumError.Server(502))
                    } else {
                        resolveUrls(mediaServer, source, itemId, preferDirect, forceTranscode, owner.tokenForUrl(mediaServer))
                    }
                    fun burnedLabel(url: String?): String? = url?.let(::burnedInSubtitleIndex)?.let { index ->
                        source.MediaStreams.firstOrNull { it.Type == "Subtitle" && it.Index == index }
                            ?.displayLabel("Subtitle", index, strings)
                            ?: strings.subtitleTrackLabel(index + 1)
                    }
                    val burnedInLabel = burnedLabel(urls.primaryUrl)
                    successOf(
                        PlaybackPlan(
                            url = urls.primaryUrl,
                            method = urls.primaryMethod,
                            audioTracks = source.tracksOfType("Audio", strings),
                            subtitleTracks = source.tracksOfType("Subtitle", strings),
                            subtitles = if (burnedInLabel == null) source.externalSubtitles(server, itemId, owner.tokenForUrl(server)) else emptyList(),
                            durationMs = source.RunTimeTicks?.let { it / TICKS_PER_MS },
                            startPositionMs = startPositionMs,
                            lineLabel = lineLabel,
                            lineBaseUrl = mediaServer,
                            videoSpec = source.videoSpec(),
                            fallbackUrl = urls.fallbackUrl,
                            fallbackMethod = urls.fallbackMethod,
                            playSessionId = info.PlaySessionId,
                            fileName = source.danmuFileName(),
                            burnedInSubtitleLabel = burnedInLabel,
                            fallbackBurnedInSubtitleLabel = burnedLabel(urls.fallbackUrl),
                            mediaSourceId = source.Id,
                            burnedInSubtitleIndex = burnedInSubtitleIndex(urls.primaryUrl),
                            fallbackBurnedInSubtitleIndex = urls.fallbackUrl?.let(::burnedInSubtitleIndex),
                            serverSubtitleSelection = explicitSubtitle || burnedInLabel != null,
                            serverSubtitleStreamIndex = subtitleStreamIndex ?: burnedInSubtitleIndex(urls.primaryUrl),
                            streamStartPositionMs = transcodeStreamStartPositionMs(urls.primaryUrl, urls.primaryMethod),
                            fallbackStreamStartPositionMs = urls.fallbackUrl?.let {
                                transcodeStreamStartPositionMs(it, urls.fallbackMethod ?: PlayMethod.Transcode)
                            } ?: 0L,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (!isCurrent(owner)) return@withContext failureOf(NovumError.Unauthorized())
                // 播放信息接口失败不等于不能播：仍尝试静态直连兜底，
                // 但鉴权失败必须显式返回，避免用户对着黑屏猜问题。
                val error = throwable.toNovumError()
                if (error is NovumError.Unauthorized || explicitSubtitle || requiresPositionedTranscode) {
                    failureOf(error)
                } else {
                    logger.w(TAG, "PlaybackInfo 失败，回落到静态流", throwable)
                    successOf(fallbackPlan(server, itemId, startPositionMs, owner.tokenForUrl(server)))
                }
            }
        }
    }

    /**
     * 请求一次播放信息。
     *
     * @param forceTranscode 为 true 时不声明任何直连能力，服务端只能返回转码地址。
     */
    private suspend fun requestPlan(
        owner: SessionSnapshot,
        itemId: String,
        audioPassthrough: Boolean,
        forceTranscode: Boolean,
        assBurnIn: Boolean,
        failedVideoCodecs: Set<String> = emptySet(),
        mediaSourceId: String? = null,
        subtitleStreamIndex: Int? = null,
        startPositionMs: Long? = null,
        forceSubtitleBurnIn: Boolean = false,
    ) = clients.api(owner.server, owner).playbackInfo(
        itemId = itemId,
        userId = owner.userId,
        // 能力来自实际探测：只声明这台机器真的能解的编码，
        // 直通开关再收敛一层（关掉时不给杜比 / DTS，服务端转 AAC）
        body = PlaybackInfoRequestDto(
            DeviceProfile = DeviceProfileDto.forCapabilities(
                audioCodecs = capability.audioCodecDeclaration(audioPassthrough),
                videoCodecs = capability.videoCodecDeclaration(failedVideoCodecs),
                allowDirectPlay = !forceTranscode,
                assBurnIn = assBurnIn,
                forceSubtitleBurnIn = forceSubtitleBurnIn,
            ),
            MediaSourceId = mediaSourceId,
            SubtitleStreamIndex = subtitleStreamIndex,
            StartTimeTicks = startPositionMs?.coerceIn(0L, Long.MAX_VALUE / TICKS_PER_MS)?.times(TICKS_PER_MS),
            EnableDirectPlay = false.takeIf { forceTranscode },
            EnableDirectStream = false.takeIf { forceTranscode },
            EnableTranscoding = true.takeIf { forceTranscode || subtitleStreamIndex != null },
            AllowVideoStreamCopy = false.takeIf { forceSubtitleBurnIn },
            IsPlayback = true.takeIf { subtitleStreamIndex != null },
        ),
    )

    /**
     * 优先视频质量选版本（mediaSourceId 未指定时）：
     * - **默认（空）**= 服务端顺序首个（服务器片源默认版本）；
     * - **dolby / hdr / sdr** = 有对应动态范围的片源时优先选之（HDR 含
     *   HDR10/HDR10+/HLG），没有则回落服务端默认；
     * - **bitrate** = 优先视频流码率，缺失时按体积与时长估算平均码率；
     *   所有版本都无法取得码率时才按文件体积兜底；
     * - **ask（每次询问）** = 详情页在点击播放时已弹版本层并把选中版本经路由传入
     *   （mediaSourceId 命中上面第一分支）；走到这里说明入口无法询问（如首页
     *   Hero 播放 CTA），按服务端默认版本处理。
     */
    private fun pickVersionByStrategy(
        sources: List<MediaSourceDto>,
        strategy: String?,
    ): MediaSourceDto? {
        val fallback = sources.firstOrNull() ?: return null
        when (strategy?.lowercase()) {
            "dolby", "hdr", "sdr" -> {
                val matched = sources.firstOrNull { source ->
                    val video = source.MediaStreams.firstOrNull { it.Type == "Video" }
                    val vrt = video?.VideoRangeType.orEmpty()
                    val vr = video?.VideoRange.orEmpty()
                    when (strategy.lowercase()) {
                        "dolby" -> vrt.startsWith("DOVI", true) || vrt.contains("Dolby", true)
                        "hdr" -> vrt.contains("HDR", true) || vrt.contains("HLG", true) ||
                            vr.contains("HDR", true)
                        else -> vrt.contains("SDR", true) || vr.contains("SDR", true)
                    }
                }
                return matched ?: fallback
            }

            "bitrate" -> return sources
                .mapNotNull { source -> source.selectionBitrate()?.let { source to it } }
                .maxByOrNull { (_, bitrate) -> bitrate }?.first
                ?: sources.maxByOrNull { it.Size?.coerceAtLeast(0L) ?: 0L }
                ?: fallback

            else -> return fallback
        }
    }

    override suspend fun reportStarted(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String?,
    ) {
        report(sessionHolder.capture(), itemId, positionMs, method, playSessionId, ReportEvent.Started)
    }

    override suspend fun reportProgress(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String?,
    ) {
        report(sessionHolder.capture(), itemId, positionMs, method, playSessionId, ReportEvent.Progress)
    }

    override suspend fun reportStopped(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String?,
    ) {
        report(sessionHolder.capture(), itemId, positionMs, method, playSessionId, ReportEvent.Stopped)
    }

    override fun sessionReporter(): PlaybackReporter {
        val owner = sessionHolder.capture()
        return object : PlaybackReporter {
            override suspend fun reportStarted(itemId: String, positionMs: Long, method: PlayMethod, playSessionId: String?) {
                report(owner, itemId, positionMs, method, playSessionId, ReportEvent.Started)
            }

            override suspend fun reportProgress(itemId: String, positionMs: Long, method: PlayMethod, playSessionId: String?) {
                report(owner, itemId, positionMs, method, playSessionId, ReportEvent.Progress)
            }

            override suspend fun reportStopped(itemId: String, positionMs: Long, method: PlayMethod, playSessionId: String?) {
                report(owner, itemId, positionMs, method, playSessionId, ReportEvent.Stopped)
            }
        }
    }

    private enum class ReportEvent { Started, Progress, Stopped }

    private fun isCurrent(owner: SessionSnapshot): Boolean =
        sessionHolder.capture() === owner && owner.server.isNotBlank() &&
            owner.userId.isNotBlank() && owner.token.isNotBlank()

    /** 排队的收尾请求不能在换账号后借用新身份。失败只记日志，不打断播放。 */
    private suspend fun report(
        owner: SessionSnapshot,
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String?,
        event: ReportEvent,
    ) {
        withContext(dispatchers.io) {
            if (!isCurrent(owner)) return@withContext
            val api = runCatching { clients.api(owner.server, owner) }.getOrNull() ?: return@withContext
            runCatchingSuspend {
                val body = progressBody(itemId, positionMs, method, playSessionId)
                when (event) {
                    ReportEvent.Started -> api.reportPlaybackStarted(body)
                    ReportEvent.Progress -> api.reportPlaybackProgress(body)
                    ReportEvent.Stopped -> api.reportPlaybackStopped(body)
                }
            }.onFailure { logger.w(TAG, "播放进度上报失败", it) }
        }
    }

    private fun progressBody(
        itemId: String,
        positionMs: Long,
        method: PlayMethod,
        playSessionId: String?,
    ) = PlaybackProgressDto(
        ItemId = itemId,
        PositionTicks = positionMs * TICKS_PER_MS,
        PlayMethod = when (method) {
            PlayMethod.DirectPlay -> "DirectPlay"
            PlayMethod.DirectStream -> "DirectStream"
            PlayMethod.Transcode -> "Transcode"
        },
        PlaySessionId = playSessionId,
    )

    /** 无播放信息时的兜底：Emby 标准静态流。 */
    private fun fallbackPlan(server: String, itemId: String, startPositionMs: Long, token: String?): PlaybackPlan {
        val url = buildString {
            append(server.trimEnd('/'))
            append("/Videos/").append(itemId).append("/stream?Static=true")
            if (!token.isNullOrBlank()) {
                append("&api_key=").append(java.net.URLEncoder.encode(token, "UTF-8"))
            }
        }
        return PlaybackPlan(
            url = url,
            method = PlayMethod.DirectPlay,
            audioTracks = emptyList(),
            subtitleTracks = emptyList(),
            subtitles = emptyList(),
            durationMs = null,
            startPositionMs = startPositionMs,
        )
    }

    /**
     * 选出主地址与兜底地址。
     *
     * 回退链严格按「直连 → 转封装 → 转码」推进：主地址失败时播放器会用 [ResolvedUrls.fallbackUrl]
     * 重新起播并保留进度。用户关闭优先直连时直接给转码地址，不再保留直连兜底
     * （否则等于把用户的选择又绕回去了）。
     */
    private fun resolveUrls(
        server: String,
        source: MediaSourceDto,
        itemId: String,
        preferDirect: Boolean,
        forceTranscode: Boolean,
        token: String?,
    ): ResolvedUrls {
        val directPath = source.DirectStreamUrl?.takeIf { source.SupportsDirectPlay || source.SupportsDirectStream }
        val transcodePath = source.TranscodingUrl

        if (forceTranscode) {
            val absolute = (transcodePath ?: directPath).toAbsoluteUrl(server)
            return ResolvedUrls(
                primaryUrl = absolute ?: fallbackPlan(server, itemId, 0L, token).url,
                // 没有任何可用地址时实际播的是静态流（DirectPlay），不能上报 DirectStream
                primaryMethod = when {
                    absolute == null -> PlayMethod.DirectPlay
                    transcodePath != null -> PlayMethod.Transcode
                    else -> PlayMethod.DirectStream
                },
                fallbackUrl = null,
                fallbackMethod = null,
            )
        }

        if (!preferDirect) {
            val absolute = (transcodePath ?: directPath).toAbsoluteUrl(server)
            return ResolvedUrls(
                primaryUrl = absolute ?: fallbackPlan(server, itemId, 0L, token).url,
                primaryMethod = when {
                    absolute == null -> PlayMethod.DirectPlay
                    transcodePath != null -> PlayMethod.Transcode
                    else -> PlayMethod.DirectStream
                },
                fallbackUrl = null,
                fallbackMethod = null,
            )
        }

        val primary = directPath ?: transcodePath
        val primaryMethod = when {
            // 直连/转码地址都没有：实际回落的是静态流（DirectPlay），不能上报 Transcode
            primary == null -> PlayMethod.DirectPlay
            source.SupportsDirectPlay && directPath != null -> PlayMethod.DirectPlay
            directPath != null -> PlayMethod.DirectStream
            else -> PlayMethod.Transcode
        }
        // 兜底只在「主地址不是转码」且转码地址确实存在时才有意义
        val fallback = transcodePath?.takeIf { primaryMethod != PlayMethod.Transcode && it != primary }
        return ResolvedUrls(
            primaryUrl = primary.toAbsoluteUrl(server) ?: fallbackPlan(server, itemId, 0L, token).url,
            primaryMethod = primaryMethod,
            fallbackUrl = fallback.toAbsoluteUrl(server),
            fallbackMethod = fallback?.let { PlayMethod.Transcode },
        )
    }

    /** An explicit subtitle choice may only use a server URL that honors that exact choice. */
    private fun resolveSubtitleUrls(
        server: String,
        source: MediaSourceDto,
        preferDirect: Boolean,
        forceTranscode: Boolean,
        subtitleIndex: Int,
    ): ResolvedUrls? {
        val transcode = source.TranscodingUrl.toAbsoluteUrl(server)?.takeIf { url ->
            urlMatchesSource(url, source.Id) && if (subtitleIndex >= 0) burnedInSubtitleIndex(url) == subtitleIndex
            else queryParameter(url, "SubtitleStreamIndex")?.toIntOrNull() == -1
        }
        if (subtitleIndex >= 0 || forceTranscode) {
            return transcode?.let { ResolvedUrls(it, PlayMethod.Transcode, null, null) }
        }
        val direct = source.DirectStreamUrl
            ?.takeIf { source.SupportsDirectPlay || source.SupportsDirectStream }
            .toAbsoluteUrl(server)
            ?.takeIf { urlMatchesSource(it, source.Id) &&
                (!hasQueryParameter(it, "SubtitleMethod") || queryParameter(it, "SubtitleMethod")?.let { method ->
                    !method.equals("Encode", ignoreCase = true)
                } == true) }
        // Disabling a burned subtitle may restore the same source's original static/direct stream.
        // No synthesized static URL and no old encoded fallback may silently undo the request.
        val primary = if (preferDirect) direct ?: transcode else transcode ?: direct
        if (primary == null) return null
        val method = if (primary == transcode) PlayMethod.Transcode else
            if (source.SupportsDirectPlay) PlayMethod.DirectPlay else PlayMethod.DirectStream
        return ResolvedUrls(primary, method, transcode?.takeIf { it != primary },
            PlayMethod.Transcode.takeIf { transcode != null && transcode != primary })
    }

    private companion object {
        const val TAG = "PlaybackRepo"

        /** 1 tick = 100ns。 */
        const val TICKS_PER_MS = 10_000L
    }
}

/**
 * 判断片源是否超出本机解码能力。
 *
 * 目前覆盖两类真实会翻车的场景：
 * - **Dolby Vision**：显示端不支持 DV 时，ExoPlayer 会在解码器初始化阶段直接失败；
 * - **编码缺硬解**：例如只有 h264 解码器的老盒子拿到 hevc 4K。
 *
 * **仅诊断日志用**（`ForLog` 后缀 = 契约测试的日志文案豁免标记，勿改名）：
 * 文案只进 [logger.w]，不面向用户。
 *
 * @param capabilities 设备能力快照。
 * @return 不支持的原因；可直连时返回 null。
 */
private fun MediaSourceDto.unsupportedReasonForLog(capabilities: DeviceCapabilities): String? {
    val video = MediaStreams.firstOrNull { it.Type == "Video" } ?: return null
    if (isDolbyVision()) {
        // Dolby Vision 统一使用服务端转码，避免普通 HEVC 解码路径错误处理 DV 色彩信息。
        return "Dolby Vision（${video.VideoRangeType.orEmpty().ifBlank { "DOVI" }}，一律转码保色彩正确）"
    }
    return unsupportedVideoCodec(capabilities)?.let { "缺少 $it 硬件解码" }
}

/**
 * 片源视频编码若缺少本机硬件解码，返回该编码（小写）；Dolby Vision、可直连、
 * 无视频流或编码未知均返回 null。
 *
 * 供 [unsupportedReasonForLog] 组织日志文案，也决定是否把编码回写进
 * `failedVideoCodecs`——只有真正解不了的编码才该被排除直连。
 */
internal fun MediaSourceDto.unsupportedVideoCodec(capabilities: DeviceCapabilities): String? {
    if (isDolbyVision()) return null
    val video = MediaStreams.firstOrNull { it.Type == "Video" } ?: return null
    val codec = video.Codec?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    return codec.takeIf { it !in capabilities.videoCodecs }
}

/** 视频流是否标记为 Dolby Vision（VideoRangeType 的 DOVI* 或 VideoRange 含 Dolby）。 */
private fun MediaSourceDto.isDolbyVision(): Boolean {
    val video = MediaStreams.firstOrNull { it.Type == "Video" } ?: return false
    return video.VideoRangeType?.contains("DOVI", ignoreCase = true) == true ||
        video.VideoRange?.contains("Dolby", ignoreCase = true) == true
}

/**
 * 主地址与兜底地址。
 *
 * @param primaryUrl 首选播放地址。
 * @param primaryMethod 首选地址的播放方式。
 * @param fallbackUrl 失败后改用的地址，可能为空。
 * @param fallbackMethod 兜底地址的播放方式。
 */
private data class ResolvedUrls(
    val primaryUrl: String,
    val primaryMethod: PlayMethod,
    val fallbackUrl: String?,
    val fallbackMethod: PlayMethod?,
)

/** 相对地址补全为绝对地址；空值原样返回 null。 */
private fun String?.toAbsoluteUrl(server: String): String? {
    val path = this?.takeIf { it.isNotBlank() } ?: return null
    return ServerAddress.resolveUrl(server, path)
}

/** Compare rates in bits/second; raw file bytes cannot be compared with a stream's BitRate. */
private fun MediaSourceDto.selectionBitrate(): Double? {
    MediaStreams.firstOrNull { it.Type == "Video" }?.BitRate
        ?.takeIf { it > 0L }?.let { return it.toDouble() }
    val bytes = Size?.takeIf { it > 0L } ?: return null
    val ticks = RunTimeTicks?.takeIf { it > 0L } ?: return null
    return bytes.toDouble() * 80_000_000.0 / ticks
}

/**
 * 弹幕匹配用的资源文件名：优先 `Path` 的文件段（去目录与查询串），
 * 缺 Path 时回落 `Name`；两者都空返回 null（弹幕只能手动搜索匹配）。
 */
internal fun MediaSourceDto.danmuFileName(): String? {
    val fromPath = Path?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
    return fromPath ?: Name?.takeIf { it.isNotBlank() }
}

/**
 * 从媒体源提取视频规格，供播放器信息面板展示。
 *
 * HDR 只认服务端明确标注的 HDR / Dolby，SDR 保持 null，界面按「SDR」展示。
 */
private fun MediaSourceDto.videoSpec(): VideoSpec? {
    val video = MediaStreams.firstOrNull { it.Type == "Video" } ?: return null
    val audio = MediaStreams.firstOrNull { it.Type == "Audio" }
    return VideoSpec(
        width = video.Width,
        height = video.Height,
        codec = video.Codec,
        bitrate = video.BitRate,
        frameRate = video.FrameRate,
        hdr = video.VideoRange?.takeIf { range ->
            range.contains("HDR", ignoreCase = true) || range.contains("Dolby", ignoreCase = true)
        },
        container = Container,
        audioCodec = audio?.Codec,
        audioChannels = audio?.Channels,
    )
}

/**
 * 外挂字幕源收集。
 *
 * 优先使用服务端提供的 DeliveryUrl；外部字幕缺少该地址时，按 Emby 标准端点构造。
 * 内嵌字幕随容器播放，不需要单独的字幕地址。
 */
private fun MediaSourceDto.externalSubtitles(
    server: String,
    itemId: String,
    token: String?,
): List<SubtitleSource> =
    MediaStreams
        .mapIndexed { index, stream ->
            if (stream.Type != "Subtitle") return@mapIndexed null
            val streamIndex = stream.Index ?: index
            // 优先使用服务端地址；外部字幕缺少该地址时按 Emby 标准端点构造。
            val delivered = stream.DeliveryUrl.toAbsoluteUrl(server)
            val constructed = if (stream.IsExternal && !stream.Codec.isNullOrBlank()) {
                buildString {
                    append("${server.trimEnd('/')}/Videos/$itemId/${Id.orEmpty()}/Subtitles/$streamIndex/Stream.${stream.Codec?.lowercase()}")
                    if (!token.isNullOrBlank()) {
                        append("?api_key=").append(java.net.URLEncoder.encode(token, "UTF-8"))
                    }
                }
            } else {
                null
            }
            val url = delivered ?: constructed ?: return@mapIndexed null
            val mimeType = stream.Codec.toSubtitleMime() ?: return@mapIndexed null
            SubtitleSource(
                id = "sub-$streamIndex-${stream.Language.orEmpty()}",
                url = url,
                language = stream.Language.normalizeLanguage(),
                label = stream.DisplayTitle ?: stream.Title,
                mimeType = mimeType,
            )
        }.mapNotNull { it }

private fun String?.normalizeLanguage(): String? = this
    ?.substringBefore('&')
    ?.substringBefore(' ')
    ?.trim()
    ?.takeIf { it.isNotBlank() && it != "und" }

/** Unsupported binary formats must never be advertised as plain-text SRT. */
internal fun String?.toSubtitleMime(): String? = when (this?.lowercase()) {
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt", "webvtt" -> MimeTypes.TEXT_VTT
    "ttml" -> MimeTypes.APPLICATION_TTML
    "mov_text" -> MimeTypes.APPLICATION_SUBRIP
    "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
    "pgs", "pgssub", "hdmv_pgs_subtitle", "sup" -> MimeTypes.APPLICATION_PGS
    // Raw DVB/VobSub deliveries need initialization/palette data not carried by SubtitleSource.
    // Embedded DVB/PGS cues are rendered from the container through the native subtitle view.
    else -> null
}

internal fun burnedInSubtitleIndex(url: String): Int? {
    if (!queryParameter(url, "SubtitleMethod").equals("Encode", ignoreCase = true)) return null
    return queryParameter(url, "SubtitleStreamIndex")?.toIntOrNull()?.takeIf { it >= 0 }
}

/** HLS manifests retain the full media timeline; only a sliced non-HLS stream needs an offset. */
internal fun transcodeStreamStartPositionMs(url: String, method: PlayMethod): Long {
    if (method != PlayMethod.Transcode ||
        url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
        queryParameter(url, "Protocol").equals("hls", ignoreCase = true) ||
        queryParameter(url, "CopyTimestamps").equals("true", ignoreCase = true)
    ) return 0L
    return queryParameter(url, "StartTimeTicks")?.toLongOrNull()?.coerceAtLeast(0L)?.div(10_000L) ?: 0L
}

/** Duplicate or malformed selection parameters are not evidence that the server honored a choice. */
private fun queryParameter(url: String, name: String): String? {
    val matches = url.substringBefore('#').substringAfter('?', "").split('&').filter { part ->
        val key = runCatching { java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") }.getOrNull()
        key.equals(name, ignoreCase = true)
    }
    val part = matches.singleOrNull() ?: return null
    return runCatching { java.net.URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
}

private fun hasQueryParameter(url: String, name: String): Boolean =
    url.substringBefore('#').substringAfter('?', "").split('&').any { part ->
        runCatching { java.net.URLDecoder.decode(part.substringBefore('='), "UTF-8") }
            .getOrNull().equals(name, ignoreCase = true)
    }

private fun urlMatchesSource(url: String, sourceId: String?): Boolean =
    !hasQueryParameter(url, "MediaSourceId") || queryParameter(url, "MediaSourceId") == sourceId

/** 过滤出指定类型的轨，并生成可展示的选项。 */
private fun MediaSourceDto.tracksOfType(type: String, strings: MediaStrings): List<TrackOption> =
    MediaStreams.filter { it.Type == type && (type != "Subtitle" || it.Index?.let { index -> index >= 0 } == true) }
        .mapIndexed { index, stream ->
            TrackOption(
                index = stream.Index ?: index,
                label = stream.displayLabel(type, index, strings),
                language = stream.Language.normalizeLanguage(),
            )
        }

/** 轨道展示名：优先服务端标题，其次「语言 · 编码」（兜底文案经 [MediaStrings]）。 */
private fun MediaStreamDto.displayLabel(type: String, index: Int, strings: MediaStrings): String {
    // 兜底名自带序号（「音轨 2」），只拼后面的规格段
    val prefix = if (type == "Audio") strings.audioTrackLabel(index + 1) else strings.subtitleTrackLabel(index + 1)
    val title = DisplayTitle?.takeIf { it.isNotBlank() }
    val detail = listOfNotNull(
        Language?.takeIf { it.isNotBlank() && it != "und" },
        Codec?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    return when {
        title != null && detail.isNotEmpty() -> "$title（$detail）"
        title != null -> title
        detail.isNotEmpty() -> "$prefix · $detail"
        else -> prefix
    }
}
