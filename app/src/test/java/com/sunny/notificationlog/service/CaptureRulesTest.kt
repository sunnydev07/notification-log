package com.sunny.notificationlog.service

import android.app.Notification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests: Notification flag values are compile-time int constants,
 * so they are inlined into the test bytecode and no Android runtime is needed.
 */
class CaptureRulesTest {

    @Test
    fun isGroupSummary_groupSummaryFlagSet_true() {
        assertTrue(CaptureRules.isGroupSummary(Notification.FLAG_GROUP_SUMMARY))
    }

    @Test
    fun isGroupSummary_noFlags_false() {
        assertFalse(CaptureRules.isGroupSummary(0))
    }

    @Test
    fun isGroupSummary_otherFlagsOnly_false() {
        // Ongoing + auto-cancel: typical of real content notifications.
        val realContentFlags =
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_AUTO_CANCEL
        assertFalse(CaptureRules.isGroupSummary(realContentFlags))
    }

    @Test
    fun isGroupSummary_groupSummaryCombinedWithOtherFlags_true() {
        // Summary notifications usually carry extra flags too; the summary bit
        // must still win.
        val summaryPlusOthers = Notification.FLAG_ONGOING_EVENT or
            Notification.FLAG_AUTO_CANCEL or
            Notification.FLAG_GROUP_SUMMARY
        assertTrue(CaptureRules.isGroupSummary(summaryPlusOthers))
    }

    // region Phase 4: self-exclusion

    @Test
    fun isOwnNotification_samePackage_true() {
        // The infinite-loop guard: our own package name must always match.
        assertTrue(
            CaptureRules.isOwnNotification(
                ownPackageName = "com.sunny.notificationlog",
                postedByPackageName = "com.sunny.notificationlog"
            )
        )
    }

    @Test
    fun isOwnNotification_differentPackage_false() {
        assertFalse(
            CaptureRules.isOwnNotification(
                ownPackageName = "com.sunny.notificationlog",
                postedByPackageName = "org.telegram.messenger"
            )
        )
    }

    // endregion

    // region Phase 4: ongoing-notification noise filter

    @Test
    fun isOngoing_ongoingFlagSet_true() {
        // Typical persistent notification: media player / download progress.
        assertTrue(CaptureRules.isOngoing(Notification.FLAG_ONGOING_EVENT))
    }

    @Test
    fun isOngoing_normalContentFlags_false() {
        // A dismissable message notification is NOT ongoing.
        val flags = Notification.FLAG_AUTO_CANCEL
        assertFalse(CaptureRules.isOngoing(flags))
    }

    @Test
    fun isOngoing_noFlags_false() {
        assertFalse(CaptureRules.isOngoing(0))
    }

    // endregion

    // region Phase 4: blank-content noise filter

    @Test
    fun hasNoMeaningfulText_bothNull_true() {
        assertTrue(CaptureRules.hasNoMeaningfulText(null, null))
    }

    @Test
    fun hasNoMeaningfulText_titleOnlyPresent_false() {
        assertFalse(CaptureRules.hasNoMeaningfulText("Download complete", null))
    }

    @Test
    fun hasNoMeaningfulText_textOnlyPresent_false() {
        // Title-less but searchable content is still worth storing.
        assertFalse(CaptureRules.hasNoMeaningfulText(null, "Your code is 482913"))
    }

    @Test
    fun hasNoMeaningfulText_bothPresent_false() {
        assertFalse(CaptureRules.hasNoMeaningfulText("Sofia", "Are we still on for tonight?"))
    }

    // endregion
}
