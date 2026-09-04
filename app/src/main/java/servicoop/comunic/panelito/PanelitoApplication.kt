package servicoop.comunic.panelito

import android.app.Application
import servicoop.comunic.panelito.services.mqtt.MqttSession

class PanelitoApplication : Application() {
    val mqttSession: MqttSession by lazy { MqttSession(this) }
}
