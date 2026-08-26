package com.sunny.notificationlog.util

import com.sunny.notificationlog.data.NotificationEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RFC 4180 CSV building for the Pro export (phase 7).
 *
 * Pure functions of their inputs — no Android types — so the escaping rules
 * are unit-testable on the JVM. Escaping is the entire hard part of CSV:
 * any field containing a comma, quote, or newline must be wrapped in quotes
 * with internal quotes doubled, otherwise the file silently corrupts when a
 * notification body contains "Hello, world" or an OTP split across lines.
 */
object Csv {

    private val ISO_LOCAL = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    /** Column header row; names match NotificationEntity fields where possible. */
    const val HEADER = "id,package_name,app_label,title,text,timestamp_ms,posted_at"

    /**
     * Escapes one field per RFC 4180: wrap in double quotes if it contains a
     * comma, quote, carriage return, or line feed; double embedded quotes.
     */
    fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }

    /** Builds the complete export file: header + rows, oldest first (caller orders). */
    fun build(notifications: List<NotificationEntity>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append("\r\n")
        for (n in notifications) {
            sb.append(n.id).append(',')
                .append(escape(n.packageName)).append(',')
                .append(escape(n.appLabel)).append(',')
                .append(escape(n.title)).append(',')
                .append(escape(n.text)).append(',')
                .append(n.timestamp).append(',')
                // Human-readable local time alongside raw millis — the millis
                // column keeps the file lossless for future re-imports.
                .append(ISO_LOCAL.format(Instant.ofEpochMilli(n.timestamp)))
            sb.append("\r\n")
        }
        return sb.toString()
    }
}
