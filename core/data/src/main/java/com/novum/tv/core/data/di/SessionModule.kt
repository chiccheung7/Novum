package com.novum.tv.core.data.di

import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.repository.DefaultAuthRepository
import com.novum.tv.data.repository.DefaultMediaRepository
import com.novum.tv.data.repository.DefaultPlaybackRepository
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PlaybackRepository
import com.novum.tv.data.session.DataStoreSessionStore
import com.novum.tv.data.session.AndroidKeyStoreSessionSecretCipher
import com.novum.tv.data.session.SessionSecretCipher
import com.novum.tv.data.session.SessionStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
/** 会话与认证仓储绑定。 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {

    @Binds
    @Singleton
    abstract fun bindSessionSecretCipher(cipher: AndroidKeyStoreSessionSecretCipher): SessionSecretCipher

    /**
     * 会话存储实现绑定。
     *
     * @param store DataStore 实现。
     */
    @Binds
    @Singleton
    abstract fun bindSessionStore(store: DataStoreSessionStore): SessionStore

    /**
     * 登录仓储实现绑定。
     *
     * @param repository 默认实现。
     */
    @Binds
    @Singleton
    abstract fun bindAuthRepository(repository: DefaultAuthRepository): AuthRepository

    /**
     * 媒体库仓储实现绑定。
     *
     * @param repository 默认实现。
     */
    @Binds
    @Singleton
    abstract fun bindMediaRepository(repository: DefaultMediaRepository): MediaRepository

    /**
     * 播放仓储实现绑定。
     *
     * @param repository 默认实现。
     */
    @Binds
    @Singleton
    abstract fun bindPlaybackRepository(repository: DefaultPlaybackRepository): PlaybackRepository
}
