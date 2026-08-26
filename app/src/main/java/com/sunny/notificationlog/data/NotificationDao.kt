package com.sunny.notificationlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Database access for captured notifications.
 *
 * Room verifies every @Query against the entity at COMPILE time, so a typo in
 * SQL or a column rename breaks the build instead of crashing at runtime.
 */
@Dao
interface NotificationDao {

    /** Suspends until the row is written; returns the generated row id. */
    @Insert
    suspend fun insert(notification: NotificationEntity): Long

    /**
     * A cold Flow that re-emits the whole list whenever the notifications
     * table changes (Room wires this up via its InvalidationTracker). The
     * list screen collects this reactively.
     *
     * Secondary sort by id DESC: grouped apps post several notifications in
     * the same millisecond, and without a tie-break their relative order
     * would be undefined (and could shuffle between emissions).
     */
    @Query("SELECT * FROM notifications ORDER BY timestamp DESC, id DESC")
    fun getAllOrderedByTimestampDesc(): Flow<List<NotificationEntity>>

    /**
     * Basic search: substring match on title OR text, ordered newest first
     * (same id DESC tie-break as above).
     *
     * Case handling: SQLite's LIKE is already case-insensitive for ASCII
     * letters (a-z), which covers English content well enough for now. Full
     * Unicode case folding would need extra normalization — deliberately not
     * built yet.
     *
     * Note that '%' and '_' typed by a user act as LIKE wildcards; acceptable
     * quirk for a local utility, documented rather than escaped.
     *
     * An empty query matches everything ('%' || '' || '%' = '%'), so callers
     * can treat "no search term" as just another query value.
     */
    @Query(
        "SELECT * FROM notifications " +
            "WHERE title LIKE '%' || :query || '%' " +
            "OR text LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC, id DESC"
    )
    fun search(query: String): Flow<List<NotificationEntity>>

    /**
     * Phase 4 swipe-to-delete. Deleting one row by primary key; deleting a
     * non-existent id is a silent no-op (standard SQLite DELETE semantics),
     * which keeps a double-swipe race harmless.
     */
    @Query("DELETE FROM notifications WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Phase 4 "clear all": wipes the table in one statement, inside one transaction. */
    @Query("DELETE FROM notifications")
    suspend fun deleteAll()

    /**
     * One-shot snapshot for CSV export (phase 7), oldest first so the file
     * reads chronologically — the opposite order to the UI list, on purpose.
     */
    @Query("SELECT * FROM notifications ORDER BY timestamp ASC, id ASC")
    suspend fun getAllForExport(): List<NotificationEntity>
}
