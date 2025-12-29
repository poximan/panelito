package servicoop.comunic.panelito.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.GeEstado

sealed class CheatSheetEntry {
    data class Endpoint(
        val id: Int,
        val title: String,
        val url: String,
        val ip: String,
        val pingResult: String,
        val isPingRunning: Boolean = false
    ) : CheatSheetEntry()

    data class TcpProbe(
        val id: Int,
        val title: String,
        val host: String,
        val port: Int,
        val result: String,
        val isRunning: Boolean = false
    ) : CheatSheetEntry()

    data class GeStatus(
        val id: Int,
        val title: String,
        val estado: GeEstado
    ) : CheatSheetEntry()

    data class Notes(val text: String) : CheatSheetEntry()
}

class CheatSheetAdapter(
    private val onVisitClicked: (CheatSheetEntry.Endpoint) -> Unit,
    private val onPingClicked: (CheatSheetEntry.Endpoint) -> Unit,
    private val onTcpProbeClicked: (CheatSheetEntry.TcpProbe) -> Unit
) : ListAdapter<CheatSheetEntry, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is CheatSheetEntry.Endpoint -> VIEW_TYPE_ENDPOINT
        is CheatSheetEntry.TcpProbe -> VIEW_TYPE_TCP_PROBE
        is CheatSheetEntry.GeStatus -> VIEW_TYPE_GE_STATUS
        is CheatSheetEntry.Notes -> VIEW_TYPE_NOTES
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_ENDPOINT -> {
                val view = inflater.inflate(R.layout.item_cheat_sheet_endpoint, parent, false)
                EndpointViewHolder(view, onVisitClicked, onPingClicked)
            }

            VIEW_TYPE_TCP_PROBE -> {
                val view = inflater.inflate(R.layout.item_cheat_sheet_tcp_probe, parent, false)
                TcpProbeViewHolder(view, onTcpProbeClicked)
            }

            VIEW_TYPE_GE_STATUS -> {
                val view = inflater.inflate(R.layout.item_cheat_sheet_ge_status, parent, false)
                GeStatusViewHolder(view)
            }

            VIEW_TYPE_NOTES -> {
                val view = inflater.inflate(R.layout.item_cheat_sheet_notes, parent, false)
                NotesViewHolder(view)
            }

            else -> throw IllegalArgumentException("Unexpected view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is EndpointViewHolder -> holder.bind(getItem(position) as CheatSheetEntry.Endpoint)
            is TcpProbeViewHolder -> holder.bind(getItem(position) as CheatSheetEntry.TcpProbe)
            is GeStatusViewHolder -> holder.bind(getItem(position) as CheatSheetEntry.GeStatus)
            is NotesViewHolder -> holder.bind(getItem(position) as CheatSheetEntry.Notes)
        }
    }

    private class EndpointViewHolder(
        itemView: View,
        private val onVisit: (CheatSheetEntry.Endpoint) -> Unit,
        private val onPing: (CheatSheetEntry.Endpoint) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.txt_endpoint_title)
        private val urlView: TextView = itemView.findViewById(R.id.txt_endpoint_url)
        private val visitButton: Button = itemView.findViewById(R.id.btn_endpoint_visit)
        private val ipView: TextView = itemView.findViewById(R.id.txt_endpoint_ip)
        private val pingButton: Button = itemView.findViewById(R.id.btn_endpoint_ping)
        private val pingResultView: TextView = itemView.findViewById(R.id.txt_ping_result)

        fun bind(item: CheatSheetEntry.Endpoint) {
            titleView.text = item.title
            urlView.text = item.url
            ipView.text = item.ip
            pingResultView.text = item.pingResult

            pingButton.isEnabled = !item.isPingRunning
            pingButton.alpha = if (item.isPingRunning) 0.5f else 1.0f

            visitButton.setOnClickListener { onVisit(item) }
            pingButton.setOnClickListener {
                if (!item.isPingRunning) {
                    onPing(item)
                }
            }
        }
    }

    private class TcpProbeViewHolder(
        itemView: View,
        private val onProbe: (CheatSheetEntry.TcpProbe) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.txt_tcp_title)
        private val targetView: TextView = itemView.findViewById(R.id.txt_tcp_target)
        private val probeButton: Button = itemView.findViewById(R.id.btn_tcp_probe)
        private val resultView: TextView = itemView.findViewById(R.id.txt_tcp_result)

        fun bind(item: CheatSheetEntry.TcpProbe) {
            val context = itemView.context
            titleView.text = item.title
            targetView.text = context.getString(
                R.string.cheat_sheet_tcp_target,
                item.host,
                item.port
            )
            resultView.text = item.result
            probeButton.isEnabled = !item.isRunning
            probeButton.alpha = if (item.isRunning) 0.5f else 1f
            probeButton.setOnClickListener {
                if (!item.isRunning) {
                    onProbe(item)
                }
            }
        }
    }

    private class GeStatusViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.txt_ge_title)
        private val statusView: TextView = itemView.findViewById(R.id.txt_ge_status)
        private val ledView: View = itemView.findViewById(R.id.view_ge_led)

        fun bind(item: CheatSheetEntry.GeStatus) {
            val context = itemView.context
            titleView.text = item.title
            val (textRes, colorRes) = when (item.estado) {
                GeEstado.MARCHA -> R.string.cheat_sheet_ge_running to R.color.ge_led_marcha
                GeEstado.PARADO -> R.string.cheat_sheet_ge_stopped to R.color.ge_led_parado
                GeEstado.DESCONOCIDO -> R.string.cheat_sheet_ge_unknown to R.color.ge_led_unknown
            }
            statusView.text = context.getString(textRes)
            val tinted = DrawableCompat.wrap(ledView.background.mutate())
            DrawableCompat.setTint(tinted, ContextCompat.getColor(context, colorRes))
            ledView.background = tinted
        }
    }

    private class NotesViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val notesView: TextView = itemView.findViewById(R.id.txt_cheat_notes)

        fun bind(item: CheatSheetEntry.Notes) {
            notesView.text = item.text
        }
    }

    private companion object {
        private const val VIEW_TYPE_ENDPOINT = 1
        private const val VIEW_TYPE_NOTES = 2
        private const val VIEW_TYPE_TCP_PROBE = 3
        private const val VIEW_TYPE_GE_STATUS = 4

        private val DiffCallback = object : DiffUtil.ItemCallback<CheatSheetEntry>() {
            override fun areItemsTheSame(
                oldItem: CheatSheetEntry,
                newItem: CheatSheetEntry
            ): Boolean {
                return when {
                    oldItem is CheatSheetEntry.Endpoint && newItem is CheatSheetEntry.Endpoint ->
                        oldItem.id == newItem.id
                    oldItem is CheatSheetEntry.TcpProbe && newItem is CheatSheetEntry.TcpProbe ->
                        oldItem.id == newItem.id
                    oldItem is CheatSheetEntry.GeStatus && newItem is CheatSheetEntry.GeStatus ->
                        oldItem.id == newItem.id
                    oldItem is CheatSheetEntry.Notes && newItem is CheatSheetEntry.Notes -> true
                    else -> false
                }
            }

            override fun areContentsTheSame(
                oldItem: CheatSheetEntry,
                newItem: CheatSheetEntry
            ): Boolean = oldItem == newItem
        }
    }
}
