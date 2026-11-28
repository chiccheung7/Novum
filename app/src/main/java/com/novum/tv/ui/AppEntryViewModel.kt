package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.error.NovumError
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.data.settings.pinnedCleartextLineFor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 应用入口状态：决定首屏是登录页还是首页。
 *
 * 单独抽 ViewModel 的原因：会话存储在 DataStore 中，读取是异步的；
 * 若直接在 Composable 里 `runBlocking` 会阻塞首帧。
 *
 * @param authRepository 用于恢复本地会话。
 */
@HiltViewModel
class AppEntryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val sessionHolder: SessionHolder,
) : ViewModel() {

    /** 入口状态。 */
    sealed interface Entry {
        /** 正在读取本地会话。 */
        data object Loading : Entry

        /** 已有会话，直接进首页。 */
        data object LoggedIn : Entry

        /** 无会话，进登录页。 */
        data object LoggedOut : Entry

        /** 读取本地会话失败；允许用户重试，而不是误判为未登录。 */
        data class RestoreFailed(val error: NovumError) : Entry
    }

    private val _entry = MutableStateFlow<Entry>(Entry.Loading)
    val entry: StateFlow<Entry> = _entry.asStateFlow()
    private var restoreJob: Job? = null

    init {
        retryRestore()
    }

    fun retryRestore() {
        restoreJob?.cancel()
        _entry.value = Entry.Loading
        restoreJob = viewModelScope.launch {
            try {
                val session = authRepository.restore()
                if (session == null) {
                    _entry.value = Entry.LoggedOut
                } else {
                    val accountKey = sessionHolder.playbackPrefsNamespace()
                    val settings = settingsRepository.settings.first()
                    sessionHolder.setPinnedLineCleartextPermission(
                        settings.pinnedCleartextLineFor(accountKey),
                        accountKey,
                        confirmed = settings.pinnedLineCleartextConfirmed,
                    )
                    _entry.value = Entry.LoggedIn
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                _entry.value = Entry.RestoreFailed(NovumError.Unknown(exception))
            }
        }
    }
}
