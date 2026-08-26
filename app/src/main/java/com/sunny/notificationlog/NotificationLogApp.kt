package com.sunny.notificationlog

import android.app.Application
import com.sunny.notificationlog.billing.BillingRepository
import com.sunny.notificationlog.data.NotificationDatabase
import com.sunny.notificationlog.data.NotificationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal hand-rolled dependency holder. No DI framework on purpose
 * (AGENTS.md: minimum viable dependencies) — three lazy properties cover it.
 */
class NotificationLogApp : Application() {

    val database: NotificationDatabase by lazy {
        NotificationDatabase.getInstance(this)
    }

    val repository: NotificationRepository by lazy {
        NotificationRepository(database.notificationDao())
    }

    /**
     * Phase 7: Play Billing wrapper for the one-time Pro purchase. Shares the
     * application scope; connects lazily on first ViewModel init rather than
     * at process start, so cold starts without opening the app never pay for
     * a Play Billing IPC.
     */
    val billingRepository: BillingRepository by lazy {
        BillingRepository(this, applicationScope)
    }

    /**
     * Application-lifetime scope used by the notification listener.
     *
     * Deliberately NOT tied to the service lifecycle: Android can unbind and
     * rebind a notification listener frequently, and cancelling this scope in
     * onDestroy could drop a database write that was still in flight when the
     * service was torn down. The scope dies only when the process dies — at
     * which point any interrupted write would be lost regardless.
     */
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
