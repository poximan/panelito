package servicoop.comunic.panelito.data.mqtt

import servicoop.comunic.panelito.BuildConfig

object MqttConfig {
    // Conexion
    val brokerUrl: String = BuildConfig.PANELITO_MQTT_BROKER_URL
    val username: String = BuildConfig.PANELITO_MQTT_USERNAME
    val password: String = BuildConfig.PANELITO_MQTT_PASSWORD

    // Ajustes energeticos
    // Mantener 300s suele balancear NAT timeouts vs. pings (tuneable segun carrier).
    const val KEEP_ALIVE_SECONDS = 300

    // Backoff maximo entre reintentos cuando hay red pero falla el broker.
    const val RECONNECT_MAX_BACKOFF_SECONDS = 120

    // Contrato MQTT versionado publicado por lechuza-server.
    private const val BASE = "lechu/v1"

    // Estado REMOTO del modem, NO confundir con estado del broker local
    const val TOPIC_MODEM_CONEXION =
        "$BASE/modem/status" // payload JSON: {"estado":"abierto|cerrado|desconocido","ts":"..."}
    const val TOPIC_GRADO =
        "$BASE/exemys/grd/summary" // payload JSON: {"porcentaje": 58.3, "total": N, "conectados": M, "ts": "..."}
    const val TOPIC_GRDS =
        "$BASE/exemys/grd/disconnected" // payload JSON: {"items":[{"id":11,"nombre":"...", "ultima_caida":"..."}], "ts":"..."}
    const val TOPIC_EMAIL_ESTADO =
        "$BASE/email/status" // payload JSON: {"smtp":"conectado","ping_local":"...","ping_remoto":"...","ts":"..."}
    const val TOPIC_PROXMOX_ESTADO =
        "$BASE/proxmox/status" // payload JSON: {"ts":"...","status":"online|offline","vms":[...],"missing":[...]}
    const val TOPIC_EMAIL_EVENT =
        "$BASE/email/event" // payload JSON: {"type":"email","subject":"...","ok":true,"ts":"..."}
    const val TOPIC_SERVICE_STATUS = "$BASE/services/lechu/status" // payload JSON: {"status":"online|offline","ts":"...","reason":"..."}
    const val TOPIC_GE_ESTIVARIZ =
        "$BASE/generators/edif-estivariz/status" // payload JSON: {"edificio":"edif-estivariz","interruptor_linea":{"estado":"abierto|cerrado","bit":0|1},"ts":"..."}
    const val TOPIC_GE_FONTANA =
        "$BASE/generators/edif-fontana/status" // payload JSON: {"edificio":"edif-fontana","interruptor_linea":{"estado":"abierto|cerrado","bit":0|1},"interruptor_grupo":{"estado":"abierto|cerrado","bit":0|1},"ts":"..."}

    // Estado consolidado de charo-daemon publicado por charito-service.
    // Panelito no consume topicos directos de charo-daemon.
    const val TOPIC_CHARITO_STATE = "$BASE/charito/status"

    const val RPC_REQ_ROOT = "$BASE/rpc/request"
    const val RPC_RES_ROOT = "$BASE/rpc/response"

    fun rpcResponseSubscription(clientId: String): String = "$RPC_RES_ROOT/$clientId/+"

    fun rpcResponseTopic(clientId: String, corr: String): String = "$RPC_RES_ROOT/$clientId/$corr"

    // QoS recomendado
    const val QOS_SUBS = 1
}




