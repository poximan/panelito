package servicoop.comunic.panelito.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.util.TimestampFormatter
import servicoop.comunic.panelito.services.mqtt.MQTTService
import java.util.Locale
import kotlin.math.roundToInt

private val IPV4_REGEX = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

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
        lastPayload?.let { parseAndRender(it) } ?: run {
            empty.isVisible = true
            empty.text = getString(R.string.charo_list_empty)
            recycler.isVisible = false
        }
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(receiver)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastPayload?.let { outState.putString("charito_last_state", it) }
    }

    private fun parseAndRender(raw: String) {
        try {
            val obj = JSONObject(raw)
            val items = obj.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<CharoInstance>()
            for (i in 0 until items.length()) {
                val current = items.optJSONObject(i) ?: continue
                val alias = current.optString("alias")
                val id = current.optString("instanceId").ifBlank { current.optString("topicId") }
                if (id.isBlank()) continue
                val receivedAt = current.optString("receivedAt", current.optString("generatedAt", ""))
                val samples = current.optInt("samples", 0)
                val window = current.optLong("windowSeconds", 0L)
                val avgCpu = current.optDouble("averageCpuLoad", Double.NaN)
                val avgMemRatio = current.optDouble("averageMemoryUsageRatio", Double.NaN)
                val instCpu = current.optDouble("cpuLoadInstant", Double.NaN)
                val instMem = current.optDouble("memoryUsageInstant", Double.NaN)
                val tempInstant = current.optDouble("cpuTemperatureInstant", Double.NaN)
                val status = current.optString("status", "")
                val processes = extractProcesses(current)
                val interfaces = extractInterfaces(current)
                list.add(
                    CharoInstance(
                        instanceId = id,
                        receivedAt = receivedAt,
                        samples = samples,
                        windowSeconds = window,
                        avgCpuPercent = percentOrNull(avgCpu),
                        avgMemPercent = percentOrNull(avgMemRatio),
                        cpuInstantPercent = percentOrNull(instCpu),
                        memInstantPercent = percentOrNull(instMem),
                        cpuTempCelsius = tempInstant.takeIf { !it.isNaN() && it >= 0.0 },
                        status = status,
                        processes = processes,
                        alias = alias,
                        interfaces = interfaces,
                    )
                )
            }
            adapter.submitList(list)
            val hasItems = list.isNotEmpty()
            empty.isVisible = !hasItems
            if (!hasItems) {
                empty.text = getString(R.string.charo_list_empty)
            }
            recycler.isVisible = hasItems
        } catch (e: Exception) {
            empty.isVisible = true
            empty.text = getString(R.string.charo_list_empty)
            recycler.isVisible = false
        }
    }

    companion object {
        fun newInstance(): CharitoFragment = CharitoFragment()
    }

    private fun resolveSpanCount(): Int {
        val config = resources.configuration
        val sw = if (config.smallestScreenWidthDp > 0) config.smallestScreenWidthDp else config.screenWidthDp
        return if (sw >= 600) 2 else 1
    }

    private fun extractProcesses(entry: JSONObject): List<CharoProcess> {
        val sample = entry.optJSONObject("latestSample")
        val processesArray = sample?.optJSONArray("watchedProcesses")
            ?: entry.optJSONArray("watchedProcesses")
            ?: JSONArray()
        val result = mutableListOf<CharoProcess>()
        for (j in 0 until processesArray.length()) {
            val proc = processesArray.optJSONObject(j) ?: continue
            val name = proc.optString("processName", proc.optString("name", "")).trim()
            val state = if (proc.has("running") && !proc.isNull("running")) {
                if (proc.optBoolean("running")) CharoProcessState.ACTIVE else CharoProcessState.STOPPED
            } else {
                CharoProcessState.UNKNOWN
            }
            result.add(CharoProcess(name, state))
        }
        return result
    }

    private fun extractInterfaces(entry: JSONObject): List<CharoInterface> {
        val sample = entry.optJSONObject("latestSample")
        val array = sample?.optJSONArray("networkInterfaces")
            ?: entry.optJSONArray("networkInterfaces")
            ?: JSONArray()
        val result = mutableListOf<CharoInterface>()
        for (index in 0 until array.length()) {
            val iface = array.optJSONObject(index) ?: continue
            val ipv4 = findIpv4(iface.optJSONArray("addresses") ?: JSONArray()) ?: continue
            val name = iface.optString("displayName", iface.optString("name", "")).ifBlank {
                iface.optString("name", "")
            }
            if (name.isBlank()) continue
            val upState: Boolean? = if (iface.has("up") && !iface.isNull("up")) {
                iface.optBoolean("up")
            } else {
                null
            }
            result.add(
                CharoInterface(
                    name = name,
                    ipv4 = ipv4.first,
                    netmask = ipv4.second,
                    up = upState,
                    virtual = iface.optBoolean("virtual"),
                )
            )
        }
        return result
    }

    private fun findIpv4(addresses: JSONArray): Pair<String, String?>? {
        for (i in 0 until addresses.length()) {
            val obj = addresses.optJSONObject(i) ?: continue
            val address = obj.optString("address").trim()
            if (address.isBlank() || !IPV4_REGEX.matches(address)) continue
            val netmaskRaw = obj.optString("netmask").trim()
            val netmask = if (netmaskRaw.isNotEmpty() && IPV4_REGEX.matches(netmaskRaw)) netmaskRaw else null
            return address to netmask
        }
        return null
    }
}

data class CharoInstance(
    val instanceId: String,
    val receivedAt: String,
    val samples: Int,
    val windowSeconds: Long,
    val avgCpuPercent: Double?,
    val avgMemPercent: Double?,
    val cpuInstantPercent: Double?,
    val memInstantPercent: Double?,
    val cpuTempCelsius: Double?,
    val status: String,
    val processes: List<CharoProcess>,
    val alias: String?,
    val interfaces: List<CharoInterface>,
)

data class CharoProcess(
    val name: String,
    val state: CharoProcessState,
)

enum class CharoProcessState {
    ACTIVE,
    STOPPED,
    UNKNOWN
}

data class CharoInterface(
    val name: String,
    val ipv4: String,
    val netmask: String?,
    val up: Boolean?,
    val virtual: Boolean,
)

class CharitoInstanceAdapter : RecyclerView.Adapter<CharitoVH>() {
    private var items: List<CharoInstance> = emptyList()
    fun submitList(newItems: List<CharoInstance>) {
        items = newItems
        notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CharitoVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_charito_instance, parent, false)
        return CharitoVH(v)
    }
    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: CharitoVH, position: Int) {
        holder.bind(items[position])
    }
}

class CharitoVH(view: View) : RecyclerView.ViewHolder(view) {
    private val title: TextView = view.findViewById(R.id.txt_charito_tile_title)
    private val aliasLabel: TextView = view.findViewById(R.id.txt_charito_tile_alias)
    private val status: TextView = view.findViewById(R.id.txt_charito_tile_status)
    private val indicator: View = view.findViewById(R.id.indicator_charito_status)
    private val updated: TextView = view.findViewById(R.id.txt_charito_tile_updated)
    private val window: TextView = view.findViewById(R.id.txt_charito_tile_window)
    private val cpuAvgValue: TextView = view.findViewById(R.id.txt_charito_tile_cpu_avg_value)
    private val cpuAvgProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_cpu_avg)
    private val cpuInstValue: TextView = view.findViewById(R.id.txt_charito_tile_cpu_inst_value)
    private val cpuInstProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_cpu_inst)
    private val tempValue: TextView = view.findViewById(R.id.txt_charito_tile_temp_value)
    private val memAvgValue: TextView = view.findViewById(R.id.txt_charito_tile_memory_avg_value)
    private val memAvgProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_memory_avg)
    private val memInstValue: TextView = view.findViewById(R.id.txt_charito_tile_memory_inst_value)
    private val memInstProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_memory_inst)
    private val networkTitle: TextView = view.findViewById(R.id.txt_charito_network_title)
    private val networkContainer: LinearLayout = view.findViewById(R.id.container_charito_networks)
    private val processTitle: TextView = view.findViewById(R.id.txt_charito_processes_title)
    private val processContainer: LinearLayout = view.findViewById(R.id.container_charito_processes)

    fun bind(item: CharoInstance) {
        val ctx = itemView.context
        val displayId = item.instanceId.ifBlank { item.alias ?: ctx.getString(R.string.charo_instance_unknown_id) }
        title.text = displayId
        val aliasText = item.alias?.takeIf { it.isNotBlank() && it != displayId }
        aliasLabel.isVisible = !aliasText.isNullOrBlank()
        aliasLabel.text = aliasText ?: ""

        val statusText = when (item.status.lowercase(Locale.getDefault())) {
            "online" -> {
                indicator.setBackgroundResource(R.drawable.led_verde)
                ctx.getString(R.string.charo_status_online_short)
            }
            "offline" -> {
                indicator.setBackgroundResource(R.drawable.led_rojo)
                ctx.getString(R.string.charo_status_offline_short)
            }
            else -> {
                indicator.setBackgroundResource(R.drawable.led_naranja)
                ctx.getString(R.string.charo_status_unknown_short)
            }
        }
        status.text = ctx.getString(R.string.charo_status_label_prefix, statusText)

        val formattedTs = TimestampFormatter.format(item.receivedAt, ctx.getString(R.string.charo_time_unknown))
        updated.text = ctx.getString(R.string.charo_tile_updated_at, formattedTs)
        val samplesText = ctx.resources.getQuantityString(
            R.plurals.charo_row_samples,
            if (item.samples <= 0) 1 else item.samples,
            item.samples
        )
        window.text = ctx.getString(R.string.charo_tile_window_format, samplesText, item.windowSeconds)

        bindMetric(item.avgCpuPercent, cpuAvgValue, cpuAvgProgress, R.string.charo_cpu_na)
        bindMetric(item.cpuInstantPercent, cpuInstValue, cpuInstProgress, R.string.charo_cpu_na)
        tempValue.text = item.cpuTempCelsius?.let { ctx.getString(R.string.charo_temp_format, it) }
            ?: ctx.getString(R.string.charo_temp_na)
        bindMetric(item.avgMemPercent, memAvgValue, memAvgProgress, R.string.charo_mem_na)
        bindMetric(item.memInstantPercent, memInstValue, memInstProgress, R.string.charo_mem_na)
        renderNetworks(item.interfaces)
        renderProcesses(item.processes)
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
        val ctx = itemView.context
        networkContainer.removeAllViews()
        networkTitle.isVisible = true
        if (interfaces.isEmpty()) {
            val placeholder = TextView(ctx).apply {
                text = ctx.getString(R.string.charo_network_empty)
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_charo_process_unknown)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            networkContainer.addView(placeholder)
            return
        }
        interfaces.forEachIndexed { index, iface ->
            val statusTextRes = when (iface.up) {
                true -> R.string.charo_network_status_up
                false -> R.string.charo_network_status_down
                null -> R.string.charo_network_status_unknown
            }
            val statusText = ctx.getString(statusTextRes)
            val nameLabel = if (iface.virtual) {
                ctx.getString(R.string.charo_network_virtual_name, iface.name)
            } else {
                iface.name
            }
            val ipLabel = iface.netmask?.let { ctx.getString(R.string.charo_network_ip_mask, iface.ipv4, it) } ?: iface.ipv4
            val chipText = ctx.getString(R.string.charo_network_chip_text, nameLabel, statusText, ipLabel)
            val bgRes = when (iface.up) {
                true -> R.drawable.bg_charo_process_active
                false -> R.drawable.bg_charo_process_stopped
                null -> R.drawable.bg_charo_process_unknown
            }
            val textColor = if (iface.up == false) Color.BLACK else Color.WHITE
            val chip = TextView(ctx).apply {
                text = chipText
                setTextColor(textColor)
                background = ContextCompat.getDrawable(ctx, bgRes)
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                params.topMargin = dp(6)
            }
            chip.layoutParams = params
            networkContainer.addView(chip)
        }
    }

    private fun renderProcesses(processes: List<CharoProcess>) {
        val ctx = itemView.context
        processContainer.removeAllViews()
        processContainer.isVisible = true
        if (processes.isEmpty()) {
            val placeholder = TextView(ctx).apply {
                text = ctx.getString(R.string.charo_process_empty)
                setTextColor(Color.WHITE)
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_charo_process_unknown)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            placeholder.layoutParams = params
            processContainer.addView(placeholder)
            processTitle.isVisible = true
            return
        }
        processTitle.isVisible = true
        processes.forEachIndexed { index, process ->
            val stateTextRes = when (process.state) {
                CharoProcessState.ACTIVE -> R.string.charo_process_state_active
                CharoProcessState.STOPPED -> R.string.charo_process_state_stopped
                CharoProcessState.UNKNOWN -> R.string.charo_process_state_unknown
            }
            val bgRes = when (process.state) {
                CharoProcessState.ACTIVE -> R.drawable.bg_charo_process_active
                CharoProcessState.STOPPED -> R.drawable.bg_charo_process_stopped
                CharoProcessState.UNKNOWN -> R.drawable.bg_charo_process_unknown
            }
            val textColor = when (process.state) {
                CharoProcessState.STOPPED -> Color.BLACK
                else -> Color.WHITE
            }
            val name = process.name.ifBlank { ctx.getString(R.string.value_not_available) }
            val chip = TextView(ctx).apply {
                text = ctx.getString(R.string.charo_process_chip_text, name, ctx.getString(stateTextRes))
                setTextColor(textColor)
                background = ContextCompat.getDrawable(ctx, bgRes)
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (index > 0) {
                params.topMargin = dp(6)
            }
            chip.layoutParams = params
            processContainer.addView(chip)
        }
    }

    private fun dp(value: Int): Int =
        (value * itemView.resources.displayMetrics.density).roundToInt()
}

private fun percentOrNull(value: Double): Double? =
    if (!value.isNaN() && value >= 0.0) value * 100.0 else null
