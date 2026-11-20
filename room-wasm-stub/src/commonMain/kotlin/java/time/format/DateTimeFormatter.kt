package java.time.format

import java.time.Instant
import java.time.ZoneId

class DateTimeFormatter {
    fun withZone(zone: ZoneId): DateTimeFormatter = this
    fun format(instant: Instant): String = "2026-11-19 00:00:00"
    companion object {
        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter()
    }
}
