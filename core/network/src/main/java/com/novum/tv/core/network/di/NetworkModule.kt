package com.novum.tv.core.network.di

import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.EmbyClientFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * 网络与会话装配。
 *
 * JSON 配置说明：
 * - `ignoreUnknownKeys`：Emby 不同版本字段增减频繁，必须忽略未知字段；
 * - `explicitNulls = false`：请求体不发送空值，避免服务端把 null 当有效参数；
 * - `coerceInputValues`：偶发的空字符串数值字段回落默认值，避免整页解析失败。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** 全局 JSON 实例，所有网络序列化共用。 */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** 把 Retrofit 工厂绑定到抽象接口，测试可替换。 */
    @Provides
    @Singleton
    fun provideEmbyApiProvider(factory: EmbyClientFactory): EmbyApiProvider = factory
}
