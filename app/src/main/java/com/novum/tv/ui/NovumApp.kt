package com.novum.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumDimens
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novum.tv.navigation.HomeRoute
import com.novum.tv.navigation.LoginRoute
import com.novum.tv.navigation.NovumNavHost
import com.novum.tv.ui.component.LocalNovumToast
import com.novum.tv.ui.component.NovumFocusScrollTuning
import com.novum.tv.ui.component.NovumToastHost
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme

/**
 * 应用根 Composable：主题 + 入口分流 + 导航图 + 全局 Toast 层。
 *
 * 入口分流由 [AppEntryViewModel] 决定：本地有会话直接进首页，否则进登录页。
 *
 * Toast 层挂在 [NovumNavHost] **之外**：
 * - 高于所有页面内容（含播放器控制条与抽屉）；
 * - 低于 `Dialog` 独立窗口（播放退出确认框）与系统弹窗；
 * - 容器不参与焦点，遥控器导航完全不受影响。
 *
 * 焦点滚动死区也挂在根部：它只调整"焦点滚动多少距离"，
 * 对业务无感，但能消掉横移卡片时按下瞬间的上下抖动。
 */
@Composable
fun NovumApp(entryViewModel: AppEntryViewModel = hiltViewModel()) {
    val entry by entryViewModel.entry.collectAsStateWithLifecycle()

    NovumTheme {
        NovumFocusScrollTuning {
            val toastViewModel: NovumToastViewModel = hiltViewModel()
            Box(modifier = Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalNovumToast provides toastViewModel.controller) {
                    when (entry) {
                        AppEntryViewModel.Entry.Loading -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NovumPalette.Ink),
                        )

                        AppEntryViewModel.Entry.LoggedIn ->
                            NovumNavHost(startDestination = HomeRoute)

                        AppEntryViewModel.Entry.LoggedOut ->
                            NovumNavHost(startDestination = LoginRoute)
                    }
                }

                NovumToastHost(
                    controller = toastViewModel.controller,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 在底部安全区上沿再抬高 48dp。
                        .padding(bottom = NovumDimens.SafeVertical + 48.dp),
                )
            }
        }
    }
}
