package com.novum.tv.navigation

import kotlinx.serialization.Serializable

/**
 * 首页路由（起始页）。
 *
 * 使用 `@Serializable` 对象而非字符串：Navigation Compose 会为类型安全路由生成
 * 编译期校验的参数解析，避免手拼 `"detail/{id}"` 时漏转义或拼错。
 */
@Serializable
data object HomeRoute

/** 登录页路由（未登录时的起始页）。 */
@Serializable
data object LoginRoute

/**
 * 条目网格路由。
 *
 * @param parentId 媒体库 id。
 * @param title 页面标题。
 */
@Serializable
data class GridRoute(val parentId: String, val title: String)

/** 媒体库页（顶栏「分类」图标入口）。 */
@kotlinx.serialization.Serializable
data object LibrariesRoute

/** 收藏列表路由。 */
@Serializable
data object FavoritesRoute

/** 搜索页路由。 */
@Serializable
data object SearchRoute

/** 设置页路由。 */
@Serializable
data object SettingsRoute

/**
 * 人物作品页路由，从详情页演职员头像进入。
 *
 * @param personId 人物条目 id（Emby People[].Id）。
 * @param personName 人物名，用作页标题。
 */
@Serializable
data class PersonRoute(val personId: String, val personName: String)
