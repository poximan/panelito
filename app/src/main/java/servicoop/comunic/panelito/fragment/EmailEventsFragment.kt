package servicoop.comunic.panelito.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.services.mqtt.MQTTService
import servicoop.comunic.panelito.ui.MainActivity
import servicoop.comunic.panelito.ui.adapter.EmailEventsAdapter
import java.util.ArrayList
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EmailEventsFragment : Fragment() {

    private lateinit var indicatorStatus: View
    private lateinit var txtSummary: TextView
    private lateinit var txtSmtp: TextView
    private lateinit var txtPingLocal: TextView
    private lateinit var txtPingRemote: TextView
    private lateinit var btnEmailTest: Button
    private lateinit var recycler: RecyclerView
    private val adapter = EmailEventsAdapter()
    private val events = mutableListOf<EmailEvent>()
    private var lastEmailStateJson: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MQTTService.ACTION_EMAIL_EVENT -> {
                    val snapshot = intent.getEmailEventList(MQTTService.EXTRA_EMAIL_EVENT_LIST)
                    val single = intent.getEmailEvent(MQTTService.EXTRA_EMAIL_EVENT)
                    when {
                        snapshot != null -> {
                            events.clear()
                            events.addAll(snapshot.take(MAX_EVENTS))
                            submitList()
                        }
                        single != null -> {
                            events.remove(single)
                            events.add(0, single)
                            if (events.size > MAX_EVENTS) {
                                events.removeAt(events.lastIndex)
                            }
                            submitList()
                        }
                    }
                }
                MQTTService.ACTION_EMAIL_ESTADO -> {
                    val raw = intent.getStringExtra(MQTTService.EXTRA_EMAIL_ESTADO) ?: return
                    lastEmailStateJson = raw
                    actualizarEmailEstado(raw)
                }
                MQTTService.ACTION_BROKER_ESTADO -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_BROKER_ESTADO) ?: return
                    handleBrokerState(estado)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            val restored = savedInstanceState.getEmailEventList(KEY_EVENTS_STATE)
            if (restored != null) {
                events.clear()
                events.addAll(restored)
            }
            lastEmailStateJson = savedInstanceState.getString(KEY_EMAIL_STATE)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_email_events, container, false)
        indicatorStatus = view.findViewById(R.id.indicator_email_status)
        txtSummary = view.findViewById(R.id.txt_email_status_summary)
        txtSmtp = view.findViewById(R.id.txt_email_status_smtp)
        txtPingLocal = view.findViewById(R.id.txt_email_status_ping_local)
        txtPingRemote = view.findViewById(R.id.txt_email_status_ping_remote)
        btnEmailTest = view.findViewById(R.id.btn_email_test)
        recycler = view.findViewById(R.id.recycler_email_events)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        btnEmailTest.isEnabled = (activity as? MainActivity)?.isBrokerDesiredEnabled() == true
        btnEmailTest.setOnClickListener { solicitarEmailTest() }

        submitList()
        lastEmailStateJson?.let { actualizarEmailEstado(it) }

        return view
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(MQTTService.ACTION_EMAIL_EVENT)
                addAction(MQTTService.ACTION_EMAIL_ESTADO)
                addAction(MQTTService.ACTION_BROKER_ESTADO)
            }
        )
        submitList()
        requestCachedState()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_EVENTS_STATE, ArrayList(events))
        lastEmailStateJson?.let { outState.putString(KEY_EMAIL_STATE, it) }
    }

    private fun requestCachedState() {
        val host = activity as? MainActivity ?: return
        val repo = host.settingsRepo
        viewLifecycleOwner.lifecycleScope.launch {
            val enabled = repo.getServiceEnabled().first()
            btnEmailTest.isEnabled = enabled && host.isBrokerDesiredEnabled()
            if (enabled) {
                val intent = Intent(requireContext(), MQTTService::class.java).apply {
                    putExtra(MQTTService.EXTRA_SOLICITAR_ESTADO, true)
                }
                requireContext().startService(intent)
            }
        }
    }

    private fun submitList() {
        adapter.submit(events)
    }

    private fun solicitarEmailTest() {
        val host = activity as? MainActivity
        if (host == null) {
            Toast.makeText(requireContext(), R.string.email_test_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        if (!host.isBrokerDesiredEnabled()) {
            Toast.makeText(requireContext(), R.string.email_test_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), MQTTService::class.java).apply {
            action = MQTTService.ACTION_RPC_EMAIL_TEST
        }
        requireContext().startService(intent)
        Toast.makeText(requireContext(), R.string.email_test_sent, Toast.LENGTH_SHORT).show()
    }

    private fun handleBrokerState(raw: String) {
        val state = runCatching { BrokerEstado.valueOf(raw) }.getOrElse { BrokerEstado.ERROR }
        when (state) {
            BrokerEstado.CONECTADO -> {
                btnEmailTest.isEnabled = (activity as? MainActivity)?.isBrokerDesiredEnabled() == true
                lastEmailStateJson?.let { actualizarEmailEstado(it) }
            }
            BrokerEstado.CONECTANDO,
            BrokerEstado.REINTENTANDO -> {
                btnEmailTest.isEnabled = false
            }
            BrokerEstado.DESCONECTADO,
            BrokerEstado.ERROR -> {
                mostrarEstadoDesconectado()
            }
        }
    }

    private fun mostrarEstadoDesconectado() {
        lastEmailStateJson = null
        indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
        txtSummary.text = getString(R.string.email_summary_unknown)
        txtSmtp.text = getString(R.string.email_smtp_placeholder)
        txtPingLocal.text = getString(R.string.email_ping_local_placeholder)
        txtPingRemote.text = getString(R.string.email_ping_remote_placeholder)
        events.clear()
        submitList()
        btnEmailTest.isEnabled = false
    }

    private fun actualizarEmailEstado(json: String) {
        try {
            val root = JSONObject(json)
            val defaultUnknown = getString(R.string.status_unknown)
            val smtp = root.optString("smtp", defaultUnknown)
            val pingLocal = root.optString("ping_local", defaultUnknown)
            val pingRemoto = root.optString("ping_remoto", defaultUnknown)
            val ts = root.optString("ts", "")

            txtSmtp.text = getString(R.string.email_smtp_format, formatearEstado(smtp))
            txtPingLocal.text = getString(R.string.email_ping_local_format, formatearEstado(pingLocal))
            txtPingRemote.text = getString(R.string.email_ping_remote_format, formatearEstado(pingRemoto))

            val estados = listOf(smtp, pingLocal, pingRemoto)
            val resumen = when {
                estados.any { it.equals("desconectado", ignoreCase = true) } -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
                    getString(R.string.email_summary_no_service)
                }
                estados.any { it.equals("desconocido", ignoreCase = true) } -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_naranja)
                    getString(R.string.email_summary_unknown)
                }
                else -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_verde)
                    getString(R.string.email_summary_operational)
                }
            }
            val sello = if (ts.isNotBlank()) " - $ts" else ""
            txtSummary.text = resumen + sello
        } catch (e: Exception) {
            txtSummary.text = getString(R.string.email_summary_unknown)
            indicatorStatus.setBackgroundResource(R.drawable.led_naranja)
        }
    }

    private fun formatearEstado(valor: String): String {
        val base = valor.ifBlank { getString(R.string.status_unknown) }
        val lower = base.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
        }
    }

    private fun Intent.getEmailEvent(key: String): EmailEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(key, EmailEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(key) as? EmailEvent
        }

    @Suppress("UNCHECKED_CAST")
    private fun Intent.getEmailEventList(key: String): List<EmailEvent>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializableExtra(key, ArrayList::class.java) as? ArrayList<EmailEvent>
        } else {
            @Suppress("DEPRECATION")
            getSerializableExtra(key) as? ArrayList<EmailEvent>
        }

    @Suppress("UNCHECKED_CAST")
    private fun Bundle.getEmailEventList(key: String): List<EmailEvent>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getSerializable(key, ArrayList::class.java) as? ArrayList<EmailEvent>
        } else {
            @Suppress("DEPRECATION")
            getSerializable(key) as? ArrayList<EmailEvent>
        }

    companion object {
        private const val KEY_EVENTS_STATE = "email_events_state"
        private const val KEY_EMAIL_STATE = "email_state_json"
        private const val MAX_EVENTS = 50

        fun newInstance(): EmailEventsFragment = EmailEventsFragment()
    }
}

