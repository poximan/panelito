package servicoop.comunic.panelito.core.model

enum class GeEstado {
    MARCHA,
    PARADO,
    DESCONOCIDO;

    companion object {
        fun fromString(value: String): GeEstado {
            return when {
                value.equals("marcha", ignoreCase = true) -> MARCHA
                value.equals("parado", ignoreCase = true) -> PARADO
                else -> DESCONOCIDO
            }
        }
    }
}
