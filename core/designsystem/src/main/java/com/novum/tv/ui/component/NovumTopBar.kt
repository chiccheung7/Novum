package com.novum.tv.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.novum.tv.core.designsystem.R
import com.novum.tv.ui.icon.NovumIcons
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette

/**
 * 顶部导航条：右上四个玻璃黑图标（收藏、分类、搜索、设置），左上角可经 [leading] 槽位注入 Hero ClearLogo。
 * 点当前页图标为 no-op；退出登录仅在设置页提供。
 *
 * @param current 当前所在页签。
 * @param onNavigateTab 页签导航回调。
 * @param modifier 外部修饰符。
 * @param leading 左上角槽位：首页传 Hero ClearLogo。
 */
@Composable
fun NovumTopBar(
    current: NovumNavTab,
    onNavigateTab: (NovumNavTab) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 最小高度：leading 放大（如 88dp ClearLogo）时行随内容长高
            .heightIn(min = NovumDimens.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
        }
        Spacer(modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(ICON_GAP)) {
            NovumTopIcon(
                icon = NovumIcons.Favorite,
                label = stringResource(R.string.top_nav_favorites),
                current = current,
                target = NovumNavTab.Favorites,
                onNavigateTab = onNavigateTab,
            )
            NovumTopIcon(
                icon = NovumIcons.Library,
                label = stringResource(R.string.library_title),
                current = current,
                target = NovumNavTab.Libraries,
                onNavigateTab = onNavigateTab,
            )
            NovumTopIcon(
                icon = NovumIcons.Search,
                label = stringResource(R.string.top_nav_search),
                current = current,
                target = NovumNavTab.Search,
                onNavigateTab = onNavigateTab,
            )
            NovumTopIcon(
                icon = NovumIcons.Settings,
                label = stringResource(R.string.top_nav_settings),
                current = current,
                target = NovumNavTab.Settings,
                onNavigateTab = onNavigateTab,
            )
        }
    }
}

/** 顶栏入口图标：玻璃黑胶囊图标钮，点当前页 no-op（语义与旧页签一致）。 */
@Composable
private fun NovumTopIcon(
    icon: ImageVector,
    label: String,
    current: NovumNavTab,
    target: NovumNavTab,
    onNavigateTab: (NovumNavTab) -> Unit,
) {
    NovumIconButton(
        icon = icon,
        contentDescription = label,
        size = TOP_ICON_SIZE,
        onClick = { if (current != target) onNavigateTab(target) },
    )
}

/** 顶栏入口图标尺寸：小于全站默认图标钮（64），但要保证 10ft 下可辨认与可聚焦。 */
private val TOP_ICON_SIZE = 56.dp

/** 顶栏入口图标间距。 */
private val ICON_GAP = 12.dp

/** 常驻页的顶部导航页签，不含播放器、详情和登录页。 */
enum class NovumNavTab {
    Home,
    Favorites,
    Libraries,
    Search,
    Settings,
}

/**
 * 无顶栏页面的统一骨架：全出血底色、安全区内容盒与返回路径。
 * 底部安全区由宿主或滚动容器二选一持有；顶部安全区由本骨架持有。
 *
 * @param contentHorizontalPadding 正文水平边距。
 * @param bottomPadding 页面级底部安全边距；滚动容器自带底部内边距时传 0.dp。
 * @param content 正文内容。
 */
@Composable
fun NovumContentScaffold(
    modifier: Modifier = Modifier,
    contentHorizontalPadding: Dp = NovumDimens.SafeHorizontal,
    bottomPadding: Dp = NovumDimens.SafeVertical,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize().background(NovumPalette.Ink)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(NovumDimens.SafeVertical))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = contentHorizontalPadding),
            ) {
                content()
            }
            if (bottomPadding > 0.dp) {
                Spacer(modifier = Modifier.height(bottomPadding))
            }
        }
    }
}
