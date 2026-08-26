package com.sunny.notificationlog.service

import android.app.Notification

/**
 * Pure decision helpers for what gets stored (phase 4 "noise filter" + the
 * phase-2 dedupe rule). Kept free of StatusBarNotification so they can be
 * unit-tested on the JVM.
 *
 * The full filter chain applied in NotificationCaptureService.onNotificationPosted,
 * in order:
 * 1. [isOwnNotification]  – never capture our own output (infinite-loop guard).
 * 2. [isGroupSummary]     – synthetic "3 new messages" duplicates; children carry
 *                           the real content.
 * 3. [isOngoing]          – persistent status indicators (downloads, media
 *                           playback, VPN, active call). See [isOngoing] for why
 *                           these are treated as noise rather than history.
 * 4. [hasNoMeaningfulText] – notifications whose title AND body both extract to
 *                           blank have nothing worth searching later.
 */
internal object CaptureRules {

    /**
     * Group summaries are the synthetic "3 new messages" notifications apps
     * post on top of a group of real child notifications (see the dedupe
     * comment in NotificationCaptureService). The platform marks them with
     * FLAG_GROUP_SUMMARY.
     */
    fun isGroupSummary(flags: Int): Boolean =
        flags and Notification.FLAG_GROUP_SUMMARY != 0

    /**
     * True when a posted notification came from this app itself.
     *
     * Why this must be checked FIRST: a listener that captures its own output
     * re-triggers itself forever (capture -> post -> capture -> ...), pinning a
     * core at 100% and flooding the log. We currently post no notifications,
     * but future phases might — this guard makes that safe by construction.
     */
    fun isOwnNotification(ownPackageName: String, postedByPackageName: String): Boolean =
        ownPackageName == postedByPackageName

    /**
     * FLAG_ONGOING_EVENT marks PERSISTENT notifications: progress bars ("file
     * downloading"), media players, VPN/call status. Phase-4 judgment call,
     * documented: these are machine-to-human STATUS indicators, not messages
     * anyone sent you, so they never contain the "what did I swipe away?"
     * content this app exists to preserve — but because they re-post on every
     * progress tick they would otherwise dominate the log within hours.
     *
     * Trade-off accepted: a rare ongoing heads-up (e.g. an active call banner)
     * will not appear in history either. Recoverable later if it hurts; the
     * rule lives in one testable function, not smeared through the service.
     */
    fun isOngoing(flags: Int): Boolean =
        flags and Notification.FLAG_ONGOING_EVENT != 0

    /**
     * True when neither title nor body survived text extraction. Silent system
     * notifications often arrive with empty/whitespace extras; storing them as
     * "(no title)/(no text)" placeholder pairs would only add unsearchable rows.
     */
    fun hasNoMeaningfulText(extractedTitle: String?, extractedText: String?): Boolean =
        extractedTitle == null && extractedText == null
}
