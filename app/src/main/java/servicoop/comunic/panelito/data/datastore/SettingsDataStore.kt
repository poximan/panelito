package servicoop.comunic.panelito.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
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
        private val KEY_MOBILE_UPDATE_OMISSIONS = intPreferencesKey("mobile_update_omissions")
    }

    override fun getEmailEvents(): Flow<List<EmailEvent>> = context.dataStore.data.map { prefs ->
        decodeEmailEvents(prefs[KEY_EMAIL_EVENTS].orEmpty())
    }

    override suspend fun saveEmailEvents(events: List<EmailEvent>) {
        val payload = JSONArray()
        events.take(50).forEach { event ->
            payload.put(
                JSONObject()
                    .put("id", event.id)
                    .put("type", event.type)
                    .put("subject", event.subject)
                    .put("status", event.status)
                    .put("timestamp", event.timestamp)
                    .put("detail", event.detail),
            )
        }
        context.dataStore.edit { prefs -> prefs[KEY_EMAIL_EVENTS] = payload.toString() }
    }

    override fun getMobileUpdateOmissions(): Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_MOBILE_UPDATE_OMISSIONS] ?: 0).coerceAtLeast(0)
    }

    override suspend fun saveMobileUpdateOmissions(value: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_MOBILE_UPDATE_OMISSIONS] = value.coerceAtLeast(0) }
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
                            id = item.optString("id"),
                            type = item.optString("type", "email"),
                            subject = item.optString("subject"),
                            status = item.optString("status").ifBlank {
                                if (item.optBoolean("ok", false)) "sent" else "failed"
                            },
                            timestamp = timestamp,
                            detail = item.optString("detail"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
