package com.sunny.notificationlog.data

import kotlinx.coroutines.flow.Flow

/**
 * Single doorway to the data layer (AGENTS.md architecture: screens and
 * services never touch the DAO directly). Thin today — its value is the seam:
 * Phase 3 ViewModels depend on this class, not on Room, which keeps them
 * testable and lets storage details change without touching UI code.
 */
class NotificationRepository(private val dao: NotificationDao) {

    /** Full history, newest first — the unfiltered list screen state. */
    fun getAllOrderedByTimestampDesc(): Flow<List<NotificationEntity>> =
        dao.getAllOrderedByTimestampDesc()

    /**
     * One reactive stream for the screen regardless of search state: a blank
     * query observes everything, anything else observes the filtered result.
     * Keeping this routing here means the ViewModel never needs to know which
     * DAO method backs the current view.
     */
    fun observeNotifications(query: String): Flow<List<NotificationEntity>> =
        if (query.isBlank()) dao.getAllOrderedByTimestampDesc() else dao.search(query)

    suspend fun insert(notification: NotificationEntity): Long = dao.insert(notification)

    /** Removes one captured notification (phase 4 swipe-to-delete). */
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** Wipes the whole history (phase 4 clear-all). */
    suspend fun deleteAll() = dao.deleteAll()

    /** Chronological snapshot of every row (phase 7 CSV export). */
    suspend fun getAllForExport(): List<NotificationEntity> = dao.getAllForExport()
}
