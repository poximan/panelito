package servicoop.comunic.redirectorllamadas.mqtt

object MqttConfig {
    // Conexion
    const val BROKER_URL = "mibroker"
    const val USERNAME = "usr"
    const val PASSWORD = "contr"

    // Convencion de topicos (publicados por el server Python)
    private const val BASE = "exemys"
    // Estado REMOTO del modem, NO confundir con estado del broker local
    const val TOPIC_MODEM_CONEXION = "$BASE/estado/conexion_modem" // payload: "conectado" | "desconectado"
    const val TOPIC_GRADO = "$BASE/estado/grado"                   // payload: {"porcentaje": 58.3}
    const val TOPIC_GRDS = "$BASE/estado/grds"                     // payload: {"items":[{"id":11,"nombre":"...", "ultima_caida":"2025-08-19T12:19:01Z"}]}

    // QoS recomendado
    const val QOS_SUBS = 1
}