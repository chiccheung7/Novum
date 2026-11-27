package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.MediaSourceDto
import com.novum.tv.data.remote.emby.MediaStreamDto
import com.novum.tv.data.remote.emby.PlaybackInfoDto
import com.novum.tv.data.remote.emby.PlaybackInfoRequestDto
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.core.capability.DeviceCapabilities
import com.novum.tv.model.PlayMethod
import com.novum.tv.testing.FakeCapabilityProvider
import com.novum.tv.testing.EmbyApiStub
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.data.remote.emby.PlaybackProgressDto
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放仓储测试：地址选择策略、轨映射、异常兜底、未登录短路。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultPlaybackRepositoryTest {

    @Test
    fun actualEncodedSubtitleIsReadOnlyEvenWhenItsMetadataIsMissing() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(MediaSourceDto(
            Id = "src", SupportsDirectPlay = false,
            TranscodingUrl = "/play/master.m3u8?SubtitleMethod=Encode&SubtitleStreamIndex=7",
            MediaStreams = emptyList(),
        ))))
        val plan = (repository(api).plan("movie") as NovumResult.Success).value
        assertTrue(!plan.burnedInSubtitleLabel.isNullOrBlank())
        assertTrue(plan.subtitles.isEmpty())
        assertEquals("src", plan.mediaSourceId)
        assertEquals(7, plan.burnedInSubtitleIndex)
        assertEquals(7, plan.serverSubtitleStreamIndex)
        assertTrue(plan.serverSubtitleSelection)
    }

    @Test
    fun changingServerSubtitleRequestsTheExactVersionTrackAndCurrentPosition() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(
            PlaySessionId = "new-subtitle-session",
            MediaSources = listOf(
                subtitleSource().copy(Id = "default-version"),
                subtitleSource(),
            ),
        ))
        val plan = (repository(api).plan(
            "movie", startPositionMs = 123_456L, mediaSourceId = "source-4k", subtitleStreamIndex = 7,
        ) as NovumResult.Success).value
        val body = api.requests.single()

        assertEquals("source-4k", body.MediaSourceId)
        assertEquals(7, body.SubtitleStreamIndex)
        assertEquals(1_234_560_000L, body.StartTimeTicks)
        assertEquals(false, body.EnableDirectPlay)
        assertEquals(false, body.EnableDirectStream)
        assertEquals(false, body.AllowVideoStreamCopy)
        assertEquals(true, body.EnableTranscoding)
        assertTrue(body.DeviceProfile.DirectPlayProfiles.isEmpty())
        assertTrue(body.DeviceProfile.SubtitleProfiles.all { it.Method == "Encode" })
        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals("source-4k", plan.mediaSourceId)
        assertEquals("new-subtitle-session", plan.playSessionId)
        assertEquals(123_456L, plan.startPositionMs)
        assertEquals(0L, plan.streamStartPositionMs)
        assertEquals(7, plan.burnedInSubtitleIndex)
        assertTrue(plan.serverSubtitleSelection)
        assertEquals(7, plan.serverSubtitleStreamIndex)
        assertEquals(listOf(3, 7), plan.subtitleTracks.map { it.index })
        assertTrue(plan.subtitles.isEmpty())
        assertNull(plan.fallbackUrl)
    }

    @Test
    fun switchingOffBurnedSubtitlesRestoresDirectStreamAndDropsAnOldEncodedFallback() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(subtitleSource())))

        val plan = (repository(api).plan(
            "movie", startPositionMs = 71_000L, mediaSourceId = "source-4k", subtitleStreamIndex = -1,
        ) as NovumResult.Success).value

        assertEquals(-1, api.requests.single().SubtitleStreamIndex)
        assertEquals(710_000_000L, api.requests.single().StartTimeTicks)
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertEquals("https://smart.uhdnow.com/provider/movie?api_key=fixture", plan.url)
        assertNull(plan.burnedInSubtitleLabel)
        assertNull(plan.burnedInSubtitleIndex)
        assertNull(plan.fallbackUrl)
        assertTrue(plan.serverSubtitleSelection)
        assertEquals(-1, plan.serverSubtitleStreamIndex)
        assertEquals(listOf(3, 7), plan.subtitleTracks.map { it.index })
    }

    @Test
    fun switchingOffCanUseANewTranscodeWithoutSubtitlesWhenDirectIsUnavailable() = runTest(dispatcher) {
        val source = subtitleSource().copy(
            SupportsDirectPlay = false,
            DirectStreamUrl = null,
            TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=-1&SubtitleMethod=Encode",
        )
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(source)))
        val plan = (repository(api).plan(
            "movie", startPositionMs = 60_000, mediaSourceId = "source-4k", subtitleStreamIndex = -1,
        ) as NovumResult.Success).value

        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals(-1, plan.serverSubtitleStreamIndex)
        assertNull(plan.burnedInSubtitleLabel)
        assertEquals(0L, plan.streamStartPositionMs)
    }

    @Test
    fun slicedTranscodeCarriesTheActualServerStartOffsetWithoutChangingAbsoluteResumePosition() = runTest(dispatcher) {
        val source = subtitleSource().copy(
            TranscodingUrl = "/provider/stream.ts?SubtitleStreamIndex=7&SubtitleMethod=Encode&StartTimeTicks=1200000000",
        )
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(source)))
        val plan = (repository(api).plan(
            "movie", startPositionMs = 120_000, mediaSourceId = "source-4k", subtitleStreamIndex = 7,
        ) as NovumResult.Success).value

        assertEquals(120_000L, plan.startPositionMs)
        assertEquals(120_000L, plan.streamStartPositionMs)
        assertTrue(plan.url.endsWith("StartTimeTicks=1200000000"))
    }

    @Test
    fun explicitSubtitleChoiceNeverFallsBackToStaticAfterAPlaybackInfoFailure() = runTest(dispatcher) {
        val result = repository(StubPlaybackApi(error = IOException("offline"))).plan(
            "movie", mediaSourceId = "source-4k", subtitleStreamIndex = 7,
        )
        assertTrue(result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Network)
    }

    @Test
    fun subtitleSwitchRejectsMissingVersionAndWrongOrMissingSubtitleInsteadOfUsingDefaults() = runTest(dispatcher) {
        val rejectedSources = listOf(
            subtitleSource().copy(Id = "different-version"),
            subtitleSource().copy(MediaStreams = emptyList()),
            subtitleSource().copy(TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=3&SubtitleMethod=Encode"),
            subtitleSource().copy(TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=7&SubtitleMethod=External"),
            subtitleSource().copy(TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=7&SubtitleMethod=Encode&MediaSourceId=wrong-version"),
            subtitleSource().copy(TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=7&SubtitleStreamIndex=3&SubtitleMethod=Encode"),
            subtitleSource().copy(TranscodingUrl = null),
        )
        for (source in rejectedSources) {
            val result = repository(StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(source)))).plan(
                "movie", mediaSourceId = "source-4k", subtitleStreamIndex = 7,
            )
            assertTrue("Must reject an unfulfilled subtitle switch: $source", result is NovumResult.Failure)
        }
    }

    @Test
    fun disablingSubtitlesRejectsTheOldBurnedStreamWhenNoCleanUrlIsReturned() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(
            subtitleSource().copy(SupportsDirectPlay = false, DirectStreamUrl = null),
        )))
        val result = repository(api).plan("movie", mediaSourceId = "source-4k", subtitleStreamIndex = -1)
        assertTrue(result is NovumResult.Failure)
    }

    @Test
    fun explicitSubtitleNeedsAValidMediaSourceAndStreamIndexBeforeMakingARequest() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(subtitleSource())))
        for ((sourceId, index) in listOf(null to 7, "" to -1, "source-4k" to -2)) {
            val result = repository(api).plan("movie", mediaSourceId = sourceId, subtitleStreamIndex = index)
            assertTrue(result is NovumResult.Failure)
        }
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun automaticPlansKeepTheExistingProtocolAndExposeTheRealVersionForLaterSwitches() = runTest(dispatcher) {
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(subtitleSource())))
        val plan = (repository(api).plan("movie") as NovumResult.Success).value
        assertNull(api.requests.single().SubtitleStreamIndex)
        assertNull(api.requests.single().StartTimeTicks)
        assertEquals("source-4k", plan.mediaSourceId)
        assertTrue(!plan.serverSubtitleSelection)
        assertEquals(7, plan.fallbackBurnedInSubtitleIndex)
    }

    @Test
    fun serverSubtitleCatalogDoesNotInventIndicesForUnidentifiedTracks() = runTest(dispatcher) {
        val source = subtitleSource().copy(MediaStreams = listOf(
            MediaStreamDto(Type = "Subtitle", Index = null, DisplayTitle = "Missing index"),
            MediaStreamDto(Type = "Subtitle", Index = -1, DisplayTitle = "Invalid index"),
            MediaStreamDto(Type = "Subtitle", Index = 7, DisplayTitle = "Identified subtitle"),
        ))
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(source)))
        val plan = (repository(api).plan("movie") as NovumResult.Success).value
        assertEquals(listOf(7), plan.subtitleTracks.map { it.index })
    }

    @Test
    fun localTimelineOffsetOnlyAppliesToSlicedNonHlsTranscoding() {
        val query = "?StartTimeTicks=1234500000"
        assertEquals(123_450L, transcodeStreamStartPositionMs("https://media/stream.ts$query", PlayMethod.Transcode))
        assertEquals(0L, transcodeStreamStartPositionMs("https://media/master.m3u8$query", PlayMethod.Transcode))
        assertEquals(0L, transcodeStreamStartPositionMs("https://media/stream$query&Protocol=hls", PlayMethod.Transcode))
        assertEquals(0L, transcodeStreamStartPositionMs("https://media/stream.ts$query&CopyTimestamps=true", PlayMethod.Transcode))
        assertEquals(0L, transcodeStreamStartPositionMs("https://media/stream.ts$query", PlayMethod.DirectPlay))
        assertEquals(0L, transcodeStreamStartPositionMs("https://media/stream.ts?StartTimeTicks=-1", PlayMethod.Transcode))
    }

    private fun subtitleSource() = MediaSourceDto(
        Id = "source-4k", SupportsDirectPlay = true,
        DirectStreamUrl = "/provider/movie?api_key=fixture",
        TranscodingUrl = "/provider/master.m3u8?SubtitleStreamIndex=7&SubtitleMethod=Encode",
        MediaStreams = listOf(
            MediaStreamDto(Type = "Subtitle", Index = 3, Codec = "ass", Language = "zh-Hant", DisplayTitle = "繁体"),
            MediaStreamDto(Type = "Subtitle", Index = 7, Codec = "subrip", Language = "zh-Hans", DisplayTitle = "简体"),
        ),
    )

    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher()

    private val dispatchers = object : DispatcherProvider {
        override val default = dispatcher
        override val io = dispatcher
        override val main = dispatcher
    }

    @Test
    fun usesServerProvidedDirectUrlAsAbsoluteUrl() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-1",
                        Container = "mkv",
                        SupportsDirectPlay = true,
                        SupportsDirectStream = true,
                        DirectStreamUrl = "/play/video/file-1?api_key=abc",
                        MediaStreams = listOf(
                            MediaStreamDto(Type = "Video", Index = 0, Codec = "h264"),
                            MediaStreamDto(
                                Type = "Audio",
                                Index = 1,
                                Codec = "aac",
                                Language = "zh",
                            ),
                            MediaStreamDto(
                                Type = "Subtitle",
                                Index = 2,
                                Codec = "subrip",
                                Language = "zh",
                            ),
                        ),
                        RunTimeTicks = 6_000_000_000L,
                    ),
                ),
            ),
        )

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        // 托管商自定义路径必须原样拼接，不能改写成标准 /Videos/{id}/stream
        assertEquals("https://smart.uhdnow.com/play/video/file-1?api_key=abc", plan.url)
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertEquals(600_000L, plan.durationMs)
        assertEquals(1, plan.audioTracks.size)
        assertEquals(1, plan.subtitleTracks.size)
        assertTrue(plan.audioTracks.first().label.contains("aac"))
    }

    @Test
    fun usesTranscodingUrlWhenPreferDirectDisabled() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-3",
                        SupportsDirectPlay = true,
                        SupportsDirectStream = true,
                        DirectStreamUrl = "/play/video/file-2?api_key=abc",
                        TranscodingUrl = "/videos/movie-1/master.m3u8?api_key=abc",
                    ),
                ),
            ),
        )

        val settings = FakeSettingsRepository(AppSettings(preferDirect = false))
        val plan = (repository(api, settings).plan("movie-1") as NovumResult.Success).value

        assertEquals(
            "https://smart.uhdnow.com/videos/movie-1/master.m3u8?api_key=abc",
            plan.url,
        )
        assertEquals(PlayMethod.Transcode, plan.method)
    }

    @Test
    fun fallsBackToTranscodingWhenNoDirectUrl() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-2",
                        SupportsDirectPlay = false,
                        SupportsTranscoding = true,
                        TranscodingUrl = "/videos/movie-1/master.m3u8?api_key=abc",
                    ),
                ),
            ),
        )

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        assertEquals("https://smart.uhdnow.com/videos/movie-1/master.m3u8?api_key=abc", plan.url)
        assertEquals(PlayMethod.Transcode, plan.method)
    }

    @Test
    fun exposesTranscodeUrlAsFallbackForDirectPlayAndMapsVideoSpec() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-1",
                        Container = "mkv",
                        SupportsDirectPlay = true,
                        SupportsDirectStream = true,
                        DirectStreamUrl = "/play/video/file-1?api_key=abc",
                        TranscodingUrl = "/videos/movie-1/master.m3u8?api_key=abc",
                        MediaStreams = listOf(
                            MediaStreamDto(
                                Type = "Video",
                                Codec = "hevc",
                                Width = 3840,
                                Height = 2160,
                                BitRate = 15_000_000,
                                FrameRate = 23.976f,
                                VideoRange = "HDR",
                            ),
                            MediaStreamDto(Type = "Audio", Codec = "eac3", Channels = 6),
                        ),
                    ),
                ),
            ),
        )

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        assertEquals("https://smart.uhdnow.com/play/video/file-1?api_key=abc", plan.url)
        assertEquals(PlayMethod.DirectPlay, plan.method)
        // 直连为主时，转码地址必须作为兜底随计划一起下发，播放器才能在失败后无缝切换
        assertEquals(
            "https://smart.uhdnow.com/videos/movie-1/master.m3u8?api_key=abc",
            plan.fallbackUrl,
        )
        assertEquals(PlayMethod.Transcode, plan.fallbackMethod)
        assertEquals("3840×2160", plan.videoSpec?.resolution)
        assertEquals("HDR", plan.videoSpec?.hdr)
        assertEquals(6, plan.videoSpec?.audioChannels)
    }

    @Test
    fun hasNoFallbackWhenPrimaryIsAlreadyTranscode() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-2",
                        SupportsDirectPlay = false,
                        TranscodingUrl = "/videos/movie-1/master.m3u8?api_key=abc",
                    ),
                ),
            ),
        )

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        assertEquals(PlayMethod.Transcode, plan.method)
        assertNull("已经是转码就没有兜底可切", plan.fallbackUrl)
    }

    @Test
    fun requestsTranscodeWhenDolbyVisionIsUnsupported() = runTest(dispatcher) {
        val api = object : EmbyApiStub() {
            val bodies = mutableListOf<PlaybackInfoRequestDto>()
            override suspend fun playbackInfo(
                itemId: String,
                userId: String,
                body: PlaybackInfoRequestDto,
            ): PlaybackInfoDto {
                bodies += body
                // 第一次按直连返回，第二次（只声明转码）返回转码地址
                val direct = bodies.size == 1
                return PlaybackInfoDto(
                    MediaSources = listOf(
                        MediaSourceDto(
                            Id = "src-dv",
                            SupportsDirectPlay = direct,
                            SupportsDirectStream = direct,
                            DirectStreamUrl = if (direct) "/play/video/dv" else null,
                            TranscodingUrl = "/videos/dv/master.m3u8?api_key=abc",
                            MediaStreams = listOf(
                                MediaStreamDto(
                                    Type = "Video",
                                    Codec = "hevc",
                                    VideoRangeType = "DOVIWithHDR10",
                                    Width = 3840,
                                    Height = 2160,
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
        val noDv = FakeCapabilityProvider(
            DeviceCapabilities(
                videoCodecs = setOf("h264", "hevc"),
                supportsDolbyVision = false,
            ),
        )

        val settings = FakeSettingsRepository()

        val plan = (repository(api, settings = settings, capability = noDv).plan("movie-1") as NovumResult.Success).value

        assertEquals("应发起两次 PlaybackInfo（第二次强制转码）", 2, api.bodies.size)
        assertTrue(
            "第二次请求不能声明任何直连能力",
            api.bodies[1].DeviceProfile.DirectPlayProfiles.isEmpty(),
        )
        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals("https://smart.uhdnow.com/videos/dv/master.m3u8?api_key=abc", plan.url)
        // Dolby Vision 是刻意转码，hevc 本身并没有解不了：不能回写进 failedVideoCodecs，
        // 否则之后所有 HEVC 片源都会被排除直连
        assertEquals("DV 转码不得污染失败编码表", "", settings.settings.first().failedVideoCodecs)
    }

    @Test
    fun recordsVideoCodecWhenHardwareDecoderIsMissing() = runTest(dispatcher) {
        val api = object : EmbyApiStub() {
            val bodies = mutableListOf<PlaybackInfoRequestDto>()
            override suspend fun playbackInfo(
                itemId: String,
                userId: String,
                body: PlaybackInfoRequestDto,
            ): PlaybackInfoDto {
                bodies += body
                val direct = bodies.size == 1
                return PlaybackInfoDto(
                    MediaSources = listOf(
                        MediaSourceDto(
                            Id = "src-hevc",
                            SupportsDirectPlay = direct,
                            DirectStreamUrl = if (direct) "/play/video/hevc" else null,
                            TranscodingUrl = "/videos/hevc/master.m3u8?api_key=abc",
                            MediaStreams = listOf(
                                MediaStreamDto(Type = "Video", Codec = "hevc", Width = 3840, Height = 2160),
                            ),
                        ),
                    ),
                )
            }
        }
        val h264Only = FakeCapabilityProvider(DeviceCapabilities(videoCodecs = setOf("h264")))
        val settings = FakeSettingsRepository()

        val plan = (repository(api, settings = settings, capability = h264Only).plan("movie-1") as NovumResult.Success).value

        assertEquals("缺硬解的编码必须回写，下次协商直接排除", "hevc", settings.settings.first().failedVideoCodecs)
        assertEquals(PlayMethod.Transcode, plan.method)
    }

    @Test
    fun reportsDirectPlayWhenBothServerUrlsAreMissing() = runTest(dispatcher) {
        // 服务端既没给 DirectStreamUrl 也没给 TranscodingUrl：仓储回落 Emby 标准静态流，
        // 实际是直连播放——PlayMethod 不能上报成 Transcode（会话统计会失真）
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(MediaSourceDto(Id = "src-bare", Container = "mkv")),
            ),
        )

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertTrue("应回落静态流地址", plan.url.endsWith("/Videos/movie-1/stream?Static=true&api_key=token"))
        assertNull(plan.fallbackUrl)
    }

    @Test
    fun transcodesDolbyVisionEvenWhenSupported() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "src-dv",
                        SupportsDirectPlay = true,
                        DirectStreamUrl = "/play/video/dv",
                        TranscodingUrl = "/videos/dv/master.m3u8?api_key=abc",
                        MediaStreams = listOf(
                            MediaStreamDto(Type = "Video", Codec = "hevc", VideoRangeType = "DOVI"),
                        ),
                    ),
                ),
            ),
        )
        val dvTv = FakeCapabilityProvider(
            DeviceCapabilities(
                videoCodecs = setOf("h264", "hevc"),
                supportsDolbyVision = true,
            ),
        )

        val plan = (repository(api, capability = dvTv).plan("movie-1") as NovumResult.Success).value

        // Dolby Vision 使用转码，避免 ExoPlayer 按样例 MIME（hevc）选择
        // 解码器，DV profile 5 被普通 HEVC 解码器硬解 → 画面发绿（钢铁侠2
        // 普通解码器，服务端 tone-map 转码保持色彩正确。
        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals("https://smart.uhdnow.com/videos/dv/master.m3u8?api_key=abc", plan.url)
    }

    @Test
    fun fallsBackToStaticStreamWhenPlaybackInfoFails() = runTest(dispatcher) {
        val api = StubPlaybackApi(error = IOException("boom"))

        val plan = (repository(api).plan("movie-1") as NovumResult.Success).value

        assertEquals(
            "https://smart.uhdnow.com/Videos/movie-1/stream?Static=true&api_key=token",
            plan.url,
        )
        assertEquals(PlayMethod.DirectPlay, plan.method)
        assertTrue(plan.audioTracks.isEmpty())
    }

    @Test
    fun returnsUnauthorizedWithoutSession() = runTest(dispatcher) {
        val repository = DefaultPlaybackRepository(
            clients = FixedPlaybackProvider(StubPlaybackApi()),
            sessionHolder = SessionHolder(),
            settings = FakeSettingsRepository(),
            capability = FakeCapabilityProvider(),
            dispatchers = dispatchers,
            logger = NoopLogger,
            lineProbe = com.novum.tv.data.line.LineProbe(
                FixedPlaybackProvider(StubPlaybackApi()),
                SessionHolder(),
                NoopLogger,
            ),
            strings = FakeMediaStrings,
        )

        val result = repository.plan("movie-1")

        assertTrue(result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }

    @Test
    fun resolvesRootRelativePlaybackAndSubtitleUrlsWithoutDuplicatingProxyPath() = runTest(dispatcher) {
        listOf("/emby", "").forEach { deliveredPrefix ->
            val api = StubPlaybackApi(
                info = PlaybackInfoDto(
                    MediaSources = listOf(
                        MediaSourceDto(
                            Id = "source",
                            SupportsDirectPlay = true,
                            DirectStreamUrl = "$deliveredPrefix/Videos/movie-1/stream?api_key=signed",
                            MediaStreams = listOf(
                                MediaStreamDto(
                                    Type = "Subtitle",
                                    Index = 7,
                                    Codec = "subrip",
                                    DeliveryUrl = "$deliveredPrefix/Videos/movie-1/Subtitles/7/Stream.srt?api_key=signed",
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val plan = (repository(api, server = "https://media.example/emby").plan("movie-1") as NovumResult.Success).value

            assertEquals("https://media.example/emby/Videos/movie-1/stream?api_key=signed", plan.url)
            assertEquals(
                "https://media.example/emby/Videos/movie-1/Subtitles/7/Stream.srt?api_key=signed",
                plan.subtitles.single().url,
            )
        }
    }

    @Test
    fun constructedExternalSubtitleUsesStreamIndexAndEncodedSessionToken() = runTest(dispatcher) {
        val api = StubPlaybackApi(
            info = PlaybackInfoDto(
                MediaSources = listOf(
                    MediaSourceDto(
                        Id = "source",
                        SupportsDirectPlay = true,
                        DirectStreamUrl = "/Videos/movie-1/stream",
                        MediaStreams = listOf(
                            MediaStreamDto(Type = "Video", Index = 0, Codec = "h264"),
                            MediaStreamDto(Type = "Audio", Index = 2, Codec = "aac"),
                            MediaStreamDto(Type = "Subtitle", Index = 7, Codec = "srt", IsExternal = true),
                        ),
                    ),
                ),
            ),
        )

        val plan = (repository(api, token = "fixture+token&value").plan("movie-1") as NovumResult.Success).value

        assertEquals(7, plan.subtitleTracks.single().index)
        assertEquals("sub-7-", plan.subtitles.single().id)
        assertEquals(
            "https://smart.uhdnow.com/Videos/movie-1/source/Subtitles/7/Stream.srt?api_key=fixture%2Btoken%26value",
            plan.subtitles.single().url,
        )
    }

    @Test
    fun fallbackNeverAddsTheNewSessionTokenToThePreviousServerUrl() = runTest(dispatcher) {
        val holder = SessionHolder().apply {
            update(Session("https://old.example/emby", "old-fixture-token", "fixture-user", "Fixture", "Fixture server", "fixture-device"))
        }
        val api = object : EmbyApiStub() {
            override suspend fun playbackInfo(
                itemId: String,
                userId: String,
                body: PlaybackInfoRequestDto,
            ): PlaybackInfoDto {
                holder.update(Session("https://new.example", "new-fixture-token", "fixture-user", "Fixture", "Fixture server", "fixture-device"))
                throw IOException("Fixture request failed after the session changed")
            }
        }

        val result = repository(api, sessionHolder = holder).plan("movie-1")

        assertTrue("A plan whose account changed must not publish any playback URL", result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }

    @Test
    fun latePlaybackInfoCannotPublishAPlanAfterSwitchingAccountsOnTheSameServer() = runTest(dispatcher) {
        val holder = activeHolder()
        val api = object : EmbyApiStub() {
            override suspend fun playbackInfo(itemId: String, userId: String, body: PlaybackInfoRequestDto): PlaybackInfoDto {
                holder.update(holderSession("second-user"))
                return PlaybackInfoDto(MediaSources = listOf(MediaSourceDto(
                    SupportsDirectPlay = true, DirectStreamUrl = "/play/movie?api_key=first-token",
                )))
            }
        }

        val result = repository(api, sessionHolder = holder).plan("movie-1")
        assertTrue(result is NovumResult.Failure)
        assertTrue((result as NovumResult.Failure).error is NovumError.Unauthorized)
    }

    @Test
    fun accountSwitchWhileReadingSettingsStopsThePlanBeforePlaybackInfo() = runTest(dispatcher) {
        val holder = activeHolder()
        var infoCalls = 0
        val settings = object : SettingsRepository {
            override val settings = flow {
                holder.update(holderSession("second-user"))
                emit(AppSettings())
            }
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        val api = object : EmbyApiStub() {
            override suspend fun playbackInfo(itemId: String, userId: String, body: PlaybackInfoRequestDto): PlaybackInfoDto {
                infoCalls++
                return PlaybackInfoDto()
            }
        }

        val result = repository(api, settings, sessionHolder = holder).plan("movie-1")
        assertTrue(result is NovumResult.Failure)
        assertEquals(0, infoCalls)
    }

    @Test
    fun playerBoundReporterDropsEventsAfterAnAccountSwitch() = runTest(dispatcher) {
        val holder = activeHolder()
        val calls = mutableListOf<String>()
        val reporter = repository(reportingApi(calls), sessionHolder = holder).sessionReporter()
        holder.update(holderSession("second-user"))

        reporter.reportStarted("movie-1", 0, PlayMethod.DirectPlay, "old-session")
        reporter.reportProgress("movie-1", 1_000, PlayMethod.DirectPlay, "old-session")
        reporter.reportStopped("movie-1", 2_000, PlayMethod.DirectPlay, "old-session")
        assertTrue("Old events must never be sent with the new account", calls.isEmpty())
    }

    @Test
    fun playerBoundReporterStillReportsForItsActiveAccount() = runTest(dispatcher) {
        val calls = mutableListOf<String>()
        val reporter = repository(reportingApi(calls), sessionHolder = activeHolder()).sessionReporter()
        reporter.reportStarted("movie-1", 0, PlayMethod.DirectPlay, "session")
        reporter.reportProgress("movie-1", 1_000, PlayMethod.DirectPlay, "session")
        reporter.reportStopped("movie-1", 2_000, PlayMethod.DirectPlay, "session")
        assertEquals(listOf("started", "progress", "stopped"), calls)
    }

    @Test
    fun directReportWaitingForIoDoesNotUseANewAccount() = runTest(dispatcher) {
        val holder = activeHolder()
        val calls = mutableListOf<String>()
        val queuedIo = StandardTestDispatcher(testScheduler)
        val queuedDispatchers = object : DispatcherProvider {
            override val default = dispatcher
            override val main = dispatcher
            override val io = queuedIo
        }
        val repository = repository(reportingApi(calls), sessionHolder = holder, dispatcherProvider = queuedDispatchers)
        val report = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.reportStopped("movie-1", 1_000, PlayMethod.DirectPlay, "session")
        }
        holder.update(holderSession("second-user"))
        runCurrent()
        report.join()
        assertTrue(calls.isEmpty())
    }

    private fun activeHolder() = SessionHolder().apply { update(holderSession("first-user")) }

    @Test
    fun aPinOwnedByAnotherAccountCannotReceiveTheCurrentAccountsSignedMediaUrl() = runTest(dispatcher) {
        val holder = activeHolder()
        val oldOwner = SessionHolder().apply {
            update(holderSession("other-account").copy(server = "https://old.example"))
        }
        val settings = FakeSettingsRepository(AppSettings(
            pinnedLineUrl = "https://old-cdn.example",
            pinnedLineName = "Old account line",
            pinnedLineAccountKey = oldOwner.playbackPrefsNamespace().orEmpty(),
        ))
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(MediaSourceDto(
            SupportsDirectPlay = true,
            DirectStreamUrl = "/play/movie?api_key=current-account-token",
        ))))

        val plan = (repository(api, settings, sessionHolder = holder).plan("movie-1") as NovumResult.Success).value
        assertEquals("https://media.example/play/movie?api_key=current-account-token", plan.url)
    }

    @Test
    fun aPinOwnedByTheCurrentAccountStillSelectsItsCdn() = runTest(dispatcher) {
        val holder = activeHolder()
        val settings = FakeSettingsRepository(AppSettings(
            pinnedLineUrl = "https://current-cdn.example",
            pinnedLineAccountKey = holder.playbackPrefsNamespace().orEmpty(),
        ))
        val api = StubPlaybackApi(info = PlaybackInfoDto(MediaSources = listOf(MediaSourceDto(
            SupportsDirectPlay = true, DirectStreamUrl = "/play/movie?api_key=current-account-token",
        ))))

        val plan = (repository(api, settings, sessionHolder = holder).plan("movie-1") as NovumResult.Success).value
        assertEquals("https://current-cdn.example/play/movie?api_key=current-account-token", plan.url)
    }

    private fun holderSession(user: String) = Session("https://media.example", "$user-token", user, user, "Fixture", "device")

    private fun reportingApi(calls: MutableList<String>) = object : EmbyApiStub() {
        override suspend fun reportPlaybackStarted(body: PlaybackProgressDto) { calls += "started" }
        override suspend fun reportPlaybackProgress(body: PlaybackProgressDto) { calls += "progress" }
        override suspend fun reportPlaybackStopped(body: PlaybackProgressDto) { calls += "stopped" }
    }

    @Test
    fun fallbackEncodesItsSameOriginSessionToken() = runTest(dispatcher) {
        val plan = (repository(
            StubPlaybackApi(error = IOException("Fixture request failed")),
            token = "fixture+token&value",
        ).plan("movie-1") as NovumResult.Success).value

        assertEquals(
            "https://smart.uhdnow.com/Videos/movie-1/stream?Static=true&api_key=fixture%2Btoken%26value",
            plan.url,
        )
    }

    private fun repository(
        api: EmbyApi,
        settings: SettingsRepository = FakeSettingsRepository(),
        capability: FakeCapabilityProvider = FakeCapabilityProvider(),
        server: String = "https://smart.uhdnow.com",
        token: String = "token",
        sessionHolder: SessionHolder? = null,
        dispatcherProvider: DispatcherProvider = dispatchers,
    ): DefaultPlaybackRepository {
        val holder = sessionHolder ?: SessionHolder().apply {
            update(
                Session(
                    server = server,
                    token = token,
                    userId = "uid-1",
                    userName = "chic",
                    serverName = "UHD",
                    deviceId = "device-1",
                ),
            )
        }
        val provider = FixedPlaybackProvider(api)
        return DefaultPlaybackRepository(
            clients = provider,
            sessionHolder = holder,
            settings = settings,
            capability = capability,
            dispatchers = dispatcherProvider,
            logger = NoopLogger,
            lineProbe = LevelProbeForTest(provider, holder),
            strings = FakeMediaStrings,
        )
    }
}

/** 固定 API 提供者。 */
private class FixedPlaybackProvider(private val api: EmbyApi) : EmbyApiProvider {
    override fun api(baseUrl: String): EmbyApi = api
}

/** 可配置返回值的播放信息桩。 */
private class StubPlaybackApi(
    private val info: PlaybackInfoDto = PlaybackInfoDto(),
    private val error: Throwable? = null,
) : EmbyApiStub() {

    val requests = mutableListOf<PlaybackInfoRequestDto>()

    override suspend fun playbackInfo(
        itemId: String,
        userId: String,
        body: PlaybackInfoRequestDto,
    ): PlaybackInfoDto {
        requests += body
        error?.let { throw it }
        return info
    }
}

/** 测试用静默日志。 */
private object NoopLogger : NovumLogger {
    override val isEnabled: Boolean = false
    override fun d(tag: String, message: String) = Unit
    override fun w(tag: String, message: String, throwable: Throwable?) = Unit
    override fun e(tag: String, message: String, throwable: Throwable?) = Unit
}

/**
 * 测试用线路探测：桩 API 的 serverDomains 会抛异常，LineProbe 直接退化为单线路，
 * 不发起任何真实网络测速。
 */
private fun LevelProbeForTest(
    provider: com.novum.tv.data.remote.emby.EmbyApiProvider,
    holder: SessionHolder,
) = com.novum.tv.data.line.LineProbe(provider, holder, NoopLogger)
