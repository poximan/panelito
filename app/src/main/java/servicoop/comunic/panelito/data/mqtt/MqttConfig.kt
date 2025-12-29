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
    private const val BASE = "exemys"

    // Estado REMOTO del modem, NO confundir con estado del broker local
    const val TOPIC_MODEM_CONEXION =
        "$BASE/estado/conexion_modem" // payload JSON: {"estado":"abierto|cerrado|desconocido","ts":"..."}
    const val TOPIC_GRADO =
        "$BASE/estado/grado" // payload JSON: {"porcentaje": 58.3, "total": N, "conectados": M, "ts": "..."}
    const val TOPIC_GRDS =
        "$BASE/estado/grds" // payload JSON: {"items":[{"id":11,"nombre":"...", "ultima_caida":"..."}], "ts":"..."}
    const val TOPIC_EMAIL_ESTADO =
        "$BASE/estado/email" // payload JSON: {"smtp":"conectado","ping_local":"...","ping_remoto":"...","ts":"..."}
    const val TOPIC_PROXMOX_ESTADO =
        "$BASE/estado/proxmox" // payload JSON: {"ts":"...","status":"online|offline","vms":[...],"missing":[...]}
    const val TOPIC_EMAIL_EVENT =
        "$BASE/eventos/email" // payload JSON: {"type":"email","subject":"...","ok":true,"ts":"..."}
    const val TOPIC_SERVICE_STATUS = "panelexemys/status" // payload JSON: {"status":"online|offline","ts":"...","reason":"..."}
    const val TOPIC_GE_EMAR =
        "$BASE/estado/ge_emar" // payload JSON: {"estado":"marcha|parado|desconocido","ts":"..."}

    // Publicaciones directas de charo-daemon por host (para N instancias)
    // Panelito se alimenta exclusivamente de estos topicos para la vista de charo-daemon.
    const val TOPIC_CHARODAEMON_STATUS = "charodaemon/host/+/status"
    const val TOPIC_CHARODAEMON_METRICS = "charodaemon/host/+/metrics"
    const val TOPIC_CHARITO_WHITELIST = "charito/whitelist/instances"

    const val RPC_ROOT = "app/req"

    // QoS recomendado
    const val QOS_SUBS = 1
}




