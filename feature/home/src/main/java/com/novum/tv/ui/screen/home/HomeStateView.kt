package com.novum.tv.ui.screen.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.core.designsystem.R
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.state.UiState
import com.novum.tv.ui.common.userMessage
import com.novum.tv.ui.component.NovumButton
import com.novum.tv.ui.component.NovumInitialFocus
import com.novum.tv.ui.component.NovumStateView
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumType

/** Revoked sessions get a remote-focusable escape; other errors retain ordinary retry behavior. */
@Composable
internal fun HomeStateView(
    state: UiState<*>,
    onRetry: () -> Unit,
    onReauthenticate: () -> Unit,
    modifier: Modifier = Modifier,
    reauthenticating: Boolean = false,
    reauthenticationError: NovumError? = null,
) {
    if (state !is UiState.Error || state.error !is NovumError.Unauthorized) {
        NovumStateView(state = state, onRetry = onRetry, modifier = modifier)
        return
    }

    val firstFocus = remember { FocusRequester() }
    if (!reauthenticating) NovumInitialFocus(firstFocus)
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NovumDimens.TextBlockGap),
    ) {
        Text(
            text = state.error.userMessage(),
            style = NovumType.Section,
            color = NovumPalette.Danger,
            textAlign = TextAlign.Center,
        )
        reauthenticationError?.let { error ->
            Text(text = error.userMessage(), style = NovumType.Body, color = NovumPalette.Danger)
        }
        NovumButton(
            onClick = onReauthenticate,
            enabled = !reauthenticating,
            modifier = Modifier.focusRequester(firstFocus),
        ) {
            Text(text = stringResource(R.string.home_reauthenticate), style = NovumType.ButtonLg)
        }
    }
}
