package servicoop.comunic.panelito.core.model

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
