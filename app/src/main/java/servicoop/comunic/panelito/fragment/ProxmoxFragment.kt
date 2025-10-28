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
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.ProxmoxState
import servicoop.comunic.panelito.core.model.ProxmoxVm
import servicoop.comunic.panelito.services.mqtt.MQTTService
import servicoop.comunic.panelito.ui.adapter.ProxmoxVmAdapter

class ProxmoxFragment : Fragment() {

    private lateinit var statusIndicator: View
    private lateinit var statusText: TextView
    private lateinit var nodeText: TextView
    private lateinit var updatedText: TextView
    private lateinit var missingText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = ProxmoxVmAdapter()

    private var lastPayload: String? = null

    companion object {
        private const val KEY_LAST_PAYLOAD = "last_payload"
        fun newInstance(): ProxmoxFragment = ProxmoxFragment()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MQTTService.ACTION_PROXMOX_ESTADO -> {
                    val raw = intent.getStringExtra(MQTTService.EXTRA_PROXMOX_ESTADO) ?: return
                    Log.d("ProxmoxFragment", "Estado recibido (${raw.length} chars)")
                    lastPayload = raw
                    parseAndRender(raw)
                }
                MQTTService.ACTION_BROKER_ESTADO -> {
                    val estado = intent.getStringExtra(MQTTService.EXTRA_BROKER_ESTADO) ?: return
                    if (!estado.equals(BrokerEstado.CONECTADO.name, ignoreCase = true)) {
                        showOfflineState()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastPayload = savedInstanceState?.getString(KEY_LAST_PAYLOAD)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_proxmox, container, false)
        statusIndicator = view.findViewById(R.id.indicator_proxmox_status)
        statusText = view.findViewById(R.id.txt_proxmox_status)
        nodeText = view.findViewById(R.id.txt_proxmox_node)
        updatedText = view.findViewById(R.id.txt_proxmox_updated)
        missingText = view.findViewById(R.id.txt_proxmox_missing)
        recycler = view.findViewById(R.id.recycler_proxmox)
        emptyView = view.findViewById(R.id.txt_proxmox_empty)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        lastPayload?.let { parseAndRender(it) }

        return view
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(MQTTService.ACTION_PROXMOX_ESTADO)
                addAction(MQTTService.ACTION_BROKER_ESTADO)
            }
        )
        lastPayload?.let { parseAndRender(it) }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastPayload?.let { outState.putString(KEY_LAST_PAYLOAD, it) }
    }

    private fun showOfflineState() {
        lastPayload = null
        statusIndicator.setBackgroundResource(R.drawable.led_rojo)
        statusText.text = getString(R.string.proxmox_status_offline)
        nodeText.isVisible = false
        updatedText.isVisible = false
        missingText.isVisible = false
        adapter.submitList(emptyList())
        recycler.isVisible = false
        emptyView.isVisible = true
    }

    private fun parseAndRender(raw: String) {
        try {
            val obj = JSONObject(raw)
            val state = mapState(obj)
            render(state)
        } catch (e: Exception) {
            Log.e("ProxmoxFragment", "Error parseando estado Proxmox: ${e.message}", e)
        }
    }

    private fun mapState(obj: JSONObject): ProxmoxState {
        val status = obj.optString("status", "offline")
        val ts = obj.optString("ts", "")
        val node = obj.optString("node", "")
        val rawError = obj.optString("error", "")
        val error = rawError.takeIf { it.isNotBlank() }

        val missingRaw = obj.optJSONArray("missing") ?: JSONArray()
        val missing = mutableListOf<Int>()
        for (i in 0 until missingRaw.length()) {
            missing.add(missingRaw.optInt(i))
        }

        val vmsArray = obj.optJSONArray("vms") ?: JSONArray()
        val vms = mutableListOf<ProxmoxVm>()
        for (i in 0 until vmsArray.length()) {
            val vmObj = vmsArray.optJSONObject(i) ?: continue
            val vmid = vmObj.optInt("vmid")
            val name = vmObj.optString("name", "VM $vmid")
            val statusRaw = vmObj.optString("status", "desconocido")
            val statusDisplay = vmObj.optString("status_display", statusRaw.uppercase())
            val cpus = vmObj.optInt("cpus")
            val cpuPct = vmObj.optDouble("cpu_pct", 0.0)
            val memUsed = vmObj.optDouble("mem_used_gb", 0.0)
            val memTotal = vmObj.optDouble("mem_total_gb", 0.0)
            val diskUsed = vmObj.optDouble("disk_used_gb", 0.0)
            val diskTotal = vmObj.optDouble("disk_total_gb", 0.0)
            val diskPct = vmObj.optDouble("disk_pct", Double.NaN).takeUnless { it.isNaN() }
            val diskReadBytes = vmObj.optDouble("disk_read_bytes", 0.0)
            val diskWriteBytes = vmObj.optDouble("disk_write_bytes", 0.0)
            val diskReadRate = vmObj.optDouble("disk_read_rate_bps", 0.0)
            val diskWriteRate = vmObj.optDouble("disk_write_rate_bps", 0.0)
            val memPct = vmObj.optDouble("mem_pct", Double.NaN).takeUnless { it.isNaN() }
            val uptime = vmObj.optString("uptime_human", "0m")

            vms.add(
                ProxmoxVm(
                    vmid = vmid,
                    name = name,
                    status = statusRaw,
                    statusDisplay = statusDisplay,
                    cpus = cpus,
                    cpuPct = cpuPct,
                    memUsedGb = memUsed,
                    memTotalGb = memTotal,
                    memPct = memPct,
                    diskUsedGb = diskUsed,
                    diskTotalGb = diskTotal,
                    diskPct = diskPct,
                    diskReadBytes = diskReadBytes,
                    diskWriteBytes = diskWriteBytes,
                    diskReadRateBps = diskReadRate,
                    diskWriteRateBps = diskWriteRate,
                    uptime = uptime
                )
            )
        }

        return ProxmoxState(
            status = status,
            timestamp = ts,
            node = node,
            error = error,
            missing = missing,
            vms = vms
        )
    }

    private fun render(state: ProxmoxState) {
        val online = state.status.equals("online", ignoreCase = true)
        if (online) {
            statusIndicator.setBackgroundResource(R.drawable.led_verde)
            statusText.text = getString(R.string.proxmox_status_online)
        } else {
            statusIndicator.setBackgroundResource(R.drawable.led_rojo)
            statusText.text = getString(R.string.proxmox_status_offline)
        }

        if (state.node.isNotBlank()) {
            nodeText.text = getString(R.string.proxmox_node_label, state.node)
            nodeText.isVisible = true
        } else {
            nodeText.isVisible = false
        }

        if (state.timestamp.isNotBlank()) {
            updatedText.isVisible = true
            updatedText.text = getString(R.string.proxmox_updated_at, state.timestamp)
        } else {
            updatedText.isVisible = false
        }

        when {
            !state.error.isNullOrBlank() -> {
                missingText.isVisible = true
                missingText.text = state.error
            }
            state.missing.isNotEmpty() -> {
                val label = state.missing.joinToString(", ")
                missingText.isVisible = true
                missingText.text = getString(R.string.proxmox_missing, label)
            }
            else -> missingText.isVisible = false
        }

        val ordered = state.vms.sortedBy { it.vmid }
        adapter.submitList(ordered)
        recycler.isVisible = ordered.isNotEmpty()
        emptyView.isVisible = ordered.isEmpty()
    }
}
