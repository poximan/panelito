package servicoop.comunic.panelito.core.model

enum class WakeOnLanStatus {
    IDLE,
    REQUESTING,
    PACKET_SENT,
    SSH_OPEN,
    ERROR,
}

data class WakeOnLanState(
    val status: WakeOnLanStatus = WakeOnLanStatus.IDLE,
    val detail: String? = null,
)
