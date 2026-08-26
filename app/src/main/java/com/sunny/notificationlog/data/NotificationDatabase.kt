package com.sunny.notificationlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The single Room database for the app.
 *
 * Why the companion-object singleton: building a RoomDatabase is expensive,
 * and multiple instances against one SQLite file risk write contention. Every
 * caller gets the same instance for the process lifetime.
 */
@Database(
    entities = [NotificationEntity::class],
    version = 1,
    // Schema JSONs are exported to /app/schemas (see ksp block in build file)
    // so future schema changes can be migrated deliberately.
    exportSchema = true
)
abstract class NotificationDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    companion object {
        const val DATABASE_NAME = "notification_log.db"

        @Volatile
        private var INSTANCE: NotificationDatabase? = null

        fun getInstance(context: Context): NotificationDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NotificationDatabase::class.java,
                    DATABASE_NAME
                ).build().also { INSTANCE = it }
            }
    }
}
