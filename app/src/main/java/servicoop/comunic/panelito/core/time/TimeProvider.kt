package servicoop.comunic.panelito.core.time

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface TimeProvider {
    fun nowUtc(): Instant
    fun epochMillis(): Long
    fun formatForPresentation(raw: String?, fallback: String = "N/D"): String
    fun sinceDescription(raw: String): String?
}

class SystemTimeProvider(
    private val clock: Clock = Clock.systemUTC(),
) : TimeProvider {
    override fun nowUtc(): Instant = clock.instant()

    override fun epochMillis(): Long = nowUtc().toEpochMilli()

    override fun formatForPresentation(raw: String?, fallback: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return fallback
        val instant = parseUtc(trimmed) ?: return trimmed
        return DISPLAY_FORMATTER.format(instant.atOffset(PRESENTATION_OFFSET))
    }

    override fun sinceDescription(raw: String): String? {
        val instant = parseUtc(raw) ?: return null
        val totalMinutes = Duration.between(instant, nowUtc()).toMinutes().coerceAtLeast(0)
        val totalHours = TimeUnit.MINUTES.toHours(totalMinutes)
        val days = TimeUnit.HOURS.toDays(totalHours)
        val hours = totalHours - TimeUnit.DAYS.toHours(days)
        val minutes = totalMinutes - TimeUnit.HOURS.toMinutes(totalHours)
        return when {
            totalMinutes < 60 -> "${totalMinutes}m"
            totalHours < 24 -> "${totalHours}h ${minutes}m"
            else -> "${days}d ${hours}h"
        }
    }

    private fun parseUtc(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrElse {
            parseLegacyUtc(value)
        }

    private fun parseLegacyUtc(value: String): Instant? {
        val formatter = if ('T' in value) {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        } else {
            LEGACY_FORMATTER
        }
        return runCatching {
            LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC)
        }.getOrNull()
    }

    companion object {
        private val PRESENTATION_OFFSET: ZoneOffset = ZoneOffset.ofHours(-3)
        private val DISPLAY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val LEGACY_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

object AppTime : TimeProvider by SystemTimeProvider()
