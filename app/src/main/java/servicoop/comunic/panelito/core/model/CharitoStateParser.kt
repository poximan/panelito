package servicoop.comunic.panelito.core.model

import org.json.JSONArray
import org.json.JSONObject

object CharitoStateParser {
    private val ipv4Regex = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun parse(raw: String): List<CharoInstance> {
        val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val current = items.optJSONObject(index) ?: continue
                val instanceId = current.optString("instanceId")
                if (instanceId.isBlank()) continue
                add(
                    CharoInstance(
                        instanceId = instanceId,
                        receivedAt = current.optString(
                            "receivedAt",
                            current.optString("generatedAt", ""),
                        ),
                        samples = current.optInt("samples", 0),
                        windowSeconds = current.optLong("windowSeconds", 0L),
                        cpuPercent = percentOrNull(current.optDouble("cpuLoad", Double.NaN)),
                        memPercent = percentOrNull(current.optDouble("memoryUsageRatio", Double.NaN)),
                        cpuTempCelsius = current.optDouble("cpuTemperatureCelsius", Double.NaN)
                            .takeIf { !it.isNaN() && it >= 0.0 },
                        status = current.optString("status", ""),
                        processes = extractProcesses(current),
                        alias = current.optString("alias"),
                        interfaces = extractInterfaces(current),
                    )
                )
            }
        }
    }

    private fun extractProcesses(entry: JSONObject): List<CharoProcess> {
        val sample = entry.optJSONObject("latestSample")
        val processes = sample?.optJSONArray("watchedProcesses")
            ?: entry.optJSONArray("watchedProcesses")
            ?: JSONArray()
        return buildList {
            for (index in 0 until processes.length()) {
                val process = processes.optJSONObject(index) ?: continue
                val state = if (process.has("running") && !process.isNull("running")) {
                    if (process.optBoolean("running")) CharoProcessState.ACTIVE else CharoProcessState.STOPPED
                } else {
                    CharoProcessState.UNKNOWN
                }
                add(
                    CharoProcess(
                        name = process.optString("processName", process.optString("name", "")).trim(),
                        state = state,
                    )
                )
            }
        }
    }

    private fun extractInterfaces(entry: JSONObject): List<CharoInterface> {
        val sample = entry.optJSONObject("latestSample")
        val interfaces = sample?.optJSONArray("networkInterfaces")
            ?: entry.optJSONArray("networkInterfaces")
            ?: JSONArray()
        return buildList {
            for (index in 0 until interfaces.length()) {
                val source = interfaces.optJSONObject(index) ?: continue
                val ipv4 = findIpv4(source.optJSONArray("addresses") ?: JSONArray()) ?: continue
                val name = source.optString("displayName", source.optString("name", "")).ifBlank {
                    source.optString("name", "")
                }
                if (name.isBlank()) continue
                add(
                    CharoInterface(
                        name = name,
                        ipv4 = ipv4.first,
                        netmask = ipv4.second,
                        up = if (source.has("up") && !source.isNull("up")) source.optBoolean("up") else null,
                        virtual = source.optBoolean("virtual"),
                    )
                )
            }
        }
    }

    private fun findIpv4(addresses: JSONArray): Pair<String, String?>? {
        for (index in 0 until addresses.length()) {
            val source = addresses.optJSONObject(index) ?: continue
            val address = source.optString("address").trim()
            if (address.isBlank() || !ipv4Regex.matches(address)) continue
            val rawNetmask = source.optString("netmask").trim()
            val netmask = rawNetmask.takeIf { it.isNotEmpty() && ipv4Regex.matches(it) }
            return address to netmask
        }
        return null
    }

    private fun percentOrNull(value: Double): Double? =
        if (!value.isNaN() && value >= 0.0) value * 100.0 else null
}
