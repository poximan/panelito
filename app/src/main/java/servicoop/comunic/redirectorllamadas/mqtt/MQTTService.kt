package servicoop.comunic.redirectorllamadas.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import servicoop.comunic.redirectorllamadas.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MQTTService : Service(), MqttCallbackExtended {

    companion object {
        const val ID_CANAL = "MqttChannel"
        const val NOMBRE_CANAL = "MQTT Service Notifications"
        const val NOTIFICATION_ID = 1

        // Broadcasts a UI (broker local)
        const val ACTION_BROKER_ESTADO = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_BROKER_ESTADO"
        const val EXTRA_BROKER_ESTADO = "EXTRA_BROKER_ESTADO" // valores de BrokerEstado.name

        // Broadcasts a UI (estado remoto modem y datos)
        const val ACTION_MODEM_ESTADO = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_MODEM_ESTADO"
        const val EXTRA_MODEM_ESTADO = "EXTRA_MODEM_ESTADO" // valores de ModemEstado.name

        const val ACTION_ACTUALIZAR_GRADO = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ACTUALIZAR_GRADO"
        const val ACTION_ACTUALIZAR_GRDS = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ACTUALIZAR_GRDS"
        const val ACTION_ERROR = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ERROR"

        const val EXTRA_GRADO_PCT = "EXTRA_GRADO_PCT"
        const val EXTRA_GRDS_JSON = "EXTRA_GRDS_JSON"
        const val EXTRA_ERROR = "EXTRA_ERROR"

        // Pedido de estado desde UI
        const val EXTRA_SOLICITAR_ESTADO = "solicitar_estado"

        // Accion opcional para publicar (se mantiene por compatibilidad)
        const val ACTION_PUBLICAR = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_PUBLICAR"
        const val EXTRA_TOPIC_PUBLICAR = "EXTRA_TOPIC_PUBLICAR"
        const val EXTRA_MENSAJE_PUBLICAR = "EXTRA_MENSAJE_PUBLICAR"
        const val EXTRA_QOS_PUBLICAR = "EXTRA_QOS_PUBLICAR"
        const val EXTRA_RETAINED_PUBLICAR = "EXTRA_RETAINED_PUBLICAR"
    }

    private val brokerUrl = MqttConfig.BROKER_URL
    private val clientId: String by lazy {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        "Redirector_${id ?: "unknown"}"
    }

    private lateinit var mqttClient: MqttClient
    private var isConnected = false

    // Ejecutor de IO para no bloquear el hilo principal
    private val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val options = MqttConnectOptions().apply {
        isCleanSession = false
        isAutomaticReconnect = true
        userName = MqttConfig.USERNAME
        password = MqttConfig.PASSWORD.toCharArray()
        connectionTimeout = 10
        keepAliveInterval = 60
    }

    // cache ultimo estado conocido
    private var lastBrokerEstado: BrokerEstado = BrokerEstado.DESCONECTADO
    private var lastModemEstado: ModemEstado = ModemEstado.DESCONECTADO
    private var lastGradoPct: Double? = null
    private var lastGrdsJson: String? = null

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        iniciarEnPrimerPlano("Desconectado del broker MQTT")
        Log.d("MQTTService", "onCreate. clientId=$clientId")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val solicitar = intent?.getBooleanExtra(EXTRA_SOLICITAR_ESTADO, false) ?: false
        val action = intent?.action

        if (solicitar) {
            emitirEstadoCache()
            return START_STICKY
        }

        if (action == ACTION_PUBLICAR) {
            val topic = intent.getStringExtra(EXTRA_TOPIC_PUBLICAR)
            val message = intent.getStringExtra(EXTRA_MENSAJE_PUBLICAR)
            val qos = intent.getIntExtra(EXTRA_QOS_PUBLICAR, 1)
            val retained = intent.getBooleanExtra(EXTRA_RETAINED_PUBLICAR, false)
            if (!topic.isNullOrBlank() && message != null) {
                publicarMensaje(topic, message, qos, retained)
            } else {
                enviarError("Faltan datos de publicacion (topic o mensaje)")
            }
            return START_STICKY
        }

        if (!this::mqttClient.isInitialized || !isConnected) conectarAsync()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { io.shutdownNow() } catch (_: Exception) {}
        try {
            if (this::mqttClient.isInitialized && mqttClient.isConnected) mqttClient.disconnect()
        } catch (_: Exception) {}
        isConnected = false
        actualizarNotificacion("Servicio MQTT detenido", "Desconectado")
        enviarBrokerEstado(BrokerEstado.DESCONECTADO)
    }

    // MqttCallbackExtended
    override fun connectionLost(cause: Throwable?) {
        isConnected = false
        enviarBrokerEstado(BrokerEstado.REINTENTANDO)
        enviarError("Conexion perdida: ${cause?.message ?: "desconocido"}")
        actualizarNotificacion("Reconectando...", "Intentando reconectar")
        // Paho gestionara la reconexion por isAutomaticReconnect=true
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        isConnected = true
        enviarBrokerEstado(BrokerEstado.CONECTADO)
        actualizarNotificacion("Servicio MQTT", if (reconnect) "Reconectado" else "Conectado")
        try {
            mqttClient.subscribe(MqttConfig.TOPIC_MODEM_CONEXION, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRADO, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRDS, MqttConfig.QOS_SUBS)
            Log.i("MQTTService", "Suscrito a topicos de estado Exemys")
        } catch (e: Exception) {
            enviarError("Error al suscribirse: ${e.message}")
        }
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val payload = String(message.payload)
        Log.d("MQTTService", "messageArrived. topic=$topic payload=${payload.take(200)}")

        when (topic) {
            MqttConfig.TOPIC_MODEM_CONEXION -> {
                val estado = parseModemEstado(payload)
                lastModemEstado = estado
                enviarModemEstado(estado)
            }
            MqttConfig.TOPIC_GRADO -> {
                try {
                    val o = JSONObject(payload)
                    val pct = o.optDouble("porcentaje", Double.NaN)
                    if (!pct.isNaN()) {
                        lastGradoPct = pct
                        enviarGrado(pct)
                    }
                } catch (e: Exception) {
                    enviarError("Error parseando grado: ${e.message}")
                }
            }
            MqttConfig.TOPIC_GRDS -> {
                lastGrdsJson = payload
                enviarGrds(payload)
            }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        Log.v("MQTTService", "deliveryComplete id=${token?.messageId}")
    }

    // Conexion
    private fun conectarAsync() {
        io.execute {
            try {
                enviarBrokerEstado(BrokerEstado.CONECTANDO)
                actualizarNotificacion("Conectando...", "Estableciendo conexion MQTT")
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                mqttClient.setCallback(this)
                mqttClient.connect(options)
                // connectComplete se invoca tras connect si la conexion fue exitosa
            } catch (e: MqttException) {
                isConnected = false
                enviarBrokerEstado(BrokerEstado.ERROR)
                enviarError("Error de conexion MQTT: ${e.message}")
                actualizarNotificacion("Error de conexion", "Revisar parametros")
            } catch (e: Exception) {
                isConnected = false
                enviarBrokerEstado(BrokerEstado.ERROR)
                enviarError("Error inesperado: ${e.message}")
                actualizarNotificacion("Error inesperado", "Ver logs")
            }
        }
    }

    // Publicacion opcional
    private fun publicarMensaje(topic: String, message: String, qos: Int, retained: Boolean) {
        io.execute {
            if (!this::mqttClient.isInitialized || !mqttClient.isConnected) {
                enviarError("No conectado al broker. No se publico en $topic")
                return@execute
            }
            try {
                val mqttMessage = MqttMessage(message.toByteArray()).apply {
                    this.qos = qos
                    isRetained = retained
                }
                mqttClient.publish(topic, mqttMessage)
                Log.i("MQTTService", "Publicado en '$topic': ${message.take(80)}... (qos=$qos retained=$retained)")
            } catch (e: Exception) {
                enviarError("Error publicando en $topic: ${e.message}")
            }
        }
    }

    // Notificacion
    private fun crearCanalNotificacion() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(ID_CANAL, NOMBRE_CANAL, NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun iniciarEnPrimerPlano(texto: String) {
        val n = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle("Servicio MQTT")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }

    private fun actualizarNotificacion(title: String, text: String) {
        val n = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, n)
    }

    // Broadcast helpers
    private fun enviarBrokerEstado(estado: BrokerEstado) {
        lastBrokerEstado = estado
        val i = Intent(ACTION_BROKER_ESTADO).apply { putExtra(EXTRA_BROKER_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
        Log.d("MQTTService", "Broker estado: ${estado.name}")
    }

    private fun enviarModemEstado(estado: ModemEstado) {
        val i = Intent(ACTION_MODEM_ESTADO).apply { putExtra(EXTRA_MODEM_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarGrado(pct: Double) {
        val i = Intent(ACTION_ACTUALIZAR_GRADO).apply { putExtra(EXTRA_GRADO_PCT, pct) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarGrds(jsonRaw: String) {
        val i = Intent(ACTION_ACTUALIZAR_GRDS).apply { putExtra(EXTRA_GRDS_JSON, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarError(msg: String) {
        val i = Intent(ACTION_ERROR).apply { putExtra(EXTRA_ERROR, msg) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
        Log.e("MQTTService", msg)
    }

    private fun emitirEstadoCache() {
        enviarBrokerEstado(lastBrokerEstado)
        enviarModemEstado(lastModemEstado)
        lastGradoPct?.let { enviarGrado(it) }
        lastGrdsJson?.let { enviarGrds(it) }
    }

    // Parser de estado del modem compatible con texto plano y JSON {"estado":"conectado"}
    private fun parseModemEstado(payload: String): ModemEstado {
        val raw = payload.trim()
        val valor = if (raw.startsWith("{") && raw.endsWith("}")) {
            try {
                val obj = JSONObject(raw)
                obj.optString("estado", raw)
            } catch (_: Exception) {
                raw
            }
        } else {
            raw
        }
        return if (valor.equals("conectado", ignoreCase = true)) {
            ModemEstado.CONECTADO
        } else {
            ModemEstado.DESCONECTADO
        }
    }
}