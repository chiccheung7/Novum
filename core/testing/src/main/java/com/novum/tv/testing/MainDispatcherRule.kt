package com.novum.tv.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把 `Dispatchers.Main` 替换为测试调度器的 JUnit 规则。
 *
 * ViewModel 内部使用 `viewModelScope`（绑定 Main），不替换会导致单测直接抛
 * "Module with the Main dispatcher had failed to initialize"。
 *
 * @param dispatcher 测试调度器，默认 Unconfined（立即执行，便于断言）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
