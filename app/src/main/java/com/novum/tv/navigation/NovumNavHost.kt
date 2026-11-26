package com.novum.tv.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.novum.tv.ui.component.NovumNavTab
import com.novum.tv.ui.screen.detail.DetailScreen
import com.novum.tv.ui.screen.favorites.FavoritesScreen
import com.novum.tv.ui.screen.grid.GridScreen
import com.novum.tv.ui.screen.home.HomeScreen
import com.novum.tv.ui.screen.libraries.LibrariesScreen
import com.novum.tv.ui.screen.login.LoginScreen
import com.novum.tv.ui.screen.person.PersonScreen
import com.novum.tv.ui.screen.player.PlayerScreen
import com.novum.tv.ui.screen.search.SearchScreen
import com.novum.tv.ui.screen.settings.SettingsScreen

/**
 * 应用的唯一导航图。
 *
 * 路由约定：
 * - 页面通过 lambda 回调通信，不把 NavController 传进 Screen。
 * - 详情页返回直接回首页，弹窗由页面自行处理 BackHandler 优先级。
 * - 页签跳转使用 popUpTo(HomeRoute)+launchSingleTop；当前页签点击由组件守卫为 no-op。
 *
 * 详情目的地采用缩放淡入：进入时 0.94 → 1，返回时对称缩小淡出；其他目的地使用默认淡入。
 * 缩放仅作用于绘制，不改变布局和焦点，时长由 DETAIL_TRANSITION_MS 控制。
 *
 * @param navController 可注入的导航控制器，测试时可替换。
 * @param userMenuViewModel 提供会话用户名与退出登录动作。
 * @param modifier 作用于 NavHost 的修饰符。
 */
@Composable
fun NovumNavHost(
    modifier: Modifier = Modifier,
    startDestination: Any = HomeRoute,
    navController: NavHostController = rememberNavController(),
) {
    val navigateTab: (NovumNavTab) -> Unit = { tab ->
        val route: Any = when (tab) {
            NovumNavTab.Home -> HomeRoute
            NovumNavTab.Favorites -> FavoritesRoute
            NovumNavTab.Libraries -> LibrariesRoute
            NovumNavTab.Search -> SearchRoute
            NovumNavTab.Settings -> SettingsRoute
        }
        navController.navigate(route) {
            // 常驻页同层切换：回退栈只保留首页，按返回键总能一步步回到首页
            popUpTo(HomeRoute)
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<LoginRoute> {
            val loginDestinationId = navController.currentBackStackEntry?.destination?.id
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(HomeRoute) {
                        // 登录成功后把登录页从回退栈移除，避免返回键回到登录
                        if (loginDestinationId != null) {
                            popUpTo(loginDestinationId) { inclusive = true }
                        }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<HomeRoute>(
            enterTransition = { tabEnterTransition(this) },
            popEnterTransition = { tabEnterTransition(this) },
            popExitTransition = { tabExitTransition(this) },
        ) {
            HomeScreen(
                onOpenDetail = { itemId -> navController.navigate(DetailRoute(itemId)) },
                // 继续观看行剧集卡：直达详情页的集级语境。
                onOpenSeriesResume = { seriesId ->
                    navController.navigate(DetailRoute(seriesId, focusCurrentEpisode = true))
                },
                // Hero「播放」CTA：电影直接起播（无进度 → 从头播）
                onPlay = { itemId -> navController.navigate(PlayerRoute(itemId)) },
                onNavigateTab = navigateTab,
                onOpenLibrary = { id, name -> navController.navigate(GridRoute(id, name)) },
                onReauthenticate = {
                    navController.navigate(LoginRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<LibrariesRoute>(
            enterTransition = { tabEnterTransition(this) },
            popEnterTransition = { tabEnterTransition(this) },
            popExitTransition = { tabExitTransition(this) },
        ) {
            LibrariesScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenLibrary = { id, name -> navController.navigate(GridRoute(id, name)) },
            )
        }
        // 详情页进入时缩放淡入，返回时对称缩小淡出；其余方向使用默认淡入淡出。
        composable<DetailRoute>(
            enterTransition = {
                fadeIn(tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing)) +
                    scaleIn(
                        initialScale = DETAIL_ENTER_SCALE,
                        animationSpec = tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing),
                    )
            },
            popExitTransition = {
                fadeOut(tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing)) +
                    scaleOut(
                        targetScale = DETAIL_ENTER_SCALE,
                        animationSpec = tween(DETAIL_TRANSITION_MS, easing = FastOutSlowInEasing),
                    )
            },
        ) {
            DetailScreen(
                onBack = { navigateTab(NovumNavTab.Home) },
                onPlay = { itemId, startPositionMs, mediaSourceId ->
                    navController.navigate(PlayerRoute(itemId, startPositionMs, mediaSourceId))
                },
                // 演职员头像：人物作品页
                onOpenPerson = { personId, personName ->
                    navController.navigate(PersonRoute(personId, personName))
                },
            )
        }
        composable<PersonRoute> { backStackEntry ->
            val route: PersonRoute = backStackEntry.toRoute()
            PersonScreen(
                personName = route.personName,
                onOpenDetail = { itemId -> navController.navigate(DetailRoute(itemId)) },
                onBack = { navController.popBackStack() },
            )
        }
        composable<PlayerRoute> {
            PlayerScreen(
                onBack = { navController.popBackStack() },
                onOpenEpisode = { itemId, mediaSourceId, _ ->
                    // 上一集/下一集/抽屉选集：替换当前播放页而不是叠加——否则退出
                    // 播放要按 N 次返回才能回详情页（每次 Back 都回到上一集的播放页）。
                    // mediaSourceId 非空 = 抽屉「每次询问」里选定的本次播放版本；
                    // finished 只给播放页收尾上报用（是否已看完），导航侧无关
                    val currentPlayerId = navController.currentBackStackEntry?.destination?.id
                    navController.navigate(PlayerRoute(itemId, mediaSourceId = mediaSourceId)) {
                        if (currentPlayerId != null) {
                            popUpTo(currentPlayerId) { inclusive = true }
                        }
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<FavoritesRoute>(
            enterTransition = { tabEnterTransition(this) },
            popEnterTransition = { tabEnterTransition(this) },
            popExitTransition = { tabExitTransition(this) },
        ) {
            FavoritesScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenDetail = { itemId -> navController.navigate(DetailRoute(itemId)) },
            )
        }
        composable<SearchRoute>(
            enterTransition = { tabEnterTransition(this) },
            popEnterTransition = { tabEnterTransition(this) },
            popExitTransition = { tabExitTransition(this) },
        ) {
            SearchScreen(
                modifier = Modifier.fillMaxSize(),
                onOpenDetail = { itemId -> navController.navigate(DetailRoute(itemId)) },
            )
        }
        composable<GridRoute> { backStackEntry ->
            val route: GridRoute = backStackEntry.toRoute()
            GridScreen(
                libraryId = route.parentId,
                title = route.title,
                onOpenDetail = { itemId -> navController.navigate(DetailRoute(itemId)) },
            )
        }
        composable<SettingsRoute>(
            enterTransition = { tabEnterTransition(this) },
            popEnterTransition = { tabEnterTransition(this) },
            popExitTransition = { tabExitTransition(this) },
        ) {
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    // 退出后清空回退栈，避免返回键回到已失效的登录态页面
                    navController.navigate(LoginRoute) {
                        popUpTo(0) { inclusive = true }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}

/** 详情页缩放淡入转场时长（毫秒）。 */
private const val DETAIL_TRANSITION_MS = 300

/** 详情页进场起始缩放（0.94 → 1：轻微放大，克制不炫技）。 */
private const val DETAIL_ENTER_SCALE = 0.94f

/** 页签切换轻滑移转场时长（毫秒）。 */
private const val TAB_TRANSITION_MS = 300

/** 页签轻滑移幅度（视口宽的 6%：方向可感但不做全宽横扫——双整页同帧渲染成本可控）。 */
private const val TAB_SLIDE_RATIO = 0.06f

/** 页签序（转场方向基准）：顶栏从左到右。 */
private fun NavDestination.tabIndex(): Int? {
    // 类型安全路由的 destination.route = 路由类的限定名（容错：以简单名结尾亦认）
    val route = route ?: return null
    val classes = listOf(
        HomeRoute::class,
        FavoritesRoute::class,
        LibrariesRoute::class,
        SearchRoute::class,
        SettingsRoute::class,
    )
    return classes.indexOfFirst { cls ->
        route == cls.qualifiedName || route.endsWith("." + cls.simpleName)
    }.takeIf { it >= 0 }
}

/** 页签切换方向（+1 = 往页签序右侧去 / -1 = 往左）；任一端不是页签目的地为 null
 *  （转场回退 NavHost 默认淡入淡出——详情/播放器等进出不受影响）。 */
private fun tabSlideDirection(
    initial: NavBackStackEntry,
    target: NavBackStackEntry,
): Int? {
    val from = initial.destination.tabIndex() ?: return null
    val to = target.destination.tabIndex() ?: return null
    return when {
        to > from -> 1
        to < from -> -1
        else -> null
    }
}

/** 页签进场的方向感知轻滑移（淡入 + 6% 滑入）。 */
private fun tabEnterTransition(
    scope: AnimatedContentTransitionScope<NavBackStackEntry>,
): EnterTransition? {
    val dir = tabSlideDirection(scope.initialState, scope.targetState) ?: return null
    val fade = tween<Float>(TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    val slide = tween<IntOffset>(TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    return fadeIn(fade) + slideInHorizontally(slide) { full -> (full * TAB_SLIDE_RATIO * dir).toInt() }
}

/** 页签离场的方向感知轻滑移（淡出 + 反向 6% 滑出）。 */
private fun tabExitTransition(
    scope: AnimatedContentTransitionScope<NavBackStackEntry>,
): ExitTransition? {
    val dir = tabSlideDirection(scope.initialState, scope.targetState) ?: return null
    val fade = tween<Float>(TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    val slide = tween<IntOffset>(TAB_TRANSITION_MS, easing = FastOutSlowInEasing)
    return fadeOut(fade) + slideOutHorizontally(slide) { full -> (full * TAB_SLIDE_RATIO * -dir).toInt() }
}
