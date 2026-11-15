package com.novum.tv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV 打包契约测试：把「必须是电视应用、且零 Leanback 依赖」这两条硬性约束固化为可执行断言，
 * 保证电视分发声明完整，且不依赖 View/Leanback 体系。
 *
 * 说明：本测试为纯 JVM 测试，直接校验源码树中的清单与构建脚本，无需设备。
 */
class TvPackagingContractTest {

    private val moduleDir: File = File(System.getProperty("user.dir") ?: ".").let { dir ->
        if (File(dir, "src/main/AndroidManifest.xml").exists()) dir else File(dir, "app")
    }

    private val manifestText: String =
        File(moduleDir, "src/main/AndroidManifest.xml").readText()

    private val appBuildScript: String = File(moduleDir, "build.gradle.kts").readText()

    /** 清单必须声明 Leanback 能力且为必需，保证只在电视设备分发。 */
    @Test
    fun manifestRequiresLeanbackFeature() {
        assertTrue(
            "清单必须声明 android.software.leanback",
            manifestText.contains("android.software.leanback"),
        )
        assertTrue(
            "Leanback 能力必须为 required=true",
            Regex("""android:name="android\.software\.leanback"\s+android:required="true"""")
                .containsMatchIn(manifestText),
        )
    }

    /** 清单必须声明不依赖触摸屏，否则电视设备会被过滤。 */
    @Test
    fun manifestDeclaresNoTouchscreenRequirement() {
        assertTrue(
            "必须声明 android.hardware.touchscreen required=false",
            Regex("""android:name="android\.hardware\.touchscreen"\s+android:required="false"""")
                .containsMatchIn(manifestText),
        )
    }

    /** 电视桌面入口依赖 LEANBACK_LAUNCHER 分类与 banner 图。 */
    @Test
    fun manifestDeclaresLeanbackLauncherAndBanner() {
        assertTrue(
            "必须声明 LEANBACK_LAUNCHER 入口",
            manifestText.contains("android.intent.category.LEANBACK_LAUNCHER"),
        )
        assertTrue(
            "必须声明 android:banner",
            manifestText.contains("android:banner="),
        )
        assertTrue(
            "banner 图必须存在（官方 mipmap 全密度五档，§4.64）",
            listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi").all { d ->
                File(moduleDir, "src/main/res/mipmap-$d/app_banner.png").exists()
            },
        )
    }

    /** 严禁引入 Leanback / View 体系依赖。 */
    @Test
    fun moduleHasNoLeanbackDependency() {
        assertFalse(
            "app 模块不得依赖 androidx.leanback",
            appBuildScript.contains("androidx.leanback"),
        )
    }
}
