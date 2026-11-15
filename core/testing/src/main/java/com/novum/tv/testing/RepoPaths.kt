package com.novum.tv.testing

import java.io.File

/**
 * 仓库路径解析。
 *
 * 契约测试要扫全仓源码（零硬编码中文 / 令牌白名单 / Retrofit 抽象方法 / 焦点家规…）。
 * Gradle 测试进程的 `user.dir` 可能是模块目录，因此统一向上查找
 * `settings.gradle.kts` 定位仓库根。
 */
object RepoPaths {

    /** 仓库根（含 `settings.gradle.kts` 的目录）。 */
    val root: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir = start
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
            dir = dir.parentFile!!
        }
        check(File(dir, "settings.gradle.kts").exists()) {
            "未找到仓库根（settings.gradle.kts）：从 $start 向上查找失败"
        }
        dir
    }

    /** 仓库内相对路径 → [File]（供契约测试引用源码）。 */
    fun resolve(relativePath: String): File = File(root, relativePath)

    /** 仓库内源码根清单（app 主源集 + core 与 feature 下各模块的主源集）。 */
    fun mainSourceDirs(): List<File> = buildList {
        add(File(root, "app/src/main/java"))
        listOf("core", "feature").forEach { group ->
            File(root, group).listFiles()?.forEach { module ->
                add(File(module, "src/main/java"))
            }
        }
    }.filter { it.exists() }
}
