package com.novum.tv.ui.screen.home

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Waits for the selected backdrop to settle before counting its visible interval. */
internal suspend fun awaitHeroRotationInterval(
    settledBackdropUrl: Flow<String?>,
    currentBackdropUrl: String?,
    intervalMs: Long,
) {
    if (!currentBackdropUrl.isNullOrBlank()) {
        settledBackdropUrl.first { it == currentBackdropUrl }
    }
    delay(intervalMs)
}
