package com.novum.tv.di

import com.novum.tv.BuildConfig
import com.novum.tv.core.common.feedback.ToastSink
import com.novum.tv.core.dispatcher.DefaultDispatcherProvider
import com.novum.tv.core.dispatcher.DispatcherProvider
import com.novum.tv.core.log.AndroidNovumLogger
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.data.text.MediaStrings
import com.novum.tv.ui.component.NovumToastController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 应用级单例装配。
 *
 * 只提供跨页面共享、无状态或进程级有状态的对象；页面级依赖（ViewModel）由 Hilt 自动构造，
 * 不要在这里手写 Factory。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 提供全局协程调度器。
     *
     * @return 生产环境调度器；测试通过 `@TestInstallIn` 替换。
     */
    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    /**
     * 提供全局日志门面。
     *
     * @return Debug 构建输出调试日志，Release 仅输出警告与错误。
     */
    @Provides
    @Singleton
    fun provideLogger(): NovumLogger = AndroidNovumLogger(debugEnabled = BuildConfig.DEBUG)

    /**
     * 提供全局 Toast 控制器。
     *
     * @return 应用唯一实例；[com.novum.tv.ui.NovumApp] 的宿主与各 ViewModel 共享它。
     */
    @Provides
    @Singleton
    fun provideToastController(): NovumToastController = NovumToastController()

    /**
     * 把控制器绑定成 [ToastSink]，供不依赖 Compose 的模块（feature/core）注入。
     *
     * @param controller 全局控制器实现。
     * @return 同一实例，仅收窄接口。
     */
    @Provides
    @Singleton
    fun provideToastSink(controller: NovumToastController): ToastSink = controller

    /**
     * 数据层展示文案提供者：[ResourceMediaStrings] 从 designsystem strings.xml 解析，
     * core:data 仅依赖接口。
     *
     * @param impl 资源解析实现。
     * @return 同一实例，仅收窄接口。
     */
    @Provides
    @Singleton
    fun provideMediaStrings(impl: ResourceMediaStrings): MediaStrings = impl
}
