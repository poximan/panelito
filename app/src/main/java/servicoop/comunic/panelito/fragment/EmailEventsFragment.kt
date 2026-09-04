package servicoop.comunic.panelito.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.PanelitoApplication
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.core.model.EmailServiceHealth
import servicoop.comunic.panelito.core.model.EmailServiceStateParser
import servicoop.comunic.panelito.core.time.AppTime
import servicoop.comunic.panelito.ui.adapter.EmailEventsAdapter
import java.util.Locale

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
    private var hasRenderedEmailState = false
    private val mqttSession
        get() = (requireActivity().application as PanelitoApplication).mqttSession

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
        btnEmailTest.setOnClickListener { solicitarEmailTest() }

        submitList()
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mqttSession.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: servicoop.comunic.panelito.services.mqtt.MqttState) {
        btnEmailTest.isEnabled = state.broker == BrokerEstado.CONECTADO
        events.clear()
        events.addAll(state.emailEvents.take(MAX_EVENTS))
        submitList()
        if (state.emailStateJson != null) {
            lastEmailStateJson = state.emailStateJson
            actualizarEmailEstado(state.emailStateJson)
        }
        if (state.broker != BrokerEstado.CONECTADO && lastEmailStateJson == null) {
            mostrarEstadoDesconectado()
        }
    }

    private fun submitList() {
        adapter.submit(events)
    }

    private fun solicitarEmailTest() {
        if (mqttSession.state.value.broker != BrokerEstado.CONECTADO) {
            Toast.makeText(requireContext(), R.string.email_test_disabled, Toast.LENGTH_SHORT).show()
            return
        }
        mqttSession.sendEmailTest()
        Toast.makeText(requireContext(), R.string.email_test_sent, Toast.LENGTH_SHORT).show()
    }

    private fun mostrarEstadoDesconectado() {
        indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
        txtSummary.text = getString(R.string.email_summary_unknown)
        txtSmtp.text = getString(R.string.email_smtp_placeholder)
        txtPingLocal.text = getString(R.string.email_ping_local_placeholder)
        txtPingRemote.text = getString(R.string.email_ping_remote_placeholder)
        btnEmailTest.isEnabled = false
    }

    private fun actualizarEmailEstado(json: String) {
        try {
            val defaultUnknown = getString(R.string.status_unknown)
            val state = EmailServiceStateParser.parse(json, defaultUnknown)
            val tsFormatted = AppTime.formatForPresentation(state.timestamp, "")

            txtSmtp.text = getString(R.string.email_smtp_format, formatearEstado(state.smtp))
            txtPingLocal.text = getString(R.string.email_ping_local_format, formatearEstado(state.pingLocal))
            txtPingRemote.text = getString(R.string.email_ping_remote_format, formatearEstado(state.pingRemote))

            val resumen = when (state.health) {
                EmailServiceHealth.NO_SERVICE -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_rojo)
                    getString(R.string.email_summary_no_service)
                }
                EmailServiceHealth.UNKNOWN -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_naranja)
                    getString(R.string.email_summary_unknown)
                }
                EmailServiceHealth.OPERATIONAL -> {
                    indicatorStatus.setBackgroundResource(R.drawable.led_verde)
                    getString(R.string.email_summary_operational)
                }
            }
            val sello = if (tsFormatted.isNotBlank()) " - $tsFormatted" else ""
            txtSummary.text = resumen + sello
            hasRenderedEmailState = true
        } catch (_: Exception) {
            if (!hasRenderedEmailState) mostrarEstadoDesconectado()
        }
    }

    private fun formatearEstado(valor: String): String {
        val base = valor.trim().ifBlank { getString(R.string.status_unknown) }
        val lower = base.lowercase(Locale.getDefault())
        return lower.replaceFirstChar { ch ->
            if (ch.isLowerCase()) ch.titlecase(Locale.getDefault()) else ch.toString()
        }
    }

    companion object {
        private const val MAX_EVENTS = 50

        fun newInstance(): EmailEventsFragment = EmailEventsFragment()
    }
}
