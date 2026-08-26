package com.sunny.notificationlog.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DAO logic tested on the desktop JVM via Robolectric + an in-memory Room
 * database (fresh instance per test — no cross-test pollution).
 */
@RunWith(RobolectricTestRunner::class)
class NotificationDaoTest {

    private lateinit var database: NotificationDatabase
    private lateinit var dao: NotificationDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            NotificationDatabase::class.java
        ).build()
        dao = database.notificationDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun entity(
        title: String = "title",
        text: String = "body",
        timestamp: Long = 0L,
        packageName: String = "com.example.app",
        appLabel: String = "Example"
    ) = NotificationEntity(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        timestamp = timestamp
    )

    @Test
    fun insert_generatesPositiveRowId() = runTest {
        val id = dao.insert(entity())
        assertTrue(id > 0)
    }

    @Test
    fun insert_roundTripsAllColumns() = runTest {
        dao.insert(
            entity(
                title = "Login code",
                text = "Your code is 123456",
                timestamp = 1_700_000_000_000,
                packageName = "org.example.mail",
                appLabel = "Mail"
            )
        )

        val stored = dao.getAllOrderedByTimestampDesc().first().single()
        assertEquals("Login code", stored.title)
        assertEquals("Your code is 123456", stored.text)
        assertEquals(1_700_000_000_000, stored.timestamp)
        assertEquals("org.example.mail", stored.packageName)
        assertEquals("Mail", stored.appLabel)
    }

    @Test
    fun getAllOrderedByTimestampDesc_ordersNewestFirst() = runTest {
        dao.insert(entity(title = "old", timestamp = 100))
        dao.insert(entity(title = "newest", timestamp = 300))
        dao.insert(entity(title = "middle", timestamp = 200))

        val titles = dao.getAllOrderedByTimestampDesc().first().map { it.title }

        assertEquals(listOf("newest", "middle", "old"), titles)
    }

    @Test
    fun getAllOrderedByTimestampDesc_sameTimestamp_ordersByInsertionDescending() = runTest {
        // Grouped apps post children in the same millisecond; the id DESC
        // tie-break must keep their order stable (last posted shown first).
        val firstId = dao.insert(entity(title = "first", timestamp = 100))
        val secondId = dao.insert(entity(title = "second", timestamp = 100))
        assertTrue(secondId > firstId)

        val titles = dao.getAllOrderedByTimestampDesc().first().map { it.title }

        assertEquals(listOf("second", "first"), titles)
    }

    @Test
    fun search_matchingTitle_caseInsensitive() = runTest {
        dao.insert(entity(title = "Bitcoin price alert"))
        dao.insert(entity(title = "Weather"))

        val results = dao.search("BITCOIN").first()

        assertEquals(1, results.size)
        assertEquals("Bitcoin price alert", results.single().title)
    }

    @Test
    fun search_matchingText_caseInsensitive() = runTest {
        dao.insert(entity(title = "Shipping", text = "Package TOKEN-99 delivered"))
        dao.insert(entity(title = "Unrelated", text = "nothing here"))

        val results = dao.search("token").first()

        assertEquals(1, results.size)
        assertEquals("Shipping", results.single().title)
    }

    @Test
    fun search_partialSubstring_matches() = runTest {
        dao.insert(entity(text = "your verification hello world code"))

        val results = dao.search("lo wo").first()

        assertEquals(1, results.size)
    }

    @Test
    fun search_noMatch_returnsEmptyList() = runTest {
        dao.insert(entity(title = "Breakfast", text = "eggs"))

        assertTrue(dao.search("zebra").first().isEmpty())
    }

    @Test
    fun search_emptyQuery_returnsAllRows() = runTest {
        dao.insert(entity(title = "one", timestamp = 1))
        dao.insert(entity(title = "two", timestamp = 2))

        assertEquals(2, dao.search("").first().size)
    }

    // region Phase 5: documented search quirks, pinned by tests so a future
    // escaping fix (or accidental behavior change) is caught immediately.

    @Test
    fun search_percentSign_actsAsWildcard_matchingEveryRow() = runTest {
        dao.insert(entity(title = "Meeting notes"))
        dao.insert(entity(title = "50% discount"))

        // '%' typed by the user is a SQL LIKE wildcard (documented in the DAO
        // KDoc), so it matches EVERY row — not just ones containing "%".
        assertEquals(2, dao.search("%").first().size)
    }

    @Test
    fun search_underscoreSign_actsAsSingleCharWildcard() = runTest {
        dao.insert(entity(title = "a_b"))
        dao.insert(entity(title = "axb"))
        dao.insert(entity(title = "abc"))

        // '_' matches exactly one arbitrary character: hits "a_b" and "axb",
        // skips "abc" (the third char must still be 'b').
        val titles = dao.search("a_b").first().map { it.title }.sorted()

        assertEquals(listOf("a_b", "axb"), titles)
    }

    // endregion

    // region Phase 4: data ownership (delete one / clear all)

    @Test
    fun deleteById_removesOnlyTargetRow() = runTest {
        val keepId = dao.insert(entity(title = "keep", timestamp = 1))
        val deleteId = dao.insert(entity(title = "delete me", timestamp = 2))

        dao.deleteById(deleteId)

        val titles = dao.getAllOrderedByTimestampDesc().first().map { it.title }
        assertEquals(listOf("keep"), titles)
        assertTrue(dao.getAllOrderedByTimestampDesc().first().single().id == keepId)
    }

    @Test
    fun deleteById_nonexistentId_isNoOp() = runTest {
        dao.insert(entity(title = "survivor"))

        // Double-swipe race / stale id must not throw or remove anything else.
        dao.deleteById(9999)

        assertEquals(1, dao.getAllOrderedByTimestampDesc().first().size)
    }

    @Test
    fun deleteAll_clearsWholeTable_andFlowEmitsEmptyList() = runTest {
        dao.insert(entity(title = "one", timestamp = 1))
        dao.insert(entity(title = "two", timestamp = 2))

        dao.deleteAll()

        assertTrue(dao.getAllOrderedByTimestampDesc().first().isEmpty())
    }

    @Test
    fun getAllForExport_returnsChronologicalSnapshot_oldestFirst() = runTest {
        // Opposite order to the UI list: export reads like a log, ascending.
        dao.insert(entity(title = "newest", timestamp = 300))
        dao.insert(entity(title = "oldest", timestamp = 100))
        dao.insert(entity(title = "middle", timestamp = 200))

        val titles = dao.getAllForExport().map { it.title }

        assertEquals(listOf("oldest", "middle", "newest"), titles)
    }

    // endregion
}
