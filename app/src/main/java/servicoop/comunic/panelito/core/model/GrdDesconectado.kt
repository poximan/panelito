package servicoop.comunic.panelito.core.model

data class GrdDesconectado(
    val id: Int,
    val nombre: String,
    val ultimaCaida: String // ISO-8601 preferido. Se tolera "yyyy-MM-dd HH:mm:ss" en parser
)