package servicoop.comunic.panelito.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import servicoop.comunic.panelito.repository.SettingsRepository
import servicoop.comunic.panelito.core.model.EmailEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Implementacion de SettingsRepository con Preferences DataStore.
 */
private val Context.dataStore by preferencesDataStore(name = "panelito_settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {

    companion object {
        private val KEY_EMAIL_EVENTS = stringPreferencesKey("email_events")
    }

    override fun getEmailEvents(): Flow<List<EmailEvent>> = context.dataStore.data.map { prefs ->
        decodeEmailEvents(prefs[KEY_EMAIL_EVENTS].orEmpty())
    }

    override suspend fun saveEmailEvents(events: List<EmailEvent>) {
        val payload = JSONArray()
        events.take(50).forEach { event ->
            payload.put(
                JSONObject()
                    .put("type", event.type)
                    .put("subject", event.subject)
                    .put("ok", event.ok)
                    .put("timestamp", event.timestamp)
                    .put("detail", event.detail),
            )
        }
        context.dataStore.edit { prefs -> prefs[KEY_EMAIL_EVENTS] = payload.toString() }
    }

    private fun decodeEmailEvents(raw: String): List<EmailEvent> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val timestamp = item.optString("timestamp")
                    if (timestamp.isBlank()) continue
                    add(
                        EmailEvent(
                            type = item.optString("type", "email"),
                            subject = item.optString("subject"),
                            ok = item.optBoolean("ok", false),
                            timestamp = timestamp,
                            detail = item.optString("detail"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
