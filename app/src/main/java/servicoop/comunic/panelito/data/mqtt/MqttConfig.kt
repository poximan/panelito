package servicoop.comunic.panelito.data.mqtt

import android.content.Context
import servicoop.comunic.panelito.R

object MqttConfig {
    // Conexion
    fun brokerUrl(context: Context): String = context.getString(R.string.mqtt_broker_url)
    fun username(context: Context): String = context.getString(R.string.mqtt_broker_username)
    fun password(context: Context): String = context.getString(R.string.mqtt_broker_password)

    // Ajustes energeticos
    // Mantener 300s suele balancear NAT timeouts vs. pings (tuneable segun carrier).
    const val KEEP_ALIVE_SECONDS = 300

    // Backoff maximo entre reintentos cuando hay red pero falla el broker.
    const val RECONNECT_MAX_BACKOFF_SECONDS = 120

    // Convencion de topicos (publicados por el server Python)
    private const val BASE = "lechuza-server"

    // Estado REMOTO del modem, NO confundir con estado del broker local
    const val TOPIC_MODEM_CONEXION =
        "$BASE/router/status" // payload JSON: {"estado":"abierto|cerrado|desconocido","ts":"..."}
    const val TOPIC_GRADO =
        "$BASE/modbus/grd/summary" // payload JSON: {"porcentaje": 58.3, "total": N, "conectados": M, "ts": "..."}
    const val TOPIC_GRDS =
        "$BASE/modbus/grd/disconnected" // payload JSON: {"items":[{"id":11,"nombre":"...", "ultima_caida":"..."}], "ts":"..."}
    const val TOPIC_EMAIL_ESTADO =
        "$BASE/email/status" // payload JSON: {"smtp":"conectado","ping_local":"...","ping_remoto":"...","ts":"..."}
    const val TOPIC_PROXMOX_ESTADO =
        "$BASE/pve/status" // payload JSON: {"ts":"...","status":"online|offline","vms":[...],"missing":[...]}
    const val TOPIC_EMAIL_EVENT =
        "$BASE/email/event" // payload JSON: {"type":"email","subject":"...","ok":true,"ts":"..."}
    const val TOPIC_SERVICE_STATUS = "panelexemys/status" // payload JSON: {"status":"online|offline","ts":"...","reason":"..."}
    const val TOPIC_GE_ESTIVARIZ =
        "$BASE/modbus/ge/edif-estivariz/status" // payload JSON: {"edificio":"edif-estivariz","interruptor_linea":{"estado":"abierto|cerrado","bit":0|1},"ts":"..."}
    const val TOPIC_GE_FONTANA =
        "$BASE/modbus/ge/edif-fontana/status" // payload JSON: {"edificio":"edif-fontana","interruptor_linea":{"estado":"abierto|cerrado","bit":0|1},"interruptor_grupo":{"estado":"abierto|cerrado","bit":0|1},"ts":"..."}

    // Estado consolidado de charo-daemon publicado por charito-service.
    // Panelito no consume topicos directos de charo-daemon.
    const val TOPIC_CHARITO_STATE = "charito/state"

    const val RPC_REQ_ROOT = "lechuza-server/rpc/req"
    const val RPC_RES_ROOT = "lechuza-server/rpc/res"

    fun rpcResponseSubscription(clientId: String): String = "$RPC_RES_ROOT/$clientId/+"

    fun rpcResponseTopic(clientId: String, corr: String): String = "$RPC_RES_ROOT/$clientId/$corr"

    // QoS recomendado
    const val QOS_SUBS = 1
}




