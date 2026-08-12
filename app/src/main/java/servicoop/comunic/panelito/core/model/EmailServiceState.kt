package servicoop.comunic.panelito.core.model

import org.json.JSONObject

data class EmailServiceState(
    val smtp: String,
    val pingLocal: String,
    val pingRemote: String,
    val timestamp: String,
    val health: EmailServiceHealth,
)

enum class EmailServiceHealth {
    OPERATIONAL,
    NO_SERVICE,
    UNKNOWN,
}

object EmailServiceStateParser {
    fun parse(raw: String, unknownValue: String): EmailServiceState {
        val source = JSONObject(raw)
        val smtp = valueOrUnknown(source, "smtp", unknownValue)
        val pingLocal = valueOrUnknown(source, "ping_local", unknownValue)
        val pingRemote = valueOrUnknown(source, "ping_remoto", unknownValue)
        val normalized = listOf(smtp, pingLocal, pingRemote).map { it.trim().lowercase() }
        val health = when {
            normalized.any { it == "desconectado" } -> EmailServiceHealth.NO_SERVICE
            normalized.any { it == "desconocido" } -> EmailServiceHealth.UNKNOWN
            else -> EmailServiceHealth.OPERATIONAL
        }
        return EmailServiceState(
            smtp = smtp,
            pingLocal = pingLocal,
            pingRemote = pingRemote,
            timestamp = source.optString("ts", ""),
            health = health,
        )
    }

    private fun valueOrUnknown(source: JSONObject, key: String, unknownValue: String): String =
        source.optString(key, unknownValue).trim().ifEmpty { unknownValue }
}
