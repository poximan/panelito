package servicoop.comunic.panelito.core.model

data class ProxmoxVm(
    val vmid: Int,
    val name: String,
    val status: String,
    val statusDisplay: String,
    val cpus: Int,
    val cpuPct: Double,
    val memUsedGb: Double,
    val memTotalGb: Double,
    val memPct: Double?,
    val diskUsedGb: Double,
    val diskTotalGb: Double,
    val diskPct: Double?,
    val diskReadBytes: Double,
    val diskWriteBytes: Double,
    val diskReadRateBps: Double,
    val diskWriteRateBps: Double,
    val uptime: String
)

data class ProxmoxState(
    val status: String,
    val timestamp: String,
    val node: String,
    val error: String?,
    val missing: List<Int>,
    val vms: List<ProxmoxVm>
)
