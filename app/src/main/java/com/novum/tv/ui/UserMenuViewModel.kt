package com.novum.tv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.common.feedback.ToastSink
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.log.NovumLogger
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.session.SessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val toast: ToastSink,
    private val logger: NovumLogger,
    @ApplicationContext private val appContext: Context,
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
     * 退出登录：只有会话真正清除后才导航，失败则停留在当前页提示重试。
     *
     * @param onDone 会话清理成功后的导航回调。
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { authRepository.logout() }
                .onFailure {
                    logger.w(TAG, "退出登录清理失败", it)
                    toast.show(appContext.getString(R.string.settings_logout_failed))
                }
                .onSuccess { onDone() }
        }
    }
}

/** 退出登录日志标签。 */
private const val TAG = "UserMenuVM"
