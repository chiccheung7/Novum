package com.novum.tv.ui.screen.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.runCatchingSuspend
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
    /** 是否记住凭证（安全保存 token 与密码）；首次默认关。 */
    val remember: Boolean = false,
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

    private var serverEdited = false
    private var usernameEdited = false
    private var passwordEdited = false
    private var rememberEdited = false

    init {
        // 预填上次登录的服务器地址：电视上用遥控器重输域名成本很高
        viewModelScope.launch {
            val lastServer = runCatchingSuspend { authRepository.lastServer() }.getOrDefault("")
            val lastUser = runCatchingSuspend { authRepository.lastUsername() }.getOrDefault("")
            val lastPassword = runCatchingSuspend { authRepository.lastPassword() }.getOrNull()
            val rememberCredentials = runCatchingSuspend {
                authRepository.shouldRememberCredentials()
            }.getOrDefault(false)
            _state.update { current ->
                // Prefill only untouched fields. An explicitly cleared field is still edited;
                // credentials saved for the old identity must not follow a newly entered server.
                val remember = if (rememberEdited) current.remember else rememberCredentials
                current.copy(
                    server = if (serverEdited) current.server else lastServer,
                    username = if (serverEdited || usernameEdited) current.username else lastUser,
                    password = if (serverEdited || usernameEdited || passwordEdited) current.password
                        else if (remember) lastPassword.orEmpty() else "",
                    remember = remember,
                )
            }
        }
    }

    /** 切换「记住登录」。 */
    fun onRememberChange(value: Boolean) {
        rememberEdited = true
        _state.update { it.copy(remember = value) }
        // Unchecking is immediate: stored token/password are deleted while form values stay filled.
        viewModelScope.launch { runCatchingSuspend { authRepository.setRememberCredentials(value) } }
    }

    /** 服务器地址变化。 */
    fun onServerChange(value: String) {
        serverEdited = true
        _state.update { it.copy(server = value, missingField = null, networkError = null) }
    }

    /** 用户名变化。 */
    fun onUsernameChange(value: String) {
        usernameEdited = true
        _state.update { it.copy(username = value, missingField = null, networkError = null) }
    }

    /** 密码变化。 */
    fun onPasswordChange(value: String) {
        passwordEdited = true
        _state.update { it.copy(password = value, missingField = null, networkError = null) }
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
            when (val result = authRepository.login(
                rawServer = current.server,
                username = current.username,
                password = current.password,
                remember = current.remember,
            )) {
                is NovumResult.Success -> _state.update { it.copy(loading = false, success = true) }
                is NovumResult.Failure -> _state.update {
                    it.copy(loading = false, networkError = result.error)
                }
            }
        }
    }
}
