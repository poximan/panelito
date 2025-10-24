package servicoop.comunic.panelito.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import servicoop.comunic.panelito.R
import servicoop.comunic.panelito.core.model.ProxmoxVm
import kotlin.math.roundToInt

class ProxmoxVmAdapter : ListAdapter<ProxmoxVm, ProxmoxVmAdapter.ProxmoxVmViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxmoxVmViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_proxmox_vm, parent, false)
        return ProxmoxVmViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProxmoxVmViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ProxmoxVmViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.txt_vm_name)
        private val statusText: TextView = itemView.findViewById(R.id.txt_vm_status)
        private val indicator: View = itemView.findViewById(R.id.indicator_vm_status)
        private val cpuProgress: ProgressBar = itemView.findViewById(R.id.progress_cpu)
        private val cpuValue: TextView = itemView.findViewById(R.id.txt_cpu_value)
        private val memProgress: ProgressBar = itemView.findViewById(R.id.progress_memory)
        private val memValue: TextView = itemView.findViewById(R.id.txt_memory_value)
        private val diskProgress: ProgressBar = itemView.findViewById(R.id.progress_disk)
        private val diskValue: TextView = itemView.findViewById(R.id.txt_disk_value)
        private val cpusText: TextView = itemView.findViewById(R.id.txt_vm_cpus)
        private val uptimeText: TextView = itemView.findViewById(R.id.txt_vm_uptime)

        fun bind(vm: ProxmoxVm) {
            val context = itemView.context
            nameText.text = context.getString(R.string.proxmox_vm_name_format, vm.vmid, vm.name)
            statusText.text = vm.statusDisplay

            when (vm.status.lowercase()) {
                "running" -> indicator.setBackgroundResource(R.drawable.led_verde)
                "paused", "suspended" -> indicator.setBackgroundResource(R.drawable.led_naranja)
                else -> indicator.setBackgroundResource(R.drawable.led_rojo)
            }

            cpuProgress.progress = vm.cpuPct.coerceIn(0.0, 100.0).roundToInt()
            cpuValue.text = context.getString(R.string.proxmox_cpu_value, vm.cpuPct)

            val memPct = if (vm.memTotalGb > 0.0) (vm.memUsedGb / vm.memTotalGb * 100.0).coerceIn(0.0, 100.0) else null
            memProgress.progress = (memPct ?: 0.0).roundToInt()
            memValue.text = if (memPct != null) {
                context.getString(R.string.proxmox_memory_value, vm.memUsedGb, vm.memTotalGb, memPct)
            } else {
                context.getString(R.string.value_not_available)
            }

            val diskPct = if (vm.diskTotalGb > 0.0) (vm.diskUsedGb / vm.diskTotalGb * 100.0).coerceIn(0.0, 100.0) else null
            diskProgress.progress = (diskPct ?: 0.0).roundToInt()
            diskValue.text = if (diskPct != null) {
                context.getString(R.string.proxmox_disk_value, vm.diskUsedGb, vm.diskTotalGb, diskPct)
            } else {
                context.getString(R.string.value_not_available)
            }

            cpusText.text = context.getString(R.string.proxmox_cpus_label, vm.cpus)
            uptimeText.text = context.getString(R.string.proxmox_uptime_label, vm.uptime)
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<ProxmoxVm>() {
        override fun areItemsTheSame(oldItem: ProxmoxVm, newItem: ProxmoxVm): Boolean = oldItem.vmid == newItem.vmid
        override fun areContentsTheSame(oldItem: ProxmoxVm, newItem: ProxmoxVm): Boolean = oldItem == newItem
    }
}
