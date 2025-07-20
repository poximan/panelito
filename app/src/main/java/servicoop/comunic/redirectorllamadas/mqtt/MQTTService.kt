package servicoop.comunic.redirectorllamadas.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log // ¡Importante: Importar esto!
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.eclipse.paho.client.mqttv3.*
import servicoop.comunic.redirectorllamadas.R
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class MQTTService : Service(), MqttCallback {

    // NOTA: Asegúrate que el puerto 883 sea el correcto para tu broker HiveMQ con TLS.
    // Lo más común es 8883 para TLS. Si tu URL es ssl://..., el puerto 883 es raro.
    // Si estás usando TLS/SSL, verifica la URL del broker y el puerto.
    // Ejemplo: ssl://a4f7b92509244483b210b9f1f69bcc37.s1.eu.hivemq.cloud:8883
    private val brokerUrl = "ssl://a4f7b92509244483b210b9f1f69bcc37.s1.eu.hivemq.cloud:8883" // <--- ¡Verifica este puerto!
    private val clientId = "AndroidClient_${System.currentTimeMillis()}"
    private lateinit var mqttClient: MqttClient
    private var isConnected = false
    private var isAttemptingConnection = false

    private val mainHandler = Handler(Looper.getMainLooper()) // <-- Corrección ya aplicada

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var reconnectAttempt = 0
    private val initialReconnectDelayMs = 2000L
    private val maxReconnectDelayMs = 60000L

    private val options = MqttConnectOptions().apply {
        isCleanSession = false // Mantener suscripciones entre sesiones
        isAutomaticReconnect = false // Lo manejaremos manualmente para mayor control
        userName = "comunicaciones"
        password = "comuniC4ciones".toCharArray()
        connectionTimeout = 10 // segundos
        keepAliveInterval = 60 // segundos
    }

    companion object {
        const val ID_CANAL = "MqttChannel"
        const val NOMBRE_CANAL = "MQTT Service Notifications"
        const val NOTIFICATION_ID = 1

        const val ACTION_MENSAJE = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_MENSAJE"
        const val EXTRA_MENSAJE = "EXTRA_MENSAJE"

        const val ACTION_ESTADO = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ESTADO"
        const val EXTRA_ESTADO = "EXTRA_ESTADO"

        const val ACTION_ERROR = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ERROR"
        const val EXTRA_ERROR = "EXTRA_ERROR"

        // Constantes para la accion de publicar (usadas por MqttPublisher)
        const val ACTION_PUBLICAR = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_PUBLICAR"
        const val EXTRA_TOPIC_PUBLICAR = "EXTRA_TOPIC_PUBLICAR"
        const val EXTRA_MENSAJE_PUBLICAR = "EXTRA_MENSAJE_PUBLICAR"
        const val EXTRA_QOS_PUBLICAR = "EXTRA_QOS_PUBLICAR"
        const val EXTRA_RETAINED_PUBLICAR = "EXTRA_RETAINED_PUBLICAR"

        // Definiciones de estados para la UI
        const val ESTADO_CONECTADO = "CONECTADO"
        const val ESTADO_DESCONECTADO = "DESCONECTADO"
        const val ESTADO_CONECTANDO = "CONECTANDO"
        const val ESTADO_REINTENTANDO = "REINTENTANDO"
        const val ESTADO_ERROR = "ERROR"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("MQTTService", "onCreate: Servicio creado.")
        crearCanalNotificacion()
        // Inicia con estado "Desconectado" en la notificacion,
        // la actualizaremos cuando el servicio intente conectar
        iniciarEnPrimerPlano(ESTADO_DESCONECTADO)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val solicitarEstado = intent?.getBooleanExtra("solicitar_estado", false) ?: false
        val action = intent?.action

        Log.d("MQTTService", "onStartCommand: Action = $action, Solicitar Estado = $solicitarEstado")

        when {
            solicitarEstado -> {
                Log.d("MQTTService", "onStartCommand: Solicitud de estado recibida.")
                if (isConnected) {
                    enviarEstadoBroadcast(ESTADO_CONECTADO)
                } else if (isAttemptingConnection) {
                    // Si estamos conectando o reintentando, reportamos ese estado
                    enviarEstadoBroadcast(ESTADO_CONECTANDO)
                } else {
                    enviarEstadoBroadcast(ESTADO_DESCONECTADO)
                }
            }
            action == ACTION_PUBLICAR -> {
                Log.d("MQTTService", "onStartCommand: Accion de publicacion recibida.")
                val topic = intent.getStringExtra(EXTRA_TOPIC_PUBLICAR)
                val message = intent.getStringExtra(EXTRA_MENSAJE_PUBLICAR)
                val qos = intent.getIntExtra(EXTRA_QOS_PUBLICAR, 1) // QoS por defecto 1
                val retained = intent.getBooleanExtra(EXTRA_RETAINED_PUBLICAR, false) // Retenido por defecto false

                if (topic != null && message != null) {
                    Log.d("MQTTService", "onStartCommand: Datos de publicacion: Topic='$topic', Message='${message.take(50)}...'") // Mostrar solo parte del mensaje
                    publicarMensaje(topic, message, qos, retained)
                } else {
                    val errorMsg = "onStartCommand: Faltan datos para publicar mensaje MQTT (Topic: $topic, Message: $message)."
                    Log.e("MQTTService", errorMsg)
                    enviarErrorBroadcast(errorMsg)
                }
            }
            !isConnected && !isAttemptingConnection -> {
                // Si no estamos conectados y no estamos en proceso de conectar, iniciamos la conexion
                Log.d("MQTTService", "onStartCommand: No conectado y no intentando. Iniciando cliente MQTT.")
                isAttemptingConnection = true // Marcamos que estamos intentando conectar
                enviarEstadoBroadcast(ESTADO_CONECTANDO)
                iniciarClienteMQTT()
            }
            else -> {
                Log.d("MQTTService", "onStartCommand: Estado actual = ${if (isConnected) "Conectado" else if (isAttemptingConnection) "Conectando" else "Desconectado"}. No se requiere accion.")
            }
        }

        return START_STICKY // El servicio se reiniciara si es detenido por el sistema
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d("MQTTService", "onBind: Llamado.")
        return null // No se usa binding en este servicio
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MQTTService", "onDestroy: Servicio destruido.")
        // Limpiamos recursos al destruir el servicio
        scheduler.shutdownNow() // Detenemos el scheduler de reintentos
        desconectarClienteMQTT()
        isConnected = false
        isAttemptingConnection = false // Resetear flag de intento de conexion
        enviarEstadoBroadcast(ESTADO_DESCONECTADO) // Notificamos desconexion a la UI
        actualizarNotificacion("Servicio MQTT detenido", "Desconectado")
    }

    // Implementacion de MqttCallback
    override fun connectionLost(cause: Throwable?) {
        // Se llama cuando la conexion se pierde inesperadamente
        isConnected = false
        isAttemptingConnection = false // La conexion se perdio, resetear flag de intento
        val errorMessage = "Conexion perdida: ${cause?.message ?: "Desconocido"}"
        Log.e("MQTTService", "connectionLost: $errorMessage", cause)
        enviarErrorBroadcast(errorMessage)
        enviarEstadoBroadcast(ESTADO_DESCONECTADO)
        actualizarNotificacion("Conexion perdida", "Reconectando...")
        // Iniciar el proceso de reconexion
        reconnectAttempt = 0
        scheduleReconnect()
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        // Se llama cuando llega un mensaje
        message?.let {
            val payload = String(it.payload)
            Log.d("MQTTService", "messageArrived: Mensaje MQTT recibido. Topic=$topic, Payload='$payload'")
            enviarMensajeBroadcast("Mensaje de $topic: $payload")
        } ?: Log.w("MQTTService", "messageArrived: Mensaje recibido pero nulo.")
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        // Se llama cuando un mensaje publicado ha sido entregado
        val messageId = token?.messageId ?: "Desconocido"
        Log.d("MQTTService", "deliveryComplete: Entrega de mensaje completa. ID=$messageId")
        enviarMensajeBroadcast("Mensaje ID $messageId entregado.")
    }

    private fun crearCanalNotificacion() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(ID_CANAL, NOMBRE_CANAL, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        Log.d("MQTTService", "crearCanalNotificacion: Canal de notificacion creado.")
    }

    private fun iniciarEnPrimerPlano(estadoInicial: String) {
        val notificationText = when (estadoInicial) {
            ESTADO_CONECTADO -> "Conectado al broker MQTT"
            ESTADO_CONECTANDO -> "Intentando conectar al broker MQTT"
            ESTADO_REINTENTANDO -> "Reintentando conexion al broker MQTT"
            else -> "Desconectado del broker MQTT"
        }
        val notification = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle("Servicio MQTT")
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.ic_mqtt) // Asegurate de tener este icono
            .build()
        startForeground(NOTIFICATION_ID, notification)
        Log.d("MQTTService", "iniciarEnPrimerPlano: Servicio en primer plano iniciado con estado: $estadoInicial")
    }

    private fun actualizarNotificacion(title: String, text: String) {
        val notification = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d("MQTTService", "actualizarNotificacion: Notificacion actualizada - Titulo: '$title', Texto: '$text'")
    }

    private fun iniciarClienteMQTT() {
        // Ejecutamos la conexion en un hilo separado para no bloquear el hilo principal
        scheduler.execute {
            Log.d("MQTTService", "iniciarClienteMQTT: Intentando conectar al broker...")
            try {
                mqttClient = MqttClient(brokerUrl, clientId, null)
                mqttClient.setCallback(this) // Establecemos el callback para eventos de conexion/mensaje

                // Enviamos estado de conectando antes de intentar la conexion
                enviarEstadoBroadcast(ESTADO_CONECTANDO)
                actualizarNotificacion("Conectando...", "Estableciendo conexion MQTT")

                mqttClient.connect(options)
                isConnected = true
                isAttemptingConnection = false // Conexion exitosa, ya no estamos intentando
                enviarEstadoBroadcast(ESTADO_CONECTADO) // Informar conexion exitosa
                actualizarNotificacion("Servicio MQTT", "Conectado al broker MQTT")
                reconnectAttempt = 0 // Resetear contador de reintentos
                Log.d("MQTTService", "iniciarClienteMQTT: Cliente MQTT conectado exitosamente.")


                // Suscribirse solo si la conexion es exitosa
                mqttClient.subscribe("llamadas/entrantes", 1) // Suscripcion a QoS 1
                enviarMensajeBroadcast("Suscrito a 'llamadas/entrantes'")
                Log.d("MQTTService", "iniciarClienteMQTT: Suscrito a 'llamadas/entrantes'.")

            } catch (e: MqttException) {
                isConnected = false
                isAttemptingConnection = false // El intento fallo
                val errorMessage = "Error de conexion MQTT: ${e.message}"
                Log.e("MQTTService", "iniciarClienteMQTT: $errorMessage", e)
                enviarErrorBroadcast(errorMessage)
                enviarEstadoBroadcast(ESTADO_ERROR) // Enviar estado de error
                actualizarNotificacion("Error de conexion", e.message ?: "Desconocido")
                // Programar reintento si la conexion falla
                scheduleReconnect()
            } catch (e: Exception) {
                isConnected = false
                isAttemptingConnection = false // El intento fallo
                val errorMessage = "Error inesperado al iniciar cliente: ${e.message}"
                Log.e("MQTTService", "iniciarClienteMQTT: $errorMessage", e)
                enviarErrorBroadcast(errorMessage)
                enviarEstadoBroadcast(ESTADO_ERROR)
                actualizarNotificacion("Error", e.message ?: "Desconocido")
                scheduleReconnect()
            }
        }
    }

    // Funcion para publicar mensajes
    private fun publicarMensaje(topic: String, message: String, qos: Int, retained: Boolean) {
        scheduler.execute { // Ejecutar la publicacion en el hilo del scheduler
            if (!isConnected) {
                val errorMsg = "publicarMensaje: No conectado al broker. No se pudo publicar en $topic."
                Log.w("MQTTService", errorMsg)
                enviarErrorBroadcast(errorMsg)
                return@execute
            }
            try {
                val mqttMessage = MqttMessage(message.toByteArray()).apply {
                    this.qos = qos
                    this.isRetained = retained
                }
                mqttClient.publish(topic, mqttMessage)
                Log.i("MQTTService", "publicarMensaje: Mensaje publicado en '$topic': ${message.take(50)}... (QoS: $qos, Retained: $retained)")
                enviarMensajeBroadcast("Publicado en '$topic': ${message.take(30)}...")
            } catch (e: MqttException) {
                val errorMsg = "publicarMensaje: Error al publicar en $topic: ${e.message}"
                Log.e("MQTTService", errorMsg, e)
                enviarErrorBroadcast(errorMsg)
            } catch (e: Exception) {
                val errorMsg = "publicarMensaje: Error inesperado al publicar en $topic: ${e.message}"
                Log.e("MQTTService", errorMsg, e)
                enviarErrorBroadcast(errorMsg)
            }
        }
    }

    // Verifica si el cliente MQTT esta inicializado y en proceso de conexion/reintento
    private fun mqttClientInitializedAndConnecting(): Boolean {
        // Ahora usamos el flag isAttemptingConnection
        return ::mqttClient.isInitialized && (mqttClient.isConnected || isAttemptingConnection)
    }

    private fun desconectarClienteMQTT() {
        try {
            if (::mqttClient.isInitialized && mqttClient.isConnected) {
                mqttClient.disconnect()
                Log.d("MQTTService", "desconectarClienteMQTT: Cliente MQTT desconectado.")
            }
        } catch (e: MqttException) {
            val errorMessage = "desconectarClienteMQTT: Error al desconectar MQTT: ${e.message}"
            Log.e("MQTTService", errorMessage, e)
            enviarErrorBroadcast(errorMessage)
        } finally {
            isConnected = false
            isAttemptingConnection = false
        }
    }

    // Programa un reintento de conexion con retardo exponencial
    private fun scheduleReconnect() {
        if (!scheduler.isShutdown) { // Asegurarse de que el scheduler no este apagado
            reconnectAttempt++
            var delay = (initialReconnectDelayMs * Math.pow(2.0, (reconnectAttempt - 1).toDouble())).toLong()
            if (delay > maxReconnectDelayMs) {
                delay = maxReconnectDelayMs
            }

            isAttemptingConnection = true // Marcamos que estamos intentando reconectar
            enviarEstadoBroadcast(ESTADO_REINTENTANDO)
            actualizarNotificacion("Reconectando...", "Intento #${reconnectAttempt} en ${delay / 1000}s")
            val logMessage = "scheduleReconnect: Reintentando conexion en ${delay / 1000} segundos (Intento $reconnectAttempt)..."
            Log.d("MQTTService", logMessage)
            enviarMensajeBroadcast(logMessage)

            scheduler.schedule({
                // Solo reintentar si no estamos ya conectados y el servicio no esta siendo destruido
                if (!isConnected && !scheduler.isShutdown) {
                    Log.d("MQTTService", "scheduleReconnect: Ejecutando reintento de conexion.")
                    iniciarClienteMQTT()
                } else {
                    Log.d("MQTTService", "scheduleReconnect: Reintento cancelado: ya conectado o scheduler apagado.")
                }
            }, delay, TimeUnit.MILLISECONDS)
        } else {
            Log.w("MQTTService", "scheduleReconnect: Scheduler apagado, no se puede programar reintento.")
        }
    }

    // Envia un mensaje de texto a la UI
    private fun enviarMensajeBroadcast(mensaje: String) {
        val intent = Intent(ACTION_MENSAJE).apply {
            putExtra(EXTRA_MENSAJE, mensaje)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.v("MQTTService", "enviarMensajeBroadcast: Enviado mensaje a UI: '$mensaje'") // Log verbose
    }

    // Envia el estado actual de la conexion a la UI
    private fun enviarEstadoBroadcast(estado: String) {
        val intent = Intent(ACTION_ESTADO).apply {
            putExtra(EXTRA_ESTADO, estado)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.v("MQTTService", "enviarEstadoBroadcast: Enviado estado a UI: '$estado'") // Log verbose
    }

    // Envia un mensaje de error a la UI
    private fun enviarErrorBroadcast(error: String) {
        val intent = Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.e("MQTTService", "enviarErrorBroadcast: Enviado error a UI: '$error'")
    }
}