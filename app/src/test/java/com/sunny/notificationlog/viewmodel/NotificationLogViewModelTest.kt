package com.sunny.notificationlog.viewmodel

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sunny.notificationlog.NotificationLogApp
import com.sunny.notificationlog.data.NotificationDatabase
import com.sunny.notificationlog.data.NotificationEntity
import com.sunny.notificationlog.data.NotificationRepository
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ViewModel state-machine tests against a real Room in-memory database
 * (same Robolectric pattern as NotificationDaoTest — no mocking framework).
 *
 * Threading strategy, deliberately boring: Main is set to Dispatchers.Unconfined,
 * so the ViewModel pipeline (including the 300ms debounce) runs in REAL time —
 * a few hundred milliseconds per test. That sidesteps the classic trap of mixing
 * the coroutine-test virtual clock with Room, whose queries execute on its own
 * real executor threads. Assertions always AWAIT an expected state with a
 * timeout instead of assuming synchronous completion (see [awaitState]).
 *
 * Isolation note: every test builds its OWN in-memory database rather than the
 * app singleton — the singleton outlives individual Robolectric environments
 * and would leak rows between tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotificationLogViewModelTest {

    /** Fixed instant (2026-08-26 12:00 UTC) for deterministic timestamps. */
    private val now = 1_787_745_600_000L

    private class Fixture(
        val database: NotificationDatabase,
        val repository: NotificationRepository,
        val viewModel: NotificationLogViewModel
    )

    private fun buildFixture(): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(
            context,
            NotificationDatabase::class.java
        ).build()
        val repository = NotificationRepository(database.notificationDao())
        val viewModel = NotificationLogViewModel(
            ApplicationProvider.getApplicationContext(),
            repository = repository,
            now = { now }
        )
        return Fixture(database, repository, viewModel)
    }

    private fun entity(
        title: String,
        text: String = "body",
        timestamp: Long = now,
        id: Long = 0
    ) = NotificationEntity(
        id = id,
        packageName = "com.example.app",
        appLabel = "Example",
        title = title,
        text = text,
        timestamp = timestamp
    )

    /**
     * Await (real time, capped) until [predicate] matches an emitted state.
     * Runs outside runTest's virtual scheduler so Room's real-thread emissions
     * are observed naturally.
     */
    private suspend fun TestScope.awaitState(
        viewModel: NotificationLogViewModel,
        predicate: (HistoryUiState) -> Boolean
    ): HistoryUiState = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            viewModel.uiState.first(predicate)
        }
    }

    private fun TestScope.startCollecting(viewModel: NotificationLogViewModel) {
        // Keeps WhileSubscribed sharing active during the test. Unconfined so
        // subscription starts IMMEDIATELY (a scheduler-queued collector would
        // never actually subscribe under this test's real-time strategy), and
        // backgroundScope so it is cancelled automatically at test end.
        backgroundScope.launch(Dispatchers.Unconfined) {
            viewModel.uiState.collect {}
        }
    }

    @Test
    fun uiState_beforeFirstEmission_isLoading() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            assertEquals(HistoryUiState.Loading, buildFixture().viewModel.uiState.value)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun uiState_emptyDatabase_blankQuery_showsEmptyState() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            startCollecting(fixture.viewModel)

            val settled = awaitState(fixture.viewModel) { it !is HistoryUiState.Loading }

            assertEquals(HistoryUiState.Empty, settled)
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun uiState_rowsExist_blankQuery_showsContentWithRelativeTimes() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "Login code", text = "Your code is 482913"))
            fixture.repository.insert(entity(title = "Older one", timestamp = now - 120_000))
            startCollecting(fixture.viewModel)

            val content = awaitState(fixture.viewModel) { it is HistoryUiState.Content }
                as HistoryUiState.Content

            // Newest first: different timestamps make ordering unambiguous.
            assertEquals("Login code", content.notifications.first().title)
            assertEquals("Just now", content.notifications.first().relativeTimestamp)
            assertEquals("2m ago", content.notifications[1].relativeTimestamp)
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun search_queryWithNoMatches_showsNoResultsCarryingTheQuery() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "Breakfast", text = "eggs"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }
            fixture.viewModel.onSearchQueryChange("zebra")

            val settled = awaitState(fixture.viewModel) { it is HistoryUiState.NoResults }

            assertEquals(HistoryUiState.NoResults("zebra"), settled)
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun search_matchingText_filtersContent() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "Shipping", text = "TOKEN-99 delivered"))
            fixture.repository.insert(entity(title = "Unrelated", text = "nothing here"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }
            fixture.viewModel.onSearchQueryChange("token")

            val content = awaitState(fixture.viewModel) {
                it is HistoryUiState.Content && it.notifications.size == 1
            } as HistoryUiState.Content

            assertTrue(content.notifications.single().text.contains("TOKEN-99"))
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun search_clearingQuery_returnsToFullListImmediately() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "One"))
            fixture.repository.insert(entity(title = "Two"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }

            fixture.viewModel.onSearchQueryChange("zzz")
            awaitState(fixture.viewModel) { it is HistoryUiState.NoResults }

            fixture.viewModel.onSearchQueryChange("")
            val content = awaitState(fixture.viewModel) {
                it is HistoryUiState.Content && it.notifications.size == 2
            } as HistoryUiState.Content

            assertEquals(2, content.notifications.size)
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    // region Phase 4: data ownership (delete one / clear all)

    @Test
    fun deleteNotification_removesRow_andListUpdatesReactively() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            val firstId = fixture.repository.insert(entity(title = "Swipe me"))
            fixture.repository.insert(entity(title = "Stay"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }
            fixture.viewModel.onDeleteNotification(firstId)

            // The Room Flow must re-emit and drop the deleted row on its own —
            // no manual refresh anywhere.
            val content = awaitState(fixture.viewModel) {
                it is HistoryUiState.Content &&
                    it.notifications.map { row -> row.title } == listOf("Stay")
            } as HistoryUiState.Content

            assertEquals(listOf("Stay"), content.notifications.map { it.title })
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun clearAll_emptiesHistory_andShowsEmptyState() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "One"))
            fixture.repository.insert(entity(title = "Two"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }
            fixture.viewModel.onClearAll()

            // Blank query + zero rows => the distinct Empty state, not NoResults.
            assertEquals(HistoryUiState.Empty, awaitState(fixture.viewModel) { it is HistoryUiState.Empty })
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    @Test
    fun deleteNotification_nonexistentId_leavesContentUnchanged() = runTest {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val fixture = buildFixture()
        try {
            fixture.repository.insert(entity(title = "Only one"))
            startCollecting(fixture.viewModel)

            awaitState(fixture.viewModel) { it is HistoryUiState.Content }
            fixture.viewModel.onDeleteNotification(9999)

            val content = awaitState(fixture.viewModel) {
                it is HistoryUiState.Content && it.notifications.size == 1
            } as HistoryUiState.Content

            assertEquals("Only one", content.notifications.single().title)
        } finally {
            Dispatchers.resetMain()
            fixture.database.close()
        }
    }

    // endregion
}