package servicoop.comunic.panelito.fragment

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.PanelitoApplication
import servicoop.comunic.panelito.core.model.ProxmoxState
import servicoop.comunic.panelito.core.model.ProxmoxStateParser
import servicoop.comunic.panelito.core.time.AppTime
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

    private val mqttSession
        get() = (requireActivity().application as PanelitoApplication).mqttSession

    companion object {
        fun newInstance(): ProxmoxFragment = ProxmoxFragment()
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

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mqttSession.state.collect { state ->
                    state.proxmoxJson?.let(::parseAndRender) ?: showOfflineState()
                }
            }
        }
    }

    private fun showOfflineState() {
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
            render(ProxmoxStateParser.parse(raw))
        } catch (e: Exception) {
            Log.e("ProxmoxFragment", "Error parseando estado Proxmox: ${e.message}", e)
        }
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

        val normalizedTs = AppTime.formatForPresentation(state.timestamp, "")
        if (normalizedTs.isNotBlank()) {
            updatedText.isVisible = true
            updatedText.text = getString(R.string.proxmox_updated_at, normalizedTs)
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
