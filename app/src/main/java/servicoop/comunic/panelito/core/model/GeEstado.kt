package servicoop.comunic.panelito.core.model

enum class GeEstado {
    LINEA_ABIERTA,
    LINEA_CERRADA,
    DESCONOCIDO;

    companion object {
        fun fromLineState(value: String, bit: Int? = null): GeEstado {
            if (bit == 1) return LINEA_CERRADA
            if (bit == 0) return LINEA_ABIERTA
            return when {
                value.equals("cerrado", ignoreCase = true) -> LINEA_CERRADA
                value.equals("abierto", ignoreCase = true) -> LINEA_ABIERTA
                else -> DESCONOCIDO
            }
        }
    }
}
