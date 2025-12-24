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
import org.json.JSONArray
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.core.model.ModemEstado
import servicoop.comunic.panelito.data.mqtt.MqttConfig
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class MQTTService : Service(), MqttCallbackExtended {

    companion object {
        const val ID_CANAL = "MqttChannel"
        const val NOTIFICATION_ID = 1

        private const val ACTION_PREFIX = "${BuildConfig.APPLICATION_ID}.mqtt"
        const val STATUS_ONLINE = "online"
        const val STATUS_OFFLINE = "offline"
        private const val STATUS_UNKNOWN = "unknown"
        private const val CHARO_TIMEOUT_MS = 90_000L
        private const val CHARO_TIMEOUT_TICK_MS = 30_000L

        // Broadcasts a UI
        const val ACTION_BROKER_ESTADO = "$ACTION_PREFIX.ACTION_BROKER_ESTADO"
        const val EXTRA_BROKER_ESTADO = "EXTRA_BROKER_ESTADO"

        const val ACTION_MODEM_ESTADO = "$ACTION_PREFIX.ACTION_MODEM_ESTADO"
        const val EXTRA_MODEM_ESTADO = "EXTRA_MODEM_ESTADO"

        const val ACTION_ACTUALIZAR_GRADO = "$ACTION_PREFIX.ACTION_ACTUALIZAR_GRADO"
        const val ACTION_ACTUALIZAR_GRDS = "$ACTION_PREFIX.ACTION_ACTUALIZAR_GRDS"
        const val ACTION_ERROR = "$ACTION_PREFIX.ACTION_ERROR"

        const val EXTRA_GRADO_PCT = "EXTRA_GRADO_PCT"
        const val EXTRA_GRDS_JSON = "EXTRA_GRDS_JSON"
        const val EXTRA_ERROR = "EXTRA_ERROR"

        const val ACTION_EMAIL_ESTADO = "$ACTION_PREFIX.ACTION_EMAIL_ESTADO"
        const val EXTRA_EMAIL_ESTADO = "EXTRA_EMAIL_ESTADO"
        const val ACTION_PROXMOX_ESTADO = "$ACTION_PREFIX.ACTION_PROXMOX_ESTADO"
        const val EXTRA_PROXMOX_ESTADO = "EXTRA_PROXMOX_ESTADO"
        const val ACTION_EMAIL_EVENT = "$ACTION_PREFIX.ACTION_EMAIL_EVENT"
        const val EXTRA_EMAIL_EVENT = "EXTRA_EMAIL_EVENT"
        const val EXTRA_EMAIL_EVENT_LIST = "EXTRA_EMAIL_EVENT_LIST"

        const val ACTION_CHARITO_ESTADO = "$ACTION_PREFIX.ACTION_CHARITO_ESTADO"
        const val EXTRA_CHARITO_ESTADO = "EXTRA_CHARITO_ESTADO"
        const val ACTION_BACKEND_STATUS = "$ACTION_PREFIX.ACTION_BACKEND_STATUS"
        const val EXTRA_BACKEND_STATUS = "EXTRA_BACKEND_STATUS"
        const val EXTRA_BACKEND_STATUS_TS = "EXTRA_BACKEND_STATUS_TS"

        // Pedido de estado desde UI
        const val EXTRA_SOLICITAR_ESTADO = "solicitar_estado"

        // Publicar / RPC
        const val ACTION_PUBLICAR = "${ACTION_PREFIX}.ACTION_PUBLICAR"
        const val ACTION_RPC_EMAIL_TEST = "${ACTION_PREFIX}.ACTION_RPC_EMAIL_TEST"
        const val EXTRA_TOPIC_PUBLICAR = "EXTRA_TOPIC_PUBLICAR"
        const val EXTRA_MENSAJE_PUBLICAR = "EXTRA_MENSAJE_PUBLICAR"
        const val EXTRA_QOS_PUBLICAR = "EXTRA_QOS_PUBLICAR"
        const val EXTRA_RETAINED_PUBLICAR = "EXTRA_RETAINED_PUBLICAR"
        private const val EMAIL_EVENT_BUFFER_LIMIT = 50
    }

    private val emailEvents = mutableListOf<EmailEvent>()
    private val brokerUrl: String by lazy { MqttConfig.brokerUrl(this) }
    private val clientId: String by lazy {
        val id = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        "Redirector_${id ?: "unknown"}"
    }

    private lateinit var mqttClient: MqttClient
    private var isConnected = false

    private val svcJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + svcJob)

    private var networkAvailable = AtomicBoolean(false)
    private lateinit var cm: ConnectivityManager
    private var reconnectJob: Job? = null
    private var defaultNetCallback: ConnectivityManager.NetworkCallback? = null
    private var charoTimeoutJob: Job? = null

    // Debounce / cache
    private var lastGradoPct: Double? = null
    private var lastGrdsJson: String? = null
    private var lastEmailEstado: String? = null
    private var lastProxmoxEstado: String? = null
    private var lastCharoSnapshot: String? = null
    private var lastModemEstado: ModemEstado = ModemEstado.DESCONOCIDO
    private var backendOnline: Boolean = true
    private var lastBrokerEstado: BrokerEstado = BrokerEstado.DESCONECTADO
    private var coalesceJob: Job? = null
    @Volatile private var pendingGrado: Double? = null
    @Volatile private var pendingGrds: String? = null
    private val charoHosts: MutableMap<String, CharoHostState> = mutableMapOf()
    @Volatile private var charoWhitelist: Set<String> = emptySet()
    private val charoAliasToId: MutableMap<String, String> = mutableMapOf()

    private data class CharoHostState(
        var topicId: String,
        var instanceId: String = topicId,
        var alias: String = topicId,
        var status: String = STATUS_UNKNOWN,
        var metrics: JSONObject? = null,
        var lastSeenMs: Long = System.currentTimeMillis(),
        var timeoutMs: Long = CHARO_TIMEOUT_MS
    )

    private lateinit var options: MqttConnectOptions

    override fun onCreate() {
        super.onCreate()
        options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false
            userName = MqttConfig.username(this@MQTTService)
            password = MqttConfig.password(this@MQTTService).toCharArray()
            connectionTimeout = 0
            keepAliveInterval = MqttConfig.KEEP_ALIVE_SECONDS
            maxInflight = 4
        }
        crearCanalNotificacion()
        iniciarEnPrimerPlano(getString(R.string.notification_text_broker_disconnected))

        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (hasUsableNetwork()) {
                    networkAvailable.set(true)
                    scheduleReconnect(immediate = true)
                }
            }
            override fun onLost(network: Network) {
                networkAvailable.set(hasUsableNetwork())
                if (!networkAvailable.get()) {
                    scope.launch { disconnectSafely() }
                    cancelReconnectLoop()
                    enviarBrokerEstado(BrokerEstado.DESCONECTADO)
                    actualizarNotificacion(
                        getString(R.string.notification_text_reconnecting_title),
                        getString(R.string.notification_text_reconnecting_body)
                    )
                }
            }
        }
        defaultNetCallback = cb
        cm.registerDefaultNetworkCallback(cb)
        charoTimeoutJob = scope.launch {
            while (isActive) {
                enforceCharoTimeout()
                delay(CHARO_TIMEOUT_TICK_MS)
            }
        }
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
                sendError(getString(R.string.error_publication_missing_data))
            }
            return START_STICKY
        }

        if (action == ACTION_RPC_EMAIL_TEST) {
            val params = JSONObject().apply { put("origin", "panelito") }
            sendRpcRequest("send_email_test", MqttConfig.TOPIC_EMAIL_EVENT, params)
            return START_STICKY
        }

        scheduleReconnect(immediate = true)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { defaultNetCallback?.let { cm.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        cancelReconnectLoop()
        scope.launch { disconnectSafely() }
        charoTimeoutJob?.cancel()
        charoTimeoutJob = null
        svcJob.cancel()
        actualizarNotificacion(
            getString(R.string.notification_text_service_stopped_title),
            getString(R.string.notification_text_service_stopped_body)
        )
        enviarBrokerEstado(BrokerEstado.DESCONECTADO)
    }

    // MqttCallbackExtended
    override fun connectionLost(cause: Throwable?) {
        isConnected = false
        enviarBrokerEstado(BrokerEstado.REINTENTANDO)
        val detail = cause?.message ?: getString(R.string.status_unknown)
        sendError(getString(R.string.error_connection_lost, detail))
        actualizarNotificacion(
            getString(R.string.notification_text_reconnecting_title),
            getString(R.string.notification_text_reconnecting_body)
        )
        scheduleReconnect(immediate = false)
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        isConnected = true
        enviarBrokerEstado(BrokerEstado.CONECTADO)
        val body = if (reconnect) {
            getString(R.string.notification_text_service_reconnected)
        } else {
            getString(R.string.notification_text_service_connected)
        }
        actualizarNotificacion(getString(R.string.notification_title_service), body)
        try {
            mqttClient.subscribe(MqttConfig.TOPIC_MODEM_CONEXION, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRADO, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_GRDS, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_EMAIL_ESTADO, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_PROXMOX_ESTADO, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_EMAIL_EVENT, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_SERVICE_STATUS, MqttConfig.QOS_SUBS)
            // Suscripciones directas a charo-daemon por host (N instancias)
            mqttClient.subscribe(MqttConfig.TOPIC_CHARODAEMON_STATUS, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_CHARODAEMON_METRICS, MqttConfig.QOS_SUBS)
            mqttClient.subscribe(MqttConfig.TOPIC_CHARITO_WHITELIST, MqttConfig.QOS_SUBS)
        } catch (e: Exception) {
            val detail = e.message ?: getString(R.string.status_unknown)
            sendError(getString(R.string.error_subscription, detail))
        }
        requestInitialState()
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val payload = message?.toString() ?: return
        when (topic) {
            MqttConfig.TOPIC_MODEM_CONEXION -> {
                lastModemEstado = parseModemEstado(payload)
                enviarModemEstado(lastModemEstado)
            }
            MqttConfig.TOPIC_GRADO -> {
                try {
                    val parsed = JSONObject(payload)
                    if (parsed.optString("type").equals("rpc", ignoreCase = true)) {
                        val ok = parsed.optBoolean("ok", true)
                        if (!ok) {
                            val detail = parsed.optString("error", getString(R.string.status_unknown))
                            sendError(getString(R.string.error_rpc_global_status, detail))
                        } else {
                            val pct = parsed.optJSONObject("data")?.optJSONObject("summary")?.optDouble("porcentaje", Double.NaN)
                            if (pct != null && !pct.isNaN()) {
                                pendingGrado = pct
                                coalesceUi()
                            }
                        }
                        return
                    }
                    val pct = parsed.optDouble("porcentaje", Double.NaN)
                    if (!pct.isNaN()) {
                        pendingGrado = pct
                        coalesceUi()
                    }
                } catch (e: Exception) {
                    val detail = e.message ?: getString(R.string.status_unknown)
                    sendError(getString(R.string.error_parse_grade, detail))
                }
            }
            MqttConfig.TOPIC_GRDS -> {
                pendingGrds = payload
                coalesceUi()
            }
            MqttConfig.TOPIC_EMAIL_ESTADO -> {
                lastEmailEstado = payload
                enviarEmailEstado(payload)
            }
            MqttConfig.TOPIC_PROXMOX_ESTADO -> {
                lastProxmoxEstado = payload
                enviarProxmoxEstado(payload)
            }
            MqttConfig.TOPIC_EMAIL_EVENT -> {
                try {
                    val parsed = JSONObject(payload)
                    val event = EmailEvent(
                        type = parsed.optString("type", "email"),
                        subject = parsed.optString("subject", getString(R.string.value_not_available)),
                        ok = parsed.optBoolean("ok", false),
                        timestamp = parsed.optString("ts", "")
                    )
                    registrarEmailEvent(event)
                } catch (e: Exception) {
                    val detail = e.message ?: getString(R.string.status_unknown)
                    sendError(getString(R.string.error_parse_email_event, detail))
                }
            }
            MqttConfig.TOPIC_SERVICE_STATUS -> {
                handleBackendStatus(payload)
            }
            MqttConfig.TOPIC_CHARITO_WHITELIST -> {
                handleCharoWhitelist(payload)
            }
            else -> handleCharoTopic(topic, payload)
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) { }

    private fun scheduleReconnect(immediate: Boolean) {
        if (!hasUsableNetwork()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var backoff = if (immediate) 0L else 2L
            while (isActive && !isConnected && hasUsableNetwork()) {
                try {
                    enviarBrokerEstado(BrokerEstado.CONECTANDO)
                    actualizarNotificacion(
                        getString(R.string.notification_text_connecting_title),
                        getString(R.string.notification_text_connecting_body)
                    )
                    connectOnce()
                    if (isConnected) break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    enviarBrokerEstado(BrokerEstado.ERROR)
                    val detail = e.message ?: getString(R.string.status_unknown)
                    sendError(getString(R.string.error_connection_failure, detail))
                }
                backoff = if (backoff == 0L) 2L else min(backoff * 2, MqttConfig.RECONNECT_MAX_BACKOFF_SECONDS.toLong())
                delay(backoff * 1000)
            }
        }
    }

    private fun cancelReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private suspend fun disconnectSafely() {
        try {
            if (this::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
            }
        } catch (_: Exception) { }
        isConnected = false
    }

    private fun connectOnce() {
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

    private fun hasUsableNetwork(): Boolean {
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
    }

    // UI helpers / broadcasts
    private fun actualizarNotificacion(title: String, text: String) {
        val n = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun crearCanalNotificacion() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(ID_CANAL, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
    }

    private fun iniciarEnPrimerPlano(texto: String) {
        val n = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle(getString(R.string.notification_title_service))
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        startForeground(NOTIFICATION_ID, n)
    }

    private fun enviarBrokerEstado(estado: BrokerEstado) {
        val i = Intent(ACTION_BROKER_ESTADO).apply { putExtra(EXTRA_BROKER_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarModemEstado(estado: ModemEstado) {
        val i = Intent(ACTION_MODEM_ESTADO).apply { putExtra(EXTRA_MODEM_ESTADO, estado.name) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarEmailEstado(jsonRaw: String) {
        val i = Intent(ACTION_EMAIL_ESTADO).apply { putExtra(EXTRA_EMAIL_ESTADO, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarProxmoxEstado(jsonRaw: String) {
        val i = Intent(ACTION_PROXMOX_ESTADO).apply { putExtra(EXTRA_PROXMOX_ESTADO, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarCharitoEstado(jsonRaw: String) {
        val i = Intent(ACTION_CHARITO_ESTADO).apply { putExtra(EXTRA_CHARITO_ESTADO, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun enviarBackendStatus(online: Boolean, timestamp: String?) {
        val intent = Intent(ACTION_BACKEND_STATUS).apply {
            putExtra(EXTRA_BACKEND_STATUS, if (online) STATUS_ONLINE else STATUS_OFFLINE)
            putExtra(EXTRA_BACKEND_STATUS_TS, timestamp ?: "")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun registrarEmailEvent(event: EmailEvent) {
        emailEvents.add(0, event)
        if (emailEvents.size > EMAIL_EVENT_BUFFER_LIMIT) {
            emailEvents.removeAt(emailEvents.lastIndex)
        }
        val intent = Intent(ACTION_EMAIL_EVENT).apply { putExtra(EXTRA_EMAIL_EVENT, event) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        val snapshot = Intent(ACTION_EMAIL_EVENT).apply { putExtra(EXTRA_EMAIL_EVENT_LIST, ArrayList(emailEvents)) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(snapshot)
    }

    private fun emitGrado(pct: Double) {
        val i = Intent(ACTION_ACTUALIZAR_GRADO).apply { putExtra(EXTRA_GRADO_PCT, pct) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun emitGrds(jsonRaw: String) {
        val i = Intent(ACTION_ACTUALIZAR_GRDS).apply { putExtra(EXTRA_GRDS_JSON, jsonRaw) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun sendError(msg: String) {
        val i = Intent(ACTION_ERROR).apply { putExtra(EXTRA_ERROR, msg) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(i)
    }

    private fun handleCharoTopic(topic: String?, payload: String) {
        if (topic.isNullOrBlank()) return
        when {
            topic.startsWith("charodaemon/host/") && topic.endsWith("/metrics") -> updateCharoMetrics(topic, payload)
            topic.startsWith("charodaemon/host/") && topic.endsWith("/status") -> updateCharoStatus(topic, payload)
        }
    }

    private fun handleCharoWhitelist(payload: String) {
        try {
            val json = JSONObject(payload)
            val array = json.optJSONArray("items") ?: JSONArray()
            val ids = mutableSetOf<String>()
            val newAliasMap = mutableMapOf<String, String>()

            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue

                val alias = entry.optString("alias").takeIf { it.isNotBlank() } ?: continue
                val id = entry.optString("instanceId").trim().ifEmpty { alias }

                ids += id
                newAliasMap[alias] = id

                ensureCharoPlaceholder(id, alias)

                val previousId = charoAliasToId[alias]
                if (previousId != null && previousId != id) {
                    renameCharoHost(previousId, id, alias)
                }
            }

            charoAliasToId.clear()
            charoAliasToId.putAll(newAliasMap)
            charoWhitelist = ids
            pruneCharoHostsByWhitelist()
            broadcastCharoState()
        } catch (e: Exception) {
            Log.w("MQTTService", "Error parsing charo whitelist: ${e.message}")
        }
    }

    private fun handleBackendStatus(payload: String) {
        try {
            val json = JSONObject(payload)
            val status = json.optString("status", STATUS_UNKNOWN)
            val ts = json.optString("ts", "")
            val online = status.equals(STATUS_ONLINE, true)
            backendOnline = online
            enviarBackendStatus(online, ts)
            if (online) {
                requestInitialState()
            } else {
                lastGradoPct = null
                lastGrdsJson = null
                lastEmailEstado = null
                lastProxmoxEstado = null
                lastModemEstado = ModemEstado.DESCONOCIDO
            }
        } catch (ex: Exception) {
            val detail = ex.message ?: getString(R.string.status_unknown)
            sendError(getString(R.string.error_parse_backend_status, detail))
        }
    }

    private fun ensureCharoPlaceholder(instanceId: String, alias: String) {
        val entry = charoHosts.getOrPut(instanceId) { CharoHostState(topicId = instanceId) }
        entry.topicId = instanceId
        entry.instanceId = instanceId
        entry.alias = alias
        if (entry.status == STATUS_UNKNOWN) {
            entry.status = STATUS_OFFLINE
        }
    }

    private fun renameCharoHost(oldId: String, newId: String, alias: String) {
        if (oldId == newId) return
        val entry = charoHosts.remove(oldId) ?: return
        entry.topicId = newId
        entry.instanceId = newId
        entry.alias = alias
        charoHosts[newId] = entry
    }

    private fun updateCharoMetrics(topic: String, payload: String) {
        try {
            val metricsJson = JSONObject(payload)
            val topicId = extractCharoTopicId(topic)
                ?: metricsJson.optString("instanceId").takeIf { it.isNotBlank() }
                ?: return
            val entry = charoHosts.getOrPut(topicId) { CharoHostState(topicId = topicId) }
            val instanceId = metricsJson.optString("instanceId")
            if (instanceId.isNotBlank()) {
                entry.instanceId = instanceId
            }
            if (!isCharoAllowed(entry.instanceId)) {
                charoHosts.remove(entry.topicId)
                return
            }
            markCharoHeartbeat(entry)
            if (entry.status != STATUS_ONLINE) {
                entry.status = STATUS_ONLINE
            }
            entry.metrics = metricsJson
            val timeoutSeconds = metricsJson.optLong("timeoutSeconds", 0L)
            if (timeoutSeconds > 0) {
                entry.timeoutMs = timeoutSeconds * 1000L
            }
            broadcastCharoState()
        } catch (ex: Exception) {
            val detail = ex.message ?: topic
            sendError(getString(R.string.error_parse_charo_metrics, detail))
        }
    }

    private fun updateCharoStatus(topic: String, payload: String) {
        val topicId = extractCharoTopicId(topic) ?: return
        val normalized = payload.trim().lowercase(Locale.getDefault())
        val entry = charoHosts.getOrPut(topicId) { CharoHostState(topicId = topicId) }
        if (!isCharoAllowed(entry.instanceId)) {
            charoHosts.remove(entry.topicId)
            return
        }
        markCharoHeartbeat(entry)
        entry.status = when (normalized) {
            STATUS_ONLINE -> STATUS_ONLINE
            STATUS_OFFLINE -> STATUS_OFFLINE
            else -> STATUS_UNKNOWN
        }
        broadcastCharoState()
    }

    private fun extractCharoTopicId(topic: String): String? {
        val parts = topic.split("/")
        return if (parts.size >= 3) parts[2] else null
    }

    private fun markCharoHeartbeat(entry: CharoHostState) {
        entry.lastSeenMs = System.currentTimeMillis()
    }

    private fun isCharoAllowed(instanceId: String?): Boolean {
        val whitelist = charoWhitelist
        if (whitelist.isEmpty()) return true
        val id = instanceId?.takeIf { it.isNotBlank() } ?: return true
        return whitelist.contains(id)
    }

    private fun pruneCharoHostsByWhitelist() {
        val whitelist = charoWhitelist
        if (whitelist.isEmpty()) return
        var changed = false
        val iterator = charoHosts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val instanceId = entry.value.instanceId
            if (instanceId.isBlank()) {
                continue
            }
            if (!whitelist.contains(instanceId)) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            broadcastCharoState()
        }
    }

    private fun broadcastCharoState() {
        val itemsArray = JSONArray()
        val sorted = charoHosts.values.sortedBy { it.instanceId.lowercase(Locale.getDefault()) }
        for (entry in sorted) {
            val payload = entry.metrics?.let { JSONObject(it.toString()) } ?: JSONObject()
            if (!payload.has("instanceId") || payload.optString("instanceId").isBlank()) {
                payload.put("instanceId", entry.instanceId)
            }
            payload.put("status", entry.status)
            if (!payload.has("timeoutSeconds")) {
                payload.put("timeoutSeconds", entry.timeoutMs / 1000L)
            }
            payload.put("topicId", entry.topicId)
            payload.put("alias", entry.alias)
            itemsArray.put(payload)
        }
        val wrapper = JSONObject().put("items", itemsArray)
        val snapshot = wrapper.toString()
        lastCharoSnapshot = snapshot
        enviarCharitoEstado(snapshot)
    }

    private fun enforceCharoTimeout() {
        val now = System.currentTimeMillis()
        var changed = false
        for (entry in charoHosts.values) {
            val elapsed = now - entry.lastSeenMs
            val threshold = (entry.timeoutMs.takeIf { it > 0 } ?: CHARO_TIMEOUT_MS) * 2
            if (elapsed > threshold && entry.status != STATUS_OFFLINE) {
                entry.status = STATUS_OFFLINE
                changed = true
            }
        }
        if (changed) {
            broadcastCharoState()
        }
    }

    private fun coalesceUi() {
        if (coalesceJob?.isActive == true) return
        coalesceJob = scope.launch {
            delay(800)
            pendingGrado?.let { emitGrado(it) ; pendingGrado = null }
            pendingGrds?.let { emitGrds(it) ; pendingGrds = null }
        }
    }

    private fun parseModemEstado(raw: String): ModemEstado {
        val valor = try {
            val parsed = JSONObject(raw)
            if (parsed.optString("type").equals("rpc", ignoreCase = true)) {
                val data = parsed.optJSONObject("data")
                data?.let { extractEstado(it, raw) } ?: raw
            } else {
                extractEstado(parsed, raw)
            }
        } catch (_: Exception) {
            raw
        }
        return ModemEstado.fromString(valor)
    }

    private fun extractEstado(obj: JSONObject, fallback: String): String {
        val estado = obj.optString("estado", "")
        if (estado.isNotBlank()) return estado
        val state = obj.optString("state", "")
        if (state.isNotBlank()) return state
        return fallback
    }

    private fun requestInitialState() {
        sendRpcRequest("get_global_status", MqttConfig.TOPIC_GRADO)
        sendRpcRequest("get_modem_status", MqttConfig.TOPIC_MODEM_CONEXION)
    }

    private fun sendRpcRequest(action: String, replyTo: String, params: JSONObject? = null) {
        val payload = JSONObject().apply {
            put("reply_to", replyTo)
            put("corr", UUID.randomUUID().toString())
            put("params", params ?: JSONObject())
        }.toString()
        publish("${MqttConfig.RPC_ROOT}/$action", payload, qos = 1, retained = false)
    }

    private fun publish(topic: String, message: String, qos: Int, retained: Boolean) {
        scope.launch {
            if (!this@MQTTService::mqttClient.isInitialized || !mqttClient.isConnected) {
                sendError(getString(R.string.error_not_connected_publish, topic))
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
                val detail = e.message ?: getString(R.string.status_unknown)
                sendError(getString(R.string.error_publish_topic, topic, detail))
            }
        }
    }

    private fun emitirEstadoCache() {
        lastGradoPct?.let { emitGrado(it) }
        lastGrdsJson?.let { emitGrds(it) }
        lastEmailEstado?.let { enviarEmailEstado(it) }
        lastProxmoxEstado?.let { enviarProxmoxEstado(it) }
        lastCharoSnapshot?.let { enviarCharitoEstado(it) }
        enviarBrokerEstado(if (isConnected) BrokerEstado.CONECTADO else BrokerEstado.DESCONECTADO)
    }
}
