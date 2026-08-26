package com.sunny.notificationlog.util

import com.sunny.notificationlog.data.NotificationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests for the CSV escaping rules (phase 7 Pro feature). CSV
 * corruption is silent, so the RFC 4180 edge cases are worth pinning down.
 */
class CsvTest {

    private fun entity(
        id: Long = 1,
        packageName: String = "com.example.app",
        appLabel: String = "Example",
        title: String = "Hello",
        text: String = "World",
        timestamp: Long = 1_700_000_000_000
    ) = NotificationEntity(
        id = id,
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        timestamp = timestamp
    )

    @Test
    fun escape_plainField_leftUntouched() {
        assertEquals("Hello world", Csv.escape("Hello world"))
    }

    @Test
    fun escape_comma_getsQuoted() {
        assertEquals("\"Hello, world\"", Csv.escape("Hello, world"))
    }

    @Test
    fun escape_quotes_getDoubledAndWrapped() {
        // A body containing quotes: He said "hi"
        assertEquals("\"He said \"\"hi\"\"\"", Csv.escape("He said \"hi\""))
    }

    @Test
    fun escape_newlines_getQuoted() {
        assertEquals("\"line1\nline2\"", Csv.escape("line1\nline2"))
    }

    @Test
    fun build_emptyList_producesHeaderOnly() {
        val csv = Csv.build(emptyList())
        assertEquals("id,package_name,app_label,title,text,timestamp_ms,posted_at\r\n", csv)
    }

    @Test
    fun build_rowWithMessyText_staysOneRow() {
        val csv = Csv.build(
            listOf(entity(text = "Code: 48,2913 \"fast\"\nsecond line"))
        )
        val lines = csv.trimEnd('\r', '\n').split("\r\n")
        // The embedded comma/quote/newline must NOT create extra rows.
        assertEquals(2, lines.size) // header + one data row

        val dataRow = lines[1]
        // Messy text opens as one quoted cell; internal quotes are doubled;
        // the row still ends with the two timestamp columns.
        assertTrue(dataRow.startsWith("1,com.example.app,Example,Hello,\"Code: 48,2913 \"\"fast\"\"\nsecond line\",1700000000000,"))
        val expectedIso = java.time.format.DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(1_700_000_000_000))
        assertTrue(dataRow.endsWith(expectedIso))
    }
}
