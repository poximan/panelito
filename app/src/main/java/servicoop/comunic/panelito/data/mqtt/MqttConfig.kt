package servicoop.comunic.panelito.data.mqtt

object MqttConfig {
    // Conexion
    const val BROKER_URL = "ssl://a4f7b92509244483b210b9f1f69bcc37.s1.eu.hivemq.cloud:8883"
    const val USERNAME = "comunicaciones"
    const val PASSWORD = "comuniC4ciones"

    // Ajustes energeticos
    // Mantener 300s suele balancear NAT timeouts vs. pings (tuneable segun carrier).
    const val KEEP_ALIVE_SECONDS = 300

    // Backoff maximo entre reintentos cuando hay red pero falla el broker.
    const val RECONNECT_MAX_BACKOFF_SECONDS = 120

    // Convencion de topicos (publicados por el server Python)
    private const val BASE = "exemys"

    // Estado REMOTO del modem, NO confundir con estado del broker local
    const val TOPIC_MODEM_CONEXION =
        "$BASE/estado/conexion_modem" // payload JSON: {"estado":"conectado","ts":"..."}
    const val TOPIC_GRADO =
        "$BASE/estado/grado" // payload JSON: {"porcentaje": 58.3, "total": N, "conectados": M, "ts": "..."}
    const val TOPIC_GRDS =
        "$BASE/estado/grds" // payload JSON: {"items":[{"id":11,"nombre":"...", "ultima_caida":"..."}], "ts":"..."}
    const val TOPIC_EMAIL_ESTADO =
        "$BASE/estado/email" // payload JSON: {"smtp":"conectado","ping_local":"...","ping_remoto":"...","ts":"..."}

    const val RPC_ROOT = "app/req"

    // QoS recomendado
    const val QOS_SUBS = 1
}
