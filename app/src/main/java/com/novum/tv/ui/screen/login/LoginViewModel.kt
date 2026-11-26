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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 登录页状态。
 *
 * @param serverInput 服务器协议、地址与端口输入。
 * @param username 用户名输入。
 * @param password 密码输入。
 * @param loading 是否正在登录。
 * @param networkError 服务端/网络错误，来自仓储层的领域错误。
 * @param missingField 本地校验失败的字段。
 * @param success 是否登录成功（用于触发导航）。
 */
data class LoginUiState(
    val serverInput: LoginServerInput = LoginServerInput(),
    val username: String = "",
    val password: String = "",
    /** 是否记住凭证（安全保存 token 与密码）；未保存选择时默认开启。 */
    val remember: Boolean = true,
    val loading: Boolean = false,
    val networkError: NovumError? = null,
    val missingField: LoginField? = null,
    val success: Boolean = false,
) {
    val server: String get() = serverInput.withAddress(serverInput.address).url

    /** 本地必填校验字段。 */
    enum class LoginField { Server, Port, Username, Password }
}

/**
 * 登录页 ViewModel。
 *
 * 职责：表单状态持有、必填校验、调用 [AuthRepository] 并把结果映射为 UI 状态。
 * 三段服务器输入在本地组合和校验；实际连接与错误分类在仓储层完成。
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

    // Saved credentials must stay with their original server and account until explicitly edited.
    private data class CredentialIdentity(val server: String, val username: String)
    private var prefilledPasswordIdentity: CredentialIdentity? = null

    init {
        // 预填上次登录的服务器地址：电视上用遥控器重输域名成本很高
        viewModelScope.launch {
            val lastServer = runCatchingSuspend { authRepository.lastServer() }.getOrDefault("")
            val lastUser = runCatchingSuspend { authRepository.lastUsername() }.getOrDefault("")
            val lastPassword = runCatchingSuspend { authRepository.lastPassword() }.getOrNull()
            val rememberCredentials = runCatchingSuspend {
                authRepository.shouldRememberCredentials()
            }.getOrDefault(true)
            val savedServerInput = LoginServerInput().withAddress(lastServer)
            val canPrefillPassword = !serverEdited && !usernameEdited && !passwordEdited
            _state.update { current ->
                // Prefill only untouched fields. An explicitly cleared field is still edited;
                // credentials saved for the old identity must not follow a newly entered server.
                val remember = if (rememberEdited) current.remember else rememberCredentials
                current.copy(
                    serverInput = if (serverEdited) current.serverInput else savedServerInput,
                    username = if (serverEdited || usernameEdited) current.username else lastUser,
                    password = if (serverEdited || usernameEdited || passwordEdited) current.password
                        else if (remember) lastPassword.orEmpty() else "",
                    remember = remember,
                )
            }
            if (canPrefillPassword && _state.value.remember && !lastPassword.isNullOrEmpty()) {
                prefilledPasswordIdentity = CredentialIdentity(_state.value.server, _state.value.username)
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
        val previous = _state.value.serverInput.address
        val prefix = previous.commonPrefixWith(value).length
        val suffix = previous.drop(prefix).commonSuffixWith(value.drop(prefix)).length
        val insertedLength = value.length - prefix - suffix
        // A paste can be split immediately. Keep individual keystrokes intact until commit,
        // otherwise typing ':' would remove it before the rest of a URL/port can be entered.
        updateServerInput { if (insertedLength > 1) it.withAddress(value) else it.copy(address = value) }
    }

    /** Split a typed URL when leaving the address field or before submitting the form. */
    fun commitServerInput() {
        updateServerInput { it.withAddress(it.address) }
    }

    /** 切换协议时使用该协议的默认端口。 */
    fun onProtocolChange(value: String) {
        val protocol = value.trim().lowercase().removeSuffix("://")
        updateServerInput {
            val committed = it.withAddress(it.address)
            if (committed.protocol == protocol) committed
            else committed.copy(protocol = protocol, port = LoginServerInput.defaultPort(protocol))
        }
    }

    /** 端口单独编辑，空值与非法范围在提交时提示。 */
    fun onPortChange(value: String) {
        updateServerInput { it.withAddress(it.address).copy(port = value.trim()) }
    }

    private fun updateServerInput(transform: (LoginServerInput) -> LoginServerInput) {
        serverEdited = true
        val serverInput = transform(_state.value.serverInput)
        val server = serverInput.withAddress(serverInput.address).url
        val clearPrefilledPassword = prefilledPasswordIdentity?.let { it.server != server } == true
        if (clearPrefilledPassword) prefilledPasswordIdentity = null
        _state.update {
            it.copy(
                serverInput = serverInput,
                password = if (clearPrefilledPassword) "" else it.password,
                missingField = null,
                networkError = null,
            )
        }
    }

    /** 用户名变化。 */
    fun onUsernameChange(value: String) {
        usernameEdited = true
        val clearPrefilledPassword = prefilledPasswordIdentity?.let { it.username != value } == true
        if (clearPrefilledPassword) prefilledPasswordIdentity = null
        _state.update {
            it.copy(
                username = value,
                password = if (clearPrefilledPassword) "" else it.password,
                missingField = null,
                networkError = null,
            )
        }
    }

    /** 密码变化。 */
    fun onPasswordChange(value: String) {
        passwordEdited = true
        // Once edited explicitly, the password belongs to the user's current form input.
        prefilledPasswordIdentity = null
        _state.update { it.copy(password = value, missingField = null, networkError = null) }
    }

    /** 提交登录。 */
    fun submit() {
        if (_state.value.loading) return
        commitServerInput()
        val current = _state.value
        val port = current.serverInput.port.toIntOrNull()
        val missing = when {
            current.serverInput.address.isBlank() -> LoginUiState.LoginField.Server
            !current.serverInput.port.all { it in '0'..'9' } ||
                port == null || port !in 1..65535 -> LoginUiState.LoginField.Port
            current.server.toHttpUrlOrNull()?.let {
                it.username.isEmpty() && it.password.isEmpty()
            } != true -> LoginUiState.LoginField.Server
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
