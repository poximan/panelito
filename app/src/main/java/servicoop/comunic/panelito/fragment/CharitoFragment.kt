package servicoop.comunic.panelito.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.CharitoStateParser
import servicoop.comunic.panelito.services.mqtt.MQTTService
import servicoop.comunic.panelito.ui.MainActivity
import servicoop.comunic.panelito.ui.adapter.CharitoInstanceAdapter

class CharitoFragment : Fragment() {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = CharitoInstanceAdapter()
    private var lastPayload: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != MQTTService.ACTION_CHARITO_ESTADO) return
            val raw = intent.getStringExtra(MQTTService.EXTRA_CHARITO_ESTADO) ?: return
            lastPayload = raw
            parseAndRender(raw)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastPayload = savedInstanceState?.getString("charito_last_state")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_charito, container, false)
        recycler = view.findViewById(R.id.recycler_charito_instances)
        empty = view.findViewById(R.id.txt_charito_empty)
        recycler.layoutManager = GridLayoutManager(requireContext(), resolveSpanCount())
        recycler.adapter = adapter
        return view
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            receiver,
            IntentFilter(MQTTService.ACTION_CHARITO_ESTADO)
        )
        requestCachedState()
        adapter.resetExpandedState()
        lastPayload?.let { parseAndRender(it) } ?: showEmptyState()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastPayload?.let { outState.putString("charito_last_state", it) }
    }

    private fun requestCachedState() {
        val hostActivity = activity as? MainActivity ?: return
        if (!hostActivity.isBrokerDesiredEnabled()) return
        val intent = Intent(requireContext(), MQTTService::class.java).apply {
            putExtra(MQTTService.EXTRA_SOLICITAR_ESTADO, true)
        }
        requireContext().startService(intent)
    }

    private fun parseAndRender(raw: String) {
        try {
            val items = CharitoStateParser.parse(raw)
            adapter.submitList(items)
            if (items.isEmpty()) {
                showEmptyState()
            } else {
                empty.isVisible = false
                recycler.isVisible = true
            }
        } catch (_: Exception) {
            showEmptyState()
        }
    }

    private fun showEmptyState() {
        empty.isVisible = true
        empty.text = getString(R.string.charo_list_empty)
        recycler.isVisible = false
    }

    private fun resolveSpanCount(): Int {
        val config = resources.configuration
        val width = if (config.smallestScreenWidthDp > 0) config.smallestScreenWidthDp else config.screenWidthDp
        return if (width >= 600) 2 else 1
    }

    companion object {
        fun newInstance(): CharitoFragment = CharitoFragment()
    }
}
