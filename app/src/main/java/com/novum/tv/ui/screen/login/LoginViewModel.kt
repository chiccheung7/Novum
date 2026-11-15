package com.novum.tv.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 登录页状态。
 *
 * @param server 服务器地址输入。
 * @param username 用户名输入。
 * @param password 密码输入。
 * @param loading 是否正在登录。
 * @param networkError 服务端/网络错误，来自仓储层的领域错误。
 * @param missingField 本地校验失败的字段。
 * @param success 是否登录成功（用于触发导航）。
 */
data class LoginUiState(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val networkError: NovumError? = null,
    val missingField: LoginField? = null,
    val success: Boolean = false,
) {
    /** 本地必填校验字段。 */
    enum class LoginField { Server, Username, Password }
}

/**
 * 登录页 ViewModel。
 *
 * 职责：表单状态持有、必填校验、调用 [AuthRepository] 并把结果映射为 UI 状态。
 * 地址规范化、协议探测、错误分类全部在仓储层完成，这里不碰网络细节。
 *
 * @param authRepository 登录仓储。
 */
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        // 预填上次登录的服务器地址：电视上用遥控器重输域名成本很高
        viewModelScope.launch {
            val last = runCatching { authRepository.lastServer() }.getOrDefault("")
            if (last.isNotBlank()) _state.update { it.copy(server = last) }
        }
    }

    /** 服务器地址变化。 */
    fun onServerChange(value: String) = _state.update {
        it.copy(server = value, missingField = null, networkError = null)
    }

    /** 用户名变化。 */
    fun onUsernameChange(value: String) = _state.update {
        it.copy(username = value, missingField = null, networkError = null)
    }

    /** 密码变化。 */
    fun onPasswordChange(value: String) = _state.update {
        it.copy(password = value, missingField = null, networkError = null)
    }

    /** 提交登录。 */
    fun submit() {
        val current = _state.value
        if (current.loading) return
        val missing = when {
            current.server.isBlank() -> LoginUiState.LoginField.Server
            current.username.isBlank() -> LoginUiState.LoginField.Username
            current.password.isBlank() -> LoginUiState.LoginField.Password
            else -> null
        }
        if (missing != null) {
            _state.update { it.copy(missingField = missing) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(loading = true, networkError = null) }
            when (val result = authRepository.login(current.server, current.username, current.password)) {
                is NovumResult.Success -> _state.update { it.copy(loading = false, success = true) }
                is NovumResult.Failure -> _state.update {
                    it.copy(loading = false, networkError = result.error)
                }
            }
        }
    }
}
