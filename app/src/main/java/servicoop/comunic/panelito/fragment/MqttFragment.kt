package servicoop.comunic.panelito.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.GrdDesconectado
import servicoop.comunic.panelito.core.util.Thresholds
import servicoop.comunic.panelito.services.mqtt.MQTTService
import servicoop.comunic.panelito.ui.adapter.DisconnectedGrdAdapter
import java.util.Locale

class MqttFragment : Fragment() {

    private lateinit var switchConnect: Switch

    // Broker (local)
    private lateinit var indicatorBroker: View
    private lateinit var txtBroker: TextView

    // Modem (remoto)
    private lateinit var indicatorModem: View
    private lateinit var txtModem: TextView

    // Grado
    private lateinit var progressGrado: ProgressBar
    private lateinit var txtGradoPct: TextView
    private lateinit var indicatorSalud: View

    // Lista GRDs
    private lateinit var rvGrds: RecyclerView
    private lateinit var grdsAdapter: DisconnectedGrdAdapter

    private val mqttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MQTTService.ACTION_BROKER_ESTADO -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_BROKER_ESTADO) ?: return
                    actualizarBrokerEstado(estado)
                }
                MQTTService.ACTION_MODEM_ESTADO -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_MODEM_ESTADO) ?: return
                    actualizarModemEstado(estado)
                }
                MQTTService.ACTION_ACTUALIZAR_GRADO -> {
                    val pct = intent.getDoubleExtra(MQTTService.EXTRA_GRADO_PCT, Double.NaN)
                    if (!pct.isNaN()) actualizarGrado(pct)
                }
                MQTTService.ACTION_ACTUALIZAR_GRDS -> {
                    val json = intent.getStringExtra(MQTTService.EXTRA_GRDS_JSON) ?: return
                    actualizarGrds(json)
                }
                MQTTService.ACTION_ERROR -> {
                    val err = intent.getStringExtra(MQTTService.EXTRA_ERROR) ?: return
                    Log.e("MqttFragment", "ERROR: $err")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_mqtt, container, false)

        switchConnect = v.findViewById(R.id.switch_connect)

        indicatorBroker = v.findViewById(R.id.indicator_broker)
        txtBroker = v.findViewById(R.id.txt_broker)

        indicatorModem = v.findViewById(R.id.indicator_modem)
        txtModem = v.findViewById(R.id.txt_modem)

        progressGrado = v.findViewById(R.id.progress_grado)
        txtGradoPct = v.findViewById(R.id.txt_grado_pct)
        indicatorSalud = v.findViewById(R.id.indicator_salud)

        rvGrds = v.findViewById(R.id.rv_grds)
        grdsAdapter = DisconnectedGrdAdapter()
        rvGrds.layoutManager = LinearLayoutManager(requireContext())
        rvGrds.adapter = grdsAdapter

        switchConnect.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) iniciarServicio() else detenerServicio()
        }

        return v
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MQTTService.ACTION_BROKER_ESTADO)
            addAction(MQTTService.ACTION_MODEM_ESTADO)
            addAction(MQTTService.ACTION_ACTUALIZAR_GRADO)
            addAction(MQTTService.ACTION_ACTUALIZAR_GRDS)
            addAction(MQTTService.ACTION_ERROR)
        }
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(mqttReceiver, filter)

        // solicitar ultimo estado al servicio
        val i = Intent(requireContext(), MQTTService::class.java).apply {
            putExtra(MQTTService.EXTRA_SOLICITAR_ESTADO, true)
        }
        requireContext().startService(i)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mqttReceiver)
    }

    private fun iniciarServicio() {
        requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
    }

    private fun detenerServicio() {
        requireContext().stopService(Intent(requireContext(), MQTTService::class.java))
        actualizarBrokerEstado(BrokerEstado.DESCONECTADO.name)
        actualizarModemEstado("DESCONECTADO")
    }

    // UI updates
    private fun actualizarBrokerEstado(estado: String) {
        txtBroker.text = "Broker: $estado"
        when (estado.uppercase(Locale.getDefault())) {
            BrokerEstado.CONECTADO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_verde)
                switchConnect.isChecked = true
            }
            BrokerEstado.CONECTANDO.name, BrokerEstado.REINTENTANDO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_naranja)
                switchConnect.isChecked = true
            }
            BrokerEstado.ERROR.name, BrokerEstado.DESCONECTADO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_rojo)
            }
            else -> indicatorBroker.setBackgroundResource(R.drawable.led_rojo)
        }
    }

    private fun actualizarModemEstado(estado: String) {
        txtModem.text = "Modem: $estado"
        if (estado.equals("CONECTADO", ignoreCase = true)) {
            indicatorModem.setBackgroundResource(R.drawable.led_verde)
        } else {
            indicatorModem.setBackgroundResource(R.drawable.led_rojo)
        }
    }

    private fun actualizarGrado(porcentaje: Double) {
        val pctInt = porcentaje.coerceIn(0.0, 100.0).toInt()
        progressGrado.progress = pctInt
        txtGradoPct.text = String.format(Locale.getDefault(), "%.1f%%", porcentaje)

        val led = when {
            porcentaje < Thresholds.ROJO -> R.drawable.led_rojo
            porcentaje < Thresholds.AMARILLO -> R.drawable.led_naranja
            else -> R.drawable.led_verde
        }
        indicatorSalud.setBackgroundResource(led)
    }

    private fun actualizarGrds(json: String) {
        try {
            val root = JSONObject(json)
            val arr = root.optJSONArray("items") ?: return
            val out = ArrayList<GrdDesconectado>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optInt("id")
                val nombre = o.optString("nombre", "N/D")
                val uc = o.optString("ultima_caida", "")
                out.add(GrdDesconectado(id, nombre, uc))
            }
            grdsAdapter.submit(out)
        } catch (e: Exception) {
            Log.e("MqttFragment", "Error parseando GRDs: ${e.message}", e)
        }
    }
}