package com.novum.tv.testing

import com.novum.tv.data.remote.emby.AuthRequestDto
import com.novum.tv.data.remote.emby.AuthResultDto
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.ItemDto
import com.novum.tv.data.remote.emby.PersonDto
import com.novum.tv.data.remote.emby.PlaybackInfoDto
import com.novum.tv.data.remote.emby.PlaybackInfoRequestDto
import com.novum.tv.data.remote.emby.PlaybackProgressDto
import com.novum.tv.data.remote.emby.PublicInfoDto
import com.novum.tv.data.remote.emby.QueryResultDto
import com.novum.tv.data.remote.emby.UserDataDto

/**
 * 测试用 [EmbyApi] 桩实现：所有方法给出「空但合法」的返回值。
 *
 * 目的：接口方法较多时，测试只需覆写真正关心的那一个，
 * 避免每加一个接口就要改所有假实现，把测试维护成本压在最低。
 */
open class EmbyApiStub : EmbyApi {

    override suspend fun publicInfo(): PublicInfoDto = PublicInfoDto()

    /** 默认视为服务端未部署多线路扩展：抛出后 LineProbe 退化为单线路。 */
    override suspend fun serverDomains(): kotlinx.serialization.json.JsonElement =
        throw UnsupportedOperationException("ServerDomains 未实现")

    override suspend fun authenticate(body: AuthRequestDto): AuthResultDto = AuthResultDto()

    override suspend fun views(uid: String, fields: String): QueryResultDto<ItemDto> =
        QueryResultDto()

    override suspend fun items(
        uid: String,
        parentId: String?,
        ids: String?,
        personIds: String?,
        recursive: Boolean,
        includeItemTypes: String?,
        searchTerm: String?,
        sortBy: String,
        sortOrder: String,
        startIndex: Int,
        limit: Int,
        maxOfficialRating: String?,
        genres: String?,
        filters: String?,
        minPremiereDate: String?,
        minDateCreated: String?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto()

    override suspend fun resume(uid: String, limit: Int, mediaTypes: String, fields: String) =
        QueryResultDto<ItemDto>()

    override suspend fun nextUp(uid: String, limit: Int, fields: String, seriesId: String?) = QueryResultDto<ItemDto>()

    override suspend fun latest(uid: String, parentId: String, limit: Int, fields: String) =
        emptyList<ItemDto>()

    override suspend fun detail(uid: String, itemId: String, fields: String): ItemDto = ItemDto()

    override suspend fun persons(
        userId: String?,
        searchTerm: String?,
        limit: Int,
        fields: String,
    ): QueryResultDto<PersonDto> = QueryResultDto()

    override suspend fun seasons(
        seriesId: String,
        userId: String,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto()

    override suspend fun episodes(
        seriesId: String,
        userId: String,
        seasonId: String?,
        fields: String,
    ): QueryResultDto<ItemDto> = QueryResultDto()

    override suspend fun addFavorite(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun removeFavorite(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun markPlayed(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun markUnplayed(uid: String, itemId: String): UserDataDto = UserDataDto()

    override suspend fun playbackInfo(
        itemId: String,
        userId: String,
        body: PlaybackInfoRequestDto,
    ): PlaybackInfoDto = PlaybackInfoDto()

    override suspend fun reportPlaybackStarted(body: PlaybackProgressDto) = Unit

    override suspend fun reportPlaybackProgress(body: PlaybackProgressDto) = Unit

    override suspend fun reportPlaybackStopped(body: PlaybackProgressDto) = Unit
}
