package com.novum.tv.data.repository

import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.testing.FakeMediaStrings
import com.novum.tv.testing.NoopNovumLogger

/**
 * 测试用搜索目录：与仓储共用同一个 API 提供者/会话/调度器，
 * 避免每个用例各自拼装依赖。
 */
internal fun testSearchCatalog(
    clients: EmbyApiProvider,
    holder: SessionHolder,
    dispatchers: DispatcherProvider,
): SearchCatalogStore = SearchCatalogStore(
    clients = clients,
    sessionHolder = holder,
    strings = FakeMediaStrings,
    dispatchers = dispatchers,
    logger = NoopNovumLogger,
)
