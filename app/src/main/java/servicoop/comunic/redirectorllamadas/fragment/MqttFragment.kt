package servicoop.comunic.redirectorllamadas.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
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

    private val mqttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MQTTService.ACTION_MENSAJE -> {
                    val mensaje = intent.getStringExtra(MQTTService.EXTRA_MENSAJE) ?: return
                    actualizarPantalla(mensaje)
                }
                MQTTService.ACTION_ESTADO -> {
                    val estado = intent.getBooleanExtra(MQTTService.EXTRA_ESTADO, false)
                    switchConnect.isChecked = estado
                    actualizarEstado(estado)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_mqtt, container, false)

        switchConnect = view.findViewById(R.id.switch_connect)
        indicatorStatus = view.findViewById(R.id.indicator_status)
        txtMensajes = view.findViewById(R.id.txt_mensajes)

        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                iniciarServicioMQTT()
            } else {
                detenerServicioMQTT()
            }
            actualizarEstado(isChecked)
        }

        return view
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MQTTService.ACTION_MENSAJE)
            addAction(MQTTService.ACTION_ESTADO)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mqttReceiver, filter)

        solicitarEstadoServicio()
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mqttReceiver)
    }

    private fun iniciarServicioMQTT() {
        requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
    }

    private fun detenerServicioMQTT() {
        requireContext().stopService(Intent(requireContext(), MQTTService::class.java))
    }

    private fun actualizarEstado(conectado: Boolean) {
        val drawable = if (conectado) R.drawable.led_verde else R.drawable.led_rojo
        indicatorStatus.setBackgroundResource(drawable)
    }

    private fun actualizarPantalla(mensaje: String) {
        val textoPrevio = txtMensajes.text.toString()
        txtMensajes.text = "$mensaje\n$textoPrevio"
    }

    private fun solicitarEstadoServicio() {
        // Llamamos a MQTTService para obtener su estado actual
        val intent = Intent(requireContext(), MQTTService::class.java)
        requireContext().startService(intent)
    }
}