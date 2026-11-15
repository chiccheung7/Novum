package com.novum.tv.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import com.novum.tv.data.repository.PersonIndexStore
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.settings.DataStoreSettingsRepository
import com.novum.tv.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /** 提供本地人物索引（落盘文件定位在应用私有目录）。 */
    @Provides
    @Singleton
    fun providePersonIndexStore(
        @ApplicationContext context: Context,
    ): PersonIndexStore = PersonIndexStore(File(context.filesDir, "person_index.json"))

    /** 提供条目级播放偏好（手动改过的字幕/音轨/版本，按条目记忆）。 */
    @Provides
    @Singleton
    fun provideItemPlaybackPrefsStore(
        @ApplicationContext context: Context,
    ): ItemPlaybackPrefsStore = ItemPlaybackPrefsStore(File(context.filesDir, "item_playback_prefs.json"))
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
}
