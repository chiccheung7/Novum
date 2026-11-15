package com.novum.tv.data.settings

import kotlinx.coroutines.flow.Flow

/**
 * 应用设置读写契约。
 *
 * 之所以用「快照 + transform」而不是十个 setter：设置项会随播放能力扩展持续增加，
 * transform 方式让新增字段只需改 [AppSettings]，不必同步改接口与实现。
 *
 * 线程约定：所有 suspend 方法内部自行切换 IO，调用方可在任意调度器调用。
 */
interface SettingsRepository {

    /** 设置的实时快照；首次订阅即发射当前值。 */
    val settings: Flow<AppSettings>

    /**
     * 原子地读取-修改-写回设置。
     *
     * @param transform 基于当前快照生成新设置，必须是纯函数。
     *
     * 注意：并发调用按 DataStore 事务串行执行，不会互相覆盖。
     */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}
