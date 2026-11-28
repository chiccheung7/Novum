package com.novum.tv.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.error.NovumError

/**
 * 把领域错误映射为面向用户的文案。
 *
 * 放在 UI 层而不是错误类里：文案属于表现层，方便统一口径与后续多语言。
 *
 * @return 可直接展示的字符串。
 */
@Composable
fun NovumError.userMessage(): String = when (this) {
    is NovumError.Network -> stringResource(R.string.error_network)
    is NovumError.Unauthorized -> stringResource(R.string.error_unauthorized)
    is NovumError.Server -> stringResource(R.string.error_server, code)
    is NovumError.PlaybackUnavailable -> stringResource(when (reason) {
        NovumError.PlaybackFailure.SourceUnavailable -> R.string.error_playback_source_unavailable
        NovumError.PlaybackFailure.SubtitleUnavailable -> R.string.error_playback_subtitle_unavailable
        NovumError.PlaybackFailure.SeekUnavailable -> R.string.error_playback_seek_unavailable
    })
    is NovumError.Parse -> stringResource(R.string.error_parse)
    is NovumError.NotFound -> stringResource(R.string.error_not_found)
    is NovumError.Unknown -> stringResource(R.string.error_unknown)
}
