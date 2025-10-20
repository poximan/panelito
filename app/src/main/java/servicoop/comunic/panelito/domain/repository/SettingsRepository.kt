package servicoop.comunic.panelito.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Abstraccion de origen de settings.
 * La UI solo conoce esta interfaz.
 */
interface SettingsRepository {
    /** emite el estado del flag service_enabled */
    fun getServiceEnabled(): Flow<Boolean>

    /** setea el flag service_enabled */
    suspend fun setServiceEnabled(enabled: Boolean)

    /**
     * Garantiza defaults de primera ejecucion:
     * - si no hay init_done, setea service_enabled=true y marca init_done=true
     */
    suspend fun ensureDefaults()
}