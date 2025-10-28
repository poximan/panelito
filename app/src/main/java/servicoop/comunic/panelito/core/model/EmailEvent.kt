package servicoop.comunic.panelito.core.model

import java.io.Serializable

data class EmailEvent(
    val type: String,
    val subject: String,
    val ok: Boolean,
    val timestamp: String
) : Serializable

