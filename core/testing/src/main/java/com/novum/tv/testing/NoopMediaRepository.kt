package com.novum.tv.testing

import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.Episode
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.MediaLibrary
import com.novum.tv.model.MediaPage
import com.novum.tv.model.Season

/** Empty offline browser data for tests that only override a single repository operation. */
object NoopMediaRepository : MediaRepository {
    override suspend fun libraries(): NovumResult<List<MediaLibrary>> = NovumResult.Success(emptyList())

    override suspend fun items(
        parentId: String?, startIndex: Int, limit: Int, sortBy: String, ascending: Boolean,
        filters: String?, searchTerm: String?, includeItemTypes: String?,
    ): NovumResult<MediaPage> = NovumResult.Success(MediaPage(emptyList(), 0))

    override suspend fun detail(itemId: String): NovumResult<MediaDetail> =
        NovumResult.Failure(NovumError.NotFound())

    override suspend fun seasons(seriesId: String): NovumResult<List<Season>> = NovumResult.Success(emptyList())

    override suspend fun episodes(seriesId: String, seasonId: String?): NovumResult<List<Episode>> =
        NovumResult.Success(emptyList())

    override suspend fun seriesNextUp(seriesId: String): NovumResult<Episode?> = NovumResult.Success(null)

    override suspend fun setFavorite(itemId: String, favorite: Boolean): NovumResult<Boolean> =
        NovumResult.Success(favorite)

    override suspend fun setPlayed(itemId: String, played: Boolean): NovumResult<Boolean> =
        NovumResult.Success(played)

    override suspend fun feed(): NovumResult<HomeFeed> = NovumResult.Failure(NovumError.NotFound())
}
