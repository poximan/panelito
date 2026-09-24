package servicoop.comunic.panelito.services.mqtt

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import servicoop.comunic.panelito.BuildConfig
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.core.model.GeEstado
import servicoop.comunic.panelito.core.model.ModemEstado
import servicoop.comunic.panelito.core.model.MobileReleaseState
import servicoop.comunic.panelito.core.model.WakeOnLanState
import servicoop.comunic.panelito.core.model.WakeOnLanStatus
import servicoop.comunic.panelito.data.datastore.SettingsDataStore
import servicoop.comunic.panelito.data.mqtt.MqttConfig
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

class MqttSession(context: Context) : MqttCallbackExtended {
    private val appContext = context.applicationContext
    private val settings = SettingsDataStore(appContext)
    private val storageScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stateStore = MutableStateFlow(
        MqttState(
            geStates = mapOf(
                GE_EDIF_ESTIVARIZ to GeEstado.DESCONOCIDO,
                GE_EDIF_FONTANA to GeEstado.DESCONOCIDO,
            ),
        ),
    )
    val state: StateFlow<MqttState> = stateStore.asStateFlow()

    private var scope: CoroutineScope? = null
    private var connectionJob: Job? = null
    private var client: MqttClient? = null
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var wakeOnLanCorrelation: String? = null
    private var wakeOnLanTimeoutJob: Job? = null
    private val clientId: String by lazy {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: UUID.randomUUID().toString()
        "Panelito_$androidId"
    }

    fun start() {
        if (connectionJob?.isActive == true) return
        val newScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope = newScope
        newScope.launch {
            settings.getEmailEvents().first().let { events ->
                stateStore.update { it.copy(emailEvents = events) }
            }
        }
        newScope.launch {
            val omissions = settings.getMobileUpdateOmissions().first()
            stateStore.update { state ->
                state.copy(mobileRelease = state.mobileRelease.copy(omissionsUsed = omissions))
            }
        }
        connectionJob = newScope.launch {
            var backoffSeconds = 2L
            while (isActive) {
                update { it.copy(broker = BrokerEstado.CONECTANDO, error = null) }
                try {
                    connectOnce()
                    backoffSeconds = 2L
                    while (isActive && client?.isConnected == true) delay(1_000L)
                } catch (error: Exception) {
                    update {
                        it.copy(
                            broker = BrokerEstado.REINTENTANDO,
                            error = error.message ?: error.javaClass.simpleName,
                        )
                    }
                } finally {
                    disconnectClient()
                }
                if (isActive) {
                    delay(backoffSeconds * 1_000L)
                    backoffSeconds = min(backoffSeconds * 2L, MqttConfig.RECONNECT_MAX_BACKOFF_SECONDS.toLong())
                }
            }
        }
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        wakeOnLanCorrelation = null
        wakeOnLanTimeoutJob?.cancel()
        wakeOnLanTimeoutJob = null
        disconnectClient()
        scope?.cancel()
        scope = null
        update {
            it.copy(
                broker = BrokerEstado.DESCONECTADO,
                wakeOnLan = WakeOnLanState(),
            )
        }
    }

    fun requestEmailEvents() {
        val runningScope = scope ?: return
        runningScope.launch {
            val mqttClient = client ?: return@launch
            if (!mqttClient.isConnected) return@launch
            val correlation = UUID.randomUUID().toString()
            val replyTopic = MqttConfig.rpcResponseTopic(clientId, correlation)
            val response = CompletableDeferred<JSONObject>()
            pendingRequests[correlation] = response
            try {
                val request = JSONObject()
                    .put("reply_to", replyTopic)
                    .put("corr", correlation)
                    .put("params", JSONObject())
                publish("${MqttConfig.RPC_REQ_ROOT}/get_email_events", request.toString())
                val result = withTimeoutOrNull(RPC_TIMEOUT_MS) { response.await() }
                    ?: throw IllegalStateException("Tiempo agotado al recuperar eventos de correo")
                if (!result.optBoolean("ok", false)) return@launch
                val items = result.optJSONObject("data")?.optJSONArray("items") ?: return@launch
                mergeEmailEvents(parseEmailEvents(items))
            } catch (error: Exception) {
                update { it.copy(error = error.message ?: "No se pudo recuperar eventos de correo") }
            } finally {
                pendingRequests.remove(correlation)
            }
        }
    }

    fun sendEmailTest() {
        val runningScope = scope ?: return
        runningScope.launch {
            val mqttClient = client ?: return@launch
            if (!mqttClient.isConnected) return@launch
            val correlation = UUID.randomUUID().toString()
            val replyTopic = MqttConfig.rpcResponseTopic(clientId, correlation)
            val request = JSONObject()
                .put("reply_to", replyTopic)
                .put("corr", correlation)
                .put("params", JSONObject().put("origin", "panelito"))
            pendingRequests[correlation] = CompletableDeferred()
            try {
                publish("${MqttConfig.RPC_REQ_ROOT}/send_email_test", request.toString())
                val result = withTimeoutOrNull(RPC_TIMEOUT_MS) { pendingRequests[correlation]?.await() }
                    ?: throw IllegalStateException("Tiempo agotado al solicitar prueba de correo")
                check(result.optBoolean("ok", false)) {
                    result.optString("error").ifBlank { "La prueba de correo fue rechazada" }
                }
                delay(3_000L)
                requestEmailEvents()
                delay(12_000L)
                requestEmailEvents()
            } catch (error: Exception) {
                update { it.copy(error = error.message ?: "No se pudo solicitar la prueba de correo") }
            } finally {
                pendingRequests.remove(correlation)
            }
        }
    }

    fun requestWakeOnLan() {
        val currentStatus = stateStore.value.wakeOnLan.status
        if (currentStatus == WakeOnLanStatus.REQUESTING || currentStatus == WakeOnLanStatus.PACKET_SENT) return
        val runningScope = scope
        val mqttClient = client
        if (runningScope == null || mqttClient?.isConnected != true) {
            update {
                it.copy(
                    wakeOnLan = WakeOnLanState(
                        WakeOnLanStatus.ERROR,
                        appContext.getString(servicoop.comunic.panelito.R.string.wol_error_broker),
                    ),
                )
            }
            return
        }

        val correlation = UUID.randomUUID().toString()
        wakeOnLanCorrelation = correlation
        wakeOnLanTimeoutJob?.cancel()
        update { it.copy(wakeOnLan = WakeOnLanState(WakeOnLanStatus.REQUESTING)) }
        wakeOnLanTimeoutJob = runningScope.launch {
            try {
                val request = JSONObject()
                    .put("reply_to", MqttConfig.rpcResponseTopic(clientId, correlation))
                    .put("corr", correlation)
                    .put("params", JSONObject().put("contract_version", 1))
                publish(MqttConfig.WOL_REQUEST_TOPIC, request.toString())
                delay(WOL_RESPONSE_TIMEOUT_MS)
                if (wakeOnLanCorrelation == correlation) {
                    finishWakeOnLan(
                        WakeOnLanStatus.ERROR,
                        appContext.getString(servicoop.comunic.panelito.R.string.wol_error_timeout),
                    )
                }
            } catch (error: Exception) {
                if (wakeOnLanCorrelation == correlation) {
                    finishWakeOnLan(
                        WakeOnLanStatus.ERROR,
                        error.message ?: appContext.getString(servicoop.comunic.panelito.R.string.wol_error_request),
                    )
                }
            }
        }
    }

    fun omitMobileUpdate() {
        val release = stateStore.value.mobileRelease
        if (!release.updateRequired || release.updateMandatory) return
        val next = (release.omissionsUsed + 1).coerceAtMost(release.maxOmissions)
        update {
            it.copy(
                mobileRelease = release.copy(
                    omissionsUsed = next,
                    updateMandatory = next >= release.maxOmissions,
                ),
            )
        }
        storageScope.launch { settings.saveMobileUpdateOmissions(next) }
    }

    fun requestMobileRelease() {
        val runningScope = scope ?: return
        runningScope.launch {
            val mqttClient = client ?: return@launch
            if (!mqttClient.isConnected) return@launch
            val correlation = UUID.randomUUID().toString()
            val replyTopic = MqttConfig.rpcResponseTopic(clientId, correlation)
            val response = CompletableDeferred<JSONObject>()
            pendingRequests[correlation] = response
            update { it.copy(mobileRelease = it.mobileRelease.copy(checking = true, error = null)) }
            try {
                val request = JSONObject()
                    .put("reply_to", replyTopic)
                    .put("corr", correlation)
                    .put(
                        "params",
                        JSONObject()
                            .put("contract_version", 1)
                            .put("app", "panelito")
                            .put("platform", "android")
                            .put("current_version", BuildConfig.VERSION_NAME)
                            .put("current_version_code", BuildConfig.VERSION_CODE),
                    )
                publish("${MqttConfig.RPC_REQ_ROOT}/get_mobile_release", request.toString())
                val result = withTimeoutOrNull(RPC_TIMEOUT_MS) { response.await() }
                    ?: error("Tiempo agotado al consultar la actualizacion")
                check(result.optBoolean("ok", false)) {
                    result.optString("error").ifBlank { "No se pudo consultar la actualizacion" }
                }
                applyMobileRelease(result.getJSONObject("data"))
            } catch (error: Exception) {
                update {
                    it.copy(mobileRelease = it.mobileRelease.copy(checking = false, error = error.message))
                }
            } finally {
                pendingRequests.remove(correlation)
            }
        }
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        update { it.copy(broker = BrokerEstado.CONECTADO, error = null) }
        val mqttClient = client ?: return
        try {
            mqttClient.subscribe(
                arrayOf(
                    MqttConfig.TOPIC_MODEM_CONEXION,
                    MqttConfig.TOPIC_GRADO,
                    MqttConfig.TOPIC_GRDS,
                    MqttConfig.TOPIC_EMAIL_ESTADO,
                    MqttConfig.TOPIC_PROXMOX_ESTADO,
                    MqttConfig.TOPIC_EMAIL_EVENT,
                    MqttConfig.TOPIC_SERVICE_STATUS,
                    MqttConfig.TOPIC_CHARITO_STATE,
                    MqttConfig.TOPIC_GE_ESTIVARIZ,
                    MqttConfig.TOPIC_GE_FONTANA,
                    MqttConfig.rpcResponseSubscription(clientId),
                ),
                IntArray(11) { MqttConfig.QOS_SUBS },
            )
        } catch (error: Exception) {
            update { it.copy(error = error.message ?: "No se pudieron suscribir los topicos") }
        }
        requestEmailEvents()
        requestMobileRelease()
    }

    override fun connectionLost(cause: Throwable?) {
        if (wakeOnLanCorrelation != null) {
            finishWakeOnLan(
                WakeOnLanStatus.ERROR,
                appContext.getString(servicoop.comunic.panelito.R.string.wol_error_broker),
            )
        }
        update {
            it.copy(
                broker = BrokerEstado.REINTENTANDO,
                error = cause?.message,
            )
        }
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val actualTopic = topic ?: return
        val payload = message?.toString() ?: return
        try {
            when {
                actualTopic == MqttConfig.TOPIC_MODEM_CONEXION ->
                    update { it.copy(modem = parseModem(payload)) }
                actualTopic == MqttConfig.TOPIC_GRADO ->
                    parseGrade(payload)
                actualTopic == MqttConfig.TOPIC_GRDS ->
                    update { it.copy(grdsJson = payload) }
                actualTopic == MqttConfig.TOPIC_EMAIL_ESTADO ->
                    update { it.copy(emailStateJson = payload) }
                actualTopic == MqttConfig.TOPIC_PROXMOX_ESTADO ->
                    update { it.copy(proxmoxJson = payload) }
                actualTopic == MqttConfig.TOPIC_EMAIL_EVENT ->
                    mergeEmailEvents(parseEmailEvents(JSONArray().put(JSONObject(payload))))
                actualTopic == MqttConfig.TOPIC_SERVICE_STATUS ->
                    parseLechuStatus(payload)
                actualTopic == MqttConfig.TOPIC_CHARITO_STATE ->
                    update { it.copy(charitoJson = payload) }
                actualTopic == MqttConfig.TOPIC_GE_ESTIVARIZ || actualTopic == MqttConfig.TOPIC_GE_FONTANA ->
                    parseGe(actualTopic, payload)
                actualTopic.startsWith("${MqttConfig.RPC_RES_ROOT}/$clientId/") ->
                    handleRpcResponse(payload)
            }
        } catch (error: Exception) {
            update { it.copy(error = error.message ?: "Payload MQTT invalido") }
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit

    private fun connectOnce() {
        val mqttClient = MqttClient(
            MqttConfig.brokerUrl,
            clientId,
            MemoryPersistence(),
        )
        mqttClient.setCallback(this)
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = false
            userName = MqttConfig.username
            password = MqttConfig.password.toCharArray()
            connectionTimeout = 10
            keepAliveInterval = MqttConfig.KEEP_ALIVE_SECONDS
            maxInflight = 4
        }
        client = mqttClient
        mqttClient.connect(options)
    }

    private fun disconnectClient() {
        val current = client
        client = null
        try {
            if (current?.isConnected == true) current.disconnect()
        } catch (_: Exception) {
            // La sesion ya esta terminando.
        }
        try {
            current?.close()
        } catch (_: Exception) {
            // No hay mas recursos que liberar.
        }
    }

    private fun publish(topic: String, payload: String) {
        val mqttClient = client ?: throw IllegalStateException("MQTT no esta inicializado")
        check(mqttClient.isConnected) { "MQTT no esta conectado" }
        val message = MqttMessage(payload.toByteArray(Charsets.UTF_8)).apply {
            qos = MqttConfig.QOS_SUBS
            isRetained = false
        }
        mqttClient.publish(topic, message)
    }

    private fun parseGrade(payload: String) {
        val source = JSONObject(payload)
        val percentage = source.getDouble("porcentaje")
        val unavailable = source.getInt("no_disponibles")
        require(percentage in 0.0..100.0 && unavailable >= 0) { "Resumen GRD invalido" }
        update { it.copy(gradoPct = percentage, grdUnavailableCount = unavailable) }
    }

    private fun parseModem(payload: String): ModemEstado {
        val value = runCatching { JSONObject(payload).optString("estado") }
            .getOrDefault(payload)
        return ModemEstado.fromString(value)
    }

    private fun parseGe(topic: String, payload: String) {
        val source = JSONObject(payload)
        val building = if (topic == MqttConfig.TOPIC_GE_FONTANA) GE_EDIF_FONTANA else GE_EDIF_ESTIVARIZ
        val sourceStatus = source.optString("source_status", "available")
        if (sourceStatus != "available") {
            update { it.copy(geStates = it.geStates + (building to GeEstado.DESCONOCIDO)) }
            return
        }
        val line = source.getJSONObject("interruptor_linea")
        val bit = line.getInt("bit")
        val expected = if (bit == 1) "cerrado" else "abierto"
        val state = line.getString("estado")
        require(bit == 0 || bit == 1) { "interruptor_linea.bit fuera de rango" }
        require(state.equals(expected, true)) { "interruptor_linea.estado no coincide con bit" }
        update { it.copy(geStates = it.geStates + (building to GeEstado.fromLineState(state, bit))) }
    }

    private fun parseLechuStatus(payload: String) {
        val source = JSONObject(payload)
        val status = source.getString("status")
        require(status == "online" || status == "offline") { "status de lechu invalido" }
        update { it.copy(lechuOnline = status == "online", lechuStatusTimestamp = source.optString("ts")) }
    }

    private fun handleRpcResponse(payload: String) {
        val source = JSONObject(payload)
        val correlation = source.optString("corr")
        if (
            source.optString("action") == "wake_host" &&
            correlation.isNotBlank() &&
            correlation == wakeOnLanCorrelation
        ) {
            handleWakeOnLanResponse(source)
            return
        }
        pendingRequests[correlation]?.complete(source)
    }

    private fun handleWakeOnLanResponse(source: JSONObject) {
        if (!source.optBoolean("ok", false)) {
            finishWakeOnLan(
                WakeOnLanStatus.ERROR,
                source.optString("error").ifBlank {
                    appContext.getString(servicoop.comunic.panelito.R.string.wol_error_request)
                },
            )
            return
        }
        val data = source.optJSONObject("data")
        if (data == null) {
            finishWakeOnLan(
                WakeOnLanStatus.ERROR,
                appContext.getString(servicoop.comunic.panelito.R.string.wol_error_contract),
            )
            return
        }
        if (
            data.optInt("contract_version") != 1 ||
            data.optString("request_id") != wakeOnLanCorrelation
        ) {
            finishWakeOnLan(
                WakeOnLanStatus.ERROR,
                appContext.getString(servicoop.comunic.panelito.R.string.wol_error_contract),
            )
            return
        }
        when (data.optString("status")) {
            "accepted" -> update {
                it.copy(wakeOnLan = WakeOnLanState(WakeOnLanStatus.REQUESTING))
            }
            "packet_sent" -> update {
                it.copy(wakeOnLan = WakeOnLanState(WakeOnLanStatus.PACKET_SENT))
            }
            "ssh_open" -> finishWakeOnLan(WakeOnLanStatus.SSH_OPEN, null)
            else -> finishWakeOnLan(
                WakeOnLanStatus.ERROR,
                appContext.getString(servicoop.comunic.panelito.R.string.wol_error_contract),
            )
        }
    }

    private fun finishWakeOnLan(status: WakeOnLanStatus, detail: String?) {
        wakeOnLanCorrelation = null
        wakeOnLanTimeoutJob?.cancel()
        wakeOnLanTimeoutJob = null
        update { it.copy(wakeOnLan = WakeOnLanState(status, detail)) }
    }

    private fun applyMobileRelease(source: JSONObject) {
        require(source.getInt("contract_version") == 1) { "Contrato de actualizacion invalido" }
        require(source.getString("app") == "panelito") { "La actualizacion corresponde a otra aplicacion" }
        require(source.getString("platform") == "android") { "Plataforma de actualizacion invalida" }
        require(source.getString("hash_algorithm") == "sha256") { "Algoritmo de integridad invalido" }
        val versionName = source.getString("latest_version").also { require(it.isNotBlank()) }
        val versionCode = source.getLong("artifact_version_code").also { require(it > 0L) }
        val sizeBytes = source.getLong("artifact_size_bytes").also { require(it > 0L) }
        val hash = source.getString("artifact_hash").lowercase()
            .also { require(it.matches(Regex("^[a-f0-9]{64}$"))) }
        val apkUrl = source.getString("apk_url").also { require(it.isNotBlank()) }
        val maxOmissions = source.getInt("max_omissions").also { require(it >= 0) }
        val updateRequired = source.getBoolean("update_required")
        require(updateRequired == (versionCode > BuildConfig.VERSION_CODE.toLong())) {
            "Decision de actualizacion inconsistente"
        }
        val omissions = stateStore.value.mobileRelease.omissionsUsed.coerceAtMost(maxOmissions)
        if (!updateRequired) storageScope.launch { settings.saveMobileUpdateOmissions(0) }
        update {
            it.copy(
                mobileRelease = MobileReleaseState(
                    checking = false,
                    updateRequired = updateRequired,
                    updateMandatory = updateRequired && omissions >= maxOmissions,
                    versionName = versionName,
                    versionCode = versionCode,
                    apkUrl = apkUrl,
                    sha256 = hash,
                    sizeBytes = sizeBytes,
                    maxOmissions = maxOmissions,
                    omissionsUsed = if (updateRequired) omissions else 0,
                ),
            )
        }
    }

    private fun parseEmailEvents(items: JSONArray): List<EmailEvent> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val timestamp = item.optString("ts", item.optString("updated_at"))
            if (timestamp.isBlank()) continue
            add(
                EmailEvent(
                    id = item.optString("id"),
                    type = item.optString("type", "email"),
                    subject = item.optString("subject"),
                    status = item.optString("status").ifBlank {
                        if (item.optBoolean("ok", false)) "sent" else "failed"
                    },
                    timestamp = timestamp,
                    detail = item.optString("detail"),
                ),
            )
        }
    }

    private fun mergeEmailEvents(incoming: List<EmailEvent>) {
        if (incoming.isEmpty()) return
        val merged = (incoming + stateStore.value.emailEvents)
            .distinctBy { it.id.ifBlank { "${it.timestamp}|${it.subject}|${it.status}|${it.detail}" } }
            .sortedByDescending { it.timestamp }
            .take(MAX_EMAIL_EVENTS)
        update { it.copy(emailEvents = merged) }
        storageScope.launch { settings.saveEmailEvents(merged) }
    }

    private fun update(transform: (MqttState) -> MqttState) {
        stateStore.update(transform)
    }

    companion object {
        const val GE_EDIF_ESTIVARIZ = "edif-estivariz"
        const val GE_EDIF_FONTANA = "edif-fontana"
        private const val MAX_EMAIL_EVENTS = 50
        private const val RPC_TIMEOUT_MS = 10_000L
        private const val WOL_RESPONSE_TIMEOUT_MS = 190_000L
    }
}
