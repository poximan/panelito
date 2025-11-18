package servicoop.comunic.panelito.core.model

data class ProxmoxState(
    val status: String,
    val timestamp: String,
    val node: String,
    val error: String?,
    val missing: List<Int>,
    val vms: List<ProxmoxVm>
)
