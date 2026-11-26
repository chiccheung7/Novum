package com.novum.tv.ui.screen.search

import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PersonRef
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopMediaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PersonSearchViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun startReusesCurrentQueryButExplicitSearchAndClearAllowRepeatingIt() = runTest {
        var calls = 0
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> {
                calls++
                return listOf(PersonRef("Result", "person"))
            }
        }
        val viewModel = PersonSearchViewModel(repository)
        viewModel.start("same query")
        advanceUntilIdle()
        viewModel.start("same query")
        advanceUntilIdle()
        assertEquals("页面恢复复用同关键词的请求", 1, calls)

        viewModel.search("same query")
        advanceUntilIdle()
        assertEquals("显式确认仍强制重新搜索", 2, calls)
        viewModel.clear()
        viewModel.start("same query")
        advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test
    fun repeatedQueryCannotAcceptAnOlderResponseAfterClearing() = runTest {
        val oldResponse = CompletableDeferred<List<PersonRef>>()
        var calls = 0
        val fresh = PersonRef("New result", "new")
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> =
                if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() } else listOf(fresh)
        }
        val viewModel = PersonSearchViewModel(repository)
        viewModel.search("same query")
        runCurrent()
        viewModel.clear()
        viewModel.search("same query")
        runCurrent()
        assertEquals(listOf(fresh), viewModel.hits.value)

        oldResponse.complete(listOf(PersonRef("Old result", "old")))
        advanceUntilIdle()
        assertEquals("A cancelled request must not replace the new request for the same text", listOf(fresh), viewModel.hits.value)
    }

    @Test
    fun olderSameQueryCannotClearTheLatestLoadingState() = runTest {
        val oldResponse = CompletableDeferred<List<PersonRef>>()
        val newResponse = CompletableDeferred<List<PersonRef>>()
        var calls = 0
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> =
                if (calls++ == 0) withContext(NonCancellable) { oldResponse.await() } else newResponse.await()
        }
        val viewModel = PersonSearchViewModel(repository)
        viewModel.search("same query")
        runCurrent()
        viewModel.search("same query")
        runCurrent()
        oldResponse.complete(emptyList())
        runCurrent()
        try {
            assertTrue("The current request is still running", viewModel.loading.value)
        } finally {
            newResponse.complete(emptyList())
            advanceUntilIdle()
        }
    }
}
