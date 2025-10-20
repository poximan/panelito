package servicoop.comunic.panelito.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import servicoop.comunic.panelito.domain.repository.SettingsRepository

/**
 * Implementacion de SettingsRepository con Preferences DataStore.
 * Usa una bandera init_done para diferenciar primera ejecucion.
 */
private val Context.dataStore by preferencesDataStore(name = "panelito_settings")

class SettingsDataStore(private val context: Context) : SettingsRepository {

    companion object {
        private val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        private val KEY_INIT_DONE = booleanPreferencesKey("init_done")
    }

    override fun getServiceEnabled(): Flow<Boolean> {
        return context.dataStore.data.map { prefs ->
            prefs[KEY_SERVICE_ENABLED] ?: false
        }
    }

    override suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVICE_ENABLED] = enabled
        }
    }

    override suspend fun ensureDefaults() {
        val prefs: Preferences = context.dataStore.data.first()
        val init = prefs[KEY_INIT_DONE] ?: false
        if (!init) {
            context.dataStore.edit { p ->
                // primer arranque: default ON como pidio el usuario
                p[KEY_SERVICE_ENABLED] = true
                p[KEY_INIT_DONE] = true
            }
        }
    }
}