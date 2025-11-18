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
                val id = current.optString("instanceId").ifBlank { current.optString("topicId") }
                if (id.isBlank()) continue
                val receivedAt = current.optString("receivedAt", current.optString("generatedAt", ""))
                val samples = current.optInt("samples", current.optInt("sampleCount", 0))
                val window = current.optLong("windowSeconds", current.optLong("windowDurationSeconds", 0L))
                val avgCpu = current.optDouble("averageCpuLoad", Double.NaN)
                val avgMemRatio = current.optDouble("averageMemoryUsageRatio", Double.NaN)
                val status = current.optString("status", "")
                val processes = extractProcesses(current)
                list.add(
                    CharoInstance(
                        instanceId = id,
                        receivedAt = receivedAt,
                        samples = samples,
                        windowSeconds = window,
                        avgCpuPercent = if (!avgCpu.isNaN() && avgCpu >= 0.0) avgCpu * 100.0 else null,
                        avgMemPercent = if (!avgMemRatio.isNaN() && avgMemRatio >= 0.0) avgMemRatio * 100.0 else null,
                        status = status,
                        processes = processes,
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
}

data class CharoInstance(
    val instanceId: String,
    val receivedAt: String,
    val samples: Int,
    val windowSeconds: Long,
    val avgCpuPercent: Double?,
    val avgMemPercent: Double?,
    val status: String,
    val processes: List<CharoProcess>,
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
    private val status: TextView = view.findViewById(R.id.txt_charito_tile_status)
    private val indicator: View = view.findViewById(R.id.indicator_charito_status)
    private val updated: TextView = view.findViewById(R.id.txt_charito_tile_updated)
    private val window: TextView = view.findViewById(R.id.txt_charito_tile_window)
    private val cpuValue: TextView = view.findViewById(R.id.txt_charito_tile_cpu_value)
    private val cpuProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_cpu)
    private val memValue: TextView = view.findViewById(R.id.txt_charito_tile_memory_value)
    private val memProgress: android.widget.ProgressBar = view.findViewById(R.id.progress_charito_memory)
    private val processTitle: TextView = view.findViewById(R.id.txt_charito_processes_title)
    private val processContainer: LinearLayout = view.findViewById(R.id.container_charito_processes)

    fun bind(item: CharoInstance) {
        val ctx = itemView.context
        val displayId = item.instanceId.ifBlank { ctx.getString(R.string.charo_instance_unknown_id) }
        title.text = displayId

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

        bindMetric(item.avgCpuPercent, cpuValue, cpuProgress, ctx, true)
        bindMetric(item.avgMemPercent, memValue, memProgress, ctx, false)
        renderProcesses(item.processes)
    }

    private fun bindMetric(
        value: Double?,
        label: TextView,
        progressBar: android.widget.ProgressBar,
        ctx: Context,
        isCpu: Boolean
    ) {
        if (value != null && value >= 0) {
            val text = if (isCpu) ctx.getString(R.string.charo_cpu_value, value)
            else ctx.getString(R.string.charo_mem_value, value)
            label.text = text
            progressBar.isIndeterminate = false
            progressBar.progress = value.coerceIn(0.0, 100.0).toInt()
            progressBar.alpha = 1f
        } else {
            label.text = if (isCpu) ctx.getString(R.string.charo_cpu_na) else ctx.getString(R.string.charo_mem_na)
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            progressBar.alpha = 0.3f
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
