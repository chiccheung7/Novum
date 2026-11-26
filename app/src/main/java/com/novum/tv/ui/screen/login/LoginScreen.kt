package com.novum.tv.ui.screen.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.common.userMessage
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumConfirmDialog
import com.novum.tv.ui.component.NovumOptionListDialog
import com.novum.tv.ui.component.NovumSafeArea
import com.novum.tv.ui.component.NovumSwitchInline
import com.novum.tv.ui.component.NovumTextField
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumShapes
import com.novum.tv.ui.theme.NovumType
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A single, centered TV login form with separately editable server components. */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val protocolFocus = remember { FocusRequester() }
    val addressFocus = remember { FocusRequester() }
    val portFocus = remember { FocusRequester() }
    val usernameFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val rememberFocus = remember { FocusRequester() }
    val loginFocus = remember { FocusRequester() }
    val loginBringIntoView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var acknowledgedServer by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmationServer by rememberSaveable { mutableStateOf<String?>(null) }
    var showProtocols by remember { mutableStateOf(false) }
    var addressWasFocused by remember { mutableStateOf(false) }

    val submit = {
        keyboard?.hide()
        // IME text commits and Done can arrive in the same frame. Read the live form.
        viewModel.commitServerInput()
        val current = viewModel.state.value
        val url = current.server.toHttpUrlOrNull()
        val complete = url != null && url.username.isEmpty() && url.password.isEmpty() &&
            current.username.isNotBlank() && current.password.isNotBlank() &&
            current.serverInput.port.toIntOrNull() in 1..65535
        if (!current.loading && complete && url.scheme == "http" &&
            acknowledgedServer != current.server
        ) {
            confirmationServer = current.server
        } else {
            viewModel.submit()
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        addressFocus.requestFocus()
    }
    LaunchedEffect(state.success) {
        if (state.success) onLoggedIn()
    }
    LaunchedEffect(state.missingField) {
        when (state.missingField) {
            LoginUiState.LoginField.Server -> addressFocus.requestFocus()
            LoginUiState.LoginField.Port -> portFocus.requestFocus()
            LoginUiState.LoginField.Username -> usernameFocus.requestFocus()
            LoginUiState.LoginField.Password -> passwordFocus.requestFocus()
            null -> Unit
        }
    }

    NovumSafeArea(modifier = modifier.imePadding()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Row(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Image(
                        painter = painterResource(com.novum.tv.R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                    )
                    Text(stringResource(R.string.login_wordmark), style = NovumType.Section, color = NovumPalette.Paper)
                }
                Text(
                    text = stringResource(R.string.login_title),
                    style = NovumType.Section,
                    color = NovumPalette.Paper,
                )
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.login_server),
                            style = NovumType.Caption,
                            color = NovumPalette.PaperMuted,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ProtocolSelector(
                                protocol = state.serverInput.protocol,
                                onClick = { keyboard?.hide(); showProtocols = true },
                                modifier = Modifier
                                    .width(132.dp)
                                    .focusRequester(protocolFocus)
                                    .fieldNavigation(down = usernameFocus, right = addressFocus),
                            )
                            NovumTextField(
                                value = state.serverInput.address,
                                onValueChange = {
                                    acknowledgedServer = null
                                    confirmationServer = null
                                    viewModel.onServerChange(it)
                                },
                                label = stringResource(R.string.login_server),
                                showLabel = false,
                                keyboardType = KeyboardType.Uri,
                                modifier = Modifier
                                    .weight(1f)
                                    .focusRequester(addressFocus)
                                    .onFocusChanged {
                                        if (addressWasFocused && !it.hasFocus) viewModel.commitServerInput()
                                        addressWasFocused = it.hasFocus
                                    }
                                    .fieldNavigation(down = usernameFocus, left = protocolFocus, right = portFocus),
                                keyboardActions = KeyboardActions(onNext = {
                                    viewModel.commitServerInput()
                                    portFocus.requestFocus()
                                }),
                            )
                            NovumTextField(
                                value = state.serverInput.port,
                                onValueChange = {
                                    acknowledgedServer = null
                                    confirmationServer = null
                                    viewModel.onPortChange(it)
                                },
                                label = stringResource(R.string.login_port),
                                showLabel = false,
                                keyboardType = KeyboardType.Number,
                                modifier = Modifier
                                    .width(112.dp)
                                    .focusRequester(portFocus)
                                    .fieldNavigation(down = usernameFocus, left = addressFocus),
                                keyboardActions = KeyboardActions(onNext = { usernameFocus.requestFocus() }),
                            )
                        }
                    }
                    NovumTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsernameChange,
                        label = stringResource(R.string.login_username),
                        modifier = Modifier.focusRequester(usernameFocus)
                            .fieldNavigation(up = addressFocus, down = passwordFocus),
                        keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    )
                    NovumTextField(
                        value = state.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = stringResource(R.string.login_password),
                        isPassword = true,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        modifier = Modifier.focusRequester(passwordFocus)
                            .fieldNavigation(up = usernameFocus, down = rememberFocus),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                }
                val errorText = when (state.missingField) {
                    LoginUiState.LoginField.Server -> stringResource(R.string.login_error_server)
                    LoginUiState.LoginField.Port -> stringResource(R.string.login_error_port)
                    LoginUiState.LoginField.Username -> stringResource(R.string.login_error_username)
                    LoginUiState.LoginField.Password -> stringResource(R.string.login_error_password)
                    null -> state.networkError?.userMessage()
                }
                if (errorText != null) {
                    Text(
                        errorText,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = NovumType.Caption,
                        color = NovumPalette.Danger,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NovumSwitchInline(
                        text = stringResource(R.string.login_remember),
                        checked = state.remember,
                        onCheckedChange = viewModel::onRememberChange,
                        focusRequester = rememberFocus,
                        labelSpacing = 16.dp,
                        modifier = Modifier.width(176.dp).fieldNavigation(up = passwordFocus),
                    )
                    NovumButton(
                        onClick = submit,
                        enabled = !state.loading,
                        centerContent = true,
                        modifier = Modifier.width(NovumDimens.ButtonWidth).focusRequester(loginFocus)
                            .fieldNavigation(up = passwordFocus, left = rememberFocus)
                            .bringIntoViewRequester(loginBringIntoView)
                            .onFocusChanged {
                                if (it.isFocused) scope.launch { loginBringIntoView.bringIntoView() }
                            },
                    ) {
                        Text(
                            stringResource(if (state.loading) R.string.login_action_loading else R.string.login_action),
                            style = NovumType.ButtonLg,
                        )
                    }
                }
            }
        }
    }

    if (showProtocols) {
        val protocols = listOf("https", "http")
        NovumOptionListDialog(
            title = stringResource(R.string.login_server),
            options = protocols.map { it.uppercase() },
            selectedIndex = protocols.indexOf(state.serverInput.protocol),
            width = 320.dp,
            onSelect = {
                acknowledgedServer = null
                confirmationServer = null
                viewModel.onProtocolChange(protocols[it])
                showProtocols = false
            },
            onDismiss = { showProtocols = false },
        )
    }
    val pendingServer = confirmationServer
    if (pendingServer != null && pendingServer == state.server) {
        NovumConfirmDialog(
            title = stringResource(R.string.login_cleartext_title),
            message = stringResource(R.string.login_cleartext_message),
            confirmText = stringResource(R.string.login_cleartext_confirm),
            cancelText = stringResource(R.string.login_cleartext_cancel),
            onConfirm = {
                confirmationServer = null
                if (viewModel.state.value.server == pendingServer) {
                    acknowledgedServer = pendingServer
                    viewModel.submit()
                }
            },
            onCancel = { confirmationServer = null },
        )
    }
}

@Composable
private fun ProtocolSelector(protocol: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(NovumShapes.Card)
            .background(NovumPalette.Surface)
            .border(
                if (focused) 2.dp else 1.dp,
                if (focused) NovumPalette.Paper else NovumPalette.Border,
                NovumShapes.Card,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(protocol.uppercase(), style = NovumType.Body, color = NovumPalette.Paper)
        Icon(
            NovumIcons.ChevronDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = NovumPalette.PaperMuted,
        )
    }
}

/** TV arrow navigation takes precedence over the text editor's cursor movement. */
private fun Modifier.fieldNavigation(
    up: FocusRequester? = null,
    down: FocusRequester? = null,
    left: FocusRequester? = null,
    right: FocusRequester? = null,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val target = when (event.key) {
        Key.DirectionUp -> up
        Key.DirectionDown -> down
        Key.DirectionLeft -> left
        Key.DirectionRight -> right
        else -> null
    }
    if (target != null) { target.requestFocus(); true } else false
}
