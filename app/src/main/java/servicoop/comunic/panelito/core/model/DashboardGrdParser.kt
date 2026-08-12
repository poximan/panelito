package servicoop.comunic.panelito.core.model

import org.json.JSONObject

object DashboardGrdParser {
    fun parse(raw: String, fallbackName: String): List<GrdDesconectado> {
        val items = JSONObject(raw).getJSONArray("items")
        return buildList {
            for (index in 0 until items.length()) {
                val source = items.getJSONObject(index)
                add(
                    GrdDesconectado(
                        id = source.optInt("id"),
                        nombre = source.optString("nombre", fallbackName),
                        ultimaCaida = source.optString("ultima_caida", ""),
                    )
                )
            }
        }
    }
}
