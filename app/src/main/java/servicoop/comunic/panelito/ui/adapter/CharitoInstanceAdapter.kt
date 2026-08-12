package servicoop.comunic.panelito.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.CharoInstance
import servicoop.comunic.panelito.core.model.CharoInterface
import servicoop.comunic.panelito.core.model.CharoProcess
import servicoop.comunic.panelito.core.model.CharoProcessState
import servicoop.comunic.panelito.core.time.AppTime
import java.util.Locale
import kotlin.math.roundToInt

class CharitoInstanceAdapter : RecyclerView.Adapter<CharitoViewHolder>() {
    private var items: List<CharoInstance> = emptyList()
    private val expandedState = mutableMapOf<String, Boolean>()

    fun submitList(newItems: List<CharoInstance>) {
        val ids = newItems.map { it.instanceId }.toSet()
        expandedState.keys.retainAll(ids)
        newItems.forEach { expandedState.putIfAbsent(it.instanceId, false) }
        items = newItems
        notifyDataSetChanged()
    }

    fun resetExpandedState() {
        expandedState.keys.forEach { expandedState[it] = false }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharitoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_charito_instance, parent, false)
        return CharitoViewHolder(view) { instanceId ->
            expandedState[instanceId] = !(expandedState[instanceId] ?: false)
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: CharitoViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, expandedState[item.instanceId] ?: false)
    }
}

class CharitoViewHolder(view: View, private val onToggle: (String) -> Unit) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.txt_charito_tile_title)
    private val aliasLabel: TextView = view.findViewById(R.id.txt_charito_tile_alias)
    private val status: TextView = view.findViewById(R.id.txt_charito_tile_status)
    private val indicator: View = view.findViewById(R.id.indicator_charito_status)
    private val updated: TextView = view.findViewById(R.id.txt_charito_tile_updated)
    private val window: TextView = view.findViewById(R.id.txt_charito_tile_window)
    private val cpuValue: TextView = view.findViewById(R.id.txt_charito_tile_cpu_value)
    private val cpuProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_cpu)
    private val tempValue: TextView = view.findViewById(R.id.txt_charito_tile_temp_value)
    private val memValue: TextView = view.findViewById(R.id.txt_charito_tile_memory_value)
    private val memProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_memory)
    private val networkTitle: TextView = view.findViewById(R.id.txt_charito_network_title)
    private val networkContainer: LinearLayout = view.findViewById(R.id.container_charito_networks)
    private val processTitle: TextView = view.findViewById(R.id.txt_charito_processes_title)
    private val processContainer: LinearLayout = view.findViewById(R.id.container_charito_processes)
    private val detailsContainer: LinearLayout = view.findViewById(R.id.charito_details_container)
    private val toggleIcon: ImageView = view.findViewById(R.id.img_charito_toggle)

    fun bind(item: CharoInstance, expanded: Boolean) {
        val context = itemView.context
        val displayId = item.instanceId.ifBlank { item.alias ?: context.getString(R.string.charo_instance_unknown_id) }
        title.text = displayId
        val aliasText = item.alias?.takeIf { it.isNotBlank() && it != displayId }
        aliasLabel.isVisible = !aliasText.isNullOrBlank()
        aliasLabel.text = aliasText ?: ""

        val statusText = when (item.status.lowercase(Locale.getDefault())) {
            "online" -> {
                indicator.setBackgroundResource(R.drawable.led_verde)
                context.getString(R.string.charo_status_online_short)
            }
            "offline" -> {
                indicator.setBackgroundResource(R.drawable.led_rojo)
                context.getString(R.string.charo_status_offline_short)
            }
            "error" -> {
                indicator.setBackgroundResource(R.drawable.led_naranja)
                context.getString(R.string.charo_status_error_short)
            }
            else -> {
                indicator.setBackgroundResource(R.drawable.led_naranja)
                context.getString(R.string.charo_status_unknown_short)
            }
        }
        status.text = context.getString(R.string.charo_status_label_prefix, statusText)

        val formattedTs = AppTime.formatForPresentation(
            item.receivedAt,
            context.getString(R.string.charo_time_unknown),
        )
        updated.text = context.getString(R.string.charo_tile_updated_at, formattedTs)
        val samplesText = context.resources.getQuantityString(
            R.plurals.charo_row_samples,
            if (item.samples <= 0) 1 else item.samples,
            item.samples
        )
        window.text = context.getString(R.string.charo_tile_window_format, samplesText, item.windowSeconds)

        bindMetric(item.cpuPercent, cpuValue, cpuProgress, R.string.charo_cpu_na)
        tempValue.text = item.cpuTempCelsius?.let { context.getString(R.string.charo_temp_format, it) }
            ?: context.getString(R.string.charo_temp_na)
        bindMetric(item.memPercent, memValue, memProgress, R.string.charo_mem_na)
        renderNetworks(item.interfaces)
        renderProcesses(item.processes)
        detailsContainer.isVisible = expanded
        toggleIcon.rotation = if (expanded) 180f else 0f
        itemView.setOnClickListener { onToggle(item.instanceId) }
    }

    private fun bindMetric(
        value: Double?,
        label: TextView,
        progressBar: android.widget.ProgressBar,
        fallbackRes: Int
    ) {
        if (value != null && value >= 0) {
            label.text = itemView.context.getString(R.string.percent_format, value)
            progressBar.isIndeterminate = false
            progressBar.progress = value.coerceIn(0.0, 100.0).toInt()
            progressBar.alpha = 1f
        } else {
            label.text = itemView.context.getString(fallbackRes)
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressBar.alpha = 0.3f
        }
    }

    private fun renderNetworks(interfaces: List<CharoInterface>) {
        val context = itemView.context
        networkContainer.removeAllViews()
        networkTitle.isVisible = true
        if (interfaces.isEmpty()) {
            val placeholder = TextView(context).apply {
                text = context.getString(R.string.charo_network_empty)
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(context, R.drawable.bg_charo_process_unknown)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            networkContainer.addView(placeholder)
            return
        }
        interfaces.forEachIndexed { index, network ->
            val statusText = context.getString(
                when (network.up) {
                    true -> R.string.charo_network_status_up
                    false -> R.string.charo_network_status_down
                    null -> R.string.charo_network_status_unknown
                }
            )
            val name = if (network.virtual) {
                context.getString(R.string.charo_network_virtual_name, network.name)
            } else {
                network.name
            }
            val ip = network.netmask?.let {
                context.getString(R.string.charo_network_ip_mask, network.ipv4, it)
            } ?: network.ipv4
            val chip = TextView(context).apply {
                text = context.getString(R.string.charo_network_chip_text, name, statusText, ip)
                setTextColor(if (network.up == false) Color.BLACK else Color.WHITE)
                background = ContextCompat.getDrawable(
                    context,
                    when (network.up) {
                        true -> R.drawable.bg_charo_process_active
                        false -> R.drawable.bg_charo_process_stopped
                        null -> R.drawable.bg_charo_process_unknown
                    }
                )
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            chip.layoutParams = verticalParams(index)
            networkContainer.addView(chip)
        }
    }

    private fun renderProcesses(processes: List<CharoProcess>) {
        val context = itemView.context
        processContainer.removeAllViews()
        processContainer.isVisible = true
        processTitle.isVisible = true
        if (processes.isEmpty()) {
            val placeholder = TextView(context).apply {
                text = context.getString(R.string.charo_process_empty)
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(context, R.drawable.bg_charo_process_unknown)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = verticalParams(0)
            }
            processContainer.addView(placeholder)
            return
        }
        processes.forEachIndexed { index, process ->
            val stateText = context.getString(
                when (process.state) {
                    CharoProcessState.ACTIVE -> R.string.charo_process_state_active
                    CharoProcessState.STOPPED -> R.string.charo_process_state_stopped
                    CharoProcessState.UNKNOWN -> R.string.charo_process_state_unknown
                }
            )
            val chip = TextView(context).apply {
                val name = process.name.ifBlank { context.getString(R.string.value_not_available) }
                text = context.getString(R.string.charo_process_chip_text, name, stateText)
                setTextColor(if (process.state == CharoProcessState.STOPPED) Color.BLACK else Color.WHITE)
                background = ContextCompat.getDrawable(
                    context,
                    when (process.state) {
                        CharoProcessState.ACTIVE -> R.drawable.bg_charo_process_active
                        CharoProcessState.STOPPED -> R.drawable.bg_charo_process_stopped
                        CharoProcessState.UNKNOWN -> R.drawable.bg_charo_process_unknown
                    }
                )
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            chip.layoutParams = verticalParams(index)
            processContainer.addView(chip)
        }
    }

    private fun verticalParams(index: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        if (index > 0) topMargin = dp(6)
    }

    private fun dp(value: Int): Int =
        (value * itemView.resources.displayMetrics.density).roundToInt()
}
