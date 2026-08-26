package com.sunny.notificationlog.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Pure JVM tests with pinned clock, timezone and locale — deterministic by
 * construction (AGENTS.md: inject/pass instead of hardcoding).
 */
class RelativeTimeTest {

    private val zone = ZoneId.of("UTC")
    private val locale = Locale.ENGLISH

    /** Wed 2026-08-26 12:00 UTC */
    private val now: Long =
        ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun minutesBefore(minutes: Long): Long =
        now - minutes * 60_000L

    @Test
    fun format_underOneMinute_justNow() {
        assertEquals(
            "Just now",
            RelativeTime.format(now - 30_000, now, zone, locale)
        )
    }

    @Test
    fun format_exactlySixtyMinutes_oneHour() {
        assertEquals("1h ago", RelativeTime.format(minutesBefore(60), now, zone, locale))
    }

    @Test
    fun format_underOneHour_minutesAgo() {
        assertEquals("5m ago", RelativeTime.format(minutesBefore(5), now, zone, locale))
    }

    @Test
    fun format_underTwentyFourHours_hoursAgo() {
        assertEquals("2h ago", RelativeTime.format(minutesBefore(120), now, zone, locale))
    }

    @Test
    fun format_between24and48Hours_yesterday() {
        assertEquals("Yesterday", RelativeTime.format(minutesBefore(60 * 30), now, zone, locale))
    }

    @Test
    fun format_upToAWeek_daysAgo() {
        assertEquals("3d ago", RelativeTime.format(minutesBefore(60 * 24 * 3), now, zone, locale))
    }

    @Test
    fun format_olderThanThisWeek_sameYear_dayAndMonth() {
        // 16 days earlier: Mon 2026-08-10
        assertEquals(
            "10 Aug",
            RelativeTime.format(minutesBefore(60 * 24 * 16), now, zone, locale)
        )
    }

    @Test
    fun format_olderYear_includesYear() {
        // Jan 2025 is in a different calendar year than "now" (Aug 2026).
        val old = ZonedDateTime.of(2025, 1, 15, 9, 0, 0, 0, zone)
            .toInstant().toEpochMilli()
        assertEquals("15 Jan 2025", RelativeTime.format(old, now, zone, locale))
    }

    @Test
    fun format_futureTimestamp_clampsToJustNow() {
        // Device clock skew guard: never show negative durations.
        assertEquals("Just now", RelativeTime.format(now + 60_000, now, zone, locale))
    }
}