package servicoop.comunic.panelito.core.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimestampFormatter {
    private val targetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun format(raw: String?, fallback: String = "N/D"): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        val instant = parseInstant(trimmed) ?: return trimmed
        val zoned = instant.atZone(ZoneId.systemDefault())
        return targetFormatter.format(zoned)
    }

    internal fun parseInstant(input: String): Instant? {
        val localZone = ZoneId.systemDefault()
        return try {
            Instant.parse(input)
        } catch (_: Exception) {
            try {
                val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val ldt = LocalDateTime.parse(input, fmt)
                ldt.atZone(localZone).toInstant()
            } catch (_: Exception) {
                try {
                    val ldt = LocalDateTime.parse(input, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    ldt.atZone(localZone).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}
