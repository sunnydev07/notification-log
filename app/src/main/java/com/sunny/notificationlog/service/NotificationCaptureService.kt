package com.sunny.notificationlog.service

import android.app.Notification
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.sunny.notificationlog.NotificationLogApp
import com.sunny.notificationlog.data.NotificationEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Captures every posted notification and stores it in the Room database.
 *
 * How this works (platform notes):
 *
 * - After the user grants "Notification access" for this app in Settings,
 *   the SYSTEM binds this service on its own. We never start or stop it;
 *   revoking access unbinds it. Because binding survives the activity being
 *   closed (and the whole task being swiped away), notifications are captured
 *   while the app UI is not running at all — that is Phase 2's first
 *   acceptance criterion.
 *
 * - [onNotificationPosted] fires for every status bar notification while we
 *   are bound. It runs on a binder thread (not the main thread), so we must
 *   hand database writes off to a background scope rather than doing them
 *   here synchronously.
 */
class NotificationCaptureService : NotificationListenerService() {

    private val app: NotificationLogApp
        get() = applicationContext as NotificationLogApp

    /**
     * App-label cache: PackageManager lookups are binder IPC calls, and busy
     * apps post dozens of notifications per hour. Labels rarely change, so
     * caching per process is a cheap, safe win.
     */
    private val appLabelCache = HashMap<String, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Useful sanity check: if you don't see this in Logcat after granting
        // access, the system did not bind us (e.g. toggle got revoked again).
        Log.i(TAG, "Listener connected — capturing notifications")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            // Exclude notifications posted by our own app, checked FIRST: a
            // listener that captures its own output re-triggers itself forever
            // (capture -> post -> capture -> ...). We post none today, but this
            // makes any future local notification safe by construction.
            if (CaptureRules.isOwnNotification(packageName, sbn.packageName)) return

            /*
             * Noise filter — phase-4 judgment calls, all documented in
             * CaptureRules. Each rule answers "would anyone ever search for
             * this row later?"; if not, it is skipped (with a debug log) so
             * the history stays a readable record of real messages:
             *
             * 1. Group summaries ("3 new messages"): synthetic duplicates of
             *    child notifications that are captured individually.
             * 2. Ongoing/persistent notifications: download progress bars,
             *    media players, VPN status — machine status indicators that
             *    re-post on every tick, never swiped-away content.
             */
            val flags = sbn.notification.flags
            if (CaptureRules.isGroupSummary(flags)) {
                Log.d(TAG, "Skipped group summary from ${sbn.packageName}")
                return
            }
            if (CaptureRules.isOngoing(flags)) {
                Log.d(TAG, "Skipped ongoing notification from ${sbn.packageName}")
                return
            }

            val extras = sbn.notification.extras

            // Extras are notoriously null/unreliable across apps. Treat every
            // field as possibly missing; extraction yields null for blank or
            // whitespace-only values.
            val extractedTitle = extractText(extras.getCharSequence(Notification.EXTRA_TITLE))

            // Some apps put the real content only in BIG_TEXT (expanded view),
            // leaving TEXT short or blank, so fall back to it.
            val extractedText = extractText(extras.getCharSequence(Notification.EXTRA_TEXT))
                ?: extractText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))

            // 3. Nothing meaningful anywhere: silent/system pings with no text
            //    would only become "(no title)"/"(no text)" placeholder rows.
            if (CaptureRules.hasNoMeaningfulText(extractedTitle, extractedText)) {
                Log.d(TAG, "Skipped blank-content notification from ${sbn.packageName}")
                return
            }

            val title = extractedTitle ?: "(no title)"
            val text = extractedText ?: "(no text)"
            val appLabel = resolveAppLabel(sbn.packageName)

            // Keep the Phase 1 log line: free debugging signal while storage
            // itself is invisible until the Database Inspector / future UI.
            val timestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(sbn.postTime))
            Log.d(
                TAG,
                "Captured | package=${sbn.packageName} | title=$title | " +
                    "text=$text | postedAt=$timestamp"
            )

            // Insert off the binder thread. The scope belongs to the
            // Application object so an in-flight write survives the service
            // being unbound mid-write (see NotificationLogApp.applicationScope).
            app.applicationScope.launch {
                try {
                    val rowId = app.repository.insert(
                        NotificationEntity(
                            packageName = sbn.packageName,
                            appLabel = appLabel,
                            title = title,
                            text = text,
                            timestamp = sbn.postTime
                        )
                    )
                    Log.d(TAG, "Stored row #$rowId from ${sbn.packageName}")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to store notification from ${sbn.packageName}", t)
                }
            }
        } catch (t: Throwable) {
            // Defensive: extraction bugs or weird OEM notifications must not
            // crash the service — keep capturing everything else.
            Log.w(TAG, "Failed to process a posted notification", t)
        }
    }

    /**
     * Human-readable app name ("Telegram"), falling back to the package id
     * itself when PackageManager can't resolve it (app uninstalled mid-flight,
     * odd system components, ...). Failures are logged and NOT cached so a
     * transient lookup problem can succeed next time.
     */
    private fun resolveAppLabel(packageName: String): String =
        try {
            appLabelCache.getOrPut(packageName) { loadAppLabel(packageName) }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve app label for $packageName", e)
            packageName
        }

    private fun loadAppLabel(packageName: String): String {
        val pm = packageManager
        // The flags-based overload was deprecated in API 33 in favor of
        // ApplicationInfoFlags; keep both branches until minSdk >= 33.
        val appInfo = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        return pm.getApplicationLabel(appInfo).toString().ifBlank { packageName }
    }

    private fun extractText(charSequence: CharSequence?): String? =
        charSequence?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        // Consistent log tag per AGENTS.md — filter Logcat by this string.
        const val TAG = "NotificationLog"
    }
}
