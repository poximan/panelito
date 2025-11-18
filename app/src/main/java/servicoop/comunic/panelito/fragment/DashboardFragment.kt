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

class DashboardFragment : Fragment() {

    private lateinit var indicatorModem: View
    private lateinit var txtModem: TextView
    private lateinit var progressGrado: ProgressBar
    private lateinit var txtGradoPct: TextView
    private lateinit var indicatorSalud: View
    private lateinit var rvGrds: RecyclerView
    private lateinit var grdsAdapter: DisconnectedGrdAdapter
    private var lastModemState: String? = null

    private val mqttReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MQTTService.ACTION_BROKER_ESTADO -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_BROKER_ESTADO) ?: return
                    handleBrokerState(estado)
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
                    Log.e("DashboardFragment", "ERROR: $err")
                }
                MQTTService.ACTION_BACKEND_STATUS -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_BACKEND_STATUS) ?: return
                    handleBackendStatus(estado)
                }
            }
        }
    }

    companion object {
        fun newInstance(): DashboardFragment = DashboardFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_mqtt, container, false)

        indicatorModem = v.findViewById(R.id.indicator_modem)
        txtModem = v.findViewById(R.id.txt_modem)
        progressGrado = v.findViewById(R.id.progress_grado)
        txtGradoPct = v.findViewById(R.id.txt_grado_pct)
        indicatorSalud = v.findViewById(R.id.indicator_salud)

        rvGrds = v.findViewById(R.id.rv_grds)
        rvGrds.layoutManager = LinearLayoutManager(requireContext())
        grdsAdapter = DisconnectedGrdAdapter()
        rvGrds.adapter = grdsAdapter

        return v
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            mqttReceiver,
            IntentFilter().apply {
                addAction(MQTTService.ACTION_BROKER_ESTADO)
                addAction(MQTTService.ACTION_MODEM_ESTADO)
                addAction(MQTTService.ACTION_ACTUALIZAR_GRADO)
                addAction(MQTTService.ACTION_ACTUALIZAR_GRDS)
                addAction(MQTTService.ACTION_ERROR)
                addAction(MQTTService.ACTION_BACKEND_STATUS)
            }
        )
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mqttReceiver)
        super.onStop()
    }

    private fun handleBrokerState(estado: String) {
        val brokerEstado = runCatching { BrokerEstado.valueOf(estado) }.getOrElse { BrokerEstado.ERROR }
        when (brokerEstado) {
            BrokerEstado.CONECTADO -> lastModemState?.let { actualizarModemEstado(it) }
            BrokerEstado.DESCONECTADO -> {
                mostrarModemSinDatos()
                actualizarGrado(0.0)
                grdsAdapter.submit(emptyList())
            }
            BrokerEstado.CONECTANDO,
            BrokerEstado.REINTENTANDO,
            BrokerEstado.ERROR -> mostrarModemSinDatos()
        }
    }

    private fun handleBackendStatus(estado: String) {
        if (estado.equals(MQTTService.STATUS_OFFLINE, ignoreCase = true)) {
            mostrarBackendIncerto()
        }
    }

    private fun actualizarModemEstado(estado: String) {
        lastModemState = estado
        txtModem.text = getString(R.string.modem_status, estado)
        if (estado.equals("CONECTADO", ignoreCase = true)) {
            indicatorModem.setBackgroundResource(R.drawable.led_verde)
        } else {
            indicatorModem.setBackgroundResource(R.drawable.led_rojo)
        }
    }

    private fun mostrarModemSinDatos() {
        txtModem.text = getString(R.string.modem_status, getString(R.string.status_unknown_capitalized))
        indicatorModem.setBackgroundResource(R.drawable.led_naranja)
    }

    private fun mostrarBackendIncerto() {
        mostrarModemSinDatos()
        txtGradoPct.text = getString(R.string.value_not_available)
        progressGrado.progress = 0
        indicatorSalud.setBackgroundResource(R.drawable.led_naranja)
        grdsAdapter.submit(emptyList())
    }

    private fun actualizarGrado(porcentaje: Double) {
        val pct = porcentaje.coerceIn(0.0, 100.0)
        progressGrado.progress = pct.toInt()
        txtGradoPct.text = getString(R.string.percent_format, pct)

        val led = when {
            pct < Thresholds.ROJO -> R.drawable.led_rojo
            pct < Thresholds.AMARILLO -> R.drawable.led_naranja
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
                val nombre = o.optString("nombre", getString(R.string.value_not_available))
                val uc = o.optString("ultima_caida", "")
                out.add(GrdDesconectado(id, nombre, uc))
            }
            grdsAdapter.submit(out)
        } catch (e: Exception) {
            Log.e("DashboardFragment", "Error parseando GRDs: ${e.message}", e)
        }
    }
}
