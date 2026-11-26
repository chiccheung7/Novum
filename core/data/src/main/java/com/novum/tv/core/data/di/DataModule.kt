package com.novum.tv.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.settings.DataStoreSettingsRepository
import com.novum.tv.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级协程作用域限定符。
 *
 * 用于「页面已经离场也必须跑完」的收尾请求——典型是播放停止上报：换集/退出会立刻
 * 销毁播放页，`viewModelScope` 随之取消，挂在它上面的网络请求会被中断，服务端就
 * 收不到本集的结束位置（表现为该集永远停在「看到一半」，继续观看/NextUp 不前进）。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * 数据层装配：设置存储与仓储绑定。
 *
 * 说明：DataStore 只创建一个实例；重复创建同一个文件会抛
 * `IllegalStateException: There are multiple DataStores active for the same file`。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    /**
     * 提供进程级 DataStore。
     *
     * @param context 应用上下文，用于定位私有目录下的设置文件。
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        produceFile = { context.preferencesDataStoreFile("novum_settings") },
    )

    /** 提供条目级播放偏好（手动改过的字幕/音轨/版本，按条目记忆）。 */
    @Provides
    @Singleton
    fun provideItemPlaybackPrefsStore(
        @ApplicationContext context: Context,
        sessionHolder: SessionHolder,
    ): ItemPlaybackPrefsStore = ItemPlaybackPrefsStore(
        storeFile = File(context.filesDir, "item_playback_prefs.json"),
        namespaceProvider = sessionHolder::playbackPrefsNamespace,
    )

    /**
     * 进程级协程作用域（见 [ApplicationScope]）。
     *
     * 只放「必须送达」的收尾请求；普通业务协程仍用 viewModelScope，随页面回收。
     */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** 仓储接口与实现的绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    /**
     * 把设置仓储实现绑定到接口，UI/ViewModel 只依赖接口。
     *
     * @param repository DataStore 实现。
     */
    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        repository: DataStoreSettingsRepository,
    ): SettingsRepository

    /**
     * 弹幕仓储绑定（UHDNOW 原生能力，非 UHD 服务器上调用会鉴权失败并静默降级）。
     *
     * @param repository 默认实现。
     */
    @Binds
    @Singleton
    abstract fun bindDanmuRepository(
        repository: com.novum.tv.data.repository.DefaultDanmuRepository,
    ): com.novum.tv.data.repository.DanmuRepository
}
