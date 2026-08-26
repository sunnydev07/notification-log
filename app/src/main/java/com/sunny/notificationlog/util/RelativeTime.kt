package com.sunny.notificationlog.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Compact relative timestamps for list rows ("2h ago", "Yesterday").
 *
 * Pure function of (timestamp, now, zone, locale) so it is trivially unit
 * testable — the call site injects System.currentTimeMillis().
 *
 * The ladder uses ELAPSED time, not calendar days: 24–48h counts as
 * "Yesterday" even if a midnight boundary sits in between. Colloquially that
 * reads fine, and it keeps the logic timezone-free. Deliberate trade-off,
 * documented per AGENTS.md.
 *
 * Localization note: the words are hardcoded English for now (solo offline
 * utility). If this app is ever localized, swap these for resources with
 * proper plurals.
 */
object RelativeTime {

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 60 * MINUTE_MILLIS
    private const val DAY_MILLIS = 24 * HOUR_MILLIS

    fun format(
        timestamp: Long,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String {
        // Future timestamp = device clock skew between capture and display;
        // clamp instead of showing a negative count.
        if (now <= timestamp) return "Just now"

        val elapsedMinutes = (now - timestamp) / MINUTE_MILLIS
        return when {
            elapsedMinutes < 1 -> "Just now"
            elapsedMinutes < 60 -> "${elapsedMinutes}m ago"
            elapsedMinutes < DAY_MILLIS / MINUTE_MILLIS -> "${elapsedMinutes / 60}h ago"
            elapsedMinutes < 2 * DAY_MILLIS / MINUTE_MILLIS -> "Yesterday"
            elapsedMinutes < 7 * DAY_MILLIS / MINUTE_MILLIS ->
                "${elapsedMinutes / (DAY_MILLIS / MINUTE_MILLIS)}d ago"
            else -> formatDate(timestamp, now, zone, locale)
        }
    }

    /** Beyond a week, an absolute date beats vagueness ("3 Aug", or with year). */
    private fun formatDate(
        timestamp: Long,
        now: Long,
        zone: ZoneId,
        locale: Locale
    ): String {
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val sameYearAsNow =
            date.year == Instant.ofEpochMilli(now).atZone(zone).toLocalDate().year
        val pattern = if (sameYearAsNow) "d MMM" else "d MMM yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(date)
    }
}
