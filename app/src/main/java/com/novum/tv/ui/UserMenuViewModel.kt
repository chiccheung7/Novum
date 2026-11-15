package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 用户菜单 ViewModel：统一提供会话用户名与退出登录动作。
 * 导航回调由 [com.novum.tv.navigation.NovumNavHost] 接管，清理会话后返回登录页。
 */
@HiltViewModel
class UserMenuViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    sessionStore: SessionStore,
    private val logger: NovumLogger,
) : ViewModel() {

    private val _userName = MutableStateFlow<String?>(null)

    /** 当前登录用户名；无会话或用户名为空时为 null（菜单标题行隐藏）。 */
    val userName: StateFlow<String?> = _userName.asStateFlow()

    init {
        viewModelScope.launch {
            val session = runCatching { sessionStore.load() }.getOrNull() ?: return@launch
            _userName.value = session.userName.takeIf { it.isNotBlank() }
        }
    }

    /**
     * 退出登录：清除本地会话后回调，由调用方导航回登录页。
     *
     * @param onDone 会话清理完成后的导航回调。
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            // 退出清理失败时记录日志，导航仍继续。
            runCatching { authRepository.logout() }
                .onFailure { logger.w(TAG, "退出登录清理失败", it) }
            onDone()
        }
    }
}

/** 退出登录日志标签。 */
private const val TAG = "UserMenuVM"
