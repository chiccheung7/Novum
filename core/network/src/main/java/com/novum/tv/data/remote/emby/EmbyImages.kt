package com.novum.tv.data.remote.emby

/**
 * Emby 图片地址构造。
 *
 * 图片路径为 Items/{id}/Images/{type}；maxWidth 指定请求宽度；tag 用作图片版本号以支持缓存失效。
 * 图片请求固定使用 DEFAULT_MAX_WIDTH，服务端未执行缩放时仍可正常返回原图。
 */
object EmbyImages {

    /** 主图/横图统一请求宽度：卡片显示宽 402px 的超采样余量。 */
    private const val DEFAULT_MAX_WIDTH = 540

    /**
     * 主图（海报 / 缩略图）。
     *
     * @param server 服务器地址，不含尾部斜杠。
     * @param itemId 条目 id。
     * @param tag `ImageTags["Primary"]`，可为空。
     * @param maxWidth 服务端缩放宽度（恒 [DEFAULT_MAX_WIDTH]，服务端失效时回原图）。
     */
    fun primary(
        server: String,
        itemId: String,
        tag: String?,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
    ): String = build(server, itemId, "Primary", tag, maxWidth)

    /**
     * 横版缩略图（Thumb），用于 16:9 卡片行。
     * 并非所有影片都有 Thumb；调用方应检查 ImageTags，缺失时回落到 [primary]。
     *
     * @param server 服务器地址，不含尾部斜杠。
     * @param itemId 条目 id。
     * @param tag 图片版本号，可为空。
     * @param maxWidth 请求宽度。
     */
    fun thumb(
        server: String,
        itemId: String,
        tag: String?,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
    ): String = build(server, itemId, "Thumb", tag, maxWidth)

    /**
     * 背景图，用于详情页大图与 Hero。
     *
     * @param server 服务器地址。
     * @param itemId 条目 id。
     * @param tag 图片版本号，可为空。
     * @param width 目标宽度，默认 1920。
     */
    fun backdrop(
        server: String,
        itemId: String,
        tag: String?,
        width: Int = 1920,
    ): String = build(server, itemId, "Backdrop", tag, width)

    /**
     * 透明底标题图（ClearLogo）。
     *
     * 不是每部影片都有：调用方必须先判断 `ImageTags["Logo"]` 是否存在，
     * 缺失时回落到文本标题，不能假设一定拿得到。
     *
     * @param server 服务器地址。
     * @param itemId 条目 id。
     * @param tag `ImageTags["Logo"]`。
     * @param maxHeight 目标高度，默认 240，供大屏显示使用。
     */
    fun logo(
        server: String,
        itemId: String,
        tag: String?,
        maxHeight: Int = 240,
    ): String {
        val base = server.trimEnd('/')
        val builder = StringBuilder("$base/Items/$itemId/Images/Logo?maxHeight=$maxHeight")
        if (!tag.isNullOrBlank()) builder.append("&tag=").append(tag)
        return builder.toString()
    }

    private fun build(server: String, itemId: String, type: String, tag: String?, maxWidth: Int): String {
        val base = server.trimEnd('/')
        val builder = StringBuilder("$base/Items/$itemId/Images/$type?maxWidth=$maxWidth")
        if (!tag.isNullOrBlank()) builder.append("&tag=").append(tag)
        return builder.toString()
    }
}
