package com.novum.tv.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.common.userMessage
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumCheckboxRow
import com.novum.tv.ui.component.NovumSafeArea
import com.novum.tv.ui.component.NovumTextField
import kotlinx.coroutines.launch
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.novum.tv.core.network.ServerAddress
import com.novum.tv.ui.component.NovumConfirmDialog

/**
 * Emby 服务器登录页：地址、账号与密码。
 *
 * @param onLoggedIn 登录成功回调，由导航层切到首页。
 * @param modifier 外部修饰符。
 * @param viewModel 登录 ViewModel。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val serverFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val buttonBringIntoView = remember { BringIntoViewRequester() }
    // 明文地址的显式确认：本次会话内确认过一次就不再重复打扰
    var cleartextAcknowledged by rememberSaveable { mutableStateOf(false) }
    var showCleartextConfirm by rememberSaveable { mutableStateOf(false) }
    val cleartext = ServerAddress.usesCleartext(state.server)

    // 等首帧布局完成再请求焦点：输入框在可滚动容器内，过早请求会静默失败
    LaunchedEffect(Unit) {
        withFrameNanos { }
        serverFocus.requestFocus()
    }
    LaunchedEffect(state.success) {
        if (state.success) onLoggedIn()
    }

    NovumSafeArea(modifier = modifier) {
        Column(
            modifier = Modifier
                // 顶部对齐而非垂直居中：表单项多时居中会让首项顶出屏幕且无法滚入；
                // 首尾留白给聚焦放大的按钮/输入框留呼吸位，避免被视口边缘硬裁。
                .align(Alignment.TopStart)
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(NovumDimens.TextBlockGap),
        ) {
            Text(
                text = stringResource(R.string.login_title),
                style = NovumType.Display,
                color = NovumPalette.Paper,
            )
            Text(
                text = stringResource(R.string.login_hint),
                style = NovumType.Body,

                color = NovumPalette.PaperMuted,
            )

            NovumTextField(
                value = state.server,
                onValueChange = viewModel::onServerChange,
                label = stringResource(R.string.login_server),
                placeholder = stringResource(R.string.login_server_placeholder),
                modifier = Modifier.focusRequester(serverFocus),
            )
            // 先说清楚"这条连接不加密"，再让用户按登录；提交时还有一次确认
            if (cleartext) {
                Text(
                    text = stringResource(R.string.login_cleartext_inline),
                    style = NovumType.Caption,
                    color = NovumPalette.PaperMuted,
                )
            }
            NovumTextField(
                value = state.username,
                onValueChange = viewModel::onUsernameChange,
                label = stringResource(R.string.login_username),
            )
            NovumTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(R.string.login_password),
                isPassword = true,
                imeAction = ImeAction.Done,
            )
            // 记住登录：对号框勾选保存 token 自动登录；关闭则只预填地址+用户名
            NovumCheckboxRow(
                text = stringResource(R.string.login_remember),
                checked = state.remember,
                onClick = { viewModel.onRememberChange(!state.remember) },
            )

            val errorText = when {
                state.missingField == LoginUiState.LoginField.Server ->
                    stringResource(R.string.login_error_server)
                state.missingField == LoginUiState.LoginField.Username ->
                    stringResource(R.string.login_error_username)
                state.missingField == LoginUiState.LoginField.Password ->
                    stringResource(R.string.login_error_password)
                state.networkError != null -> state.networkError!!.userMessage()
                else -> null
            }
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = NovumType.Caption,
                    color = NovumPalette.Danger,
                )
            }

            NovumButton(
                onClick = {
                    // 明文地址首次提交前显式确认
                    if (cleartext && !cleartextAcknowledged) {
                        showCleartextConfirm = true
                    } else {
                        viewModel.submit()
                    }
                },
                // 按内容自适应宽度：固定宽度 + centerContent 在「正在登录…」
                // 这类较长文案下会把文字挤到两侧被硬裁。改为不定宽，文字完整可见。
                // 首焦容器已改顶部对齐并留首尾白，按钮不再贴视口边缘；
                // bring-into-view 仅作小屏/大字体下的兜底。
                modifier = Modifier
                    .padding(top = 8.dp, bottom = 8.dp)
                    .bringIntoViewRequester(buttonBringIntoView)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            scope.launch { buttonBringIntoView.bringIntoView() }
                        }
                    },
                centerContent = false,
                enabled = !state.loading,
            ) {
                Text(
                    text = if (state.loading) {
                        stringResource(R.string.login_action_loading)
                    } else {
                        stringResource(R.string.login_action)
                    },
                    style = NovumType.ButtonLg,
                )
            }
        }
    }

    if (showCleartextConfirm) {
        NovumConfirmDialog(
            title = stringResource(R.string.login_cleartext_title),
            message = stringResource(R.string.login_cleartext_message),
            confirmText = stringResource(R.string.login_cleartext_confirm),
            cancelText = stringResource(R.string.login_cleartext_cancel),
            onConfirm = {
                showCleartextConfirm = false
                cleartextAcknowledged = true
                viewModel.submit()
            },
            onCancel = { showCleartextConfirm = false },
        )
    }
}
