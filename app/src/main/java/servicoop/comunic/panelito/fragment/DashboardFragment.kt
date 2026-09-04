package servicoop.comunic.panelito.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.PanelitoApplication
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.DashboardGrdParser
import servicoop.comunic.panelito.core.util.Thresholds
import servicoop.comunic.panelito.core.model.ModemEstado
import servicoop.comunic.panelito.services.mqtt.MqttState
import servicoop.comunic.panelito.ui.adapter.DisconnectedGrdAdapter

class DashboardFragment : Fragment() {

    private lateinit var indicatorModem: View
    private lateinit var txtModem: TextView
    private lateinit var progressGrado: ProgressBar
    private lateinit var txtGradoPct: TextView
    private lateinit var indicatorSalud: View
    private lateinit var rvGrds: RecyclerView
    private lateinit var grdsAdapter: DisconnectedGrdAdapter
    private val mqttSession
        get() = (requireActivity().application as PanelitoApplication).mqttSession

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mqttSession.state.collect { render(it) }
            }
        }
    }

    private fun render(state: MqttState) {
        val brokerEstado = state.broker
        when (brokerEstado) {
            BrokerEstado.CONECTADO -> {
                actualizarModemEstado(state.modem)
                state.gradoPct?.let { actualizarGrado(it) }
                state.grdsJson?.let { actualizarGrds(it) }
            }
            BrokerEstado.DESCONECTADO -> {
                mostrarModemSinDatos()
            }
            BrokerEstado.CONECTANDO,
            BrokerEstado.REINTENTANDO,
            BrokerEstado.ERROR -> mostrarModemSinDatos()
        }
        state.error?.let { Log.e("DashboardFragment", it) }
    }

    private fun actualizarModemEstado(estado: ModemEstado) {
        val estadoTexto = when (estado) {
            ModemEstado.ABIERTO -> getString(R.string.modem_state_open)
            ModemEstado.CERRADO -> getString(R.string.modem_state_closed)
            ModemEstado.DESCONOCIDO -> getString(R.string.status_unknown_capitalized)
        }
        txtModem.text = getString(R.string.modem_status, estadoTexto)
        val led = when (estado) {
            ModemEstado.ABIERTO -> R.drawable.led_verde
            ModemEstado.CERRADO -> R.drawable.led_rojo
            ModemEstado.DESCONOCIDO -> R.drawable.led_naranja
        }
        indicatorModem.setBackgroundResource(led)
    }

    private fun mostrarModemSinDatos() {
        txtModem.text = getString(R.string.modem_status, getString(R.string.status_unknown_capitalized))
        indicatorModem.setBackgroundResource(R.drawable.led_naranja)
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
            grdsAdapter.submit(
                DashboardGrdParser.parse(json, getString(R.string.value_not_available))
            )
        } catch (e: Exception) {
            Log.e("DashboardFragment", "Error parseando GRDs: ${e.message}", e)
        }
    }
}
