package servicoop.comunic.redirectorllamadas.mqtt

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import org.json.JSONObject

/**
 * Publisher opcional.
 * Por defecto, no publica nada (ENABLE_PUBLISH = false) porque el server es el publicador oficial.
 * Si queres habilitar publicaciones locales (ej. llamadas entrantes), cambia a true.
 */
object MqttPublisher {

    private const val ENABLE_PUBLISH = false // mantener false para el esquema actual (solo servidor publica)

    // topic namespaced por dispositivo para evitar colisiones entre multiples apps
    private fun topicIncomingCalls(context: Context): String {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return "exemys/app/Redirector_$id/llamadas/entrantes"
    }

    /**
     * Publica (opcional) un evento de llamada entrante en JSON:
     * {
     *   "tipo_evento": "llamada_entrante",
     *   "numero_telefono": "...",
     *   "nombre_contacto": "...",
     *   "timestamp": 1712345678
     * }
     */
    fun publishIncomingCall(context: Context, phoneNumber: String, callerName: String?) {
        if (!ENABLE_PUBLISH) {
            Log.i("MqttPublisher", "Publicacion deshabilitada por configuracion. Evento ignorado.")
            return
        }

        val topic = topicIncomingCalls(context)
        val jsonMessage = JSONObject().apply {
            put("tipo_evento", "llamada_entrante")
            put("numero_telefono", phoneNumber)
            put("nombre_contacto", callerName ?: "Desconocido")
            put("timestamp", System.currentTimeMillis())
        }
        val message = jsonMessage.toString()

        Log.d("MqttPublisher", "Preparando publicacion. topic='$topic' msg='${message.take(100)}...'")

        val publishIntent = Intent(context, MQTTService::class.java).apply {
            action = MQTTService.ACTION_PUBLICAR
            putExtra(MQTTService.EXTRA_TOPIC_PUBLICAR, topic)
            putExtra(MQTTService.EXTRA_MENSAJE_PUBLICAR, message)
            putExtra(MQTTService.EXTRA_QOS_PUBLICAR, 1)         // QoS 1
            putExtra(MQTTService.EXTRA_RETAINED_PUBLICAR, false) // no retenido
        }

        try {
            context.startService(publishIntent)
            Log.d("MqttPublisher", "Intent de publicacion enviado a MQTTService.")
        } catch (e: Exception) {
            Log.e("MqttPublisher", "Error al enviar intent de publicacion a MQTTService: ${e.message}", e)
        }
    }
}