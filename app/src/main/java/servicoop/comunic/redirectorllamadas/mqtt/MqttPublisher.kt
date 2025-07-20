package servicoop.comunic.redirectorllamadas.mqtt

import android.content.Context
import android.content.Intent
import android.util.Log // ¡Importante: Importar esto!
import org.json.JSONObject

object MqttPublisher {

    fun publishIncomingCall(context: Context, phoneNumber: String, callerName: String?) {
        val topic = "llamadas/entrantes"

        // Crear un objeto JSON con los detalles de la llamada
        val jsonMessage = JSONObject().apply {
            put("tipo_evento", "llamada_entrante")
            put("numero_telefono", phoneNumber)
            put("nombre_contacto", callerName ?: "Desconocido")
            put("timestamp", System.currentTimeMillis())
        }
        val message = jsonMessage.toString()

        Log.d("MqttPublisher", "publishIncomingCall: Preparando mensaje para publicar. Topic='$topic', Message='${message.take(100)}...'") // Limitar log de mensaje para no saturar

        val publishIntent = Intent(context, MQTTService::class.java).apply {
            action = MQTTService.ACTION_PUBLICAR
            putExtra(MQTTService.EXTRA_TOPIC_PUBLICAR, topic)
            putExtra(MQTTService.EXTRA_MENSAJE_PUBLICAR, message)
            putExtra(MQTTService.EXTRA_QOS_PUBLICAR, 1) // QoS 1 para asegurar la entrega
            putExtra(MQTTService.EXTRA_RETAINED_PUBLICAR, false) // No retenido
        }

        try {
            context.startService(publishIntent)
            Log.d("MqttPublisher", "publishIncomingCall: Intent de publicacion enviado a MQTTService.")
        } catch (e: Exception) {
            Log.e("MqttPublisher", "publishIncomingCall: Error al enviar intent de publicacion a MQTTService: ${e.message}", e)
            // Considerar notificar a la UI sobre este error si es critico, por ejemplo, enviando un broadcast de error.
        }
    }
}