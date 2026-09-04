package servicoop.comunic.panelito.repository

import kotlinx.coroutines.flow.Flow
import servicoop.comunic.panelito.core.model.EmailEvent

/**
 * Abstraccion de origen de settings.
 * La UI solo conoce esta interfaz.
 */
interface SettingsRepository {
    fun getEmailEvents(): Flow<List<EmailEvent>>

    suspend fun saveEmailEvents(events: List<EmailEvent>)
}
