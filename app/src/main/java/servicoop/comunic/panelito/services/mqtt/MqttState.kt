package servicoop.comunic.panelito.services.mqtt

import servicoop.comunic.panelito.core.model.BrokerEstado
import servicoop.comunic.panelito.core.model.EmailEvent
import servicoop.comunic.panelito.core.model.GeEstado
import servicoop.comunic.panelito.core.model.ModemEstado

data class MqttState(
    val broker: BrokerEstado = BrokerEstado.DESCONECTADO,
    val modem: ModemEstado = ModemEstado.DESCONOCIDO,
    val gradoPct: Double? = null,
    val grdsJson: String? = null,
    val emailStateJson: String? = null,
    val proxmoxJson: String? = null,
    val charitoJson: String? = null,
    val emailEvents: List<EmailEvent> = emptyList(),
    val geStates: Map<String, GeEstado> = emptyMap(),
    val lechuOnline: Boolean? = null,
    val lechuStatusTimestamp: String? = null,
    val error: String? = null,
)
