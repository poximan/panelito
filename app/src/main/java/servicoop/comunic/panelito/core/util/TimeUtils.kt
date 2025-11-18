package servicoop.comunic.panelito.core.util

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

object TimeUtils {
    /**
     * Devuelve una descripcion adaptativa de la duracion desde el string de fecha.
     * - Si son minutos: "Xm"
     * - Si son horas: "Xh Ym"
     * - Si son dias: "Xd Yh"
     */
    fun sinceDescription(isoOrLegacy: String): String? {
        val inst = TimestampFormatter.parseInstant(isoOrLegacy) ?: return null
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
