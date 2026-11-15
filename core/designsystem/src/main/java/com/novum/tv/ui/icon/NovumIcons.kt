package com.novum.tv.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Novum 线条图标集（24dp 网格，1.8dp 描边，圆头圆角）。
 *
 * 为什么不引 `material-icons-extended`：
 * - 包体多出 1.5MB 以上，且图标风格混杂（有的实心有的线性）；
 * - 电视上图标只用于控制条与动作入口，总数不超过 15 个，手写更可控。
 *
 * 约定：
 * - 全部走 24×24 viewport，描边 1.8dp，端点与拐角均为圆角，与 10-foot 界面的圆润感一致；
 * - 填充色统一为 [Color.Black]，实际颜色由 `Icon(tint = ...)` 覆盖，不要在图标内部写死品牌色；
 * - 用 `by lazy` 缓存实例：`ImageVector` 每次重建都会产生新对象，导致 `Icon` 无谓重组。
 */
object NovumIcons {

    /** 播放（实心三角）。 */
    val Play: ImageVector by lazy {
        filled("novum_play") {
            moveTo(8f, 5f)
            lineTo(19f, 12f)
            lineTo(8f, 19f)
            close()
        }
    }

    /** 暂停（两根竖条）。 */
    val Pause: ImageVector by lazy {
        filled("novum_pause") {
            moveTo(7f, 5f)
            lineTo(10.2f, 5f)
            lineTo(10.2f, 19f)
            lineTo(7f, 19f)
            close()
            moveTo(13.8f, 5f)
            lineTo(17f, 5f)
            lineTo(17f, 19f)
            lineTo(13.8f, 19f)
            close()
        }
    }

    /** 信息（圆圈 + i）。 */
    val Info: ImageVector by lazy {
        stroked("novum_info") {
            circle(12f, 12f, 9f)
            // 圆头短笔画就是一个点
            moveTo(12f, 7.6f)
            lineTo(12f, 7.7f)
            moveTo(12f, 11f)
            lineTo(12f, 16.6f)
        }
    }

    /** 音轨（扬声器 + 声波）。 */
    val Audio: ImageVector by lazy {
        stroked("novum_audio") {
            moveTo(4f, 9.6f)
            lineTo(7.2f, 9.6f)
            lineTo(11f, 6.2f)
            lineTo(11f, 17.8f)
            lineTo(7.2f, 14.4f)
            lineTo(4f, 14.4f)
            close()
            moveTo(14.4f, 9.2f)
            quadTo(16.6f, 12f, 14.4f, 14.8f)
            moveTo(17.2f, 7f)
            quadTo(20.8f, 12f, 17.2f, 17f)
        }
    }

    /** 字幕（画框 + 两行字幕）。 */
    val Subtitle: ImageVector by lazy {
        stroked("novum_subtitle") {
            moveTo(3.4f, 5.6f)
            lineTo(20.6f, 5.6f)
            lineTo(20.6f, 18.4f)
            lineTo(3.4f, 18.4f)
            close()
            moveTo(7f, 11.2f)
            lineTo(17f, 11.2f)
            moveTo(7f, 14.6f)
            lineTo(13.4f, 14.6f)
        }
    }

    /** 倍速（仪表盘 + 指针）。 */
    val Speed: ImageVector by lazy {
        stroked("novum_speed") {
            moveTo(3.6f, 17.4f)
            arcTo(8.4f, 8.4f, 0f, false, true, 20.4f, 17.4f)
            moveTo(12f, 17.4f)
            lineTo(15.8f, 10.6f)
        }
    }

    /** 关闭（叉）。 */
    val Close: ImageVector by lazy {
        stroked("novum_close") {
            moveTo(6f, 6f)
            lineTo(18f, 18f)
            moveTo(18f, 6f)
            lineTo(6f, 18f)
        }
    }

    /** 下拉指示（V 形）。 */
    val ChevronDown: ImageVector by lazy {
        stroked("novum_chevron_down") {
            moveTo(6f, 9.4f)
            lineTo(12f, 15.4f)
            lineTo(18f, 9.4f)
        }
    }

    /** 搜索（放大镜）。 */
    val Search: ImageVector by lazy {
        stroked("novum_search") {
            circle(10.6f, 10.6f, 6.4f)
            moveTo(15.4f, 15.4f)
            lineTo(20.2f, 20.2f)
        }
    }

    /** 收藏（心形，描边）。 */
    val Favorite: ImageVector by lazy {
        stroked("novum_favorite") {
            moveTo(12f, 20.2f)
            curveTo(5.6f, 16.2f, 3.2f, 13f, 3.2f, 9.7f)
            curveTo(3.2f, 7.1f, 5.2f, 5f, 7.7f, 5f)
            curveTo(9.5f, 5f, 11f, 6.1f, 12f, 7.8f)
            curveTo(13f, 6.1f, 14.5f, 5f, 16.3f, 5f)
            curveTo(18.8f, 5f, 20.8f, 7.1f, 20.8f, 9.7f)
            curveTo(20.8f, 13f, 18.4f, 16.2f, 12f, 20.2f)
            close()
        }
    }

    /** 已收藏（心形，实心）。 */
    val FavoriteFilled: ImageVector by lazy {
        filled("novum_favorite_filled") {
            moveTo(12f, 20.2f)
            curveTo(5.6f, 16.2f, 3.2f, 13f, 3.2f, 9.7f)
            curveTo(3.2f, 7.1f, 5.2f, 5f, 7.7f, 5f)
            curveTo(9.5f, 5f, 11f, 6.1f, 12f, 7.8f)
            curveTo(13f, 6.1f, 14.5f, 5f, 16.3f, 5f)
            curveTo(18.8f, 5f, 20.8f, 7.1f, 20.8f, 9.7f)
            curveTo(20.8f, 13f, 18.4f, 16.2f, 12f, 20.2f)
            close()
        }
    }

    /** 首页图标。 */
    val Home: ImageVector by lazy {
        stroked("novum_home") {
            moveTo(4.2f, 11.2f)
            lineTo(12f, 4.6f)
            lineTo(19.8f, 11.2f)
            moveTo(6.2f, 9.8f)
            lineTo(6.2f, 19.2f)
            lineTo(17.8f, 19.2f)
            lineTo(17.8f, 9.8f)
            // 门
            moveTo(10.2f, 19.2f)
            lineTo(10.2f, 14.6f)
            lineTo(13.8f, 14.6f)
            lineTo(13.8f, 19.2f)
        }
    }

    /** 媒体库（四宫格）。 */
    val Library: ImageVector by lazy {
        stroked("novum_library") {
            moveTo(4f, 4f)
            lineTo(10.4f, 4f)
            lineTo(10.4f, 10.4f)
            lineTo(4f, 10.4f)
            close()
            moveTo(13.6f, 4f)
            lineTo(20f, 4f)
            lineTo(20f, 10.4f)
            lineTo(13.6f, 10.4f)
            close()
            moveTo(4f, 13.6f)
            lineTo(10.4f, 13.6f)
            lineTo(10.4f, 20f)
            lineTo(4f, 20f)
            close()
            moveTo(13.6f, 13.6f)
            lineTo(20f, 13.6f)
            lineTo(20f, 20f)
            lineTo(13.6f, 20f)
            close()
        }
    }

    /** 画面比例（外屏框 + 内画面，缩放模式切换用）。 */
    val Aspect: ImageVector by lazy {
        stroked("novum_aspect") {
            // 屏框
            moveTo(3.4f, 6.2f)
            lineTo(20.6f, 6.2f)
            lineTo(20.6f, 17.8f)
            lineTo(3.4f, 17.8f)
            close()
            // 画面
            moveTo(7f, 9.4f)
            lineTo(17f, 9.4f)
            lineTo(17f, 14.6f)
            lineTo(7f, 14.6f)
            close()
        }
    }

    /** 设置（三条滑杆）。 */
    val Settings: ImageVector by lazy {
        stroked("novum_settings") {
            moveTo(4f, 7f)
            lineTo(20f, 7f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(4f, 17f)
            lineTo(20f, 17f)
            moveTo(9.2f, 7f)
            lineTo(9.3f, 7f)
            moveTo(15f, 12f)
            lineTo(15.1f, 12f)
            moveTo(7.4f, 17f)
            lineTo(7.5f, 17f)
        }
    }

    /** 用户图标：头像轮廓。 */
    val Person: ImageVector by lazy {
        stroked("novum_person") {
            circle(12f, 8f, 3.6f)
            // 肩部弧线
            moveTo(5.2f, 19.6f)
            curveTo(5.2f, 15.4f, 8.4f, 13.2f, 12f, 13.2f)
            curveTo(15.6f, 13.2f, 18.8f, 15.4f, 18.8f, 19.6f)
        }
    }

    /** 快退（双左三角，播放控制条）。 */
    val Rewind: ImageVector by lazy {
        filled("novum_rewind") {
            moveTo(11.5f, 5f)
            lineTo(3.5f, 12f)
            lineTo(11.5f, 19f)
            close()
            moveTo(20.5f, 5f)
            lineTo(12.5f, 12f)
            lineTo(20.5f, 19f)
            close()
        }
    }

    /** 快进（双右三角，播放控制条）。 */
    val FastForward: ImageVector by lazy {
        filled("novum_fast_forward") {
            moveTo(3.5f, 5f)
            lineTo(11.5f, 12f)
            lineTo(3.5f, 19f)
            close()
            moveTo(12.5f, 5f)
            lineTo(20.5f, 12f)
            lineTo(12.5f, 19f)
            close()
        }
    }

    /** 上一集（左三角 + 竖条）。 */
    val SkipPrev: ImageVector by lazy {
        filled("novum_skip_prev") {
            moveTo(6f, 5f)
            lineTo(9f, 5f)
            lineTo(9f, 19f)
            lineTo(6f, 19f)
            close()
            moveTo(19f, 5f)
            lineTo(9f, 12f)
            lineTo(19f, 19f)
            close()
        }
    }

    /** 下一集（右三角 + 竖条）。 */
    val SkipNext: ImageVector by lazy {
        filled("novum_skip_next") {
            moveTo(15f, 5f)
            lineTo(18f, 5f)
            lineTo(18f, 19f)
            lineTo(15f, 19f)
            close()
            moveTo(5f, 5f)
            lineTo(15f, 12f)
            lineTo(5f, 19f)
            close()
        }
    }

    /** 从头播放（半球弧 + 箭头，详情页操作）。 */
    val Replay: ImageVector by lazy {
        stroked("novum_replay") {
            moveTo(19f, 12f)
            arcTo(7f, 7f, 0f, false, false, 5f, 12f)
            // 箭头顶点在弧终点、指向行进方向（逆时针 → 左端向下）
            moveTo(2.6f, 9.6f)
            lineTo(5f, 12f)
            lineTo(7.4f, 9.6f)
        }
    }

    /** 对勾（标为已看）。 */
    val Check: ImageVector by lazy {
        stroked("novum_check") {
            moveTo(4.6f, 12.6f)
            lineTo(9.6f, 17.6f)
            lineTo(19.4f, 6.8f)
        }
    }

    /** 实心对勾：与 [Check] 描边勾使用相同几何；已看态可染 [com.novum.tv.ui.theme.NovumPalette.Success]。 */
    val CheckFilled: ImageVector by lazy {
        filled("novum_check_filled") {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineTo(3.41f, 13.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineTo(19.59f, 5.59f)
            close()
        }
    }

    /** 更多（三个圆点）。 */
    val More: ImageVector by lazy {
        filled("novum_more") {
            dot(5f, 12f)
            dot(12f, 12f)
            dot(19f, 12f)
        }
    }

    /**
     * 胶卷盘（版本选择入口）：外圆 + 中心轴 + 三个片孔。
     *
     * 用描边风格（与已看/未看图标的材质一致），图形语义直指「多版本/胶片」。
     */
    val FilmReel: ImageVector by lazy {
        stroked("novum_film_reel") {
            // 外圆
            circle(12f, 12f, 9f)
            // 中心轴
            circle(12f, 12f, 2f)
            // 三个片孔（均布 120°）
            circle(12f, 6.2f, 1.9f)
            circle(6.9f, 15f, 1.9f)
            circle(17.1f, 15f, 1.9f)
        }
    }

    /** 画一个实心圆点（More 图标用）。 */
    private fun PathBuilder.dot(cx: Float, cy: Float) {
        val r = 1.6f
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, true, true, cx, cy + r)
        arcTo(r, r, 0f, true, true, cx, cy - r)
        close()
    }

    /** 画一个描边圆（PathBuilder 没有 ellipse 原语）。 */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, true, true, cx, cy + r)
        arcTo(r, r, 0f, true, true, cx, cy - r)
        close()
    }

    /** 统一的描边图标构建器。 */
    private fun stroked(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = block,
        ).build()

    /** 统一的实心图标构建器。 */
    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            stroke = null,
            pathBuilder = block,
        ).build()

    /** 线条粗细：1.8dp 在 1080p 电视上约为 2 个物理像素，既不虚也不糊。 */
    private const val STROKE_WIDTH = 1.8f
}
