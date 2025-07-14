package servicoop.comunic.redirectorllamadas.mqtt

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import servicoop.comunic.redirectorllamadas.R

class MQTTService : Service() {

    private val brokerUrl = "ssl://a4f7b92509244483b210b9f1f69bcc37.s1.eu.hivemq.cloud:8883"
    private val clientId = "AndroidClient"
    private lateinit var mqttClient: MqttClient

    private val options = MqttConnectOptions().apply {
        isCleanSession = false
        isAutomaticReconnect = true
        userName = "comunicaciones"
        password = "comuniC4ciones".toCharArray()
    }

    companion object {
        const val ID_CANAL = "MqttChannel"
        const val NOMBRE_CANAL = "MQTT Service Notifications"
        const val ACTION_MENSAJE = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_MENSAJE"
        const val EXTRA_MENSAJE = "EXTRA_MENSAJE"
        const val ACTION_ESTADO = "servicoop.comunic.redirectorllamadas.mqtt.ACTION_ESTADO"
        const val EXTRA_ESTADO = "EXTRA_ESTADO"
    }

    override fun onCreate() {
        super.onCreate()
        crearCanalNotificacion()
        iniciarClienteMQTT()
        enviarEstado(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        iniciarEnPrimerPlano()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        mqttClient.disconnect()
        enviarEstado(false)
    }

    private fun crearCanalNotificacion() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(ID_CANAL, NOMBRE_CANAL, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    private fun iniciarEnPrimerPlano() {
        val notification = NotificationCompat.Builder(this, ID_CANAL)
            .setContentTitle("MQTT Service")
            .setContentText("Conectado al broker MQTT")
            .setSmallIcon(R.drawable.ic_mqtt)
            .build()
        startForeground(1, notification)
    }

    private fun iniciarClienteMQTT() {
        try {
            mqttClient = MqttClient(brokerUrl, clientId, null)
            mqttClient.connect(options)
            mqttClient.subscribe("llamadas/entrantes", 1) { _, message ->
                enviarMensajeBroadcast(String(message.payload))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun enviarMensajeBroadcast(mensaje: String) {
        val intent = Intent(ACTION_MENSAJE).apply {
            putExtra(EXTRA_MENSAJE, mensaje)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun enviarEstado(estado: Boolean) {
        val intent = Intent(ACTION_ESTADO).apply {
            putExtra(EXTRA_ESTADO, estado)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}