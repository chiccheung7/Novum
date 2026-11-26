package com.novum.tv.ui.screen.player

/** Actual displayed video rectangle, shared by subtitle positioning and letterbox calculations. */
internal data class VideoViewport(val width: Float, val height: Float)

internal fun videoViewport(width: Float, height: Float, aspect: Float?, mode: VideoScaleMode): VideoViewport {
    if (width <= 0f || height <= 0f || aspect == null || !aspect.isFinite() || aspect <= 0f || mode == VideoScaleMode.Stretch) {
        return VideoViewport(width.coerceAtLeast(0f), height.coerceAtLeast(0f))
    }
    val fitWidth = minOf(width, height * aspect)
    val fitHeight = fitWidth / aspect
    val zoom = if (mode == VideoScaleMode.Crop) maxOf(width / fitWidth, height / fitHeight) else 1f
    return VideoViewport(fitWidth * zoom, fitHeight * zoom)
}

internal fun bottomLetterboxHeight(width: Float, height: Float, aspect: Float?, mode: VideoScaleMode): Float =
    ((height - videoViewport(width, height, aspect, mode).height) / 2f).coerceAtLeast(0f)
