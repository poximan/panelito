package servicoop.comunic.panelito.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.PanelitoApplication
import servicoop.comunic.panelito.core.model.CharitoStateParser
import servicoop.comunic.panelito.ui.adapter.CharitoInstanceAdapter

class CharitoFragment : Fragment() {
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private val adapter = CharitoInstanceAdapter()
    private val mqttSession
        get() = (requireActivity().application as PanelitoApplication).mqttSession

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_charito, container, false)
        recycler = view.findViewById(R.id.recycler_charito_instances)
        empty = view.findViewById(R.id.txt_charito_empty)
        recycler.layoutManager = GridLayoutManager(requireContext(), resolveSpanCount())
        recycler.adapter = adapter
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter.resetExpandedState()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                mqttSession.state.collect { state ->
                    state.charitoJson?.let(::parseAndRender) ?: showEmptyState()
                }
            }
        }
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
            if (adapter.itemCount == 0) showEmptyState()
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
