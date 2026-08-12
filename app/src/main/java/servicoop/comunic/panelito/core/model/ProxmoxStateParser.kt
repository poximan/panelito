package servicoop.comunic.panelito.core.model

import org.json.JSONArray
import org.json.JSONObject

object ProxmoxStateParser {
    fun parse(raw: String): ProxmoxState {
        val source = JSONObject(raw)
        val missingSource = source.optJSONArray("missing") ?: JSONArray()
        val missing = buildList {
            for (index in 0 until missingSource.length()) add(missingSource.optInt(index))
        }
        val vmSource = source.optJSONArray("vms") ?: JSONArray()
        val vms = buildList {
            for (index in 0 until vmSource.length()) {
                val vm = vmSource.optJSONObject(index) ?: continue
                val vmid = vm.optInt("vmid")
                val status = vm.optString("status", "desconocido")
                add(
                    ProxmoxVm(
                        vmid = vmid,
                        name = vm.optString("name", "VM $vmid"),
                        status = status,
                        statusDisplay = vm.optString("status_display", status.uppercase()),
                        cpus = vm.optInt("cpus"),
                        cpuPct = vm.optDouble("cpu_pct", 0.0),
                        memUsedGb = vm.optDouble("mem_used_gb", 0.0),
                        memTotalGb = vm.optDouble("mem_total_gb", 0.0),
                        memPct = optionalDouble(vm, "mem_pct"),
                        diskUsedGb = vm.optDouble("disk_used_gb", 0.0),
                        diskTotalGb = vm.optDouble("disk_total_gb", 0.0),
                        diskPct = optionalDouble(vm, "disk_pct"),
                        diskReadBytes = vm.optDouble("disk_read_bytes", 0.0),
                        diskWriteBytes = vm.optDouble("disk_write_bytes", 0.0),
                        diskReadRateBps = vm.optDouble("disk_read_rate_bps", 0.0),
                        diskWriteRateBps = vm.optDouble("disk_write_rate_bps", 0.0),
                        uptime = vm.optString("uptime_human", "0m"),
                    )
                )
            }
        }
        return ProxmoxState(
            status = source.optString("status", "offline"),
            timestamp = source.optString("ts", ""),
            node = source.optString("node", ""),
            error = source.optString("error", "").takeIf { it.isNotBlank() },
            missing = missing,
            vms = vms,
        )
    }

    private fun optionalDouble(source: JSONObject, key: String): Double? =
        source.optDouble(key, Double.NaN).takeUnless { it.isNaN() }
}
