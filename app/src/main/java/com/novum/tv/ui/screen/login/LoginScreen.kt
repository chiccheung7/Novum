package com.novum.tv.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.novum.tv.ui.component.NovumSwitchInline
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
                .padding(vertical = 16.dp),
            // 登录页只有 3 个输入框 + 1 行操作：间距收一档，整页一屏放得下
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.login_title),
                // 页标题用 Title（与设置/搜索一致），Display 只留给 Hero
                style = NovumType.Title,
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

            // 「记住登录」与「登录」同一行左右放置：布尔项不再单独占一整行，
            // 页面在 720 高内一屏可读完，不用翻页找按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                NovumSwitchInline(
                    text = stringResource(R.string.login_remember),
                    checked = state.remember,
                    onCheckedChange = viewModel::onRememberChange,
                )
                Spacer(modifier = Modifier.weight(1f))
                NovumButton(
                    onClick = {
                        // 明文地址首次提交前显式确认
                        if (cleartext && !cleartextAcknowledged) {
                            showCleartextConfirm = true
                        } else {
                            viewModel.submit()
                        }
                    },
                    modifier = Modifier
                        .width(NovumDimens.ButtonWidth)
                        .bringIntoViewRequester(buttonBringIntoView)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                scope.launch { buttonBringIntoView.bringIntoView() }
                            }
                        },
                    centerContent = true,
                    enabled = !state.loading,
                ) {
                    Text(
                        text = if (state.loading) {
                            stringResource(R.string.login_action_loading)
                        } else {
                            stringResource(R.string.login_action)
                        },
                        style = NovumType.ButtonLg,
                        maxLines = 1,
                    )
                }
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
