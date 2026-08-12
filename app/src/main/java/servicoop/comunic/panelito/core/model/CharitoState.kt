package servicoop.comunic.panelito.core.model

data class CharoInstance(
    val instanceId: String,
    val receivedAt: String,
    val samples: Int,
    val windowSeconds: Long,
    val cpuPercent: Double?,
    val memPercent: Double?,
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
