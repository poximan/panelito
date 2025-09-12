package servicoop.comunic.panelito.core.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object TimeUtils {
    /**
     * Intenta parsear varios formatos:
     * - ISO-8601 con zona u offset (Instant.parse)
     * - "yyyy-MM-dd HH:mm:ss" sin zona -> interpreta en zona local del dispositivo
     * - "yyyy-MM-dd'T'HH:mm:ss" sin zona -> interpreta en zona local del dispositivo
     */
    private fun parseToInstant(input: String): Instant? {
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

    /**
     * Devuelve una descripcion adaptativa de la duracion desde el string de fecha.
     * - Si son minutos: "Xm"
     * - Si son horas: "Xh Ym"
     * - Si son dias: "Xd Yh"
     */
    fun sinceDescription(isoOrLegacy: String): String? {
        val inst = parseToInstant(isoOrLegacy) ?: return null
        val dur = Duration.between(inst, Instant.now())
        val totalMin = dur.toMinutes().coerceAtLeast(0)
        val totalHours = TimeUnit.MINUTES.toHours(totalMin)
        val days = TimeUnit.HOURS.toDays(totalHours)
        val hours = totalHours - TimeUnit.DAYS.toHours(days)
        val minutes = totalMin - TimeUnit.HOURS.toMinutes(totalHours)

        return when {
            totalMin < 60 -> "${totalMin}m"
            totalHours < 24 -> "${totalHours}h ${minutes}m"
            else -> "${days}d ${hours}h"
        }
    }
}
