package servicoop.comunic.panelito.core.model

/**
 * Estados tipados para evitar strings magicos en intents y UI.
 */

enum class ModemEstado {
    ABIERTO,
    CERRADO,
    DESCONOCIDO;

    companion object {
        fun fromString(value: String): ModemEstado {
            return when {
                value.equals("abierto", ignoreCase = true) -> ABIERTO
                value.equals("cerrado", ignoreCase = true) -> CERRADO
                value.equals("desconocido", ignoreCase = true) -> DESCONOCIDO
                else -> DESCONOCIDO
            }
        }
    }
}
