package servicoop.comunic.redirectorllamadas.mqtt

/**
 * Estados tipados para evitar strings magicos en intents y UI.
 */
enum class BrokerEstado {
    CONECTADO,
    DESCONECTADO,
    CONECTANDO,
    REINTENTANDO,
    ERROR
}

enum class ModemEstado {
    CONECTADO,
    DESCONECTADO
}
