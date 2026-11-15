package com.novum.tv.core.data.di

import com.novum.tv.core.capability.AndroidCapabilityProvider
import com.novum.tv.core.capability.CapabilityProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 设备能力装配。
 *
 * 接口与实现分离：测试注入固定能力，换设备只换实现。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CapabilityModule {

    /**
     * 绑定 Android 系统能力探测实现。
     *
     * @param impl 基于系统 API 的实现。
     */
    @Binds
    @Singleton
    abstract fun bindCapabilityProvider(impl: AndroidCapabilityProvider): CapabilityProvider
}
