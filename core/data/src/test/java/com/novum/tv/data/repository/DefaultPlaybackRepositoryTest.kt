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
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

        val plan = (repository(api, capability = noDv).plan("movie-1") as NovumResult.Success).value

        assertEquals("应发起两次 PlaybackInfo（第二次强制转码）", 2, api.bodies.size)
        assertTrue(
            "第二次请求不能声明任何直连能力",
            api.bodies[1].DeviceProfile.DirectPlayProfiles.isEmpty(),
        )
        assertEquals(PlayMethod.Transcode, plan.method)
        assertEquals("https://smart.uhdnow.com/videos/dv/master.m3u8?api_key=abc", plan.url)
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

    private fun repository(
        api: EmbyApi,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        capability: FakeCapabilityProvider = FakeCapabilityProvider(),
    ): DefaultPlaybackRepository {
        val holder = SessionHolder().apply {
            update(
                Session(
                    server = "https://smart.uhdnow.com",
                    token = "token",
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
            dispatchers = dispatchers,
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

    override suspend fun playbackInfo(
        itemId: String,
        userId: String,
        body: PlaybackInfoRequestDto,
    ): PlaybackInfoDto {
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
