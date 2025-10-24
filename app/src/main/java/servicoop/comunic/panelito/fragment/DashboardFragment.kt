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
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.GrdDesconectado
import servicoop.comunic.panelito.core.util.Thresholds
import servicoop.comunic.panelito.domain.repository.SettingsRepository
import servicoop.comunic.panelito.services.mqtt.MQTTService
import servicoop.comunic.panelito.ui.MainActivity
import servicoop.comunic.panelito.ui.adapter.DisconnectedGrdAdapter
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var switchConnect: Switch

    // Broker (local)
    private lateinit var indicatorBroker: View
    private lateinit var txtBroker: TextView

    // Modem (remoto)
    private lateinit var indicatorModem: View
    private lateinit var txtModem: TextView

    // Correo
    private lateinit var indicatorEmail: View
    private lateinit var txtEmailResumen: TextView
    private lateinit var txtEmailSmtp: TextView
    private lateinit var txtEmailPingLocal: TextView
    private lateinit var txtEmailPingRemoto: TextView

    // Grado
    private lateinit var progressGrado: ProgressBar
    private lateinit var txtGradoPct: TextView
    private lateinit var indicatorSalud: View

    // Lista GRDs
    private lateinit var rvGrds: RecyclerView
    private lateinit var grdsAdapter: DisconnectedGrdAdapter

    // Repo de settings (inyectado por la actividad)
    private lateinit var settingsRepo: SettingsRepository

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
                MQTTService.ACTION_EMAIL_ESTADO -> {
                    val json = intent.getStringExtra(MQTTService.EXTRA_EMAIL_ESTADO) ?: return
                    actualizarEmailEstado(json)
                }
                MQTTService.ACTION_ERROR -> {
                    val err = intent.getStringExtra(MQTTService.EXTRA_ERROR) ?: return
                    Log.e("DashboardFragment", "ERROR: $err")
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

        // inyeccion: tomar repo desde la actividad
        settingsRepo = (requireActivity() as MainActivity).settingsRepo

        switchConnect = v.findViewById(R.id.switch_connect)

        indicatorBroker = v.findViewById(R.id.indicator_broker)
        txtBroker = v.findViewById(R.id.txt_broker)

        indicatorModem = v.findViewById(R.id.indicator_modem)
        txtModem = v.findViewById(R.id.txt_modem)

        indicatorEmail = v.findViewById(R.id.indicator_email)
        txtEmailResumen = v.findViewById(R.id.txt_email_resumen)
        txtEmailSmtp = v.findViewById(R.id.txt_email_smtp)
        txtEmailPingLocal = v.findViewById(R.id.txt_email_ping_local)
        txtEmailPingRemoto = v.findViewById(R.id.txt_email_ping_remoto)

        progressGrado = v.findViewById(R.id.progress_grado)
        txtGradoPct = v.findViewById(R.id.txt_grado_pct)
        indicatorSalud = v.findViewById(R.id.indicator_salud)

        rvGrds = v.findViewById(R.id.rv_grds)
        rvGrds.layoutManager = LinearLayoutManager(requireContext())
        grdsAdapter = DisconnectedGrdAdapter()
        rvGrds.adapter = grdsAdapter

        inicializarBrokerSwitch()

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
                addAction(MQTTService.ACTION_EMAIL_ESTADO)
                addAction(MQTTService.ACTION_ERROR)
            }
        )
        aplicarEstadoActual()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(mqttReceiver)
        super.onStop()
    }

    private fun inicializarBrokerSwitch() {
        lifecycleScope.launch {
            // asegura defaults de primera ejecucion (ON la primera vez)
            settingsRepo.ensureDefaults()

            val enabled = settingsRepo.getServiceEnabled().first()
            switchConnect.isChecked = enabled

            switchConnect.setOnCheckedChangeListener { _, isChecked ->
                lifecycleScope.launch {
                    settingsRepo.setServiceEnabled(isChecked)
                }
                if (isChecked) {
                    requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
                } else {
                    requireContext().stopService(Intent(requireContext(), MQTTService::class.java))
                    // estado visual consistente al apagar
                    actualizarBrokerEstado(BrokerEstado.DESCONECTADO.name)
                    actualizarModemEstado(getString(R.string.status_disconnected_upper))
                    indicatorEmail.setBackgroundResource(R.drawable.led_rojo)
                    txtEmailResumen.text = getString(R.string.email_summary_no_data)
                    txtEmailSmtp.text = getString(R.string.email_smtp_placeholder)
                    txtEmailPingLocal.text = getString(R.string.email_ping_local_placeholder)
                    txtEmailPingRemoto.text = getString(R.string.email_ping_remote_placeholder)
                }
            }

            // si el usuario tenia ON, arrancar el servicio
            if (enabled) {
                requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
            }
        }
    }

    private fun aplicarEstadoActual() {
        lifecycleScope.launch {
            val enabled = settingsRepo.getServiceEnabled().first()
            if (enabled) {
                requireContext().startForegroundService(Intent(requireContext(), MQTTService::class.java))
            } else {
                requireContext().stopService(Intent(requireContext(), MQTTService::class.java))
                actualizarBrokerEstado(BrokerEstado.DESCONECTADO.name)
                actualizarModemEstado(getString(R.string.status_disconnected_upper))
                indicatorEmail.setBackgroundResource(R.drawable.led_rojo)
                txtEmailResumen.text = getString(R.string.email_summary_no_data)
                txtEmailSmtp.text = getString(R.string.email_smtp_placeholder)
                txtEmailPingLocal.text = getString(R.string.email_ping_local_placeholder)
                txtEmailPingRemoto.text = getString(R.string.email_ping_remote_placeholder)
            }
        }
    }

    // ---- UI updates

    private fun actualizarBrokerEstado(estado: String) {
        txtBroker.text = getString(R.string.broker_status, estado)
        when (estado.uppercase(Locale.getDefault())) {
            BrokerEstado.CONECTADO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_verde)
            }
            BrokerEstado.CONECTANDO.name, BrokerEstado.REINTENTANDO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_naranja)
            }
            BrokerEstado.ERROR.name, BrokerEstado.DESCONECTADO.name -> {
                indicatorBroker.setBackgroundResource(R.drawable.led_rojo)
            }
            else -> indicatorBroker.setBackgroundResource(R.drawable.led_rojo)
        }
    }

    private fun actualizarModemEstado(estado: String) {
        txtModem.text = getString(R.string.modem_status, estado)
        if (estado.equals("CONECTADO", ignoreCase = true)) {
            indicatorModem.setBackgroundResource(R.drawable.led_verde)
        } else {
            indicatorModem.setBackgroundResource(R.drawable.led_rojo)
        }
    }

    private fun actualizarEmailEstado(json: String) {
        try {
            val root = JSONObject(json)
            val defaultUnknown = getString(R.string.status_unknown)
            val smtp = root.optString("smtp", defaultUnknown)
            val pingLocal = root.optString("ping_local", defaultUnknown)
            val pingRemoto = root.optString("ping_remoto", defaultUnknown)
            val ts = root.optString("ts", "")

            txtEmailSmtp.text = getString(R.string.email_smtp_format, formatearEstado(smtp))
            txtEmailPingLocal.text = getString(R.string.email_ping_local_format, formatearEstado(pingLocal))
            txtEmailPingRemoto.text = getString(R.string.email_ping_remote_format, formatearEstado(pingRemoto))

            val estados = listOf(smtp, pingLocal, pingRemoto)
            val resumen = when {
                estados.any { it.equals("desconectado", ignoreCase = true) } -> {
                    indicatorEmail.setBackgroundResource(R.drawable.led_rojo)
                    getString(R.string.email_summary_no_service)
                }
                estados.any { it.equals("desconocido", ignoreCase = true) } -> {
                    indicatorEmail.setBackgroundResource(R.drawable.led_naranja)
                    getString(R.string.email_summary_unknown)
                }
                else -> {
                    indicatorEmail.setBackgroundResource(R.drawable.led_verde)
                    getString(R.string.email_summary_operational)
                }
            }
            val sello = if (ts.isNotBlank()) " - $ts" else ""
            txtEmailResumen.text = resumen + sello
        } catch (e: Exception) {
            Log.e("DashboardFragment", "Error parseando estado correo: ${e.message}", e)
        }
    }

    private fun formatearEstado(valor: String): String {
        val base = valor.ifBlank { getString(R.string.status_unknown) }
        val lower = base.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
        }
    }

    private fun actualizarGrado(porcentaje: Double) {
        val pctInt = porcentaje.coerceIn(0.0, 100.0).toInt()
        progressGrado.progress = pctInt
        txtGradoPct.text = getString(R.string.percent_format, porcentaje)

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