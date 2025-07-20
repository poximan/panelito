package servicoop.comunic.redirectorllamadas.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log // ¡Importante: Importar esto!
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import servicoop.comunic.redirectorllamadas.R
import servicoop.comunic.redirectorllamadas.mqtt.MQTTService

class MqttFragment : Fragment() {

    private lateinit var switchConnect: Switch
    private lateinit var indicatorStatus: View
    private lateinit var txtMensajes: TextView

    // Receptor de broadcasts para actualizar la UI desde el MQTTService
    private val mqttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("MqttFragment", "mqttReceiver: Intent recibido. Action = ${intent?.action}")
            when (intent?.action) {
                MQTTService.ACTION_MENSAJE -> {
                    val mensaje = intent.getStringExtra(MQTTService.EXTRA_MENSAJE) ?: return
                    Log.d("MqttFragment", "mqttReceiver: Mensaje MQTT: $mensaje")
                    actualizarPantalla(mensaje)
                }
                MQTTService.ACTION_ESTADO -> {
                    // Recibe el estado de conexion
                    val estado = intent.getStringExtra(MQTTService.EXTRA_ESTADO) ?: return
                    Log.d("MqttFragment", "mqttReceiver: Estado MQTT: $estado")
                    actualizarEstadoUI(estado)
                }
                MQTTService.ACTION_ERROR -> {
                    // Recibe mensajes de error
                    val error = intent.getStringExtra(MQTTService.EXTRA_ERROR) ?: return
                    Log.d("MqttFragment", "mqttReceiver: Error MQTT: $error")
                    actualizarPantalla("ERROR: $error")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d("MqttFragment", "onCreateView: Fragmento creado.")
        val view = inflater.inflate(R.layout.fragment_mqtt, container, false)

        switchConnect = view.findViewById(R.id.switch_connect)
        indicatorStatus = view.findViewById(R.id.indicator_status)
        txtMensajes = view.findViewById(R.id.txt_mensajes)

        // Listener para el switch de conexion
        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            Log.d("MqttFragment", "switchConnect: Switch cambiado a $isChecked")
            if (isChecked) {
                iniciarServicioMQTT()
                actualizarEstadoUI(MQTTService.ESTADO_CONECTANDO)
            } else {
                detenerServicioMQTT()
                actualizarEstadoUI(MQTTService.ESTADO_DESCONECTADO)
            }
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        Log.d("MqttFragment", "onResume: Registrando BroadcastReceiver.")
        val filter = IntentFilter().apply {
            addAction(MQTTService.ACTION_MENSAJE)
            addAction(MQTTService.ACTION_ESTADO)
            addAction(MQTTService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mqttReceiver, filter)

        solicitarEstadoServicio()
    }

    override fun onPause() {
        super.onPause()
        Log.d("MqttFragment", "onPause: Desregistrando BroadcastReceiver.")
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mqttReceiver)
    }

    private fun iniciarServicioMQTT() {
        Log.d("MqttFragment", "iniciarServicioMQTT: Iniciando MQTTService.")
        requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
    }

    private fun detenerServicioMQTT() {
        Log.d("MqttFragment", "detenerServicioMQTT: Deteniendo MQTTService.")
        requireContext().stopService(Intent(requireContext(), MQTTService::class.java))
    }

    private fun actualizarEstadoUI(estado: String) {
        Log.d("MqttFragment", "actualizarEstadoUI: Actualizando UI a estado: $estado")
        when (estado) {
            MQTTService.ESTADO_CONECTADO -> {
                indicatorStatus.setBackgroundResource(R.drawable.led_verde)
                switchConnect.isChecked = true
                actualizarPantalla("Estado: Conectado al broker MQTT.")
            }
            MQTTService.ESTADO_DESCONECTADO -> {
                indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
                switchConnect.isChecked = false
                actualizarPantalla("Estado: Desconectado del broker MQTT.")
            }
            MQTTService.ESTADO_CONECTANDO -> {
                indicatorStatus.setBackgroundResource(R.drawable.led_naranja)
                switchConnect.isChecked = true
                actualizarPantalla("Estado: Intentando conectar...")
            }
            MQTTService.ESTADO_REINTENTANDO -> {
                indicatorStatus.setBackgroundResource(R.drawable.led_naranja)
                switchConnect.isChecked = true
                actualizarPantalla("Estado: Reintentando conexion...")
            }
            MQTTService.ESTADO_ERROR -> {
                indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
                switchConnect.isChecked = false
            }
        }
    }

    private fun actualizarPantalla(mensaje: String) {
        Log.d("MqttFragment", "actualizarPantalla: Añadiendo mensaje a UI: $mensaje")
        val textoPrevio = txtMensajes.text.toString()
        txtMensajes.text = "$mensaje\n$textoPrevio"
    }

    private fun solicitarEstadoServicio() {
        Log.d("MqttFragment", "solicitarEstadoServicio: Solicitando estado actual del MQTTService.")
        val intent = Intent(requireContext(), MQTTService::class.java).apply {
            putExtra("solicitar_estado", true)
        }
        requireContext().startService(intent)
    }
}