package com.novum.tv.testing

import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.DanmuRepository
import com.novum.tv.model.DanmuAnime
import com.novum.tv.model.DanmuComment
import com.novum.tv.model.DanmuEpisode
import com.novum.tv.model.DanmuMatch

/**
 * 弹幕仓储的测试替身：全部返回空数据（弹幕功能未启用的最小合法实现）。
 *
 * 播放页 ViewModel 测试只关心弹幕不影响播放主链路；需要验证弹幕交互时
 * 再写带行为的假实现。
 */
object NoopDanmuRepository : DanmuRepository {

    override suspend fun match(fileName: String): NovumResult<DanmuMatch?> = NovumResult.Success(null)

    override suspend fun searchAnime(keyword: String): NovumResult<List<DanmuAnime>> =
        NovumResult.Success(emptyList())

    override suspend fun episodes(bangumiId: String): NovumResult<List<DanmuEpisode>> =
        NovumResult.Success(emptyList())

    override suspend fun comments(episodeId: String): NovumResult<List<DanmuComment>> =
        NovumResult.Success(emptyList())
}
