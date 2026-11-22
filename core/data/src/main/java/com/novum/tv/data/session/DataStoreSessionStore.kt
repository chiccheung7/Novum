package com.novum.tv.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 基于 Preferences DataStore 的会话存储。
 *
 * 设备 id 与会话分开：退出登录不清设备 id，保证服务端看到的仍是同一台电视
 * （Emby 用它关联播放进度与设备配置）。
 *
 * @param dataStore 与设置共用的进程级 DataStore。
 */
class DataStoreSessionStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SessionStore {

    override val session: Flow<Session?> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toSession() }
        .distinctUntilChanged()

    override suspend fun load(): Session? = session.first()

    override suspend fun save(
        server: String,
        token: String,
        userId: String,
        userName: String,
        serverName: String,
    ) {
        dataStore.edit { preferences ->
            preferences[SessionKeys.Server] = server
            preferences[SessionKeys.Token] = token
            preferences[SessionKeys.UserId] = userId
            preferences[SessionKeys.UserName] = userName
            preferences[SessionKeys.ServerName] = serverName
            preferences[SessionKeys.LastUsername] = userName
            if (preferences[SessionKeys.DeviceId].isNullOrBlank()) {
                preferences[SessionKeys.DeviceId] = UUID.randomUUID().toString()
            }
        }
    }

    override suspend fun saveIdentity(server: String, username: String) {
        dataStore.edit { preferences ->
            preferences[SessionKeys.Server] = server
            preferences[SessionKeys.LastUsername] = username
            // 「记住登录」关闭：确保不残留旧 token（否则会被当成已登录）
            preferences.remove(SessionKeys.Token)
            preferences.remove(SessionKeys.UserId)
            preferences.remove(SessionKeys.UserName)
            preferences.remove(SessionKeys.ServerName)
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            // 刻意保留 Server / LastUsername：登录页需要预填，减少遥控器输入
            preferences.remove(SessionKeys.Token)
            preferences.remove(SessionKeys.UserId)
            preferences.remove(SessionKeys.UserName)
            preferences.remove(SessionKeys.ServerName)
        }
    }

    override suspend fun lastServer(): String = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences[SessionKeys.Server].orEmpty() }
        .first()

    override suspend fun lastUsername(): String = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences[SessionKeys.LastUsername].orEmpty() }
        .first()

    override suspend fun deviceId(): String {
        val existing = dataStore.data
            .catch { throwable ->
                if (throwable is IOException) emit(emptyPreferences()) else throw throwable
            }
            .map { preferences -> preferences[SessionKeys.DeviceId] }
            .first()
        if (!existing.isNullOrBlank()) return existing

        val generated = UUID.randomUUID().toString()
        dataStore.edit { preferences ->
            if (preferences[SessionKeys.DeviceId].isNullOrBlank()) {
                preferences[SessionKeys.DeviceId] = generated
            }
        }
        return dataStore.data.first()[SessionKeys.DeviceId] ?: generated
    }

}

/** 键位保持稳定，重命名会导致用户被登出。 */
private object SessionKeys {
    val Server = stringPreferencesKey("session_server")
    val Token = stringPreferencesKey("session_token")
    val UserId = stringPreferencesKey("session_uid")
    val UserName = stringPreferencesKey("session_uname")
    val ServerName = stringPreferencesKey("session_sname")
    val DeviceId = stringPreferencesKey("session_device_id")
    /** 「记住登录」关闭时也保留的用户名，仅用于预填；不参与会话判定。 */
    val LastUsername = stringPreferencesKey("session_last_username")
}

/** 从偏好快照构造会话；任一关键字段缺失即视为未登录。 */
private fun Preferences.toSession(): Session? {
    val server = this[SessionKeys.Server].orEmpty()
    val token = this[SessionKeys.Token].orEmpty()
    val userId = this[SessionKeys.UserId].orEmpty()
    if (server.isBlank() || token.isBlank() || userId.isBlank()) return null
    return Session(
        server = server,
        token = token,
        userId = userId,
        userName = this[SessionKeys.UserName].orEmpty(),
        serverName = this[SessionKeys.ServerName].orEmpty(),
        deviceId = this[SessionKeys.DeviceId].orEmpty(),
    )
}
