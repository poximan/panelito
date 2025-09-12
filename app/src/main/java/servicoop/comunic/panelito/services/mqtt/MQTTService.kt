package servicoop.comunic.panelito.services.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.BuildConfig
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.ModemEstado
import servicoop.comunic.panelito.data.mqtt.MqttConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MQTTService : Service(), MqttCallbackExtended {

    companion object {
        const val ID_CANAL = "MqttChannel"
        const val NOMBRE_CANAL = "MQTT Service Notifications"
        const val NOTIFICATION_ID = 1

        // Prefijo de acciones derivado del applicationId para evitar strings magicos
        private const val ACTION_PREFIX = "${BuildConfig.APPLICATION_ID}.mqtt"

        // Broadcasts a UI (broker local)
        const val ACTION_BROKER_ESTADO = "$ACTION_PREFIX.ACTION_BROKER_ESTADO"
        const val EXTRA_BROKER_ESTADO = "EXTRA_BROKER_ESTADO" // valores de BrokerEstado.name

        // Broadcasts a UI (estado remoto modem y datos)
        const val ACTION_MODEM_ESTADO = "$ACTION_PREFIX.ACTION_MODEM_ESTADO"
        const val EXTRA_MODEM_ESTADO = "EXTRA_MODEM_ESTADO" // valores de ModemEstado.name

        const val ACTION_ACTUALIZAR_GRADO = "$ACTION_PREFIX.ACTION_ACTUALIZAR_GRADO"
        const val ACTION_ACTUALIZAR_GRDS  = "$ACTION_PREFIX.ACTION_ACTUALIZAR_GRDS"
        const val ACTION_ERROR            = "$ACTION_PREFIX.ACTION_ERROR"

        const val EXTRA_GRADO_PCT = "EXTRA_GRADO_PCT"
        const val EXTRA_GRDS_JSON = "EXTRA_GRDS_JSON"
        const val EXTRA_ERROR     = "EXTRA_ERROR"

        // Pedido de estado desde UI
        const val EXTRA_SOLICITAR_ESTADO = "solicitar_estado"

        // Accion opcional para publicar (se mantiene por compatibilidad)
        const val ACTION_PUBLICAR = "$ACTION_PREFIX.ACTION_PUBLICAR"
        const val EXTRA_TOPIC_PUBLICAR     = "EXTRA_TOPIC_PUBLICAR"
        const val EXTRA_MENSAJE_PUBLICAR   = "EXTRA_MENSAJE_PUBLICAR"
        const val EXTRA_QOS_PUBLICAR       = "EXTRA_QOS_PUBLICAR"
        const val EXTRA_RETAINED_PUBLICAR  = "EXTRA_RETAINED_PUBLICAR"
    }

    private val brokerUrl = MqttConfig.BROKER_URL
    private val clientId: String by lazy {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        "Redirector_${id ?: "unknown"}"
    }

    private lateinit var mqttClient: MqttClient
    private var isConnected = false

    // ---- Optimización de hilos: corutinas en pool IO compartido
    private val svcJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + svcJob)

    // ---- Consciencia de red
    private var networkAvailable = AtomicBoolean(false)
    private lateinit var cm: ConnectivityManager
    private var reconnectJob: Job? = null

    // ---- Debounce de emisiones a UI
    private var lastGradoPct: Double? = null
    private var lastGrdsJson: String? = null
    private var lastModemEstado: ModemEstado = ModemEstado.DESCONECTADO
    private var lastBrokerEstado: BrokerEstado = BrokerEstado.DESCONECTADO
    private var coalesceJob: Job? = null
    private @Volatile var pendingGrado: Double? = null
    private @Volatile var pendingGrds: String? = null

    private val options = MqttConnectOptions().apply {
        isCleanSession = true
        // Desactivamos el auto-reconnect del cliente para tomar control y ahorrar bateria.
        isAutomaticReconnect = false
        userName = MqttConfig.USERNAME
        password = MqttConfig.PASSWORD.toCharArray()
        connectionTimeout = 0
        keepAliveInterval = MqttConfig.KEEP_ALIVE_SECONDS
        maxInflight = 4 // bajo para bajar uso de RAM/CPU en cliente movil
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        iniciarEnPrimerPlano("Desconectado del broker MQTT")

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Registramos callback de red: evitamos reconectar a ciegas sin conectividad
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasUsableNetwork()) {
                    networkAvailable.set(true)
                    Log.d("MQTTService", "Red disponible -> intentar conectar")
                    scheduleReconnect(immediate = true)
                }
            }
            override fun onLost(network: Network) {
                // Cortamos consumo inmediatamente si se pierde red
                networkAvailable.set(hasUsableNetwork())
                if (!networkAvailable.get()) {
                    Log.d("MQTTService", "Red perdida -> desconectar y pausar reintentos")
                    scope.launch { disconnectSafely() }
                    cancelReconnectLoop()
                    enviarBrokerEstado(BrokerEstado.DESCONECTADO)
                    actualizarNotificacion("Sin conexion de red", "Esperando red...")
                }
            }
        })
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
                publish(topic, message, qos, retained)
            } else {
                sendError("Faltan datos de publicacion (topic o mensaje)")
            }
            return START_STICKY
        }

        // Primer arranque o reintento
        scheduleReconnect(immediate = true)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { cm.unregisterNetworkCallback(ConnectivityManager.NetworkCallback()) } catch (_: Exception) {}
        cancelReconnectLoop()
        scope.launch { disconnectSafely() }
        svcJob.cancel()
        actualizarNotificacion("Servicio MQTT detenido", "Desconectado")
        enviarBrokerEstado(BrokerEstado.DESCONECTADO)
    }

    // ---------- MqttCallbackExtended
    override fun connectionLost(cause: Throwable?) {
        isConnected = false
        enviarBrokerEstado(BrokerEstado.REINTENTANDO)
        sendError("Conexion perdida: ${cause?.message ?: "desconocido"}")
        actualizarNotificacion("Reconectando...", "Intentando reconectar")
        scheduleReconnect(immediate = false)
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        isConnected = true
        enviarBrokerEstado(BrokerEstado.CONECTADO)
        actualizarNotificacion("Servicio MQTT", if (reconnect) "Reconectado" else "Conectado")
        try {
            mqttClient.subscribe(MqttConfig.TOPIC_MODEM_CONEXION, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRADO, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRDS, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(topicSyncSnapshot(), MqttConfig.QOS_SUBS)

            solicitarSnapshot()
            Log.i("MQTTService", "Suscripto a topicos y snapshot")
        } catch (e: Exception) {
            sendError("Error al suscribirse: ${e.message}")
        }
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        if (topic == null || message == null) return
        val payload = String(message.payload)

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
                        // Coalesce: acumulamos y emitimos en lote cada ~800ms
                        pendingGrado = pct
                        coalesceUi()
                    }
                } catch (e: Exception) {
                    sendError("Error parseando grado: ${e.message}")
                }
            }
            MqttConfig.TOPIC_GRDS -> {
                pendingGrds = payload
                coalesceUi()
            }
            topicSyncSnapshot() -> {
                try {
                    val o = JSONObject(payload)
                    o.optJSONObject("modem")?.let { m ->
                        val estado = parseModemEstado(m.toString())
                        lastModemEstado = estado
                        enviarModemEstado(estado)
                    }
                    o.optJSONObject("grado")?.let { g ->
                        val pct = g.optDouble("porcentaje", Double.NaN)
                        if (!pct.isNaN()) {
                            pendingGrado = pct
                        }
                    }
                    o.optJSONObject("grds")?.let { gr ->
                        pendingGrds = gr.toString()
                    }
                    coalesceUi()
                } catch (e: Exception) {
                    sendError("Error parseando snapshot: ${e.message}")
                }
            }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        // sin-op: no necesitamos nada aqui
    }

    // ---------- Conexion (con backoff y consciencia de red)

    private fun scheduleReconnect(immediate: Boolean) {
        if (!networkAvailable.get()) return
        if (reconnectJob?.isActive == true) return

        reconnectJob = scope.launch {
            var backoff = if (immediate) 0L else 2L
            while (isActive && !isConnected && networkAvailable.get()) {
                try {
                    enviarBrokerEstado(BrokerEstado.CONECTANDO)
                    actualizarNotificacion("Conectando...", "Estableciendo conexion MQTT")
                    connectOnce()
                    // Si conecto, salgo del loop
                    if (isConnected) break
                } catch (e: CancellationException) {
                    // MUY IMPORTANTE: propagar la cancelacion para no seguir programando reintentos
                    throw e
                } catch (e: Exception) {
                    enviarBrokerEstado(BrokerEstado.ERROR)
                    sendError("Fallo de conexion: ${e.message}")
                }

                backoff = if (backoff == 0L) 2L
                else min(backoff * 2, MqttConfig.RECONNECT_MAX_BACKOFF_SECONDS.toLong())

                val sleep = backoff
                Log.d("MQTTService", "Reintento en ${sleep}s")
                delay(sleep * 1000) // delay es cancelable
            }
        }
    }

    private fun cancelReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private suspend fun connectOnce() {
        // Paho maneja sus propios hilos internos; esta llamada es bloqueante.
        mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
        mqttClient.setCallback(this)
        try {
            mqttClient.connect(options)
            isConnected = mqttClient.isConnected
        } catch (e: MqttException) {
            isConnected = false
            throw e
        }
    }

    private suspend fun disconnectSafely() {
        try {
            if (this::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (_: Exception) {
        } finally {
            isConnected = false
        }
    }

    private fun hasUsableNetwork(): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        // Permitimos celular y wifi; podrias filtrar metered si quisieras.
        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
        return ok
    }

    // ---------- Publicacion (opcional)
    private fun publish(topic: String, message: String, qos: Int, retained: Boolean) {
        scope.launch {
            if (!this@MQTTService::mqttClient.isInitialized || !mqttClient.isConnected) {
                sendError("No conectado al broker. No se publico en $topic")
                return@launch
            }
            try {
                val mqttMessage = MqttMessage(message.toByteArray()).apply {
                    this.qos = qos
                    isRetained = retained
                }
                mqttClient.publish(topic, mqttMessage)
                Log.i("MQTTService", "Publicado en '$topic': ${message.take(80)}... (qos=$qos retained=$retained)")
            } catch (e: Exception) {
                sendError("Error publicando en $topic: ${e.message}")
            }
        }
    }

    // ---------- Notificacion
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

    // ---------- Broadcast helpers (con cache)
    private fun enviarBrokerEstado(estado: BrokerEstado) {
        lastBrokerEstado = estado
        val i = Intent(ACTION_BROKER_ESTADO).apply { putExtra(EXTRA_BROKER_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarModemEstado(estado: ModemEstado) {
        lastModemEstado = estado
        val i = Intent(ACTION_MODEM_ESTADO).apply { putExtra(EXTRA_MODEM_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarGrado(pct: Double) {
        lastGradoPct = pct
        val i = Intent(ACTION_ACTUALIZAR_GRADO).apply { putExtra(EXTRA_GRADO_PCT, pct) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarGrds(jsonRaw: String) {
        lastGrdsJson = jsonRaw
        val i = Intent(ACTION_ACTUALIZAR_GRDS).apply { putExtra(EXTRA_GRDS_JSON, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun sendError(msg: String) {
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
            } catch (_: Exception) { raw }
        } else raw
        return if (valor.equals("conectado", ignoreCase = true)) {
            ModemEstado.CONECTADO
        } else {
            ModemEstado.DESCONECTADO
        }
    }

    // Plan B: canales de sincronizacion por cliente
    private fun topicSyncRequest(): String = "exemys/app/$clientId/sync/request"
    private fun topicSyncSnapshot(): String = "exemys/app/$clientId/sync/snapshot"

    // Plan B: solicita snapshot actual al backend
    private fun solicitarSnapshot() {
        val payload = JSONObject().apply {
            put("tipo", "sync_request")
            put("client_id", clientId)
            put("timestamp", System.currentTimeMillis())
        }.toString()
        publish(topicSyncRequest(), payload, qos = 1, retained = false)
    }

    // ---- Coalescing: agrupa emisiones frecuentes en ~800ms para ahorrar CPU/UI wakes
    private fun coalesceUi() {
        if (coalesceJob?.isActive == true) return
        coalesceJob = scope.launch {
            delay(800) // ventana de coalescencia
            pendingGrado?.let {
                enviarGrado(it)
                pendingGrado = null
            }
            pendingGrds?.let {
                enviarGrds(it)
                pendingGrds = null
            }
        }
    }
}